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

package com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.messagecodec.server;

import static io.netty.channel.ChannelHandler.*;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.Security;
import com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.Transport;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {

    String host = System.getProperty("HOST", "localhost");
    int port = Integer.parseInt(System.getProperty("PORT", "8088"));
    String keyStoreFile = System.getProperty("KEYSTORE", "localhost.p12");
    String keyStorePassword = System.getProperty("KEYSTORE_PASS", "localhost");
    boolean isNativeTransport =
        Boolean.parseBoolean(System.getProperty("NATIVE_TRANSPORT", "true"));
    int flowControlWindowSize =
        Integer.parseInt(System.getProperty("FLOW_CONTROL_WINDOW", "100000"));
    boolean isOpensslAvailable = OpenSsl.isAvailable();
    boolean isEpollAvailable = Epoll.isAvailable();
    boolean isKqueueAvailable = KQueue.isAvailable();

    logger.info("\n==> http2 websocket load test server\n");
    logger.info("\n==> bind address: {}:{}", host, port);
    logger.info("\n==> flow control window size: {}", flowControlWindowSize);
    logger.info("\n==> native transport: {}", isNativeTransport);
    logger.info("\n==> epoll available: {}", isEpollAvailable);
    logger.info("\n==> kqueue available: {}", isKqueueAvailable);
    logger.info("\n==> openssl available: {}", isOpensslAvailable);

    Transport transport = Transport.get(isNativeTransport);

    SslContext sslContext = Security.serverSslContext(keyStoreFile, keyStorePassword);

    ServerBootstrap bootstrap = new ServerBootstrap();
    Channel server =
        bootstrap
            .group(transport.eventLoopGroup())
            .channel(transport.serverChannel())
            .childHandler(new ConnectionAcceptor(flowControlWindowSize, sslContext))
            .bind(host, port)
            .sync()
            .channel();
    logger.info("\n==> Server is listening on {}:{}", host, port);
    server.closeFuture().sync();
  }

  private static class ConnectionAcceptor extends ChannelInitializer<SocketChannel> {
    private final int flowControlWindowSize;
    private final SslContext sslContext;

    ConnectionAcceptor(int flowControlWindowSize, SslContext sslContext) {
      this.flowControlWindowSize = flowControlWindowSize;
      this.sslContext = sslContext;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      SslHandler sslHandler = sslContext.newHandler(ch.alloc());

      Http2FrameCodecBuilder http2Builder =
          Http2WebSocketServerBuilder.configureHttp2Server(Http2FrameCodecBuilder.forServer());
      http2Builder.initialSettings().initialWindowSize(flowControlWindowSize);
      Http2FrameCodec http2FrameCodec = http2Builder.build();
      Http2Connection connection = http2FrameCodec.connection();
      connection
          .remote()
          .flowController(
              new DefaultHttp2RemoteFlowController(
                  connection, new UniformStreamByteDistributor(connection)));

      EchoWebSocketHandler echoWebSocketHandler = new EchoWebSocketHandler();
      Http2WebSocketServerHandler http2webSocketHandler =
          Http2WebSocketServerBuilder.create()
              .assumeSingleWebSocketPerConnection(true)
              .acceptor(
                  (ctx, path, subprotocols, request, response) -> {
                    if ("/echo".equals(path) && subprotocols.isEmpty()) {
                      return ctx.executor().newSucceededFuture(echoWebSocketHandler);
                    }
                    return ctx.executor()
                        .newFailedFuture(
                            new WebSocketHandshakeException(
                                String.format(
                                    "path not found: %s, subprotocols: %s", path, subprotocols)));
                  })
              .build();

      ExceptionHandler exceptionHandler = new ExceptionHandler();

      ch.pipeline().addLast(sslHandler, http2FrameCodec, http2webSocketHandler, exceptionHandler);
    }
  }

  private static class ExceptionHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.error("Unexpected connection error", cause);
      ctx.close();
    }
  }

  @Sharable
  private static class EchoWebSocketHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      if (!(msg instanceof BinaryWebSocketFrame)) {
        ReferenceCountUtil.safeRelease(msg);
        return;
      }
      ctx.write(msg);
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
      logger.info("Unexpected websocket error", cause);
      ctx.close();
    }
  }
}
