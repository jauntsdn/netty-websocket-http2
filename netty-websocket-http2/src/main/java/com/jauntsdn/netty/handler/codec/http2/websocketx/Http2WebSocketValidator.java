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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class Http2WebSocketValidator {
  static final AsciiString PSEUDO_HEADER_METHOD = AsciiString.of(":method");
  static final AsciiString PSEUDO_HEADER_SCHEME = AsciiString.of(":scheme");
  static final AsciiString PSEUDO_HEADER_AUTHORITY = AsciiString.of(":authority");
  static final AsciiString PSEUDO_HEADER_PATH = AsciiString.of(":path");
  static final AsciiString PSEUDO_HEADER_PROTOCOL = AsciiString.of(":protocol");
  static final AsciiString PSEUDO_HEADER_STATUS = AsciiString.of(":status");
  static final AsciiString PSEUDO_HEADER_METHOD_CONNECT = AsciiString.of("connect");

  static final AsciiString HEADER_CONNECTION = AsciiString.of("connection");
  static final AsciiString HEADER_KEEPALIVE = AsciiString.of("keep-alive");
  static final AsciiString HEADER_PROXY_CONNECTION = AsciiString.of("proxy-connection");
  static final AsciiString HEADER_TRANSFER_ENCODING = AsciiString.of("transfer-encoding");
  static final AsciiString HEADER_UPGRADE = AsciiString.of("upgrade");
  static final AsciiString HEADER_TE = AsciiString.of("te");
  static final AsciiString HEADER_TE_TRAILERS = AsciiString.of("trailers");

  static final Set<CharSequence> INVALID_HEADERS = invalidHeaders();

  public static boolean isValid(final Http2Headers responseHeaders) {
    boolean isFirst = true;
    for (Map.Entry<CharSequence, CharSequence> header : responseHeaders) {
      CharSequence name = header.getKey();
      if (isFirst) {
        if (!PSEUDO_HEADER_STATUS.equals(name) || isEmpty(header.getValue())) {
          return false;
        }
        isFirst = false;
      } else if (Http2Headers.PseudoHeaderName.hasPseudoHeaderFormat(name)) {
        return false;
      }
    }
    return containsValidHeaders(responseHeaders);
  }

  static boolean containsValidPseudoHeaders(
      Http2Headers requestHeaders, Set<CharSequence> validPseudoHeaders) {
    for (Map.Entry<CharSequence, CharSequence> header : requestHeaders) {
      CharSequence name = header.getKey();
      if (!Http2Headers.PseudoHeaderName.hasPseudoHeaderFormat(name)) {
        break;
      }
      if (!validPseudoHeaders.contains(name)) {
        return false;
      }
    }
    return true;
  }

  static boolean containsValidHeaders(Http2Headers headers) {
    for (CharSequence invalidHeader : INVALID_HEADERS) {
      if (headers.contains(invalidHeader)) {
        return false;
      }
    }
    CharSequence te = headers.get(HEADER_TE);
    return te == null || HEADER_TE_TRAILERS.equals(te);
  }

  static Set<CharSequence> validPseudoHeaders() {
    Set<CharSequence> result = new HashSet<>();
    result.add(PSEUDO_HEADER_SCHEME);
    result.add(PSEUDO_HEADER_AUTHORITY);
    result.add(PSEUDO_HEADER_PATH);
    result.add(PSEUDO_HEADER_METHOD);
    return result;
  }

  private static Set<CharSequence> invalidHeaders() {
    Set<CharSequence> result = new HashSet<>();
    result.add(HEADER_CONNECTION);
    result.add(HEADER_KEEPALIVE);
    result.add(HEADER_PROXY_CONNECTION);
    result.add(HEADER_TRANSFER_ENCODING);
    result.add(HEADER_UPGRADE);
    return result;
  }

  static boolean isEmpty(CharSequence seq) {
    return seq == null || seq.length() == 0;
  }

  static boolean isHttp(CharSequence scheme) {
    return Http2WebSocketProtocol.SCHEME_HTTPS.equals(scheme)
        || Http2WebSocketProtocol.SCHEME_HTTP.equals(scheme);
  }

  static class Http {
    private static final Set<CharSequence> VALID_PSEUDO_HEADERS = validPseudoHeaders();

    public static boolean isValid(final Http2Headers requestHeaders, boolean endOfStream) {
      AsciiString authority = AsciiString.of(requestHeaders.authority());
      /*must be non-empty, not include userinfo subcomponent*/
      if (isEmpty(authority) || authority.contains("@")) {
        return false;
      }

      AsciiString method = AsciiString.of(requestHeaders.method());
      if (isEmpty(method)) {
        return false;
      }
      AsciiString scheme = AsciiString.of(requestHeaders.scheme());
      AsciiString path = AsciiString.of(requestHeaders.path());
      if (method.equals(PSEUDO_HEADER_METHOD_CONNECT)) {
        if (!isEmpty(scheme) || !isEmpty(path)) {
          return false;
        }
      } else {
        if (isEmpty(scheme)) {
          return false;
        }
        /*must be non-empty for http/https requests*/
        if (isEmpty(path) && isHttp(scheme)) {
          return false;
        }
      }

      return containsValidPseudoHeaders(requestHeaders, VALID_PSEUDO_HEADERS)
          && containsValidHeaders(requestHeaders);
    }
  }

  static class WebSocket {
    private static final Set<CharSequence> VALID_PSEUDO_HEADERS;

    static {
      Set<CharSequence> headers = VALID_PSEUDO_HEADERS = validPseudoHeaders();
      headers.add(PSEUDO_HEADER_PROTOCOL);
    }

    public static boolean isValid(final Http2Headers requestHeaders, boolean endOfStream) {
      if (endOfStream) {
        return false;
      }

      if (!isHttp(requestHeaders.scheme())) {
        return false;
      }

      AsciiString authority = AsciiString.of(requestHeaders.authority());
      /*must be non-empty, not include userinfo subcomponent*/
      if (isEmpty(authority) || authority.contains("@")) {
        return false;
      }

      if (isEmpty(requestHeaders.path())) {
        return false;
      }
      /*:method is known to be "connect"*/

      return containsValidPseudoHeaders(requestHeaders, VALID_PSEUDO_HEADERS)
          && containsValidHeaders(requestHeaders);
    }
  }
}
