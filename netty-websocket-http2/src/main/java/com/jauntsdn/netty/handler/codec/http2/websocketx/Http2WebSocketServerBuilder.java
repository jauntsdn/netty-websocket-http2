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

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketHandshakeOnlyServerHandler.*;
import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler.*;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builder for {@link Http2WebSocketServerHandler} */
public final class Http2WebSocketServerBuilder {
  private static final Logger logger = LoggerFactory.getLogger(Http2WebSocketServerBuilder.class);
  private WebSocketDecoderConfig webSocketDecoderConfig;
  private boolean isEncoderMaskPayload = true;
  private Map<String, AcceptorHandler> webSocketHandlers = Collections.emptyMap();
  private long handshakeTimeoutMillis = 15_000;
  private PerMessageDeflateServerExtensionHandshaker perMessageDeflateServerExtensionHandshaker;
  private long closedWebSocketRemoveTimeoutMillis = 30_000;
  private TimeoutScheduler closedWebSocketTimeoutScheduler;
  private boolean isSingleWebSocketPerConnection;

  Http2WebSocketServerBuilder() {}

  /** Utility method for configuring Http2FrameCodecBuilder with websocket-over-http2 support */
  public static Http2FrameCodecBuilder configureHttp2Server(Http2FrameCodecBuilder http2Builder) {
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
   * @param timeoutScheduler scheduler used for closed websocket remove timeouts as described in
   *     {@link #closedWebSocketRemoveTimeout(long)}. Must be non-null
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder closedWebSocketRemoveScheduler(
      TimeoutScheduler timeoutScheduler) {
    this.closedWebSocketTimeoutScheduler =
        Objects.requireNonNull(timeoutScheduler, "closedWebSocketTimeoutScheduler");
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
   * netty's PerMessageDeflateClientExtensionHandshaker
   *
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
   * Adds http1 websocket handler for given path
   *
   * @param path websocket path. Must be non-empty
   * @param handler websocket handler for given path. Must be non-null
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder handler(String path, ChannelHandler handler) {
    return handler(path, "", Http2WebSocketAcceptor.ACCEPT_ALL, handler);
  }

  /**
   * Adds http1 websocket handler with request acceptor for given path
   *
   * @param path websocket path. Must be non-empty
   * @param acceptor websocket request acceptor. Must be non-null. Default acceptor {@link
   *     Http2WebSocketAcceptor#ACCEPT_ALL} accepts all requests
   * @param handler websocket handler for given path. Must be non-null
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder handler(
      String path, Http2WebSocketAcceptor acceptor, ChannelHandler handler) {
    return handler(path, "", acceptor, handler);
  }

  /**
   * Adds http1 websocket handler with request acceptor for given path and subprotocol
   *
   * @param path websocket path. Must be non-empty
   * @param subprotocol websocket subprotocol. Must be non-null
   * @param acceptor websocket request acceptor. Must be non-null. Default acceptor {@link
   *     Http2WebSocketAcceptor#ACCEPT_ALL} accepts all requests
   * @param handler websocket handler for given path and subprotocol. Must be non-null
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder handler(
      String path, String subprotocol, Http2WebSocketAcceptor acceptor, ChannelHandler handler) {
    Preconditions.requireNonNull(path, "path");
    Preconditions.requireNonNull(subprotocol, "subprotocol");
    Preconditions.requireNonNull(acceptor, "acceptor");
    Preconditions.requireNonNull(handler, "handler");
    AcceptorHandler acceptorHandler = handler(path);
    if (acceptorHandler == null) {
      acceptorHandler = new AcceptorHandler(acceptor, handler, subprotocol);
      addHandler(path, acceptorHandler);
    } else {
      if (!acceptorHandler.addHandler(subprotocol, acceptor, handler)) {
        String subprotocolOrEmpty = subprotocol.isEmpty() ? "no subprotocol" : subprotocol;
        throw new IllegalArgumentException(
            String.format(
                "Duplicate handler for path: %s, subprotocol: %s", path, subprotocolOrEmpty));
      }
    }
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
    return new Http2WebSocketHandshakeOnlyServerHandler(null);
  }

  /**
   * Builds handshake-only {@link Http2WebSocketHandshakeOnlyServerHandler}. All configuration
   * options provided on this builder are ignored.
   *
   * @param rejectedWebSocketListener listener for websocket requests that were rejected due to
   *     protocol violation
   * @return new {@link Http2WebSocketHandshakeOnlyServerHandler} instance
   */
  public Http2WebSocketHandshakeOnlyServerHandler handshakeOnly(
      RejectedWebSocketListener rejectedWebSocketListener) {
    return new Http2WebSocketHandshakeOnlyServerHandler(
        Objects.requireNonNull(rejectedWebSocketListener, "rejectedWebSocketListener"));
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
        handshakeTimeoutMillis,
        closedWebSocketRemoveTimeoutMillis,
        closedWebSocketTimeoutScheduler,
        perMessageDeflateServerExtensionHandshaker,
        webSocketHandlers,
        isSingleWebSocketPerConnection);
  }

  private AcceptorHandler handler(String path) {
    return webSocketHandlers.get(path);
  }

  private void addHandler(String path, AcceptorHandler acceptorHandler) {
    Map<String, AcceptorHandler> handlers = webSocketHandlers;
    switch (handlers.size()) {
      case 0:
        webSocketHandlers = Collections.singletonMap(path, acceptorHandler);
        break;
      case 1:
        Map<String, AcceptorHandler> h = webSocketHandlers = new HashMap<>(/*capacity*/ 4);
        h.putAll(handlers);
        h.put(path, acceptorHandler);
        break;
      default:
        handlers.put(path, acceptorHandler);
    }
  }
}
