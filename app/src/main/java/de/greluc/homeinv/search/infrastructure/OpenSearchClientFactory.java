/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.infrastructure;

import de.greluc.homeinv.platform.PinnedCertificate;
import de.greluc.homeinv.search.application.SearchEngineProperties;
import java.security.SecureRandom;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the client that talks to OpenSearch (REQ-SRCH-005, ADR-0008).
 *
 * <h2>Only when it was asked for</h2>
 *
 * <p>The bean exists when {@code HOMEINV_SEARCH_ENGINE} is {@code opensearch} and not otherwise, so
 * the {@code minimal} profile — which never gets OpenSearch — starts without a client, without a
 * connection attempt and without a warning about a service it was never meant to have.
 *
 * <h2>Pinned, not CA-validated</h2>
 *
 * <p>The deployment's CA signs {@code api}, {@code worker}, {@code blobstore}, {@code opensearch}
 * and every plugin, so accepting anything it signed would let a compromised plugin answer as the
 * index. {@code HOMEINV_SEARCH_FINGERPRINT} names the one certificate this client accepts, exactly
 * as {@code HOMEINV_BLOBSTORE_FINGERPRINT} does for the blob store (REQ-SEC-056, ADR-0044). The
 * hostname is not verified, because pinning answers a stricter question than a name does.
 *
 * <p>The user and password are sent on top of that. Reachability is not authorisation, inside the
 * deployment as much as outside it: {@code internal} is a network, not a trust boundary.
 *
 * <p>The client brings its own Jackson 2 for the wire format. That is a second JSON library beside
 * this project's Jackson 3, accepted with the owner on 2026-09-14 because {@code opensearch-java}
 * is the supported way to speak to the server and hand-rolling one would be a query builder to
 * maintain; {@code app/build.gradle.kts} records the version to watch.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "homeinv.search.engine", havingValue = "opensearch")
public class OpenSearchClientFactory {

  /**
   * The transport, authenticated and pinned.
   *
   * <p>Its own bean because it is the half that holds the connections: {@code OpenSearchClient} has
   * no {@code close}, the transport does, and a context that is thrown away without closing it
   * leaks a connection pool per restart — which a test suite notices long before a deployment does.
   *
   * @param properties where OpenSearch is, as whom, and which certificate it must present
   * @return the transport
   * @throws IllegalStateException when the TLS context cannot be built, which is a configuration
   *     fault and not something to start without
   */
  @Bean(destroyMethod = "close")
  public OpenSearchTransport openSearchTransport(SearchEngineProperties properties) {
    HttpHost host = HttpHost.create(properties.getUrl());

    BasicCredentialsProvider credentials = new BasicCredentialsProvider();
    credentials.setCredentials(
        new AuthScope(host),
        new UsernamePasswordCredentials(
            properties.getUsername(), properties.getPassword().toCharArray()));

    return ApacheHttpClient5TransportBuilder.builder(host)
        .setMapper(new JacksonJsonpMapper())
        // Off. The transport otherwise asks for gzip and then insists on it,
        // while OpenSearch only compresses when `http_compression` is on - and
        // the answer comes back plain, which the client reads as "Not in GZIP
        // format" and reports as a transport failure. The hop is one container
        // to another on a private segment, where the compression would buy
        // latency on a link that has none to spare.
        .setCompressionEnabled(false)
        .setHttpClientConfigCallback(client -> configure(client, credentials, properties))
        .build();
  }

  /**
   * The client every adapter here talks to.
   *
   * @param transport the connections it speaks over
   * @return the client
   */
  @Bean
  public OpenSearchClient openSearchClient(OpenSearchTransport transport) {
    return new OpenSearchClient(transport);
  }

  /**
   * Puts the credentials and the pinned trust onto the HTTP client.
   *
   * @param client the builder the transport hands over
   * @param credentials the user and password
   * @param properties the fingerprint and the URL
   * @return the same builder
   */
  private static HttpAsyncClientBuilder configure(
      HttpAsyncClientBuilder client,
      BasicCredentialsProvider credentials,
      SearchEngineProperties properties) {
    client.setDefaultCredentialsProvider(credentials);
    // Two layers would otherwise both try. Apache HttpClient asks for gzip and
    // decodes what comes back; the OpenSearch transport does its own accounting
    // of whether compression was negotiated, and with it switched off above the
    // response is read twice - once by the decoder and once as JSON, which
    // surfaces as "Not in GZIP format" on a response that never was one. The hop
    // is container to container on a private segment, where compression buys
    // nothing worth a second decoder.
    client.disableContentCompression();
    if (!properties.isTls()) {
      // Plain HTTP is a test container's shape, never a deployment's: every
      // service on `internal` speaks TLS (ADR-0044). Said out loud so that a
      // deployment configured this way is noticed in the log rather than in an
      // audit.
      log.warn(
          "HOMEINV_SEARCH_URL is not https. The deployment's OpenSearch speaks TLS; this is only"
              + " expected in a test.");
      return client;
    }
    client.setConnectionManager(
        PoolingAsyncClientConnectionManagerBuilder.create()
            .setTlsStrategy(
                ClientTlsStrategyBuilder.create()
                    .setSslContext(pinnedTo(properties.getFingerprint()))
                    // Pinning has already answered a stricter question than a
                    // hostname does, and the certificate is issued to a service
                    // name a client may reach under another.
                    .setHostnameVerifier((hostname, session) -> true)
                    .build())
            .build());
    return client;
  }

  /**
   * An SSL context that accepts exactly one certificate.
   *
   * @param fingerprint the expected SHA-256 fingerprint, already configured
   * @return the context
   * @throws IllegalStateException when it cannot be built
   */
  private static SSLContext pinnedTo(String fingerprint) {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(
          null,
          new TrustManager[] {
            new PinnedCertificate("OpenSearch", "HOMEINV_SEARCH_FINGERPRINT", fingerprint)
          },
          new SecureRandom());
      return context;
    } catch (java.security.GeneralSecurityException unusable) {
      throw new IllegalStateException(
          "The TLS context for OpenSearch could not be built from HOMEINV_SEARCH_FINGERPRINT",
          unusable);
    }
  }
}
