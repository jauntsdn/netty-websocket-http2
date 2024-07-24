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
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketAcceptor;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtensionHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateFrameServerExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handler to process both websocket-over-http1 and websocket-over-http2 protocols */
public final class MultiProtocolWebSocketServerHandler extends ChannelInitializer<SocketChannel> {
  private static final Logger logger =
      LoggerFactory.getLogger(MultiProtocolWebSocketServerHandler.class);

  private final Http1WebSocketCodec webSocketCodec;
  private final WebSocketDecoderConfig webSocketDecoderConfig;
  private final HttpDecoderConfig http1Config;
  private final Http2Config http2Config;
  private final CompressionConfig compression;
  private final String path;
  private final String subprotocols;
  private final Set<String> subprotocolSet;
  private final long handshakeTimeoutMillis;
  private final ChannelHandler webSocketHandler;

  MultiProtocolWebSocketServerHandler(
      Http1WebSocketCodec webSocketCodec,
      WebSocketDecoderConfig webSocketDecoderConfig,
      HttpDecoderConfig http1Config,
      Http2Config http2Config,
      CompressionConfig compression,
      String path,
      @Nullable String subprotocols,
      long handshakeTimeoutMillis,
      ChannelHandler webSocketHandler) {
    this.webSocketCodec = webSocketCodec;
    this.webSocketDecoderConfig = webSocketDecoderConfig;
    this.http1Config = http1Config;
    this.http2Config = http2Config;
    this.compression = compression;
    this.path = path;
    this.subprotocols = subprotocols;
    this.subprotocolSet = parseSubprotocols(subprotocols);
    this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    this.webSocketHandler = webSocketHandler;
  }

  @Override
  protected void initChannel(SocketChannel ch) {
    ChannelHandler webSocketHandler = this.webSocketHandler;
    ApplicationProtocolNegotiationHandler alpnHandler =
        new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
          @Override
          protected void configurePipeline(ChannelHandlerContext c, String protocol) {

            switch (protocol) {
              case ApplicationProtocolNames.HTTP_2:
                {
                  Http2Config h2Config = http2Config;
                  Http2FrameCodecBuilder http2FrameCodecBuilder =
                      applyConfig(Http2FrameCodecBuilder.forServer(), h2Config);
                  Http2FrameCodec http2frameCodec =
                      Http2WebSocketServerBuilder.configureHttp2Server(http2FrameCodecBuilder)
                          .build();

                  Http2WebSocketServerBuilder http2webSocketBuilder =
                      applyConfig(
                          Http2WebSocketServerBuilder.create()
                              .codec(webSocketCodec)
                              .decoderConfig(webSocketDecoderConfig),
                          h2Config);

                  CompressionConfig compr = compression;
                  if (compr != null) {
                    http2webSocketBuilder.compression(
                        compr.compressionLevel,
                        compr.allowServerWindowSize,
                        compr.preferredClientWindowSize,
                        compr.allowServerNoContext,
                        compr.preferredClientNoContext);
                  }

                  String webSocketPath = path;
                  Http2WebSocketServerHandler http2WebSocketHandler =
                      http2webSocketBuilder
                          .acceptor(
                              (ctx, path, requestedSubprotocols, request, response) -> {
                                if (webSocketPath.equals(path)) {
                                  String subprotocol =
                                      selectSubprotocol(requestedSubprotocols, subprotocolSet);
                                  if (subprotocol != null) {
                                    if (!subprotocol.isEmpty()) {
                                      Http2WebSocketAcceptor.Subprotocol.accept(
                                          subprotocol, response);
                                    }
                                    return ctx.executor().newSucceededFuture(webSocketHandler);
                                  }
                                }
                                return ctx.executor()
                                    .newFailedFuture(
                                        new WebSocketHandshakeException(
                                            String.format(
                                                "websocket rejected, path: %s, subprotocols: %s",
                                                path, requestedSubprotocols)));
                              })
                          .build();

                  ch.pipeline().addLast(http2frameCodec, http2WebSocketHandler);
                  break;
                }

              case ApplicationProtocolNames.HTTP_1_1:
                {
                  logger.debug("Server accepted TLS connection for websockets-over-http1");

                  ChannelPipeline pipeline = ch.pipeline();

                  HttpServerCodec http1Codec = createWithConfig(http1Config);
                  HttpObjectAggregator http1Aggregator =
                      new HttpObjectAggregator(/*maxContentLength*/ 65536);
                  pipeline.addLast(http1Codec, http1Aggregator);

                  if (webSocketCodec == Http1WebSocketCodec.DEFAULT) {
                    CompressionConfig compr = compression;
                    if (compr != null) {
                      WebSocketServerExtensionHandler http1WebSocketCompressor =
                          new ConfigurableWebSocketCompressionHandler(
                              compr.compressionLevel,
                              compr.allowServerWindowSize,
                              compr.preferredClientWindowSize,
                              compr.allowServerNoContext,
                              compr.preferredClientNoContext);

                      pipeline.addLast(http1WebSocketCompressor);
                    }
                    io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
                        defaultWebSocketProtocolHandler =
                            new io.netty.handler.codec.http.websocketx
                                .WebSocketServerProtocolHandler(
                                path,
                                subprotocols,
                                false,
                                false,
                                handshakeTimeoutMillis,
                                webSocketDecoderConfig);
                    pipeline.addLast(defaultWebSocketProtocolHandler);
                  } else {
                    com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
                        callbacksWebSocketProtocolHandler =
                            com.jauntsdn.netty.handler.codec.http.websocketx
                                .WebSocketServerProtocolHandler.create()
                                .path(path)
                                .subprotocols(subprotocols)
                                .handshakeTimeoutMillis(handshakeTimeoutMillis)
                                .decoderConfig(webSocketDecoderConfig)
                                .build();
                    pipeline.addLast(callbacksWebSocketProtocolHandler);
                  }
                  pipeline.addLast(webSocketHandler);
                  break;
                }
              default:
                logger.info("Unsupported protocol for TLS connection: {}", protocol);
                c.close();
            }
          }
        };

