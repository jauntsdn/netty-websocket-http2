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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import java.io.InputStream;
import java.net.SocketAddress;
import java.security.KeyStore;
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
    return serverSslContext("localhost.p12", "localhost");
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

  static final class PathAcceptor implements Http2WebSocketAcceptor {
    private final String path;
    private final ChannelHandler webSocketHandler;

    PathAcceptor(String path, ChannelHandler webSocketHandler) {
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
      if (subprotocols.isEmpty() && path.equals(this.path)) {
        return ctx.executor().newSucceededFuture(webSocketHandler);
      }
      return ctx.executor()
          .newFailedFuture(
              new WebSocketHandshakeException(
                  String.format("Path not found: %s , subprotocols: %s", path, subprotocols)));
    }
  }

  static final class PathSubprotocolAcceptor implements Http2WebSocketAcceptor {
    private final ChannelHandler webSocketHandler;
    private final String path;
    private final String subprotocol;
    private final boolean acceptSubprotocol;

    public PathSubprotocolAcceptor(
        String path, String subprotocol, ChannelHandler webSocketHandler) {
      this(path, subprotocol, webSocketHandler, true);
    }

    public PathSubprotocolAcceptor(
        String path,
        String subprotocol,
        ChannelHandler webSocketHandler,
        boolean acceptSubprotocol) {
      this.path = path;
      this.subprotocol = subprotocol;
      this.webSocketHandler = webSocketHandler;
      this.acceptSubprotocol = acceptSubprotocol;
    }

    @Override
    public Future<ChannelHandler> accept(
        ChannelHandlerContext ctx,
        String path,
        List<String> subprotocols,
        Http2Headers request,
        Http2Headers response) {
      String subprotocol = this.subprotocol;
      if (path.equals(this.path) && subprotocols.contains(subprotocol)) {
        if (acceptSubprotocol) {
          Subprotocol.accept(subprotocol, response);
        }
        return ctx.executor().newSucceededFuture(webSocketHandler);
      }
      return ctx.executor()
          .newFailedFuture(
              new Http2WebSocketPathNotFoundException(
                  String.format("Path not found: %s , subprotocols: %s", path, subprotocols)));
    }
  }
}
