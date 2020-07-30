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

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.Http2Headers;
import javax.annotation.Nullable;

public abstract class Http2WebSocketEvent {
  private final Type type;

  Http2WebSocketEvent(Type type) {
    this.type = type;
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
    CLOSE_LOCAL,
    CLOSE_REMOTE,
    WEIGHT_UPDATE
  }

  public static class Http2WebSocketHandshakeEvent extends Http2WebSocketEvent {
    private final String id;
    private final String path;
    private final long timestampNanos;

    Http2WebSocketHandshakeEvent(Type type, String id, String path, long timestampNanos) {
      super(type);
      this.id = id;
      this.path = path;
      this.timestampNanos = timestampNanos;
    }

    public String id() {
      return id;
    }

    public String path() {
      return path;
    }

    public long timestampNanos() {
      return timestampNanos;
    }

    static void fireStartAndError(
        Channel parentChannel,
        String serial,
        String path,
        Http2Headers requestHeaders,
        long startNanos,
        long errorNanos,
        Throwable t) {
      ChannelPipeline parentPipeline = parentChannel.pipeline();

      parentPipeline.fireUserEventTriggered(
          new Http2WebSocketHandshakeStartEvent(serial, path, startNanos, requestHeaders));

      parentPipeline.fireUserEventTriggered(
          new Http2WebSocketHandshakeErrorEvent(serial, path, errorNanos, null, t));
    }

    static void fireStartAndError(
        Channel parentChannel,
        String serial,
        String path,
        Http2Headers requestHeaders,
        long startNanos,
        long errorNanos,
        String errorName,
        String errorMessage) {
      ChannelPipeline parentPipeline = parentChannel.pipeline();

      parentPipeline.fireUserEventTriggered(
          new Http2WebSocketHandshakeStartEvent(serial, path, startNanos, requestHeaders));

      parentPipeline.fireUserEventTriggered(
          new Http2WebSocketHandshakeErrorEvent(
              serial, path, errorNanos, null, errorName, errorMessage));
    }

    static void fireStartAndSuccess(
        Http2WebSocketChannel webSocketChannel,
        String serial,
        String path,
        Http2Headers requestHeaders,
        Http2Headers responseHeaders,
        long startNanos,
        long successNanos) {
      ChannelPipeline parentPipeline = webSocketChannel.parent().pipeline();
      ChannelPipeline webSocketPipeline = webSocketChannel.pipeline();

      Http2WebSocketHandshakeStartEvent startEvent =
          new Http2WebSocketHandshakeStartEvent(serial, path, startNanos, requestHeaders);
      Http2WebSocketHandshakeSuccessEvent successEvent =
          new Http2WebSocketHandshakeSuccessEvent(serial, path, successNanos, responseHeaders);

      parentPipeline.fireUserEventTriggered(startEvent);
      parentPipeline.fireUserEventTriggered(successEvent);

      webSocketPipeline.fireUserEventTriggered(startEvent);
      webSocketPipeline.fireUserEventTriggered(successEvent);
    }

    static void fireStart(
        Http2WebSocketChannel webSocketChannel, Http2Headers requestHeaders, long timestampNanos) {
      ChannelPipeline parentPipeline = webSocketChannel.parent().pipeline();
      ChannelPipeline webSocketPipeline = webSocketChannel.pipeline();

      Http2WebSocketHandshakeStartEvent startEvent =
          new Http2WebSocketHandshakeStartEvent(
              webSocketChannel.serial(), webSocketChannel.path(), timestampNanos, requestHeaders);

      parentPipeline.fireUserEventTriggered(startEvent);
      webSocketPipeline.fireUserEventTriggered(startEvent);
    }

    static void fireError(
        Http2WebSocketChannel webSocketChannel,
        Http2Headers responseHeaders,
        long timestampNanos,
        Throwable cause) {
      String path = webSocketChannel.path();
      ChannelPipeline parentPipeline = webSocketChannel.parent().pipeline();
      ChannelPipeline webSocketPipeline = webSocketChannel.pipeline();

      Http2WebSocketHandshakeErrorEvent errorEvent =
          new Http2WebSocketHandshakeErrorEvent(
              webSocketChannel.serial(), path, timestampNanos, responseHeaders, cause);

      parentPipeline.fireUserEventTriggered(errorEvent);
      webSocketPipeline.fireUserEventTriggered(errorEvent);
    }

