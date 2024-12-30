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
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionDecoder;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionEncoder;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtensionHandshaker;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.ReadOnlyHttp2Headers;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

final class Http2WebSocketServerHandshaker implements GenericFutureListener<ChannelFuture> {
  private static final AsciiString HEADERS_STATUS_200 = AsciiString.of("200");
  private static final ReadOnlyHttp2Headers HEADERS_OK =
      ReadOnlyHttp2Headers.serverHeaders(false, HEADERS_STATUS_200);
  private static final ReadOnlyHttp2Headers HEADERS_UNSUPPORTED_VERSION =
      ReadOnlyHttp2Headers.serverHeaders(
          false,
          AsciiString.of("400"),
          AsciiString.of(Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_NAME),
          AsciiString.of(Http2WebSocketProtocol.HEADER_WEBSOCKET_VERSION_VALUE));
  private static final ReadOnlyHttp2Headers HEADERS_REJECTED =
      ReadOnlyHttp2Headers.serverHeaders(false, AsciiString.of("400"));
  private static final ReadOnlyHttp2Headers HEADERS_NOT_FOUND =
      ReadOnlyHttp2Headers.serverHeaders(false, AsciiString.of("404"));
  private static final ReadOnlyHttp2Headers HEADERS_INTERNAL_ERROR =
      ReadOnlyHttp2Headers.serverHeaders(false, AsciiString.of("500"));

  private final WebSocketsParent webSocketsParent;
  private final WebSocketDecoderConfig webSocketDecoderConfig;
  private final boolean isEncoderMaskPayload;
  private final boolean isNomaskingExtension;
  private final Http2WebSocketAcceptor http2WebSocketAcceptor;
  private final Http1WebSocketCodec webSocketCodec;
  private final WebSocketServerExtensionHandshaker compressionHandshaker;

  Http2WebSocketServerHandshaker(
      WebSocketsParent webSocketsParent,
      WebSocketDecoderConfig webSocketDecoderConfig,
      boolean isEncoderMaskPayload,
      boolean isNomaskingExtension,
      Http2WebSocketAcceptor http2WebSocketAcceptor,
      Http1WebSocketCodec webSocketCodec,
      @Nullable WebSocketServerExtensionHandshaker compressionHandshaker) {
    this.webSocketsParent = webSocketsParent;
    this.webSocketDecoderConfig = webSocketDecoderConfig;
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    this.isNomaskingExtension = isNomaskingExtension;
    this.http2WebSocketAcceptor = http2WebSocketAcceptor;
    this.webSocketCodec = webSocketCodec;
    this.compressionHandshaker = compressionHandshaker;
  }

  void reject(final int streamId, final Http2Headers requestHeaders, boolean endOfStream) {
    Http2WebSocketEvent.fireHandshakeValidationStartAndError(
        webSocketsParent.context().channel(),
        streamId,
        requestHeaders.set(endOfStreamName(), endOfStreamValue(endOfStream)));
    writeRstStream(streamId).addListener(this);
  }

  void handshake(final int streamId, final Http2Headers requestHeaders, boolean endOfStream) {
    long startNanos = System.nanoTime();
    ChannelHandlerContext ctx = webSocketsParent.context();

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
          Http2WebSocketProtocol.MSG_HANDSHAKE_UNSUPPORTED_VERSION + webSocketVersion);

