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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameListener;

interface Http2WebSocket extends Http2FrameListener {

  /*get rid of checked throws Http2Exception */
  @Override
  void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData);

  void trySetWritable();

  void fireExceptionCaught(Throwable t);

  void streamClosed();

  void closeForcibly();

  Http2WebSocket CLOSED = new Http2WebSocketClosedChannel();

  class Http2WebSocketClosedChannel extends Http2FrameAdapter implements Http2WebSocket {

    @Override
    public void onGoAwayRead(
        ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {}

    @Override
    public void streamClosed() {}

    @Override
    public void trySetWritable() {}

    @Override
    public void fireExceptionCaught(Throwable t) {}

    @Override
    public void closeForcibly() {}

    @Override
    public int onDataRead(
        ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream)
        throws Http2Exception {
      int processed = super.onDataRead(ctx, streamId, data, padding, endOfStream);
      data.release();
      return processed;
    }

    @Override
    public void onUnknownFrame(
        ChannelHandlerContext ctx,
        byte frameType,
        int streamId,
        Http2Flags flags,
        ByteBuf payload) {
      payload.release();
    }
  }
}
