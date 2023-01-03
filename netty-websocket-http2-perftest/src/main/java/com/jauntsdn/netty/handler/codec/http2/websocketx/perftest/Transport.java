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

package com.jauntsdn.netty.handler.codec.http2.websocketx.perftest;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class Transport {
  static final boolean isEpollAvailable;
  static final boolean isKqueueAvailable;

  static {
    boolean available;
    try {
      Class.forName("io.netty.channel.epoll.Epoll");
      available = Epoll.isAvailable();
    } catch (ClassNotFoundException e) {
      available = false;
    }
    isEpollAvailable = available;

    try {
      Class.forName("io.netty.channel.kqueue.KQueue");
      available = KQueue.isAvailable();
    } catch (ClassNotFoundException e) {
      available = false;
    }
    isKqueueAvailable = available;
  }

  private final String type;
  private final Class<? extends Channel> clientChannel;
  private final Class<? extends ServerChannel> serverChannel;
  private final EventLoopGroup eventLoopGroup;

  public static boolean isEpollAvailable() {
    return isEpollAvailable;
  }

  public static boolean isKqueueAvailable() {
    return isKqueueAvailable;
  }

  public static Transport get(boolean isNative) {
    int threadCount = Runtime.getRuntime().availableProcessors() * 2;
    if (isNative) {
      if (isEpollAvailable()) {
        return new Transport(
            "epoll",
            EpollSocketChannel.class,
            EpollServerSocketChannel.class,
            new EpollEventLoopGroup(threadCount));
      }
      if (isKqueueAvailable()) {
        return new Transport(
            "kqueue",
            KQueueSocketChannel.class,
            KQueueServerSocketChannel.class,
            new KQueueEventLoopGroup(threadCount));
      }
    }
    return new Transport(
        "nio",
        NioSocketChannel.class,
        NioServerSocketChannel.class,
        new NioEventLoopGroup(threadCount));
  }

  Transport(
      String type,
      Class<? extends Channel> clientChannel,
      Class<? extends ServerChannel> serverChannel,
      EventLoopGroup eventLoopGroup) {
    this.type = type;
    this.clientChannel = clientChannel;
    this.serverChannel = serverChannel;
    this.eventLoopGroup = eventLoopGroup;
  }

  public Class<? extends Channel> clientChannel() {
    return clientChannel;
  }

  public Class<? extends ServerChannel> serverChannel() {
    return serverChannel;
  }

  public EventLoopGroup eventLoopGroup() {
    return eventLoopGroup;
  }

  public String type() {
    return type;
  }

  @Override
  public String toString() {
    return "Transport{" + "type='" + type + '\'' + '}';
  }
}
