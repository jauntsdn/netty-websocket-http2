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
package com.jauntsdn.netty.handler.codec.http2.websocketx;

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Http2WebSocketRemoteCloseEvent;
import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Http2WebSocketStreamWeightUpdateEvent;
import static java.lang.Math.min;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketHandler.WebSocketsParent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionDecoder;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionEncoder;
import io.netty.handler.codec.http2.*;
import io.netty.util.AttributeKey;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.StringUtil;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Http2WebSocketChannel extends DefaultAttributeMap implements Channel, Http2FrameListener {
  private static final Logger logger = LoggerFactory.getLogger(Http2WebSocketChannel.class);
  private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
  private static final AttributeKey<Short> STREAM_WEIGHT_KEY =
      AttributeKey.newInstance("com.jauntsdn.netty.handler.codec.http2.websocketx.stream_weight");
  /**
   * Number of bytes to consider non-payload messages. 9 is arbitrary, but also the minimum size of
   * an HTTP/2 frame. Primarily is non-zero.
   */
  private static final int MIN_HTTP2_FRAME_SIZE = 9;

  /**
   * Returns the flow-control size for DATA frames, and {@value MIN_HTTP2_FRAME_SIZE} for all other
   * frames.
   */
  private static final class FlowControlledFrameSizeEstimator implements MessageSizeEstimator {

    static final FlowControlledFrameSizeEstimator INSTANCE = new FlowControlledFrameSizeEstimator();

    private static final Handle HANDLE_INSTANCE =
        new Handle() {
          @Override
          public int size(Object msg) {
            return msg instanceof Http2DataFrame
                ?
                // Guard against overflow.
                (int)
                    min(
                        Integer.MAX_VALUE,
                        ((Http2DataFrame) msg).initialFlowControlledBytes()
                            + (long) MIN_HTTP2_FRAME_SIZE)
                : MIN_HTTP2_FRAME_SIZE;
          }
        };

    @Override
    public Handle newHandle() {
      return HANDLE_INSTANCE;
    }
  }

  private static final AtomicLongFieldUpdater<Http2WebSocketChannel> TOTAL_PENDING_SIZE_UPDATER =
      AtomicLongFieldUpdater.newUpdater(Http2WebSocketChannel.class, "totalPendingSize");

  private static final AtomicIntegerFieldUpdater<Http2WebSocketChannel> UNWRITABLE_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(Http2WebSocketChannel.class, "unwritable");

  /** The current status of the read-processing for a {@link Http2WebSocketChannel}. */
  private enum ReadStatus {
    /** No read in progress and no read was requested (yet) */
    IDLE,

    /** Reading in progress */
    IN_PROGRESS,

    /** A read operation was requested. */
    REQUESTED
  }

  private final Http2StreamChannelConfig config = new Http2StreamChannelConfig(this);
  private final Http2ChannelUnsafe unsafe = new Http2ChannelUnsafe();
  private final ChannelId channelId;
  private final ChannelPipeline pipeline;
  private final WebSocketsParent webSocketChannelParent;
  private final String websocketChannelSerial;
  private final String path;
  private final String subprotocol;
  private final ChannelPromise closePromise;
  private final ChannelPromise handshakePromise;

  private volatile int streamId;
  private volatile boolean registered;
  private volatile long totalPendingSize;
  private volatile int unwritable;
  // Cached to reduce GC
  private Runnable fireChannelWritabilityChangedTask;
  private boolean outboundClosed;
  /**
   * This variable represents if a read is in progress for the current channel or was requested.
   * Note that depending upon the {@link RecvByteBufAllocator} behavior a read may extend beyond the
   * {@link Http2ChannelUnsafe#beginRead()} method scope. The {@link Http2ChannelUnsafe#beginRead()}
   * loop may drain all pending data, and then if the parent channel is reading this channel may
   * still accept frames.
   */
  private ReadStatus readStatus = ReadStatus.IDLE;

  private Queue<ByteBuf> inboundBuffer;
  private boolean readCompletePending;
  private short pendingStreamWeight;
  private boolean isHandshakeCompleted;
  private WebSocketExtensionEncoder compressionEncoder;
  private WebSocketExtensionDecoder compressionDecoder;

  /*client*/
  Http2WebSocketChannel(
      WebSocketsParent webSocketChannelParent,
      int websocketChannelSerial,
      String path,
      String subprotocol,
      WebSocketDecoderConfig config,
      boolean isEncoderMaskPayload,
      ChannelHandler websocketHandler) {
    this.isHandshakeCompleted = false;
    this.webSocketChannelParent = webSocketChannelParent;
    this.websocketChannelSerial = String.valueOf(websocketChannelSerial);
    this.path = path;
    this.subprotocol = subprotocol;
    pipeline = new WebSocketChannelPipeline(this);
    channelId = new Http2WebSocketChannelId(parent().id(), websocketChannelSerial);

    ChannelPipeline pl = pipeline;
    PreHandshakeHandler preHandshakeHandler = new PreHandshakeHandler();
    pl.addLast(preHandshakeHandler, websocketHandler);

    closePromise = pl.newPromise();

    ChannelPromise hp = handshakePromise = newPromise();
    hp.addListener(
        future -> {
          isHandshakeCompleted = true;

          Throwable cause = future.cause();
          if (cause != null) {
            preHandshakeHandler.cancel(cause);
            return;
          }

          if (config.withUTF8Validator()) {
            pl.addFirst(new Utf8FrameValidator());
          }
          WebSocketExtensionEncoder encoder = compressionEncoder;
          WebSocketExtensionDecoder decoder = compressionDecoder;
          if (encoder != null && decoder != null) {
            pl.addFirst(
                new WebSocket13FrameDecoder(config),
                decoder,
                new WebSocket13FrameEncoder(isEncoderMaskPayload),
                encoder);
          } else {
            pl.addFirst(
                new WebSocket13FrameDecoder(config),
                new WebSocket13FrameEncoder(isEncoderMaskPayload));
          }
          preHandshakeHandler.complete();
        });

    parent().closeFuture().addListener(future -> streamClosed());
  }

  /*server*/
  Http2WebSocketChannel(
      WebSocketsParent webSocketChannelParent,
      int websocketChannelSerial,
      String path,
      String subprotocol,
      WebSocketDecoderConfig config,
      boolean isEncoderMaskPayload,
      @Nullable WebSocketExtensionEncoder compressionEncoder,
      @Nullable WebSocketExtensionDecoder compressionDecoder,
      ChannelHandler websocketHandler) {
    this.isHandshakeCompleted = true;
    this.webSocketChannelParent = webSocketChannelParent;
    this.websocketChannelSerial = String.valueOf(websocketChannelSerial);
    this.path = path;
    this.subprotocol = subprotocol;
    pipeline = new WebSocketChannelPipeline(this);
    channelId = new Http2WebSocketChannelId(parent().id(), websocketChannelSerial);

    ChannelPipeline pl = pipeline;
    if (compressionEncoder != null && compressionDecoder != null) {
      pl.addLast(
          new WebSocket13FrameDecoder(config),
          compressionDecoder,
          new WebSocket13FrameEncoder(isEncoderMaskPayload),
          compressionEncoder);
    } else {
      pl.addLast(
          new WebSocket13FrameDecoder(config), new WebSocket13FrameEncoder(isEncoderMaskPayload));
    }
    if (config.withUTF8Validator()) {
      pl.addLast(new Utf8FrameValidator());
    }
    pl.addLast(websocketHandler);

    closePromise = pl.newPromise();

    handshakePromise = null;

    parent().closeFuture().addListener(future -> streamClosed());
  }

  String serial() {
    return websocketChannelSerial;
  }

  String path() {
    return path;
  }

  String subprotocol() {
    return subprotocol;
  }

  short pendingStreamWeight() {
    short weight = pendingStreamWeight;
    pendingStreamWeight = 0;
    return weight;
  }

  void compression(
      WebSocketExtensionEncoder compressionEncoder, WebSocketExtensionDecoder compressionDecoder) {
    this.compressionEncoder = compressionEncoder;
    this.compressionDecoder = compressionDecoder;
  }

  /* websocket stream */
  @Override
  public int onDataRead(
      ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream)
      throws Http2Exception {
    /*padded data is not handled, firefox does not pad, we neither*/
    int readableBytes = data.readableBytes();
    if (padding > 0) {
      data.release();
      pipeline()
          .fireExceptionCaught(
              new IllegalArgumentException(
                  "Http2WebSocketChannel received padded DATA frame, padding length: " + padding));
      close();
      return readableBytes;
    }
    if (!isHandshakeCompleted) {
      data.release();
      pipeline()
          .fireExceptionCaught(
              new IllegalArgumentException(
                  "Http2WebSocketChannel received DATA frame before handshake completion"));
      close();
      return readableBytes;
    }

    fireChildRead(data, endOfStream);
    return readableBytes;
  }

  @Override
  public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
      throws Http2Exception {
    streamClosed();
  }

  @Override
  public void onGoAwayRead(
      ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData)
      throws Http2Exception {
    streamClosed();
  }

  public Http2WebSocketChannel setStreamId(int streamId) {
    this.streamId = streamId;
    return this;
  }

  public ChannelPromise handshakePromise() {
    return handshakePromise;
  }

  private void incrementPendingOutboundBytes(long size, boolean invokeLater) {
    if (size == 0) {
      return;
    }

    long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, size);
    if (newWriteBufferSize > config().getWriteBufferHighWaterMark()) {
      setUnwritable(invokeLater);
    }
  }

  private void decrementPendingOutboundBytes(long size, boolean invokeLater) {
    if (size == 0) {
      return;
    }

    long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);
    // Once the totalPendingSize dropped below the low water-mark we can mark the child channel
    // as writable again. Before doing so we also need to ensure the parent channel is writable to
    // prevent excessive buffering in the parent outbound buffer. If the parent is not writable
    // we will mark the child channel as writable once the parent becomes writable by calling
    // trySetWritable() later.
    if (newWriteBufferSize < config().getWriteBufferLowWaterMark() && parent().isWritable()) {
      setWritable(invokeLater);
    }
  }

  public void trySetWritable() {
    // The parent is writable again but the child channel itself may still not be writable.
    // Lets try to set the child channel writable to match the state of the parent channel
    // if (and only if) the totalPendingSize is smaller then the low water-mark.
    // If this is not the case we will try again later once we drop under it.
    if (totalPendingSize < config().getWriteBufferLowWaterMark()) {
      setWritable(false);
    }
  }

  private void setWritable(boolean invokeLater) {
    for (; ; ) {
      final int oldValue = unwritable;
      final int newValue = oldValue & ~1;
      if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
        if (oldValue != 0 && newValue == 0) {
          fireChannelWritabilityChanged(invokeLater);
        }
        break;
      }
    }
  }

  private void setUnwritable(boolean invokeLater) {
    for (; ; ) {
      final int oldValue = unwritable;
      final int newValue = oldValue | 1;
      if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
        if (oldValue == 0 && newValue != 0) {
          fireChannelWritabilityChanged(invokeLater);
        }
        break;
      }
    }
  }

  private void fireChannelWritabilityChanged(boolean invokeLater) {
    final ChannelPipeline pipeline = pipeline();
    if (invokeLater) {
      Runnable task = fireChannelWritabilityChangedTask;
      if (task == null) {
        fireChannelWritabilityChangedTask =
            task =
                new Runnable() {
                  @Override
                  public void run() {
                    pipeline.fireChannelWritabilityChanged();
                  }
                };
      }
      eventLoop().execute(task);
    } else {
      pipeline.fireChannelWritabilityChanged();
    }
  }

  public int streamId() {
    return streamId;
  }

  void streamClosed() {
    unsafe.readEOS();
    // Attempt to drain any queued data from the queue and deliver it to the application before
    // closing this
    // channel.
    unsafe.doBeginRead();
  }

  @Override
  public ChannelMetadata metadata() {
    return METADATA;
  }

  @Override
  public ChannelConfig config() {
    return config;
  }

  @Override
  public boolean isOpen() {
    return !closePromise.isDone();
  }

  @Override
  public boolean isActive() {
    return isOpen();
  }

  @Override
  public boolean isWritable() {
    return unwritable == 0;
  }

  @Override
  public ChannelId id() {
    return channelId;
  }

  @Override
  public EventLoop eventLoop() {
    return parent().eventLoop();
  }

  @Override
  public Channel parent() {
    return webSocketChannelParent.context().channel();
  }

  @Override
  public boolean isRegistered() {
    return registered;
  }

  @Override
  public SocketAddress localAddress() {
    return parent().localAddress();
  }

  @Override
  public SocketAddress remoteAddress() {
    return parent().remoteAddress();
  }

  @Override
  public ChannelFuture closeFuture() {
    return closePromise;
  }

  @Override
  public long bytesBeforeUnwritable() {
    long bytes = config().getWriteBufferHighWaterMark() - totalPendingSize;
    // If bytes is negative we know we are not writable, but if bytes is non-negative we have to
    // check
    // writability. Note that totalPendingSize and isWritable() use different volatile variables
    // that are not
    // synchronized together. totalPendingSize will be updated before isWritable().
    if (bytes > 0) {
      return isWritable() ? bytes : 0;
    }
    return 0;
  }

  @Override
  public long bytesBeforeWritable() {
    long bytes = totalPendingSize - config().getWriteBufferLowWaterMark();
    // If bytes is negative we know we are writable, but if bytes is non-negative we have to check
    // writability.
    // Note that totalPendingSize and isWritable() use different volatile variables that are not
    // synchronized
    // together. totalPendingSize will be updated before isWritable().
    if (bytes > 0) {
      return isWritable() ? 0 : bytes;
    }
    return 0;
  }

  @Override
  public Unsafe unsafe() {
    return unsafe;
  }

  @Override
  public ChannelPipeline pipeline() {
    return pipeline;
  }

  @Override
  public ByteBufAllocator alloc() {
    return config().getAllocator();
  }

  @Override
  public Channel read() {
    pipeline().read();
    return this;
  }

  @Override
  public Channel flush() {
    pipeline().flush();
    return this;
  }

  @Override
  public ChannelFuture bind(SocketAddress localAddress) {
    return pipeline().bind(localAddress);
  }

  @Override
  public ChannelFuture connect(SocketAddress remoteAddress) {
    return pipeline().connect(remoteAddress);
  }

  @Override
  public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
    return pipeline().connect(remoteAddress, localAddress);
  }

  @Override
  public ChannelFuture disconnect() {
    return pipeline().disconnect();
  }

  @Override
  public ChannelFuture close() {
    return pipeline().close();
  }

  @Override
  public ChannelFuture deregister() {
    return pipeline().deregister();
  }

  @Override
  public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
    return pipeline().bind(localAddress, promise);
  }

  @Override
  public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
    return pipeline().connect(remoteAddress, promise);
  }

  @Override
  public ChannelFuture connect(
      SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
    return pipeline().connect(remoteAddress, localAddress, promise);
  }

  @Override
  public ChannelFuture disconnect(ChannelPromise promise) {
    return pipeline().disconnect(promise);
  }

  @Override
  public ChannelFuture close(ChannelPromise promise) {
    return pipeline().close(promise);
  }

  @Override
  public ChannelFuture deregister(ChannelPromise promise) {
    return pipeline().deregister(promise);
  }

  @Override
  public ChannelFuture write(Object msg) {
    return pipeline().write(msg);
  }

  @Override
  public ChannelFuture write(Object msg, ChannelPromise promise) {
    return pipeline().write(msg, promise);
  }

  @Override
  public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
    return pipeline().writeAndFlush(msg, promise);
  }

  @Override
  public ChannelFuture writeAndFlush(Object msg) {
    return pipeline().writeAndFlush(msg);
  }

  @Override
  public ChannelPromise newPromise() {
    return pipeline().newPromise();
  }

  @Override
  public ChannelProgressivePromise newProgressivePromise() {
    return pipeline().newProgressivePromise();
  }

  @Override
  public ChannelFuture newSucceededFuture() {
    return pipeline().newSucceededFuture();
  }

  @Override
  public ChannelFuture newFailedFuture(Throwable cause) {
    return pipeline().newFailedFuture(cause);
  }

  @Override
  public ChannelPromise voidPromise() {
    return pipeline().voidPromise();
  }

  @Override
  public int hashCode() {
    return id().hashCode();
  }

  @Override
  public boolean equals(Object o) {
    return this == o;
  }

  @Override
  public int compareTo(Channel o) {
    if (this == o) {
      return 0;
    }

    return id().compareTo(o.id());
  }

  @Override
  public String toString() {
    return parent().toString() /*+ "(H2 - " + stream + ')'*/;
  }

  /**
   * Receive a read message. This does not notify handlers unless a read is in progress on the
   * channel.
   */
  void fireChildRead(ByteBuf data, boolean endOfStream) {
    assert eventLoop().inEventLoop();
    if (!isActive()) {
      ReferenceCountUtil.release(data);
    } else if (readStatus != ReadStatus.IDLE) {
      // If a read is in progress or has been requested, there cannot be anything in the queue,
      // otherwise we would have drained it from the queue and processed it during the read cycle.
      assert inboundBuffer == null || inboundBuffer.isEmpty();
      @SuppressWarnings("deprecation")
      final RecvByteBufAllocator.Handle allocHandle = unsafe.recvBufAllocHandle();
      unsafe.doRead0(data, allocHandle);
      // We currently don't need to check for readEOS because the parent channel and child channel
      // are limited
      // to the same EventLoop thread. There are a limited number of frame types that may come after
      // EOS is
      // read (unknown, reset) and the trade off is less conditionals for the hot path
      // (headers/data) at the
      // cost of additional readComplete notifications on the rare path.
      if (allocHandle.continueReading()) {
        maybeAddChannelToReadCompletePendingQueue();
      } else {
        unsafe.notifyReadComplete(allocHandle, true);
      }
    } else {
      if (inboundBuffer == null) {
        inboundBuffer = new ArrayDeque<>(4);
      }
      inboundBuffer.add(data);
    }
    if (endOfStream) {
      pipeline()
          .fireUserEventTriggered(
              new Http2WebSocketRemoteCloseEvent(serial(), path, System.nanoTime()));
    }
  }

  void fireChildReadComplete() {
    assert eventLoop().inEventLoop();
    assert readStatus != ReadStatus.IDLE || !readCompletePending;
    unsafe.notifyReadComplete(unsafe.recvBufAllocHandle(), false);
  }

  void setStreamWeightAttribute(short streamWeight) {
    attr(STREAM_WEIGHT_KEY).set(streamWeight);
  }

  Short streamWeightAttribute() {
    /*avoid allocation for non-existent key*/
    if (!hasAttr(STREAM_WEIGHT_KEY)) {
      return null;
    }
    return attr(STREAM_WEIGHT_KEY).get();
  }

  private final class Http2ChannelUnsafe implements Unsafe {
    private final VoidChannelPromise unsafeVoidPromise =
        new VoidChannelPromise(Http2WebSocketChannel.this, false);

    @SuppressWarnings("deprecation")
    private RecvByteBufAllocator.Handle recvHandle;

    private boolean writeDoneAndNoFlush;
    private boolean closeInitiated;
    private boolean readEOS;

    @Override
    public void connect(
        final SocketAddress remoteAddress,
        SocketAddress localAddress,
        final ChannelPromise promise) {
      if (!promise.setUncancellable()) {
        return;
      }
      promise.setFailure(new UnsupportedOperationException());
    }

    @SuppressWarnings("deprecation")
    @Override
    public RecvByteBufAllocator.Handle recvBufAllocHandle() {
      if (recvHandle == null) {
        recvHandle = config().getRecvByteBufAllocator().newHandle();
        recvHandle.reset(config());
      }
      return recvHandle;
    }

    @Override
    public SocketAddress localAddress() {
      return parent().unsafe().localAddress();
    }

    @Override
    public SocketAddress remoteAddress() {
      return parent().unsafe().remoteAddress();
    }

    @Override
    public void register(EventLoop eventLoop, ChannelPromise promise) {
      if (!promise.setUncancellable()) {
        return;
      }
      if (registered) {
        promise.setFailure(new UnsupportedOperationException("Re-register is not supported"));
        return;
      }

      registered = true;

      promise.setSuccess();

      pipeline().fireChannelRegistered();
      if (isActive()) {
        pipeline().fireChannelActive();
      }
    }

    @Override
    public void bind(SocketAddress localAddress, ChannelPromise promise) {
      if (!promise.setUncancellable()) {
        return;
      }
      promise.setFailure(new UnsupportedOperationException());
    }

    @Override
    public void disconnect(ChannelPromise promise) {
      close(promise);
    }

    @Override
    public void close(final ChannelPromise promise) {
      if (!promise.setUncancellable()) {
        return;
      }
      if (closeInitiated) {
        if (closePromise.isDone()) {
          // Closed already.
          promise.setSuccess();
        } else if (!(promise
            instanceof VoidChannelPromise)) { // Only needed if no VoidChannelPromise.
          // This means close() was called before so we just register a listener and return
          closePromise.addListener(
              new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                  promise.setSuccess();
                }
              });
        }
        return;
      }
      closeInitiated = true;
      // Just set to false as removing from an underlying queue would even be more expensive.
      readCompletePending = false;

      final boolean wasActive = isActive();

      // There is no need to update the local window as once the stream is closed all the pending
      // bytes will be
      // given back to the connection window by the controller itself.

      // Only ever send a reset frame if the connection is still alive and if the stream was created
      // before
      // as otherwise we may send a RST on a stream in an invalid state and cause a connection
      // error.
      if (parent().isActive() && !readEOS && streamId > 0) {
        writeRstStream();
      }

      if (inboundBuffer != null) {
        for (; ; ) {
          ByteBuf msg = inboundBuffer.poll();
          if (msg == null) {
            break;
          }
          ReferenceCountUtil.release(msg);
        }
        inboundBuffer = null;
      }

      // The promise should be notified before we call fireChannelInactive().
      outboundClosed = true;
      closePromise.setSuccess();
      promise.setSuccess();

      fireChannelInactiveAndDeregister(voidPromise(), wasActive);
    }

    @Override
    public void closeForcibly() {
      close(unsafe().voidPromise());
    }

    @Override
    public void deregister(ChannelPromise promise) {
      fireChannelInactiveAndDeregister(promise, false);
    }

    private void fireChannelInactiveAndDeregister(
        final ChannelPromise promise, final boolean fireChannelInactive) {
      if (!promise.setUncancellable()) {
        return;
      }

      if (!registered) {
        promise.setSuccess();
        return;
      }

      // As a user may call deregister() from within any method while doing processing in the
      // ChannelPipeline,
      // we need to ensure we do the actual deregister operation later. This is necessary to
      // preserve the
      // behavior of the AbstractChannel, which always invokes channelUnregistered and
      // channelInactive
      // events 'later' to ensure the current events in the handler are completed before these
      // events.
      //
      // See:
      // https://github.com/netty/netty/issues/4435
      invokeLater(
          new Runnable() {
            @Override
            public void run() {
              if (fireChannelInactive) {
                pipeline.fireChannelInactive();
              }
              // The user can fire `deregister` events multiple times but we only want to fire the
              // pipeline
              // event if the channel was actually registered.
              if (registered) {
                registered = false;
                pipeline.fireChannelUnregistered();
              }
              safeSetSuccess(promise);
            }
          });
    }

    private void safeSetSuccess(ChannelPromise promise) {
      if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
        logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
      }
    }

    private void invokeLater(Runnable task) {
      try {
        // This method is used by outbound operation implementations to trigger an inbound event
        // later.
        // They do not trigger an inbound event immediately because an outbound operation might have
        // been
        // triggered by another inbound event handler method.  If fired immediately, the call stack
        // will look like this for example:
        //
        //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
        //   -> handlerA.ctx.close()
        //     -> channel.unsafe.close()
        //       -> handlerA.channelInactive() - (2) another inbound handler method called while in
        // (1) yet
        //
        // which means the execution of two inbound handler methods of the same handler overlap
        // undesirably.
        eventLoop().execute(task);
      } catch (RejectedExecutionException e) {
        logger.warn("Can't invoke task later as EventLoop rejected it", e);
      }
    }

    @Override
    public void beginRead() {
      if (!isActive()) {
        return;
      }
      // updateLocalWindowIfNeeded();

      switch (readStatus) {
        case IDLE:
          readStatus = ReadStatus.IN_PROGRESS;
          doBeginRead();
          break;
        case IN_PROGRESS:
          readStatus = ReadStatus.REQUESTED;
          break;
        default:
          break;
      }
    }

    private ByteBuf pollQueuedMessage() {
      return inboundBuffer == null ? null : inboundBuffer.poll();
    }

    void doBeginRead() {
      // Process messages until there are none left (or the user stopped requesting) and also handle
      // EOS.
      while (readStatus != ReadStatus.IDLE) {
        ByteBuf message = pollQueuedMessage();
        if (message == null) {
          if (readEOS) {
            unsafe.closeForcibly();
          }
          // We need to double check that there is nothing left to flush such as a
          // window update frame.
          flush();
          break;
        }
        @SuppressWarnings("deprecation")
        final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
        allocHandle.reset(config());
        boolean continueReading = false;
        do {
          doRead0(message, allocHandle);
        } while ((readEOS || (continueReading = allocHandle.continueReading()))
            && (message = pollQueuedMessage()) != null);

        if (continueReading && isParentReadInProgress() && !readEOS) {
          // Currently the parent and child channel are on the same EventLoop thread. If the parent
          // is
          // currently reading it is possible that more frames will be delivered to this child
          // channel. In
          // the case that this child channel still wants to read we delay the channelReadComplete
          // on this
          // child channel until the parent is done reading.
          maybeAddChannelToReadCompletePendingQueue();
        } else {
          notifyReadComplete(allocHandle, true);
        }
      }
    }

    void readEOS() {
      readEOS = true;
    }

    @SuppressWarnings("deprecation")
    void notifyReadComplete(RecvByteBufAllocator.Handle allocHandle, boolean forceReadComplete) {
      if (!readCompletePending && !forceReadComplete) {
        return;
      }
      // Set to false just in case we added the channel multiple times before.
      readCompletePending = false;

      if (readStatus == ReadStatus.REQUESTED) {
        readStatus = ReadStatus.IN_PROGRESS;
      } else {
        readStatus = ReadStatus.IDLE;
      }

      allocHandle.readComplete();
      pipeline().fireChannelReadComplete();
      // Reading data may result in frames being written (e.g. WINDOW_UPDATE, RST, etc..). If the
      // parent
      // channel is not currently reading we need to force a flush at the child channel, because we
      // cannot
      // rely upon flush occurring in channelReadComplete on the parent channel.
      flush();
      if (readEOS) {
        unsafe.closeForcibly();
      }
    }

    @SuppressWarnings("deprecation")
    void doRead0(ByteBuf data, RecvByteBufAllocator.Handle allocHandle) {
      final int bytes = data.readableBytes();
      // Update before firing event through the pipeline to be consistent with other Channel
      // implementation.
      allocHandle.attemptedBytesRead(bytes);
      allocHandle.lastBytesRead(bytes);
      allocHandle.incMessagesRead(1);

      pipeline().fireChannelRead(data);
    }

    @Override
    public void write(Object msg, final ChannelPromise promise) {
      // After this point its not possible to cancel a write anymore.
      if (!promise.setUncancellable()) {
        ReferenceCountUtil.release(msg);
        return;
      }

      if (!isActive()
          ||
          // Once the outbound side was closed we should not allow header / data frames
          outboundClosed && (msg instanceof ByteBuf)) {
        ReferenceCountUtil.release(msg);
        promise.setFailure(new ClosedChannelException());
        logger.debug("Websocket channel frame dropped because outbound is closed");

        return;
      }

      try {
        if (msg instanceof ByteBuf) {
          writeDataFrame((ByteBuf) msg, promise);
        } else {
          String msgStr = msg.toString();
          ReferenceCountUtil.release(msg);
          promise.setFailure(
              new IllegalArgumentException(
                  "Message must be an "
                      + StringUtil.simpleClassName(ByteBuf.class)
                      + ": "
                      + msgStr));
        }
      } catch (Throwable t) {
        promise.tryFailure(t);
      }
    }

    void writeDataFrame(ByteBuf dataFrameContents, final ChannelPromise promise) {
      ChannelFuture f = writeData(dataFrameContents, false);
      if (f.isDone()) {
        writeComplete(f, promise);
      } else {
        final long bytes = FlowControlledFrameSizeEstimator.HANDLE_INSTANCE.size(dataFrameContents);
        incrementPendingOutboundBytes(bytes, false);
        f.addListener(
            new ChannelFutureListener() {
              @Override
              public void operationComplete(ChannelFuture future) {
                writeComplete(future, promise);
                decrementPendingOutboundBytes(bytes, false);
              }
            });
        writeDoneAndNoFlush = true;
      }
    }

    private void writeComplete(ChannelFuture future, ChannelPromise promise) {
      Throwable cause = future.cause();
      if (cause == null) {
        promise.setSuccess();
      } else {
        Throwable error = wrapStreamClosedError(cause);
        // To make it more consistent with AbstractChannel we handle all IOExceptions here.
        if (error instanceof IOException) {
          if (config.isAutoClose()) {
            // Close channel if needed.
            closeForcibly();
          } else {
            // TODO: Once Http2StreamChannel extends DuplexChannel we should call
            // shutdownOutput(...)
            outboundClosed = true;
          }
        }
        promise.setFailure(error);
      }
    }

    private Throwable wrapStreamClosedError(Throwable cause) {
      // If the error was caused by STREAM_CLOSED we should use a ClosedChannelException to better
      // mimic other transports and make it easier to reason about what exceptions to expect.
      if (cause instanceof Http2Exception
          && ((Http2Exception) cause).error() == Http2Error.STREAM_CLOSED) {
        return new ClosedChannelException().initCause(cause);
      }
      return cause;
    }

    @Override
    public void flush() {
      // If we are currently in the parent channel's read loop we should just ignore the flush.
      // We will ensure we trigger ctx.flush() after we processed all Channels later on and
      // so aggregate the flushes. This is done as ctx.flush() is expensive when as it may trigger
      // an
      // write(...) or writev(...) operation on the socket.
      if (!writeDoneAndNoFlush || isParentReadInProgress()) {
        // There is nothing to flush so this is a NOOP.
        return;
      }
      // We need to set this to false before we call flush0(...) as ChannelFutureListener may
      // produce more data
      // that are explicit flushed.
      writeDoneAndNoFlush = false;
      webSocketChannelParent.context().flush();
    }

    @Override
    public ChannelPromise voidPromise() {
      return unsafeVoidPromise;
    }

    @Override
    public ChannelOutboundBuffer outboundBuffer() {
      // Always return null as we not use the ChannelOutboundBuffer and not even support it.
      return null;
    }
  }

  /**
   * {@link ChannelConfig} so that the high and low writebuffer watermarks can reflect the outbound
   * flow control window, without having to create a new {@link WriteBufferWaterMark} object
   * whenever the flow control window changes.
   */
  private static final class Http2StreamChannelConfig extends DefaultChannelConfig {
    Http2StreamChannelConfig(Channel channel) {
      super(channel);
    }

    @Override
    public MessageSizeEstimator getMessageSizeEstimator() {
      return FlowControlledFrameSizeEstimator.INSTANCE;
    }

    @Override
    public ChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator) {
      if (!(allocator.newHandle() instanceof RecvByteBufAllocator.ExtendedHandle)) {
        throw new IllegalArgumentException(
            "allocator.newHandle() must return an object of type: "
                + RecvByteBufAllocator.ExtendedHandle.class);
      }
      super.setRecvByteBufAllocator(allocator);
      return this;
    }
  }

  private void maybeAddChannelToReadCompletePendingQueue() {
    if (!readCompletePending) {
      readCompletePending = true;
      addChannelToReadCompletePendingQueue();
    }
  }

  private ChannelFuture writeRstStream() {
    logger.debug(
        "Websocket channel writing RST frame for path: {}, streamId: {}, errorCode: {}",
        path,
        streamId,
        Http2Error.CANCEL.code());
    ChannelFuture channelFuture =
        webSocketChannelParent.writeRstStream(streamId, Http2Error.CANCEL.code());
    flush();
    return channelFuture;
  }

  private ChannelFuture writeData(ByteBuf msg, boolean endOfStream) {
    logger.debug("Websocket channel writing DATA frame for path: {}, streamId: {}", path, streamId);
    return webSocketChannelParent.writeData(streamId, msg, 0, endOfStream);
  }

  private ChannelFuture writePriority(short weight) {
    logger.debug(
        "Websocket channel writing PRIORITY frame for path: {}, streamId: {}, weight: {}",
        path,
        streamId,
        weight);
    return webSocketChannelParent.writePriority(streamId, weight);
  }

  private boolean isParentReadInProgress() {
    return webSocketChannelParent.isParentReadInProgress();
  };

  private void addChannelToReadCompletePendingQueue() {
    webSocketChannelParent.addChannelToReadCompletePendingQueue(this);
  }

  /* noop */
  @Override
  public void onPriorityRead(
      ChannelHandlerContext ctx,
      int streamId,
      int streamDependency,
      short weight,
      boolean exclusive)
      throws Http2Exception {}

  @Override
  public void onSettingsAckRead(ChannelHandlerContext ctx) throws Http2Exception {}

  @Override
  public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings)
      throws Http2Exception {}

  @Override
  public void onPingRead(ChannelHandlerContext ctx, long data) throws Http2Exception {}

  @Override
  public void onPingAckRead(ChannelHandlerContext ctx, long data) throws Http2Exception {}

  @Override
  public void onPushPromiseRead(
      ChannelHandlerContext ctx,
      int streamId,
      int promisedStreamId,
      Http2Headers headers,
      int padding)
      throws Http2Exception {}

  @Override
  public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement)
      throws Http2Exception {}

  @Override
  public void onUnknownFrame(
      ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags, ByteBuf payload)
      throws Http2Exception {
    payload.release();
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      int streamId,
      Http2Headers headers,
      int padding,
      boolean endOfStream)
      throws Http2Exception {}

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
      throws Http2Exception {}

  private class WebSocketChannelPipeline extends DefaultChannelPipeline
      implements GenericFutureListener<Future<? super Void>> {

    protected WebSocketChannelPipeline(Channel channel) {
      super(channel);
    }

    @Override
    protected void incrementPendingOutboundBytes(long size) {
      Http2WebSocketChannel.this.incrementPendingOutboundBytes(size, true);
    }

    @Override
    protected void decrementPendingOutboundBytes(long size) {
      Http2WebSocketChannel.this.decrementPendingOutboundBytes(size, true);
    }

    @Override
    protected void onUnhandledInboundUserEventTriggered(Object evt) {
      if (evt instanceof Http2WebSocketEvent) {
        if (closePromise.isDone()) {
          return;
        }
        Http2WebSocketEvent webSocketEvent = (Http2WebSocketEvent) evt;
        switch (webSocketEvent.type()) {
          case CLOSE_LOCAL:
            writeData(Unpooled.EMPTY_BUFFER, true).addListener(this);
            flush();
            break;
          case WEIGHT_UPDATE:
            /*priority update is for client websocket only*/
            if (handshakePromise == null) {
              logger.warn(
                  "Attempted to send PRIORITY frame for stream: {} as server, ignoring", streamId);
              return;
            }
            short weight =
                webSocketEvent.<Http2WebSocketStreamWeightUpdateEvent>cast().streamWeight();
            /*http2 stream was not created yet*/
            if (streamId == 0) {
              pendingStreamWeight = weight;
              return;
            }
            logger.info(
                "Priority frames are ignored until https://github.com/netty/netty/issues/10416 is resolved");
            /*writePriority(weight)
                .addListener(
                    future -> {
                      if (future.isSuccess()) {
                        setStreamWeightAttribute(weight);
                      }
                    });
            flush();*/
            break;
          default:
            /*noop*/
        }
        return;
      }
      super.onUnhandledInboundUserEventTriggered(evt);
    }

    @Override
    public void operationComplete(Future<? super Void> future) throws Exception {
      streamClosed();
    }
  }

  static class Http2WebSocketChannelId implements ChannelId {
    private static final long serialVersionUID = 461278605552437427L;

    private final int id;
    private final ChannelId parentId;

    Http2WebSocketChannelId(ChannelId parentId, int id) {
      this.parentId = parentId;
      this.id = id;
    }

    @Override
    public String asShortText() {
      return parentId.asShortText() + '/' + id;
    }

    @Override
    public String asLongText() {
      return parentId.asLongText() + '/' + id;
    }

    @Override
    public int compareTo(ChannelId o) {
      if (o instanceof Http2WebSocketChannelId) {
        Http2WebSocketChannelId otherId = (Http2WebSocketChannelId) o;
        int res = parentId.compareTo(otherId.parentId);
        if (res == 0) {
          return id - otherId.id;
        } else {
          return res;
        }
      }
      return parentId.compareTo(o);
    }

    @Override
    public int hashCode() {
      return id * 31 + parentId.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Http2WebSocketChannelId)) {
        return false;
      }
      Http2WebSocketChannelId otherId = (Http2WebSocketChannelId) obj;
      return id == otherId.id && parentId.equals(otherId.parentId);
    }

    @Override
    public String toString() {
      return asShortText();
    }
  }

  static class PreHandshakeHandler extends ChannelOutboundHandlerAdapter {
    Queue<PendingOutbound> pendingOutbound;
    boolean isDone;
    ChannelHandlerContext ctx;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      this.ctx = ctx;
      super.handlerAdded(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {
      if (isDone) {
        ReferenceCountUtil.safeRelease(msg);
        return;
      }
      if (!(msg instanceof WebSocketFrame)) {
        super.write(ctx, msg, promise);
        return;
      }

      Queue<PendingOutbound> outbound = pendingOutbound;
      if (outbound == null) {
        outbound = pendingOutbound = new ArrayDeque<>();
      }
      outbound.offer(new PendingOutbound((WebSocketFrame) msg, promise));
    }

    void complete() {
      Queue<PendingOutbound> outbound = pendingOutbound;
      if (outbound == null) {
        ctx.pipeline().remove(this);
        return;
      }
      pendingOutbound = null;
      PendingOutbound o = outbound.poll();
      do {
        ctx.write(o.webSocketFrame, o.completePromise);
        o = outbound.poll();
      } while (o != null);
      ctx.flush();
      ctx.pipeline().remove(this);
    }

    void cancel(@Nullable Throwable cause) {
      isDone = true;
      Queue<PendingOutbound> outbound = pendingOutbound;
      if (outbound == null) {
        ctx.close();
        return;
      }
      pendingOutbound = null;

      PendingOutbound o = outbound.poll();
      do {
        o.completePromise.tryFailure(cause);
        o.webSocketFrame.release();
        o = outbound.poll();
      } while (o != null);
      ctx.close();
    }

    static class PendingOutbound {
      final WebSocketFrame webSocketFrame;
      final ChannelPromise completePromise;

      PendingOutbound(WebSocketFrame webSocketFrame, ChannelPromise completePromise) {
        this.webSocketFrame = webSocketFrame;
        this.completePromise = completePromise;
      }
    }
  }
}
