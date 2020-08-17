package com.jauntsdn.netty.handler.codec.http2.websocketx;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameListener;

interface Http2WebSocket extends Http2FrameListener {

  void trySetWritable();

  void fireExceptionCaught(Throwable t);

  void streamClosed();

  void closeForcibly();

  Http2WebSocket CLOSED = new Http2WebSocketClosedChannel();

  class Http2WebSocketClosedChannel extends Http2FrameAdapter implements Http2WebSocket {

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
