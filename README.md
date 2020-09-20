[![Build Status](https://travis-ci.org/jauntsdn/netty-websocket-http2.svg?branch=develop)](https://travis-ci.org/jauntsdn/netty-websocket-http2)
![Maven Central](https://img.shields.io/maven-central/v/com.jauntsdn.netty/netty-websocket-http2)

# netty-websocket-http2

Netty based implementation of [rfc8441](https://tools.ietf.org/html/rfc8441) - bootstrapping websockets with http/2

Library is addressing 2 use cases: for application servers and clients, 
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
       Http2WebSocketServerHandler.builder()
              .handler("/echo", http1WebSocketHandler)
              .build();

      ch.pipeline()
           .addLast(sslHandler, 
                    http2frameCodec, 
                    http2webSocketHandler);
```  

Server websocket handler can be accompanied by `Http2WebSocketAcceptor`
```groovy
Http2WebSocketServerHandler.builder()
   .handler("/echo", new EchoWebsocketAcceptor(), http1WebSocketHandler);
```
```groovy
interface Http2WebSocketAcceptor {

  ChannelFuture accept(ChannelHandlerContext context,
                       Http2Headers request, 
                       Http2Headers response);
}
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
                        Http2WebSocketClientHandler.builder()
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
   new DefaultHttp2Headers().set("user-agent", "jauntsdn-websocket-http2-client/0.1.0");
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
Only verifies whether http2 stream is valid websocket, then passes it down the pipeline. 
```groovy
      Http2WebSocketServerHandler http2webSocketHandler =
          Http2WebSocketServerHandler.builder().handshakeOnly();

      Http2StreamsHandler http2StreamsHandler = new Http2StreamsHandler();
      ch.pipeline()
           .addLast(sslHandler, 
                    frameCodec, 
                    http2webSocketHandler, 
                    http2StreamsHandler);
``` 

Works with both callbacks-style `Http2ConnectionHandler` and frames based `Http2FrameCodec`.      

```
Http2WebSocketServerHandler.builder().handshakeOnly(rejectedWebSocketListener);
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
Server/client subprotocols are configured on per-path basis
```groovy
ChannelFuture handshake =
        handShaker.handshake("/echo", "subprotocol", headers, new EchoWebSocketHandler());
``` 
```groovy
Http2WebSocketServerHandler.builder()
              .handler("/echo", "subprotocol", http1WebSocketHandler)
              .handler("/echo", "wamp", http1WebSocketWampHandler);
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
Currently blocked by [netty bug](https://github.com/netty/netty/issues/10416). 
 
### examples

`netty-websocket-http2-example` module contains demos showcasing both API styles, 
with this library/browser as clients. 
* `channelserver, channelclient` packages for websocket subchannel API demos. 
* `handshakeserver, channelclient` packages for handshake only API demo.
* `lwsclient` package for client demo that runs against [https://libwebsockets.org/testserver/](https://libwebsockets.org/testserver/) which hosts websocket-over-http2
server implemented with [libwebsockets](https://github.com/warmcat/libwebsockets) - popular C-based networking library. 
### browser example
Both example servers have web page at `https://localhost:8099` that sends pings to
`/echo` endpoint.   
The only browser with http2 websockets protocol support is `Mozilla Firefox`.

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
    implementation 'com.jauntsdn.netty:netty-websocket-http2:0.1.0'
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