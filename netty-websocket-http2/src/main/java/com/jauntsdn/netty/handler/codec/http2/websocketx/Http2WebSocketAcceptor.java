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
import io.netty.handler.codec.http2.Http2Headers;

/**
 * Accepts valid websocket-over-http2 request based on request headers; optionally modifies request
 * and response headers
 */
public interface Http2WebSocketAcceptor {

  /**
   * @param context ChannelHandlerContext of connection
   * @param request request headers
   * @param response response headers, writeable if {@link #writesResponseHeaders()} returns true
   * @return Succeeded future for accepted request, failed for rejected request. It is an error to
   *     return non completed future
   */
  ChannelFuture accept(ChannelHandlerContext context, Http2Headers request, Http2Headers response);

  /** @return true if acceptor is going to modify response headers, false otherwise. */
  default boolean writesResponseHeaders() {
    return true;
  }

  /** Default {@link Http2WebSocketAcceptor} implementation that accepts all requests */
  Http2WebSocketAcceptor ACCEPT_ALL =
      new Http2WebSocketAcceptor() {

        @Override
        public ChannelFuture accept(
            ChannelHandlerContext context, Http2Headers request, Http2Headers response) {
          return context.newSucceededFuture();
        }

        @Override
        public boolean writesResponseHeaders() {
          return false;
        }
      };
}
