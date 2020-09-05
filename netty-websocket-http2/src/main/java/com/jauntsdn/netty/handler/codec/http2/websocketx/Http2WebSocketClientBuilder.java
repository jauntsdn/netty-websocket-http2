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

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketUtils.*;

import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;

/** Builder for {@link Http2WebSocketClientHandler} */
public final class Http2WebSocketClientBuilder {
  private static final short DEFAULT_STREAM_WEIGHT = 16;

  private WebSocketDecoderConfig webSocketDecoderConfig;
  private PerMessageDeflateClientExtensionHandshaker perMessageDeflateClientExtensionHandshaker;
  private boolean isEncoderMaskPayload = true;
  private long handshakeTimeoutMillis = 15_000;
  private short streamWeight;
  private long closedWebSocketRemoveTimeoutMillis = 30_000;
  private boolean isSingleWebSocketPerConnection;

  Http2WebSocketClientBuilder() {}

  /**
   * @param webSocketDecoderConfig websocket decoder configuration. Must be non-null
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder decoderConfig(WebSocketDecoderConfig webSocketDecoderConfig) {
    this.webSocketDecoderConfig =
        Preconditions.requireNonNull(webSocketDecoderConfig, "webSocketDecoderConfig");
    return this;
  }

  /**
   * @param isEncoderMaskPayload enables websocket frames encoder payload masking
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder encoderMaskPayload(boolean isEncoderMaskPayload) {
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    return this;
  }

  /**
   * @param handshakeTimeoutMillis websocket handshake timeout. Must be positive
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder handshakeTimeoutMillis(long handshakeTimeoutMillis) {
    this.handshakeTimeoutMillis =
        Preconditions.requirePositive(handshakeTimeoutMillis, "handshakeTimeoutMillis");
    return this;
  }

  /**
   * @param closedWebSocketRemoveTimeoutMillis delay until websockets handler forgets closed
   *     websocket. Necessary to gracefully handle incoming http2 frames racing with outgoing stream
   *     termination frame.
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder closedWebSocketRemoveTimeoutMillis(
      long closedWebSocketRemoveTimeoutMillis) {
    this.closedWebSocketRemoveTimeoutMillis =
        Preconditions.requirePositive(
            closedWebSocketRemoveTimeoutMillis, "closedWebSocketRemoveTimeoutMillis");
    return this;
  }

  /**
   * @param isCompressionEnabled enables permessage-deflate compression with default configuration
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
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

  /**
   * Enables permessage-deflate compression with extended configuration. Parameters are described in
   * netty's PerMessageDeflateClientExtensionHandshaker
   *
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
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

  /**
   * @param weight sets websocket http2 stream weight. Must belong to [1; 256] range
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder streamWeight(int weight) {
    this.streamWeight = Preconditions.requireRange(weight, 1, 256, "streamWeight");
    return this;
  }

  /**
   * @param isSingleWebSocketPerConnection optimize for at most 1 websocket per connection
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder assumeSingleWebSocketPerConnection(
      boolean isSingleWebSocketPerConnection) {
    this.isSingleWebSocketPerConnection = isSingleWebSocketPerConnection;
    return this;
  }

  /** @return new {@link Http2WebSocketClientHandler} instance */
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
        compressionHandshaker,
        isSingleWebSocketPerConnection);
  }
}
