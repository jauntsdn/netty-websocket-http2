#!/bin/sh

export NETTY_WEBSOCKET_HTTP2_PERFTEST_BULK_SERVER_OPTS='--add-exports java.base/sun.security.x509=ALL-UNNAMED'

cd netty-websocket-http2-perftest/build/install/netty-websocket-http2-perftest/bin && ./netty-websocket-http2-perftest-bulk-server