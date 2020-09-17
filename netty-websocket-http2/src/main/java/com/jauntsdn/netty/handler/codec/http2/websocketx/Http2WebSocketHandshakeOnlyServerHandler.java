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

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.concurrent.GenericFutureListener;
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
public final class Http2WebSocketHandshakeOnlyServerHandler extends Http2WebSocketHandler
    implements GenericFutureListener<ChannelFuture> {
  private static final Logger logger =
      LoggerFactory.getLogger(Http2WebSocketHandshakeOnlyServerHandler.class);

  Http2WebSocketHandshakeOnlyServerHandler() {}

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      int streamId,
      Http2Headers headers,
      int padding,
      boolean endOfStream)
      throws Http2Exception {
    if (handshake(headers, endOfStream)) {
      super.onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    } else {
      reject(ctx, streamId, headers, endOfStream);
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
    if (handshake(headers, endOfStream)) {
      super.onHeadersRead(
          ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
    } else {
      reject(ctx, streamId, headers, endOfStream);
    }
  }

  /*RST_STREAM frame write*/
  @Override
  public void operationComplete(ChannelFuture future) {
    Throwable cause = future.cause();
    if (cause != null) {
      Http2WebSocketEvent.fireFrameWriteError(future.channel(), cause);
    }
  }

  private boolean handshake(Http2Headers headers, boolean endOfStream) {
    if (Http2WebSocketProtocol.isExtendedConnect(headers)) {
      boolean isValid = Http2WebSocketValidator.WebSocket.isValid(headers, endOfStream);
      if (isValid) {
        Http2WebSocketServerHandshaker.handshakeOnlyWebSocket(headers);
      }
      return isValid;
    }
    return Http2WebSocketValidator.Http.isValid(headers, endOfStream);
  }

  private void reject(
      ChannelHandlerContext ctx, int streamId, Http2Headers headers, boolean endOfStream) {
    Http2WebSocketEvent.fireHandshakeValidationStartAndError(
        ctx.channel(), streamId, headers.set(endOfStreamName(), endOfStreamValue(endOfStream)));
    http2Handler
        .encoder()
        .writeRstStream(ctx, streamId, Http2Error.PROTOCOL_ERROR.code(), ctx.newPromise())
        .addListener(this);
    ctx.flush();
  }
}
