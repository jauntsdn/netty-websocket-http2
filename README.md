![Maven Central](https://img.shields.io/maven-central/v/com.jauntsdn.netty/netty-websocket-http2)
[![Build](https://github.com/jauntsdn/netty-websocket-http2/actions/workflows/ci-build.yml/badge.svg)](https://github.com/jauntsdn/netty-websocket-http2/actions/workflows/ci-build.yml)
# netty-websocket-http2

Netty based implementation of [rfc8441](https://tools.ietf.org/html/rfc8441) - bootstrapping websockets with http/2

Library addresses 2 use cases: for application servers and clients, 
It is transparent use of existing http1 websocket handlers on top of http2 streams; for gateways/proxies, 
It is websockets-over-http2 support with no http1 dependencies and minimal overhead.

[https://jauntsdn.com/post/netty-websocket-http2/](https://jauntsdn.com/post/netty-websocket-http2/)

### websocket channel API  
Intended for application servers and clients.  
Allows transparent application of existing http1 websocket handlers on top of http2 stream.  

* Server
```groovy
EchoWebSocketHandler http1WebSocketHandler = new EchoWebSocketHandler();

 Http2WebSocketServerHandler http2webSocketHandler =
       Http2WebSocketServerBuilder.create()
              .acceptor(
                   (ctx, path, subprotocols, request, response) -> {
                     switch (path) {
                       case "/echo":
                         if (subprotocols.contains("echo.jauntsdn.com")
                             && acceptUserAgent(request, response)) {
                           /*selecting subprotocol for accepted requests is mandatory*/
                           Http2WebSocketAcceptor.Subprotocol
                                  .accept("echo.jauntsdn.com", response);
                           return ctx.executor()
                                  .newSucceededFuture(http1WebSocketHandler);
                         }
                         break;
                       case "/echo_all":
                         if (subprotocols.isEmpty() 
                                  && acceptUserAgent(request, response)) {
                           return ctx.executor()
                                  .newSucceededFuture(http1WebSocketHandler);
                         }
                         break;
                     }
                     return ctx.executor()
                         .newFailedFuture(
                             new WebSocketHandshakeException(
                                  "websocket rejected, path: " + path));
                   })
              .build();

      ch.pipeline()
           .addLast(sslHandler, 
                    http2frameCodec, 
                    http2webSocketHandler);
```  

* Client
```groovy
 Channel channel =
        new Bootstrap()
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {

                    Http2WebSocketClientHandler http2WebSocketClientHandler =
                        Http2WebSocketClientBuilder.create()
                            .handshakeTimeoutMillis(15_000)
                            .build();

                    ch.pipeline()
                        .addLast(
                            sslHandler,
                            http2FrameCodec,
                            http2WebSocketClientHandler);
                  }
                })
            .connect(address)
            .sync()
            .channel();

Http2WebSocketClientHandshaker handShaker = Http2WebSocketClientHandshaker.create(channel);

Http2Headers headers =
   new DefaultHttp2Headers().set("user-agent", "jauntsdn-websocket-http2-client/1.1.6");
ChannelFuture handshakeFuture =
   /*http1 websocket handler*/
   handShaker.handshake("/echo", headers, new EchoWebSocketHandler());
    
handshakeFuture.channel().writeAndFlush(new TextWebSocketFrame("hello http2 websocket"));
```
Successfully handshaked http2 stream spawns websocket subchannel, with provided  
http1 websocket handlers on its pipeline.

Runnable demo is available in `netty-websocket-http2-example` module - 
[channelserver](https://github.com/jauntsdn/netty-websocket-http2/blob/develop/netty-websocket-http2-example/src/main/java/com/jauntsdn/netty/handler/codec/http2/websocketx/example/channelserver/Main.java), 
[channelclient](https://github.com/jauntsdn/netty-websocket-http2/blob/develop/netty-websocket-http2-example/src/main/java/com/jauntsdn/netty/handler/codec/http2/websocketx/example/channelclient/Main.java).

### websocket handshake only API
Intended for intermediaries/proxies.   
Only verifies whether http2 stream is valid websocket, then passes it down the pipeline as `POST` request with `x-protocol=websocket` header.
 
```groovy
      Http2WebSocketServerHandler http2webSocketHandler =
          Http2WebSocketServerBuilder.buildHandshakeOnly();

      Http2StreamsHandler http2StreamsHandler = new Http2StreamsHandler();
      ch.pipeline()
           .addLast(sslHandler, 
                    frameCodec, 
                    http2webSocketHandler, 
                    http2StreamsHandler);
``` 

Works with both callbacks-style `Http2ConnectionHandler` and frames based `Http2FrameCodec`.      

```
Http2WebSocketServerBuilder.buildHandshakeOnly();
```
 
Runnable demo is available in `netty-websocket-http2-example` module - 
[handshakeserver](https://github.com/jauntsdn/netty-websocket-http2/blob/develop/netty-websocket-http2-example/src/main/java/com/jauntsdn/netty/handler/codec/http2/websocketx/example/handshakeserver/Main.java), 
[channelclient](https://github.com/jauntsdn/netty-websocket-http2/blob/develop/netty-websocket-http2-example/src/main/java/com/jauntsdn/netty/handler/codec/http2/websocketx/example/channelclient/Main.java).

### configuration
Initial settings of server http2 codecs (`Http2ConnectionHandler` or `Http2FrameCodec`)  should contain [SETTINGS_ENABLE_CONNECT_PROTOCOL=1](https://tools.ietf.org/html/rfc8441#section-9.1)
parameter to advertise websocket-over-http2 support.

Also server http2 codecs must disable built-in headers validation because It is not compatible
with rfc8441 due to newly introduced `:protocol` pseudo-header. All websocket handlers provided by this library
do headers validation on their own - both for websocket and non-websocket requests.

Above configuration may be done with utility methods of `Http2WebSocketServerBuilder`

```
public static Http2FrameCodecBuilder configureHttp2Server(
                                          Http2FrameCodecBuilder http2Builder);

public static Http2ConnectionHandlerBuilder configureHttp2Server(
                                          Http2ConnectionHandlerBuilder http2Builder)
```    

### compression & subprotocols
Client and server `permessage-deflate` compression configuration is shared by all streams
```groovy
Http2WebSocketServerBuilder.compression(enabled);
```
or
```groovy
Http2WebSocketServerBuilder.compression(
      compressionLevel,
      allowServerWindowSize,
      preferredClientWindowSize,
      allowServerNoContext,
      preferredClientNoContext);
``` 
Client subprotocols are configured on per-path basis
```groovy
EchoWebSocketHandler http1WebsocketHandler = new EchoWebSocketHandler();
ChannelFuture handshake =
        handShaker.handshake("/echo", "subprotocol", headers, http1WebsocketHandler);
``` 
On a server It is responsibility of `Http2WebSocketAcceptor` to select supported subprotocol with
```groovy
Http2WebSocketAcceptor.Subprotocol.accept(subprotocol, response);
```
### lifecycle 

Handshake events and several shutdown options are available when 
using `Websocket channel` style APIs.  

#### handshake events  

Events are fired on parent channel, also on websocket channel if one gets created  
* `Http2WebSocketHandshakeStartEvent(websocketId, path, subprotocols, timestampNanos, requestHeaders)`
* `Http2WebSocketHandshakeErrorEvent(webSocketId, path, subprotocols, timestampNanos, responseHeaders, error)`
* `Http2WebSocketHandshakeSuccessEvent(webSocketId, path, subprotocols, timestampNanos, responseHeaders)`

#### close events

Outbound `Http2WebSocketLocalCloseEvent` on websocket channel pipeline closes
http2 stream by sending empty `DATA` frame with `END_STREAM` flag set.

Graceful and `RST` stream shutdown by remote endpoint is represented with inbound `Http2WebSocketRemoteCloseEvent` 
(with type `CLOSE_REMOTE_ENDSTREAM` and `CLOSE_REMOTE_RESET` respectively)  on websocket channel pipeline. 

Graceful connection shutdown by remote with `GO_AWAY` frame is represented by inbound `Http2WebSocketRemoteGoAwayEvent` 
on websocket channel pipeline.  

#### shutdown

Closing websocket channel terminates its http2 stream by sending `RST` frame.

#### validation & write error events

Both API style handlers send `Http2WebSocketHandshakeErrorEvent` for invalid websocket-over-http2 and http requests.
For http2 frame write errors `Http2WebSocketWriteErrorEvent` is sent on parent channel if auto-close is not enabled;
otherwise exception is delivered with `ChannelPipeline.fireExceptionCaught` followed by immediate close.

### flow control

Inbound flow control is done automatically as soon as `DATA` frames are received. 
Library relies on netty's `DefaultHttp2LocalFlowController` for refilling receive window.

Outbound flow control is expressed as websocket channels writability change on send window
exhaust/refill, provided by `DefaultHttp2RemoteFlowController`.

### websocket stream weight

Initial stream weight is configured with 

```groovy
Http2WebSocketClientBuilder.streamWeight(weight);
```
it can be updated by firing `Http2WebSocketStreamWeightUpdateEvent` on websocket channel pipeline.

### performance

Library relies on capabilities provided by netty's `Http2ConnectionHandler` so performance characteristics should be similar.
[netty-websocket-http2-perftest](https://github.com/jauntsdn/netty-websocket-http2/tree/develop/netty-websocket-http2-perftest/src/main/java/com/jauntsdn/netty/handler/codec/http2/websocketx/perftest) 
module contains application that gives rough throughput/latency estimate. The application is started with `perf_server.sh`, `perf_client.sh`. 

On modern box one can expect following results for single websocket:

```properties
19:31:58.537 epollEventLoopGroup-2-1 com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.client.Main p50 => 435 micros
19:31:58.537 epollEventLoopGroup-2-1 com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.client.Main p95 => 662 micros
19:31:58.537 epollEventLoopGroup-2-1 com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.client.Main p99 => 841 micros
19:31:58.537 epollEventLoopGroup-2-1 com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.client.Main throughput => 205874 messages
19:31:58.537 epollEventLoopGroup-2-1 com.jauntsdn.netty.handler.codec.http2.websocketx.perftest.client.Main throughput => 201048.83 kbytes

```

To evaluate performance with multiple connections we compose an application comprised with simple echo server, and client
sending batches of messages periodically over single websocket per connection (approximately models chat application)  

With 25k active connections each sending batches of 5-10 messages of 0.2-0.5 KBytes over single websocket every 15-30seconds,
the results are as follows (measured over time spans of 5 seconds): 
```properties
11:32:44.080 pool-2-thread-1 com.jauntsdn.netty.handler.codec.http2.websocketx.stresstest.client.Main connection success   ==> 25000
11:32:44.080 pool-2-thread-1 com.jauntsdn.netty.handler.codec.http2.websocketx.stresstest.client.Main handshake success    ==> 25000
11:32:44.080 pool-2-thread-1 com.jauntsdn.netty.handler.codec.http2.websocketx.stresstest.client.Main messages p99, micros ==> 177
11:32:44.080 pool-2-thread-1 com.jauntsdn.netty.handler.codec.http2.websocketx.stresstest.client.Main messages p50, micros ==> 91
```

### examples

`netty-websocket-http2-example` module contains demos showcasing both API styles, with this library/browser as clients.
 
* `channelserver, channelclient` packages for websocket subchannel API demos. 
* `handshakeserver, channelclient` packages for handshake only API demo.
* `multiprotocolserver, multiprotocolclient` packages for demo of server handling htt1/http2 websockets on the same port.
* `lwsclient` package for client demo that runs against [https://libwebsockets.org/testserver/](https://libwebsockets.org/testserver/) which hosts websocket-over-http2
server implemented with [libwebsockets](https://github.com/warmcat/libwebsockets) - popular C-based networking library. 

### browser example
`Channelserver` example serves web page at `https://www.localhost:8099` that sends pings to `/echo` endpoint.   

Currently Google Chrome, Mozilla Firefox and Microsoft Edge support websockets-over-http2.

### build & binaries
```
./gradlew
```

Releases are published on MavenCentral
```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.jauntsdn.netty:netty-websocket-http2:1.1.6'
}
```

## LICENSE

Copyright 2020-Present Maksym Ostroverkhov.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
