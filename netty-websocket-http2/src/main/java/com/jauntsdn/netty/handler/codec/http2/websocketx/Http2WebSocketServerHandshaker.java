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

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.*;
import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler.*;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketChannelHandler.WebSocketsParent;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.extensions.*;
import io.netty.handler.codec.http2.*;
import io.netty.util.AsciiString;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import javax.annotation.Nullable;

class Http2WebSocketServerHandshaker {
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
  private final Map<String, AcceptorHandler> webSocketHandlers;
  private final WebSocketDecoderConfig webSocketDecoderConfig;
  private final boolean isEncoderMaskPayload;
  private final long handshakeTimeoutMillis;
  private final WebSocketServerExtensionHandshaker compressionHandshaker;
  private int webSocketChannelSerial;

  Http2WebSocketServerHandshaker(
      WebSocketsParent webSocketsParent,
      WebSocketDecoderConfig webSocketDecoderConfig,
      boolean isEncoderMaskPayload,
      long handshakeTimeoutMillis,
      Map<String, AcceptorHandler> webSocketHandlers,
      @Nullable WebSocketServerExtensionHandshaker compressionHandshaker) {
    this.webSocketsParent = webSocketsParent;
    this.webSocketHandlers = webSocketHandlers;
    this.webSocketDecoderConfig = webSocketDecoderConfig;
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    this.compressionHandshaker = compressionHandshaker;
  }

  static boolean handshakeProtocol(final Http2Headers requestHeaders, boolean endOfStream) {
    if (endOfStream) {
      return false;
    }
    CharSequence pathSeq = requestHeaders.path();
    if (isEmpty(pathSeq)) {
      return false;
    }
    CharSequence authority = requestHeaders.authority();
    if (isEmpty(authority)) {
      return false;
    }
    CharSequence scheme = requestHeaders.scheme();
    if (isEmpty(scheme) || !isHttp(scheme)) {
      return false;
    }
    return true;
  }

