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

package com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.bulkcodec.client;

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Http2WebSocketLifecycleEvent;
import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Type;

import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketCallbacksHandler;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameFactory;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameListener;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketProtocol;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketClientHandshaker;
import com.jauntsdn.netty.handler.codec.http2.websocketx.WebSocketCallbacksCodec;
import com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.Security;
import com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.Transport;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
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
    int frameSize = Integer.parseInt(System.getProperty("FRAME", "140"));
    int outboundFramesWindow = Integer.parseInt(System.getProperty("WINDOW", "2222"));

    boolean isOpensslAvailable = OpenSsl.isAvailable();
    boolean isEpollAvailable = Epoll.isAvailable();
    boolean isKqueueAvailable = KQueue.isAvailable();

    logger.info("\n==> http2 websocket bulk codec perf test client\n");
    logger.info("\n==> remote address: {}:{}", host, port);
    logger.info("\n==> duration: {}", duration);
    logger.info("\n==> native transport: {}", isNativeTransport);
    logger.info("\n==> epoll available: {}", isEpollAvailable);
    logger.info("\n==> kqueue available: {}", isKqueueAvailable);
    logger.info("\n==> openssl available: {}\n", isOpensslAvailable);
    logger.info("\n==> frame payload size: {}", frameSize);
    logger.info("\n==> outbound frames window: {}", outboundFramesWindow);

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

                    WebSocketDecoderConfig decoderConfig =
                        WebSocketDecoderConfig.newBuilder()
                            .maxFramePayloadLength(65_535)
                            .expectMaskedFrames(false)
                            .allowMaskMismatch(true)
                            .allowExtensions(false)
                            .withUTF8Validator(false)
                            .build();

                    Http2WebSocketClientHandler http2WebSocketClientHandler =
                        Http2WebSocketClientBuilder.create()
                            .codec(WebSocketCallbacksCodec.instance())
                            .compression(false)
                            .decoderConfig(decoderConfig)
                            .maskPayload(false)
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
    FrameCounters frameCounters = new FrameCounters(false);

    Recorder framesHistogram = new Recorder(3600000000000L, 3);

    ChannelFuture handshakeFuture =
        handShaker.handshake(
            "/echo",
            new WebSocketsCallbacksHandler(
                new WebSocketClientHandler(
                    frameCounters,
                    framesPayload,
                    ThreadLocalRandom.current(),
                    outboundFramesWindow)));

    handshakeFuture.addListener(new CloseOnError(channel));

    int warmupMillis = 5000;
    logger.info("==> warming up for {} millis...", warmupMillis);
    channel
        .eventLoop()
        .schedule(
            () -> {
              logger.info("==> warm up completed");
              frameCounters.start();
              channel
                  .eventLoop()
                  .scheduleAtFixedRate(
                      new StatsReporter(frameCounters, frameSize),
                      1000,
                      1000,
                      TimeUnit.MILLISECONDS);
            },
            warmupMillis,
            TimeUnit.MILLISECONDS);

    channel.closeFuture().sync();
    logger.info("Client terminated");
  }

  private static class FrameCounters {
    private final Recorder histogram;
    private int frameCount;
    private boolean isStarted;

    public FrameCounters(boolean totalFrames) {
      histogram = totalFrames ? null : new Recorder(36000000000L, 3);
    }

    private long totalFrameCount;

    public void start() {
      isStarted = true;
    }

    public void countFrame(long timestamp) {
      if (!isStarted) {
        return;
      }

      if (histogram == null) {
        totalFrameCount++;
      } else {
        frameCount++;
        if (timestamp >= 0) {
          histogram.recordValue(System.nanoTime() - timestamp);
        }
      }
    }

    public Recorder histogram() {
      return histogram;
    }

    public int frameCount() {
      int count = frameCount;
      frameCount = 0;
      return count;
    }

    public long totalFrameCount() {
      return totalFrameCount;
    }
  }

  private static class StatsReporter implements Runnable {
    private final FrameCounters frameCounters;
    private final int frameSize;
    private int iteration;

    public StatsReporter(FrameCounters frameCounters, int frameSize) {
      this.frameCounters = frameCounters;
      this.frameSize = frameSize;
    }

    @Override
    public void run() {
      Recorder histogram = frameCounters.histogram();
      if (histogram != null) {
        Histogram h = histogram.getIntervalHistogram();
        long p50 = h.getValueAtPercentile(50) / 1000;
        long p95 = h.getValueAtPercentile(95) / 1000;
        long p99 = h.getValueAtPercentile(99) / 1000;
        int count = frameCounters.frameCount();

        logger.info("p50 => {} micros", p50);
        logger.info("p95 => {} micros", p95);
        logger.info("p99 => {} micros", p99);
        logger.info("throughput => {} messages", count);
        logger.info("throughput => {} kbytes\n", count * frameSize / (float) 1024);
      } else {
        if (++iteration % 10 == 0) {
          logger.info(
              "total frames, iteration {} => {}", iteration, frameCounters.totalFrameCount());
        }
      }
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

  static class WebSocketClientHandler implements WebSocketCallbacksHandler, WebSocketFrameListener {

    private final FrameCounters frameCounters;
    private final List<ByteBuf> dataList;
    private final Random random;
    private final int window;
    private int sendIndex;
    private boolean isClosed;
    private FrameWriter frameWriter;

    WebSocketClientHandler(
        FrameCounters frameCounters, List<ByteBuf> dataList, Random random, int window) {
      this.frameCounters = frameCounters;
      this.dataList = dataList;
      this.random = random;
      this.window = window;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory frameFactory) {
      frameWriter = new FrameWriter(ctx, frameFactory.bulkEncoder(), window);
      return this;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      frameWriter.startWrite();
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      isClosed = true;
      frameWriter.close();
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (opcode != WebSocketProtocol.OPCODE_BINARY) {
        payload.release();
        return;
      }

      long timeStamp = payload.readLong();
      frameCounters.countFrame(timeStamp);
      payload.release();
      frameWriter.tryContinueWrite();
    }

    @Override
    public void onChannelReadComplete(ChannelHandlerContext ctx) {}

    @Override
    public void onUserEventTriggered(ChannelHandlerContext ctx, Object evt) {}

    @Override
    public void onChannelWritabilityChanged(ChannelHandlerContext ctx) {
      Channel ch = ctx.channel();
      if (!ch.isWritable()) {
        ch.flush();
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!isClosed) {
        isClosed = true;
        logger.error("Channel error", cause);
        ctx.close();
      }
    }

    class FrameWriter {
      private final ChannelHandlerContext ctx;
      private final WebSocketFrameFactory.BulkEncoder bulkEncoder;
      private ByteBuf outbuffer;
      private final int window;
      private int queued;

      FrameWriter(
          ChannelHandlerContext ctx, WebSocketFrameFactory.BulkEncoder bulkEncoder, int window) {
        this.ctx = ctx;
        this.bulkEncoder = bulkEncoder;
        this.window = window;
        this.outbuffer = ctx.alloc().buffer(4096, 4096);
      }

      void close() {
        ByteBuf out = outbuffer;
        if (out != null) {
          outbuffer = null;
          out.release();
        }
      }

      void startWrite() {
        if (isClosed) {
          return;
        }
        int cur = queued;
        int w = window;
        int writeCount = w - cur;
        ChannelHandlerContext c = ctx;
        for (int i = 0; i < writeCount; i++) {
          writeWebSocketFrame(c);
        }
        queued = w;
        ByteBuf out = outbuffer;
        if (out.readableBytes() > 0) {
          c.write(out, c.voidPromise());
          outbuffer = ctx.alloc().buffer(4096, 4096);
        }
        c.flush();
      }

      void tryContinueWrite() {
        int q = --queued;
        if (q <= window / 2) {
          startWrite();
        }
      }

      void writeWebSocketFrame(ChannelHandlerContext ctx) {
        List<ByteBuf> dl = dataList;
        int dataIndex = random.nextInt(dl.size());
        ByteBuf data = dl.get(dataIndex);
        int index = sendIndex++;
        int dataSize = data.readableBytes();
        int payloadSize = Long.BYTES + dataSize;

        ByteBuf out = outbuffer;
        WebSocketFrameFactory.BulkEncoder encoder = bulkEncoder;
        int outSize = encoder.sizeofBinaryFrame(payloadSize);
        if (outSize > out.capacity() - out.writerIndex()) {
          ctx.write(out, ctx.voidPromise());
          out = outbuffer = ctx.alloc().buffer(4096, 4096);
        }

        int mask = encoder.encodeBinaryFramePrefix(out, payloadSize);
        out.writeLong(index % 50_000 == 0 ? System.nanoTime() : -1).writeBytes(data, 0, dataSize);
        encoder.maskBinaryFrame(out, mask, payloadSize);
      }
    }
  }

  private static class WebSocketsCallbacksHandler extends ChannelInboundHandlerAdapter {
    final WebSocketCallbacksHandler webSocketHandler;

    WebSocketsCallbacksHandler(WebSocketCallbacksHandler webSocketHandler) {
      this.webSocketHandler = webSocketHandler;
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
            WebSocketCallbacksHandler.exchange(ctx, webSocketHandler);
            ctx.pipeline().remove(this);
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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.info("Unexpected websocket error", cause);
      ctx.close();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      logger.info("Received {} message on callbacks handler", msg);
      super.channelRead(ctx, msg);
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
