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

import com.jauntsdn.netty.handler.codec.http2.websocketx.WebSocketEvent.WebSocketHandshakeErrorEvent;
import com.jauntsdn.netty.handler.codec.http2.websocketx.WebSocketEvent.WebSocketHandshakeStartEvent;
import com.jauntsdn.netty.handler.codec.http2.websocketx.WebSocketEvent.WebSocketHandshakeSuccessEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http2.Http2Headers;
import javax.annotation.Nullable;

/** Base type for websocket-over-http2 events */
public abstract class Http2WebSocketEvent {
  private final Type type;

  Http2WebSocketEvent(Type type) {
    this.type = type;
  }

  static void fireWebSocketSupported(Channel parentChannel, boolean webSocketSupported) {
    long timestamp = System.nanoTime();
    ChannelPipeline parentPipeline = parentChannel.pipeline();
    parentPipeline.fireUserEventTriggered(
        new Http2WebSocketSupportedEvent(webSocketSupported, timestamp));
  }

  static void fireFrameWriteError(Channel parentChannel, Throwable t) {
    ChannelPipeline parentPipeline = parentChannel.pipeline();
    if (parentChannel.config().isAutoClose()) {
      parentPipeline.fireExceptionCaught(t);
      parentChannel.close();
      return;
    }
    if (t instanceof Exception) {
      parentPipeline.fireUserEventTriggered(
          new Http2WebSocketWriteErrorEvent(Http2WebSocketProtocol.MSG_WRITE_ERROR, t));
      return;
    }
    parentPipeline.fireExceptionCaught(t);
  }

  static void fireHandshakeValidationStartAndError(
      Channel parentChannel, int streamId, Http2Headers headers) {
    long timestamp = System.nanoTime();
    Http2WebSocketEvent.fireHandshakeStartAndError(
        parentChannel,
        streamId,
        nonNullString(headers.path()),
        nonNullString(headers.get(Http2WebSocketProtocol.HEADER_WEBSOCKET_SUBPROTOCOL_NAME)),
        headers,
        timestamp,
        timestamp,
        WebSocketHandshakeException.class.getName(),
        Http2WebSocketProtocol.MSG_HANDSHAKE_INVALID_REQUEST_HEADERS);
  }

  static void fireHandshakeStartAndError(
      Channel parentChannel,
      int serial,
      String path,
      String subprotocols,
      Http2Headers requestHeaders,
      long startNanos,
      long errorNanos,
      Throwable t) {
    ChannelPipeline parentPipeline = parentChannel.pipeline();
    if (t instanceof Exception) {
      parentPipeline.fireUserEventTriggered(
          new Http2WebSocketHandshakeStartEvent(
              serial, path, subprotocols, startNanos, requestHeaders));
      parentPipeline.fireUserEventTriggered(
          new WebSocketHandshakeStartEvent(serial, path, subprotocols, startNanos, requestHeaders));

      parentPipeline.fireUserEventTriggered(
          new Http2WebSocketHandshakeErrorEvent(serial, path, subprotocols, errorNanos, null, t));
      parentPipeline.fireUserEventTriggered(
          new WebSocketHandshakeErrorEvent(serial, path, subprotocols, errorNanos, null, t));
      return;
    }
    parentPipeline.fireExceptionCaught(t);
  }

  static void fireHandshakeStartAndError(
      Channel parentChannel,
      int serial,
      String path,
      String subprotocols,
      Http2Headers requestHeaders,
      long startNanos,
      long errorNanos,
      String errorName,
      String errorMessage) {
    ChannelPipeline parentPipeline = parentChannel.pipeline();

    parentPipeline.fireUserEventTriggered(
        new Http2WebSocketHandshakeStartEvent(
            serial, path, subprotocols, startNanos, requestHeaders));
    parentPipeline.fireUserEventTriggered(
        new WebSocketHandshakeStartEvent(serial, path, subprotocols, startNanos, requestHeaders));

    parentPipeline.fireUserEventTriggered(
        new Http2WebSocketHandshakeErrorEvent(
            serial, path, subprotocols, errorNanos, null, errorName, errorMessage));
    parentPipeline.fireUserEventTriggered(
        new WebSocketHandshakeErrorEvent(
            serial, path, subprotocols, errorNanos, null, errorName, errorMessage));
  }

