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

import io.netty.handler.codec.http2.Http2Headers;

class Http2WebSocketClientHandshake extends Http2WebSocketServerHandshake {
  private final Http2WebSocketChannel webSocketChannel;
  private final Http2Headers requestHeaders;
  private final long handshakeStartNanos;

  public Http2WebSocketClientHandshake(
      Http2WebSocketChannel webSocketChannel,
      Http2Headers requestHeaders,
      long timeoutMillis,
      long handshakeStartNanos) {
    super(webSocketChannel.closeFuture(), webSocketChannel.handshakePromise(), timeoutMillis);
    this.webSocketChannel = webSocketChannel;
    this.requestHeaders = requestHeaders;
    this.handshakeStartNanos = handshakeStartNanos;
  }

  public Http2WebSocketChannel webSocketChannel() {
    return webSocketChannel;
  }

  public Http2Headers requestHeaders() {
    return requestHeaders;
  }

  public long startNanos() {
    return handshakeStartNanos;
  }
}
