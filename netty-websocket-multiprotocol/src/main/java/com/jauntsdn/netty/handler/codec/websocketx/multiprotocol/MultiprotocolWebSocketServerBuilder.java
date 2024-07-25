/*
 * Copyright 2024 - present Maksym Ostroverkhov.
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

package com.jauntsdn.netty.handler.codec.websocketx.multiprotocol;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http1WebSocketCodec;
import com.jauntsdn.netty.handler.codec.http2.websocketx.WebSocketCallbacksCodec;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builder for {@link MultiProtocolWebSocketServerHandler} */
public final class MultiprotocolWebSocketServerBuilder {
  private static final Logger logger =
      LoggerFactory.getLogger(MultiprotocolWebSocketServerBuilder.class);
  private static final boolean MASK_PAYLOAD = false;

  private String path = "/";
  private String subprotocols;
  private WebSocketDecoderConfig webSocketDecoderConfig;
  private Http1WebSocketCodec webSocketCodec = Http1WebSocketCodec.DEFAULT;

  private MultiProtocolWebSocketServerHandler.CompressionConfig compression;
  private ChannelHandler handler;
  private HttpDecoderConfig http1Config;
  private MultiProtocolWebSocketServerHandler.Http2Config http2Config;
  private long handshakeTimeoutMillis = 10_000;

  MultiprotocolWebSocketServerBuilder() {}

  /** @return new {@link MultiprotocolWebSocketServerBuilder} instance */
  public static MultiprotocolWebSocketServerBuilder create() {
    return new MultiprotocolWebSocketServerBuilder();
  }

  /**
   * Configures this handler with netty's default codec for websockets processing
   *
   * @return this {@link MultiProtocolWebSocketServerHandler} instance
   */
  public MultiprotocolWebSocketServerBuilder defaultCodec() {
    webSocketCodec = Http1WebSocketCodec.DEFAULT;
    return this;
  }

  /**
   * Configures this handler with jauntsdn/netty-websocket-http1 websocket codec offering
   * significantly higher throughput and lower per-frame heap allocation rate.
   *
   * @return this {@link MultiProtocolWebSocketServerHandler} instance
   */
  public MultiprotocolWebSocketServerBuilder callbacksCodec() {
    try {
      webSocketCodec = WebSocketCallbacksCodec.instance();
    } catch (NoClassDefFoundError e) {
      throw new IllegalArgumentException("websocket-http1 callbacks codec is not available", e);
    }
    return this;
  }

