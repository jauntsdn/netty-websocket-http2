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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.concurrent.Future;
import java.util.List;
import java.util.Objects;

/** Accepts valid websocket-over-http2 request, optionally modifies request and response headers */
public interface Http2WebSocketAcceptor {

  /**
   * @param ctx ChannelHandlerContext of connection channel. Intended for creating acceptor result
   *     with context.executor().newFailedFuture(Throwable),
   *     context.executor().newSucceededFuture(ChannelHandler)
   * @param path websocket path
   * @param subprotocols requested websocket subprotocols. Accepted subprotocol must be set on
   *     response headers, e.g. with {@link Subprotocol#accept(String, Http2Headers)}
   * @param request request headers
   * @param response response headers
   * @return Succeeded future for accepted request, failed for rejected request. It is an error to
   *     return non completed future
   */
  Future<ChannelHandler> accept(
      ChannelHandlerContext ctx,
      String path,
      List<String> subprotocols,
      Http2Headers request,
      Http2Headers response);

  final class Subprotocol {
    private Subprotocol() {}

    public static void accept(String subprotocol, Http2Headers response) {
      Objects.requireNonNull(subprotocol, "subprotocol");
      if (subprotocol.isEmpty()) {
        return;
      }
      response.set(Http2WebSocketProtocol.HEADER_WEBSOCKET_SUBPROTOCOL_NAME, subprotocol);
    }
  }
}
