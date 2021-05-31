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

package com.jauntsdn.netty.handler.codec.http2.websocketx.example.handshakeserver;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerBuilder;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketServerHandler;
import com.jauntsdn.netty.handler.codec.http2.websocketx.example.Security;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.*;
import io.netty.util.AsciiString;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    String host = System.getProperty("HOST", "localhost");
    int port = Integer.parseInt(System.getProperty("PORT", "8099"));
    String echoPath = System.getProperty("PING", "echo");
    String keyStoreFile = System.getProperty("KEYSTORE", "localhost.p12");
    String keyStorePassword = System.getProperty("KEYSTORE_PASS", "localhost");

    logger.info("\n==> Handshake only websocket server\n");
    logger.info("\n==> Bind address: {}:{}", host, port);
    logger.info("\n==> Keystore file: {}", keyStoreFile);

    SslContext sslContext = Security.serverSslContext(keyStoreFile, keyStorePassword);

    ServerBootstrap bootstrap = new ServerBootstrap();
    Channel server =
        bootstrap
            .group(new NioEventLoopGroup())
            .channel(NioServerSocketChannel.class)
            .childHandler(new ConnectionAcceptor(sslContext))
            .bind(host, port)
            .sync()
            .channel();
    logger.info("\n==> Server is listening on {}:{}", host, port);

    logger.info("\n==> Echo path: {}", echoPath);
    logger.info("\n==> Modern browser (Mozilla Firefox) demo: https://{}:{}", host, port);

    server.closeFuture().sync();
  }

  private static class ConnectionAcceptor extends ChannelInitializer<SocketChannel> {
    private static final int FLOW_CONTROL_WINDOW_SIZE = 1_000;
    private final SslContext sslContext;

    ConnectionAcceptor(SslContext sslContext) {
      this.sslContext = sslContext;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      SslHandler sslHandler = sslContext.newHandler(ch.alloc());
      Http2FrameCodecBuilder http2Builder =
          Http2WebSocketServerBuilder.configureHttp2Server(Http2FrameCodecBuilder.forServer());
      http2Builder.initialSettings().initialWindowSize(FLOW_CONTROL_WINDOW_SIZE);
      Http2FrameCodec frameCodec = http2Builder.build();

      Http2WebSocketHandler http2webSocketHandler =
          Http2WebSocketServerHandler.builder().handshakeOnly();

      Http2StreamsHandler http2StreamsHandler = new Http2StreamsHandler(FLOW_CONTROL_WINDOW_SIZE);
      ch.pipeline().addLast(sslHandler, frameCodec, http2webSocketHandler, http2StreamsHandler);
    }
  }

  private static class Http2StreamsHandler extends Http2ChannelDuplexHandler {
    private static final ReadOnlyHttp2Headers HEADERS_404 =
        ReadOnlyHttp2Headers.serverHeaders(false, AsciiString.of("404"));
    private static final ReadOnlyHttp2Headers HEADERS_405 =
        ReadOnlyHttp2Headers.serverHeaders(false, AsciiString.of("405"));
    private static final ReadOnlyHttp2Headers HEADERS_200 =
        ReadOnlyHttp2Headers.serverHeaders(true, AsciiString.of("200"));
    private static final ReadOnlyHttp2Headers HEADERS_200_ECHO_PROTOCOL =
        ReadOnlyHttp2Headers.serverHeaders(
            true,
            AsciiString.of("200"),
            AsciiString.of("sec-websocket-protocol"),
            AsciiString.of("echo.jauntsdn.com"));

    private final IntObjectMap<Http2FrameStream> echos = new IntObjectHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private int receiveBytes;
    private final int receiveWindowBytes;

    public Http2StreamsHandler(int receiveWindowBytes) {
      this.receiveWindowBytes = receiveWindowBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof Http2Frame) {
        if (msg instanceof Http2HeadersFrame) {
          Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
          Http2Headers requestHeaders = headersFrame.headers();
          Http2FrameStream stream = headersFrame.stream();
          CharSequence method = requestHeaders.method();
          String query = requestHeaders.get(":path").toString();
          String path = new QueryStringDecoder(query).path();

          if ("GET".contentEquals(method)) {
            if (path.isEmpty() || path.equals("/")) {
              path = "index.html";
            }
            serveResource(ctx, stream, path);
            return;
          }

          if (!"POST".contentEquals(method)) {
            ctx.write(new DefaultHttp2HeadersFrame(HEADERS_405, true).stream(stream));
            return;
          }

          CharSequence protocol = requestHeaders.get("x-protocol");
          if (!"websocket".contentEquals(protocol)) {
            ctx.write(new DefaultHttp2HeadersFrame(HEADERS_404, true).stream(stream));
            return;
          }

          switch (path) {
            case "/echo":
              echos.put(stream.id(), stream);
              CharSequence subprotocol = requestHeaders.get("sec-websocket-protocol");
              Http2Headers responseHeaders =
                  subprotocol != null && "echo.jauntsdn.com".contentEquals(subprotocol)
                      ? HEADERS_200_ECHO_PROTOCOL
                      : HEADERS_200;
              ctx.write(new DefaultHttp2HeadersFrame(responseHeaders, false).stream(stream));
              return;
            default:
              ctx.write(new DefaultHttp2HeadersFrame(HEADERS_404, true).stream(stream));
              return;
          }

        } else if (msg instanceof Http2DataFrame) {
          Http2DataFrame dataFrame = (Http2DataFrame) msg;
          receiveBytes += dataFrame.content().readableBytes();
          if (receiveBytes >= receiveWindowBytes / 2) {
            int windowUpdateBytes = receiveBytes;
            receiveBytes = 0;
            ctx.writeAndFlush(
                new DefaultHttp2WindowUpdateFrame(windowUpdateBytes).stream(dataFrame.stream()));
          }

          int streamId = dataFrame.stream().id();
          Http2FrameStream echoStream =
              dataFrame.isEndStream() ? echos.remove(streamId) : echos.get(streamId);
          if (echoStream != null) {
            ctx.write(dataFrame);
            return;
          }
          logger.info("Unknown DATA frame: {}", dataFrame);

        } else if (msg instanceof Http2ResetFrame) {
          Http2ResetFrame resetFrame = (Http2ResetFrame) msg;
          boolean isEcho = echos.remove(resetFrame.stream().id()) != null;
          if (isEcho && resetFrame.errorCode() != Http2Error.CANCEL.code()) {
            logger.info("Unexpected RESET frame with non-CANCEL code: {}", resetFrame);
            ctx.write(
                new DefaultHttp2GoAwayFrame(Http2Error.PROTOCOL_ERROR, Unpooled.EMPTY_BUFFER));
            return;
          }
        }
      } else {
        logger.info("Unexpected message: {}", msg);
      }
      super.channelRead(ctx, msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
      ctx.flush();
      super.channelReadComplete(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.error("Unexpected connection error", cause);
      ctx.close();
    }

    private void serveResource(
        ChannelHandlerContext ctx, Http2FrameStream frameStream, String resource) {
      executorService.execute(
          () -> {
            String classPathResource = "web/" + resource;
            try (InputStream resourceStream =
                Main.class.getClassLoader().getResourceAsStream(classPathResource)) {
              if (resourceStream == null) {
                ctx.write(new DefaultHttp2HeadersFrame(HEADERS_404, true).stream(frameStream));
                return;
              }
              Http2Headers responseHeaders = new DefaultHttp2Headers(true).status("200");

              String contentType;
              if (resource.endsWith(".html")) {
                contentType = "text/html";
              } else if (resource.endsWith(".js")) {
                contentType = "text/javascript";
              } else {
                contentType = "application/octet-stream";
              }
              responseHeaders.set("content-type", contentType);

              ctx.write(new DefaultHttp2HeadersFrame(responseHeaders, false).stream(frameStream));

              int bufferSize = 16384;
              InputStream bufferedResourceStream =
                  new BufferedInputStream(resourceStream, bufferSize);
              byte[] buffer = new byte[bufferSize];
              int bytesRead;
              /*keep it simple for the demo*/
              while ((bytesRead = bufferedResourceStream.read(buffer)) != -1) {
                ByteBuf response = ByteBufAllocator.DEFAULT.buffer(bytesRead);
                response.writeBytes(buffer, 0, bytesRead);
                ctx.write(new DefaultHttp2DataFrame(response, false).stream(frameStream));
              }
              ctx.writeAndFlush(
                  new DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true).stream(frameStream));
            } catch (Exception e) {
              ctx.fireExceptionCaught(
                  new RuntimeException(
                      "Error while reading classpath resource: " + classPathResource, e));
            }
          });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      echos.clear();
      super.channelInactive(ctx);
    }
  }
}
