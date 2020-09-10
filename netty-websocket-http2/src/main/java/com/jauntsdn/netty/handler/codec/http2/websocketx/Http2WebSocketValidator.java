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

class Http2WebSocketValidator {
  static final AsciiString HEADER_WEBSOCKET_ENDOFSTREAM_NAME =
      AsciiString.of("x-websocket-endofstream");
  static final AsciiString HEADER_WEBSOCKET_ENDOFSTREAM_VALUE_TRUE = AsciiString.of("true");
  static final AsciiString HEADER_WEBSOCKET_ENDOFSTREAM_VALUE_FALSE = AsciiString.of("false");

  static AsciiString endOfStreamName() {
    return HEADER_WEBSOCKET_ENDOFSTREAM_NAME;
  }

  static AsciiString endOfStreamValue(boolean endOfStream) {
    return endOfStream
        ? HEADER_WEBSOCKET_ENDOFSTREAM_VALUE_TRUE
        : HEADER_WEBSOCKET_ENDOFSTREAM_VALUE_FALSE;
  }

  static boolean isValidWebSocket(final Http2Headers requestHeaders, boolean endOfStream) {
    if (endOfStream) {
      return false;
    }
    CharSequence pathSeq = requestHeaders.path();
    if (isEmpty(pathSeq)) {
      return false;
    }
    CharSequence authority = requestHeaders.authority();
    if (isEmpty(authority)) {
      return false;
    }
    CharSequence scheme = requestHeaders.scheme();
    if (isEmpty(scheme) || !isHttp(scheme)) {
      return false;
    }
    return true;
  }

  private static boolean isEmpty(CharSequence seq) {
    return seq == null || seq.length() == 0;
  }

  private static boolean isHttp(CharSequence scheme) {
    return Http2WebSocketProtocol.SCHEME_HTTPS.equals(scheme)
        || Http2WebSocketProtocol.SCHEME_HTTP.equals(scheme);
  }
}
