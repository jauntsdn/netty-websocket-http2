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

import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

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

  /*extensions*/
  static final String HEADER_WEBSOCKET_EXTENSIONS_VALUE_PERMESSAGE_DEFLATE = "permessage-deflate";
  static final AsciiString HEADER_WEBSOCKET_EXTENSIONS_VALUE_PERMESSAGE_DEFLATE_ASCII =
      AsciiString.of(HEADER_WEBSOCKET_EXTENSIONS_VALUE_PERMESSAGE_DEFLATE);
  static final Pattern HEADER_WEBSOCKET_EXTENSIONS_PARAMETER_PATTERN =
      Pattern.compile("^([^=]+)(=[\\\"]?([^\\\"]+)[\\\"]?)?$");

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

  /*extensions*/

  @Nullable
  static WebSocketExtensionData decodeExtensions(@Nullable CharSequence extensionHeader) {
    if (extensionHeader == null || extensionHeader.length() == 0) {
      return null;
    }
    AsciiString asciiExtensionHeader = (AsciiString) extensionHeader;

    for (AsciiString extension : asciiExtensionHeader.split(',')) {
      AsciiString[] extensionParameters = extension.split(';');
      AsciiString name = extensionParameters[0].trim();
      if (HEADER_WEBSOCKET_EXTENSIONS_VALUE_PERMESSAGE_DEFLATE_ASCII.equals(name)) {
        Map<String, String> parameters;
        if (extensionParameters.length > 1) {
          parameters = new HashMap<>(extensionParameters.length - 1);
          for (int i = 1; i < extensionParameters.length; i++) {
            AsciiString parameter = extensionParameters[i].trim();
            Matcher parameterMatcher =
                HEADER_WEBSOCKET_EXTENSIONS_PARAMETER_PATTERN.matcher(parameter);
            if (parameterMatcher.matches()) {
              String key = parameterMatcher.group(1);
              if (key != null) {
                String value = parameterMatcher.group(3);
                parameters.put(key, value);
              }
            }
          }
        } else {
          parameters = Collections.emptyMap();
        }
        return new WebSocketExtensionData(
            HEADER_WEBSOCKET_EXTENSIONS_VALUE_PERMESSAGE_DEFLATE, parameters);
      }
    }
    return null;
  }

  static String encodeExtensions(WebSocketExtensionData extensionData) {
    String name = extensionData.name();
    Map<String, String> params = extensionData.parameters();
    if (params.isEmpty()) {
      return name;
    }
    /*at most 4 parameters*/
    StringBuilder sb = new StringBuilder(sizeOf(name, params));
    sb.append(name);
    for (Map.Entry<String, String> param : params.entrySet()) {
      sb.append(";");
      sb.append(param.getKey());
      String value = param.getValue();
      if (value != null) {
        sb.append("=");
        sb.append(value);
      }
    }
    return sb.toString();
  }

  static int sizeOf(String extensionName, Map<String, String> extensionParameters) {
    int size = extensionName.length();
    for (Map.Entry<String, String> param : extensionParameters.entrySet()) {
      /* key and ; */
      size += param.getKey().length() + 1;
      String value = param.getValue();
      if (value != null) {
        /* value and = */ size += value.length() + 1;
      }
    }
    return size;
  }

  static final class Validator {
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
      return SCHEME_HTTPS.equals(scheme) || SCHEME_HTTP.equals(scheme);
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

  /*preconditions*/

  static long requirePositive(long value, String message) {
    if (value <= 0) {
      throw new IllegalArgumentException(message + " must be positive: " + value);
    }
    return value;
  }

  static short requireRange(int value, int from, int to, String message) {
    if (value >= from && value <= to) {
      return (short) value;
    }
    throw new IllegalArgumentException(
        String.format("%s must belong to range [%d, %d]: ", message, from, to));
  }
}
