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

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.ssl.SslHandler;
import javax.annotation.Nullable;

/**
 * Provides server-side support for websocket-over-http2. Creates sub channel for http2 stream of
 * successfully handshaked websocket. Subchannel is compatible with http1 websocket handlers.
 */
public final class Http2WebSocketServerHandler extends Http2WebSocketChannelHandler {
  private final PerMessageDeflateServerExtensionHandshaker compressionHandshaker;
  private final Http2WebSocketAcceptor http2WebSocketAcceptor;

  private Http2WebSocketServerHandshaker handshaker;

  Http2WebSocketServerHandler(
      Http1WebSocketCodec webSocketCodec,
      WebSocketDecoderConfig webSocketDecoderConfig,
      boolean isEncoderMaskPayload,
      boolean nomaskingExtension,
      long closedWebSocketRemoveTimeoutMillis,
      @Nullable PerMessageDeflateServerExtensionHandshaker compressionHandshaker,
      Http2WebSocketAcceptor http2WebSocketAcceptor,
      boolean isSingleWebSocketPerConnection) {
    super(
        webSocketCodec,
        webSocketDecoderConfig,
        isEncoderMaskPayload,
        nomaskingExtension,
        closedWebSocketRemoveTimeoutMillis,
        isSingleWebSocketPerConnection);
    this.compressionHandshaker = compressionHandshaker;
    this.http2WebSocketAcceptor = http2WebSocketAcceptor;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);
    boolean nomaskingExtension =
        isNomaskingExtension && ctx.pipeline().get(SslHandler.class) != null;
    this.handshaker =
        new Http2WebSocketServerHandshaker(
            webSocketsParent,
            config,
            isEncoderMaskPayload,
            nomaskingExtension,
            http2WebSocketAcceptor,
            webSocketCodec,
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
      if (!Http2WebSocketProtocol.Validator.WebSocket.isValid(headers, endOfStream)) {
        handshaker.reject(streamId, headers, endOfStream);
      } else {
        handshaker.handshake(streamId, headers, endOfStream);
      }
      return false;
    }
    if (!Http2WebSocketProtocol.Validator.Http.isValid(headers, endOfStream)) {
      handshaker.reject(streamId, headers, endOfStream);
      return false;
    }
    return true;
  }
}
