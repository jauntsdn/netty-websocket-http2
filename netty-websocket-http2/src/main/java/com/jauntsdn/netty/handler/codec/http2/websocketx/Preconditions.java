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
import io.netty.channel.ChannelHandler;

class Preconditions {
  static <T> T requireNonNull(T t, String message) {
    if (t == null) {
      throw new IllegalArgumentException(message + " must be non null");
    }
    return t;
  }

  static String requireNonEmpty(String string, String message) {
    if (string == null || string.isEmpty()) {
      throw new IllegalArgumentException(message + " must be non empty");
    }
    return string;
  }

  static <T extends ChannelHandler> T requireHandler(Channel channel, Class<T> handler) {
    T h = channel.pipeline().get(handler);
    if (h == null) {
      throw new IllegalArgumentException(
          handler.getSimpleName() + " is absent in the channel pipeline");
    }
    return h;
  }

  static long requirePositive(long value, String message) {
    if (value <= 0) {
      throw new IllegalArgumentException(message + " must be positive: " + value);
    }
    return value;
  }

  static int requireNonNegative(int value, String message) {
    if (value < 0) {
      throw new IllegalArgumentException(message + " must be non-negative: " + value);
    }
    return value;
  }

  static short requireRange(int value, int from, int to, String message) {
    if (value >= from && value <= to) {
      return (short) value;
    }
    throw new IllegalArgumentException(
        String.format("%s must belong to range [%d, %d]: ", message, from, to));
  }
}
