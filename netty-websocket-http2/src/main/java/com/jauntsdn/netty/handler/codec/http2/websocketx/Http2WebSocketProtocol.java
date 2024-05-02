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
import io.netty.util.AsciiString;

final class Http2WebSocketProtocol {
  static final char SETTINGS_ENABLE_CONNECT_PROTOCOL = 8;
  static final AsciiString HEADER_METHOD_CONNECT = AsciiString.of("CONNECT");
  static final AsciiString HEADER_PROTOCOL_NAME = AsciiString.of(":protocol");
  static final AsciiString HEADER_PROTOCOL_VALUE = AsciiString.of("websocket");
  static final AsciiString SCHEME_HTTP = AsciiString.of("http");
  static final AsciiString SCHEME_HTTPS = AsciiString.of("https");

  static final AsciiString HEADER_WEBSOCKET_VERSION_NAME = AsciiString.of("sec-websocket-version");
  static final AsciiString HEADER_WEBSOCKET_VERSION_VALUE = AsciiString.of("13");
  static final AsciiString HEADER_WEBSOCKET_SUBPROTOCOL_NAME =
      AsciiString.of("sec-websocket-protocol");
  static final AsciiString HEADER_WEBSOCKET_EXTENSIONS_NAME =
      AsciiString.of("sec-websocket-extensions");

  static final AsciiString HEADER_PROTOCOL_NAME_HANDSHAKED = AsciiString.of("x-protocol");
  static final AsciiString HEADER_METHOD_CONNECT_HANDSHAKED = AsciiString.of("POST");

  /*messages*/
  static final String MSG_HANDSHAKE_UNEXPECTED_RESULT =
      "websocket handshake error: unexpected result - status=200, end_of_stream=true";
  static final String MSG_HANDSHAKE_UNSUPPORTED_VERSION =
      "websocket handshake error: unsupported version; supported versions - ";
  static final String MSG_HANDSHAKE_BAD_REQUEST = "websocket handshake error: bad request";
  static final String MSG_HANDSHAKE_PATH_NOT_FOUND = "websocket handshake error: path not found - ";
  static final String MSG_HANDSHAKE_PATH_NOT_FOUND_SUBPROTOCOLS = ", subprotocols - ";
  static final String MSG_HANDSHAKE_UNEXPECTED_SUBPROTOCOL =
      "websocket handshake error: unexpected subprotocol - ";
  static final String MSG_HANDSHAKE_GENERIC_ERROR = "websocket handshake error: ";
  static final String MSG_HANDSHAKE_UNSUPPORTED_ACCEPTOR_TYPE =
      "websocket handshake error: async acceptors are not supported";
  static final String MSG_HANDSHAKE_UNSUPPORTED_BOOTSTRAP =
      "websocket handshake error: bootstrapping websockets with http2 is not supported by server";
  static final String MSG_HANDSHAKE_INVALID_REQUEST_HEADERS =
      "websocket handshake error: invalid request headers";
  static final String MSG_HANDSHAKE_INVALID_RESPONSE_HEADERS =
      "websocket handshake error: invalid response headers";
  static final String MSG_WRITE_ERROR = "websocket frame write error";

  static Http2Headers extendedConnect(Http2Headers headers) {
    return headers
        .method(Http2WebSocketProtocol.HEADER_METHOD_CONNECT)
        .set(
            Http2WebSocketProtocol.HEADER_PROTOCOL_NAME,
            Http2WebSocketProtocol.HEADER_PROTOCOL_VALUE);
  }

  static boolean isExtendedConnect(Http2Headers headers) {
    return HEADER_METHOD_CONNECT.equals(headers.method())
        && HEADER_PROTOCOL_VALUE.equals(headers.get(HEADER_PROTOCOL_NAME));
  }
}
