/*
 * Copyright 2020 - present Maksym Ostroverkhov.
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

package com.jauntsdn.netty.handler.codec.http2.websocketx.example.lwsclient;

import static io.netty.channel.ChannelHandler.Sharable;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandshaker;
import com.jauntsdn.netty.handler.codec.http2.websocketx.example.Security;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    String host = System.getProperty("HOST", "libwebsockets.org");
    int port = Integer.parseInt(System.getProperty("PORT", "443"));
    InetSocketAddress address = new InetSocketAddress(host, port);

    logger.info("\n==> libwebsockets.org websocket-over-http2 client\n");
    logger.info("\n==> Remote address: {}:{}", host, port);
    logger.info("\n==> Dumb increment demo of https://libwebsockets.org/testserver/");

    SslContext sslContext = Security.clientSslContextHttp2();
    Channel channel =
        new Bootstrap()
            .group(new NioEventLoopGroup())
            .channel(NioSocketChannel.class)
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {
                    SslHandler sslHandler = sslContext.newHandler(ch.alloc());
                    Http2FrameCodecBuilder http2FrameCodecBuilder =
                        Http2FrameCodecBuilder.forClient();
                    http2FrameCodecBuilder.initialSettings().initialWindowSize(10_000);
                    Http2FrameCodec http2FrameCodec = http2FrameCodecBuilder.build();
                    Http2WebSocketClientHandler http2WebSocketClientHandler =
                        Http2WebSocketClientBuilder.create()
                            .decoderConfig(
                                WebSocketDecoderConfig.newBuilder()
                                    .expectMaskedFrames(false)
                                    .allowMaskMismatch(false)
                                    .build())
                            .handshakeTimeoutMillis(15_000)
                            .compression(true)
                            .build();

                    ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                  }
                })
            .connect(address)
            .sync()
            .channel();

    Http2WebSocketClientHandshaker handShaker = Http2WebSocketClientHandshaker.create(channel);

    Http2Headers headers =
        new DefaultHttp2Headers().set("user-agent", "jauntsdn-websocket-http2-client/1.1.5");
    ChannelFuture handshake =
        handShaker.handshake(
            "/", "dumb-increment-protocol", headers, new WebSocketDumbIncrementHandler());

    handshake.addListener(new WebSocketFutureListener());

    Channel echoWebSocketChannel = handshake.channel();

    EventLoopGroup eventLoop = echoWebSocketChannel.eventLoop();

    echoWebSocketChannel.closeFuture().sync();
    logger.info("==> Websocket closed. Terminating client...");
    eventLoop.shutdownGracefully();
    logger.info("==> Client terminated");
  }

  @Sharable
  private static class WebSocketDumbIncrementHandler
      extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private ScheduledFuture<?> sendResetFuture;
    private ScheduledFuture<?> receiveReportFuture;
    private int receiveCounter;
    private int receiveValue;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      sendResetFuture =
          ctx.executor()
              .scheduleAtFixedRate(
                  () -> {
                    ctx.writeAndFlush(new TextWebSocketFrame("reset\n"));
                    logger.info("==> Sent reset counter websocket frame");
                  },
                  5_000,
                  5_000,
                  TimeUnit.MILLISECONDS);

      receiveReportFuture =
          ctx.executor()
              .scheduleAtFixedRate(
                  () ->
                      logger.info(
                          "==> Received {} frames, latest value: {}", receiveCounter, receiveValue),
                  1_000,
                  1_000,
                  TimeUnit.MILLISECONDS);

      super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      sendResetFuture.cancel(true);
      receiveReportFuture.cancel(true);
      super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame webSocketFrame) {
      receiveValue = Integer.parseInt(webSocketFrame.text());
      receiveCounter++;
    }
  }

  private static class WebSocketFutureListener
      implements GenericFutureListener<Future<? super Void>> {
    @Override
    public void operationComplete(Future<? super Void> future) {
      if (future.isSuccess()) {
        logger.info("==> Websocket channel future success");
      } else {
        logger.info("==> Websocket channel future error: {}", future.cause().toString());
      }
    }
  }
}
