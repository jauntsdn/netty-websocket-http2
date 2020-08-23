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

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.*;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Http2WebSocketHandshakeStartEvent;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
                      Http2WebSocketServerHandler.builder()
                          .handler("/test", new ChannelInboundHandlerAdapter())
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler(2);
    SocketAddress address = server.localAddress();
    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientHandler.builder().handshakeTimeoutMillis(5_000).build();
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
    Assertions.assertThat(events).hasSize(2);
    Http2WebSocketEvent startEvent = events.get(0);
    Http2WebSocketEvent successEvent = events.get(1);
    Assertions.assertThat(startEvent).isExactlyInstanceOf(Http2WebSocketHandshakeStartEvent.class);
    Assertions.assertThat(startEvent.<Http2WebSocketHandshakeStartEvent>cast().path())
        .isEqualTo("/test");
    Assertions.assertThat(successEvent)
        .isExactlyInstanceOf(Http2WebSocketHandshakeSuccessEvent.class);
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
    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler(2);
    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientHandler.builder().handshakeTimeoutMillis(5_000).build();
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
    Assertions.assertThat(events).hasSize(2);
    Http2WebSocketEvent startEvent = events.get(0);
    Http2WebSocketEvent errorEvent = events.get(1);
    Assertions.assertThat(startEvent).isExactlyInstanceOf(Http2WebSocketHandshakeStartEvent.class);
    Assertions.assertThat(startEvent.<Http2WebSocketHandshakeStartEvent>cast().path())
        .isEqualTo("/test");
    Assertions.assertThat(errorEvent).isExactlyInstanceOf(Http2WebSocketHandshakeErrorEvent.class);
    Assertions.assertThat(errorEvent.<Http2WebSocketHandshakeErrorEvent>cast().error())
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
                      Http2WebSocketServerHandler.builder()
                          .handler("/test", new ChannelInboundHandlerAdapter())
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

  static Stream<Http2Headers> invalidWebSocketRequests() {
    Http2Headers emptyPath =
        Http2WebSocketProtocol.extendedConnect()
            .scheme("https")
            .authority("localhost")
            .path("")
            /* sec-websocket-version=13 only */
            .set(
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME,
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE);

    Http2Headers emptyAuthority =
        Http2WebSocketProtocol.extendedConnect()
            .scheme("https")
            .authority("")
            .path("path")
            /* sec-websocket-version=13 only */
            .set(
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME,
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE);

    Http2Headers emptyScheme =
        Http2WebSocketProtocol.extendedConnect()
            .scheme("")
            .authority("localhost")
            .path("path")
            /* sec-websocket-version=13 only */
            .set(
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME,
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE);

    Http2Headers nonHttpScheme =
        Http2WebSocketProtocol.extendedConnect()
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
}
