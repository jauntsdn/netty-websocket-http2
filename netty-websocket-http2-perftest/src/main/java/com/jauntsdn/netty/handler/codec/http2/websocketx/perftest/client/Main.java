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

package com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.client;

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.*;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandshaker;
import com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.Security;
import com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.Transport;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GenericFutureListener;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    String host = System.getProperty("HOST", "localhost");
    int port = Integer.parseInt(System.getProperty("PORT", "8088"));
    int duration = Integer.parseInt(System.getProperty("DURATION", "600"));
    boolean isNativeTransport = Boolean.parseBoolean(System.getProperty("NATIVE", "true"));
    int flowControlWindowSize = Integer.parseInt(System.getProperty("WINDOW", "100000"));
    int frameSize = Integer.parseInt(System.getProperty("FRAME", "1000"));
    int framesQueueLimit = Integer.parseInt(System.getProperty("QUEUE", "60"));
    int websocketsCount = Integer.parseInt(System.getProperty("WEBSOCKETS", "1"));

    boolean isOpensslAvailable = OpenSsl.isAvailable();
    boolean isEpollAvailable = Transport.isEpollAvailable();

    logger.info("\n==> http2 websocket load test client\n");
    logger.info("\n==> remote address: {}:{}", host, port);
    logger.info("\n==> duration: {}", duration);
    logger.info("\n==> native transport: {}", isNativeTransport);
    logger.info("\n==> epoll available: {}", isEpollAvailable);
    logger.info("\n==> openssl available: {}\n", isOpensslAvailable);
    logger.info("\n==> frame payload size: {}", frameSize);
    logger.info("\n==> written frames queue limit: {}", framesQueueLimit);
    logger.info("\n==> websockets count: {}", websocketsCount);

    Transport transport = Transport.get(isNativeTransport);

    final SslContext sslContext = Security.clientLocalSslContext();

    Channel channel =
        new Bootstrap()
            .group(transport.eventLoopGroup())
            .channel(transport.clientChannel())
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {
                    SslHandler sslHandler = sslContext.newHandler(ch.alloc());
                    Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forClient();
                    frameCodecBuilder.initialSettings().initialWindowSize(flowControlWindowSize);
                    Http2FrameCodec http2FrameCodec = frameCodecBuilder.build();
                    Http2WebSocketClientHandler http2WebSocketClientHandler =
                        Http2WebSocketClientBuilder.create()
                            .handshakeTimeoutMillis(15_000)
                            .assumeSingleWebSocketPerConnection(true)
                            .build();
                    ch.pipeline().addLast(sslHandler, http2FrameCodec, http2WebSocketClientHandler);
                  }
                })
            .connect(new InetSocketAddress(host, port))
            .sync()
            .channel();
    Http2WebSocketClientHandshaker handShaker = Http2WebSocketClientHandshaker.create(channel);

    Random random = new Random();
    List<ByteBuf> framesPayload = framesPayload(1000, frameSize, random);
    Recorder framesHistogram = new Recorder(3600000000000L, 3);

    for (int i = 0; i < websocketsCount; i++) {
      ChannelFuture handshakeFuture =
          handShaker.handshake(
              "/echo",
              new EchoWebSocketHandler(
                  framesHistogram,
                  framesPayload,
                  frameSize,
                  random,
                  framesQueueLimit / 2,
                  framesQueueLimit));

      handshakeFuture.addListener(new CloseOnError(channel));
    }

    channel
        .eventLoop()
        .scheduleAtFixedRate(
            new StatsReporter(framesHistogram, frameSize), 1000, 1000, TimeUnit.MILLISECONDS);

    channel.closeFuture().sync();
    logger.info("Client terminated");
  }

  private static class StatsReporter implements Runnable {
    private final Recorder framesHistogram;
    private final int frameSize;

    public StatsReporter(Recorder framesHistogram, int frameSize) {
      this.framesHistogram = framesHistogram;
      this.frameSize = frameSize;
    }

    @Override
    public void run() {
      Histogram h = framesHistogram.getIntervalHistogram();
      long p50 = h.getValueAtPercentile(50) / 1000;
      long p95 = h.getValueAtPercentile(95) / 1000;
      long p99 = h.getValueAtPercentile(99) / 1000;
      long count = h.getTotalCount();

      logger.info("p50 => {} micros", p50);
      logger.info("p95 => {} micros", p95);
      logger.info("p99 => {} micros", p99);
      logger.info("throughput => {} messages", count);
      logger.info("throughput => {} kbytes\n", count * frameSize / (float) 1024);
    }
  }

  private static class CloseOnError implements GenericFutureListener<ChannelFuture> {
    private final Channel connection;

    public CloseOnError(Channel connection) {
      this.connection = connection;
    }

    @Override
    public void operationComplete(ChannelFuture future) {
      Throwable cause = future.cause();
      if (cause != null) {
        logger.info(
            "Websocket handshake error: {}:{}",
            cause.getClass().getSimpleName(),
            cause.getMessage());
        connection.eventLoop().shutdownGracefully();
      }
    }
  }

  private static class EchoWebSocketHandler extends ChannelInboundHandlerAdapter {
    private static final int HEADER_SIZE = Long.BYTES + Integer.BYTES;

    private final Recorder histogram;
    private final List<ByteBuf> dataList;
    private final Random random;
    private final int queueLowMark;
    private final int queueHighMark;
    private final int frameSize;
    private int sendIndex;
    private int receiveIndex;
    private boolean isClosed;
    private QueueLimitingFrameWriter frameWriter;

    public EchoWebSocketHandler(
        Recorder histogram,
        List<ByteBuf> dataList,
        int dataSize,
        Random random,
        int queueLowMark,
        int queueHighMark) {
      this.histogram = histogram;
      this.dataList = dataList;
      this.frameSize = dataSize + HEADER_SIZE;
      this.random = random;
      this.queueLowMark = queueLowMark;
      this.queueHighMark = queueHighMark;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      isClosed = true;
      super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      if (!(msg instanceof BinaryWebSocketFrame)) {
        ReferenceCountUtil.safeRelease(msg);
        return;
      }
      BinaryWebSocketFrame webSocketFrame = (BinaryWebSocketFrame) msg;
      try {
        read(ctx, webSocketFrame);
      } finally {
        webSocketFrame.release();
      }
    }

    private void read(ChannelHandlerContext ctx, BinaryWebSocketFrame webSocketFrame) {
      ByteBuf content = webSocketFrame.content();
      int expectedSize = frameSize;
      if (content.readableBytes() != expectedSize) {
        ctx.close();
        throw new IllegalStateException(
            String.format(
                "received data contents do not match - actual: %d, expected: %d",
                content.readableBytes(), expectedSize));
      }
      int sendIndex = content.readInt();
      int expectedIndex = receiveIndex;
      if (sendIndex != expectedIndex) {
        ctx.close();
        throw new IllegalStateException(
            String.format(
                "received unexpected data - sendIndex: %d, receiveIndex: %d",
                sendIndex, expectedIndex));
      }
      receiveIndex++;

      long timeStamp = content.readLong();
      histogram.recordValue(System.nanoTime() - timeStamp);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
      boolean writable = ctx.channel().isWritable();
      if (writable) {
        QueueLimitingFrameWriter fw = frameWriter;
        if (fw != null) {
          fw.tryWrite();
        }
      }
      super.channelWritabilityChanged(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof Http2WebSocketLifecycleEvent) {
        Http2WebSocketLifecycleEvent handshakeEvent = (Http2WebSocketLifecycleEvent) evt;
        Type eventType = handshakeEvent.type();
        switch (eventType) {
          case HANDSHAKE_START:
          case CLOSE_REMOTE_ENDSTREAM:
          case CLOSE_REMOTE_RESET:
            break;
          case HANDSHAKE_SUCCESS:
            logger.info("==> WebSocket handshake success");
            if (isClosed) {
              return;
            }
            QueueLimitingFrameWriter fw =
                frameWriter = new QueueLimitingFrameWriter(ctx, queueLowMark, queueHighMark);
            fw.tryWrite();
            break;
          case HANDSHAKE_ERROR:
            logger.info("==> WebSocket handshake error");
            break;
          default:
            logger.info("==> WebSocket handshake unexpected event - type: {}", eventType);
        }
        return;
      }
      super.userEventTriggered(ctx, evt);
    }

    BinaryWebSocketFrame webSocketFrame() {
      List<ByteBuf> dl = dataList;
      int dataIndex = random.nextInt(dl.size());
      ByteBuf data = dl.get(dataIndex);
      ByteBuf frame =
          ByteBufAllocator.DEFAULT
              .buffer(frameSize)
              .writeInt(sendIndex++)
              .writeLong(System.nanoTime())
              .writeBytes(data, 0, data.readableBytes());
      return new BinaryWebSocketFrame(frame);
    }

    class QueueLimitingFrameWriter implements GenericFutureListener<ChannelFuture> {
      private final Channel channel;
      private final ChannelHandlerContext ctx;
      private final int lowMark;
      private final int highMark;
      private int queued;
      private boolean isTerminated;

      QueueLimitingFrameWriter(ChannelHandlerContext ctx, int lowMark, int highMark) {
        this.ctx = ctx;
        this.channel = ctx.channel();
        this.lowMark = lowMark;
        this.highMark = highMark;
      }

      void tryWrite() {
        if (queued <= lowMark) {
          while (queued < highMark) {
            if (isClosed) {
              return;
            }
            if (channel.isWritable()) {
              queued++;
              ctx.write(webSocketFrame()).addListener(this);
            } else {
              break;
            }
          }
          ctx.flush();
        }
      }

      @Override
      public void operationComplete(ChannelFuture future) {
        Throwable cause = future.cause();
        if (cause != null) {
          logger.error("Error writing frame", cause);
          if (!isTerminated) {
            isTerminated = true;
            ctx.close();
          }
          return;
        }
        queued--;
        tryWrite();
      }
    }
  }

  private static List<ByteBuf> framesPayload(int count, int size, Random random) {
    List<ByteBuf> data = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      byte[] bytes = new byte[size];
      random.nextBytes(bytes);
      data.add(Unpooled.wrappedBuffer(bytes));
    }
    return data;
  }
}