  /**
   * @param path websocket path, must start with /
   * @return this {@link MultiprotocolWebSocketServerBuilder} instance
   */
  public MultiprotocolWebSocketServerBuilder path(String path) {
    Objects.requireNonNull(path, "path");
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException("path must be started with /");
    }
    this.path = path;
    return this;
  }

  /**
   * @param subprotocols comma separated list of supported subprotocols
   * @return this {@link MultiprotocolWebSocketServerBuilder} instance
   */
  public MultiprotocolWebSocketServerBuilder subprotocols(String subprotocols) {
    this.subprotocols = Objects.requireNonNull(subprotocols, "subprotocols");
    return this;
  }

  /**
   * @param handshakeTimeoutMillis websocket handshake timeout, millis
   * @return this {@link MultiprotocolWebSocketServerBuilder} instance
   */
  public MultiprotocolWebSocketServerBuilder handshakeTimeout(long handshakeTimeoutMillis) {
    this.handshakeTimeoutMillis =
        MultiProtocolWebSocketServerHandler.requirePositive(
            handshakeTimeoutMillis, "handshakeTimeoutMillis");
    return this;
  }

  /**
   * @param webSocketDecoderConfig websocket decoder configuration. Must be non-null
   * @return this {@link MultiprotocolWebSocketServerBuilder} instance
   */
  public MultiprotocolWebSocketServerBuilder decoderConfig(
      WebSocketDecoderConfig webSocketDecoderConfig) {
    this.webSocketDecoderConfig =
        Objects.requireNonNull(webSocketDecoderConfig, "webSocketDecoderConfig");
    return this;
  }

  /**
   * @param http1Config http1 codec configuration options
   * @return this {@link MultiprotocolWebSocketServerBuilder} instance
   */
  public MultiprotocolWebSocketServerBuilder http1Config(HttpDecoderConfig http1Config) {
    this.http1Config = Objects.requireNonNull(http1Config, "http1Config");
    return this;
  }

  /**
   * @param http2Config http2 codec configuration options
   * @return this {@link MultiprotocolWebSocketServerBuilder} instance
   */
  public MultiprotocolWebSocketServerBuilder http2Config(
      MultiProtocolWebSocketServerHandler.Http2Config http2Config) {
    this.http2Config = Objects.requireNonNull(http2Config, "http2Config");
    return this;
  }

  /**
   * @param isCompressionEnabled enables permessage-deflate compression with default configuration
   * @return this {@link MultiprotocolWebSocketServerBuilder} instance
   */
  public MultiprotocolWebSocketServerBuilder compression(boolean isCompressionEnabled) {
    if (isCompressionEnabled) {
      compression = new MultiProtocolWebSocketServerHandler.CompressionConfig();
    } else {
      compression = null;
    }
    return this;
  }

  /**
   * Enables permessage-deflate compression with extended configuration. Parameters are described in
   * netty's PerMessageDeflateServerExtensionHandshaker
   *
   * @param compressionLevel sets compression level. Range is [0; 9], default is 6
   * @param allowServerWindowSize allows client to customize the server's inflater window size,
   *     default is false
   * @param preferredClientWindowSize preferred client window size if client inflater is
   *     customizable
   * @param allowServerNoContext allows client to activate server_no_context_takeover, default is
   *     false
   * @param preferredClientNoContext whether server prefers to activate client_no_context_takeover
   *     if client is compatible, default is false
   * @return this {@link MultiprotocolWebSocketServerBuilder} instance
   */
  public MultiprotocolWebSocketServerBuilder compression(
      int compressionLevel,
      boolean allowServerWindowSize,
      int preferredClientWindowSize,
      boolean allowServerNoContext,
      boolean preferredClientNoContext) {
    compression =
        new MultiProtocolWebSocketServerHandler.CompressionConfig(
            compressionLevel,
            allowServerWindowSize,
            preferredClientWindowSize,
            allowServerNoContext,
            preferredClientNoContext);
    return this;
  }

  /**
   * @param channelHandler websocket channel handler. Must be non-null.
   * @return this {@link MultiprotocolWebSocketServerBuilder} instance
   */
  public MultiprotocolWebSocketServerBuilder handler(ChannelHandler channelHandler) {
    this.handler = Objects.requireNonNull(channelHandler, "channelHandler");
    return this;
  }

  /** @return new {@link MultiProtocolWebSocketServerHandler} instance */
  public MultiProtocolWebSocketServerHandler build() {
    ChannelHandler webSocketHandler = handler;
    boolean hasCompression = compression != null;
    WebSocketDecoderConfig wsConfig = webSocketDecoderConfig;
    HttpDecoderConfig h1Config = http1Config;
    MultiProtocolWebSocketServerHandler.Http2Config h2Config = http2Config;
    Http1WebSocketCodec codec = webSocketCodec;

    if (wsConfig == null) {
      if (codec == WebSocketCallbacksCodec.DEFAULT) {
        wsConfig =
            WebSocketDecoderConfig.newBuilder()
                /*align with the spec and strictness of some browsers*/
                .expectMaskedFrames(true)
                .allowMaskMismatch(false)
                .allowExtensions(hasCompression)
                .build();
      } else {
        wsConfig =
            WebSocketDecoderConfig.newBuilder()
                .expectMaskedFrames(true)
                .allowMaskMismatch(true)
                .withUTF8Validator(false)
                .maxFramePayloadLength(65535)
                .allowExtensions(hasCompression)
                .build();
      }
    } else {
      boolean isAllowExtensions = wsConfig.allowExtensions();
      if (!isAllowExtensions && hasCompression) {
        wsConfig = wsConfig.toBuilder().allowExtensions(true).build();
      }
    }
    codec.validate(MASK_PAYLOAD, wsConfig);

    return new MultiProtocolWebSocketServerHandler(
        codec,
        wsConfig,
        h1Config,
        h2Config,
        compression,
        path,
        subprotocols,
        handshakeTimeoutMillis,
        webSocketHandler);
  }
}
