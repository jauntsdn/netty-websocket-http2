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

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler.*;
import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketUtils.*;
import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketValidator.HEADER_WEBSOCKET_ENDOFSTREAM_NAME;
import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketValidator.endOfStreamValue;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketChannelHandler.WebSocketsParent;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.extensions.*;
import io.netty.handler.codec.http2.*;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

class Http2WebSocketServerHandshaker implements GenericFutureListener<ChannelFuture> {
  private static final AsciiString HEADERS_STATUS_200 = AsciiString.of("200");
  private static final ReadOnlyHttp2Headers HEADERS_OK =
      ReadOnlyHttp2Headers.serverHeaders(true, HEADERS_STATUS_200);
  private static final ReadOnlyHttp2Headers HEADERS_UNSUPPORTED_VERSION =
      ReadOnlyHttp2Headers.serverHeaders(
          true,
          AsciiString.of("400"),
          AsciiString.of(Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME),
          AsciiString.of(Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE));
  private static final ReadOnlyHttp2Headers HEADERS_REJECTED_REQUEST =
      ReadOnlyHttp2Headers.serverHeaders(true, AsciiString.of("400"));
  private static final ReadOnlyHttp2Headers HEADERS_NOT_FOUND =
      ReadOnlyHttp2Headers.serverHeaders(true, AsciiString.of("404"));
  private static final ReadOnlyHttp2Headers HEADERS_INTERNAL_ERROR =
      ReadOnlyHttp2Headers.serverHeaders(true, AsciiString.of("500"));

  private final WebSocketsParent webSocketsParent;
  private final WebSocketHandler.Container webSocketHandlers;
  private final WebSocketDecoderConfig webSocketDecoderConfig;
  private final boolean isEncoderMaskPayload;
  private final WebSocketServerExtensionHandshaker compressionHandshaker;

  Http2WebSocketServerHandshaker(
      WebSocketsParent webSocketsParent,
      WebSocketDecoderConfig webSocketDecoderConfig,
      boolean isEncoderMaskPayload,
      WebSocketHandler.Container webSocketHandlers,
      @Nullable WebSocketServerExtensionHandshaker compressionHandshaker) {
    this.webSocketsParent = webSocketsParent;
    this.webSocketHandlers = webSocketHandlers;
    this.webSocketDecoderConfig = webSocketDecoderConfig;
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    this.compressionHandshaker = compressionHandshaker;
  }

