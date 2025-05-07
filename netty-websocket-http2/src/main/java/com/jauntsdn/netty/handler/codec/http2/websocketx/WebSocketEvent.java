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

package com.jauntsdn.netty.handler.codec.http2.websocketx;

import io.netty.handler.codec.Headers;
import javax.annotation.Nullable;

/** Base type for transport agnostic websocket lifecycle events */
public abstract class WebSocketEvent extends Http2WebSocketEvent.Http2WebSocketLifecycleEvent {

  WebSocketEvent(
      Http2WebSocketEvent.Type type,
      int id,
      String authority,
      String path,
      String subprotocols,
      long timestampNanos) {
    super(type, id, authority, path, subprotocols, timestampNanos);
  }

  /** websocket handshake start event */
  public static class WebSocketHandshakeStartEvent extends WebSocketEvent {
    private final Headers<CharSequence, CharSequence, ?> requestHeaders;

    WebSocketHandshakeStartEvent(
        int id,
        String authority,
        String path,
        String subprotocol,
        long timestampNanos,
        Headers<CharSequence, CharSequence, ?> requestHeaders) {
      super(Type.HANDSHAKE_START, id, authority, path, subprotocol, timestampNanos);
      this.requestHeaders = requestHeaders;
    }

    /** @return websocket request headers */
    public Headers<CharSequence, CharSequence, ?> requestHeaders() {
      return requestHeaders;
    }
  }

  /** websocket handshake error event */
  public static class WebSocketHandshakeErrorEvent extends WebSocketEvent {
    private final Headers<CharSequence, CharSequence, ?> responseHeaders;
    private final String errorName;
    private final String errorMessage;
    private final Throwable error;

    WebSocketHandshakeErrorEvent(
        int id,
        String authority,
        String path,
        String subprotocols,
        long timestampNanos,
        Headers<CharSequence, CharSequence, ?> responseHeaders,
        Throwable error) {
      this(id, authority, path, subprotocols, timestampNanos, responseHeaders, error, null, null);
    }

    WebSocketHandshakeErrorEvent(
        int id,
        String authority,
        String path,
        String subprotocols,
        long timestampNanos,
        Headers<CharSequence, CharSequence, ?> responseHeaders,
        String errorName,
        String errorMessage) {
      this(
          id,
          authority,
          path,
          subprotocols,
          timestampNanos,
          responseHeaders,
          null,
          errorName,
          errorMessage);
    }

    private WebSocketHandshakeErrorEvent(
        int id,
        String authority,
        String path,
        String subprotocols,
        long timestampNanos,
        Headers<CharSequence, CharSequence, ?> responseHeaders,
        Throwable error,
        String errorName,
        String errorMessage) {
      super(Type.HANDSHAKE_ERROR, id, authority, path, subprotocols, timestampNanos);
      this.responseHeaders = responseHeaders;
      this.errorName = errorName;
      this.errorMessage = errorMessage;
      this.error = error;
    }

    /** @return response headers of failed websocket handshake */
    public Headers<CharSequence, CharSequence, ?> responseHeaders() {
      return responseHeaders;
    }

    /**
     * @return exception associated with failed websocket handshake. May be null, in this case
     *     {@link #errorName()} and {@link #errorMessage()} contain error details.
     */
    @Nullable
    public Throwable error() {
      return error;
    }

    /**
     * @return name of error associated with failed websocket handshake. May be null, in this case
     *     {@link #error()} contains respective exception
     */
    public String errorName() {
      return errorName;
    }

    /**
     * @return message of error associated with failed websocket handshake. May be null, in this
     *     case {@link #error()} contains respective exception
     */
    public String errorMessage() {
      return errorMessage;
    }
  }

  /** websocket handshake success event */
  public static class WebSocketHandshakeSuccessEvent extends WebSocketEvent {
    private final String subprotocol;
    private final Headers<CharSequence, CharSequence, ?> responseHeaders;

    WebSocketHandshakeSuccessEvent(
        int id,
        String authority,
        String path,
        String subprotocols,
        String subprotocol,
        long timestampNanos,
        Headers<CharSequence, CharSequence, ?> responseHeaders) {
      super(Type.HANDSHAKE_SUCCESS, id, authority, path, subprotocols, timestampNanos);
      this.subprotocol = subprotocol;
      this.responseHeaders = responseHeaders;
    }

    public String subprotocol() {
      return subprotocol;
    }

    /** @return response headers of succeeded websocket handshake */
    public Headers<CharSequence, CharSequence, ?> responseHeaders() {
      return responseHeaders;
    }
  }
}
