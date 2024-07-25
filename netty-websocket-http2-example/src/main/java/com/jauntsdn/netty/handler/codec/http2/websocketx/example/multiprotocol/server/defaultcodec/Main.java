package com.jauntsdn.netty.handler.codec.http2.websocketx.example.multiprotocol.server.defaultcodec;

import com.jauntsdn.netty.handler.codec.http2.websocketx.example.Security;
import com.jauntsdn.netty.handler.codec.websocketx.multiprotocol.MultiProtocolWebSocketServerHandler;
import com.jauntsdn.netty.handler.codec.websocketx.multiprotocol.MultiprotocolWebSocketServerBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
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
                            .defaultCodec()
                            .handler(new DefaultEchoWebSocketHandler())
                            .build();
                    ch.pipeline().addLast(sslHandler, multiprotocolHandler);
                  }
                })
            .bind(host, port)
            .sync()
            .channel();
    logger.info("\n==> Websocket server (default codec) is listening on {}:{}", host, port);
    server.closeFuture().await();
  }

  @ChannelHandler.Sharable
  private static class DefaultEchoWebSocketHandler
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
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.error("Unexpected websocket error", cause);
      ctx.close();
    }
  }
}
