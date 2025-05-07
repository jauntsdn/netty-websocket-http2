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

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketHandler.endOfStreamName;
import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketHandler.endOfStreamValue;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketChannelHandler.WebSocketsParent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtension;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Establishes websocket-over-http2 on provided connection channel */
public final class Http2WebSocketClientHandshaker {
  private static final Logger logger =
      LoggerFactory.getLogger(Http2WebSocketClientHandshaker.class);
  private static final int ESTIMATED_DEFERRED_HANDSHAKES = 4;
  private static final AtomicIntegerFieldUpdater<Http2WebSocketClientHandshaker>
      WEBSOCKET_CHANNEL_SERIAL =
          AtomicIntegerFieldUpdater.newUpdater(
              Http2WebSocketClientHandshaker.class, "webSocketChannelSerial");
  private static final Http2Headers EMPTY_HEADERS = new DefaultHttp2Headers(false);

  private final Http1WebSocketCodec webSocketCodec;
  private final Http2Connection.Endpoint<Http2LocalFlowController> streamIdFactory;
  private final WebSocketDecoderConfig webSocketDecoderConfig;
  private final WebSocketsParent webSocketsParent;
  private final short streamWeight;
  private final CharSequence scheme;
  private final PerMessageDeflateClientExtensionHandshaker compressionHandshaker;
  private final boolean isEncoderMaskPayload;
  private final boolean isNomaskingExtension;
  private final long timeoutMillis;
  private Queue<Handshake> deferred;
  private Boolean supportsWebSocket;
  private volatile int webSocketChannelSerial;
  private CharSequence extensionsHeader;

  Http2WebSocketClientHandshaker(
      WebSocketsParent webSocketsParent,
      Http2Connection.Endpoint<Http2LocalFlowController> streamIdFactory,
      WebSocketDecoderConfig webSocketDecoderConfig,
      boolean isEncoderMaskPayload,
      boolean isNomaskingExtension,
      short streamWeight,
      CharSequence scheme,
      long handshakeTimeoutMillis,
      Http1WebSocketCodec webSocketCodec,
      @Nullable PerMessageDeflateClientExtensionHandshaker compressionHandshaker) {
    this.webSocketsParent = webSocketsParent;
    this.streamIdFactory = streamIdFactory;
    this.webSocketDecoderConfig = webSocketDecoderConfig;
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    this.isNomaskingExtension = isNomaskingExtension;
    this.timeoutMillis = handshakeTimeoutMillis;
    this.streamWeight = streamWeight;
    this.scheme = scheme;
    this.webSocketCodec = webSocketCodec;
    this.compressionHandshaker = compressionHandshaker;
  }

  /**
   * Creates new {@link Http2WebSocketClientHandshaker} for given connection channel
   *
   * @param channel connection channel. Pipeline must contain {@link Http2WebSocketClientHandler}
   *     and netty http2 codec (e.g. Http2ConnectionHandler or Http2FrameCodec)
   * @return new {@link Http2WebSocketClientHandshaker} instance
   */
  public static Http2WebSocketClientHandshaker create(Channel channel) {
    Objects.requireNonNull(channel, "channel");
    return Http2WebSocketHandler.requireChannelHandler(channel, Http2WebSocketClientHandler.class)
        .handShaker();
  }

  /**
   * Starts websocket-over-http2 handshake using given path
   *
   * @param path websocket path, must be non-empty
   * @param webSocketHandler http1 websocket handler added to pipeline of subchannel created for
   *     successfully handshaked http2 websocket
   * @return ChannelFuture with result of handshake. Its channel accepts http1 WebSocketFrames as
   *     soon as this method returns.
   */
  public ChannelFuture handshake(String path, ChannelHandler webSocketHandler) {
    return handshake("", path, "", EMPTY_HEADERS, webSocketHandler);
  }

  /**
   * Starts websocket-over-http2 handshake using given path and request headers
   *
   * @param path websocket path, must be non-empty
   * @param requestHeaders request headers, must be non-null
   * @param webSocketHandler http1 websocket handler added to pipeline of subchannel created for
   *     successfully handshaked http2 websocket
   * @return ChannelFuture with result of handshake. Its channel accepts http1 WebSocketFrames as
   *     soon as this method returns.
   */
  public ChannelFuture handshake(
      String path, Http2Headers requestHeaders, ChannelHandler webSocketHandler) {
    return handshake("", path, "", requestHeaders, webSocketHandler);
  }

