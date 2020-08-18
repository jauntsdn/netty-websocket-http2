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

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http2.*;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

public abstract class Http2WebSocketHandler extends ChannelDuplexHandler
    implements Http2FrameListener {
  final IntObjectMap<Http2WebSocket> webSockets = new IntObjectHashMap<>();
  final WebSocketDecoderConfig config;
  final long closedWebSocketRemoveTimeoutMillis;

  WebSocketsParent webSocketsParent;
  ChannelHandlerContext ctx;
  boolean isAutoRead;
  Http2ConnectionHandler http2Handler;
  Http2FrameListener next;

  Http2WebSocketHandler(
      WebSocketDecoderConfig webSocketDecoderConfig, long closedWebSocketRemoveTimeoutMillis) {
    this.config = webSocketDecoderConfig;
    this.closedWebSocketRemoveTimeoutMillis = closedWebSocketRemoveTimeoutMillis;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    this.ctx = ctx;
    this.http2Handler = Preconditions.requireHandler(ctx.channel(), Http2ConnectionHandler.class);
    this.isAutoRead = ctx.channel().config().isAutoRead();
    Http2ConnectionDecoder decoder = http2Handler.decoder();
    Http2ConnectionEncoder encoder = http2Handler.encoder();
    this.webSocketsParent = new WebSocketsParent(encoder);
    this.next = decoder.frameListener();
    decoder.frameListener(this);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    if (!isAutoRead) {
      ctx.read();
    }
    super.channelActive(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    webSockets.clear();
    super.channelInactive(ctx);
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    if (!webSockets.isEmpty()) {
      webSockets.values().forEach(Http2WebSocket::streamClosed);
    }
    super.close(ctx, promise);
  }

  @Override
  public void onGoAwayRead(
      ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData)
      throws Http2Exception {
    for (Http2WebSocket webSocket : webSockets.values()) {
      webSocket.streamClosed();
    }
    next().onGoAwayRead(ctx, lastStreamId, errorCode, debugData);
  }

  @Override
  public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
      throws Http2Exception {
    webSocketOrNext(streamId).onRstStreamRead(ctx, streamId, errorCode);
  }

  @Override
  public int onDataRead(
      ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream)
      throws Http2Exception {
    return webSocketOrNext(streamId).onDataRead(ctx, streamId, data.retain(), padding, endOfStream);
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      int streamId,
      Http2Headers headers,
      int padding,
      boolean endOfStream)
      throws Http2Exception {
    webSocketOrNext(streamId).onHeadersRead(ctx, streamId, headers, padding, endOfStream);
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      int streamId,
      Http2Headers headers,
      int streamDependency,
      short weight,
      boolean exclusive,
      int padding,
      boolean endOfStream)
      throws Http2Exception {
    webSocketOrNext(streamId)
        .onHeadersRead(
            ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
  }

  @Override
  public void onPriorityRead(
      ChannelHandlerContext ctx,
      int streamId,
      int streamDependency,
      short weight,
      boolean exclusive)
      throws Http2Exception {
    webSocketOrNext(streamId).onPriorityRead(ctx, streamId, streamDependency, weight, exclusive);
  }

  @Override
  public void onSettingsAckRead(ChannelHandlerContext ctx) throws Http2Exception {
    next().onSettingsAckRead(ctx);
  }

  @Override
  public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings)
      throws Http2Exception {
    next().onSettingsRead(ctx, settings);
  }

  @Override
  public void onPingRead(ChannelHandlerContext ctx, long data) throws Http2Exception {
    next().onPingRead(ctx, data);
  }

  @Override
  public void onPingAckRead(ChannelHandlerContext ctx, long data) throws Http2Exception {
    next().onPingAckRead(ctx, data);
  }

  @Override
  public void onPushPromiseRead(
      ChannelHandlerContext ctx,
      int streamId,
      int promisedStreamId,
      Http2Headers headers,
      int padding)
      throws Http2Exception {
    next().onPushPromiseRead(ctx, streamId, promisedStreamId, headers, padding);
  }

  @Override
  public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement)
      throws Http2Exception {
    webSocketOrNext(streamId).onWindowUpdateRead(ctx, streamId, windowSizeIncrement);
  }

  @Override
  public void onUnknownFrame(
      ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags, ByteBuf payload)
      throws Http2Exception {
    webSocketOrNext(streamId).onUnknownFrame(ctx, frameType, streamId, flags, payload);
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
    webSocketsParent.processPendingReadCompleteQueue();
    super.channelReadComplete(ctx);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    webSockets
        .values()
        .forEach(
            ws -> {
              try {
                ClosedChannelException e = new ClosedChannelException();
                e.initCause(cause);
                ws.fireExceptionCaught(e);
              } finally {
                ws.closeForcibly();
              }
            });
    super.exceptionCaught(ctx, cause);
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (ctx.channel().isWritable()) {
      webSockets.values().forEach(Http2WebSocket::trySetWritable);
    }
    super.channelWritabilityChanged(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    webSocketsParent.setReadInProgress();
    super.channelRead(ctx, msg);
  }

  Http2FrameListener next() {
    return next;
  }

  Http2FrameListener webSocketOrNext(int streamId) {
    Http2WebSocket webSocket = webSockets.get(streamId);
    if (webSocket != null) {
      ChannelHandlerContext c = ctx;
      if (!isAutoRead) {
        c.read();
      }
      return webSocket;
    }
    return next;
  }

  void registerWebSocket(int streamId, Http2WebSocketChannel webSocket) {
    IntObjectMap<Http2WebSocket> ws = webSockets;
    ws.put(streamId, webSocket);
    webSocket
        .closeFuture()
        .addListener(
            future -> {
              Channel channel = ctx.channel();
              ChannelFuture closeFuture = channel.closeFuture();
              if (closeFuture.isDone()) {
                return;
              }
              ws.put(streamId, Http2WebSocket.CLOSED);
              removeAfterTimeout(streamId, ws, closeFuture, channel.eventLoop());
            });
  }

  void removeAfterTimeout(
      int streamId,
      IntObjectMap<Http2WebSocket> webSockets,
      ChannelFuture connectionCloseFuture,
      EventLoop eventLoop) {
    RemoveWebSocket removeWebSocket =
        new RemoveWebSocket(streamId, webSockets, connectionCloseFuture);
    ScheduledFuture<?> removeWebSocketFuture =
        eventLoop.schedule(
            removeWebSocket, closedWebSocketRemoveTimeoutMillis, TimeUnit.MILLISECONDS);
    removeWebSocket.removeWebSocketFuture(removeWebSocketFuture);
  }

  private static class RemoveWebSocket implements Runnable, GenericFutureListener<ChannelFuture> {
    private final IntObjectMap<Http2WebSocket> webSockets;
    private final int streamId;
    private final ChannelFuture connectionCloseFuture;
    private ScheduledFuture<?> removeWebSocketFuture;

    RemoveWebSocket(
        int streamId,
        IntObjectMap<Http2WebSocket> webSockets,
        ChannelFuture connectionCloseFuture) {
      this.streamId = streamId;
      this.webSockets = webSockets;
      this.connectionCloseFuture = connectionCloseFuture;
    }

    void removeWebSocketFuture(ScheduledFuture<?> removeWebSocketFuture) {
      this.removeWebSocketFuture = removeWebSocketFuture;
      connectionCloseFuture.addListener(this);
    }

    /*connection close*/
    @Override
    public void operationComplete(ChannelFuture future) {
      removeWebSocketFuture.cancel(true);
    }

    /*after websocket close timeout*/
    @Override
    public void run() {
      webSockets.remove(streamId);
      connectionCloseFuture.removeListener(this);
    }
  }

  /*
   * Copyright 2019 The Netty Project
   * Copyright 2020 Maksym Ostroverkhov
   *
   * The Netty Project licenses this file to you under the Apache License,
   * version 2.0 (the "License"); you may not use this file except in compliance
   * with the License. You may obtain a copy of the License at:
   *
   *   http://www.apache.org/licenses/LICENSE-2.0
   *
   * Unless required by applicable law or agreed to in writing, software
   * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
   * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
   * License for the specific language governing permissions and limitations
   * under the License.
   */

  /**
   * Provides DATA, RST, WINDOW_UPDATE frame write operations to websocket channel. Also hosts code
   * derived from netty so It can be attributed properly
   */
  class WebSocketsParent {
    static final int READ_COMPLETE_PENDING_QUEUE_MAX_SIZE =
        Http2CodecUtil.SMALLEST_MAX_CONCURRENT_STREAMS;

    final Queue<Http2WebSocketChannel> readCompletePendingQueue = new ArrayDeque<>(8);
    boolean parentReadInProgress;
    final Http2ConnectionEncoder connectionEncoder;

    public WebSocketsParent(Http2ConnectionEncoder connectionEncoder) {
      this.connectionEncoder = connectionEncoder;
    }

    ChannelFuture writeHeaders(int streamId, Http2Headers headers, boolean endStream) {
      ChannelHandlerContext c = ctx;
      ChannelPromise p = c.newPromise();
      return connectionEncoder.writeHeaders(c, streamId, headers, 0, endStream, p);
    }

    ChannelFuture writeHeaders(
        int streamId, Http2Headers headers, boolean endStream, short weight) {
      ChannelHandlerContext c = ctx;
      ChannelPromise p = c.newPromise();
      return connectionEncoder.writeHeaders(
          c, streamId, headers, 0, weight, false, 0, endStream, p);
    }

    ChannelFuture writeData(int streamId, ByteBuf data, int padding, boolean endStream) {
      ChannelHandlerContext c = ctx;
      ChannelPromise p = c.newPromise();
      return connectionEncoder.writeData(c, streamId, data, padding, endStream, p);
    }

    ChannelFuture writeRstStream(int streamId, long errorCode) {
      ChannelHandlerContext c = ctx;
      ChannelPromise p = c.newPromise();
      return connectionEncoder.writeRstStream(c, streamId, errorCode, p);
    }

    ChannelFuture writePriority(int streamId, short weight) {
      ChannelHandlerContext c = ctx;
      ChannelPromise p = c.newPromise();
      return connectionEncoder.writePriority(c, streamId, 0, weight, false, p);
    }

    public boolean isParentReadInProgress() {
      return parentReadInProgress;
    }

    public void addChannelToReadCompletePendingQueue(Http2WebSocketChannel webSocketChannel) {
      Queue<Http2WebSocketChannel> q = readCompletePendingQueue;
      while (q.size() >= READ_COMPLETE_PENDING_QUEUE_MAX_SIZE) {
        processPendingReadCompleteQueue();
      }
      q.offer(webSocketChannel);
    }

    public ChannelHandlerContext context() {
      return ctx;
    }

    public void register(final int streamId, Http2WebSocketChannel webSocket) {
      registerWebSocket(streamId, webSocket);
    }

    void setReadInProgress() {
      parentReadInProgress = true;
    }

    void processPendingReadCompleteQueue() {
      parentReadInProgress = true;
      // If we have many child channel we can optimize for the case when multiple call flush() in
      // channelReadComplete(...) callbacks and only do it once as otherwise we will end-up with
      // multiple
      // write calls on the socket which is expensive.
      Http2WebSocketChannel childChannel = readCompletePendingQueue.poll();
      if (childChannel != null) {
        try {
          do {
            childChannel.fireChildReadComplete();
            childChannel = readCompletePendingQueue.poll();
          } while (childChannel != null);
        } finally {
          parentReadInProgress = false;
          readCompletePendingQueue.clear();
          ctx.flush();
        }
      } else {
        parentReadInProgress = false;
      }
    }
  }
}
