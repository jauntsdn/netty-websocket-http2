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
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.collection.IntCollections;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;

abstract class Http2WebSocketChannelHandler extends Http2WebSocketHandler {
  final Http1WebSocketCodec webSocketCodec;
  final WebSocketDecoderConfig config;
  final boolean isEncoderMaskPayload;
  final boolean isNomaskingExtension;
  final long closedWebSocketRemoveTimeoutMillis;
  final Supplier<IntObjectMap<Http2WebSocket>> webSocketRegistryFactory;

  IntObjectMap<Http2WebSocket> webSocketRegistry = IntCollections.emptyMap();
  ChannelHandlerContext ctx;
  WebSocketsParent webSocketsParent;
  boolean isAutoRead;

  Http2WebSocketChannelHandler(
      Http1WebSocketCodec webSocketCodec,
      @Nullable WebSocketDecoderConfig webSocketDecoderConfig,
      boolean isEncoderMaskPayload,
      boolean isNomaskingExtension,
      long closedWebSocketRemoveTimeoutMillis,
      boolean isSingleWebSocketPerConnection) {
    this.webSocketCodec = webSocketCodec;
    this.config = webSocketDecoderConfig;
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    this.isNomaskingExtension = isNomaskingExtension;
    this.closedWebSocketRemoveTimeoutMillis = closedWebSocketRemoveTimeoutMillis;
    this.webSocketRegistryFactory = webSocketRegistryFactory(isSingleWebSocketPerConnection);
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);
    this.ctx = ctx;
    this.isAutoRead = ctx.channel().config().isAutoRead();
    Http2ConnectionEncoder encoder = http2Handler.encoder();
    this.webSocketsParent = new WebSocketsParent(encoder);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    webSocketRegistry.clear();
    super.channelInactive(ctx);
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (ctx.channel().isWritable()) {
      IntObjectMap<Http2WebSocket> webSockets = this.webSocketRegistry;
      if (!webSockets.isEmpty()) {
        webSockets.forEach((key, webSocket) -> webSocket.trySetWritable());
      }
    }
    super.channelWritabilityChanged(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    webSocketsParent.setReadInProgress();
    super.channelRead(ctx, msg);
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
    webSocketsParent.processPendingReadCompleteQueue();
    super.channelReadComplete(ctx);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (!(cause instanceof Http2Exception.StreamException)) {
      super.exceptionCaught(ctx, cause);
      return;
    }
    IntObjectMap<Http2WebSocket> webSockets = this.webSocketRegistry;
    if (!webSockets.isEmpty()) {
      Http2Exception.StreamException streamException = (Http2Exception.StreamException) cause;
      Http2WebSocket webSocket = webSockets.get(streamException.streamId());
      if (webSocket == null) {
        super.exceptionCaught(ctx, cause);
        return;
      }
      if (webSocket != Http2WebSocket.CLOSED) {
        try {
          ClosedChannelException e = new ClosedChannelException();
          e.initCause(streamException);
          webSocket.fireExceptionCaught(e);
        } finally {
          webSocket.closeForcibly();
        }
      }
      return;
    }
    super.exceptionCaught(ctx, cause);
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    IntObjectMap<Http2WebSocket> webSockets = this.webSocketRegistry;
    if (!webSockets.isEmpty()) {
      webSockets.forEach((key, webSocket) -> webSocket.streamClosed());
    }
    super.close(ctx, promise);
  }

