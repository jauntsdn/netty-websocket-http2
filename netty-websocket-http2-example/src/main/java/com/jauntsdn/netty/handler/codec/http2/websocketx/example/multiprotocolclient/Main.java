/*
 * Copyright 2022 - present Maksym Ostroverkhov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jauntsdn.netty.handler.codec.http2.websocketx.example.multiprotocolclient;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandshaker;
import com.jauntsdn.netty.handler.codec.http2.websocketx.example.Security;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCounted;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    String host = System.getProperty("HOST", "localhost");
    int port = Integer.parseInt(System.getProperty("PORT", "8099"));
    InetSocketAddress address = new InetSocketAddress(host, port);

    logger.info("\n==> Websocket-over-http1 and websocket-over-http2 clients\n");
    logger.info("\n==> Remote address: {}:{}", host, port);

    EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    SslContext http2SslContext = Security.clientLocalSslContextHttp2();
    SslContext http1SslContext = Security.clientLocalSslContextHttp1();

    EchoWebSocketHandler echoWebSocketHandler = new EchoWebSocketHandler();

    WebSocketDecoderConfig decoderConfig =
        WebSocketDecoderConfig.newBuilder()
            .expectMaskedFrames(false)
            .allowMaskMismatch(false)
            .allowExtensions(true)
            .build();

    Channel http2Channel =
        new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel.class)
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {
                    SslHandler sslHandler = http2SslContext.newHandler(ch.alloc());
                    Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forClient();
                    frameCodecBuilder.initialSettings().initialWindowSize(1_000);
                    Http2FrameCodec http2FrameCodec = frameCodecBuilder.build();

                    Http2WebSocketClientHandler http2WebSocketClientHandler =
                        Http2WebSocketClientBuilder.create()
                            .decoderConfig(decoderConfig)
                            .handshakeTimeoutMillis(15_000)
                            .compression(true)
                            .build();

                    ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                  }
                })
            .connect(address)
            .sync()
            .channel();

    /*wait until channel is ready (future completes)*/
    Http2WebSocketClientHandshaker http2WebSocketHandShaker =
        Http2WebSocketClientHandshaker.create(http2Channel);

    Http2Headers http2Headers =
        new DefaultHttp2Headers().set("user-agent", "jauntsdn-websocket-http2-client/1.1.4");
    ChannelFuture http2WebSocketHandshake =
        http2WebSocketHandShaker.handshake(
            "/echo", "echo.jauntsdn.com", http2Headers, echoWebSocketHandler);

    Channel http2WebSocketChannel = http2WebSocketHandshake.channel();

    /*send websocket frames*/
    http2WebSocketChannel
        .eventLoop()
        .scheduleAtFixedRate(
            () ->
                http2WebSocketChannel.writeAndFlush(
                    new TextWebSocketFrame("hello http2 websocket")),
            0,
            2_000,
            TimeUnit.MILLISECONDS);

    HttpHeaders http1Headers =
        new DefaultHttpHeaders().set("user-agent", "jauntsdn-websocket-http1-client/1.1.4");

    Http1WebSocketHandshaker http1WebSocketHandshaker =
        new Http1WebSocketHandshaker(
            "/echo", "echo.jauntsdn.com", http1Headers, decoderConfig, host, port);

    Channel http1Channel =
        new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel.class)
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {
                    SslHandler sslHandler = http1SslContext.newHandler(ch.alloc());
                    HttpClientCodec http1Codec = new HttpClientCodec();
                    HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);
                    WebSocketClientCompressionHandler http1WebSocketCompressionHandler =
                        WebSocketClientCompressionHandler.INSTANCE;

                    ch.pipeline()
                        .addLast(
                            sslHandler,
                            http1Codec,
                            http1Aggregator,
                            http1WebSocketCompressionHandler,
                            http1WebSocketHandshaker,
                            echoWebSocketHandler);
                  }
                })
            .connect(address)
            .sync()
            .channel();

    /* wait until channel is ready (future completes)*/
    http1WebSocketHandshaker.handshakeComplete().sync();

    /*send websocket frames*/
    http1Channel
        .eventLoop()
        .scheduleAtFixedRate(
            () -> http1Channel.writeAndFlush(new TextWebSocketFrame("hello http1 websocket")),
            1_000,
            2_000,
            TimeUnit.MILLISECONDS);

    http2WebSocketChannel.closeFuture().sync();
    logger.info("==> Http2 websocket closed. Terminating client...");
    http2WebSocketChannel.eventLoop().shutdownGracefully();
    logger.info("==> Client terminated");
  }

  private static class Http1WebSocketHandshaker extends SimpleChannelInboundHandler<Object> {
    private final WebSocketClientHandshaker handshaker;
    private ChannelPromise handshakeComplete;

    public Http1WebSocketHandshaker(
        String path,
        String subprotocol,
        HttpHeaders headers,
        WebSocketDecoderConfig webSocketDecoderConfig,
        String host,
        int port) {
      handshaker =
          WebSocketClientHandshakerFactory.newHandshaker(
              uri("wss://" + host + ":" + port + path),
              WebSocketVersion.V13,
              subprotocol,
              webSocketDecoderConfig.allowExtensions(),
              headers,
              webSocketDecoderConfig.maxFramePayloadLength(),
              true,
              webSocketDecoderConfig.allowMaskMismatch());
    }

    public ChannelFuture handshakeComplete() {
      return handshakeComplete;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
      handshakeComplete = ctx.newPromise();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      handshaker.handshake(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      ChannelPromise f = handshakeComplete;
      if (!f.isDone()) {
        f.setFailure(new ClosedChannelException());
      }
      super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof FullHttpResponse) {
        WebSocketClientHandshaker h = handshaker;
        if (h.isHandshakeComplete()) {
          throw new IllegalStateException(
              "Unexpected http response after http1 websocket handshake completion");
        }
        ChannelPromise hc = handshakeComplete;
        try {
          h.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
          hc.setSuccess();
        } catch (WebSocketHandshakeException e) {
          logger.error("Http1 websocket handshake error", e);
          hc.setFailure(e);
        }
        return;
      }
      if (msg instanceof ReferenceCounted) {
        ((ReferenceCounted) msg).retain();
      }
      ctx.fireChannelRead(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      ChannelPromise f = handshakeComplete;
      if (!f.isDone()) {
        f.setFailure(cause);
      }
      ctx.close();
      super.exceptionCaught(ctx, cause);
    }

    private static URI uri(String uri) {
      try {
        return new URI(uri);
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException("uri syntax error: " + uri, e);
      }
    }
  }

  @ChannelHandler.Sharable
  private static class EchoWebSocketHandler
      extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame webSocketFrame) {
      logger.info("==> Received text websocket message: {}", webSocketFrame.text());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.error("Unexpected websocket error", cause);
      ctx.close();
    }
  }
}
