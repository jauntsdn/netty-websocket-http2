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
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class Http2WebSocketServerHandshake {
  private final Future<Void> channelClose;
  private final ChannelPromise handshake;
  private final long timeoutMillis;
  private boolean done;
  private ScheduledFuture<?> timeoutFuture;
  private Future<?> handshakeCompleteFuture;
  private GenericFutureListener<Future<? super Void>> channelCloseListener;

  public Http2WebSocketServerHandshake(
      Future<Void> channelClose, ChannelPromise handshake, long timeoutMillis) {
    this.channelClose = channelClose;
    this.handshake = handshake;
    this.timeoutMillis = timeoutMillis;
  }

  public void startTimeout() {
    Channel channel = handshake.channel();

    if (done) {
      return;
    }
    channelCloseListener = future -> onConnectionClose();
    channelClose.addListener(channelCloseListener);
    /*account for possible synchronous callback execution*/
    if (done) {
      return;
    }
    handshakeCompleteFuture = handshake.addListener(future -> onHandshakeComplete(future.cause()));
    if (done) {
      return;
    }
    timeoutFuture =
        channel.eventLoop().schedule(this::onTimeout, timeoutMillis, TimeUnit.MILLISECONDS);
  }

  public void complete(Throwable e) {
    onHandshakeComplete(e);
  }

  public boolean isDone() {
    return done;
  }

  public ChannelFuture future() {
    return handshake;
  }

  private void onConnectionClose() {
    if (!done) {
      handshake.tryFailure(new ClosedChannelException());
      done();
    }
  }

  private void onHandshakeComplete(Throwable cause) {
    if (!done) {
      if (cause != null) {
        handshake.tryFailure(cause);
      } else {
        handshake.trySuccess();
      }
      done();
    }
  }

  private void onTimeout() {
    if (!done) {
      handshake.tryFailure(new TimeoutException());
      done();
    }
  }

  private void done() {
    done = true;
    GenericFutureListener<Future<? super Void>> closeListener = channelCloseListener;
    if (closeListener != null) {
      channelClose.removeListener(closeListener);
    }
    cancel(handshakeCompleteFuture);
    cancel(timeoutFuture);
  }

  private void cancel(Future<?> future) {
    if (future != null) {
      future.cancel(true);
    }
  }
}
