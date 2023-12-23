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
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.*;
import io.netty.util.AsciiString;

/** Base type for client and server websocket-over-http2 handlers */
public abstract class Http2WebSocketHandler extends ChannelDuplexHandler
    implements Http2FrameListener {
  static final AsciiString HEADER_WEBSOCKET_ENDOFSTREAM_NAME =
      AsciiString.of("x-websocket-endofstream");
  static final AsciiString HEADER_WEBSOCKET_ENDOFSTREAM_VALUE_TRUE = AsciiString.of("true");
  static final AsciiString HEADER_WEBSOCKET_ENDOFSTREAM_VALUE_FALSE = AsciiString.of("false");

  Http2ConnectionHandler http2Handler;
  HandlerListener handlerListener;

  Http2WebSocketHandler() {}

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    Http2ConnectionHandler http2Handler =
        this.http2Handler =
            Preconditions.requireHandler(ctx.channel(), Http2ConnectionHandler.class);
    HandlerListener listener = handlerListener;
    if (listener == null) {
      Http2ConnectionDecoder decoder = http2Handler.decoder();
      Http2FrameListener next = decoder.frameListener();
      listener = handlerListener = new HandlerListener().current(this).next(next);
      decoder.frameListener(listener);
    } else {
      listener.current(this);
    }
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    HandlerListener listener = handlerListener;
    if (listener != null) {
      listener.current(null);
    }
    super.handlerRemoved(ctx);
  }

  @Override
  public void onGoAwayRead(
      ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData)
      throws Http2Exception {
    next().onGoAwayRead(ctx, lastStreamId, errorCode, debugData);
  }

  @Override
  public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
      throws Http2Exception {
    next().onRstStreamRead(ctx, streamId, errorCode);
  }

  @Override
  public int onDataRead(
      ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream)
      throws Http2Exception {
    return next().onDataRead(ctx, streamId, data.retain(), padding, endOfStream);
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      int streamId,
      Http2Headers headers,
      int padding,
      boolean endOfStream)
      throws Http2Exception {
    next().onHeadersRead(ctx, streamId, headers, padding, endOfStream);
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
    next()
        .onHeadersRead(
            ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
  }

  @Override
  public void onPriorityRead(
      ChannelHandlerContext ctx,
      int streamId,
      int streamDependency,
      short weight,
      boolean exclusive)
      throws Http2Exception {
    next().onPriorityRead(ctx, streamId, streamDependency, weight, exclusive);
  }

  @Override
  public void onSettingsAckRead(ChannelHandlerContext ctx) throws Http2Exception {
    next().onSettingsAckRead(ctx);
  }

  @Override
  public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings)
      throws Http2Exception {
    next().onSettingsRead(ctx, settings);
  }

  @Override
  public void onPingRead(ChannelHandlerContext ctx, long data) throws Http2Exception {
    next().onPingRead(ctx, data);
  }

  @Override
  public void onPingAckRead(ChannelHandlerContext ctx, long data) throws Http2Exception {
    next().onPingAckRead(ctx, data);
  }

  @Override
  public void onPushPromiseRead(
      ChannelHandlerContext ctx,
      int streamId,
      int promisedStreamId,
      Http2Headers headers,
      int padding)
      throws Http2Exception {
    next().onPushPromiseRead(ctx, streamId, promisedStreamId, headers, padding);
  }

  @Override
  public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement)
      throws Http2Exception {
    next().onWindowUpdateRead(ctx, streamId, windowSizeIncrement);
  }

  @Override
  public void onUnknownFrame(
      ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags, ByteBuf payload)
      throws Http2Exception {
    next().onUnknownFrame(ctx, frameType, streamId, flags, payload);
  }

  final Http2FrameListener next() {
    return handlerListener.next;
  }

  static AsciiString endOfStreamName() {
    return HEADER_WEBSOCKET_ENDOFSTREAM_NAME;
  }

  static AsciiString endOfStreamValue(boolean endOfStream) {
    return endOfStream
        ? HEADER_WEBSOCKET_ENDOFSTREAM_VALUE_TRUE
        : HEADER_WEBSOCKET_ENDOFSTREAM_VALUE_FALSE;
  }

  static final class HandlerListener implements Http2FrameListener {
    Http2FrameListener cur;
    Http2FrameListener next;

    public HandlerListener current(Http2FrameListener cur) {
      this.cur = cur;
      return this;
    }

    public HandlerListener next(Http2FrameListener next) {
      this.next = next;
      return this;
    }

    Http2FrameListener next() {
      return next;
    }

    private Http2FrameListener listener() {
      Http2FrameListener c = cur;
      if (c != null) {
        return c;
      }
      return next;
    }

    @Override
    public int onDataRead(
        ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream)
        throws Http2Exception {
      return listener().onDataRead(ctx, streamId, data, padding, endOfStream);
    }

    @Override
    public void onHeadersRead(
        ChannelHandlerContext ctx,
        int streamId,
        Http2Headers headers,
        int padding,
        boolean endOfStream)
        throws Http2Exception {
      listener().onHeadersRead(ctx, streamId, headers, padding, endOfStream);
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
      listener()
          .onHeadersRead(
              ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
    }

    @Override
    public void onPriorityRead(
        ChannelHandlerContext ctx,
        int streamId,
        int streamDependency,
        short weight,
        boolean exclusive)
        throws Http2Exception {
      listener().onPriorityRead(ctx, streamId, streamDependency, weight, exclusive);
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
        throws Http2Exception {
      listener().onRstStreamRead(ctx, streamId, errorCode);
    }

    @Override
    public void onSettingsAckRead(ChannelHandlerContext ctx) throws Http2Exception {
      listener().onSettingsAckRead(ctx);
    }

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings)
        throws Http2Exception {
      listener().onSettingsRead(ctx, settings);
    }

    @Override
    public void onPingRead(ChannelHandlerContext ctx, long data) throws Http2Exception {
      listener().onPingRead(ctx, data);
    }

    @Override
    public void onPingAckRead(ChannelHandlerContext ctx, long data) throws Http2Exception {
      listener().onPingAckRead(ctx, data);
    }

    @Override
    public void onPushPromiseRead(
        ChannelHandlerContext ctx,
        int streamId,
        int promisedStreamId,
        Http2Headers headers,
        int padding)
        throws Http2Exception {
      listener().onPushPromiseRead(ctx, streamId, promisedStreamId, headers, padding);
    }

    @Override
    public void onGoAwayRead(
        ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData)
        throws Http2Exception {
      listener().onGoAwayRead(ctx, lastStreamId, errorCode, debugData);
    }

    @Override
    public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement)
        throws Http2Exception {
      listener().onWindowUpdateRead(ctx, streamId, windowSizeIncrement);
    }

    @Override
    public void onUnknownFrame(
        ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags, ByteBuf payload)
        throws Http2Exception {
      listener().onUnknownFrame(ctx, frameType, streamId, flags, payload);
    }
  }
}
