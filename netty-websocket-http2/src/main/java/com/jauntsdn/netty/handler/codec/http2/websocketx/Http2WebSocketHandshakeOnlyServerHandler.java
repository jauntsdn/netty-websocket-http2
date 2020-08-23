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

public class Http2WebSocketHandshakeOnlyServerHandler extends Http2WebSocketHandler {

  Http2WebSocketHandshakeOnlyServerHandler() {}

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
        Http2WebSocketServerHandshaker.handshakedWebSocket(headers);
        super.onHeadersRead(ctx, streamId, headers, padding, endOfStream);
      } else {
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
        Http2WebSocketServerHandshaker.handshakedWebSocket(headers);
        super.onHeadersRead(
            ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
      } else {
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
  }
}
