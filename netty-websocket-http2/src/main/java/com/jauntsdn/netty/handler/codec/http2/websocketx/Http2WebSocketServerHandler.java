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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Http2WebSocketServerHandler extends Http2WebSocketHandler {
  private static final Logger logger = LoggerFactory.getLogger(Http2WebSocketServerHandler.class);

  private final PerMessageDeflateServerExtensionHandshaker compressionHandshaker;
  private final boolean isEncoderMaskPayload;
  private final long handshakeTimeoutMillis;
  private final Map<String, AcceptorHandler> webSocketHandlers;
  private Http2WebSocketServerHandshaker http2WebSocketHandShaker;

  Http2WebSocketServerHandler(
      WebSocketDecoderConfig webSocketDecoderConfig,
      boolean isEncoderMaskPayload,
      long handshakeTimeoutMillis,
      PerMessageDeflateServerExtensionHandshaker compressionHandshaker,
      Map<String, AcceptorHandler> webSocketHandlers) {
    super(webSocketDecoderConfig);
    this.isEncoderMaskPayload = isEncoderMaskPayload;
    this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    this.compressionHandshaker = compressionHandshaker;
    this.webSocketHandlers = webSocketHandlers;
  }

  Http2WebSocketServerHandler(
      WebSocketDecoderConfig webSocketDecoderConfig, boolean isEncoderMaskPayload) {
    this(webSocketDecoderConfig, isEncoderMaskPayload, 0, null, null);
  }

  public static Http2WebSocketServerBuilder builder() {
    return new Http2WebSocketServerBuilder();
  }

  public static Http2FrameCodecBuilder configureHttp2Server(Http2FrameCodecBuilder http2Builder) {
    http2Builder
        .initialSettings()
        .put(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL, (Long) 1L);
    return http2Builder.validateHeaders(false);
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);

    this.http2WebSocketHandShaker =
        new Http2WebSocketServerHandshaker(
            webSocketsParent,
            config,
            isEncoderMaskPayload,
            handshakeTimeoutMillis,
            webSocketHandlers,
            compressionHandshaker);
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      final int streamId,
      Http2Headers headers,
      int padding,
      boolean endOfStream)
      throws Http2Exception {
    boolean proceed = handshakeWebSocket(streamId, headers, endOfStream);
    if (proceed) {
      next().onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      int streamId,
      Http2Headers headers,
      int streamDependency,
      short weight,
      boolean exclusive,
      int padding,
      boolean endOfStream)
      throws Http2Exception {
    boolean proceed = handshakeWebSocket(streamId, headers, endOfStream);
    if (proceed) {
      next()
          .onHeadersRead(
              ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
    }
  }

  private boolean handshakeWebSocket(int streamId, Http2Headers headers, boolean endOfStream) {
    if (Http2WebSocketProtocol.isExtendedConnect(headers)) {
      return http2WebSocketHandShaker.handshake(streamId, headers, endOfStream);
    }
    return true;
  }

  static class AcceptorHandler {
    private final String subprotocol;
    private Http2WebSocketAcceptor acceptor;
    private ChannelHandler handler;
    private Map<String, AcceptorHandler> subprotocolHandlers;

    AcceptorHandler(Http2WebSocketAcceptor acceptor, ChannelHandler handler, String subprotocol) {
      this(acceptor, handler, subprotocol, true);
    }

    private AcceptorHandler(
        Http2WebSocketAcceptor acceptor,
        ChannelHandler handler,
        String subprotocol,
        boolean isPathHandler) {
      this.subprotocol = subprotocol;
      /*small optimization - most servers wont have protocol specific handlers, so
       * create map for them lazily*/
      if (!isPathHandler || subprotocol.isEmpty()) {
        this.subprotocolHandlers = Collections.emptyMap();
        this.acceptor = acceptor;
        this.handler = handler;
      } else {
        Map<String, AcceptorHandler> handlers = subprotocolHandlers = new HashMap<>();
        handlers.put(subprotocol, new AcceptorHandler(acceptor, handler, subprotocol, false));
      }
    }

    public String subprotocol() {
      return subprotocol;
    }

    public boolean addHandler(
        String subprotocol, Http2WebSocketAcceptor acceptor, ChannelHandler handler) {
      if (subprotocol.isEmpty()) {
        if (this.handler != null) {
          return false;
        }
        this.acceptor = acceptor;
        this.handler = handler;
        return true;
      }
      Map<String, AcceptorHandler> handlers = subprotocolHandlers;
      if (handlers.isEmpty()) {
        handlers = subprotocolHandlers = new HashMap<>();
      }
      return handlers.put(subprotocol, new AcceptorHandler(acceptor, handler, subprotocol, false))
          == null;
    }

    public AcceptorHandler subprotocolHandler(String clientSubprotocols) {
      /*default (no-subprotocol) handler*/
      if (clientSubprotocols.isEmpty()) {
        if (handler != null) {
          return this;
        }
        return null;
      }
      String[] subprotocols = clientSubprotocols.split(",");
      for (String subprotocol : subprotocols) {
        subprotocol = subprotocol.trim();
        AcceptorHandler subprotocolHandler = subprotocolHandlers.get(subprotocol);
        if (subprotocolHandler != null) {
          return subprotocolHandler;
        }
      }
      /*fallback to default (no-subprotocol) handler - reasonable clients will close websocket
       * once discover requested protocol does not match*/
      if (handler != null) {
        return this;
      }
      return null;
    }

    public Http2WebSocketAcceptor acceptor() {
      return acceptor;
    }

    public ChannelHandler handler() {
      return handler;
    }
  }
}