  /**
   * Starts websocket-over-http2 handshake using given path and subprotocol
   *
   * @param path websocket path, must be non-empty
   * @param subprotocol websocket subprotocol, must be non-null
   * @param webSocketHandler http1 websocket handler added to pipeline of subchannel created for
   *     successfully handshaked http2 websocket
   * @return ChannelFuture with result of handshake. Its channel accepts http1 WebSocketFrames as
   *     soon as this method returns.
   */
  public ChannelFuture handshake(String path, String subprotocol, ChannelHandler webSocketHandler) {
    return handshake("", path, subprotocol, EMPTY_HEADERS, webSocketHandler);
  }

  /**
   * Starts websocket-over-http2 handshake using given path, subprotocol and request headers
   *
   * @param path websocket path, must be non-empty
   * @param subprotocol websocket subprotocol, must be non-null
   * @param requestHeaders request headers, must be non-null
   * @param webSocketHandler http1 websocket handler added to pipeline of subchannel created for
   *     successfully handshaked http2 websocket
   * @return ChannelFuture with result of handshake. Its channel accepts http1 WebSocketFrames as
   *     soon as this method returns.
   */
  public ChannelFuture handshake(
      String path,
      String subprotocol,
      Http2Headers requestHeaders,
      ChannelHandler webSocketHandler) {
    return handshake("", path, subprotocol, requestHeaders, webSocketHandler);
  }

  /**
   * Starts websocket-over-http2 handshake using given authority, path, subprotocol and request
   * headers
   *
   * @param authority websocket authority, must be non-null
   * @param path websocket path, must be non-empty
   * @param subprotocol websocket subprotocol, must be non-null
   * @param requestHeaders request headers, must be non-null
   * @param webSocketHandler http1 websocket handler added to pipeline of subchannel created for
   *     successfully handshaked http2 websocket
   * @return ChannelFuture with result of handshake. Its channel accepts http1 WebSocketFrames as
   *     soon as this method returns.
   */
  public ChannelFuture handshake(
      String authority,
      String path,
      String subprotocol,
      Http2Headers requestHeaders,
      ChannelHandler webSocketHandler) {
    requireNonEmpty(path, "path");
    Objects.requireNonNull(authority, "authority");
    Objects.requireNonNull(subprotocol, "subprotocol");
    Objects.requireNonNull(requestHeaders, "requestHeaders");
    Objects.requireNonNull(webSocketHandler, "webSocketHandler");

    long startNanos = System.nanoTime();
    ChannelHandlerContext ctx = webSocketsParent.context();

    if (!ctx.channel().isOpen()) {
      return ctx.newFailedFuture(new ClosedChannelException());
    }
    int serial = WEBSOCKET_CHANNEL_SERIAL.getAndIncrement(this);

    Http2WebSocketChannel webSocketChannel =
        new Http2WebSocketChannel(
                webSocketsParent,
                serial,
                authority,
                path,
                subprotocol,
                webSocketCodec,
                webSocketHandler)
            .initialize();

    Handshake handshake =
        new Handshake(webSocketChannel, requestHeaders, timeoutMillis, startNanos);

    handshake
        .future()
        .addListener(
            future -> {
              Throwable cause = future.cause();
              /*error due to external event, e.g. cancellation, timeout*/
              if (cause != null && !(cause instanceof WebSocketHandshakeException)) {
                Http2WebSocketEvent.fireHandshakeError(
                    webSocketChannel, null, System.nanoTime(), cause);
              }
            });

    EventLoop el = ctx.channel().eventLoop();
    if (el.inEventLoop()) {
      handshakeOrDefer(handshake, el);
    } else {
      el.execute(() -> handshakeOrDefer(handshake, el));
    }
    return webSocketChannel.handshakePromise();
  }

