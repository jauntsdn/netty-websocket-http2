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
import java.util.Map;
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
}
