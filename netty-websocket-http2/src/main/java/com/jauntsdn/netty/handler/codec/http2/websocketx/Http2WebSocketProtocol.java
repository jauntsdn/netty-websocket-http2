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

import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

final class Http2WebSocketProtocol {
  static final char SETTINGS_ENABLE_CONNECT_PROTOCOL = 8;
  static final AsciiString HEADER_METHOD_CONNECT = AsciiString.of("CONNECT");
  static final AsciiString HEADER_PROTOCOL_NAME = AsciiString.of(":protocol");
  static final AsciiString HEADER_PROTOCOL_VALUE = AsciiString.of("websocket");
  static final AsciiString SCHEME_HTTP = AsciiString.of("http");
  static final AsciiString SCHEME_HTTPS = AsciiString.of("https");

  static final AsciiString HEADER_PROTOCOL_NAME_HANDSHAKED = AsciiString.of("x-protocol");
  static final AsciiString HEADER_METHOD_CONNECT_HANDSHAKED = AsciiString.of("POST");
  static final AsciiString HEADER_WEBSOCKET_VERSION_NAME = AsciiString.of("sec-websocket-version");
  static final AsciiString HEADER_WEBSOCKET_VERSION_VALUE = AsciiString.of("13");
  static final AsciiString HEADER_WEBSOCKET_SUBPROTOCOL_NAME =
      AsciiString.of("sec-websocket-protocol");
  static final AsciiString HEADER_WEBSOCKET_EXTENSIONS_NAME =
      AsciiString.of("sec-websocket-extensions");

  static Http2Headers extendedConnect() {
    return new DefaultHttp2Headers()
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
