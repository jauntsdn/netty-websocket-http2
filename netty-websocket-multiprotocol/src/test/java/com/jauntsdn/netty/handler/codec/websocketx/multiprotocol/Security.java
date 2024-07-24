/*
 * Copyright 2024 - present Maksym Ostroverkhov.
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

package com.jauntsdn.netty.handler.codec.websocketx.multiprotocol;

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;

public final class Security {

  public static SslContext serverSslContext(String keystoreFile, String keystorePassword)
      throws Exception {

    KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
    SslProvider sslProvider = sslProvider();
    KeyStore keyStore = KeyStore.getInstance("PKCS12");

    InputStream keystoreStream = Security.class.getClassLoader().getResourceAsStream(keystoreFile);
    char[] keystorePasswordArray = keystorePassword.toCharArray();
    keyStore.load(keystoreStream, keystorePasswordArray);
    keyManagerFactory.init(keyStore, keystorePasswordArray);

    return SslContextBuilder.forServer(keyManagerFactory)
        .protocols("TLSv1.3")
        .sslProvider(sslProvider)
        .applicationProtocolConfig(alpnConfigHttp2())
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .build();
  }

  public static SslContext clientLocalSslContextHttp2() throws SSLException {
    return clientSslContextBuilder().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
  }

  public static SslContext clientLocalSslContextHttp1() throws SSLException {
    return clientSslContextBuilder()
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .applicationProtocolConfig(alpnConfigHttp1())
        .build();
  }

  public static SslContext clientSslContextHttp2() throws SSLException {
    return clientSslContextBuilder().build();
  }

  private static SslContextBuilder clientSslContextBuilder() throws SSLException {
    return SslContextBuilder.forClient()
        .protocols("TLSv1.3")
        .sslProvider(sslProvider())
        .applicationProtocolConfig(alpnConfigHttp2())
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE);
  }

  private static ApplicationProtocolConfig alpnConfigHttp2() {
    return new ApplicationProtocolConfig(
        ApplicationProtocolConfig.Protocol.ALPN,
        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
        ApplicationProtocolNames.HTTP_2);
  }

  private static ApplicationProtocolConfig alpnConfigHttp1() {
    return new ApplicationProtocolConfig(
        ApplicationProtocolConfig.Protocol.ALPN,
        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
        ApplicationProtocolNames.HTTP_1_1);
  }

  private static SslProvider sslProvider() {
    final SslProvider sslProvider;
    if (OpenSsl.isAvailable()) {
      sslProvider = SslProvider.OPENSSL_REFCNT;
    } else {
      sslProvider = SslProvider.JDK;
    }
    return sslProvider;
  }
}