  boolean handshake(final int streamId, final Http2Headers requestHeaders, boolean endOfStream) {
    long startNanos = System.nanoTime();

    if (!handshakeProtocol(requestHeaders, endOfStream)) {
      writeRstStream(streamId);
      return false;
    }
    String path = requestHeaders.path().toString();
    CharSequence webSocketVersion =
        requestHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME);
    /*subprotocol*/
    CharSequence subprotocolsSeq =
        requestHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_SUBPROTOCOL_NAME);
    String subprotocols = subprotocolsSeq == null ? "" : subprotocolsSeq.toString();

    ChannelHandlerContext ctx = webSocketsParent.context();

    if (isUnsupportedWebSocketVersion(webSocketVersion)) {
      Http2WebSocketHandshakeEvent.fireStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          WebSocketHandshakeException.class.getName(),
          Http2WebSocketMessages.HANDSHAKE_UNSUPPORTED_VERSION + webSocketVersion);

      writeHeaders(ctx, streamId, HEADERS_UNSUPPORTED_VERSION, true);
      return false;
    }

    /* http2 websocket handshake is successful  */
    AcceptorHandler acceptorHandler = webSocketHandlers.get(path);
    /*no handlers for path*/
    if (acceptorHandler == null) {
      Http2WebSocketHandshakeEvent.fireStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          WebSocketHandshakeException.class.getName(),
          String.format(Http2WebSocketMessages.HANDSHAKE_PATH_NOT_FOUND, path, subprotocols));

      writeHeaders(ctx, streamId, HEADERS_NOT_FOUND, true);
      return false;
    }

    AcceptorHandler handler = acceptorHandler.subprotocolHandler(subprotocols);
    /*no handler for subprotocol - return 404*/
    if (handler == null) {
      Http2WebSocketHandshakeEvent.fireStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          WebSocketHandshakeException.class.getName(),
          String.format(Http2WebSocketMessages.HANDSHAKE_PATH_NOT_FOUND, path, subprotocols));

      writeHeaders(ctx, streamId, HEADERS_NOT_FOUND, true);
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
      Http2WebSocketHandshakeEvent.fireStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          WebSocketHandshakeException.class.getName(),
          Http2WebSocketMessages.HANDSHAKE_UNSUPPORTED_ACCEPTOR_TYPE);

      writeHeaders(ctx, streamId, HEADERS_INTERNAL_ERROR, true);
      return false;
    }

    /*rejected request*/
    if (!accepted.isSuccess()) {
      Http2WebSocketHandshakeEvent.fireStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          accepted.cause());

      writeHeaders(ctx, streamId, HEADERS_REJECTED_REQUEST, true);
      return false;
    }

    WebSocketExtensionEncoder finalCompressionEncoder = compressionEncoder;
    WebSocketExtensionDecoder finalCompressionDecoder = compressionDecoder;
    Http2Headers successHeaders = successHeaders(responseHeaders);
    writeHeaders(ctx, streamId, successHeaders, false)
        .addListener(
            future -> {
              if (future.isSuccess()) {
                /* synchronous acceptor, no need for timeout - just register webSocket*/
                ChannelHandler webSocketHandler = acceptorHandler.handler();
                Http2WebSocketChannel webSocket =
                    new Http2WebSocketChannel(
                            webSocketsParent,
                            webSocketChannelSerial++,
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
                  Http2WebSocketHandshakeEvent.fireStartAndError(
                      ctx.channel(),
                      streamId,
                      path,
                      subprotocols,
                      requestHeaders,
                      startNanos,
                      System.nanoTime(),
                      registered.cause());
                  writeRstStream(streamId);
                  webSocket.streamClosed();
                  return;
                }

                /*websocket channel closed synchronously*/
                if (webSocket.closeFuture().isDone()) {
                  Http2WebSocketHandshakeEvent.fireStartAndError(
                      ctx.channel(),
                      streamId,
                      path,
                      subprotocols,
                      requestHeaders,
                      startNanos,
                      System.nanoTime(),
                      ClosedChannelException.class.getName(),
                      "websocket channel closed immediately after eventloop registration");
                  writeRstStream(streamId);
                  return;
                }
                webSocketsParent.register(streamId, webSocket);

                Http2WebSocketHandshakeEvent.fireStartAndSuccess(
                    webSocket,
                    streamId,
                    path,
                    subprotocols,
                    requestHeaders,
                    successHeaders,
                    startNanos,
                    System.nanoTime());
                return;
              }
              Http2WebSocketHandshakeEvent.fireStartAndError(
                  ctx.channel(),
                  streamId,
                  path,
                  subprotocols,
                  requestHeaders,
                  startNanos,
                  System.nanoTime(),
                  future.cause());
            });
    return false;
  }

  private ChannelFuture writeHeaders(
      ChannelHandlerContext ctx, int streamId, Http2Headers headers, boolean endStream) {
    ChannelFuture channelFuture = webSocketsParent.writeHeaders(streamId, headers, endStream);
    ctx.flush();
    return channelFuture;
  }

  private void writeRstStream(int streamId) {
    webSocketsParent.writeRstStream(streamId, Http2Error.PROTOCOL_ERROR.code());
  }

  static Http2Headers handshakeOnlyWebSocket(Http2Headers headers) {
    headers.remove(Http2WebSocketProtocol.HEADER_PROTOCOL_NAME);
    headers.method(Http2WebSocketProtocol.HEADER_METHOD_CONNECT_HANDSHAKED);
    return headers.set(
        Http2WebSocketProtocol.HEADER_PROTOCOL_NAME_HANDSHAKED,
        Http2WebSocketProtocol.HEADER_PROTOCOL_VALUE);
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

  private static boolean isEmpty(CharSequence seq) {
    return seq == null || seq.length() == 0;
  }

  private static boolean isHttp(CharSequence scheme) {
    return Http2WebSocketProtocol.SCHEME_HTTPS.equals(scheme)
        || Http2WebSocketProtocol.SCHEME_HTTP.equals(scheme);
  }
}