      writeHeaders(ctx, streamId, HEADERS_UNSUPPORTED_VERSION, true).addListener(this);
      return;
    }

    List<String> requestedSubprotocols = parseSubprotocols(subprotocols);

    boolean encoderMaskPayload = isEncoderMaskPayload;
    WebSocketDecoderConfig decoderConfig = webSocketDecoderConfig;

    /*extensions*/
    WebSocketServerExtension compressionExtension = null;
    WebSocketExtensionEncoder compressionEncoder = null;
    WebSocketExtensionDecoder compressionDecoder = null;
    WebSocketServerExtensionHandshaker compressionHandshaker = this.compressionHandshaker;
    boolean supportsCompression = compressionHandshaker != null;
    boolean supportsNomasking = isNomaskingExtension;
    boolean acceptsNomasking = false;

    boolean supportsExtensions = supportsCompression || supportsNomasking;

    CharSequence extensionsHeader =
        supportsExtensions
            ? requestHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_NAME)
            : null;
    Http2WebSocketProtocol.WebSocketExtensions extensions =
        Http2WebSocketProtocol.decodeExtensions(extensionsHeader);

    if (extensions != null) {
      if (extensions.isNomasking() && supportsNomasking) {
        acceptsNomasking = true;
        encoderMaskPayload = Http2WebSocketProtocol.WEBSOCKET_EXTENSIONS_NOMASKING_MASK_PAYLOAD;
        decoderConfig = Http2WebSocketProtocol.nomaskingExtensionDecoderConfig(decoderConfig);
      }
      WebSocketExtensionData compression = extensions.compression();
      if (compression != null && supportsCompression) {
        compressionExtension = compressionHandshaker.handshakeExtension(compression);
        compressionEncoder = compressionExtension.newExtensionEncoder();
        compressionDecoder = compressionExtension.newExtensionDecoder();
      }
    }
    Http2Headers responseHeaders = new DefaultHttp2Headers();
    boolean acceptsCompression = compressionExtension != null;
    if (acceptsCompression) {
      responseHeaders.set(
          Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_NAME,
          Http2WebSocketProtocol.encodeExtensions(
              compressionExtension.newReponseData(), acceptsNomasking));
    } else if (acceptsNomasking) {
      responseHeaders.set(
          Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_NAME,
          Http2WebSocketProtocol.HEADER_WEBSOCKET_EXTENSIONS_VALUE_NOMASKING);
    }

    Future<ChannelHandler> acceptorResult;
    try {
      acceptorResult =
          http2WebSocketAcceptor.accept(
              ctx, path, requestedSubprotocols, requestHeaders, responseHeaders);
    } catch (Exception e) {
      acceptorResult = ctx.executor().newFailedFuture(e);
    }

    /*async acceptors are not yet supported*/
    if (!acceptorResult.isDone()) {
      acceptorResult.cancel(true);
      Http2WebSocketEvent.fireHandshakeStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          WebSocketHandshakeException.class.getName(),
          Http2WebSocketProtocol.MSG_HANDSHAKE_UNSUPPORTED_ACCEPTOR_TYPE);

      writeHeaders(ctx, streamId, HEADERS_INTERNAL_ERROR, true).addListener(this);
      return;
    }

    Throwable rejected = acceptorResult.cause();
    /*rejected request*/
    if (rejected != null) {
      Http2WebSocketEvent.fireHandshakeStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          rejected);

      Http2Headers response =
          rejected instanceof Http2WebSocketPathNotFoundException
              ? HEADERS_NOT_FOUND
              : HEADERS_REJECTED;

      writeHeaders(ctx, streamId, response, true).addListener(this);
      return;
    }

    CharSequence acceptedSubprotocolSeq =
        responseHeaders.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_SUBPROTOCOL_NAME);
    String acceptedSubprotocol = nonNullString(acceptedSubprotocolSeq);
    if (!isExpectedSubprotocol(acceptedSubprotocol, requestedSubprotocols)) {
      String subprotocolOrBlank = acceptedSubprotocol.isEmpty() ? "''" : acceptedSubprotocol;
      Http2WebSocketEvent.fireHandshakeStartAndError(
          ctx.channel(),
          streamId,
          path,
          subprotocols,
          requestHeaders,
          startNanos,
          System.nanoTime(),
          WebSocketHandshakeException.class.getName(),
          Http2WebSocketProtocol.MSG_HANDSHAKE_UNEXPECTED_SUBPROTOCOL + subprotocolOrBlank);

      writeHeaders(ctx, streamId, HEADERS_NOT_FOUND, true).addListener(this);
      return;
    }

    ChannelHandler webSocketHandler = acceptorResult.getNow();

    WebSocketExtensionEncoder finalCompressionEncoder = compressionEncoder;
    WebSocketExtensionDecoder finalCompressionDecoder = compressionDecoder;
    boolean finalEncoderMaskPayload = encoderMaskPayload;
    WebSocketDecoderConfig finalDecoderConfig = decoderConfig;

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
              Http2WebSocketChannel webSocket =
                  new Http2WebSocketChannel(
                          webSocketsParent,
                          streamId,
                          path,
                          acceptedSubprotocol,
                          finalDecoderConfig,
                          finalEncoderMaskPayload,
                          webSocketCodec,
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
                  acceptedSubprotocol,
                  requestHeaders,
                  successHeaders,
                  startNanos,
                  System.nanoTime());
            });
  }

  private boolean isExpectedSubprotocol(String subprotocol, List<String> requestedSubprotocols) {
    int requestedLength = requestedSubprotocols.size();
    if (subprotocol.isEmpty()) {
      return requestedLength == 0;
    }

    for (int i = 0; i < requestedLength; i++) {
      if (requestedSubprotocols.get(i).equals(subprotocol)) {
        return true;
      }
    }
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

  static List<String> parseSubprotocols(String subprotocols) {
    if (subprotocols.isEmpty()) {
      return Collections.emptyList();
    }
    if (subprotocols.indexOf(',') == -1) {
      return Collections.singletonList(subprotocols);
    }
    return Arrays.asList(subprotocols.split(","));
  }

  private static String nonNullString(@Nullable CharSequence seq) {
    if (seq == null) {
      return "";
    }
    return seq.toString();
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
}
