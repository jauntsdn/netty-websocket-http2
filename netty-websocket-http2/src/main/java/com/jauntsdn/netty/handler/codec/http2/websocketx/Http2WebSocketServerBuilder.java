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
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builder for {@link Http2WebSocketServerHandler} */
public final class Http2WebSocketServerBuilder {
  private static final Logger logger = LoggerFactory.getLogger(Http2WebSocketServerBuilder.class);

  private static final Http2WebSocketAcceptor REJECT_REQUESTS_ACCEPTOR =
      (context, path, subprotocols, request, response) ->
          context
              .executor()
              .newFailedFuture(
                  new Http2WebSocketPathNotFoundException(
                      Http2WebSocketMessages.HANDSHAKE_PATH_NOT_FOUND
                          + path
                          + Http2WebSocketMessages.HANDSHAKE_PATH_NOT_FOUND_SUBPROTOCOLS
                          + subprotocols));

  private WebSocketDecoderConfig webSocketDecoderConfig;
  private boolean isEncoderMaskPayload = true;
  private PerMessageDeflateServerExtensionHandshaker perMessageDeflateServerExtensionHandshaker;
  private long closedWebSocketRemoveTimeoutMillis = 30_000;
  private boolean isSingleWebSocketPerConnection;
  private Http2WebSocketAcceptor acceptor = REJECT_REQUESTS_ACCEPTOR;

  Http2WebSocketServerBuilder() {}

  /**
   * Utility method for configuring Http2FrameCodecBuilder with websocket-over-http2 support
   *
   * @param http2Builder {@link Http2FrameCodecBuilder} instance
   * @return same {@link Http2FrameCodecBuilder} instance
   */
  public static Http2FrameCodecBuilder configureHttp2Server(Http2FrameCodecBuilder http2Builder) {
    Objects.requireNonNull(http2Builder, "http2Builder")
        .initialSettings()
        .put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
    return http2Builder.validateHeaders(false);
  }

  /**
   * Utility method for configuring Http2ConnectionHandlerBuilder with websocket-over-http2 support
   *
   * @param http2Builder {@link Http2ConnectionHandlerBuilder} instance
   * @return same {@link Http2ConnectionHandlerBuilder} instance
   */
  public static Http2ConnectionHandlerBuilder configureHttp2Server(
      Http2ConnectionHandlerBuilder http2Builder) {
    Objects.requireNonNull(http2Builder, "http2Builder")
        .initialSettings()
        .put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
    return http2Builder.validateHeaders(false);
  }

  /**
   * @param webSocketDecoderConfig websocket decoder configuration. Must be non-null
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder decoderConfig(WebSocketDecoderConfig webSocketDecoderConfig) {
    this.webSocketDecoderConfig =
        Preconditions.requireNonNull(webSocketDecoderConfig, "webSocketDecoderConfig");
    return this;
  }
  /**
   * @param isEncoderMaskPayload enables websocket frames encoder payload masking
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder encoderMaskPayload(boolean isEncoderMaskPayload) {
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    return this;
  }

  /**
   * @param closedWebSocketRemoveTimeoutMillis delay until websockets handler forgets closed
   *     websocket. Necessary to gracefully handle incoming http2 frames racing with outgoing stream
   *     termination frame.
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder closedWebSocketRemoveTimeout(
      long closedWebSocketRemoveTimeoutMillis) {
    this.closedWebSocketRemoveTimeoutMillis =
        Preconditions.requirePositive(
            closedWebSocketRemoveTimeoutMillis, "closedWebSocketRemoveTimeoutMillis");
    return this;
  }

  /**
   * @param isCompressionEnabled enables permessage-deflate compression with default configuration
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder compression(boolean isCompressionEnabled) {
    if (isCompressionEnabled) {
      if (perMessageDeflateServerExtensionHandshaker == null) {
        perMessageDeflateServerExtensionHandshaker =
            new PerMessageDeflateServerExtensionHandshaker();
      }
    } else {
      perMessageDeflateServerExtensionHandshaker = null;
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
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder compression(
      int compressionLevel,
      boolean allowServerWindowSize,
      int preferredClientWindowSize,
      boolean allowServerNoContext,
      boolean preferredClientNoContext) {
    perMessageDeflateServerExtensionHandshaker =
        new PerMessageDeflateServerExtensionHandshaker(
            compressionLevel,
            allowServerWindowSize,
            preferredClientWindowSize,
            allowServerNoContext,
            preferredClientNoContext);
    return this;
  }

  /**
   * Sets http1 websocket request acceptor
   *
   * @param acceptor websocket request acceptor. Must be non-null.
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder acceptor(Http2WebSocketAcceptor acceptor) {
    this.acceptor = acceptor;
    return this;
  }

  /**
   * @param isSingleWebSocketPerConnection optimize for at most 1 websocket per connection
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder assumeSingleWebSocketPerConnection(
      boolean isSingleWebSocketPerConnection) {
    this.isSingleWebSocketPerConnection = isSingleWebSocketPerConnection;
    return this;
  }

  /**
   * Builds handshake-only {@link Http2WebSocketHandshakeOnlyServerHandler}. All configuration
   * options provided on this builder are ignored.
   *
   * @return new {@link Http2WebSocketHandshakeOnlyServerHandler} instance
   */
  public Http2WebSocketHandshakeOnlyServerHandler handshakeOnly() {
    return new Http2WebSocketHandshakeOnlyServerHandler();
  }

  /**
   * Builds subchannel based {@link Http2WebSocketServerHandler} compatible with http1 websocket
   * handlers.
   *
   * @return new {@link Http2WebSocketServerHandler} instance
   */
  public Http2WebSocketServerHandler build() {
    boolean hasCompression = perMessageDeflateServerExtensionHandshaker != null;
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
    return new Http2WebSocketServerHandler(
        config,
        isEncoderMaskPayload,
        closedWebSocketRemoveTimeoutMillis,
        perMessageDeflateServerExtensionHandshaker,
        acceptor,
        isSingleWebSocketPerConnection);
  }
}