  @Override
  public void onGoAwayRead(
      ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData)
      throws Http2Exception {
    IntObjectMap<Http2WebSocket> webSockets = this.webSocketRegistry;
    if (!webSockets.isEmpty()) {
      webSockets.forEach(
          (key, webSocket) -> webSocket.onGoAwayRead(ctx, lastStreamId, errorCode, debugData));
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

  Http2FrameListener webSocketOrNext(int streamId) {
    Http2WebSocket webSocket = webSocketRegistry.get(streamId);
    if (webSocket != null) {
      ChannelHandlerContext c = ctx;
      if (!isAutoRead) {
        c.read();
      }
      return webSocket;
    }
    return next();
  }

  void registerWebSocket(int streamId, Http2WebSocketChannel webSocket) {
    IntObjectMap<Http2WebSocket> registry = webSocketRegistry;
    if (registry == IntCollections.<Http2WebSocket>emptyMap()) {
      webSocketRegistry = registry = webSocketRegistryFactory.get();
    }
    registry.put(streamId, webSocket);
    IntObjectMap<Http2WebSocket> finalRegistry = registry;
    webSocket
        .closeFuture()
        .addListener(
            future -> {
              Channel channel = ctx.channel();
              ChannelFuture connectionCloseFuture = channel.closeFuture();
              if (connectionCloseFuture.isDone()) {
                return;
              }
              /*stream is remotely closed already so there will be no frames stream received*/
              if (!webSocket.isCloseInitiator()) {
                finalRegistry.remove(streamId);
                return;
              }
              finalRegistry.put(streamId, Http2WebSocket.CLOSED);
              removeAfterTimeout(
                  streamId, finalRegistry, connectionCloseFuture, channel.eventLoop());
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

  @SuppressWarnings("Convert2MethodRef")
  static Supplier<IntObjectMap<Http2WebSocket>> webSocketRegistryFactory(
      boolean isSingleWebSocketPerConnection) {
    if (isSingleWebSocketPerConnection) {
      return () -> new SingleElementOptimizedMap<>();
    } else {
      return () -> new IntObjectHashMap<>(4);
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
      ChannelFuture channelFuture =
          connectionEncoder.writeHeaders(c, streamId, headers, 0, weight, false, 0, endStream, p);
      c.flush();
      return channelFuture;
    }

    ChannelFuture writeData(int streamId, ByteBuf data, boolean endStream, ChannelPromise promise) {
      ChannelHandlerContext c = ctx;
      return connectionEncoder.writeData(c, streamId, data, 0, endStream, promise);
    }

    ChannelFuture writeRstStream(int streamId, long errorCode) {
      ChannelHandlerContext c = ctx;
      ChannelPromise p = c.newPromise();
      ChannelFuture channelFuture = connectionEncoder.writeRstStream(c, streamId, errorCode, p);
      c.flush();
      return channelFuture;
    }

    ChannelFuture writePriority(int streamId, short weight) {
      ChannelHandlerContext c = ctx;
      ChannelPromise p = c.newPromise();
      ChannelFuture channelFuture =
          connectionEncoder.writePriority(c, streamId, 0, weight, false, p);
      c.flush();
      return channelFuture;
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
      Queue<Http2WebSocketChannel> q = readCompletePendingQueue;
      Http2WebSocketChannel childChannel = q.poll();
      if (childChannel != null) {
        try {
          do {
            childChannel.fireChildReadComplete();
            childChannel = q.poll();
          } while (childChannel != null);
        } finally {
          parentReadInProgress = false;
          q.clear();
          ctx.flush();
        }
      } else {
        parentReadInProgress = false;
      }
    }
  }

  static final class SingleElementOptimizedMap<T> implements IntObjectMap<T> {
    /* 0: empty
     * -1: delegated */
    int singleKey;
    T singleValue;
    IntObjectMap<T> delegate = IntCollections.emptyMap();

    @Override
    public T get(int key) {
      int sk = singleKey;
      if (key == sk) {
        return singleValue;
      }
      if (sk == -1) {
        return delegate.get(key);
      }
      return null;
    }

    @Override
    public T put(int key, T value) {
      int sk = singleKey;
      /*empty or replace*/
      if (sk == 0 || key == sk) {
        T sv = singleValue;
        singleKey = key;
        singleValue = value;
        return sv;
      }
      /*put while nonEmpty - delegate*/
      IntObjectMap<T> d = delegate;
      if (d.isEmpty()) {
        d = delegate = new IntObjectHashMap<>(4);
        d.put(sk, singleValue);
        singleKey = -1;
        singleValue = null;
      }
      return d.put(key, value);
    }

    @Override
    public T remove(int key) {
      int sk = singleKey;
      if (key == sk) {
        T sv = singleValue;
        singleKey = 0;
        singleValue = null;
        return sv;
      }
      /*delegated, so not empty*/
      if (sk == -1) {
        IntObjectMap<T> d = delegate;
        T removed = d.remove(key);
        if (d.isEmpty()) {
          singleKey = 0;
          delegate = IntCollections.emptyMap();
        }
        return removed;
      }
      /*either single key does not match, or empty*/
      return null;
    }

    @Override
    public boolean containsKey(int key) {
      int sk = singleKey;
      return sk == key || sk == -1 && delegate.containsKey(key);
    }

    @Override
    public int size() {
      int sk = singleKey;
      switch (sk) {
        case 0:
          return 0;
        case -1:
          return delegate.size();
          /*sk > 0*/
        default:
          return 1;
      }
    }

    @Override
    public boolean isEmpty() {
      return singleKey == 0;
    }

    @Override
    public void clear() {
      singleKey = 0;
      singleValue = null;
      delegate = IntCollections.emptyMap();
    }

    @Override
    public void forEach(BiConsumer<? super Integer, ? super T> action) {
      int sk = singleKey;
      if (sk > 0) {
        action.accept(sk, singleValue);
      } else if (sk == -1) {
        delegate.forEach(action);
      }
    }

    @Override
    public Iterable<PrimitiveEntry<T>> entries() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean containsKey(Object key) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean containsValue(Object value) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public T get(Object key) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public T put(Integer key, T value) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public T remove(Object key) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void putAll(Map<? extends Integer, ? extends T> m) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Set<Integer> keySet() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Collection<T> values() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Set<Entry<Integer, T>> entrySet() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean equals(Object o) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public int hashCode() {
      throw new UnsupportedOperationException("Not implemented");
    }
  }
}