  static void fireHandshakeStartAndSuccess(
      Http2WebSocketChannel webSocketChannel,
      int serial,
      String path,
      String subprotocols,
      String subprotocol,
      Http2Headers requestHeaders,
      Http2Headers responseHeaders,
      long startNanos,
      long successNanos) {
    ChannelPipeline parentPipeline = webSocketChannel.parent().pipeline();
    ChannelPipeline webSocketPipeline = webSocketChannel.pipeline();

    Http2WebSocketHandshakeStartEvent http2StartEvent =
        new Http2WebSocketHandshakeStartEvent(
            serial, path, subprotocols, startNanos, requestHeaders);
    WebSocketHandshakeStartEvent startEvent =
        new WebSocketHandshakeStartEvent(serial, path, subprotocols, startNanos, requestHeaders);
    Http2WebSocketHandshakeSuccessEvent http2SuccessEvent =
        new Http2WebSocketHandshakeSuccessEvent(
            serial, path, subprotocols, subprotocol, successNanos, responseHeaders);
    WebSocketHandshakeSuccessEvent successEvent =
        new WebSocketHandshakeSuccessEvent(
            serial, path, subprotocols, subprotocol, successNanos, responseHeaders);

    parentPipeline.fireUserEventTriggered(http2StartEvent);
    parentPipeline.fireUserEventTriggered(startEvent);
    parentPipeline.fireUserEventTriggered(http2SuccessEvent);
    parentPipeline.fireUserEventTriggered(successEvent);

    webSocketPipeline.fireUserEventTriggered(http2StartEvent);
    webSocketPipeline.fireUserEventTriggered(startEvent);
    webSocketPipeline.fireUserEventTriggered(http2SuccessEvent);
    webSocketPipeline.fireUserEventTriggered(successEvent);
  }

  static void fireHandshakeStart(
      Http2WebSocketChannel webSocketChannel, Http2Headers requestHeaders, long timestampNanos) {
    ChannelPipeline parentPipeline = webSocketChannel.parent().pipeline();
    ChannelPipeline webSocketPipeline = webSocketChannel.pipeline();
    int serial = webSocketChannel.serial();
    String path = webSocketChannel.path();
    String subprotocol = webSocketChannel.subprotocol();

    Http2WebSocketHandshakeStartEvent http2StartEvent =
        new Http2WebSocketHandshakeStartEvent(
            serial, path, subprotocol, timestampNanos, requestHeaders);

    WebSocketHandshakeStartEvent startEvent =
        new WebSocketHandshakeStartEvent(serial, path, subprotocol, timestampNanos, requestHeaders);

    parentPipeline.fireUserEventTriggered(http2StartEvent);
    parentPipeline.fireUserEventTriggered(startEvent);
    webSocketPipeline.fireUserEventTriggered(http2StartEvent);
    webSocketPipeline.fireUserEventTriggered(startEvent);
  }

  static void fireHandshakeError(
      Http2WebSocketChannel webSocketChannel,
      Http2Headers responseHeaders,
      long timestampNanos,
      Throwable t) {
    ChannelPipeline parentPipeline = webSocketChannel.parent().pipeline();

    if (t instanceof Exception) {
      ChannelPipeline webSocketPipeline = webSocketChannel.pipeline();
      String path = webSocketChannel.path();
      int serial = webSocketChannel.serial();
      String subprotocol = webSocketChannel.subprotocol();

      Http2WebSocketHandshakeErrorEvent http2ErrorEvent =
          new Http2WebSocketHandshakeErrorEvent(
              serial, path, subprotocol, timestampNanos, responseHeaders, t);

      WebSocketHandshakeErrorEvent errorEvent =
          new WebSocketHandshakeErrorEvent(
              serial, path, subprotocol, timestampNanos, responseHeaders, t);

      parentPipeline.fireUserEventTriggered(http2ErrorEvent);
      parentPipeline.fireUserEventTriggered(errorEvent);
      webSocketPipeline.fireUserEventTriggered(http2ErrorEvent);
      webSocketPipeline.fireUserEventTriggered(errorEvent);
      return;
    }
    parentPipeline.fireExceptionCaught(t);
  }

  static void fireHandshakeSuccess(
      Http2WebSocketChannel webSocketChannel, Http2Headers responseHeaders, long timestampNanos) {
    ChannelPipeline parentPipeline = webSocketChannel.parent().pipeline();
    ChannelPipeline webSocketPipeline = webSocketChannel.pipeline();
    String path = webSocketChannel.path();
    String subprotocol = webSocketChannel.subprotocol();
    int serial = webSocketChannel.serial();

    Http2WebSocketHandshakeSuccessEvent http2SuccessEvent =
        new Http2WebSocketHandshakeSuccessEvent(
            serial, path, subprotocol, subprotocol, timestampNanos, responseHeaders);

    WebSocketHandshakeSuccessEvent successEvent =
        new WebSocketHandshakeSuccessEvent(
            serial, path, subprotocol, subprotocol, timestampNanos, responseHeaders);

    parentPipeline.fireUserEventTriggered(http2SuccessEvent);
    parentPipeline.fireUserEventTriggered(successEvent);
    webSocketPipeline.fireUserEventTriggered(http2SuccessEvent);
    webSocketPipeline.fireUserEventTriggered(successEvent);
  }