    ch.pipeline().addLast(alpnHandler);
  }

  static Http2FrameCodecBuilder applyConfig(
      Http2FrameCodecBuilder http2frameCodecBuilder, Http2Config http2Config) {
    if (http2Config != null) {
      http2frameCodecBuilder
          .autoAckPingFrame(http2Config.autoAckPingFrame)
          .autoAckSettingsFrame(http2Config.autoAckSettingsFrame)
          .validateHeaders(http2Config.validateHeaders)
          .gracefulShutdownTimeoutMillis(http2Config.gracefulShutdownTimeoutMillis);
      try {
        http2Config.http2Settings.accept(http2frameCodecBuilder.initialSettings());
      } catch (Exception e) {
        logger.error("Error while applying http2 settings to Http2FrameCodecBuilder", e);
      }
    }
    return http2frameCodecBuilder;
  }

  static Http2WebSocketServerBuilder applyConfig(
      Http2WebSocketServerBuilder http2WebSocketBuilder, Http2Config http2Config) {
    if (http2Config != null) {
      http2WebSocketBuilder.closedWebSocketRemoveTimeout(
          http2Config.closedWebSocketRemoveTimeoutMillis);
    }
    return http2WebSocketBuilder;
  }

  static HttpServerCodec createWithConfig(HttpDecoderConfig http1Config) {
    if (http1Config != null) {
      return new HttpServerCodec(http1Config);
    }
    return new HttpServerCodec();
  }

  static final class CompressionConfig {
    final int compressionLevel;
    final boolean allowServerWindowSize;
    final int preferredClientWindowSize;
    final boolean allowServerNoContext;
    final boolean preferredClientNoContext;

    CompressionConfig(
        int compressionLevel,
        boolean allowServerWindowSize,
        int preferredClientWindowSize,
        boolean allowServerNoContext,
        boolean preferredClientNoContext) {
      this.compressionLevel = compressionLevel;
      this.allowServerWindowSize = allowServerWindowSize;
      this.preferredClientWindowSize = preferredClientWindowSize;
      this.allowServerNoContext = allowServerNoContext;
      this.preferredClientNoContext = preferredClientNoContext;
    }

    CompressionConfig() {
      this(6, ZlibCodecFactory.isSupportingWindowSizeAndMemLevel(), 15, false, false);
    }
  }

  public static final class Http2Config {
    final Consumer<Http2Settings> http2Settings;
    final long closedWebSocketRemoveTimeoutMillis;
    final long gracefulShutdownTimeoutMillis;
    final boolean validateHeaders;
    final boolean autoAckSettingsFrame;
    final boolean autoAckPingFrame;

    Http2Config(
        Consumer<Http2Settings> http2Settings,
        long closedWebSocketRemoveTimeoutMillis,
        long gracefulShutdownTimeoutMillis,
        boolean validateHeaders,
        boolean autoAckSettingsFrame,
        boolean autoAckPingFrame) {

      this.http2Settings = http2Settings;
      this.closedWebSocketRemoveTimeoutMillis = closedWebSocketRemoveTimeoutMillis;
      this.gracefulShutdownTimeoutMillis = gracefulShutdownTimeoutMillis;
      this.validateHeaders = validateHeaders;
      this.autoAckSettingsFrame = autoAckSettingsFrame;
      this.autoAckPingFrame = autoAckPingFrame;
    }

    public static class Builder {
      private Consumer<Http2Settings> http2Settings = settings -> {};
      private long closedWebSocketRemoveTimeoutMillis = 30_000;
      private long gracefulShutdownTimeoutMillis = 30_000;
      private boolean valdiateHeaders = true;
      private boolean autoAckSettingsFrame = true;
      private boolean autoAckPingFrame = true;

      public Builder http2Settings(Consumer<Http2Settings> http2Settings) {
        this.http2Settings = Objects.requireNonNull(http2Settings, "http2Settings");
        return this;
      }

      public Builder closedWebSocketRemoveTimeoutMillis(long closedWebSocketRemoveTimeoutMillis) {
        this.closedWebSocketRemoveTimeoutMillis =
            requirePositive(
                closedWebSocketRemoveTimeoutMillis, "closedWebSocketRemoveTimeoutMillis");
        return this;
      }

      public Builder gracefulShutdownTimeoutMillis(long gracefulShutdownTimeoutMillis) {
        this.gracefulShutdownTimeoutMillis =
            requirePositive(gracefulShutdownTimeoutMillis, "gracefulShutdownTimeoutMillis");
        return this;
      }

      public Builder valdiateHeaders(boolean valdiateHeaders) {
        this.valdiateHeaders = valdiateHeaders;
        return this;
      }

      public Builder autoAckSettingsFrame(boolean autoAckSettingsFrame) {
        this.autoAckSettingsFrame = autoAckSettingsFrame;
        return this;
      }

      public Builder autoAckPingFrame(boolean autoAckPingFrame) {
        this.autoAckPingFrame = autoAckPingFrame;
        return this;
      }

      public Http2Config build() {
        return new Http2Config(
            http2Settings,
            closedWebSocketRemoveTimeoutMillis,
            gracefulShutdownTimeoutMillis,
            valdiateHeaders,
            autoAckSettingsFrame,
            autoAckPingFrame);
      }
    }
  }

  static final class ConfigurableWebSocketCompressionHandler
      extends WebSocketServerExtensionHandler {

    ConfigurableWebSocketCompressionHandler(
        int compressionLevel,
        boolean allowServerWindowSize,
        int preferredClientWindowSize,
        boolean allowServerNoContext,
        boolean preferredClientNoContext) {
      super(
          new PerMessageDeflateServerExtensionHandshaker(
              compressionLevel,
              allowServerWindowSize,
              preferredClientWindowSize,
              allowServerNoContext,
              preferredClientNoContext),
          new DeflateFrameServerExtensionHandshaker(compressionLevel));
    }
  }

  static Set<String> parseSubprotocols(String subprotocols) {
    if (subprotocols == null || subprotocols.isEmpty()) {
      return Collections.emptySet();
    }
    String[] arr = subprotocols.split(",");
    Set<String> subprotocolSet = new HashSet<>(arr.length);
    for (String subprotocol : arr) {
      subprotocolSet.add(subprotocol.trim());
    }
    return subprotocolSet;
  }

  static String selectSubprotocol(
      List<String> requestedSubprotocols, Set<String> supportedSubprotocols) {
    if (requestedSubprotocols.isEmpty() && supportedSubprotocols.isEmpty()) {
      return "";
    }
    for (String requested : requestedSubprotocols) {
      if (supportedSubprotocols.contains(requested)) {
        return requested;
      }
    }
    return null;
  }

  static long requirePositive(long value, String message) {
    if (value <= 0) {
      throw new IllegalArgumentException(message + " must positive, provided: " + value);
    }
    return value;
  }
}
