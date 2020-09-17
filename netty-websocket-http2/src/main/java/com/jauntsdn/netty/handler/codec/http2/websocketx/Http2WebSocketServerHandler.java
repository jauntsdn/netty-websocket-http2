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

import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import javax.annotation.Nullable;

/**
 * Provides server-side support for websocket-over-http2. Creates sub channel for http2 stream of
 * successfully handshaked websocket. Subchannel is compatible with http1 websocket handlers.
 */
public final class Http2WebSocketServerHandler extends Http2WebSocketChannelHandler {
  private final PerMessageDeflateServerExtensionHandshaker compressionHandshaker;
  private final WebSocketHandler.Container webSocketHandlers;

  private Http2WebSocketServerHandshaker handshaker;

  Http2WebSocketServerHandler(
      WebSocketDecoderConfig webSocketDecoderConfig,
      boolean isEncoderMaskPayload,
      long closedWebSocketRemoveTimeoutMillis,
      @Nullable PerMessageDeflateServerExtensionHandshaker compressionHandshaker,
      WebSocketHandler.Container webSocketHandlers,
      boolean isSingleWebSocketPerConnection) {
    super(
        webSocketDecoderConfig,
        isEncoderMaskPayload,
        closedWebSocketRemoveTimeoutMillis,
        isSingleWebSocketPerConnection);
    this.compressionHandshaker = compressionHandshaker;
    this.webSocketHandlers = webSocketHandlers;
  }

  public static Http2WebSocketServerBuilder builder() {
    return new Http2WebSocketServerBuilder();
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);
    this.handshaker =
        new Http2WebSocketServerHandshaker(
            webSocketsParent,
            config,
            isEncoderMaskPayload,
            webSocketHandlers,
            compressionHandshaker);
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      final int streamId,
      Http2Headers headers,
      int padding,
      boolean endOfStream)
      throws Http2Exception {
    boolean proceed = handshakeWebSocket(streamId, headers, endOfStream);
    if (proceed) {
      next().onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }
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
    boolean proceed = handshakeWebSocket(streamId, headers, endOfStream);
    if (proceed) {
      next()
          .onHeadersRead(
              ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
    }
  }

  private boolean handshakeWebSocket(int streamId, Http2Headers headers, boolean endOfStream) {
    if (Http2WebSocketProtocol.isExtendedConnect(headers)) {
      if (!Http2WebSocketValidator.WebSocket.isValid(headers, endOfStream)) {
        handshaker.reject(streamId, headers, endOfStream);
      } else {
        handshaker.handshake(streamId, headers, endOfStream);
      }
      return false;
    }
    if (!Http2WebSocketValidator.Http.isValid(headers, endOfStream)) {
      handshaker.reject(streamId, headers, endOfStream);
      return false;
    }
    return true;
  }

  interface WebSocketHandler {

    Http2WebSocketAcceptor acceptor();

    ChannelHandler handler();

    String subprotocol();

    final class Impl implements WebSocketHandler {
      private final Http2WebSocketAcceptor acceptor;
      private final ChannelHandler handler;
      private final String subprotocol;

      public Impl(Http2WebSocketAcceptor acceptor, ChannelHandler handler, String subprotocol) {
        this.acceptor = acceptor;
        this.handler = handler;
        this.subprotocol = subprotocol;
      }

      @Override
      public Http2WebSocketAcceptor acceptor() {
        return acceptor;
      }

      @Override
      public ChannelHandler handler() {
        return handler;
      }

      @Override
      public String subprotocol() {
        return subprotocol;
      }
    }

    interface Container {

      void put(
          String path, String subprotocol, Http2WebSocketAcceptor acceptor, ChannelHandler handler);

      WebSocketHandler get(String path, String subprotocol);

      WebSocketHandler get(String path, String[] subprotocols);
    }
  }
}