  public Type type() {
    return type;
  }

  @SuppressWarnings("unchecked")
  public <T extends Http2WebSocketEvent> T cast() {
    return (T) this;
  }

  public enum Type {
    HANDSHAKE_START,
    HANDSHAKE_SUCCESS,
    HANDSHAKE_ERROR,
    CLOSE_LOCAL_ENDSTREAM,
    CLOSE_REMOTE_ENDSTREAM,
    CLOSE_REMOTE_RESET,
    CLOSE_REMOTE_GOAWAY,
    WEIGHT_UPDATE,
    WRITE_ERROR,
    WEBSOCKET_SUPPORTED
  }

  /**
   * Represents write error of frames that are not exposed to user code: HEADERS and RST_STREAM
   * frames sent by server on handshake, DATA frames with END_STREAM flag for graceful shutdown,
   * PRIORITY frames etc.
   */
  public static class Http2WebSocketWriteErrorEvent extends Http2WebSocketEvent {
    private final String message;
    private final Throwable cause;

    Http2WebSocketWriteErrorEvent(String message, Throwable cause) {
      super(Type.WRITE_ERROR);
      this.message = message;
      this.cause = cause;
    }

    /** @return frame write error message */
    public String errorMessage() {
      return message;
    }

    /** @return frame write error */
    public Throwable error() {
      return cause;
    }
  }

  /** Base type for websocket-over-http2 lifecycle events */
  public static class Http2WebSocketLifecycleEvent extends Http2WebSocketEvent {
    private final int id;
    private final String path;
    private final String subprotocols;
    private final long timestampNanos;

    Http2WebSocketLifecycleEvent(
        Type type, int id, String path, String subprotocols, long timestampNanos) {
      super(type);
      this.id = id;
      this.path = path;
      this.subprotocols = subprotocols;
      this.timestampNanos = timestampNanos;
    }

    /** @return id to correlate events of particular websocket */
    public int id() {
      return id;
    }

    /** @return websocket path */
    public String path() {
      return path;
    }

    /** @return websocket subprotocols */
    public String subprotocols() {
      return subprotocols;
    }

    /** @return event timestamp */
    public long timestampNanos() {
      return timestampNanos;
    }
  }

  /** websocket-over-http2 handshake start event */
  public static class Http2WebSocketHandshakeStartEvent extends Http2WebSocketLifecycleEvent {
    private final Http2Headers requestHeaders;

    Http2WebSocketHandshakeStartEvent(
        int id, String path, String subprotocol, long timestampNanos, Http2Headers requestHeaders) {
      super(Type.HANDSHAKE_START, id, path, subprotocol, timestampNanos);
      this.requestHeaders = requestHeaders;
    }

    /** @return websocket request headers */
    public Http2Headers requestHeaders() {
      return requestHeaders;
    }
  }

  /** websocket-over-http2 handshake error event */
  public static class Http2WebSocketHandshakeErrorEvent extends Http2WebSocketLifecycleEvent {
    private final Http2Headers responseHeaders;
    private final String errorName;
    private final String errorMessage;
    private final Throwable error;

    Http2WebSocketHandshakeErrorEvent(
        int id,
        String path,
        String subprotocols,
        long timestampNanos,
        Http2Headers responseHeaders,
        Throwable error) {
      this(id, path, subprotocols, timestampNanos, responseHeaders, error, null, null);
    }

    Http2WebSocketHandshakeErrorEvent(
        int id,
        String path,
        String subprotocols,
        long timestampNanos,
        Http2Headers responseHeaders,
        String errorName,
        String errorMessage) {
      this(id, path, subprotocols, timestampNanos, responseHeaders, null, errorName, errorMessage);
    }

    private Http2WebSocketHandshakeErrorEvent(
        int id,
        String path,
        String subprotocols,
        long timestampNanos,
        Http2Headers responseHeaders,
        Throwable error,
        String errorName,
        String errorMessage) {
      super(Type.HANDSHAKE_ERROR, id, path, subprotocols, timestampNanos);
      this.responseHeaders = responseHeaders;
      this.errorName = errorName;
      this.errorMessage = errorMessage;
      this.error = error;
    }

