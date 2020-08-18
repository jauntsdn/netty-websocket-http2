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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled
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
                      Http2WebSocketServerHandler.builder()
                          .handler("/test", new ChannelInboundHandlerAdapter())
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler();
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
    /*remote handshake events are sent immediately after handshake promise completion*/
    Thread.sleep(1);
    Assertions.assertThat(handshake.isSuccess()).isTrue();
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
                      Http2WebSocketServerHandler.builder().build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler();
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
    /*remote handshake events are sent immediately after handshake promise completion*/
    Thread.sleep(1);

    Assertions.assertThat(handshake.isSuccess()).isFalse();
    Throwable cause = handshake.cause();
    Assertions.assertThat(cause).isExactlyInstanceOf(WebSocketHandshakeException.class);
    Assertions.assertThat(handshake.channel().isOpen()).isFalse();

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
                  Http2WebSocketServerHandler http2webSocketHandler =
                      Http2WebSocketServerHandler.builder().handshakeOnly();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler();
    SocketAddress address = server.localAddress();
    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientHandler.builder().handshakeTimeoutMillis(1_000).build();
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
    /*remote handshake events are sent immediately after handshake promise completion*/
    Thread.sleep(1);

    Assertions.assertThat(handshake.isSuccess()).isFalse();
    Throwable cause = handshake.cause();
    Assertions.assertThat(cause).isExactlyInstanceOf(TimeoutException.class);
    Assertions.assertThat(handshake.channel().isOpen()).isFalse();

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
                      Http2WebSocketServerHandler.builder()
                          .handler(
                              "/test",
                              new HeadersBasedAcceptor(),
                              new ChannelInboundHandlerAdapter())
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler();
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

    DefaultHttp2Headers headers = new DefaultHttp2Headers();
    headers.set("x-client-id", "test");

    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", headers, new ChannelInboundHandlerAdapter());
    handshake.await(5, TimeUnit.SECONDS);
    /*remote handshake events are sent immediately after handshake promise completion*/
    Thread.sleep(1);
    Assertions.assertThat(handshake.isSuccess()).isTrue();
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
                      Http2WebSocketServerHandler.builder()
                          .handler(
                              "/test",
                              new HeadersBasedAcceptor(),
                              new ChannelInboundHandlerAdapter())
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler();
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
    /*remote handshake events are sent immediately after handshake promise completion*/
    Thread.sleep(1);

    Assertions.assertThat(handshake.isSuccess()).isFalse();
    Assertions.assertThat(handshake.channel().isOpen()).isFalse();

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
                      Http2WebSocketServerHandler.builder()
                          .handler(
                              "/test",
                              "com.jauntsdn.test",
                              (context, request, response) -> context.newSucceededFuture(),
                              new ChannelInboundHandlerAdapter())
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler();
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
            .handshake("/test", "com.jauntsdn.test", new ChannelInboundHandlerAdapter());
    handshake.await(5, TimeUnit.SECONDS);
    /*remote handshake events are sent immediately after handshake promise completion*/
    Thread.sleep(1);
    Assertions.assertThat(handshake.isSuccess()).isTrue();
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
                      Http2WebSocketServerHandler.builder()
                          .handler(
                              "/test",
                              "com.jauntsdn.test",
                              (context, request, response) -> context.newSucceededFuture(),
                              new ChannelInboundHandlerAdapter())
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler();
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
            .handshake("/test", "com.jauntsdn.unknown", new ChannelInboundHandlerAdapter());
    handshake.await(5, TimeUnit.SECONDS);
    /*remote handshake events are sent immediately after handshake promise completion*/
    Thread.sleep(1);
    Assertions.assertThat(handshake.isSuccess()).isFalse();
    Assertions.assertThat(handshake.channel().isOpen()).isFalse();

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
                      Http2WebSocketServerHandler.builder()
                          .decoderConfig(
                              WebSocketDecoderConfig.newBuilder().allowExtensions(true).build())
                          .compression(true)
                          .handler("/test", new ChannelInboundHandlerAdapter())
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler();
    SocketAddress address = server.localAddress();
    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientHandler.builder()
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
    /*remote handshake events are sent immediately after handshake promise completion*/
    Thread.sleep(1);
    Assertions.assertThat(handshake.isSuccess()).isTrue();
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
                      Http2WebSocketServerHandler.builder()
                          .handler("/test", new ChannelInboundHandlerAdapter())
                          .build();
                  ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
                })
            .sync()
            .channel();

    WebsocketEventsHandler eventsRecorder = new WebsocketEventsHandler();
    SocketAddress address = server.localAddress();
    client =
        createClient(
                address,
                ch -> {
                  SslHandler sslHandler = clientSslContext.newHandler(ch.alloc());
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientHandler.builder()
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
    /*remote handshake events are sent immediately after handshake promise completion*/
    Thread.sleep(1);

    Assertions.assertThat(handshake.isSuccess()).isTrue();
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
    @Override
    public ChannelFuture accept(
        ChannelHandlerContext context, Http2Headers request, Http2Headers response) {
      response.set("x-request-id", UUID.randomUUID().toString());
      CharSequence requestId = request.get("x-client-id");
      if (requestId != null && requestId.length() > 0) {
        return context.newSucceededFuture();
      }
      return context.newFailedFuture(new IllegalArgumentException("Missing header: x-client-id"));
    }
  }
}