  void handshake(Http2WebSocket webSocket, Http2Headers responseHeaders, boolean endOfStream) {
    if (webSocket == Http2WebSocket.CLOSED) {
      return;
    }
    Http2WebSocketChannel webSocketChannel = (Http2WebSocketChannel) webSocket;
    ChannelPromise handshakePromise = webSocketChannel.handshakePromise();

    if (handshakePromise.isDone()) {
      return;
    }

    String errorMessage = null;
    boolean encoderMaskPayload = isEncoderMaskPayload;
    WebSocketDecoderConfig decoderConfig = webSocketDecoderConfig;
    WebSocketClientExtension compressionExtension = null;

    String status = responseHeaders.status().toString();
    switch (status) {
      case "200":
        if (endOfStream) {
          errorMessage = Http2WebSocketProtocol.MSG_HANDSHAKE_UNEXPECTED_RESULT;
        } else {
          /*subprotocol*/
          String clientSubprotocol = webSocketChannel.subprotocol();
          CharSequence serverSubprotocol =
              responseHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_SUBPROTOCOL_NAME);
          if (!isEqual(clientSubprotocol, serverSubprotocol)) {
            errorMessage =
                Http2WebSocketProtocol.MSG_HANDSHAKE_UNEXPECTED_SUBPROTOCOL + clientSubprotocol;
          }
          /*extensions*/
          if (errorMessage == null) {
            PerMessageDeflateClientExtensionHandshaker handshaker = compressionHandshaker;
            boolean supportsCompression = handshaker != null;
            boolean supportsNomasking = isNomaskingExtension;

            boolean supportsExtensions = supportsCompression || supportsNomasking;

            CharSequence extensionsHeader =
                supportsExtensions
                    ? responseHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_NAME)
                    : null;
            Http2WebSocketProtocol.WebSocketExtensions extensions =
                Http2WebSocketProtocol.decodeExtensions(extensionsHeader);

            if (extensions != null) {
              if (extensions.isNomasking() && supportsNomasking) {
                encoderMaskPayload =
                    Http2WebSocketProtocol.WEBSOCKET_EXTENSIONS_NOMASKING_MASK_PAYLOAD;
                decoderConfig =
                    Http2WebSocketProtocol.nomaskingExtensionDecoderConfig(decoderConfig);
              }
              WebSocketExtensionData compression = extensions.compression();
              if (compression != null && supportsCompression) {
                compressionExtension = handshaker.handshakeExtension(compression);
              }
            }
          }
        }
        break;
      case "400":
        CharSequence webSocketVersion =
            responseHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME);
        errorMessage =
            webSocketVersion != null
                ? Http2WebSocketProtocol.MSG_HANDSHAKE_UNSUPPORTED_VERSION + webSocketVersion
                : Http2WebSocketProtocol.MSG_HANDSHAKE_BAD_REQUEST;
        break;
      case "404":
        errorMessage =
            Http2WebSocketProtocol.MSG_HANDSHAKE_PATH_NOT_FOUND
                + webSocketChannel.path()
                + Http2WebSocketProtocol.MSG_HANDSHAKE_PATH_NOT_FOUND_SUBPROTOCOLS
                + webSocketChannel.subprotocol();
        break;
      default:
        errorMessage = Http2WebSocketProtocol.MSG_HANDSHAKE_GENERIC_ERROR + status;
    }
    if (errorMessage != null) {
      Exception cause = new WebSocketHandshakeException(errorMessage);
      if (handshakePromise.tryFailure(cause)) {
        Http2WebSocketEvent.fireHandshakeError(
            webSocketChannel, responseHeaders, System.nanoTime(), cause);
      }
      return;
    }
    webSocketChannel.codecConfig(decoderConfig, encoderMaskPayload);
    if (compressionExtension != null) {
      webSocketChannel.compression(
          compressionExtension.newExtensionEncoder(), compressionExtension.newExtensionDecoder());
    }
    if (handshakePromise.trySuccess()) {
      Http2WebSocketEvent.fireHandshakeSuccess(
          webSocketChannel, responseHeaders, System.nanoTime());
    }
  }

  void reject(int streamId, Http2WebSocket webSocket, Http2Headers headers, boolean endOfStream) {
    Http2WebSocketEvent.fireHandshakeValidationStartAndError(
        webSocketsParent.context().channel(),
        streamId,
        headers.set(endOfStreamName(), endOfStreamValue(endOfStream)));

    if (webSocket == Http2WebSocket.CLOSED) {
      return;
    }
    Http2WebSocketChannel webSocketChannel = (Http2WebSocketChannel) webSocket;
    ChannelPromise handshakePromise = webSocketChannel.handshakePromise();
    if (handshakePromise.isDone()) {
      return;
    }
    Exception cause =
        new WebSocketHandshakeException(
            Http2WebSocketProtocol.MSG_HANDSHAKE_INVALID_RESPONSE_HEADERS);
    if (handshakePromise.tryFailure(cause)) {
      Http2WebSocketEvent.fireHandshakeError(webSocketChannel, headers, System.nanoTime(), cause);
    }
  }

  void onSupportsWebSocket(boolean supportsWebSocket) {
    if (!supportsWebSocket) {
      logger.error(Http2WebSocketProtocol.MSG_HANDSHAKE_UNSUPPORTED_BOOTSTRAP);
    }
    this.supportsWebSocket = supportsWebSocket;
    handshakeDeferred(supportsWebSocket);
  }

  private void handshakeOrDefer(Handshake handshake, EventLoop eventLoop) {
    if (handshake.isDone()) {
      return;
    }
    Http2WebSocketChannel webSocketChannel = handshake.webSocketChannel();
    Http2Headers requestHeaders = handshake.requestHeaders();
    long startNanos = handshake.startNanos();

    /*synchronous on eventLoop*/
    ChannelFuture registered = eventLoop.register(webSocketChannel);
    if (!registered.isSuccess()) {
      Throwable cause = registered.cause();
      Exception e =
          new WebSocketHandshakeException("websocket handshake channel registration error", cause);
      Http2WebSocketEvent.fireHandshakeStartAndError(
          webSocketChannel.parent(),
          webSocketChannel.serial(),
          webSocketChannel.authority(),
          webSocketChannel.path(),
          webSocketChannel.subprotocol(),
          requestHeaders,
          startNanos,
          System.nanoTime(),
          e);

      handshake.complete(e);
      return;
    }
    Http2WebSocketEvent.fireHandshakeStart(webSocketChannel, requestHeaders, startNanos);

    Boolean supports = supportsWebSocket;
    /*websocket support is not known yet*/
    if (supports == null) {
      Queue<Handshake> d = deferred;
      if (d == null) {
        d = deferred = new ArrayDeque<>(ESTIMATED_DEFERRED_HANDSHAKES);
      }
      handshake.startTimeout();
      d.add(handshake);
      return;
    }
    if (supports) {
      handshake.startTimeout();
    }
    handshakeImmediate(handshake, supports);
  }

  private void handshakeDeferred(boolean supportsWebSocket) {
    Queue<Handshake> d = deferred;
    if (d == null) {
      return;
    }
    deferred = null;
    Handshake handshake = d.poll();
    while (handshake != null) {
      handshakeImmediate(handshake, supportsWebSocket);
      handshake = d.poll();
    }
  }

  private void handshakeImmediate(Handshake handshake, boolean supportsWebSocket) {
    Http2WebSocketChannel webSocketChannel = handshake.webSocketChannel();
    Http2Headers customHeaders = handshake.requestHeaders();
    if (handshake.isDone()) {
      return;
    }
    /*server does not support http2 websockets*/
    if (!supportsWebSocket) {
      WebSocketHandshakeException e =
          new WebSocketHandshakeException(
              Http2WebSocketProtocol.MSG_HANDSHAKE_UNSUPPORTED_BOOTSTRAP);
      Http2WebSocketEvent.fireHandshakeError(webSocketChannel, null, System.nanoTime(), e);
      handshake.complete(e);
      return;
    }
    int streamId = streamIdFactory.incrementAndGetNextStreamId();
    webSocketsParent.register(streamId, webSocketChannel.setStreamId(streamId));

    String path = webSocketChannel.path();
    String authority = webSocketChannel.authority();
    if (authority.isEmpty()) {
      authority = authorityFromAddress();
    }
    Http2Headers headers =
        Http2WebSocketProtocol.extendedConnect(
            new DefaultHttp2Headers()
                .scheme(scheme)
                .authority(authority)
                .path(path)
                /* sec-websocket-version=13 only */
                .set(
                    Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME,
                    Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE));

    /*extensions*/
    PerMessageDeflateClientExtensionHandshaker compression = compressionHandshaker;
    boolean hasCompression = compression != null;
    boolean hasNomasking = isNomaskingExtension;
    if (hasCompression) {
      headers.set(
          Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_NAME,
          encodeExtensions(compression, hasNomasking));
    } else if (hasNomasking) {
      headers.set(
          Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_NAME,
          Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_VALUE_NOMASKING);
    }
    /*subprotocol*/
    String subprotocol = webSocketChannel.subprotocol();
    if (!subprotocol.isEmpty()) {
      headers.set(Http2WebSocketProtocol.HEADER_WEBSOCKET_SUBPROTOCOL_NAME, subprotocol);
    }
    /*custom headers*/
    if (!customHeaders.isEmpty()) {
      headers.setAll(customHeaders);
    }

    short pendingStreamWeight = webSocketChannel.pendingStreamWeight();
    short weight = pendingStreamWeight > 0 ? pendingStreamWeight : streamWeight;
    webSocketsParent
        .writeHeaders(webSocketChannel.streamId(), headers, false, weight)
        .addListener(
            future -> {
              if (!future.isSuccess()) {
                handshake.complete(future.cause());
                return;
              }
              webSocketChannel.setStreamWeightAttribute(weight);
            });
  }

  private String authorityFromAddress() {
    return ((InetSocketAddress) webSocketsParent.context().channel().remoteAddress())
        .getHostString();
  }

  private CharSequence encodeExtensions(
      PerMessageDeflateClientExtensionHandshaker compressionExtension,
      boolean isNomaskingExtension) {
    /*extensions config is shared by all websockets of connection*/
    CharSequence header = extensionsHeader;
    if (header == null) {
      header =
          extensionsHeader =
              AsciiString.of(
                  Http2WebSocketProtocol.encodeExtensions(
                      compressionExtension.newRequestData(), isNomaskingExtension));
    }
    return header;
  }

  private static boolean isEqual(String str, @Nullable CharSequence seq) {
    /*both empty*/
    if ((seq == null || seq.length() == 0) && str.isEmpty()) {
      return true;
    }
    if (seq == null) {
      return false;
    }
    return str.contentEquals(seq);
  }

  private static String requireNonEmpty(String string, String message) {
    if (string == null || string.isEmpty()) {
      throw new IllegalArgumentException(message + " must be non empty");
    }
    return string;
  }

  static class Handshake {
    private final Http2WebSocketChannel webSocketChannel;
    private final Http2Headers requestHeaders;
    private final long handshakeStartNanos;
    private final Future<Void> channelClose;
    private final ChannelPromise handshake;
    private final long timeoutMillis;
    private boolean done;
    private ScheduledFuture<?> timeoutFuture;
    private Future<?> handshakeCompleteFuture;
    private GenericFutureListener<ChannelFuture> channelCloseListener;

    public Handshake(
        Http2WebSocketChannel webSocketChannel,
        Http2Headers requestHeaders,
        long timeoutMillis,
        long handshakeStartNanos) {
      this.channelClose = webSocketChannel.closeFuture();
      this.handshake = webSocketChannel.handshakePromise();
      this.timeoutMillis = timeoutMillis;
      this.webSocketChannel = webSocketChannel;
      this.requestHeaders = requestHeaders;
      this.handshakeStartNanos = handshakeStartNanos;
    }

    public Http2WebSocketChannel webSocketChannel() {
      return webSocketChannel;
    }

    public Http2Headers requestHeaders() {
      return requestHeaders;
    }

    public long startNanos() {
      return handshakeStartNanos;
    }

    public void startTimeout() {
      ChannelPromise h = handshake;
      Channel channel = h.channel();

      if (done) {
        return;
      }
      GenericFutureListener<ChannelFuture> l = channelCloseListener = future -> onConnectionClose();
      channelClose.addListener(l);
      /*account for possible synchronous callback execution*/
      if (done) {
        return;
      }
      handshakeCompleteFuture = h.addListener(future -> onHandshakeComplete(future.cause()));
      if (done) {
        return;
      }
      timeoutFuture =
          channel.eventLoop().schedule(this::onTimeout, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void complete(Throwable e) {
      onHandshakeComplete(e);
    }

    public boolean isDone() {
      return done;
    }

    public ChannelFuture future() {
      return handshake;
    }

    private void onConnectionClose() {
      if (!done) {
        handshake.tryFailure(new ClosedChannelException());
        done();
      }
    }

    private void onHandshakeComplete(Throwable cause) {
      if (!done) {
        if (cause != null) {
          handshake.tryFailure(cause);
        } else {
          handshake.trySuccess();
        }
        done();
      }
    }

    private void onTimeout() {
      if (!done) {
        handshake.tryFailure(new TimeoutException());
        done();
      }
    }

    private void done() {
      done = true;
      GenericFutureListener<ChannelFuture> closeListener = channelCloseListener;
      if (closeListener != null) {
        channelClose.removeListener(closeListener);
      }
      cancel(handshakeCompleteFuture);
      cancel(timeoutFuture);
    }

    private void cancel(Future<?> future) {
      if (future != null) {
        future.cancel(true);
      }
    }
  }
}
