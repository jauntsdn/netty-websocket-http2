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

import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Provides server-side support for websocket-over-http2. Creates sub channel for http2 stream of
 * successfully handshaked websocket. Subchannel is compatible with http1 websocket handlers.
 */
public final class Http2WebSocketServerHandler extends Http2WebSocketChannelHandler {
  private final long handshakeTimeoutMillis;
  private final PerMessageDeflateServerExtensionHandshaker compressionHandshaker;
  private final Map<String, AcceptorHandler> webSocketHandlers;
  private final TimeoutScheduler closedWebSocketTimeoutScheduler;

  private Http2WebSocketServerHandshaker handshaker;

  Http2WebSocketServerHandler(
      WebSocketDecoderConfig webSocketDecoderConfig,
      boolean isEncoderMaskPayload,
      long handshakeTimeoutMillis,
      long closedWebSocketRemoveTimeoutMillis,
      @Nullable TimeoutScheduler closedWebSocketTimeoutScheduler,
      @Nullable PerMessageDeflateServerExtensionHandshaker compressionHandshaker,
      Map<String, AcceptorHandler> webSocketHandlers) {
    super(webSocketDecoderConfig, isEncoderMaskPayload, closedWebSocketRemoveTimeoutMillis);
    this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    this.closedWebSocketTimeoutScheduler = closedWebSocketTimeoutScheduler;
    this.compressionHandshaker = compressionHandshaker;
    this.webSocketHandlers = webSocketHandlers;
  }

  public static Http2WebSocketServerBuilder builder() {
    return new Http2WebSocketServerBuilder();
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);
    this.handshaker =
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

  @Override
  void removeAfterTimeout(
      int streamId,
      IntObjectMap<Http2WebSocket> webSockets,
      ChannelFuture connectionCloseFuture,
      EventLoop eventLoop) {
    TimeoutScheduler scheduler = closedWebSocketTimeoutScheduler;
    /* assume most users prefer timeouts by eventloop;
    external scheduler is handled as special case to save few allocations */
    if (scheduler != null) {
      RemoveWebSocket removeWebSocket =
          new RemoveWebSocket(streamId, webSockets, connectionCloseFuture);
      TimeoutScheduler.Handle removeWebSocketHandle;
      try {
        removeWebSocketHandle =
            scheduler.schedule(
                removeWebSocket,
                closedWebSocketRemoveTimeoutMillis,
                TimeUnit.MILLISECONDS,
                eventLoop);
      } catch (Exception e) {
        ChannelHandlerContext c = ctx;
        c.fireExceptionCaught(
            new IllegalStateException(
                String.format(
                    "http2 websocket CloseTimeoutScheduler %s schedule() error",
                    scheduler.getClass().getName()),
                e));
        c.close();
        return;
      }
      if (removeWebSocketHandle == null) {
        ChannelHandlerContext c = ctx;
        c.fireExceptionCaught(
            new IllegalStateException(
                String.format(
                    "http2 websocket CloseTimeoutScheduler %s schedule() returned null handle",
                    scheduler.getClass().getName())));
        c.close();
        return;
      }
      removeWebSocket.setRemoveWebSocketHandle(removeWebSocketHandle);
      return;
    }
    super.removeAfterTimeout(streamId, webSockets, connectionCloseFuture, eventLoop);
  }

  private boolean handshakeWebSocket(int streamId, Http2Headers headers, boolean endOfStream) {
    if (Http2WebSocketProtocol.isExtendedConnect(headers)) {
      return handshaker.handshake(streamId, headers, endOfStream);
    }
    return true;
  }

  private static class RemoveWebSocket implements Runnable, GenericFutureListener<ChannelFuture> {
    private final IntObjectMap<Http2WebSocket> webSockets;
    private final int streamId;
    private final ChannelFuture connectionCloseFuture;
    private TimeoutScheduler.Handle removeWebSocketHandle;

    RemoveWebSocket(
        int streamId,
        IntObjectMap<Http2WebSocket> webSockets,
        ChannelFuture connectionCloseFuture) {
      this.streamId = streamId;
      this.webSockets = webSockets;
      this.connectionCloseFuture = connectionCloseFuture;
    }

    void setRemoveWebSocketHandle(TimeoutScheduler.Handle removeWebSocketHandle) {
      this.removeWebSocketHandle = removeWebSocketHandle;
      connectionCloseFuture.addListener(this);
    }

    /*connection close*/
    @Override
    public void operationComplete(ChannelFuture future) {
      TimeoutScheduler.Handle h = removeWebSocketHandle;
      try {
        h.cancel();
      } catch (Exception e) {
        Channel ch = connectionCloseFuture.channel();
        ch.pipeline()
            .fireExceptionCaught(
                new IllegalStateException(
                    String.format(
                        "http2 websocket CloseTimeoutScheduler handle %s cancellation error",
                        h.getClass().getName())));
        ch.close();
      }
    }

    /*after websocket close timeout*/
    @Override
    public void run() {
      EventLoop el = connectionCloseFuture.channel().eventLoop();
      if (el.inEventLoop()) {
        webSockets.remove(streamId);
        connectionCloseFuture.removeListener(this);
      } else {
        el.execute(this);
      }
    }
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
        Map<String, AcceptorHandler> handlers =
            subprotocolHandlers = new HashMap<>(/*capacity*/ 2, /*load factor*/ 1.0f);
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
        handlers = subprotocolHandlers = new HashMap<>(/*capacity*/ 2, /*load factor*/ 1.0f);
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
