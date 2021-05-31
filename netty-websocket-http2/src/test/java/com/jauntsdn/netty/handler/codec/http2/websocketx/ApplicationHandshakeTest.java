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

import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Future;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ApplicationHandshakeTest extends AbstractTest {
  private Channel server;
  private Channel client;
  private SslContext serverSslContext;
  private SslContext clientSslContext;

  @Test
  void knownPathAccepted() throws Exception {
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

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler(2);
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
    Assertions.assertThat(events).hasSize(2);
    Http2WebSocketEvent startEvent = events.get(0);
    Http2WebSocketEvent successEvent = events.get(1);
    Assertions.assertThat(startEvent).isExactlyInstanceOf(Http2WebSocketHandshakeStartEvent.class);
    Assertions.assertThat(startEvent.<Http2WebSocketHandshakeStartEvent>cast().path())
        .isEqualTo("/test");
    Assertions.assertThat(successEvent)
        .isExactlyInstanceOf(Http2WebSocketHandshakeSuccessEvent.class);
    Assertions.assertThat(startEvent.<Http2WebSocketHandshakeStartEvent>cast().id())
        .isEqualTo(successEvent.<Http2WebSocketHandshakeSuccessEvent>cast().id());
  }

  @Test
  void unknownPathRejected() throws Exception {
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
                          Http2WebSocketServerBuilder.create().build();
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
    Throwable cause = handshake.cause();
    Assertions.assertThat(cause).isExactlyInstanceOf(WebSocketHandshakeException.class);
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

  @Test
  void handshakeTimeout() throws Exception {
    server =
        createServer(
                ch -> {
                  SslHandler sslHandler = serverSslContext.newHandler(ch.alloc());
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      Http2FrameCodecBuilder.forServer().validateHeaders(false);
                  Http2Settings settings = http2FrameCodecBuilder.initialSettings();
                  settings.put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
                  Http2FrameCodec http2frameCodec = http2FrameCodecBuilder.build();
                  Http2WebSocketHandler http2webSocketHandler = Http2WebSocketServerBuilder.buildHandshakeOnly();
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
                      Http2WebSocketClientBuilder.create().handshakeTimeoutMillis(1_000).build();
                  ch.pipeline()
                      .addLast(
                          sslHandler, http2FrameCodec, http2WebSocketClientHandler, eventsRecorder);
                })
            .sync()
            .channel();

    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", new ChannelInboundHandlerAdapter());
    handshake.await(2, TimeUnit.SECONDS);

    Assertions.assertThat(handshake.isSuccess()).isFalse();
    Throwable cause = handshake.cause();
    Assertions.assertThat(cause).isExactlyInstanceOf(TimeoutException.class);
    Channel webSocketChannel = handshake.channel();
    webSocketChannel.closeFuture().await(5, TimeUnit.SECONDS);
    Assertions.assertThat(handshake.channel().isOpen()).isFalse();

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
        .isExactlyInstanceOf(TimeoutException.class);
  }

  @Test
  void serverAcceptorAccept() throws Exception {
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
                              new HeadersBasedAcceptor("/test", new ChannelInboundHandlerAdapter()))
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
                      Http2WebSocketClientBuilder.create().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline()
                      .addLast(
                          sslHandler, http2FrameCodec, http2WebSocketClientHandler, eventsRecorder);
                })
            .sync()
            .channel();

    DefaultHttp2Headers headers = new DefaultHttp2Headers();
    headers.set("x-client-id", "test");

    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", headers, new ChannelInboundHandlerAdapter());
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

    Http2Headers responseHeaders =
        successEvent.<Http2WebSocketHandshakeSuccessEvent>cast().responseHeaders();
    Assertions.assertThat(responseHeaders.contains("x-request-id")).isTrue();
    Assertions.assertThat(responseHeaders.get(":status")).isEqualTo(AsciiString.of("200"));
  }

  @Test
  void serverAcceptorReject() throws Exception {
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
                              new HeadersBasedAcceptor("/test", new ChannelInboundHandlerAdapter()))
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

    Http2Headers responseHeaders =
        errorEvent.<Http2WebSocketHandshakeErrorEvent>cast().responseHeaders();
    Assertions.assertThat(responseHeaders.get(":status")).isEqualTo(AsciiString.of("400"));
  }

  @Test
  void knownSubprotocolAccepted() throws Exception {
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
                              new PathSubprotocolAcceptor(
                                  "/test", "com.jauntsdn.test", new ChannelInboundHandlerAdapter()))
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
                      Http2WebSocketClientBuilder.create().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline()
                      .addLast(
                          sslHandler, http2FrameCodec, http2WebSocketClientHandler, eventsRecorder);
                })
            .sync()
            .channel();

    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", "com.jauntsdn.test", new ChannelInboundHandlerAdapter());
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

    Http2Headers responseHeaders =
        successEvent.<Http2WebSocketHandshakeSuccessEvent>cast().responseHeaders();
    Assertions.assertThat(responseHeaders.get(":status")).isEqualTo(AsciiString.of("200"));
  }

  @Test
  void unknownSubprotocolRejected() throws Exception {
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
                              new PathSubprotocolAcceptor(
                                  "/test", "com.jauntsdn.test", new ChannelInboundHandlerAdapter()))
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
                      Http2WebSocketClientBuilder.create().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline()
                      .addLast(
                          sslHandler, http2FrameCodec, http2WebSocketClientHandler, eventsRecorder);
                })
            .sync()
            .channel();

    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", "unknown.jauntsdn.com", new ChannelInboundHandlerAdapter());
    handshake.await(6, TimeUnit.SECONDS);
    Channel webSocketChannel = handshake.channel();
    Assertions.assertThat(handshake.isSuccess()).isFalse();
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

    Http2Headers responseHeaders =
        errorEvent.<Http2WebSocketHandshakeErrorEvent>cast().responseHeaders();
    Assertions.assertThat(responseHeaders.get(":status")).isEqualTo(AsciiString.of("404"));
  }

  @Test
  void nonHandshakedSubprotocolRejected() throws Exception {
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
                              new PathSubprotocolAcceptor(
                                  "/test",
                                  "com.jauntsdn.test",
                                  new ChannelInboundHandlerAdapter(),
                                  false))
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
                      Http2WebSocketClientBuilder.create().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline()
                      .addLast(
                          sslHandler, http2FrameCodec, http2WebSocketClientHandler, eventsRecorder);
                })
            .sync()
            .channel();

    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", "com.jauntsdn.test", new ChannelInboundHandlerAdapter());
    handshake.await(6, TimeUnit.SECONDS);
    Channel webSocketChannel = handshake.channel();
    Assertions.assertThat(handshake.isSuccess()).isFalse();
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

    Http2Headers responseHeaders =
        errorEvent.<Http2WebSocketHandshakeErrorEvent>cast().responseHeaders();
    Assertions.assertThat(responseHeaders.get(":status")).isEqualTo(AsciiString.of("404"));
  }

  @Test
  void compressionAccepted() throws Exception {
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
                          .decoderConfig(
                              WebSocketDecoderConfig.newBuilder().allowExtensions(true).build())
                          .compression(true)
                          .acceptor(new PathAcceptor("/test", new ChannelInboundHandlerAdapter()))
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
                      Http2WebSocketClientBuilder.create()
                          .handshakeTimeoutMillis(5_000)
                          .decoderConfig(
                              WebSocketDecoderConfig.newBuilder().allowExtensions(true).build())
                          .compression(true)
                          .build();
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

    Http2Headers responseHeaders =
        successEvent.<Http2WebSocketHandshakeSuccessEvent>cast().responseHeaders();
    Assertions.assertThat(responseHeaders.get(":status")).isEqualTo(AsciiString.of("200"));
    Assertions.assertThat(responseHeaders.get("sec-websocket-extensions"))
        .isEqualTo(AsciiString.of("permessage-deflate"));
  }

  @Test
  void compressionRejected() throws Exception {
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

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler(2);
    SocketAddress address = server.localAddress();
    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientBuilder.create()
                          .handshakeTimeoutMillis(5_000)
                          .decoderConfig(
                              WebSocketDecoderConfig.newBuilder().allowExtensions(true).build())
                          .compression(true)
                          .build();
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

    Http2Headers responseHeaders =
        successEvent.<Http2WebSocketHandshakeSuccessEvent>cast().responseHeaders();
    Assertions.assertThat(responseHeaders.get("sec-websocket-extensions")).isNullOrEmpty();
    Assertions.assertThat(responseHeaders.get(":status")).isEqualTo(AsciiString.of("200"));
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

  private static class HeadersBasedAcceptor implements Http2WebSocketAcceptor {
    private final String path;
    private final ChannelHandler webSocketHandler;

    HeadersBasedAcceptor(String path, ChannelHandler webSocketHandler) {
      this.path = path;
      this.webSocketHandler = webSocketHandler;
    }

    @Override
    public Future<ChannelHandler> accept(
        ChannelHandlerContext ctx,
        String path,
        List<String> subprotocols,
        Http2Headers request,
        Http2Headers response) {
      if (this.path.equals(path) && subprotocols.isEmpty()) {
        CharSequence requestId = request.get("x-client-id");
        if (requestId != null && requestId.length() > 0) {
          response.set("x-request-id", UUID.randomUUID().toString());
          return ctx.executor().newSucceededFuture(webSocketHandler);
        } else {
          return ctx.executor()
              .newFailedFuture(new WebSocketHandshakeException("Missing header: x-client-id"));
        }
      }
      return ctx.executor()
          .newFailedFuture(
              new WebSocketHandshakeException(
                  String.format("Path not found: %s , subprotocols: %s", path, subprotocols)));
    }
  }
}