  boolean handshake(final int streamId, final Http2Headers requestHeaders, boolean endOfStream) {
    long startNanos = System.nanoTime();
    ChannelHandlerContext ctx = webSocketsParent.context();

    if (!Http2WebSocketValidator.isValidWebSocket(requestHeaders, endOfStream)) {
      Http2WebSocketEvent.fireHandshakeValidationStartAndError(
          ctx.channel(),
          streamId,
          requestHeaders.set(HEADER_WEBSOCKET_ENDOFSTREAM_NAME, endOfStreamValue(endOfStream)));
      writeRstStream(streamId).addListener(this);
      return false;
    }
    String path = requestHeaders.path().toString();
    CharSequence webSocketVersion =
        requestHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME);
    /*subprotocol*/
    CharSequence subprotocolsSeq =
        requestHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_SUBPROTOCOL_NAME);
    String subprotocols = nonNullString(subprotocolsSeq);

    if (isUnsupportedWebSocketVersion(webSocketVersion)) {
      Http2WebSocketEvent.fireHandshakeStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          WebSocketHandshakeException.class.getName(),
          Http2WebSocketMessages.HANDSHAKE_UNSUPPORTED_VERSION + webSocketVersion);

      writeHeaders(ctx, streamId, HEADERS_UNSUPPORTED_VERSION, true).addListener(this);
      return false;
    }
    /* http2 websocket handshake is successful  */
    WebSocketHandler handler =
        subprotocols.isEmpty()
            ? webSocketHandlers.get(path, subprotocols)
            : webSocketHandlers.get(path, parseSubprotocols(subprotocols));

    /*no handlers for path*/
    if (handler == null) {
      Http2WebSocketEvent.fireHandshakeStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          WebSocketHandshakeException.class.getName(),
          String.format(Http2WebSocketMessages.HANDSHAKE_PATH_NOT_FOUND, path, subprotocols));

      writeHeaders(ctx, streamId, HEADERS_NOT_FOUND, true).addListener(this);
      return false;
    }

    /*compression*/
    WebSocketServerExtension compressionExtension = null;
    WebSocketServerExtensionHandshaker compressionHandshaker = this.compressionHandshaker;
    if (compressionHandshaker != null) {
      CharSequence extensionsHeader =
          requestHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_NAME);
      WebSocketExtensionData compression = Http2WebSocketExtensions.decode(extensionsHeader);
      if (compression != null) {
        compressionExtension = compressionHandshaker.handshakeExtension(compression);
      }
    }
    boolean hasCompression = compressionExtension != null;
    String subprotocol = handler.subprotocol();
    Http2WebSocketAcceptor acceptor = handler.acceptor();
    boolean hasSubprotocol = !subprotocol.isEmpty();

    WebSocketExtensionEncoder compressionEncoder = null;
    WebSocketExtensionDecoder compressionDecoder = null;
    Http2Headers responseHeaders = EmptyHttp2Headers.INSTANCE;

    if (hasCompression || hasSubprotocol || acceptor.writesResponseHeaders()) {
      responseHeaders = new DefaultHttp2Headers();
      if (hasSubprotocol) {
        responseHeaders.set(Http2WebSocketProtocol.HEADER_WEBSOCKET_SUBPROTOCOL_NAME, subprotocol);
      }
      if (hasCompression) {
        responseHeaders.set(
            Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_NAME,
            Http2WebSocketExtensions.encode(compressionExtension.newReponseData()));
        compressionEncoder = compressionExtension.newExtensionEncoder();
        compressionDecoder = compressionExtension.newExtensionDecoder();
      }
    }

    ChannelFuture accepted;
    try {
      accepted = acceptor.accept(ctx, requestHeaders, responseHeaders);
    } catch (Exception e) {
      accepted = ctx.newFailedFuture(e);
    }

    /*async acceptors are not yet supported*/
    if (!accepted.isDone()) {
      accepted.cancel(true);
      Http2WebSocketEvent.fireHandshakeStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          WebSocketHandshakeException.class.getName(),
          Http2WebSocketMessages.HANDSHAKE_UNSUPPORTED_ACCEPTOR_TYPE);

      writeHeaders(ctx, streamId, HEADERS_INTERNAL_ERROR, true).addListener(this);
      return false;
    }

    /*rejected request*/
    if (!accepted.isSuccess()) {
      Http2WebSocketEvent.fireHandshakeStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          accepted.cause());

      writeHeaders(ctx, streamId, HEADERS_REJECTED_REQUEST, true).addListener(this);
      return false;
    }

    WebSocketExtensionEncoder finalCompressionEncoder = compressionEncoder;
    WebSocketExtensionDecoder finalCompressionDecoder = compressionDecoder;
    Http2Headers successHeaders = successHeaders(responseHeaders);
    writeHeaders(ctx, streamId, successHeaders, false)
        .addListener(
            future -> {
              Throwable cause = future.cause();
              /* headers write error*/
              if (cause != null) {
                Channel ch = ctx.channel();
                Http2WebSocketEvent.fireFrameWriteError(ch, future.cause());
                Http2WebSocketEvent.fireHandshakeStartAndError(
                    ch,
                    streamId,
                    path,
                    subprotocols,
                    requestHeaders,
                    startNanos,
                    System.nanoTime(),
                    cause);
                return;
              }

              /* synchronous acceptor, no need for timeout - just register webSocket*/
              ChannelHandler webSocketHandler = handler.handler();
              Http2WebSocketChannel webSocket =
                  new Http2WebSocketChannel(
                          webSocketsParent,
                          streamId,
                          path,
                          subprotocol,
                          webSocketDecoderConfig,
                          isEncoderMaskPayload,
                          finalCompressionEncoder,
                          finalCompressionDecoder,
                          webSocketHandler)
                      .setStreamId(streamId);
              /*synchronous on eventLoop*/
              ChannelFuture registered = ctx.channel().eventLoop().register(webSocket);

              /*event loop registration error*/
              if (!registered.isSuccess()) {
                Http2WebSocketEvent.fireHandshakeStartAndError(
                    ctx.channel(),
                    streamId,
                    path,
                    subprotocols,
                    requestHeaders,
                    startNanos,
                    System.nanoTime(),
                    registered.cause());
                writeRstStream(streamId).addListener(this);
                webSocket.streamClosed();
                return;
              }

              /*websocket channel closed synchronously*/
              if (!webSocket.isOpen()) {
                Http2WebSocketEvent.fireHandshakeStartAndError(
                    ctx.channel(),
                    streamId,
                    path,
                    subprotocols,
                    requestHeaders,
                    startNanos,
                    System.nanoTime(),
                    ClosedChannelException.class.getName(),
                    "websocket channel closed immediately after eventloop registration");
                return;
              }
              webSocketsParent.register(streamId, webSocket);

              Http2WebSocketEvent.fireHandshakeStartAndSuccess(
                  webSocket,
                  streamId,
                  path,
                  subprotocols,
                  requestHeaders,
                  successHeaders,
                  startNanos,
                  System.nanoTime());
            });
    return false;
  }

  /*HEADERS, RST_STREAM frame write*/
  @Override
  public void operationComplete(ChannelFuture future) {
    Throwable cause = future.cause();
    if (cause != null) {
      Http2WebSocketEvent.fireFrameWriteError(future.channel(), cause);
    }
  }

  private ChannelFuture writeHeaders(
      ChannelHandlerContext ctx, int streamId, Http2Headers headers, boolean endStream) {
    ChannelFuture channelFuture = webSocketsParent.writeHeaders(streamId, headers, endStream);
    ctx.flush();
    return channelFuture;
  }

  private ChannelFuture writeRstStream(int streamId) {
    return webSocketsParent.writeRstStream(streamId, Http2Error.PROTOCOL_ERROR.code());
  }

  static Http2Headers handshakeOnlyWebSocket(Http2Headers headers) {
    headers.remove(Http2WebSocketProtocol.HEADER_PROTOCOL_NAME);
    headers.method(Http2WebSocketProtocol.HEADER_METHOD_CONNECT_HANDSHAKED);
    return headers.set(
        Http2WebSocketProtocol.HEADER_PROTOCOL_NAME_HANDSHAKED,
        Http2WebSocketProtocol.HEADER_PROTOCOL_VALUE);
  }

  private static String[] parseSubprotocols(String subprotocols) {
    String[] sp = subprotocols.split(",");
    for (int i = 0; i < sp.length; i++) {
      sp[i] = sp[i].trim();
    }
    return sp;
  }

  private static Http2Headers successHeaders(Http2Headers responseHeaders) {
    if (responseHeaders.isEmpty()) {
      return HEADERS_OK;
    }
    return responseHeaders.status(HEADERS_STATUS_200);
  }

  private static boolean isUnsupportedWebSocketVersion(CharSequence webSocketVersion) {
    return webSocketVersion == null
        || !Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE.contentEquals(webSocketVersion);
  }

  static class Handshake {
    private final Future<Void> channelClose;
    private final ChannelPromise handshake;
    private final long timeoutMillis;
    private boolean done;
    private ScheduledFuture<?> timeoutFuture;
    private Future<?> handshakeCompleteFuture;
    private GenericFutureListener<ChannelFuture> channelCloseListener;

    public Handshake(Future<Void> channelClose, ChannelPromise handshake, long timeoutMillis) {
      this.channelClose = channelClose;
      this.handshake = handshake;
      this.timeoutMillis = timeoutMillis;
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
