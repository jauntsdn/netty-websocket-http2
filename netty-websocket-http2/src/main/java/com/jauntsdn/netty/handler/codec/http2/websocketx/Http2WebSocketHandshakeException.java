package com.jauntsdn.netty.handler.codec.http2.websocketx;

import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;

public class Http2WebSocketHandshakeException extends WebSocketHandshakeException {

  public Http2WebSocketHandshakeException(String message) {
    super(message);
  }

  public Http2WebSocketHandshakeException(String message, Throwable throwable) {
    super(message, throwable);
  }

  @Override
  public final synchronized Throwable fillInStackTrace() {
    return this;
  }
}
