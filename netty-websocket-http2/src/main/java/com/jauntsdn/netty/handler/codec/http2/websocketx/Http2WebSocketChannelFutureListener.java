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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.GenericFutureListener;

/**
 * ChannelFuture listener that gracefully closes websocket by sending empty DATA frame with
 * END_STREAM flag set.
 */
public final class Http2WebSocketChannelFutureListener
    implements GenericFutureListener<ChannelFuture> {
  public static final Http2WebSocketChannelFutureListener CLOSE =
      new Http2WebSocketChannelFutureListener();

  private Http2WebSocketChannelFutureListener() {}

  @Override
  public void operationComplete(ChannelFuture future) {
    Channel channel = future.channel();
    Throwable cause = future.cause();
    if (cause != null) {
      Http2WebSocketEvent.fireFrameWriteError(channel, cause);
    }
    channel
        .pipeline()
        .fireUserEventTriggered(Http2WebSocketEvent.Http2WebSocketLocalCloseEvent.INSTANCE);
  }
}
