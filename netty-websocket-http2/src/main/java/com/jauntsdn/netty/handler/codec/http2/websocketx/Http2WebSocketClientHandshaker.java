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

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketHandler.*;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketChannelHandler.WebSocketsParent;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtension;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;
import io.netty.handler.codec.http2.*;
import io.netty.util.AsciiString;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
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

  private final Http2Connection.Endpoint<Http2LocalFlowController> streamIdFactory;
  private final WebSocketDecoderConfig webSocketDecoderConfig;
  private final WebSocketsParent webSocketsParent;
  private final short streamWeight;
  private final CharSequence scheme;
  private final PerMessageDeflateClientExtensionHandshaker compressionHandshaker;
  private final boolean isEncoderMaskPayload;
  private final long timeoutMillis;
  private Queue<Handshake> deferred;
  private Boolean supportsWebSocket;
  private volatile int webSocketChannelSerial;
  private CharSequence compressionExtensionHeader;

  Http2WebSocketClientHandshaker(
      WebSocketsParent webSocketsParent,
      Http2Connection.Endpoint<Http2LocalFlowController> streamIdFactory,
      WebSocketDecoderConfig webSocketDecoderConfig,
      boolean isEncoderMaskPayload,
      short streamWeight,
      CharSequence scheme,
      long handshakeTimeoutMillis,
      @Nullable PerMessageDeflateClientExtensionHandshaker compressionHandshaker) {
    this.webSocketsParent = webSocketsParent;
    this.streamIdFactory = streamIdFactory;
    this.webSocketDecoderConfig = webSocketDecoderConfig;
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    this.timeoutMillis = handshakeTimeoutMillis;
    this.streamWeight = streamWeight;
    this.scheme = scheme;
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
    return Preconditions.requireHandler(channel, Http2WebSocketClientHandler.class).handShaker();
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
    return handshake(path, "", EMPTY_HEADERS, webSocketHandler);
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
    return handshake(path, "", requestHeaders, webSocketHandler);
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
    return handshake(path, subprotocol, EMPTY_HEADERS, webSocketHandler);
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
    Preconditions.requireNonEmpty(path, "path");
    Preconditions.requireNonNull(subprotocol, "subprotocol");
    Preconditions.requireNonNull(requestHeaders, "requestHeaders");
    Preconditions.requireNonNull(webSocketHandler, "webSocketHandler");

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
                path,
                subprotocol,
                webSocketDecoderConfig,
                isEncoderMaskPayload,
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
    WebSocketClientExtension compressionExtension = null;

    String status = responseHeaders.status().toString();
    switch (status) {
      case "200":
        if (endOfStream) {
          errorMessage = Http2WebSocketMessages.HANDSHAKE_UNEXPECTED_RESULT;
        } else {
          /*subprotocol*/
          String clientSubprotocol = webSocketChannel.subprotocol();
          CharSequence serverSubprotocol =
              responseHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_SUBPROTOCOL_NAME);
          if (!isEqual(clientSubprotocol, serverSubprotocol)) {
            errorMessage =
                Http2WebSocketMessages.HANDSHAKE_UNSUPPORTED_SUBPROTOCOL + clientSubprotocol;
          }
          /*compression*/
          if (errorMessage == null) {
            PerMessageDeflateClientExtensionHandshaker handshaker = compressionHandshaker;
            if (handshaker != null) {
              CharSequence extensionsHeader =
                  responseHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_NAME);
              WebSocketExtensionData compression =
                  Http2WebSocketExtensions.decode(extensionsHeader);
              if (compression != null) {
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
                ? Http2WebSocketMessages.HANDSHAKE_UNSUPPORTED_VERSION + webSocketVersion
                : Http2WebSocketMessages.HANDSHAKE_BAD_REQUEST;
        break;
      case "404":
        errorMessage =
            String.format(
                Http2WebSocketMessages.HANDSHAKE_PATH_NOT_FOUND,
                webSocketChannel.path(),
                webSocketChannel.subprotocol());
        break;
      default:
        errorMessage = Http2WebSocketMessages.HANDSHAKE_GENERIC_ERROR + status;
    }
    if (errorMessage != null) {
      Exception cause = new WebSocketHandshakeException(errorMessage);
      if (handshakePromise.tryFailure(cause)) {
        Http2WebSocketEvent.fireHandshakeError(
            webSocketChannel, responseHeaders, System.nanoTime(), cause);
      }
      return;
    }
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
        new WebSocketHandshakeException(Http2WebSocketMessages.HANDSHAKE_INVALID_RESPONSE_HEADERS);
    if (handshakePromise.tryFailure(cause)) {
      Http2WebSocketEvent.fireHandshakeError(webSocketChannel, headers, System.nanoTime(), cause);
    }
  }

  void onSupportsWebSocket(boolean supportsWebSocket) {
    if (!supportsWebSocket) {
      logger.error(Http2WebSocketMessages.HANDSHAKE_UNSUPPORTED_BOOTSTRAP);
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
          new WebSocketHandshakeException(Http2WebSocketMessages.HANDSHAKE_UNSUPPORTED_BOOTSTRAP);
      Http2WebSocketEvent.fireHandshakeError(webSocketChannel, null, System.nanoTime(), e);
      handshake.complete(e);
      return;
    }
    int streamId = streamIdFactory.incrementAndGetNextStreamId();
    webSocketsParent.register(streamId, webSocketChannel.setStreamId(streamId));

    String path = webSocketChannel.path();
    String authority = authority();
    Http2Headers headers =
        Http2WebSocketProtocol.extendedConnect()
            .scheme(scheme)
            .authority(authority)
            .path(path)
            /* sec-websocket-version=13 only */
            .set(
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME,
                Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE);

    /*compression*/
    PerMessageDeflateClientExtensionHandshaker handshaker = compressionHandshaker;
    if (handshaker != null) {
      headers.set(
          Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_NAME,
          compressionExtensionHeader(handshaker));
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

  private String authority() {
    return ((InetSocketAddress) webSocketsParent.context().channel().remoteAddress())
        .getHostString();
  }

  private CharSequence compressionExtensionHeader(
      PerMessageDeflateClientExtensionHandshaker handshaker) {
    /*compression config is shared by all websockets of connection*/
    CharSequence header = compressionExtensionHeader;
    if (header == null) {
      header =
          compressionExtensionHeader =
              AsciiString.of(Http2WebSocketExtensions.encode(handshaker.newRequestData()));
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

  static class Handshake extends Http2WebSocketServerHandshaker.Handshake {
    private final Http2WebSocketChannel webSocketChannel;
    private final Http2Headers requestHeaders;
    private final long handshakeStartNanos;

    public Handshake(
        Http2WebSocketChannel webSocketChannel,
        Http2Headers requestHeaders,
        long timeoutMillis,
        long handshakeStartNanos) {
      super(webSocketChannel.closeFuture(), webSocketChannel.handshakePromise(), timeoutMillis);
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
  }
}
