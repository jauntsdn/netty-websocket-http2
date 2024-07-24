/*
 * Copyright 2024 - present Maksym Ostroverkhov.
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

package com.jauntsdn.netty.handler.codec.websocketx.multiprotocol;

import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketCallbacksHandler;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameListener;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandshaker;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Http2WebSocketHandshakeSuccessEvent;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class WebSocketMultiprotocolTest {

  private volatile Channel server;

  @AfterEach
  void tearDown() throws Exception {
    Channel s = server;
    if (s != null) {
      s.close();
      s.closeFuture().await(15_000);
    }
  }

  @Test
  void parseSubprotocols() {
    Set<String> subprotocols = MultiProtocolWebSocketServerHandler.parseSubprotocols(null);
    Assertions.assertThat(subprotocols).hasSize(0);
    subprotocols = MultiProtocolWebSocketServerHandler.parseSubprotocols("");
    Assertions.assertThat(subprotocols).hasSize(0);
    subprotocols = MultiProtocolWebSocketServerHandler.parseSubprotocols("foo");
    Assertions.assertThat(subprotocols).hasSize(1);
    Assertions.assertThat(subprotocols.contains("foo")).isTrue();

    subprotocols = MultiProtocolWebSocketServerHandler.parseSubprotocols("foo, bar");
    Assertions.assertThat(subprotocols).hasSize(2);
    Assertions.assertThat(subprotocols.contains("foo")).isTrue();
    Assertions.assertThat(subprotocols.contains("bar")).isTrue();
  }

  @Test
  void selectSubprotocol() {
    String subprotocol =
        MultiProtocolWebSocketServerHandler.selectSubprotocol(
            Collections.emptyList(), Collections.emptySet());
    Assertions.assertThat(subprotocol).isEmpty();
    subprotocol =
        MultiProtocolWebSocketServerHandler.selectSubprotocol(
            Collections.emptyList(), Collections.singleton("foo"));
    Assertions.assertThat(subprotocol).isNull();
    subprotocol =
        MultiProtocolWebSocketServerHandler.selectSubprotocol(
            Collections.singletonList("foo"), Collections.emptySet());
    Assertions.assertThat(subprotocol).isNull();
    subprotocol =
        MultiProtocolWebSocketServerHandler.selectSubprotocol(
            Collections.singletonList("baz"), setOf("foo", "bar"));
    Assertions.assertThat(subprotocol).isNull();
    subprotocol =
        MultiProtocolWebSocketServerHandler.selectSubprotocol(
            Collections.singletonList("foo"), Collections.singleton("foo"));
    Assertions.assertThat(subprotocol).isEqualTo("foo");
    subprotocol =
        MultiProtocolWebSocketServerHandler.selectSubprotocol(
            Collections.singletonList("foo"), setOf("foo", "bar"));
    Assertions.assertThat(subprotocol).isEqualTo("foo");
    subprotocol =
        MultiProtocolWebSocketServerHandler.selectSubprotocol(
            Arrays.asList("bar", "foo"), Collections.singleton("foo"));
    Assertions.assertThat(subprotocol).isEqualTo("foo");
    subprotocol =
        MultiProtocolWebSocketServerHandler.selectSubprotocol(
            Arrays.asList("bar", "foo"), setOf("baz", "foo"));
    Assertions.assertThat(subprotocol).isEqualTo("foo");
  }

  @Test
  void http1webSocketDefaultCodec() throws Exception {
    String host = "localhost";
    int port = 8099;
    server = server(host, port, true, new DefaultServerHandler());
    ClientHandler clientHandler = new ClientHandler();
    Channel client = http1WebSocketClient(host, port, true, clientHandler);
    client.writeAndFlush(new TextWebSocketFrame("test"));
    TextWebSocketFrame receivedFrame = clientHandler.exchangeCompleted().get(5, TimeUnit.SECONDS);
    try {
      Assertions.assertThat(receivedFrame.text()).isEqualTo("test");
    } finally {
      receivedFrame.release();
    }
  }

  @Test
  void http2webSocketDefaultCodec() throws Exception {
    String host = "localhost";
    int port = 8099;
    server = server(host, port, true, new DefaultServerHandler());
    ClientHandler clientHandler = new ClientHandler();
    Channel client = http2WebSocketClient(host, port, true, clientHandler);
    client.writeAndFlush(new TextWebSocketFrame("test"));
    TextWebSocketFrame receivedFrame = clientHandler.exchangeCompleted().get(5, TimeUnit.SECONDS);
    try {
      Assertions.assertThat(receivedFrame.text()).isEqualTo("test");
    } finally {
      receivedFrame.release();
    }
  }

  @Test
  void http1webSocketCallbacksCodec() throws Exception {
    String host = "localhost";
    int port = 8099;
    server = server(host, port, false, new CallbacksServerHandler());
    ClientHandler clientHandler = new ClientHandler();
    Channel client = http1WebSocketClient(host, port, false, clientHandler);
    client.writeAndFlush(new TextWebSocketFrame("test"));
    TextWebSocketFrame receivedFrame = clientHandler.exchangeCompleted().get(5, TimeUnit.SECONDS);
    try {
      Assertions.assertThat(receivedFrame.text()).isEqualTo("test");
    } finally {
      receivedFrame.release();
    }
  }

  @Test
  void http2webSocketCallbacksCodec() throws Exception {
    String host = "localhost";
    int port = 8099;
    server = server(host, port, false, new CallbacksServerHandler());
    ClientHandler clientHandler = new ClientHandler();
    Channel client = http2WebSocketClient(host, port, false, clientHandler);
    client.writeAndFlush(new TextWebSocketFrame("test"));
    TextWebSocketFrame receivedFrame = clientHandler.exchangeCompleted().get(5, TimeUnit.SECONDS);
    try {
      Assertions.assertThat(receivedFrame.text()).isEqualTo("test");
    } finally {
      receivedFrame.release();
    }
  }

  static Channel server(String host, int port, boolean defaultCodec, ChannelHandler handler)
      throws Exception {
    SslContext sslContext = Security.serverSslContext("localhost.p12", "localhost");

    return new ServerBootstrap()
        .group(new NioEventLoopGroup())
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {

              @Override
              protected void initChannel(SocketChannel ch) {
                SslHandler sslHandler = sslContext.newHandler(ch.alloc());

                MultiprotocolWebSocketServerBuilder builder =
                    MultiprotocolWebSocketServerBuilder.create()
                        .path("/test")
                        .compression(defaultCodec)
                        .handler(handler);
                if (defaultCodec) {
                  builder.defaultCodec();
                } else {
                  builder.callbacksCodec();
                }
                MultiProtocolWebSocketServerHandler multiprotocolHandler = builder.build();
                ch.pipeline().addLast(sslHandler, multiprotocolHandler);
              }
            })
        .bind(host, port)
        .sync()
        .channel();
  }

  static Channel http2WebSocketClient(
      String host, int port, boolean compression, ChannelHandler handler) throws Exception {
    SslContext http2SslContext = Security.clientLocalSslContextHttp2();

    WebSocketDecoderConfig decoderConfig =
        WebSocketDecoderConfig.newBuilder()
            .expectMaskedFrames(false)
            .allowMaskMismatch(false)
            .allowExtensions(compression)
            .build();

    Channel http2Channel =
        new Bootstrap()
            .group(new NioEventLoopGroup(1))
            .channel(NioSocketChannel.class)
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {
                    SslHandler sslHandler = http2SslContext.newHandler(ch.alloc());
                    Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();

                    Http2WebSocketClientHandler http2WebSocketClientHandler =
                        Http2WebSocketClientBuilder.create()
                            .decoderConfig(decoderConfig)
                            .handshakeTimeoutMillis(15_000)
                            .compression(compression)
                            .build();

                    ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                  }
                })
            .connect(new InetSocketAddress(host, port))
            .sync()
            .channel();

    /*wait until channel is ready (future completes)*/
    Http2WebSocketClientHandshaker http2WebSocketHandShaker =
        Http2WebSocketClientHandshaker.create(http2Channel);

    ChannelFuture http2WebSocketHandshake = http2WebSocketHandShaker.handshake("/test", handler);

    return http2WebSocketHandshake.sync().channel();
  }

  static Channel http1WebSocketClient(
      String host, int port, boolean compression, ChannelHandler handler) throws Exception {
    SslContext clientSslContext = Security.clientLocalSslContextHttp1();
    WebSocketDecoderConfig decoderConfig =
        WebSocketDecoderConfig.newBuilder()
            .expectMaskedFrames(false)
            .allowMaskMismatch(false)
            .allowExtensions(true)
            .build();

    Http1WebSocketHandshaker http1WebSocketHandshaker =
        new Http1WebSocketHandshaker("/test", decoderConfig, host, port);

    Channel client =
        new Bootstrap()
            .group(new NioEventLoopGroup(1))
            .channel(NioSocketChannel.class)
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {
                    SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                    HttpClientCodec http1Codec = new HttpClientCodec();
                    HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);

                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(sslHandler, http1Codec, http1Aggregator);
                    if (compression) {
                      WebSocketClientCompressionHandler http1WebSocketCompressionHandler =
                          WebSocketClientCompressionHandler.INSTANCE;
                      pipeline.addLast(http1WebSocketCompressionHandler);
                    }
                    pipeline.addLast(http1WebSocketHandshaker, handler);
                  }
                })
            .connect(new InetSocketAddress(host, port))
            .sync()
            .channel();

    http1WebSocketHandshaker.handshakeComplete().sync();
    return client;
  }

  private static class Http1WebSocketHandshaker extends SimpleChannelInboundHandler<Object> {
    private final WebSocketClientHandshaker handshaker;
    private ChannelPromise handshakeComplete;

    public Http1WebSocketHandshaker(
        String path, WebSocketDecoderConfig webSocketDecoderConfig, String host, int port) {
      handshaker =
          WebSocketClientHandshakerFactory.newHandshaker(
              uri("wss://" + host + ":" + port + path),
              WebSocketVersion.V13,
              null,
              webSocketDecoderConfig.allowExtensions(),
              null,
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

  private static class ClientHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private volatile Promise<TextWebSocketFrame> exchangeCompleted;

    public Promise<TextWebSocketFrame> exchangeCompleted() {
      return exchangeCompleted;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      this.exchangeCompleted = new DefaultPromise<>(ctx.executor());
      super.handlerAdded(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame webSocketFrame) {
      exchangeCompleted.trySuccess(webSocketFrame.retain());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      exchangeCompleted.tryFailure(cause);
      if (cause instanceof IOException) {
        return;
      }
      throw new RuntimeException("Unexpected websocket error", cause);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      exchangeCompleted.tryFailure(new ClosedChannelException());
      super.channelInactive(ctx);
    }
  }

  @ChannelHandler.Sharable
  private static class DefaultServerHandler
      extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame webSocketFrame) {
      ctx.write(webSocketFrame.retain());
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
      ctx.flush();
      super.channelReadComplete(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      throw new RuntimeException("Unexpected websocket error", cause);
    }
  }

  private static class CallbacksServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void userEventTriggered(ChannelHandlerContext c, Object evt) throws Exception {
      if (evt instanceof HandshakeComplete || evt instanceof Http2WebSocketHandshakeSuccessEvent) {
        WebSocketCallbacksHandler.exchange(
            c,
            (ctx, webSocketFrameFactory) ->
                new WebSocketFrameListener() {
                  @Override
                  public void onChannelRead(
                      ChannelHandlerContext context,
                      boolean finalFragment,
                      int rsv,
                      int opcode,
                      ByteBuf payload) {
                    ByteBuf textFrame =
                        webSocketFrameFactory.mask(
                            webSocketFrameFactory.createTextFrame(
                                ctx.alloc(), payload.readableBytes()));
                    textFrame.writeBytes(payload);
                    payload.release();
                    ctx.write(textFrame);
                  }

                  @Override
                  public void onChannelReadComplete(ChannelHandlerContext ctx1) {
                    ctx1.flush();
                  }

                  @Override
                  public void onExceptionCaught(ChannelHandlerContext ctx1, Throwable cause) {
                    if (cause instanceof IOException) {
                      return;
                    }
                    throw new RuntimeException("Unexpected websocket error", cause);
                  }
                });
      }
      super.userEventTriggered(c, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      ReferenceCountUtil.safeRelease(msg);
    }
  }

  static <T> Set<T> setOf(T... elems) {
    Set<T> set = new HashSet<>(elems.length);
    for (T elem : elems) {
      set.add(elem);
    }
    return set;
  }
}
