package com.jauntsdn.netty.handler.codec.http2.websocketx.example.multiprotocol.server.callbackscodec;

import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketCallbacksHandler;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameListener;
import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketEvent.Http2WebSocketHandshakeSuccessEvent;
import com.jauntsdn.netty.handler.codec.http2.websocketx.example.Security;
import com.jauntsdn.netty.handler.codec.websocketx.multiprotocol.MultiProtocolWebSocketServerHandler;
import com.jauntsdn.netty.handler.codec.websocketx.multiprotocol.MultiprotocolWebSocketServerBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    String host = "localhost";
    int port = 8099;
    SslContext sslContext = Security.serverSslContext("localhost.p12", "localhost");

    Channel server =
        new ServerBootstrap()
            .group(new NioEventLoopGroup())
            .channel(NioServerSocketChannel.class)
            .childHandler(
                new ChannelInitializer<SocketChannel>() {

                  @Override
                  protected void initChannel(SocketChannel ch) {
                    SslHandler sslHandler = sslContext.newHandler(ch.alloc());
                    MultiProtocolWebSocketServerHandler multiprotocolHandler =
                        MultiprotocolWebSocketServerBuilder.create()
                            .path("/echo")
                            .subprotocols("echo.jauntsdn.com")
                            .callbacksCodec()
                            .handler(
                                new ChannelInitializer<Channel>() {
                                  @Override
                                  protected void initChannel(Channel ch) {
                                    ch.pipeline().addLast(new CallbacksServerHandler());
                                  }
                                })
                            .build();
                    ch.pipeline().addLast(sslHandler, multiprotocolHandler);
                  }
                })
            .bind(host, port)
            .sync()
            .channel();
    logger.info("\n==> Websocket server (callbacks codec) is listening on {}:{}", host, port);
    server.closeFuture().await();
  }

  private static class CallbacksServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void userEventTriggered(ChannelHandlerContext c, Object evt) throws Exception {
      if (evt instanceof HandshakeComplete || evt instanceof Http2WebSocketHandshakeSuccessEvent) {
        WebSocketCallbacksHandler.exchange(
            c,
            (ctx, webSocketFrameFactory) ->
                new WebSocketFrameListener() {
                  @Override
                  public void onChannelRead(
                      ChannelHandlerContext context,
                      boolean finalFragment,
                      int rsv,
                      int opcode,
                      ByteBuf payload) {
                    ByteBuf textFrame =
                        webSocketFrameFactory.mask(
                            webSocketFrameFactory.createTextFrame(
                                ctx.alloc(), payload.readableBytes()));
                    textFrame.writeBytes(payload);
                    payload.release();
                    ctx.write(textFrame);
                  }

                  @Override
                  public void onChannelReadComplete(ChannelHandlerContext ctx1) {
                    ctx1.flush();
                  }

                  @Override
                  public void onExceptionCaught(ChannelHandlerContext ctx1, Throwable cause) {
                    if (cause instanceof IOException) {
                      return;
                    }
                    logger.error("Unexpected websocket error", cause);
                    ctx1.close();
                  }
                });
      }
      super.userEventTriggered(c, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      ReferenceCountUtil.safeRelease(msg);
    }
  }
}
