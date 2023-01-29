/*
 * Copyright 2022 - present Maksym Ostroverkhov.
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

import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameEncoder;

public interface Http1WebSocketCodec {

  WebSocketFrameEncoder encoder(boolean maskPayload);

  WebSocketFrameDecoder decoder(WebSocketDecoderConfig config);

  default WebSocketFrameDecoder decoder(boolean maskPayload, WebSocketDecoderConfig config) {
    return null;
  }

  void validate(boolean maskPayload, WebSocketDecoderConfig config);

  Http1WebSocketCodec DEFAULT =
      new Http1WebSocketCodec() {
        @Override
        public WebSocketFrameEncoder encoder(boolean maskPayload) {
          return new WebSocket13FrameEncoder(maskPayload);
        }

        @Override
        public WebSocketFrameDecoder decoder(WebSocketDecoderConfig config) {
          return new WebSocket13FrameDecoder(config);
        }

        @Override
        public void validate(boolean maskPayload, WebSocketDecoderConfig config) {}
      };
}
