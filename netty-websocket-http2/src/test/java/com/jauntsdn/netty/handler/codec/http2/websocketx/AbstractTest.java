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

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.InputStream;
import java.net.SocketAddress;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;

abstract class AbstractTest {

  static ChannelFuture createClient(
      SocketAddress address, Consumer<SocketChannel> pipelineConfigurer) {
    return new Bootstrap()
        .group(new NioEventLoopGroup())
        .channel(NioSocketChannel.class)
        .handler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                pipelineConfigurer.accept(ch);
              }
            })
        .connect(address);
  }

  static ChannelFuture createServer(Consumer<SocketChannel> pipelineConfigurer) {
    return new ServerBootstrap()
        .group(new NioEventLoopGroup())
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {

              @Override
              protected void initChannel(SocketChannel ch) {
                pipelineConfigurer.accept(ch);
              }
            })
        .bind("localhost", 0);
  }

  static SslContext serverSslContext() throws Exception {
    SecureRandom random = new SecureRandom();
    SelfSignedCertificate ssc = new SelfSignedCertificate("com.jauntsdn", random, 1024);

    return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
        .protocols("TLSv1.3")
        .sslProvider(sslProvider())
        .applicationProtocolConfig(alpnConfig())
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .build();
  }

  static SslContext serverSslContext(String keystoreFile, String keystorePassword)
      throws Exception {
    SslProvider sslProvider = sslProvider();
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    InputStream keystoreStream =
        PingPongTest.class.getClassLoader().getResourceAsStream(keystoreFile);
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

  static class WebsocketEventsHandler extends ChannelInboundHandlerAdapter {
    private final int eventsCount;
    private final List<Http2WebSocketEvent> events = new ArrayList<>();
    private volatile ChannelPromise eventsReceived;

    public WebsocketEventsHandler(int eventsCount) {
      this.eventsCount = eventsCount;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      eventsReceived = ctx.newPromise();
      super.handlerAdded(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof Http2WebSocketEvent) {
        events.add((Http2WebSocketEvent) evt);
        if (events.size() == eventsCount) {
          eventsReceived.setSuccess();
        }
      }
      super.userEventTriggered(ctx, evt);
    }

    ChannelFuture eventsReceived() {
      return eventsReceived;
    }

    public List<Http2WebSocketEvent> events() {
      return events;
    }
  }
}
