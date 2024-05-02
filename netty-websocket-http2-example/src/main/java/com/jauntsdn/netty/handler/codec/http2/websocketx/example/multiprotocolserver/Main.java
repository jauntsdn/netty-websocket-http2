/*
 * Copyright 2022 - present Maksym Ostroverkhov.
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

package com.jauntsdn.netty.handler.codec.http2.websocketx.example.multiprotocolserver;

import static io.netty.channel.ChannelHandler.Sharable;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketAcceptor;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.example.Security;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    String host = System.getProperty("HOST", "localhost");
    int port = Integer.parseInt(System.getProperty("PORT", "8099"));
    String echoPath = System.getProperty("PING", "echo");
    String keyStoreFile = System.getProperty("KEYSTORE", "localhost.p12");
    String keyStorePassword = System.getProperty("KEYSTORE_PASS", "localhost");

    logger.info("\n==> http1/http2 websocket server\n");
    logger.info("\n==> Bind address: {}:{}", host, port);
    logger.info("\n==> Keystore file: {}", keyStoreFile);

    SslContext sslContext = Security.serverSslContext(keyStoreFile, keyStorePassword);

    ServerBootstrap bootstrap = new ServerBootstrap();
    Channel server =
        bootstrap
            .group(new NioEventLoopGroup())
            .channel(NioServerSocketChannel.class)
            .childHandler(new ConnectionAcceptor(sslContext))
            .bind(host, port)
            .sync()
            .channel();
    logger.info("\n==> Server is listening on {}:{}", host, port);

    logger.info("\n==> Echo path: {}", echoPath);
    server.closeFuture().sync();
  }

  private static class ConnectionAcceptor extends ChannelInitializer<SocketChannel> {
    private static final List<String> SUPPORTED_USER_AGENTS =
        Collections.singletonList("jauntsdn-websocket-http2-client/");
    private final SslContext sslContext;

    ConnectionAcceptor(SslContext sslContext) {
      this.sslContext = sslContext;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      SslHandler sslHandler = sslContext.newHandler(ch.alloc());
      ChannelHandler http1webSocketHandler = new EchoWebSocketHandler();
      ApplicationProtocolNegotiationHandler webSocketProtocolVersionHandler =
          new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
            @Override
            protected void configurePipeline(ChannelHandlerContext c, String protocol) {

              switch (protocol) {
                case ApplicationProtocolNames.HTTP_2:
                  logger.info("Server accepted TLS connection for websockets-over-http2");

                  Http2FrameCodecBuilder http2Builder =
                      Http2WebSocketServerBuilder.configureHttp2Server(
                          Http2FrameCodecBuilder.forServer());
                  http2Builder.initialSettings().initialWindowSize(1_000);
                  Http2FrameCodec http2frameCodec = http2Builder.build();

                  Http2WebSocketServerHandler http2webSocketHandler =
                      Http2WebSocketServerBuilder.create()
                          .compression(true)
                          .acceptor(
                              (ctx, path, subprotocols, request, response) -> {
                                if ("/echo".equals(path)) {
                                  if (subprotocols.contains("echo.jauntsdn.com")
                                      && acceptUserAgent(request, response)) {
                                    Http2WebSocketAcceptor.Subprotocol.accept(
                                        "echo.jauntsdn.com", response);
                                    return ctx.executor().newSucceededFuture(http1webSocketHandler);
                                  }
                                }
                                return ctx.executor()
                                    .newFailedFuture(
                                        new WebSocketHandshakeException(
                                            String.format(
                                                "websocket rejected, path: %s, subprotocols: %s, user-agent: %s",
                                                path, subprotocols, request.get("user-agent"))));
                              })
                          .build();

                  ch.pipeline().addLast(http2frameCodec, http2webSocketHandler);
                  break;

                case ApplicationProtocolNames.HTTP_1_1:
                  logger.info("Server accepted TLS connection for websockets-over-http1");

                  HttpServerCodec http1Codec = new HttpServerCodec();
                  HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);
                  WebSocketServerCompressionHandler http1WebSocketCompressor =
                      new WebSocketServerCompressionHandler();
                  WebSocketServerProtocolHandler http1WebSocketProtocolHandler =
                      new WebSocketServerProtocolHandler("/echo", "echo.jauntsdn.com", true);

                  ch.pipeline()
                      .addLast(http1Codec)
                      .addLast(http1Aggregator)
                      .addLast(http1WebSocketCompressor)
                      .addLast(http1WebSocketProtocolHandler)
                      .addLast(http1webSocketHandler);
                  break;

                default:
                  logger.info("Unsupported protocol for TLS connection: {}", protocol);
                  c.close();
              }
            }
          };

      ch.pipeline().addLast(sslHandler, webSocketProtocolVersionHandler);
    }

    private boolean acceptUserAgent(Http2Headers request, Http2Headers response) {
      CharSequence userAgentSeq = request.get("user-agent");
      if (userAgentSeq == null || userAgentSeq.length() == 0) {
        return false;
      }
      String userAgent = userAgentSeq.toString();
      for (String supportedUserAgent : SUPPORTED_USER_AGENTS) {
        int index = userAgent.indexOf(supportedUserAgent);
        if (index >= 0) {
          int length = supportedUserAgent.length();
          String version = userAgent.substring(index + length);
          String clientId = supportedUserAgent.substring(0, length - 1);
          request.set("x-client-id", clientId);
          request.set("x-client-version", version);
          response.set("x-request-id", UUID.randomUUID().toString());
          return true;
        }
      }
      return false;
    }
  }

  @Sharable
  private static class EchoWebSocketHandler
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
      if (cause instanceof IOException) {
        return;
      }
      logger.error("Unexpected websocket error", cause);
      ctx.close();
    }
  }
}