    static void fireSuccess(
        Http2WebSocketChannel webSocketChannel, Http2Headers responseHeaders, long timestampNanos) {
      String path = webSocketChannel.path();
      ChannelPipeline parentPipeline = webSocketChannel.parent().pipeline();
      ChannelPipeline webSocketPipeline = webSocketChannel.pipeline();

      Http2WebSocketHandshakeSuccessEvent successEvent =
          new Http2WebSocketHandshakeSuccessEvent(
              webSocketChannel.serial(), path, timestampNanos, responseHeaders);

      parentPipeline.fireUserEventTriggered(successEvent);
      webSocketPipeline.fireUserEventTriggered(successEvent);
    }
  }

  public static class Http2WebSocketHandshakeStartEvent extends Http2WebSocketHandshakeEvent {
    private final Http2Headers requestHeaders;

    Http2WebSocketHandshakeStartEvent(
        String id, String path, long timestampNanos, Http2Headers requestHeaders) {
      super(Type.HANDSHAKE_START, id, path, timestampNanos);
      this.requestHeaders = requestHeaders;
    }

    public Http2Headers requestHeaders() {
      return requestHeaders;
    }
  }

  public static class Http2WebSocketHandshakeErrorEvent extends Http2WebSocketHandshakeEvent {
    private final Http2Headers responseHeaders;
    private final String errorName;
    private final String errorMessage;
    private final Throwable error;

    Http2WebSocketHandshakeErrorEvent(
        String id,
        String path,
        long timestampNanos,
        Http2Headers responseHeaders,
        Throwable error) {
      this(id, path, timestampNanos, responseHeaders, error, null, null);
    }

    Http2WebSocketHandshakeErrorEvent(
        String id,
        String path,
        long timestampNanos,
        Http2Headers responseHeaders,
        String errorName,
        String errorMessage) {
      this(id, path, timestampNanos, responseHeaders, null, errorName, errorMessage);
    }

    private Http2WebSocketHandshakeErrorEvent(
        String id,
        String path,
        long timestampNanos,
        Http2Headers responseHeaders,
        Throwable error,
        String errorName,
        String errorMessage) {
      super(Type.HANDSHAKE_ERROR, id, path, timestampNanos);
      this.responseHeaders = responseHeaders;
      this.errorName = errorName;
      this.errorMessage = errorMessage;
      this.error = error;
    }

    public Http2Headers responseHeaders() {
      return responseHeaders;
    }

    public Throwable error() {
      return error;
    }

    public String errorName() {
      return errorName;
    }

    public String errorMessage() {
      return errorMessage;
    }
  }

  public static class Http2WebSocketHandshakeSuccessEvent extends Http2WebSocketHandshakeEvent {
    private final Http2Headers responseHeaders;

    Http2WebSocketHandshakeSuccessEvent(
        String id, String path, long timestampNanos, Http2Headers responseHeaders) {
      super(Type.HANDSHAKE_SUCCESS, id, path, timestampNanos);
      this.responseHeaders = responseHeaders;
    }

    public Http2Headers responseHeaders() {
      return responseHeaders;
    }
  }

  public static class Http2WebSocketRemoteCloseEvent extends Http2WebSocketHandshakeEvent {

    Http2WebSocketRemoteCloseEvent(String id, String path, long timestampNanos) {
      super(Type.CLOSE_REMOTE, id, path, timestampNanos);
    }
  }

  public static final class Http2WebSocketLocalCloseEvent extends Http2WebSocketEvent {
    public static final Http2WebSocketLocalCloseEvent INSTANCE =
        new Http2WebSocketLocalCloseEvent();

    Http2WebSocketLocalCloseEvent() {
      super(Type.CLOSE_LOCAL);
    }
  }

  public static final class Http2WebSocketStreamWeightUpdateEvent extends Http2WebSocketEvent {
    private final short streamWeight;

    Http2WebSocketStreamWeightUpdateEvent(short streamWeight) {
      super(Type.WEIGHT_UPDATE);
      this.streamWeight = Preconditions.requireRange(streamWeight, 1, 256, "streamWeight");
    }

    public short streamWeight() {
      return streamWeight;
    }

    public static Http2WebSocketStreamWeightUpdateEvent create(short streamWeight) {
      return new Http2WebSocketStreamWeightUpdateEvent(streamWeight);
    }

    @Nullable
    public static Short streamWeight(Channel webSocketChannel) {
      if (webSocketChannel instanceof Http2WebSocketChannel) {
        return ((Http2WebSocketChannel) webSocketChannel).streamWeightAttribute();
      }
      return null;
    }
  }
}
