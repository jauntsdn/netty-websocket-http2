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

import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class HeadersValidatorTest {

  @Test
  void webSocketRequest() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(validWebSocketRequestHeaders(), false))
        .isTrue();
  }

  @Test
  void webSocketRequestWithEndStream() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(validWebSocketRequestHeaders(), true))
        .isFalse();
  }

  @Test
  void webSocketRequestWithEmptyScheme() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(
                validWebSocketRequestHeaders().scheme(asciiString("")), false))
        .isFalse();
  }

  @Test
  void webSocketRequestWithNonHttpScheme() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(
                validWebSocketRequestHeaders().scheme(asciiString("ftp")), false))
        .isFalse();
  }

  @Test
  void webSocketRequestWithEmptyAuthority() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(
                validWebSocketRequestHeaders().authority(asciiString("")), false))
        .isFalse();
  }

  @Test
  void webSocketRequestWithSubcomponentAuthority() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(
                validWebSocketRequestHeaders().authority(asciiString("test@localhost")), false))
        .isFalse();
  }

  @Test
  void webSocketRequestWithEmptyPath() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(
                validWebSocketRequestHeaders().path(asciiString("")), false))
        .isFalse();
  }

  @Test
  void webSocketRequestWithInvalidPseudoHeader() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(
                validWebSocketRequestHeaders().add(asciiString(":status"), asciiString("200")),
                false))
        .isFalse();
  }

  @Test
  void webSocketRequestWithInvalidHeader() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(
                validWebSocketRequestHeaders()
                    .add(asciiString("connection"), asciiString("keep-alive")),
                false))
        .isFalse();
  }

  @Test
  void webSocketRequestWithInvalidTeHeader() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(
                validWebSocketRequestHeaders().add(asciiString("te"), asciiString("gzip")), false))
        .isFalse();
  }

  @Test
  void webSocketRequestWithValidTeHeader() {
    Assertions.assertThat(
            Http2WebSocketValidator.WebSocket.isValid(
                validWebSocketRequestHeaders().add(asciiString("te"), asciiString("trailers")),
                false))
        .isTrue();
  }

  @Test
  void webSocketResponse() {
    Assertions.assertThat(Http2WebSocketValidator.isValid(validResponseHeaders())).isTrue();
  }

  @Test
  void webSocketResponseWithEmptyStatus() {
    Assertions.assertThat(
            Http2WebSocketValidator.isValid(validResponseHeaders().status(asciiString(""))))
        .isFalse();
  }

  @Test
  void webSocketResponseWithoutStatus() {
    Http2Headers headers = validResponseHeaders();
    headers.remove(asciiString(":status"));

    Assertions.assertThat(Http2WebSocketValidator.isValid(headers)).isFalse();
  }

  @Test
  void webSocketResponseAdditionalPseudoHeader() {
    Assertions.assertThat(
            Http2WebSocketValidator.isValid(validResponseHeaders().path(asciiString("/"))))
        .isFalse();
  }

  @Test
  void webSocketResponseUnexpectedPseudoHeader() {
    Http2Headers headers = validResponseHeaders();
    headers.remove(asciiString(":status"));
    headers.path(asciiString("/"));
    Assertions.assertThat(Http2WebSocketValidator.isValid(headers)).isFalse();
  }

  @Test
  void httpRequest() {
    Assertions.assertThat(Http2WebSocketValidator.Http.isValid(validHttpRequestHeaders(), false))
        .isTrue();
    Assertions.assertThat(Http2WebSocketValidator.Http.isValid(validHttpRequestHeaders(), true))
        .isTrue();
  }

  @Test
  void httpRequestWithEmptyAuthority() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().authority(asciiString("")), false))
        .isFalse();
  }

  @Test
  void httpRequestWithSubcomponentAuthority() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().authority(asciiString("test@localhost")), false))
        .isFalse();
  }

  @Test
  void httpRequestWithEmptyMethod() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().method(asciiString("")), false))
        .isFalse();
  }

  @Test
  void httpRequestWithEmptyScheme() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().scheme(asciiString("")), false))
        .isFalse();
  }

  @Test
  void httpRequestWithConnectMethod() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().method(asciiString("connect")), false))
        .isFalse();

    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders()
                    .method(asciiString("connect"))
                    .scheme(asciiString(""))
                    .path(asciiString("")),
                false))
        .isTrue();
  }

  @Test
  void httpRequestWithEmptyPath() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().path(asciiString("")), false))
        .isFalse();
  }

  @Test
  void nonHttpRequestWithEmptyPath() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().scheme(asciiString("ftp")).path(asciiString("")), false))
        .isTrue();
  }

  @Test
  void httpRequestWithInvalidPseudoHeader() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().add(asciiString(":status"), asciiString("200")), false))
        .isFalse();
  }

  @Test
  void httpRequestWithInvalidHeader() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().set(asciiString("connection"), asciiString("keep-alive")),
                false))
        .isFalse();
  }

  @Test
  void httpRequestWithInvalidTeHeader() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().set(asciiString("te"), asciiString("gzip")), false))
        .isFalse();
  }

  @Test
  void httpRequestWithValidTeHeader() {
    Assertions.assertThat(
            Http2WebSocketValidator.Http.isValid(
                validHttpRequestHeaders().set(asciiString("te"), asciiString("trailers")), false))
        .isTrue();
  }

  private static CharSequence asciiString(String s) {
    return AsciiString.of(s);
  }

  private static Http2Headers validWebSocketRequestHeaders() {
    return new DefaultHttp2Headers()
        .method(asciiString("connect"))
        .set(asciiString(":protocol"), asciiString("websocket"))
        .scheme(asciiString("https"))
        .authority(asciiString("localhost"))
        .path(asciiString("/"));
  }

  private static Http2Headers validHttpRequestHeaders() {
    return new DefaultHttp2Headers()
        .method(asciiString("get"))
        .scheme(asciiString("https"))
        .authority(asciiString("localhost"))
        .path(asciiString("/"));
  }

  private static Http2Headers validResponseHeaders() {
    return new DefaultHttp2Headers()
        .status(asciiString("200"))
        .set(asciiString("user-agent"), asciiString("test"));
  }
}
