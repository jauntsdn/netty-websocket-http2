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

package com.jauntsdn.netty.handler.codec.http2.websocketx;

import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WebSocketTest extends AbstractTest {
  private static final int INITIAL_WINDOW_SIZE = 100;

  private Channel server;
  private Channel client;
  private SslContext serverSslContext;
  private SslContext clientSslContext;

  @ParameterizedTest
  @MethodSource("websocketConfigurers")
  void websocketFramesExchange(WebSocketsConfigurer webSocketsConfigurer) throws Exception {
    server =
        createServer(
                ch -> {
                  SslHandler sslHandler = serverSslContext.newHandler(ch.alloc());
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      Http2FrameCodecBuilder.forServer().validateHeaders(false);
                  Http2Settings settings = http2FrameCodecBuilder.initialSettings();
                  settings.put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
                  settings.initialWindowSize(INITIAL_WINDOW_SIZE);
                  Http2FrameCodec http2frameCodec = http2FrameCodecBuilder.build();

                  Http2WebSocketServerHandler http2webSocketHandler =
                      webSocketsConfigurer
                          .server()
                          .apply(Http2WebSocketServerBuilder.create())
                          .acceptor(new PathAcceptor("/test", new ServerWebSocketHandler()))
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    SocketAddress address = server.localAddress();
    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      Http2FrameCodecBuilder.forClient();
                  Http2Settings settings = http2FrameCodecBuilder.initialSettings();
                  settings.initialWindowSize(INITIAL_WINDOW_SIZE);
                  Http2FrameCodec http2FrameCodec = http2FrameCodecBuilder.build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      webSocketsConfigurer
                          .client()
                          .apply(Http2WebSocketClientBuilder.create())
                          .handshakeTimeoutMillis(5_000)
                          .build();
                  ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                })
            .sync()
            .channel();

    int framesCount = INITIAL_WINDOW_SIZE * 2;
    ClientWebSocketHandler clientWebSocketHandler = new ClientWebSocketHandler(framesCount);
    ChannelFuture clientHandshake =
        Http2WebSocketClientHandshaker.create(client).handshake("/test", clientWebSocketHandler);

    clientHandshake.await(6, TimeUnit.SECONDS);
    Assertions.assertThat(clientHandshake.isSuccess()).isTrue();
    ChannelPromise allFramesReceived = clientWebSocketHandler.allFramesReceived();
    allFramesReceived.await(5, TimeUnit.SECONDS);
    Assertions.assertThat(allFramesReceived.isSuccess()).isTrue();
    List<TextWebSocketFrame> receivedFrames = clientWebSocketHandler.receivedFrames();
    Assertions.assertThat(receivedFrames).hasSize(framesCount);
    for (int i = 0; i < receivedFrames.size(); i++) {
      TextWebSocketFrame textWebSocketFrame = receivedFrames.get(i);
      Assertions.assertThat(textWebSocketFrame.text()).isEqualTo(String.valueOf(i));
    }
  }

  @Test
  void handlerRemove() throws InterruptedException {
    Http2FramesHandler serverHttp2FramesHandler = new Http2FramesHandler();
    server =
        createServer(
                ch -> {
                  SslHandler sslHandler = serverSslContext.newHandler(ch.alloc());
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      Http2FrameCodecBuilder.forServer().validateHeaders(false);
                  Http2Settings settings = http2FrameCodecBuilder.initialSettings();
                  settings.put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
                  settings.initialWindowSize(INITIAL_WINDOW_SIZE);
                  Http2FrameCodec http2frameCodec = http2FrameCodecBuilder.build();

                  Http2WebSocketServerHandler http2webSocketHandler =
                      Http2WebSocketServerBuilder.create()
                          .acceptor(new PathAcceptor("/test", new RemoveHttp2WebSocketHandler()))
                          .build();
                  ch.pipeline()
                      .addLast(
                          sslHandler,
                          http2frameCodec,
                          http2webSocketHandler,
                          serverHttp2FramesHandler);
                })
            .sync()
            .channel();

    SocketAddress address = server.localAddress();

    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      Http2FrameCodecBuilder.forClient();
                  Http2Settings settings = http2FrameCodecBuilder.initialSettings();
                  settings.initialWindowSize(INITIAL_WINDOW_SIZE);
                  Http2FrameCodec http2FrameCodec = http2FrameCodecBuilder.build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientBuilder.create().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                })
            .sync()
            .channel();

    NoopClientWebSocketHandler clientWebSocketHandler = new NoopClientWebSocketHandler(client);
    Http2WebSocketClientHandshaker handshaker = Http2WebSocketClientHandshaker.create(client);
    ChannelFuture clientHandshake = handshaker.handshake("/test", clientWebSocketHandler);

    clientHandshake.await(6, TimeUnit.SECONDS);
    Assertions.assertThat(clientHandshake.isSuccess()).isTrue();
    ChannelPromise frameReceived = clientWebSocketHandler.frameReceived;
    frameReceived.await(6, TimeUnit.SECONDS);
    Assertions.assertThat(frameReceived.isSuccess()).isTrue();

    NoopClientWebSocketHandler nextClientWebSocketHandler = new NoopClientWebSocketHandler(client);
    ChannelFuture nextClientHandshake = handshaker.handshake("/test", nextClientWebSocketHandler);
    nextClientHandshake.await(6, TimeUnit.SECONDS);
    Assertions.assertThat(nextClientHandshake.isSuccess()).isFalse();
    Assertions.assertThat(serverHttp2FramesHandler.requestsReceived).isEqualTo(1);
  }

  @BeforeEach
  void setUp() throws Exception {
    serverSslContext = serverSslContext();
    clientSslContext = clientSslContext();
  }

  @AfterEach
  void tearDown() throws Exception {
    Channel c = client;
    if (c != null) {
      c.eventLoop().shutdownGracefully(0, 5, TimeUnit.SECONDS);
      c.closeFuture().await(5, TimeUnit.SECONDS);
    }
    Channel s = server;
    if (s != null) {
      s.eventLoop().shutdownGracefully(0, 5, TimeUnit.SECONDS);
      s.closeFuture().await(5, TimeUnit.SECONDS);
    }
  }

  private static class NoopClientWebSocketHandler
      extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    volatile ChannelPromise frameReceived;

    NoopClientWebSocketHandler(Channel channel) {
      this.frameReceived = channel.newPromise();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);
      ctx.writeAndFlush(new TextWebSocketFrame("test"));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
      msg.release();
      frameReceived.trySuccess();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      frameReceived.tryFailure(new ClosedChannelException());
      super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      frameReceived.tryFailure(cause);
      super.exceptionCaught(ctx, cause);
    }
  }

  private static class ClientWebSocketHandler
      extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private volatile ChannelPromise allFramesReceived;
    private final int framesCount;
    private int sentFrames;
    private final List<TextWebSocketFrame> receivedFrames = new ArrayList<>();

    public ClientWebSocketHandler(int framesCount) {
      this.framesCount = framesCount;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      allFramesReceived = ctx.newPromise();
      super.handlerAdded(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      ctx.writeAndFlush(nextWebSocketFrame());
      super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame webSocketFrame) {
      receivedFrames.add(webSocketFrame.retain());
      if (sentFrames == framesCount) {
        allFramesReceived.setSuccess();
        return;
      }
      ctx.writeAndFlush(nextWebSocketFrame());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      allFramesReceived.setFailure(cause);
      ctx.close();
    }

    public ChannelPromise allFramesReceived() {
      return allFramesReceived;
    }

    public List<TextWebSocketFrame> receivedFrames() {
      return receivedFrames;
    }

    private TextWebSocketFrame nextWebSocketFrame() {
      return new TextWebSocketFrame(String.valueOf(sentFrames++));
    }
  }

  private static class Http2FramesHandler extends ChannelInboundHandlerAdapter {
    volatile int requestsReceived;

    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      try {
        if (msg instanceof Http2HeadersFrame) {
          /*called on eventloop thread only*/
          requestsReceived++;
          ctx.close();
        }
      } finally {
        ReferenceCountUtil.safeRelease(msg);
      }
    }
  }

  private static class RemoveHttp2WebSocketHandler
      extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame webSocketFrame) {
      ctx.writeAndFlush(webSocketFrame.retain());
      ctx.channel().parent().pipeline().remove(Http2WebSocketServerHandler.class);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      ctx.close();
    }
  }

  private static class ServerWebSocketHandler
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
      ctx.close();
    }
  }

  static Stream<WebSocketsConfigurer> websocketConfigurers() {
    return Stream.of(
        new WebSocketsConfigurer(
            builder -> builder.compression(true), builder -> builder.compression(true)),
        new WebSocketsConfigurer(
            builder -> builder.compression(false), builder -> builder.compression(false)),
        new WebSocketsConfigurer(
            builder -> builder.compression(false), builder -> builder.compression(true)),
        new WebSocketsConfigurer(
            builder -> builder.compression(true), builder -> builder.compression(false)));
  }

  static class WebSocketsConfigurer {
    private final Function<Http2WebSocketServerBuilder, Http2WebSocketServerBuilder>
        serverConfigurer;
    private final Function<Http2WebSocketClientBuilder, Http2WebSocketClientBuilder>
        clientConfigurer;

    WebSocketsConfigurer(
        Function<Http2WebSocketServerBuilder, Http2WebSocketServerBuilder> serverConfigurer,
        Function<Http2WebSocketClientBuilder, Http2WebSocketClientBuilder> clientConfigurer) {
      this.serverConfigurer = serverConfigurer;
      this.clientConfigurer = clientConfigurer;
    }

    public Function<Http2WebSocketServerBuilder, Http2WebSocketServerBuilder> server() {
      return serverConfigurer;
    }

    public Function<Http2WebSocketClientBuilder, Http2WebSocketClientBuilder> client() {
      return clientConfigurer;
    }
  }
}
