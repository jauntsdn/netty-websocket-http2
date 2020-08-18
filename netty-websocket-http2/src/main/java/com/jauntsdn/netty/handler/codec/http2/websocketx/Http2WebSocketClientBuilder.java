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

import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;

public final class Http2WebSocketClientBuilder {
  private static final short DEFAULT_STREAM_WEIGHT = 16;

  private WebSocketDecoderConfig webSocketDecoderConfig;
  private PerMessageDeflateClientExtensionHandshaker perMessageDeflateClientExtensionHandshaker;
  private boolean isEncoderMaskPayload = true;
  private long handshakeTimeoutMillis = 15_000;
  private short streamWeight;
  private long closedWebSocketRemoveTimeoutMillis = 30_000;

  Http2WebSocketClientBuilder() {}

  public Http2WebSocketClientBuilder decoderConfig(WebSocketDecoderConfig webSocketDecoderConfig) {
    this.webSocketDecoderConfig =
        Preconditions.requireNonNull(webSocketDecoderConfig, "webSocketDecoderConfig");
    return this;
  }

  public Http2WebSocketClientBuilder encoderMaskPayload(boolean isEncoderMaskPayload) {
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    return this;
  }

  public Http2WebSocketClientBuilder handshakeTimeoutMillis(long handshakeTimeoutMillis) {
    this.handshakeTimeoutMillis =
        Preconditions.requirePositive(handshakeTimeoutMillis, "handshakeTimeoutMillis");
    return this;
  }

  public Http2WebSocketClientBuilder closedWebSocketRemoveTimeout(
      long closedWebSocketRemoveTimeoutMillis) {
    this.closedWebSocketRemoveTimeoutMillis =
        Preconditions.requirePositive(
            closedWebSocketRemoveTimeoutMillis, "closedWebSocketRemoveTimeoutMillis");
    return this;
  }

  public Http2WebSocketClientBuilder compression(boolean isCompressionEnabled) {
    if (isCompressionEnabled) {
      if (perMessageDeflateClientExtensionHandshaker == null) {
        perMessageDeflateClientExtensionHandshaker =
            new PerMessageDeflateClientExtensionHandshaker();
      }
    } else {
      perMessageDeflateClientExtensionHandshaker = null;
    }
    return this;
  }

  public Http2WebSocketClientBuilder compression(
      int compressionLevel,
      boolean allowServerWindowSize,
      int preferredClientWindowSize,
      boolean allowServerNoContext,
      boolean preferredClientNoContext) {
    perMessageDeflateClientExtensionHandshaker =
        new PerMessageDeflateClientExtensionHandshaker(
            compressionLevel,
            allowServerWindowSize,
            preferredClientWindowSize,
            allowServerNoContext,
            preferredClientNoContext);
    return this;
  }

  public Http2WebSocketClientBuilder streamWeight(int weight) {
    this.streamWeight = Preconditions.requireRange(weight, 1, 256, "streamWeight");
    return this;
  }

  public Http2WebSocketClientHandler build() {
    PerMessageDeflateClientExtensionHandshaker compressionHandshaker =
        perMessageDeflateClientExtensionHandshaker;
    boolean hasCompression = compressionHandshaker != null;
    WebSocketDecoderConfig config = webSocketDecoderConfig;
    if (config == null) {
      config = WebSocketDecoderConfig.newBuilder().allowExtensions(hasCompression).build();
    } else {
      boolean isAllowExtensions = config.allowExtensions();
      if (!isAllowExtensions && hasCompression) {
        throw new IllegalStateException(
            "websocket compression is enabled while extensions are disabled");
      }
    }
    short weight = streamWeight;
    if (weight == 0) {
      weight = DEFAULT_STREAM_WEIGHT;
    }
    return new Http2WebSocketClientHandler(
        config,
        isEncoderMaskPayload,
        weight,
        handshakeTimeoutMillis,
        closedWebSocketRemoveTimeoutMillis,
        compressionHandshaker);
  }
}
