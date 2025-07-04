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

import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketCallbacksHandler;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameFactory;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameListener;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Http2WebSocketHandshakeErrorEvent;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Http2WebSocketHandshakeStartEvent;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Http2WebSocketHandshakeSuccessEvent;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Http2WebSocketSupportedEvent;
import com.jauntsdn.netty.handler.codec.http2.websocketx.WebSocketEvent.WebSocketHandshakeErrorEvent;
import com.jauntsdn.netty.handler.codec.http2.websocketx.WebSocketEvent.WebSocketHandshakeStartEvent;
import com.jauntsdn.netty.handler.codec.http2.websocketx.WebSocketEvent.WebSocketHandshakeSuccessEvent;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2ChannelDuplexHandler;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class ProtocolHandshakeTest extends AbstractTest {
  private Channel server;
  private Channel client;
  private SslContext serverSslContext;
  private SslContext clientSslContext;

  @Test
  void settingsEnableConnectAccepted() throws Exception {
    server =
        createServer(
                ch -> {
                  SslHandler sslHandler = serverSslContext.newHandler(ch.alloc());
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      Http2FrameCodecBuilder.forServer().validateHeaders(false);
                  Http2Settings settings = http2FrameCodecBuilder.initialSettings();
                  settings.put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
                  Http2FrameCodec http2frameCodec = http2FrameCodecBuilder.build();
                  Http2WebSocketServerHandler http2webSocketHandler =
                      Http2WebSocketServerBuilder.create()
                          .acceptor(new PathAcceptor("/test", new ChannelInboundHandlerAdapter()))
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler(4);
    SocketAddress address = server.localAddress();
    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientBuilder.create().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline()
                      .addLast(
                          sslHandler, http2FrameCodec, http2WebSocketClientHandler, eventsRecorder);
                })
            .sync()
            .channel();

    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", new ChannelInboundHandlerAdapter());
    handshake.await(5, TimeUnit.SECONDS);
    Assertions.assertThat(handshake.isSuccess()).isTrue();

    eventsRecorder.eventsReceived().await(5, TimeUnit.SECONDS);
    List<Http2WebSocketEvent> events = eventsRecorder.events();
    Http2WebSocketEvent webSocketSupportedEvent = eventsRecorder.webSocketSupportedEvent();

    Assertions.assertThat(events).hasSize(4);
    Http2WebSocketEvent http2startEvent = events.get(0);
    Http2WebSocketEvent startEvent = events.get(1);
    Http2WebSocketEvent http2successEvent = events.get(2);
    Http2WebSocketEvent successEvent = events.get(3);

    Assertions.assertThat(http2startEvent)
        .isExactlyInstanceOf(Http2WebSocketHandshakeStartEvent.class);
    Assertions.assertThat(http2startEvent.<Http2WebSocketHandshakeStartEvent>cast().path())
        .isEqualTo("/test");

    Assertions.assertThat(startEvent).isExactlyInstanceOf(WebSocketHandshakeStartEvent.class);
    Assertions.assertThat(startEvent.<WebSocketHandshakeStartEvent>cast().path())
        .isEqualTo("/test");

    Assertions.assertThat(webSocketSupportedEvent)
        .isNotNull()
        .isExactlyInstanceOf(Http2WebSocketSupportedEvent.class);
    Assertions.assertThat(
            webSocketSupportedEvent.<Http2WebSocketSupportedEvent>cast().isWebSocketSupported())
        .isTrue();

    Assertions.assertThat(http2successEvent)
        .isExactlyInstanceOf(Http2WebSocketHandshakeSuccessEvent.class);

    Assertions.assertThat(successEvent).isExactlyInstanceOf(WebSocketHandshakeSuccessEvent.class);
  }

  @Test
  void settingsNoEnableConnectRejected() throws Exception {
    SslContext serverSslContext = serverSslContext();
    server =
        createServer(
                ch -> {
                  SslHandler sslHandler = serverSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2frameCodec = Http2FrameCodecBuilder.forServer().build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec);
                })
            .sync()
            .channel();

    SocketAddress address = server.localAddress();
    SslContext clientSslContext = clientSslContext();
    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler(4);
    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientBuilder.create().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline()
                      .addLast(
                          sslHandler, http2FrameCodec, http2WebSocketClientHandler, eventsRecorder);
                })
            .sync()
            .channel();

    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", new ChannelInboundHandlerAdapter());
    handshake.await(6, TimeUnit.SECONDS);
    Assertions.assertThat(handshake.isSuccess()).isFalse();
    Assertions.assertThat(handshake.cause()).isExactlyInstanceOf(WebSocketHandshakeException.class);
    Channel webSocketChannel = handshake.channel();
    webSocketChannel.closeFuture().await(5, TimeUnit.SECONDS);
    Assertions.assertThat(webSocketChannel.isOpen()).isFalse();

    eventsRecorder.eventsReceived().await(5, TimeUnit.SECONDS);
    List<Http2WebSocketEvent> events = eventsRecorder.events();
    Http2WebSocketEvent webSocketSupportedEvent = eventsRecorder.webSocketSupportedEvent();

    Assertions.assertThat(events).hasSize(4);
    Http2WebSocketEvent http2startEvent = events.get(0);
    Http2WebSocketEvent startEvent = events.get(1);
    Http2WebSocketEvent http2errorEvent = events.get(2);
    Http2WebSocketEvent errorEvent = events.get(3);

    Assertions.assertThat(http2startEvent)
        .isExactlyInstanceOf(Http2WebSocketHandshakeStartEvent.class);
    Assertions.assertThat(http2startEvent.<Http2WebSocketHandshakeStartEvent>cast().path())
        .isEqualTo("/test");

    Assertions.assertThat(startEvent).isExactlyInstanceOf(WebSocketHandshakeStartEvent.class);
    Assertions.assertThat(startEvent.<WebSocketHandshakeStartEvent>cast().path())
        .isEqualTo("/test");

    Assertions.assertThat(webSocketSupportedEvent)
        .isNotNull()
        .isExactlyInstanceOf(Http2WebSocketSupportedEvent.class);
    Assertions.assertThat(
            webSocketSupportedEvent.<Http2WebSocketSupportedEvent>cast().isWebSocketSupported())
        .isFalse();

    Assertions.assertThat(http2errorEvent)
        .isExactlyInstanceOf(Http2WebSocketHandshakeErrorEvent.class);
    Assertions.assertThat(http2errorEvent.<Http2WebSocketHandshakeErrorEvent>cast().error())
        .isExactlyInstanceOf(WebSocketHandshakeException.class);

    Assertions.assertThat(errorEvent).isExactlyInstanceOf(WebSocketHandshakeErrorEvent.class);
    Assertions.assertThat(errorEvent.<WebSocketHandshakeErrorEvent>cast().error())
        .isExactlyInstanceOf(WebSocketHandshakeException.class);
  }

  @ParameterizedTest
  @MethodSource("invalidWebSocketRequests")
  void invalidWebSocketRequestRejected(Http2Headers invalidHttp2RequestHeaders) throws Exception {
    SslContext serverSslContext = serverSslContext();
    server =
        createServer(
                ch -> {
                  SslHandler sslHandler = serverSslContext.newHandler(ch.alloc());
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      Http2FrameCodecBuilder.forServer().validateHeaders(false);
                  Http2Settings settings = http2FrameCodecBuilder.initialSettings();
                  settings.put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
                  Http2FrameCodec http2frameCodec = http2FrameCodecBuilder.build();
                  Http2WebSocketServerHandler http2webSocketHandler =
                      Http2WebSocketServerBuilder.create()
                          .acceptor(new PathAcceptor("/test", new ChannelInboundHandlerAdapter()))
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    SocketAddress address = server.localAddress();
    StreamTerminationHandler streamTerminationHandler = new StreamTerminationHandler();
    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  ch.pipeline().addLast(sslHandler, http2FrameCodec, streamTerminationHandler);
                })
            .sync()
            .channel();

    Http2FrameStream http2FrameStream = streamTerminationHandler.newStream();
    client.writeAndFlush(
        new DefaultHttp2HeadersFrame(invalidHttp2RequestHeaders, false).stream(http2FrameStream));
    ChannelFuture streamTerminated = streamTerminationHandler.terminated();
    streamTerminated.await(5, TimeUnit.SECONDS);
    Assertions.assertThat(streamTerminated.isSuccess()).isTrue();
  }

  @Timeout(15)
  @MethodSource("externalMaskDefaultCodecProvider")
  @ParameterizedTest
  void externalMaskDefaultCodec(IntSupplier externalMask) throws Exception {
    SslContext serverSslContext = serverSslContext();
    server =
        createServer(
                ch -> {
                  SslHandler sslHandler = serverSslContext.newHandler(ch.alloc());
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      Http2FrameCodecBuilder.forServer().validateHeaders(false);
                  Http2Settings settings = http2FrameCodecBuilder.initialSettings();
                  settings.put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
                  Http2FrameCodec http2frameCodec = http2FrameCodecBuilder.build();
                  Http2WebSocketServerHandler http2webSocketHandler =
                      Http2WebSocketServerBuilder.create()
                          .acceptor(
                              new PathAcceptor(
                                  "/test",
                                  new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext ctx, Object msg)
                                        throws Exception {
                                      if (msg instanceof TextWebSocketFrame) {
                                        ctx.writeAndFlush(msg);
                                        return;
                                      }
                                      super.channelRead(ctx, msg);
                                    }
                                  }))
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    SocketAddress address = server.localAddress();
    SslContext clientSslContext = clientSslContext();

    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientBuilder.create()
                          .handshakeTimeoutMillis(5_000)
                          .maskPayload(true)
                          .mask(externalMask)
                          .build();
                  ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                })
            .sync()
            .channel();

    ClientEchoWebSocketHandler clientEchoWebSocketHandler = new ClientEchoWebSocketHandler();
    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", clientEchoWebSocketHandler);
    handshake.await(5, TimeUnit.SECONDS);
    Assertions.assertThat(handshake.isSuccess()).isTrue();

    String expectedContent = "0xFE";
    handshake.channel().writeAndFlush(new TextWebSocketFrame(expectedContent));
    TextWebSocketFrame echoFrame = clientEchoWebSocketHandler.textWebSocketFrame.join();
    try {
      Assertions.assertThat(echoFrame.text()).isEqualTo(expectedContent);
    } finally {
      echoFrame.release();
    }
  }

  static class ClientEchoWebSocketHandler extends ChannelInboundHandlerAdapter {
    final CompletableFuture<TextWebSocketFrame> textWebSocketFrame = new CompletableFuture<>();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof TextWebSocketFrame) {
        if (textWebSocketFrame.isDone()) {
          ReferenceCountUtil.safeRelease(msg);
        } else {
          textWebSocketFrame.complete((TextWebSocketFrame) msg);
        }
        return;
      }
      super.channelRead(ctx, msg);
    }
  }

  @Timeout(15)
  @MethodSource("externalMaskCallbacksCodecProvider")
  @ParameterizedTest
  void externalMaskCallbacksCodec(IntSupplier externalMask, Class<?> expectedFrameFactoryType)
      throws Exception {
    SslContext serverSslContext = serverSslContext();
    server =
        createServer(
                ch -> {
                  SslHandler sslHandler = serverSslContext.newHandler(ch.alloc());
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      Http2FrameCodecBuilder.forServer().validateHeaders(false);
                  Http2Settings settings = http2FrameCodecBuilder.initialSettings();
                  settings.put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
                  Http2FrameCodec http2frameCodec = http2FrameCodecBuilder.build();
                  Http2WebSocketServerHandler http2webSocketServerHandler =
                      Http2WebSocketServerBuilder.create()
                          .acceptor(new PathAcceptor("/test", new ChannelInboundHandlerAdapter()))
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketServerHandler);
                })
            .sync()
            .channel();

    SocketAddress address = server.localAddress();

    SslContext clientSslContext = clientSslContext();
    WebSocketDecoderConfig decoderConfig =
        WebSocketDecoderConfig.newBuilder()
            .maxFramePayloadLength(65_535)
            .expectMaskedFrames(false)
            .allowMaskMismatch(true)
            .allowExtensions(false)
            .withUTF8Validator(false)
            .build();

    TestWebSocketHandler clientCallbacksHandler = new TestWebSocketHandler();

    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientBuilder.create()
                          .handshakeTimeoutMillis(5_000)
                          .codec(WebSocketCallbacksCodec.instance())
                          .compression(false)
                          .decoderConfig(decoderConfig)
                          .maskPayload(true)
                          .mask(externalMask)
                          .build();
                  ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                })
            .sync()
            .channel();

    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", new WebSocketsHttp2CallbacksHandler(clientCallbacksHandler));
    handshake.await(5, TimeUnit.SECONDS);
    Assertions.assertThat(handshake.isSuccess()).isTrue();
    clientCallbacksHandler.onOpen.join();
    Assertions.assertThat(clientCallbacksHandler.webSocketFrameFactory)
        .isNotNull()
        .isExactlyInstanceOf(expectedFrameFactoryType);
  }

  static Stream<IntSupplier> externalMaskDefaultCodecProvider() {
    return Stream.of(() -> ThreadLocalRandom.current().nextInt(), null);
  }

  static Stream<Arguments> externalMaskCallbacksCodecProvider() throws Exception {
    return Stream.of(
        Arguments.of(
            (IntSupplier) () -> ThreadLocalRandom.current().nextInt(),
            Class.forName(
                "com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketMaskedEncoder$ExternalMaskFrameFactory")),
        Arguments.of(
            null,
            Class.forName(
                "com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketMaskedEncoder$FrameFactory")));
  }

  static Stream<Http2Headers> invalidWebSocketRequests() {
    Http2Headers emptyPath =
        Http2WebSocketProtocol.extendedConnect(new DefaultHttp2Headers(false))
            .scheme("https")
            .authority("localhost")
            .path("")
            /* sec-websocket-version=13 only */
            .set(
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME,
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE);

    Http2Headers emptyAuthority =
        Http2WebSocketProtocol.extendedConnect(new DefaultHttp2Headers(false))
            .scheme("https")
            .authority("")
            .path("path")
            /* sec-websocket-version=13 only */
            .set(
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME,
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE);

    Http2Headers emptyScheme =
        Http2WebSocketProtocol.extendedConnect(new DefaultHttp2Headers(false))
            .scheme("")
            .authority("localhost")
            .path("path")
            /* sec-websocket-version=13 only */
            .set(
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME,
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE);

    Http2Headers nonHttpScheme =
        Http2WebSocketProtocol.extendedConnect(new DefaultHttp2Headers(false))
            .scheme("ftp")
            .authority("localhost")
            .path("path")
            /* sec-websocket-version=13 only */
            .set(
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME,
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE);

    return Stream.of(emptyPath, emptyAuthority, emptyScheme, nonHttpScheme);
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

  private static class StreamTerminationHandler extends Http2ChannelDuplexHandler {
    private volatile ChannelPromise completePromise;

    @Override
    protected void handlerAdded0(ChannelHandlerContext ctx) throws Exception {
      this.completePromise = ctx.newPromise();
      super.handlerAdded0(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof Http2ResetFrame) {
        completePromise.setSuccess();
      } else if (msg instanceof Http2HeadersFrame) {
        completePromise.setFailure(
            new IllegalStateException("Http2 websocket server accepted illegal request"));
      }
      ReferenceCountUtil.release(msg);
    }

    public ChannelFuture terminated() {
      return completePromise;
    }
  }

  private static class WebSocketsHttp2CallbacksHandler extends ChannelInboundHandlerAdapter {
    final WebSocketCallbacksHandler webSocketHandler;

    WebSocketsHttp2CallbacksHandler(WebSocketCallbacksHandler webSocketHandler) {
      this.webSocketHandler = webSocketHandler;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof Http2WebSocketEvent.Http2WebSocketLifecycleEvent) {
        Http2WebSocketEvent.Http2WebSocketLifecycleEvent handshakeEvent =
            (Http2WebSocketEvent.Http2WebSocketLifecycleEvent) evt;
        Http2WebSocketEvent.Type eventType = handshakeEvent.type();
        switch (eventType) {
          case HANDSHAKE_START:
          case CLOSE_REMOTE_ENDSTREAM:
          case CLOSE_REMOTE_RESET:
          case HANDSHAKE_ERROR:
            break;
          case HANDSHAKE_SUCCESS:
            WebSocketCallbacksHandler.exchange(ctx, webSocketHandler);
            ctx.pipeline().remove(this);
            break;
        }
        return;
      }
      super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      ctx.close();
    }
  }

  static class TestWebSocketHandler implements WebSocketCallbacksHandler {
    final CompletableFuture<Void> onOpen = new CompletableFuture<>();

    volatile WebSocketFrameFactory webSocketFrameFactory;
    volatile Channel channel;

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      this.channel = ctx.channel();

      return new WebSocketFrameListener() {
        @Override
        public void onChannelRead(
            ChannelHandlerContext ctx,
            boolean finalFragment,
            int rsv,
            int opcode,
            ByteBuf payload) {
          payload.release();
        }

        @Override
        public void onOpen(ChannelHandlerContext ctx) {
          onOpen.complete(null);
        }
      };
    }
  }
}
