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

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler.*;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Http2WebSocketServerBuilder {
  private static final Logger logger = LoggerFactory.getLogger(Http2WebSocketServerBuilder.class);
  private WebSocketDecoderConfig webSocketDecoderConfig;
  private boolean isEncoderMaskPayload = true;
  private final Map<String, AcceptorHandler> webSocketHandlers = new HashMap<>();
  private long handshakeTimeoutMillis = 15_000;
  private PerMessageDeflateServerExtensionHandshaker perMessageDeflateServerExtensionHandshaker;
  private long closedWebSocketRemoveTimeoutMillis = 30_000;
  private TimeoutScheduler closedWebSocketTimeoutScheduler;

  Http2WebSocketServerBuilder() {}

  public static Http2FrameCodecBuilder configureHttp2Server(Http2FrameCodecBuilder http2Builder) {
    http2Builder
        .initialSettings()
        .put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
    return http2Builder.validateHeaders(false);
  }

  public Http2WebSocketServerBuilder decoderConfig(WebSocketDecoderConfig webSocketDecoderConfig) {
    this.webSocketDecoderConfig =
        Preconditions.requireNonNull(webSocketDecoderConfig, "webSocketDecoderConfig");
    return this;
  }

  public Http2WebSocketServerBuilder encoderMaskPayload(boolean isEncoderMaskPayload) {
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    return this;
  }

  public Http2WebSocketServerBuilder handshakeTimeoutMillis(long handshakeTimeoutMillis) {
    this.handshakeTimeoutMillis =
        Preconditions.requirePositive(handshakeTimeoutMillis, "handshakeTimeoutMillis");
    return this;
  }

  public Http2WebSocketServerBuilder closedWebSocketRemoveTimeout(
      long closedWebSocketRemoveTimeoutMillis) {
    this.closedWebSocketRemoveTimeoutMillis =
        Preconditions.requirePositive(
            closedWebSocketRemoveTimeoutMillis, "closedWebSocketRemoveTimeoutMillis");
    return this;
  }

  public Http2WebSocketServerBuilder closedWebSocketRemoveScheduler(
      TimeoutScheduler timeoutScheduler) {
    this.closedWebSocketTimeoutScheduler =
        Objects.requireNonNull(timeoutScheduler, "closedWebSocketTimeoutScheduler");
    return this;
  }

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

  public Http2WebSocketServerBuilder handler(String path, ChannelHandler handler) {
    return handler(path, "", Http2WebSocketAcceptor.ACCEPT_ALL, handler);
  }

  public Http2WebSocketServerBuilder handler(
      String path, Http2WebSocketAcceptor acceptor, ChannelHandler handler) {
    return handler(path, "", acceptor, handler);
  }

  public Http2WebSocketServerBuilder handler(
      String path, String subprotocol, Http2WebSocketAcceptor acceptor, ChannelHandler handler) {
    Preconditions.requireNonNull(path, "path");
    Preconditions.requireNonNull(subprotocol, "subprotocol");
    Preconditions.requireNonNull(acceptor, "acceptor");
    Preconditions.requireNonNull(handler, "handler");
    AcceptorHandler acceptorHandler = webSocketHandlers.get(path);
    if (acceptorHandler == null) {
      acceptorHandler = new AcceptorHandler(acceptor, handler, subprotocol);
      webSocketHandlers.put(path, acceptorHandler);
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

  public Http2WebSocketHandshakeOnlyServerHandler handshakeOnly() {
    return new Http2WebSocketHandshakeOnlyServerHandler();
  }

  public Http2WebSocketServerHandler build() {
    boolean hasCompression = perMessageDeflateServerExtensionHandshaker != null;
    WebSocketDecoderConfig config = webSocketDecoderConfig;
    if (config == null) {
      config = WebSocketDecoderConfig.newBuilder().allowExtensions(hasCompression).build();
    } else {
      boolean isAllowExtensions = config.allowExtensions();
      if (!isAllowExtensions && hasCompression) {
        config = config.toBuilder().allowExtensions(true).build();
      }
    }
    return new Http2WebSocketServerHandler(
        config,
        isEncoderMaskPayload,
        handshakeTimeoutMillis,
        closedWebSocketRemoveTimeoutMillis,
        closedWebSocketTimeoutScheduler,
        perMessageDeflateServerExtensionHandshaker,
        webSocketHandlers);
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
