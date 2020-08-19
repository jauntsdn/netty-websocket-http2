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
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Disabled
public class TerminationTest extends AbstractTest {
  private Channel server;
  private Channel client;
  private SslContext serverSslContext;
  private SslContext clientSslContext;

  @ParameterizedTest
  @MethodSource("serverCloseHandlers")
  void serverWebsocketClose(WebsocketCloseHandler serverWebsocketCloseHandler) throws Exception {
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
                          .handler("/test", serverWebsocketCloseHandler)
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
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientHandler.builder().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                })
            .sync()
            .channel();

    ChannelFuture handshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", new WebsocketCloseListenerHandler());

    Channel clientWebSocketChannel = handshake.channel();
    ChannelFuture clientWebSocketCloseFuture = clientWebSocketChannel.closeFuture();
    clientWebSocketCloseFuture.await(6, TimeUnit.SECONDS);
    Assertions.assertThat(clientWebSocketCloseFuture.isSuccess()).isTrue();
    Assertions.assertThat(clientWebSocketChannel.isOpen()).isFalse();

    Channel serverWebsocketChannel = serverWebsocketCloseHandler.websocketChannel();
    ChannelFuture serverWebSocketCloseFuture = serverWebsocketChannel.closeFuture();
    serverWebSocketCloseFuture.await(5, TimeUnit.SECONDS);
    Assertions.assertThat(serverWebSocketCloseFuture.isSuccess()).isTrue();
    Assertions.assertThat(serverWebsocketChannel.isOpen()).isFalse();

    Assertions.assertThat(client.isOpen()).isTrue();
    Assertions.assertThat(server.isOpen()).isTrue();
  }

  static Stream<WebsocketCloseHandler> serverCloseHandlers() {
    return Stream.of(new WebsocketCloseHandler(false), new WebsocketCloseHandler(true));
  }

  @ParameterizedTest
  @MethodSource("clientCloseHandlers")
  void clientWebsocketClose(Consumer<Channel> clientCloseHandler) throws Exception {
    WebsocketCloseListenerHandler serverWebsocketCloseListenerHandler =
        new WebsocketCloseListenerHandler();
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
                          .handler("/test", serverWebsocketCloseListenerHandler)
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
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientHandler.builder().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                })
            .sync()
            .channel();

    ChannelFuture clientWebsocketHandshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", new ChannelInboundHandlerAdapter());

    clientWebsocketHandshake.await(55, TimeUnit.SECONDS);
    Channel clientWebsocketChannel = clientWebsocketHandshake.channel();
    clientCloseHandler.accept(clientWebsocketChannel);

    ChannelFuture serverWebsocketCloseFuture = serverWebsocketCloseListenerHandler.closeFuture();
    serverWebsocketCloseFuture.await(56, TimeUnit.SECONDS);
    Assertions.assertThat(serverWebsocketCloseFuture.isSuccess()).isTrue();
    Channel serverWebsocketChannel = serverWebsocketCloseFuture.channel();
    Assertions.assertThat(serverWebsocketChannel.isOpen()).isFalse();

    ChannelFuture clientWebsocketCloseFuture = clientWebsocketChannel.closeFuture();
    clientWebsocketCloseFuture.await(56, TimeUnit.SECONDS);
    Assertions.assertThat(clientWebsocketCloseFuture.isSuccess()).isTrue();
    Assertions.assertThat(clientWebsocketChannel.isOpen()).isFalse();

    Assertions.assertThat(client.isOpen()).isTrue();
    Assertions.assertThat(server.isOpen()).isTrue();
  }

  static Stream<Consumer<Channel>> clientCloseHandlers() {
    return Stream.of(
        channel -> channel.close(),
        channel ->
            channel.pipeline().fireUserEventTriggered(Http2WebSocketLocalCloseEvent.INSTANCE));
  }

  @Test
  void clientConnectionClose() throws Exception {
    WebsocketCloseListenerHandler serverWebsocketCloseListenerHandler =
        new WebsocketCloseListenerHandler();
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
                          .handler("/test", serverWebsocketCloseListenerHandler)
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
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientHandler.builder().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                })
            .sync()
            .channel();

    ChannelFuture clientHandshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", new ChannelInboundHandlerAdapter());

    clientHandshake.await(6, TimeUnit.SECONDS);
    Channel clientWebsocket = clientHandshake.channel();

    client.close();

    ChannelFuture clientWebsocketCloseFuture = clientWebsocket.closeFuture();
    clientWebsocketCloseFuture.await(5, TimeUnit.SECONDS);

    Assertions.assertThat(clientWebsocketCloseFuture.isSuccess()).isTrue();
    Assertions.assertThat(clientWebsocket.isOpen()).isFalse();

    ChannelFuture serverWebsocketCloseFuture = serverWebsocketCloseListenerHandler.closeFuture();
    Channel serverWebsocket = serverWebsocketCloseFuture.channel();
    serverWebsocketCloseFuture.await(5, TimeUnit.SECONDS);
    Assertions.assertThat(serverWebsocketCloseFuture.isSuccess()).isTrue();
    Assertions.assertThat(serverWebsocket.isOpen()).isFalse();
  }

  @Test
  void serverConnectionClose() throws Exception {
    ConnectionCloseHandler serverCloseHandler = new ConnectionCloseHandler();
    server =
        createServer(
                ch -> {
                  serverCloseHandler.parentChannel(ch);
                  SslHandler sslHandler = serverSslContext.newHandler(ch.alloc());
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      Http2FrameCodecBuilder.forServer().validateHeaders(false);
                  Http2Settings settings = http2FrameCodecBuilder.initialSettings();
                  settings.put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
                  Http2FrameCodec http2frameCodec = http2FrameCodecBuilder.build();

                  Http2WebSocketServerHandler http2webSocketHandler =
                      Http2WebSocketServerHandler.builder()
                          .handler("/test", serverCloseHandler)
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
                  Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
                  Http2WebSocketClientHandler http2WebSocketClientHandler =
                      Http2WebSocketClientHandler.builder().handshakeTimeoutMillis(5_000).build();
                  ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                })
            .sync()
            .channel();

    ChannelFuture clientHandshake =
        Http2WebSocketClientHandshaker.create(client)
            .handshake("/test", new ChannelInboundHandlerAdapter());

    clientHandshake.await(6, TimeUnit.SECONDS);
    Channel clientWebsocket = clientHandshake.channel();

    ChannelFuture clientWebsocketCloseFuture = clientWebsocket.closeFuture();
    clientWebsocketCloseFuture.await(5, TimeUnit.SECONDS);

    Assertions.assertThat(clientWebsocketCloseFuture.isSuccess()).isTrue();
    Assertions.assertThat(clientWebsocket.isOpen()).isFalse();

    Channel serverWebsocket = serverCloseHandler.websocketChannel();
    ChannelFuture serverWebsocketCloseFuture = serverWebsocket.closeFuture();
    serverWebsocketCloseFuture.await(5, TimeUnit.SECONDS);
    Assertions.assertThat(serverWebsocketCloseFuture.isSuccess()).isTrue();
    Assertions.assertThat(serverWebsocket.isOpen()).isFalse();
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

  private static class WebsocketCloseListenerHandler extends ChannelInboundHandlerAdapter {
    private volatile ChannelPromise closePromise;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      closePromise = ctx.newPromise();
      super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      closePromise.setSuccess();
      super.channelInactive(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof Http2WebSocketRemoteCloseEvent) {
        ctx.close();
      }
      super.userEventTriggered(ctx, evt);
    }

    public ChannelFuture closeFuture() throws InterruptedException {
      ChannelPromise p = closePromise;
      while (p == null) {
        Thread.sleep(10);
        p = closePromise;
      }
      return p;
    }
  }

  private static class ConnectionCloseHandler extends ChannelInboundHandlerAdapter {
    private Channel parentChannel;
    private volatile Channel channel;

    public void parentChannel(Channel parentChannel) {
      this.parentChannel = parentChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      channel = ctx.channel();
      parentChannel.close();
    }

    public Channel websocketChannel() {
      return channel;
    }
  }

  private static class WebsocketCloseHandler extends ChannelInboundHandlerAdapter {
    private volatile Channel channel;
    private final boolean isGraceful;

    public WebsocketCloseHandler(boolean isGraceful) {
      this.isGraceful = isGraceful;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      channel = ctx.channel();
      if (isGraceful) {
        ctx.fireUserEventTriggered(Http2WebSocketLocalCloseEvent.INSTANCE);
      } else {
        ctx.close();
      }
    }

    public Channel websocketChannel() {
      return channel;
    }
  }
}
