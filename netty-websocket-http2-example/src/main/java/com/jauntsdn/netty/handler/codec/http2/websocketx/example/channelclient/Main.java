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

package com.jauntsdn.netty.handler.codec.http2.websocketx.example.channelclient;

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.*;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandshaker;
import com.jauntsdn.netty.handler.codec.http2.websocketx.example.Security;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    String host = System.getProperty("HOST", "localhost");
    int port = Integer.parseInt(System.getProperty("PORT", "8099"));
    InetSocketAddress address = new InetSocketAddress(host, port);

    logger.info("\n==> Channel per websocket client\n");
    logger.info("\n==> Remote address: {}:{}", host, port);

    final SslContext sslContext = Security.clientLocalSslContextHttp2();
    Channel channel =
        new Bootstrap()
            .group(new NioEventLoopGroup())
            .channel(NioSocketChannel.class)
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {
                    SslHandler sslHandler = sslContext.newHandler(ch.alloc());
                    Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forClient();
                    frameCodecBuilder.initialSettings().initialWindowSize(1_000);
                    Http2FrameCodec http2FrameCodec = frameCodecBuilder.build();

                    Http2WebSocketClientHandler http2WebSocketClientHandler =
                        Http2WebSocketClientBuilder.create()
                            .streamWeight(16)
                            .decoderConfig(
                                WebSocketDecoderConfig.newBuilder()
                                    .expectMaskedFrames(false)
                                    .allowMaskMismatch(true)
                                    .build())
                            .handshakeTimeoutMillis(15_000)
                            .compression(true)
                            .build();

                    ConnectionExceptionHandler exceptionHandler = new ConnectionExceptionHandler();

                    ch.pipeline()
                        .addLast(
                            sslHandler,
                            http2FrameCodec,
                            http2WebSocketClientHandler,
                            exceptionHandler);
                  }
                })
            .connect(address)
            .sync()
            .channel();
    /*need to wait until channel is ready (future completes)*/
    Http2WebSocketClientHandshaker handShaker = Http2WebSocketClientHandshaker.create(channel);

    Http2Headers headers =
        new DefaultHttp2Headers().set("user-agent", "jauntsdn-websocket-http2-client/1.1.5");
    ChannelFuture handshake =
        handShaker.handshake("/echo", "echo.jauntsdn.com", headers, new EchoWebSocketHandler());

    handshake.addListener(new WebSocketFutureListener());

    Channel echoWebSocketChannel = handshake.channel();

    EventLoopGroup eventLoop = echoWebSocketChannel.eventLoop();

    /*send websocket frames*/
    eventLoop.scheduleAtFixedRate(
        () -> echoWebSocketChannel.writeAndFlush(new TextWebSocketFrame("hello http2 websocket")),
        0,
        1_000,
        TimeUnit.MILLISECONDS);

    /*update websocket stream weight*/
    eventLoop.scheduleAtFixedRate(
        new StreamWeightUpdate() {
          @Override
          public void run() {
            Short curStreamWeight =
                Http2WebSocketStreamWeightUpdateEvent.streamWeight(echoWebSocketChannel);
            short nextWeight = nextWeight(curStreamWeight);
            logger.info("==> Sent websocket stream weight update: {}", nextWeight);
            echoWebSocketChannel
                .pipeline()
                .fireUserEventTriggered(Http2WebSocketStreamWeightUpdateEvent.create(nextWeight));
          }
        },
        5_000,
        5_000,
        TimeUnit.MILLISECONDS);

    echoWebSocketChannel.closeFuture().sync();
    logger.info("==> Websocket closed. Terminating client...");
    eventLoop.shutdownGracefully();
    logger.info("==> Client terminated");
  }

  private static class EchoWebSocketHandler
      extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private long startNanos;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame webSocketFrame) {
      logger.info("==> Received text websocket message: {}", webSocketFrame.text());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof Http2WebSocketLifecycleEvent) {
        Http2WebSocketLifecycleEvent handshakeEvent = (Http2WebSocketLifecycleEvent) evt;
        int id = handshakeEvent.id();
        String path = handshakeEvent.path();
        String subprotocols = handshakeEvent.subprotocols();
        String subprotocolsOrEmpty = subprotocols.isEmpty() ? "<empty>" : subprotocols;
        long timestampNanos = handshakeEvent.timestampNanos();

        switch (handshakeEvent.type()) {
          case HANDSHAKE_START:
            startNanos = timestampNanos;
            Http2WebSocketHandshakeStartEvent startEvent = handshakeEvent.cast();
            logger.info(
                "==> WebSocket handshake start event - id: {}, path: {}, subprotocols: {}, request headers: {}",
                id,
                path,
                subprotocolsOrEmpty,
                headers(startEvent.requestHeaders()));
            break;
          case HANDSHAKE_SUCCESS:
            long handshakeSuccessMillis =
                TimeUnit.NANOSECONDS.toMillis(timestampNanos - startNanos);
            Http2WebSocketHandshakeSuccessEvent successEvent = handshakeEvent.cast();
            logger.info(
                "==> WebSocket handshake success event - id: {}, path: {}, subprotocols: {}, duration: {} millis, response headers: {}",
                id,
                path,
                subprotocolsOrEmpty,
                handshakeSuccessMillis,
                headers(successEvent.responseHeaders()));
            break;
          case HANDSHAKE_ERROR:
            long handshakeErrorMillis = TimeUnit.NANOSECONDS.toMillis(timestampNanos - startNanos);
            Http2WebSocketHandshakeErrorEvent errorEvent = handshakeEvent.cast();
            String errorName;
            String errorMessage;
            Throwable cause = errorEvent.error();
            if (cause != null) {
              errorName = cause.getClass().getSimpleName();
              errorMessage = cause.getMessage();
            } else {
              errorName = errorEvent.errorName();
              errorMessage = errorEvent.errorMessage();
            }
            logger.info(
                "==> WebSocket handshake error event - id: {}, path: {}, subprotocols: {}, duration: {} millis, error: {}: {}, response headers: {}",
                id,
                path,
                subprotocolsOrEmpty,
                handshakeErrorMillis,
                errorName,
                errorMessage,
                headers(errorEvent.responseHeaders()));
            break;
          case CLOSE_REMOTE_ENDSTREAM:
            logger.info(
                "==> WebSocket stream close remote END_STREAM - id: {}, path: {}, subprotocols: {}",
                id,
                path,
                subprotocolsOrEmpty);
            if (ctx.channel().isOpen()) {
              ctx.pipeline().fireUserEventTriggered(Http2WebSocketLocalCloseEvent.INSTANCE);
            }
            break;
          case CLOSE_REMOTE_RESET:
            logger.info(
                "==> WebSocket stream close remote RST_STREAM - id: {}, path: {}, subprotocols: {}",
                id,
                path,
                subprotocolsOrEmpty);
            break;
          default:
            logger.info(
                "==> WebSocket handshake unexpected event - type: {}", handshakeEvent.type());
        }
        return;
      }
      super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.error("Unexpected websocket error", cause);
      ctx.close();
    }

    private String headers(Http2Headers headers) {
      if (headers == null) {
        return "[]";
      }
      String repr = headers.toString();
      String prefix = "Http2Headers";
      int index = repr.indexOf(prefix);
      if (index > 0) {
        return repr.substring(index + prefix.length());
      }
      return repr;
    }
  }

  private abstract static class StreamWeightUpdate implements Runnable {
    private final short firstWeight = 24;
    private final short secondWeight = 42;

    short nextWeight(Short streamWeight) {
      if (streamWeight == null || streamWeight == secondWeight) {
        streamWeight = firstWeight;
      } else {
        streamWeight = secondWeight;
      }
      return streamWeight;
    }
  }

  private static class ConnectionExceptionHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.error("Unexpected connection error", cause);
      ctx.close();
    }
  }

  private static class WebSocketFutureListener
      implements GenericFutureListener<Future<? super Void>> {
    @Override
    public void operationComplete(Future<? super Void> future) {
      if (future.isSuccess()) {
        logger.info("==> Websocket channel future success");
      } else {
        logger.info("==> Websocket channel future error: {}", future.cause().toString());
      }
    }
  }
}
