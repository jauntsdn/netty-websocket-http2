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
import java.util.Objects;

/** Builder for {@link Http2WebSocketClientHandler} */
public final class Http2WebSocketClientBuilder {
  private static final short DEFAULT_STREAM_WEIGHT = 16;

  private Http1WebSocketCodec webSocketCodec = Http1WebSocketCodec.DEFAULT;
  private WebSocketDecoderConfig webSocketDecoderConfig;
  private PerMessageDeflateClientExtensionHandshaker perMessageDeflateClientExtensionHandshaker;
  private long handshakeTimeoutMillis = 15_000;
  private short streamWeight;
  private long closedWebSocketRemoveTimeoutMillis = 30_000;
  private boolean isSingleWebSocketPerConnection;
  private boolean isMaskPayload = true;

  Http2WebSocketClientBuilder() {}

  /** @return new {@link Http2WebSocketClientBuilder} instance */
  public static Http2WebSocketClientBuilder create() {
    return new Http2WebSocketClientBuilder();
  }

  /**
   * @param webSocketCodec factory for websocket1 encoder/decoder used for protocol processing. Must
   *     be non-null
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder codec(Http1WebSocketCodec webSocketCodec) {
    this.webSocketCodec = Objects.requireNonNull(webSocketCodec, "webSocketCodec");
    return this;
  }

  /**
   * @param webSocketDecoderConfig websocket decoder configuration. Must be non-null
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder decoderConfig(WebSocketDecoderConfig webSocketDecoderConfig) {
    this.webSocketDecoderConfig =
        Objects.requireNonNull(webSocketDecoderConfig, "webSocketDecoderConfig");
    return this;
  }

  /**
   * @param maskPayload enables frame payload masking
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder maskPayload(boolean maskPayload) {
    this.isMaskPayload = maskPayload;
    return this;
  }

  /**
   * @param handshakeTimeoutMillis websocket handshake timeout. Must be positive
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder handshakeTimeoutMillis(long handshakeTimeoutMillis) {
    this.handshakeTimeoutMillis =
        Http2WebSocketProtocol.requirePositive(handshakeTimeoutMillis, "handshakeTimeoutMillis");
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
        Http2WebSocketProtocol.requirePositive(
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
   * @param compressionLevel sets compression level. Range is [0; 9], default is 6
   * @param allowClientWindowSize allows server to customize the client's inflater window size,
   *     default is false
   * @param requestedServerWindowSize requested server window size if server inflater is
   *     customizable
   * @param allowClientNoContext allows server to activate client_no_context_takeover, default is
   *     false
   * @param requestedServerNoContext whether client needs to activate server_no_context_takeover if
   *     server is compatible, default is false.
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder compression(
      int compressionLevel,
      boolean allowClientWindowSize,
      int requestedServerWindowSize,
      boolean allowClientNoContext,
      boolean requestedServerNoContext) {
    perMessageDeflateClientExtensionHandshaker =
        new PerMessageDeflateClientExtensionHandshaker(
            compressionLevel,
            allowClientWindowSize,
            requestedServerWindowSize,
            allowClientNoContext,
            requestedServerNoContext);
    return this;
  }

  /**
   * @param weight sets websocket http2 stream weight. Must belong to [1; 256] range
   * @return this {@link Http2WebSocketClientBuilder} instance
   */
  public Http2WebSocketClientBuilder streamWeight(int weight) {
    this.streamWeight = Http2WebSocketProtocol.requireRange(weight, 1, 256, "streamWeight");
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
      config =
          WebSocketDecoderConfig.newBuilder()
              /*align with the spec and strictness of some browsers*/
              .expectMaskedFrames(false)
              .allowMaskMismatch(false)
              .allowExtensions(hasCompression)
              .build();
    } else {
      boolean isAllowExtensions = config.allowExtensions();
      if (!isAllowExtensions && hasCompression) {
        config = config.toBuilder().allowExtensions(true).build();
      }
    }
    Http1WebSocketCodec codec = webSocketCodec;
    boolean maskPayload = isMaskPayload;
    codec.validate(maskPayload, config);

    short weight = streamWeight;
    if (weight == 0) {
      weight = DEFAULT_STREAM_WEIGHT;
    }

    return new Http2WebSocketClientHandler(
        codec,
        config,
        maskPayload,
        weight,
        handshakeTimeoutMillis,
        closedWebSocketRemoveTimeoutMillis,
        compressionHandshaker,
        isSingleWebSocketPerConnection);
  }
}
