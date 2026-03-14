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

package com.jauntsdn.netty.handler.codec.http2.websocketx;

import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketCallbacksHandler;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameFactory;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class NomaskingTest extends AbstractTest {
  static final WebSocketDecoderConfig CLIENT_DECODER_CONFIG =
      WebSocketDecoderConfig.newBuilder()
          .maxFramePayloadLength(65535)
          .expectMaskedFrames(false)
          .allowMaskMismatch(true)
          .allowExtensions(false)
          .withUTF8Validator(false)
          .build();

  public static final WebSocketDecoderConfig SERVER_DECODER_CONFIG =
      WebSocketDecoderConfig.newBuilder()
          .maxFramePayloadLength(65535)
          .expectMaskedFrames(true)
          .allowMaskMismatch(true)
          .allowExtensions(false)
          .withUTF8Validator(false)
          .build();

  private Channel client;
  private Channel server;

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

  @Timeout(15)
  @Test
  void nonTlsClientNomasking() throws Exception {
    boolean noMaskingExtension = true;
    CompletableFuture<Http2Headers> requestHeaders = new CompletableFuture<>();
    CompletableFuture<Http2Headers> responseHeaders = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> serverWebSocketChannel = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> clientWebSocketChannel = new CompletableFuture<>();
    server =
        server(null, noMaskingExtension, requestHeaders, responseHeaders, serverWebSocketChannel);
    client = client(null, noMaskingExtension, server.localAddress());
    Http2WebSocketClientHandshaker handshaker = Http2WebSocketClientHandshaker.create(client);
    ChannelFuture handshakeFuture =
        handshaker.handshake("/test", new WebSocketHandler(clientWebSocketChannel));
    handshakeFuture.await();
    Assertions.assertThat(handshakeFuture.cause()).isNull();
    Http2Headers request = requestHeaders.get();
    Http2Headers response = responseHeaders.get();
    CharSequence requestExtensions = request.get(AsciiString.of("sec-websocket-extensions"));
    CharSequence responseExtensions = response.get(AsciiString.of("sec-websocket-extensions"));
    Assertions.assertThat(
        requestExtensions == null || !requestExtensions.toString().contains("no-masking"));
    Assertions.assertThat(
        responseExtensions == null || !responseExtensions.toString().contains("no-masking"));

    Http2WebSocketChannel serverChannel = serverWebSocketChannel.get();
    Assertions.assertThat(serverChannel.decoderConfig.expectMaskedFrames())
        .isEqualTo(SERVER_DECODER_CONFIG.expectMaskedFrames());
    Assertions.assertThat(serverChannel.decoderConfig.allowMaskMismatch())
        .isEqualTo(SERVER_DECODER_CONFIG.allowMaskMismatch());

    Http2WebSocketChannel clientChannel = clientWebSocketChannel.get();
    Assertions.assertThat(clientChannel.isEncoderMaskPayload).isTrue();
    Assertions.assertThat(clientChannel.decoderConfig.expectMaskedFrames())
        .isEqualTo(CLIENT_DECODER_CONFIG.expectMaskedFrames());
    Assertions.assertThat(clientChannel.decoderConfig.allowMaskMismatch())
        .isEqualTo(CLIENT_DECODER_CONFIG.allowMaskMismatch());
  }

  @Timeout(15)
  @Test
  void nonTlsServerNomasking() throws Exception {
    boolean noMaskingExtension = true;
    CompletableFuture<Http2Headers> requestHeaders = new CompletableFuture<>();
    CompletableFuture<Http2Headers> responseHeaders = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> serverWebSocketChannel = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> clientWebSocketChannel = new CompletableFuture<>();

    server =
        server(null, noMaskingExtension, requestHeaders, responseHeaders, serverWebSocketChannel);
    client = client(null, true, server.localAddress());
    Http2WebSocketClientHandshaker handshaker = Http2WebSocketClientHandshaker.create(client);
    Http2Headers headers = new DefaultHttp2Headers();
    headers.set(AsciiString.of("sec-websocket-extensions"), "no-masking");
    ChannelFuture handshakeFuture =
        handshaker.handshake("/test", "", headers, new WebSocketHandler(clientWebSocketChannel));
    handshakeFuture.await();
    Assertions.assertThat(handshakeFuture.cause()).isNull();
    Http2Headers request = requestHeaders.get();
    Http2Headers response = responseHeaders.get();
    CharSequence requestExtensions = request.get(AsciiString.of("sec-websocket-extensions"));
    CharSequence responseExtensions = response.get(AsciiString.of("sec-websocket-extensions"));
    Assertions.assertThat(
        requestExtensions != null && requestExtensions.toString().contains("no-masking"));
    Assertions.assertThat(
        responseExtensions == null || !responseExtensions.toString().contains("no-masking"));

    Http2WebSocketChannel serverChannel = serverWebSocketChannel.get();
    Assertions.assertThat(serverChannel.decoderConfig.expectMaskedFrames())
        .isEqualTo(SERVER_DECODER_CONFIG.expectMaskedFrames());
    Assertions.assertThat(serverChannel.decoderConfig.allowMaskMismatch())
        .isEqualTo(SERVER_DECODER_CONFIG.allowMaskMismatch());

    Http2WebSocketChannel clientChannel = clientWebSocketChannel.get();
    Assertions.assertThat(clientChannel.isEncoderMaskPayload).isTrue();
    Assertions.assertThat(clientChannel.decoderConfig.expectMaskedFrames())
        .isEqualTo(CLIENT_DECODER_CONFIG.expectMaskedFrames());
    Assertions.assertThat(clientChannel.decoderConfig.allowMaskMismatch())
        .isEqualTo(CLIENT_DECODER_CONFIG.allowMaskMismatch());
  }

  @Timeout(15)
  @Test
  void nomaskingClientServerEnabled() throws Exception {
    boolean noMaskingExtension = true;
    SslContext serverSslContext = serverSslContext("localhost.p12", "localhost");
    SslContext clientSslContext = clientSslContext();
    CompletableFuture<Http2Headers> requestHeaders = new CompletableFuture<>();
    CompletableFuture<Http2Headers> responseHeaders = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> serverWebSocketChannel = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> clientWebSocketChannel = new CompletableFuture<>();

    server =
        server(
            serverSslContext,
            noMaskingExtension,
            requestHeaders,
            responseHeaders,
            serverWebSocketChannel);
    client = client(clientSslContext, noMaskingExtension, server.localAddress());
    Http2WebSocketClientHandshaker handshaker = Http2WebSocketClientHandshaker.create(client);
    ChannelFuture handshakeFuture =
        handshaker.handshake("/test", new WebSocketHandler(clientWebSocketChannel));
    handshakeFuture.await();
    Assertions.assertThat(handshakeFuture.cause()).isNull();
    Http2Headers request = requestHeaders.get();
    Http2Headers response = responseHeaders.get();
    CharSequence requestExtensions = request.get(AsciiString.of("sec-websocket-extensions"));
    CharSequence responseExtensions = response.get(AsciiString.of("sec-websocket-extensions"));
    Assertions.assertThat(
        requestExtensions != null && requestExtensions.toString().contains("no-masking"));
    Assertions.assertThat(
        responseExtensions != null && responseExtensions.toString().contains("no-masking"));

    Http2WebSocketChannel serverChannel = serverWebSocketChannel.get();
    Assertions.assertThat(serverChannel.decoderConfig.expectMaskedFrames()).isEqualTo(false);
    Assertions.assertThat(serverChannel.decoderConfig.allowMaskMismatch()).isEqualTo(false);

    Http2WebSocketChannel clientChannel = clientWebSocketChannel.get();
    Assertions.assertThat(clientChannel.isEncoderMaskPayload).isFalse();
    Assertions.assertThat(clientChannel.decoderConfig.expectMaskedFrames()).isEqualTo(false);
    Assertions.assertThat(clientChannel.decoderConfig.allowMaskMismatch()).isEqualTo(false);
  }

  @Timeout(15)
  @Test
  void nomaskingClientDisabledServerEnabled() throws Exception {
    SslContext serverSslContext = serverSslContext("localhost.p12", "localhost");
    SslContext clientSslContext = clientSslContext();
    CompletableFuture<Http2Headers> requestHeaders = new CompletableFuture<>();
    CompletableFuture<Http2Headers> responseHeaders = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> serverWebSocketChannel = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> clientWebSocketChannel = new CompletableFuture<>();
    server =
        server(serverSslContext, true, requestHeaders, responseHeaders, serverWebSocketChannel);
    client = client(clientSslContext, false, server.localAddress());
    Http2WebSocketClientHandshaker handshaker = Http2WebSocketClientHandshaker.create(client);
    ChannelFuture handshakeFuture =
        handshaker.handshake("/test", new WebSocketHandler(clientWebSocketChannel));
    handshakeFuture.await();
    Assertions.assertThat(handshakeFuture.cause()).isNull();
    Http2Headers request = requestHeaders.get();
    Http2Headers response = responseHeaders.get();
    CharSequence requestExtensions = request.get(AsciiString.of("sec-websocket-extensions"));
    CharSequence responseExtensions = response.get(AsciiString.of("sec-websocket-extensions"));
    Assertions.assertThat(
        requestExtensions == null || !requestExtensions.toString().contains("no-masking"));
    Assertions.assertThat(
        responseExtensions == null || !responseExtensions.toString().contains("no-masking"));

    Http2WebSocketChannel serverChannel = serverWebSocketChannel.get();
    Assertions.assertThat(serverChannel.decoderConfig.expectMaskedFrames())
        .isEqualTo(SERVER_DECODER_CONFIG.expectMaskedFrames());
    Assertions.assertThat(serverChannel.decoderConfig.allowMaskMismatch())
        .isEqualTo(SERVER_DECODER_CONFIG.allowMaskMismatch());

    Http2WebSocketChannel clientChannel = clientWebSocketChannel.get();
    Assertions.assertThat(clientChannel.isEncoderMaskPayload).isTrue();
    Assertions.assertThat(clientChannel.decoderConfig.expectMaskedFrames())
        .isEqualTo(CLIENT_DECODER_CONFIG.expectMaskedFrames());
    Assertions.assertThat(clientChannel.decoderConfig.allowMaskMismatch())
        .isEqualTo(CLIENT_DECODER_CONFIG.allowMaskMismatch());
  }

  @Timeout(15)
  @Test
  void nomaskingClientEnabledServerDisabled() throws Exception {
    SslContext serverSslContext = serverSslContext("localhost.p12", "localhost");
    SslContext clientSslContext = clientSslContext();
    CompletableFuture<Http2Headers> requestHeaders = new CompletableFuture<>();
    CompletableFuture<Http2Headers> responseHeaders = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> serverWebSocketChannel = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> clientWebSocketChannel = new CompletableFuture<>();
    server =
        server(serverSslContext, false, requestHeaders, responseHeaders, serverWebSocketChannel);
    client = client(clientSslContext, true, server.localAddress());
    Http2WebSocketClientHandshaker handshaker = Http2WebSocketClientHandshaker.create(client);
    ChannelFuture handshakeFuture =
        handshaker.handshake("/test", new WebSocketHandler(clientWebSocketChannel));
    handshakeFuture.await();
    Assertions.assertThat(handshakeFuture.cause()).isNull();
    Http2Headers request = requestHeaders.get();
    Http2Headers response = responseHeaders.get();
    CharSequence requestExtensions = request.get(AsciiString.of("sec-websocket-extensions"));
    CharSequence responseExtensions = response.get(AsciiString.of("sec-websocket-extensions"));
    Assertions.assertThat(
        requestExtensions != null && requestExtensions.toString().contains("no-masking"));
    Assertions.assertThat(
        responseExtensions == null || !responseExtensions.toString().contains("no-masking"));

    Http2WebSocketChannel serverChannel = serverWebSocketChannel.get();
    Assertions.assertThat(serverChannel.decoderConfig.expectMaskedFrames())
        .isEqualTo(SERVER_DECODER_CONFIG.expectMaskedFrames());
    Assertions.assertThat(serverChannel.decoderConfig.allowMaskMismatch())
        .isEqualTo(SERVER_DECODER_CONFIG.allowMaskMismatch());

    Http2WebSocketChannel clientChannel = clientWebSocketChannel.get();
    Assertions.assertThat(clientChannel.isEncoderMaskPayload).isTrue();
    Assertions.assertThat(clientChannel.decoderConfig.expectMaskedFrames())
        .isEqualTo(CLIENT_DECODER_CONFIG.expectMaskedFrames());
    Assertions.assertThat(clientChannel.decoderConfig.allowMaskMismatch())
        .isEqualTo(CLIENT_DECODER_CONFIG.allowMaskMismatch());
  }

  @Timeout(15)
  @Test
  void nomaskingClientServerEnabledCallbacksCodec() throws Exception {
    boolean noMaskingExtension = true;
    WebSocketCallbacksCodec webSocketCodec = WebSocketCallbacksCodec.instance();

    SslContext serverSslContext = serverSslContext("localhost.p12", "localhost");
    SslContext clientSslContext = clientSslContext();
    CompletableFuture<Http2Headers> requestHeaders = new CompletableFuture<>();
    CompletableFuture<Http2Headers> responseHeaders = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> serverWebSocketChannel = new CompletableFuture<>();
    CompletableFuture<Http2WebSocketChannel> clientWebSocketChannel = new CompletableFuture<>();

    server =
        server(
            serverSslContext,
            noMaskingExtension,
            webSocketCodec,
            requestHeaders,
            responseHeaders,
            serverWebSocketChannel);

    client = client(clientSslContext, noMaskingExtension, webSocketCodec, server.localAddress());
    Http2WebSocketClientHandshaker handshaker = Http2WebSocketClientHandshaker.create(client);
    ChannelFuture handshakeFuture =
        handshaker.handshake("/test", new CallbacksWebSocketHandler(clientWebSocketChannel));
    handshakeFuture.await();
    Assertions.assertThat(handshakeFuture.cause()).isNull();
    Http2Headers request = requestHeaders.get();
    Http2Headers response = responseHeaders.get();
    CharSequence requestExtensions = request.get(AsciiString.of("sec-websocket-extensions"));
    CharSequence responseExtensions = response.get(AsciiString.of("sec-websocket-extensions"));
    Assertions.assertThat(
        requestExtensions != null && requestExtensions.toString().contains("no-masking"));
    Assertions.assertThat(
        responseExtensions != null && responseExtensions.toString().contains("no-masking"));

    Http2WebSocketChannel serverChannel = serverWebSocketChannel.get();
    Assertions.assertThat(serverChannel.decoderConfig.expectMaskedFrames()).isEqualTo(false);
    Assertions.assertThat(serverChannel.decoderConfig.allowMaskMismatch()).isEqualTo(false);

    Http2WebSocketChannel clientChannel = clientWebSocketChannel.get();
    Assertions.assertThat(clientChannel.isEncoderMaskPayload).isFalse();
    Assertions.assertThat(clientChannel.decoderConfig.expectMaskedFrames()).isEqualTo(false);
    Assertions.assertThat(clientChannel.decoderConfig.allowMaskMismatch()).isEqualTo(false);
  }

  static class WebSocketHandler extends ChannelInboundHandlerAdapter {
    private final CompletableFuture<Http2WebSocketChannel> webSocketChannel;

    WebSocketHandler(CompletableFuture<Http2WebSocketChannel> webSocketChannel) {
      this.webSocketChannel = webSocketChannel;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof WebSocketEvent.WebSocketHandshakeSuccessEvent) {
        Http2WebSocketChannel channel = (Http2WebSocketChannel) ctx.channel();
        this.webSocketChannel.complete(channel);
      }
      super.userEventTriggered(ctx, evt);
    }
  }

  static class CallbacksWebSocketHandler extends ChannelInboundHandlerAdapter
      implements WebSocketCallbacksHandler {
    private final CompletableFuture<Http2WebSocketChannel> webSocketChannel;

    CallbacksWebSocketHandler(CompletableFuture<Http2WebSocketChannel> webSocketChannel) {
      this.webSocketChannel = webSocketChannel;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof WebSocketEvent.WebSocketHandshakeSuccessEvent) {
        WebSocketCallbacksHandler.exchange(ctx, this);
        Http2WebSocketChannel channel = (Http2WebSocketChannel) ctx.channel();
        this.webSocketChannel.complete(channel);
      }
      super.userEventTriggered(ctx, evt);
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      return (c, finalFragment, rsv, opcode, payload) -> payload.release();
    }
  }

  private static Channel server(
      SslContext sslContext,
      boolean noMaskingExtension,
      CompletableFuture<Http2Headers> requestHeaders,
      CompletableFuture<Http2Headers> responseHeaders,
      CompletableFuture<Http2WebSocketChannel> webSocketChannel)
      throws Exception {
    return server(
        sslContext,
        noMaskingExtension,
        Http1WebSocketCodec.DEFAULT,
        requestHeaders,
        responseHeaders,
        webSocketChannel);
  }

  private static Channel server(
      SslContext sslContext,
      boolean noMaskingExtension,
      Http1WebSocketCodec webSocketCodec,
      CompletableFuture<Http2Headers> requestHeaders,
      CompletableFuture<Http2Headers> responseHeaders,
      CompletableFuture<Http2WebSocketChannel> webSocketChannel)
      throws Exception {
    return new ServerBootstrap()
        .group(new NioEventLoopGroup())
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ServerAcceptor(
                sslContext,
                noMaskingExtension,
                webSocketCodec,
                requestHeaders,
                responseHeaders,
                webSocketChannel))
        .bind("localhost", 0)
        .sync()
        .channel();
  }

  private static class ServerAcceptor extends ChannelInitializer<SocketChannel> {
    private final SslContext sslContext;
    private final boolean noMaskingExtension;
    private final CompletableFuture<Http2Headers> requestHeaders;
    private final CompletableFuture<Http2Headers> responseHeaders;
    private final CompletableFuture<Http2WebSocketChannel> webSocketChannel;
    private final Http1WebSocketCodec webSocketCodec;

    ServerAcceptor(
        SslContext sslContext,
        boolean noMaskingExtension,
        Http1WebSocketCodec webSocketCodec,
        CompletableFuture<Http2Headers> requestHeaders,
        CompletableFuture<Http2Headers> responseHeaders,
        CompletableFuture<Http2WebSocketChannel> webSocketChannel) {
      this.sslContext = sslContext;
      this.noMaskingExtension = noMaskingExtension;
      this.webSocketCodec = webSocketCodec;
      this.requestHeaders = requestHeaders;
      this.responseHeaders = responseHeaders;
      this.webSocketChannel = webSocketChannel;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      Http2FrameCodec http2frameCodec =
          Http2WebSocketServerBuilder.configureHttp2Server(Http2FrameCodecBuilder.forServer())
              .build();

      Http2WebSocketServerHandler http2webSocketHandler =
          Http2WebSocketServerBuilder.create()
              .codec(webSocketCodec)
              .nomaskingExtension(noMaskingExtension)
              .decoderConfig(SERVER_DECODER_CONFIG)
              .acceptor(
                  (ctx, path, subprotocols, request, response) -> {
                    requestHeaders.complete(request);
                    responseHeaders.complete(response);
                    ChannelHandler webSocketHandler =
                        webSocketCodec == Http1WebSocketCodec.DEFAULT
                            ? new WebSocketHandler(webSocketChannel)
                            : new CallbacksWebSocketHandler(webSocketChannel);
                    return ctx.executor().newSucceededFuture(webSocketHandler);
                  })
              .build();

      ChannelPipeline pipeline = ch.pipeline();
      if (sslContext != null) {
        SslHandler sslHandler = sslContext.newHandler(ch.alloc());
        pipeline.addLast(sslHandler);
      }
      pipeline.addLast(http2frameCodec, http2webSocketHandler);
    }
  }

  private static Channel client(
      SslContext sslContext,
      boolean noMaskingExtension,
      Http1WebSocketCodec webSocketCodec,
      SocketAddress address)
      throws Exception {
    return new Bootstrap()
        .group(new NioEventLoopGroup())
        .channel(NioSocketChannel.class)
        .handler(new ClientHandler(sslContext, noMaskingExtension, webSocketCodec))
        .connect(address)
        .sync()
        .channel();
  }

  private static Channel client(
      SslContext sslContext, boolean noMaskingExtension, SocketAddress address) throws Exception {
    return client(sslContext, noMaskingExtension, Http1WebSocketCodec.DEFAULT, address);
  }

  private static class ClientHandler extends ChannelInitializer<SocketChannel> {
    private final SslContext sslContext;
    private final boolean noMaskingExtension;
    private final Http1WebSocketCodec webSocketCodec;

    ClientHandler(
        SslContext sslContext, boolean noMaskingExtension, Http1WebSocketCodec webSocketCodec) {
      this.sslContext = sslContext;
      this.noMaskingExtension = noMaskingExtension;
      this.webSocketCodec = webSocketCodec;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
      Http2WebSocketClientHandler http2WebSocketClientHandler =
          Http2WebSocketClientBuilder.create()
              .codec(webSocketCodec)
              .handshakeTimeoutMillis(5_000)
              .maskPayload(true)
              .decoderConfig(CLIENT_DECODER_CONFIG)
              .nomaskingExtension(noMaskingExtension)
              .build();
      ChannelPipeline pipeline = ch.pipeline();
      if (sslContext != null) {
        SslHandler sslHandler = sslContext.newHandler(ch.alloc());
        pipeline.addLast(sslHandler);
      }
      pipeline.addLast(http2FrameCodec, http2WebSocketClientHandler);
    }
  }
}
