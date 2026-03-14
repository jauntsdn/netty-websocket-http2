#!/bin/sh

export NETTY_WEBSOCKET_HTTP2_TEST_PERFBULKSERVER_OPTS='--add-exports java.base/sun.security.x509=ALL-UNNAMED'

cd netty-websocket-http2-test/build/install/netty-websocket-http2-test/bin && ./netty-websocket-http2-test-perfbulkserver