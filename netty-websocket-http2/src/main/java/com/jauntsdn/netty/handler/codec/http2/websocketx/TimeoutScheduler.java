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

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public interface TimeoutScheduler {

  /**
   * @param runnable scheduled action. Must be non-null
   * @param delay timeout delay. Must be non-negative
   * @param timeUnit timeout time unit. Must be non-null
   * @param executor executor associated with connection channel event loop
   * @return timeout cancellation handle. Must be non-null.
   */
  Handle schedule(Runnable runnable, long delay, TimeUnit timeUnit, Executor executor);

  /** Scheduled timeout cancellation handle */
  interface Handle {

    void cancel();
  }
}
