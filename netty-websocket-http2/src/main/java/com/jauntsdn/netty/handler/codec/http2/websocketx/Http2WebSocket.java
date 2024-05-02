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
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;

interface Http2WebSocket extends Http2FrameListener {

  /*get rid of checked throws Http2Exception */
  @Override
  void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData);

  void trySetWritable();

  void fireExceptionCaught(Throwable t);

  void streamClosed();

  void closeForcibly();

  Http2WebSocket CLOSED =
      new Http2WebSocket() {
        @Override
        public void onGoAwayRead(
            ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {}

        @Override
        public void onWindowUpdateRead(
            ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {}

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
            ChannelHandlerContext ctx,
            int streamId,
            ByteBuf data,
            int padding,
            boolean endOfStream) {
          int processed = data.readableBytes() + padding;
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

        @Override
        public void onHeadersRead(
            ChannelHandlerContext ctx,
            int streamId,
            Http2Headers headers,
            int padding,
            boolean endOfStream) {}

        @Override
        public void onHeadersRead(
            ChannelHandlerContext ctx,
            int streamId,
            Http2Headers headers,
            int streamDependency,
            short weight,
            boolean exclusive,
            int padding,
            boolean endOfStream) {}

        @Override
        public void onPriorityRead(
            ChannelHandlerContext ctx,
            int streamId,
            int streamDependency,
            short weight,
            boolean exclusive) {}

        @Override
        public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {}

        @Override
        public void onSettingsAckRead(ChannelHandlerContext ctx) {}

        @Override
        public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {}

        @Override
        public void onPingRead(ChannelHandlerContext ctx, long data) {}

        @Override
        public void onPingAckRead(ChannelHandlerContext ctx, long data) {}

        @Override
        public void onPushPromiseRead(
            ChannelHandlerContext ctx,
            int streamId,
            int promisedStreamId,
            Http2Headers headers,
            int padding) {}
      };
}
