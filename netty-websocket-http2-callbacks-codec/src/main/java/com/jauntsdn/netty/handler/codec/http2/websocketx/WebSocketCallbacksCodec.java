package com.jauntsdn.netty.handler.codec.http2.websocketx;

import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketProtocol;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameEncoder;

/** Provides integration with jauntsdn/netty-websocket-http1 - high performance websocket codec. */
public final class WebSocketCallbacksCodec implements Http1WebSocketCodec {
  private static final WebSocketCallbacksCodec INSTANCE = new WebSocketCallbacksCodec();

  private WebSocketCallbacksCodec() {}

  public static WebSocketCallbacksCodec instance() {
    return INSTANCE;
  }

  @Override
  public WebSocketFrameEncoder encoder(boolean maskPayload) {
    return WebSocketProtocol.frameEncoder(maskPayload);
  }

  @Override
  public WebSocketFrameDecoder decoder(WebSocketDecoderConfig config) {
    return WebSocketProtocol.frameDecoder(
        config.maxFramePayloadLength(), config.expectMaskedFrames(), config.allowMaskMismatch());
  }

  @Override
  public void validate(boolean maskPayload, WebSocketDecoderConfig config) {
    WebSocketProtocol.validateDecoderConfig(config);
  }
}
