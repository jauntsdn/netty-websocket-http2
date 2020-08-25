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

package com.jauntsdn.netty.handler.codec.http2.websocketx.example.channelserver;

import static com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.*;
import static io.netty.channel.ChannelHandler.*;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketAcceptor;
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
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    logger.info("\n==> Channel per websocket server\n");
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
    private final SslContext sslContext;

    ConnectionAcceptor(SslContext sslContext) {
      this.sslContext = sslContext;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      SslHandler sslHandler = sslContext.newHandler(ch.alloc());

      Http2FrameCodecBuilder http2Builder = Http2FrameCodecBuilder.forServer();
      http2Builder.initialSettings().initialWindowSize(1_000);
      Http2FrameCodec http2frameCodec =
          Http2WebSocketServerBuilder.configureHttp2Server(http2Builder).build();

      EchoWebSocketHandler echoWebSocketHandler = new EchoWebSocketHandler();
      UserAgentBasedAcceptor userAgentBasedAcceptor = new UserAgentBasedAcceptor();
      Http2WebSocketServerHandler http2webSocketHandler =
          Http2WebSocketServerHandler.builder()
              .compression(true)
              .handler("/echo", userAgentBasedAcceptor, echoWebSocketHandler)
              .handler("/echo", "com.jauntsdn.echo", userAgentBasedAcceptor, echoWebSocketHandler)
              .handler("/echo_all", echoWebSocketHandler)
              .build();

      HttpHandler httpHandler = new HttpHandler();

      ch.pipeline().addLast(sslHandler, http2frameCodec, http2webSocketHandler, httpHandler);
    }
  }

  @Sharable
  private static class EchoWebSocketHandler
      extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame webSocketFrame) {
      ctx.write(webSocketFrame.retain());
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
      ctx.flush();
      super.channelReadComplete(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof Http2WebSocketHandshakeEvent) {
        Http2WebSocketHandshakeEvent handshakeEvent = (Http2WebSocketHandshakeEvent) evt;
        int id = handshakeEvent.id();
        String path = handshakeEvent.path();
        String subprotocols = handshakeEvent.subprotocols();
        String subprotocolsOrEmpty = subprotocols.isEmpty() ? "<empty>" : subprotocols;

        switch (handshakeEvent.type()) {
          case HANDSHAKE_START:
            Http2WebSocketHandshakeEvent.Http2WebSocketHandshakeStartEvent startEvent =
                handshakeEvent.cast();
            logger.info(
                "==> WebSocket handshake start event - id: {}, path: {}, subprotocols: {}, request headers: {}",
                id,
                path,
                subprotocolsOrEmpty,
                headers(startEvent.requestHeaders()));
            break;
          case HANDSHAKE_SUCCESS:
            Http2WebSocketHandshakeEvent.Http2WebSocketHandshakeSuccessEvent successEvent =
                handshakeEvent.cast();
            logger.info(
                "==> WebSocket handshake success event - id: {}, path: {}, subprotocols: {}, response headers: {}",
                id,
                path,
                subprotocolsOrEmpty,
                headers(successEvent.responseHeaders()));
            break;
          case HANDSHAKE_ERROR:
            Http2WebSocketHandshakeEvent.Http2WebSocketHandshakeErrorEvent errorEvent =
                handshakeEvent.cast();
            String errorName;
            String errorMessage;
            Throwable cause = errorEvent.error();
            if (cause != null) {
              errorName = cause.getClass().getSimpleName();
              errorMessage = cause.getMessage();
            } else {
              errorName = errorEvent.errorName();
              errorMessage = errorEvent.errorMessage();
            }
            logger.info(
                "==> WebSocket handshake error event - id: {}, path: {}, subprotocols: {}, error: {}: {}, response headers: {}",
                id,
                path,
                subprotocolsOrEmpty,
                errorName,
                errorMessage,
                headers(errorEvent.responseHeaders()));
            break;
          case CLOSE_REMOTE:
            logger.info("==> WebSocket stream close remote - id: {}, path: {}", id, path);
            break;

          default:
            logger.info(
                "==> WebSocket handshake unexpected event - type: {}", handshakeEvent.type());
        }
        return;
      }
      super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.error("Unexpected websocket error", cause);
      ctx.close();
    }

    private String headers(Http2Headers headers) {
      if (headers == null) {
        return "[]";
      }
      String repr = headers.toString();
      String prefix = "Http2Headers";
      int index = repr.indexOf(prefix);
      if (index > 0) {
        return repr.substring(index + prefix.length());
      }
      return repr;
    }
  }

  private static class UserAgentBasedAcceptor implements Http2WebSocketAcceptor {
    private static final Collection<String> SUPPORTED_USER_AGENTS =
        Arrays.asList("Firefox/", "jauntsdn-websocket-http2-client/");

    @Override
    public ChannelFuture accept(
        ChannelHandlerContext context, Http2Headers request, Http2Headers response) {
      CharSequence userAgentSeq = request.get("user-agent");
      if (userAgentSeq == null || userAgentSeq.length() == 0) {
        return context.newFailedFuture(
            new IllegalArgumentException("user-agent header is missing"));
      }
      String userAgent = userAgentSeq.toString();
      for (String supported : SUPPORTED_USER_AGENTS) {
        int index = userAgent.indexOf(supported);
        if (index >= 0) {
          int length = supported.length();
          String version = userAgent.substring(index + length);
          String clientId = supported.substring(0, length - 1);
          request.set("x-client-id", clientId);
          request.set("x-client-version", version);
          response.set("x-request-id", UUID.randomUUID().toString());
          return context.newSucceededFuture();
        }
      }
      return context.newFailedFuture(
          new IllegalArgumentException("unsupported user-agent: " + userAgentSeq));
    }
  }

  private static class HttpHandler extends Http2ChannelDuplexHandler {
    private static final ReadOnlyHttp2Headers headers404 =
        ReadOnlyHttp2Headers.serverHeaders(false, AsciiString.of("404"));
    private static final ReadOnlyHttp2Headers headers405 =
        ReadOnlyHttp2Headers.serverHeaders(false, AsciiString.of("405"));
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private boolean closed;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof Http2Frame) {
        if (msg instanceof Http2HeadersFrame) {
          Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
          Http2Headers headers = headersFrame.headers();
          Http2FrameStream stream = headersFrame.stream();
          CharSequence method = headers.method();
          String query = headers.get(":path").toString();
          String path = new QueryStringDecoder(query).path();

          if ("GET".contentEquals(method)) {
            if (path.isEmpty() || path.equals("/")) {
              path = "index.html";
            }
            serveResource(ctx, stream, path);
            return;
          }

          ctx.write(new DefaultHttp2HeadersFrame(headers405, true).stream(stream));
        } else if (msg instanceof Http2DataFrame) {
          logger.info("Received DATA frame for GET request: {}", msg);
          ReferenceCountUtil.safeRelease(msg);
          if (!closed) {
            closed = true;
            ctx.write(
                new DefaultHttp2GoAwayFrame(
                    Http2Error.NO_ERROR,
                    Unpooled.wrappedBuffer(
                        "Received DATA frame for GET request".getBytes(StandardCharsets.UTF_8))));
          }
        }
      } else {
        logger.info("Received unexpected message: {}", msg);
        ReferenceCountUtil.safeRelease(msg);
      }
      super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      closed = true;
      super.channelInactive(ctx);
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
                ctx.write(new DefaultHttp2HeadersFrame(headers404, true).stream(frameStream));
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
  }
}
