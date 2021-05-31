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

final class Http2WebSocketMessages {
  static final String HANDSHAKE_UNEXPECTED_RESULT =
      "websocket handshake error: unexpected result - status=200, end_of_stream=true";
  static final String HANDSHAKE_UNSUPPORTED_VERSION =
      "websocket handshake error: unsupported version; supported versions - ";
  static final String HANDSHAKE_BAD_REQUEST = "websocket handshake error: bad request";
  static final String HANDSHAKE_PATH_NOT_FOUND = "websocket handshake error: path not found - ";
  static final String HANDSHAKE_PATH_NOT_FOUND_SUBPROTOCOLS = ", subprotocols - ";
  static final String HANDSHAKE_UNEXPECTED_SUBPROTOCOL =
      "websocket handshake error: unexpected subprotocol - ";
  static final String HANDSHAKE_GENERIC_ERROR = "websocket handshake error: ";
  static final String HANDSHAKE_UNSUPPORTED_ACCEPTOR_TYPE =
      "websocket handshake error: async acceptors are not supported";
  static final String HANDSHAKE_UNSUPPORTED_BOOTSTRAP =
      "websocket handshake error: bootstrapping websockets with http2 is not supported by server";
  static final String HANDSHAKE_INVALID_REQUEST_HEADERS =
      "websocket handshake error: invalid request headers";
  static final String HANDSHAKE_INVALID_RESPONSE_HEADERS =
      "websocket handshake error: invalid response headers";
  static final String WRITE_ERROR = "websocket frame write error";
}
