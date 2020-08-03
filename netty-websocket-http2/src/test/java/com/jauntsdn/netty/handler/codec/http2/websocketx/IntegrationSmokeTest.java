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

import static io.netty.channel.ChannelHandler.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.InputStream;
import java.net.SocketAddress;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class IntegrationSmokeTest {
  private Channel client;
  private Channel server;

  @BeforeEach
  void setUp() throws Exception {
    String keyStoreFile = System.getProperty("KEYSTORE", "localhost.p12");
    String keyStorePassword = System.getProperty("KEYSTORE_PASS", "localhost");

    ServerBootstrap bootstrap = new ServerBootstrap();
    server =
        bootstrap
            .group(new NioEventLoopGroup())
            .channel(NioServerSocketChannel.class)
            .childHandler(new ServerAcceptor(serverSslContext(keyStoreFile, keyStorePassword)))
            .bind("localhost", 0)
            .sync()
            .channel();

    SocketAddress address = server.localAddress();

    client =
        new Bootstrap()
            .group(new NioEventLoopGroup())
            .channel(NioSocketChannel.class)
            .handler(new ClientHandler(clientSslContext()))
            .connect(address)
            .sync()
            .channel();
  }

  @AfterEach
  void tearDown() throws Exception {
    client.eventLoop().shutdownGracefully(0, 5, TimeUnit.SECONDS);
    client.closeFuture().await(5, TimeUnit.SECONDS);
    server.eventLoop().shutdownGracefully(0, 5, TimeUnit.SECONDS);
    server.closeFuture().await(5, TimeUnit.SECONDS);
  }

  @Test
  void pingPong() throws Exception {
    Http2WebSocketClientHandshaker handshaker = Http2WebSocketClientHandshaker.create(client);
    ChannelPromise pongReceived = client.newPromise();
    ClientWebSocketHandler webSocketHandler = new ClientWebSocketHandler(pongReceived);

    ChannelFuture handshakeFuture = handshaker.handshake("/test", webSocketHandler);
    handshakeFuture.await();
    Throwable cause = handshakeFuture.cause();
    if (cause != null) {
      throw new IllegalStateException("smoke test handshake error", cause);
    }
    boolean success = pongReceived.await(5, TimeUnit.SECONDS);
    if (!success) {
      throw new IllegalStateException("smoke test timeout");
    }
    cause = pongReceived.cause();
    if (cause != null) {
      throw new IllegalStateException(cause);
    }
  }

  private static class ServerAcceptor extends ChannelInitializer<SocketChannel> {
    private final SslContext sslContext;

    ServerAcceptor(SslContext sslContext) {
      this.sslContext = sslContext;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      SslHandler sslHandler = sslContext.newHandler(ch.alloc());
      Http2FrameCodec http2frameCodec =
          Http2WebSocketServerHandler.configureHttp2Server(Http2FrameCodecBuilder.forServer())
              .build();

      ServerWebSocketHandler serverWebSocketHandler = new ServerWebSocketHandler();
      Http2WebSocketServerHandler http2webSocketHandler =
          Http2WebSocketServerHandler.builder()
              .decoderConfig(WebSocketDecoderConfig.newBuilder().allowExtensions(true).build())
              .compression(true)
              .handler("/test", serverWebSocketHandler)
              .build();

      ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler);
    }

    @Sharable
    private static class ServerWebSocketHandler
        extends SimpleChannelInboundHandler<TextWebSocketFrame> {

      @Override
      protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame webSocketFrame) {
        ctx.writeAndFlush(webSocketFrame.retain());
      }
    }
  }

  private static class ClientHandler extends ChannelInitializer<SocketChannel> {
    private final SslContext sslContext;

    public ClientHandler(SslContext sslContext) {
      this.sslContext = sslContext;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      SslHandler sslHandler = sslContext.newHandler(ch.alloc());
      Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forClient().build();
      Http2WebSocketClientHandler http2WebSocketClientHandler =
          Http2WebSocketClientHandler.builder().handshakeTimeoutMillis(5_000).build();
      ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
    }
  }

  private static class ClientWebSocketHandler extends ChannelDuplexHandler {
    private static final String PING = "ping";
    private final ChannelPromise pongReceived;

    public ClientWebSocketHandler(ChannelPromise pongReceived) {
      this.pongReceived = pongReceived;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof TextWebSocketFrame) {
        TextWebSocketFrame webSocketFrame = (TextWebSocketFrame) msg;
        String content = webSocketFrame.text();
        webSocketFrame.release();

        ChannelPromise received = pongReceived;
        if (PING.equals(content)) {
          received.setSuccess();
        } else {
          received.setFailure(
              new IllegalStateException(
                  String.format("Unexpected pong content: %s, expected: %s", content, PING)));
        }
        return;
      }
      super.channelRead(ctx, msg);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      ctx.writeAndFlush(new TextWebSocketFrame(PING));
      super.channelActive(ctx);
    }
  }

  static SslContext serverSslContext(String keystoreFile, String keystorePassword)
      throws Exception {
    SslProvider sslProvider = sslProvider();
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    InputStream keystoreStream =
        IntegrationSmokeTest.class.getClassLoader().getResourceAsStream(keystoreFile);
    char[] keystorePasswordArray = keystorePassword.toCharArray();
    keyStore.load(keystoreStream, keystorePasswordArray);

    KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
    keyManagerFactory.init(keyStore, keystorePasswordArray);

    return SslContextBuilder.forServer(keyManagerFactory)
        .protocols("TLSv1.3")
        .sslProvider(sslProvider)
        .applicationProtocolConfig(alpnConfig())
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .build();
  }

  static SslContext clientSslContext() throws SSLException {
    return SslContextBuilder.forClient()
        .protocols("TLSv1.3")
        .sslProvider(sslProvider())
        .applicationProtocolConfig(alpnConfig())
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .build();
  }

  static ApplicationProtocolConfig alpnConfig() {
    return new ApplicationProtocolConfig(
        ApplicationProtocolConfig.Protocol.ALPN,
        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
        ApplicationProtocolNames.HTTP_2);
  }

  static SslProvider sslProvider() {
    return SslProvider.OPENSSL_REFCNT;
  }
}
