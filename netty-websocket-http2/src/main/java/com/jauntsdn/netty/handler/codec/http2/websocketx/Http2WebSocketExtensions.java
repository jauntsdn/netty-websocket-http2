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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

class Http2WebSocketExtensions {
  static final String HEADER_WEBSOCKET_EXTENSIONS_VALUE_PERMESSAGE_DEFLATE = "permessage-deflate";
  static final Pattern HEADER_WEBSOCKET_EXTENSIONS_PARAMETER_PATTERN =
      Pattern.compile("^([^=]+)(=[\\\"]?([^\\\"]+)[\\\"]?)?$");

  @Nullable
  static WebSocketExtensionData decode(@Nullable CharSequence extensionHeader) {
    if (extensionHeader == null || extensionHeader.length() == 0) {
      return null;
    }
    for (String extension : extensionHeader.toString().split(",")) {
      String[] extensionParameters = extension.split(";");
      String name = extensionParameters[0].trim();
      if (HEADER_WEBSOCKET_EXTENSIONS_VALUE_PERMESSAGE_DEFLATE.equals(name)) {
        Map<String, String> parameters;
        if (extensionParameters.length > 1) {
          parameters = new HashMap<>(extensionParameters.length - 1);
          for (int i = 1; i < extensionParameters.length; i++) {
            String parameter = extensionParameters[i].trim();
            Matcher parameterMatcher =
                HEADER_WEBSOCKET_EXTENSIONS_PARAMETER_PATTERN.matcher(parameter);
            if (parameterMatcher.matches() && parameterMatcher.group(1) != null) {
              parameters.put(parameterMatcher.group(1), parameterMatcher.group(3));
            }
          }
        } else {
          parameters = Collections.emptyMap();
        }
        return new WebSocketExtensionData(name, parameters);
      }
    }
    return null;
  }

  static String encode(WebSocketExtensionData extensionData) {
    String extensionName = extensionData.name();
    Map<String, String> extensionParameters = extensionData.parameters();
    if (extensionParameters.isEmpty()) {
      return extensionName;
    }
    StringBuilder sb = new StringBuilder(extensionName.length() + extensionParameters.size() * 80);
    sb.append(extensionName);
    for (Map.Entry<String, String> extensionParameter : extensionParameters.entrySet()) {
      sb.append(";");
      sb.append(extensionParameter.getKey());
      String value = extensionParameter.getValue();
      if (value != null) {
        sb.append("=");
        sb.append(value);
      }
    }
    return sb.toString();
  }
}
