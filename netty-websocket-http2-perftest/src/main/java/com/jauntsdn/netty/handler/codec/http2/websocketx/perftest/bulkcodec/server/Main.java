/*
 * Copyright 2023 - present Maksym Ostroverkhov.
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

package com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.bulkcodec.server;

import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketCallbacksHandler;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameFactory;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameListener;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketProtocol;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.WebSocketCallbacksCodec;
import com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.Security;
import com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.Transport;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http2.DefaultHttp2RemoteFlowController;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.UniformStreamByteDistributor;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {

    String host = System.getProperty("HOST", "localhost");
    int port = Integer.parseInt(System.getProperty("PORT", "8088"));
    boolean isNativeTransport =
        Boolean.parseBoolean(System.getProperty("NATIVE_TRANSPORT", "true"));
    int flowControlWindowSize =
        Integer.parseInt(System.getProperty("FLOW_CONTROL_WINDOW", "100000"));
    boolean isOpensslAvailable = OpenSsl.isAvailable();
    boolean isEpollAvailable = Epoll.isAvailable();
    boolean isKqueueAvailable = KQueue.isAvailable();

    logger.info("\n==> http2 websocket bulk codec perf test server\n");
    logger.info("\n==> bind address: {}:{}", host, port);
    logger.info("\n==> flow control window size: {}", flowControlWindowSize);
    logger.info("\n==> native transport: {}", isNativeTransport);
    logger.info("\n==> epoll available: {}", isEpollAvailable);
    logger.info("\n==> kqueue available: {}", isKqueueAvailable);
    logger.info("\n==> openssl available: {}", isOpensslAvailable);

    Transport transport = Transport.get(isNativeTransport);

    SslContext sslContext = Security.serverSslContext();

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

      WebSocketsCallbacksHandler webSocketsCallbacksHandler =
          new WebSocketsCallbacksHandler(new EchoWebSocketHandler());

      WebSocketDecoderConfig decoderConfig =
          WebSocketDecoderConfig.newBuilder()
              .maxFramePayloadLength(65_535)
              .expectMaskedFrames(false)
              .allowMaskMismatch(true)
              .allowExtensions(false)
              .withUTF8Validator(false)
              .build();

      Http2WebSocketServerHandler http2webSocketHandler =
          Http2WebSocketServerBuilder.create()
              .codec(WebSocketCallbacksCodec.instance())
              .assumeSingleWebSocketPerConnection(true)
              .compression(false)
              .decoderConfig(decoderConfig)
              .acceptor(
                  (ctx, path, subprotocols, request, response) -> {
                    if ("/echo".equals(path) && subprotocols.isEmpty()) {
                      return ctx.executor().newSucceededFuture(webSocketsCallbacksHandler);
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

  private static class EchoWebSocketHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    private WebSocketFrameFactory.BulkEncoder bulkEncoder;
    private ByteBuf outbuffer;

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.bulkEncoder = webSocketFrameFactory.bulkEncoder();
      return this;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      outbuffer = ctx.alloc().buffer(4096, 4096);
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (opcode != WebSocketProtocol.OPCODE_BINARY) {
        payload.release();
        throw new IllegalStateException("received non-binary opcode");
      }
      WebSocketFrameFactory.BulkEncoder encoder = bulkEncoder;
      ByteBuf out = outbuffer;
      int payloadSize = payload.readableBytes();

      int outSize = encoder.sizeofBinaryFrame(payloadSize);
      if (outSize > out.capacity() - out.writerIndex()) {
        ctx.write(out, ctx.voidPromise());
        out = outbuffer = ctx.alloc().buffer(4096, 4096);
      }

      int mask = encoder.encodeBinaryFramePrefix(out, payloadSize);
      out.writeBytes(payload);
      payload.release();
      encoder.maskBinaryFrame(out, mask, payloadSize);
    }

    @Override
    public void onChannelReadComplete(ChannelHandlerContext ctx) {
      ByteBuf out = outbuffer;
      if (out.readableBytes() > 0) {
        ctx.writeAndFlush(out, ctx.voidPromise());
        this.outbuffer = ctx.alloc().buffer(4096, 4096);
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.info("Unexpected websocket error", cause);
      ctx.close();
    }

    @Override
    public void onChannelWritabilityChanged(ChannelHandlerContext ctx) {
      if (!ctx.channel().isWritable()) {
        ctx.flush();
      }
    }
  }

  @ChannelHandler.Sharable
  private static class WebSocketsCallbacksHandler extends ChannelInboundHandlerAdapter {
    final WebSocketCallbacksHandler webSocketHandler;

    WebSocketsCallbacksHandler(WebSocketCallbacksHandler webSocketHandler) {
      this.webSocketHandler = webSocketHandler;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof Http2WebSocketEvent.Http2WebSocketHandshakeSuccessEvent) {

        WebSocketCallbacksHandler.exchange(ctx, webSocketHandler);
        ctx.pipeline().remove(this);
      }
      super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      logger.info("Received {} message on callbacks handler", msg);
      super.channelRead(ctx, msg);
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
