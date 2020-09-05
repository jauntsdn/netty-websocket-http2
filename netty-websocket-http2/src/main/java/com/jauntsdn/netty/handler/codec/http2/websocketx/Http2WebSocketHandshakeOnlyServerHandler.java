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
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides server-side support for websocket-over-http2. Verifies websocket-over-http2 request
 * validity. Invalid websocket requests are rejected by sending RST frame, valid websocket http2
 * stream frames are passed down the pipeline. Valid websocket stream request headers are modified
 * as follows: :method=POST, x-protocol=websocket. Intended for proxies/intermidiaries that do not
 * process websocket byte streams, but only route respective http2 streams - hence is not compatible
 * with http1 websocket handlers. http1 websocket handlers support is provided by complementary
 * {@link Http2WebSocketServerHandler}
 */
public final class Http2WebSocketHandshakeOnlyServerHandler extends Http2WebSocketHandler {
  private static final Logger logger =
      LoggerFactory.getLogger(Http2WebSocketHandshakeOnlyServerHandler.class);

  private final RejectedWebSocketListener rejectedWebSocketListener;

  Http2WebSocketHandshakeOnlyServerHandler(
      @Nullable RejectedWebSocketListener rejectedWebSocketListener) {
    this.rejectedWebSocketListener = rejectedWebSocketListener;
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      int streamId,
      Http2Headers headers,
      int padding,
      boolean endOfStream)
      throws Http2Exception {
    if (Http2WebSocketProtocol.isExtendedConnect(headers)) {
      if (Http2WebSocketServerHandshaker.handshakeProtocol(headers, endOfStream)) {
        Http2Headers handshakeOnlyWebSocket =
            Http2WebSocketServerHandshaker.handshakeOnlyWebSocket(headers);
        super.onHeadersRead(ctx, streamId, handshakeOnlyWebSocket, padding, endOfStream);
      } else {
        onWebSocketRejected(ctx, streamId, headers, endOfStream);
        writeRstStream(ctx, streamId, Http2Error.PROTOCOL_ERROR.code());
      }
    } else {
      super.onHeadersRead(ctx, streamId, headers, padding, endOfStream);
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
    if (Http2WebSocketProtocol.isExtendedConnect(headers)) {
      if (Http2WebSocketServerHandshaker.handshakeProtocol(headers, endOfStream)) {
        Http2Headers handshakeOnlyWebSocket =
            Http2WebSocketServerHandshaker.handshakeOnlyWebSocket(headers);
        super.onHeadersRead(
            ctx,
            streamId,
            handshakeOnlyWebSocket,
            streamDependency,
            weight,
            exclusive,
            padding,
            endOfStream);
      } else {
        onWebSocketRejected(ctx, streamId, headers, endOfStream);
        writeRstStream(ctx, streamId, Http2Error.PROTOCOL_ERROR.code());
      }
    } else {
      super.onHeadersRead(
          ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
    }
  }

  private void writeRstStream(ChannelHandlerContext ctx, int streamId, long errorCode) {
    ChannelPromise p = ctx.newPromise();
    http2Handler.encoder().writeRstStream(ctx, streamId, errorCode, p);
    ctx.flush();
  }

  private void onWebSocketRejected(
      ChannelHandlerContext ctx, int streamId, Http2Headers headers, boolean endOfStream) {
    RejectedWebSocketListener l = rejectedWebSocketListener;
    if (l != null) {
      try {
        l.onWebSocketRejected(ctx, streamId, headers, endOfStream);
      } catch (Exception e) {
        logger.error("Rejected http2 websocket listener error", e);
      }
    }
  }

  public interface RejectedWebSocketListener {

    void onWebSocketRejected(
        ChannelHandlerContext ctx, int streamId, Http2Headers headers, boolean endOfStream);
  }
}
