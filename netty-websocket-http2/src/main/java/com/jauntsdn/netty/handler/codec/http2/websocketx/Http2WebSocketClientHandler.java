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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.SslHandler;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nullable;

public class Http2WebSocketClientHandler extends Http2WebSocketHandler {
  private static final AtomicReferenceFieldUpdater<
          Http2WebSocketClientHandler, Http2WebSocketClientHandshaker>
      HANDSHAKER =
          AtomicReferenceFieldUpdater.newUpdater(
              Http2WebSocketClientHandler.class,
              Http2WebSocketClientHandshaker.class,
              "handshaker");

  private final boolean encoderMaskPayload;
  private final long handshakeTimeoutMillis;
  private final PerMessageDeflateClientExtensionHandshaker compressionHandshaker;
  private final short streamWeight;
  private Boolean supportsWebSocket;
  private volatile Http2Connection.Endpoint<Http2LocalFlowController> streamIdFactory;
  private volatile Http2WebSocketClientHandshaker handshaker;
  private CharSequence scheme;
  private boolean supportsWebSocketCalled;

  Http2WebSocketClientHandler(
      WebSocketDecoderConfig webSocketDecoderConfig,
      boolean encoderMaskPayload,
      short streamWeight,
      long handshakeTimeoutMillis,
      long closedWebSocketRemoveTimeoutMillis,
      @Nullable PerMessageDeflateClientExtensionHandshaker compressionHandshaker) {
    super(webSocketDecoderConfig, closedWebSocketRemoveTimeoutMillis);
    this.encoderMaskPayload = encoderMaskPayload;
    this.streamWeight = streamWeight;
    this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    this.compressionHandshaker = compressionHandshaker;
  }

  public static Http2WebSocketClientBuilder builder() {
    return new Http2WebSocketClientBuilder();
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);
    this.scheme =
        ctx.pipeline().get(SslHandler.class) != null
            ? Http2WebSocketProtocol.SCHEME_HTTPS
            : Http2WebSocketProtocol.SCHEME_HTTP;
    this.streamIdFactory = http2Handler.connection().local();
  }

  @Override
  public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings)
      throws Http2Exception {
    if (supportsWebSocket != null) {
      super.onSettingsRead(ctx, settings);
      return;
    }
    Long extendedConnectEnabled =
        settings.get(Http2WebSocketProtocol.SETTINGS_ENABLE_CONNECT_PROTOCOL);
    boolean supports =
        supportsWebSocket = extendedConnectEnabled != null && extendedConnectEnabled == 1;
    Http2WebSocketClientHandshaker listener = HANDSHAKER.get(this);
    if (listener != null) {
      supportsWebSocketCalled = true;
      listener.onSupportsWebSocket(supports);
    }
    super.onSettingsRead(ctx, settings);
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      int streamId,
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

  Http2WebSocketClientHandshaker handShaker() {
    Http2WebSocketClientHandshaker h = HANDSHAKER.get(this);
    if (h != null) {
      return h;
    }
    Http2Connection.Endpoint<Http2LocalFlowController> streamIdFactory = this.streamIdFactory;
    if (streamIdFactory == null) {
      throw new IllegalStateException(
          "webSocket handshaker cant be created before channel is registered");
    }
    Http2WebSocketClientHandshaker handShaker =
        new Http2WebSocketClientHandshaker(
            webSocketsParent,
            streamIdFactory,
            config,
            encoderMaskPayload,
            streamWeight,
            scheme,
            handshakeTimeoutMillis,
            compressionHandshaker);

    if (HANDSHAKER.compareAndSet(this, null, handShaker)) {
      EventLoop el = ctx.channel().eventLoop();
      if (el.inEventLoop()) {
        onSupportsWebSocket(handShaker);
      } else {
        el.execute(() -> onSupportsWebSocket(handShaker));
      }
      return handShaker;
    }
    return HANDSHAKER.get(this);
  }

  private boolean handshakeWebSocket(
      int streamId, Http2Headers responseHeaders, boolean endOfStream) {
    Http2WebSocket webSocketChannel = webSockets.get(streamId);
    if (webSocketChannel == null) {
      return true;
    }
    handShaker().handshake(webSocketChannel, responseHeaders, endOfStream);
    return false;
  }

  private void onSupportsWebSocket(Http2WebSocketClientHandshaker handshaker) {
    if (supportsWebSocketCalled) {
      return;
    }
    Boolean supports = supportsWebSocket;
    if (supports != null) {
      handshaker.onSupportsWebSocket(supports);
    }
  }
}
