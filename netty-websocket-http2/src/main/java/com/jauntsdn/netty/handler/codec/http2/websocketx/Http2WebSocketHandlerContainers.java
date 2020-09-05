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

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler.*;

import io.netty.channel.ChannelHandler;
import java.util.HashMap;
import java.util.Map;

class Http2WebSocketHandlerContainers {

  static class EmptyHandlerContainer implements WebSocketHandler.Container {
    private static final EmptyHandlerContainer INSTANCE = new EmptyHandlerContainer();

    private EmptyHandlerContainer() {}

    public static EmptyHandlerContainer getInstance() {
      return INSTANCE;
    }

    @Override
    public void put(
        String path, String subprotocol, Http2WebSocketAcceptor acceptor, ChannelHandler handler) {
      throw new UnsupportedOperationException(
          "EmptyHandlerContainer does not support put() method");
    }

    @Override
    public WebSocketHandler get(String path, String subprotocol) {
      return null;
    }

    @Override
    public WebSocketHandler get(String path, String[] subprotocols) {
      return null;
    }
  }

  static class SingleHandlerContainer implements WebSocketHandler.Container, WebSocketHandler {
    private String path;
    private String subprotocol;
    private Http2WebSocketAcceptor acceptor;
    private ChannelHandler handler;

    public SingleHandlerContainer() {}

    @Override
    public void put(
        String path, String subprotocol, Http2WebSocketAcceptor acceptor, ChannelHandler handler) {
      if (this.path != null) {
        throw new IllegalStateException("only single ");
      }
      this.path = path;
      this.subprotocol = subprotocol;
      this.acceptor = acceptor;
      this.handler = handler;
    }

    @Override
    public WebSocketHandler get(String path, String subprotocol) {
      if (path.equals(this.path) && subprotocol.equals(this.subprotocol)) {
        return this;
      }
      return null;
    }

    @Override
    public WebSocketHandler get(String path, String[] subprotocols) {
      if (!path.equals(this.path)) {
        return null;
      }
      for (String subprotocol : subprotocols) {
        if (subprotocol.equals(this.subprotocol)) {
          return this;
        }
      }
      return null;
    }

    @Override
    public Http2WebSocketAcceptor acceptor() {
      return acceptor;
    }

    @Override
    public ChannelHandler handler() {
      return handler;
    }

    @Override
    public String subprotocol() {
      return subprotocol;
    }
  }

  static class DefaultHandlerContainer implements WebSocketHandler.Container {
    private final Map<Path, WebSocketHandler> webSocketHandlers;

    public DefaultHandlerContainer(int handlersCount) {
      /*todo use open addressing hashmap*/
      this.webSocketHandlers = new HashMap<>(handlersCount);
    }

    @Override
    public void put(
        String path, String subprotocol, Http2WebSocketAcceptor acceptor, ChannelHandler handler) {
      webSocketHandlers.put(
          new Path(path, subprotocol), new WebSocketHandler.Impl(acceptor, handler, subprotocol));
    }

    @Override
    public WebSocketHandler get(String path, String subprotocol) {
      return webSocketHandlers.get(new Path(path, subprotocol));
    }

    @Override
    public WebSocketHandler get(String path, String[] subprotocols) {
      Path p = null;
      for (String subprotocol : subprotocols) {
        if (p == null) {
          p = new Path(path, subprotocol);
        } else {
          p.subprotocol(subprotocol);
        }
        WebSocketHandler webSocketHandler = webSocketHandlers.get(p);
        if (webSocketHandler != null) {
          return webSocketHandler;
        }
      }
      return null;
    }

    private static class Path {
      private final String path;
      private String subprotocol;

      public Path(String path, String subprotocol) {
        this.path = path;
        this.subprotocol = subprotocol;
      }

      public Path subprotocol(String subprotocol) {
        this.subprotocol = subprotocol;
        return this;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Path p = (Path) o;

        if (!path.equals(p.path)) return false;
        return subprotocol.equals(p.subprotocol);
      }

      @Override
      public int hashCode() {
        int result = path.hashCode();
        result = 31 * result + subprotocol.hashCode();
        return result;
      }
    }
  }
}
