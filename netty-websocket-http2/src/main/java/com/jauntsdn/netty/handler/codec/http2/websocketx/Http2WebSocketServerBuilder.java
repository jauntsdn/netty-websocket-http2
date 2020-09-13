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

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketHandlerContainers.*;
import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketHandshakeOnlyServerHandler.*;
import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketUtils.*;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builder for {@link Http2WebSocketServerHandler} */
public final class Http2WebSocketServerBuilder {
  private static final Logger logger = LoggerFactory.getLogger(Http2WebSocketServerBuilder.class);

  private WebSocketDecoderConfig webSocketDecoderConfig;
  private boolean isEncoderMaskPayload = true;
  private PerMessageDeflateServerExtensionHandshaker perMessageDeflateServerExtensionHandshaker;
  private long closedWebSocketRemoveTimeoutMillis = 30_000;
  private boolean isSingleWebSocketPerConnection;
  private int handlersCountHint;
  private List<WebSocketPathHandler> webSocketPathHandlers;
  private Http2WebSocketServerHandler.WebSocketHandler.Container websocketHandlers;

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
   * @param handlersCountHint number of handlers. Optional, enables minor optimizations.
   * @return this {@link Http2WebSocketServerBuilder} instance
   */
  public Http2WebSocketServerBuilder handlersCount(int handlersCountHint) {
    this.handlersCountHint =
        Preconditions.requireNonNegative(handlersCountHint, "handlersCountHint");
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

    List<WebSocketPathHandler> pathHandlers = webSocketPathHandlers;
    /*handlers list created first as there was no size hint*/
    if (pathHandlers != null) {
      pathHandlers.add(new WebSocketPathHandler(path, subprotocol, acceptor, handler));
      return this;
    }
    int count = handlersCountHint;
    Http2WebSocketServerHandler.WebSocketHandler.Container handlers = websocketHandlers;
    if (count > 0 && handlers == null) {
      handlers = websocketHandlers = createWebSocketHandlersContainer(count);
    }
    /*handlers container created first as there was size hint*/
    if (handlers != null) {
      handlers.put(path, subprotocol, acceptor, handler);
      return this;
    }
    pathHandlers = webSocketPathHandlers = new ArrayList<>(4);
    pathHandlers.add(new WebSocketPathHandler(path, subprotocol, acceptor, handler));
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
    Http2WebSocketServerHandler.WebSocketHandler.Container handlers = websocketHandlers;
    if (handlers == null) {
      List<WebSocketPathHandler> pathHandlers = webSocketPathHandlers;
      if (pathHandlers == null) {
        handlers = EmptyHandlerContainer.getInstance();
      } else {
        handlers = createWebSocketHandlersContainer(pathHandlers.size());
        for (WebSocketPathHandler handler : pathHandlers) {
          handlers.put(
              handler.path(), handler.subprotocol(), handler.acceptor(), handler.handler());
        }
      }
    }
    return new Http2WebSocketServerHandler(
        config,
        isEncoderMaskPayload,
        closedWebSocketRemoveTimeoutMillis,
        perMessageDeflateServerExtensionHandshaker,
        handlers,
        isSingleWebSocketPerConnection);
  }

  static Http2WebSocketServerHandler.WebSocketHandler.Container createWebSocketHandlersContainer(
      int handlersCount) {
    if (handlersCount == 1) {
      return new SingleHandlerContainer();
    }
    return new DefaultHandlerContainer(handlersCount);
  }

  private static class WebSocketPathHandler {
    private final String path;
    private final String subprotocol;
    private final Http2WebSocketAcceptor acceptor;
    private final ChannelHandler handler;

    public WebSocketPathHandler(
        String path, String subprotocol, Http2WebSocketAcceptor acceptor, ChannelHandler handler) {
      this.path = path;
      this.subprotocol = subprotocol;
      this.acceptor = acceptor;
      this.handler = handler;
    }

    public String path() {
      return path;
    }

    public String subprotocol() {
      return subprotocol;
    }

    public Http2WebSocketAcceptor acceptor() {
      return acceptor;
    }

    public ChannelHandler handler() {
      return handler;
    }
  }
}