    /** @return response headers of failed websocket handshake */
    public Http2Headers responseHeaders() {
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

  /** websocket-over-http2 handshake success event */
  public static class Http2WebSocketHandshakeSuccessEvent extends Http2WebSocketLifecycleEvent {
    private final String subprotocol;
    private final Http2Headers responseHeaders;

    Http2WebSocketHandshakeSuccessEvent(
        int id,
        String path,
        String subprotocols,
        String subprotocol,
        long timestampNanos,
        Http2Headers responseHeaders) {
      super(Type.HANDSHAKE_SUCCESS, id, path, subprotocols, timestampNanos);
      this.subprotocol = subprotocol;
      this.responseHeaders = responseHeaders;
    }

    /** @return selected subprotocol of succeeded websocket handshake */
    public String subprotocol() {
      return subprotocol;
    }

    /** @return response headers of succeeded websocket handshake */
    public Http2Headers responseHeaders() {
      return responseHeaders;
    }
  }

  /**
   * websocket-over-http2 close by remote event. Graceful close is denoted by {@link
   * Type#CLOSE_REMOTE_ENDSTREAM}, forced close is denoted by {@link Type#CLOSE_REMOTE_RESET}
   */
  public static class Http2WebSocketRemoteCloseEvent extends Http2WebSocketLifecycleEvent {
    private Http2WebSocketRemoteCloseEvent(
        Type type, int id, String path, String subprotocols, long timestampNanos) {
      super(type, id, path, subprotocols, timestampNanos);
    }

    static Http2WebSocketRemoteCloseEvent endStream(
        int id, String path, String subprotocols, long timestampNanos) {
      return new Http2WebSocketRemoteCloseEvent(
          Type.CLOSE_REMOTE_ENDSTREAM, id, path, subprotocols, timestampNanos);
    }

    static Http2WebSocketRemoteCloseEvent reset(
        int id, String path, String subprotocols, long timestampNanos) {
      return new Http2WebSocketRemoteCloseEvent(
          Type.CLOSE_REMOTE_RESET, id, path, subprotocols, timestampNanos);
    }
  }

  /** graceful connection close by remote (GO_AWAY) event. */
  public static class Http2WebSocketRemoteGoAwayEvent extends Http2WebSocketLifecycleEvent {
    private final long errorCode;

    Http2WebSocketRemoteGoAwayEvent(
        int id, String path, String subprotocol, long timestampNanos, long errorCode) {
      super(Type.CLOSE_REMOTE_GOAWAY, id, path, subprotocol, timestampNanos);
      this.errorCode = errorCode;
    }

    /** @return received GO_AWAY frame error code */
    public long errorCode() {
      return errorCode;
    }
  }

  /**
   * websocket-over-http2 local graceful close event. Firing {@link
   * Http2WebSocketLocalCloseEvent#INSTANCE} on channel pipeline will close associated http2 stream
   * locally by sending empty DATA frame with END_STREAN flag set
   */
  public static final class Http2WebSocketLocalCloseEvent extends Http2WebSocketEvent {
    public static final Http2WebSocketLocalCloseEvent INSTANCE =
        new Http2WebSocketLocalCloseEvent();

    Http2WebSocketLocalCloseEvent() {
      super(Type.CLOSE_LOCAL_ENDSTREAM);
    }
  }

  /**
   * websocket-over-http2 stream weight update event. Firing {@link
   * Http2WebSocketLocalCloseEvent#INSTANCE} on channel pipeline will send PRIORITY frame for
   * associated http2 stream
   */
  public static final class Http2WebSocketStreamWeightUpdateEvent extends Http2WebSocketEvent {
    private final short streamWeight;

    Http2WebSocketStreamWeightUpdateEvent(short streamWeight) {
      super(Type.WEIGHT_UPDATE);
      this.streamWeight = Http2WebSocketProtocol.requireRange(streamWeight, 1, 256, "streamWeight");
    }

    public short streamWeight() {
      return streamWeight;
    }

    public static Http2WebSocketStreamWeightUpdateEvent create(short streamWeight) {
      return new Http2WebSocketStreamWeightUpdateEvent(streamWeight);
    }

    /**
     * @param webSocketChannel websocket-over-http2 channel
     * @return weight of http2 stream associated with websocket channel
     */
    @Nullable
    public static Short streamWeight(Channel webSocketChannel) {
      if (webSocketChannel instanceof Http2WebSocketChannel) {
        return ((Http2WebSocketChannel) webSocketChannel).streamWeightAttribute();
      }
      return null;
    }
  }

  /** websocket-over-http2 support event */
  public static final class Http2WebSocketSupportedEvent extends Http2WebSocketEvent {
    private final boolean webSocketSupported;
    private final long timestampNanos;

    Http2WebSocketSupportedEvent(boolean webSocketSupported, long timestampNanos) {
      super(Type.WEBSOCKET_SUPPORTED);
      this.webSocketSupported = webSocketSupported;
      this.timestampNanos = timestampNanos;
    }

    public boolean isWebSocketSupported() {
      return webSocketSupported;
    }

    public long timestampNanos() {
      return timestampNanos;
    }
  }

  private static String nonNullString(@Nullable CharSequence seq) {
    if (seq == null) {
      return "";
    }
    return seq.toString();
  }
}
