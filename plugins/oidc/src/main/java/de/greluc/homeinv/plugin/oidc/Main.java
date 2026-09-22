/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugin.oidc;

import de.greluc.homeinv.plugin.v1.Check;
import de.greluc.homeinv.plugin.v1.HealthRequest;
import de.greluc.homeinv.plugin.v1.HealthResponse;
import de.greluc.homeinv.plugin.v1.HealthState;
import de.greluc.homeinv.plugin.v1.IdentityProviderBeginRequest;
import de.greluc.homeinv.plugin.v1.IdentityProviderBeginResponse;
import de.greluc.homeinv.plugin.v1.IdentityProviderCompleteRequest;
import de.greluc.homeinv.plugin.v1.IdentityProviderDescribeRequest;
import de.greluc.homeinv.plugin.v1.IdentityProviderDescriptor;
import de.greluc.homeinv.plugin.v1.IdentityProviderGrpc;
import de.greluc.homeinv.plugin.v1.IdentityProviderIdentity;
import de.greluc.homeinv.plugin.v1.PluginHealthGrpc;
import com.nimbusds.jwt.JWTClaimsSet;
import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code plugin-oidc} — federated sign-in against an OIDC provider.
 *
 * <p>The fifth of the five first-party plugins and the only one in Java
 * ([ADR-0072](../../../../../../../docs/adr/0072-first-party-plugins-live-here.md)): ID-token
 * validation is where an audited library outweighs an image size, because a subtle verification
 * bug written twice is how a federated login becomes an authentication bypass.
 *
 * <h2>What the core keeps and what this plugin is given</h2>
 *
 * <p>The {@code state}, the {@code nonce} and the PKCE verifier are the <b>core's</b>. They are
 * minted there, stored there and checked there; this plugin receives the challenge and the nonce
 * because the protocol puts them on the wire, and it checks the nonce inside the token because
 * only the token can carry it. A value only a plugin knew would be a value the core could not
 * check.
 *
 * <p>This plugin also decides nothing about accounts. It reports who the provider says somebody
 * is; whether that becomes a session, and against which account, is the core's decision and a
 * person's confirmation (REQ-AUTH-006).
 *
 * <h2>Configured once, by the operator</h2>
 *
 * <p>No per-tenant half, unlike the other four: a sign-in happens before any tenant is known.
 * {@link Configuration} says what that means.
 */
public final class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  /** The port the service matrix names for this plugin. */
  private static final int DEFAULT_PORT = 8204;

  /** Where the runtime mounts this plugin's own identity. */
  private static final String DEFAULT_IDENTITY = "/run/secrets/mtls-plugin-oidc";

  private Main() {}

  /**
   * Starts the server and waits for it.
   *
   * @param arguments ignored; everything is configured through the environment
   * @throws IOException when the mounted identity cannot be read or the port cannot be bound
   * @throws InterruptedException when the wait for shutdown is interrupted
   */
  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (licences(arguments)) {
      return;
    }
    Configuration configuration = Configuration.fromEnvironment();
    int port = port();
    Path identity = Path.of(environment("HOMEINV_MTLS_PLUGIN_FILE", DEFAULT_IDENTITY));

    List<String> missing = configuration.missing();
    for (String reason : missing) {
      // Said once, at startup, and each one names what to set. A plugin that
      // cannot sign anybody in is one an operator has to be able to diagnose
      // without reading its source (REQ-PLG-015).
      log.warn("plugin-oidc is not ready: {}", reason);
    }

    Pem.Bundle bundle = Pem.read(identity);
    ServerCredentials credentials =
        TlsServerCredentials.newBuilder()
            .keyManager(bundle.chain(), bundle.key())
            .trustManager(bundle.authority())
            // A plugin is called by the core and by nothing else. There is no
            // unauthenticated mode and no flag to enable one: a mode that exists
            // is a mode somebody runs.
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();

    Provider provider = new Provider(configuration);
    Server server =
        Grpc.newServerBuilderForPort(port, credentials)
            .addService(new Federation(configuration, provider))
            .addService(new Health(configuration))
            .build()
            .start();

    log.info(
        "plugin-oidc is listening on {} for issuer {}",
        port,
        configuration.issuer() == null ? "none" : configuration.issuer());
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  log.info("plugin-oidc is stopping");
                  server.shutdown();
                }));
    server.awaitTermination();
  }

  private static int port() {
    try {
      return Integer.parseInt(environment("HOMEINV_PLUGIN_PORT", String.valueOf(DEFAULT_PORT)));
    } catch (NumberFormatException notANumber) {
      return DEFAULT_PORT;
    }
  }

  private static String environment(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  /** The contract itself. */
  private static final class Federation extends IdentityProviderGrpc.IdentityProviderImplBase {

    private final Configuration configuration;
    private final Provider provider;

    Federation(Configuration configuration, Provider provider) {
      this.configuration = configuration;
      this.provider = provider;
    }

    @Override
    public void describe(
        IdentityProviderDescribeRequest request,
        StreamObserver<IdentityProviderDescriptor> responses) {
      responses.onNext(
          IdentityProviderDescriptor.newBuilder()
              .setProviderKey(configuration.providerKey())
              .setDisplayName(configuration.displayName())
              // Always. The core sends a challenge whatever a provider insists
              // on, and a provider that ignores it is one where an intercepted
              // code is worth something — which an operator wants to see.
              .setPkceRequired(true)
              .build());
      responses.onCompleted();
    }

    @Override
    public void begin(
        IdentityProviderBeginRequest request,
        StreamObserver<IdentityProviderBeginResponse> responses) {
      if (!ready(responses)) {
        return;
      }
      try {
        String url =
            provider.authorizationUrl(
                request.getRedirectUri(),
                request.getState(),
                request.getNonce(),
                request.getCodeChallenge(),
                request.getLoginHint());
        responses.onNext(
            IdentityProviderBeginResponse.newBuilder().setAuthorizationUrl(url).build());
        responses.onCompleted();
      } catch (IOException unreachable) {
        log.warn("The provider's metadata could not be read", unreachable);
        responses.onError(
            Status.UNAVAILABLE
                .withDescription("the provider could not be reached: " + unreachable.getMessage())
                .asRuntimeException());
      }
    }

    @Override
    public void complete(
        IdentityProviderCompleteRequest request,
        StreamObserver<IdentityProviderIdentity> responses) {
      if (!ready(responses)) {
        return;
      }
      try {
        JWTClaimsSet claims =
            provider.verifiedIdentity(
                request.getCode(),
                request.getCodeVerifier(),
                request.getRedirectUri(),
                request.getNonce());
        responses.onNext(identityOf(claims));
        responses.onCompleted();
      } catch (Provider.VerificationException refused) {
        // UNAUTHENTICATED and not UNAVAILABLE: a token that does not verify is
        // something to refuse rather than something to retry, and the core
        // turns the two into different answers.
        log.info("An ID token was refused: {}", refused.getMessage());
        responses.onError(
            Status.UNAUTHENTICATED.withDescription(refused.getMessage()).asRuntimeException());
      } catch (IOException unreachable) {
        log.warn("The token exchange failed", unreachable);
        responses.onError(
            Status.UNAVAILABLE
                .withDescription("the provider could not be reached: " + unreachable.getMessage())
                .asRuntimeException());
      }
    }

    /** Answers the caller when this plugin is installed and not configured. */
    private boolean ready(StreamObserver<?> responses) {
      List<String> missing = configuration.missing();
      if (missing.isEmpty()) {
        return true;
      }
      responses.onError(
          Status.FAILED_PRECONDITION
              .withDescription(
                  "this plugin is installed and not configured: " + String.join("; ", missing))
              .asRuntimeException());
      return false;
    }

    /**
     * Turns verified claims into what the contract returns.
     *
     * <p>An address the provider did not mark verified is reported as <b>unverified</b> and
     * never quietly promoted: the core treats an unverified address as absent, and it is the one
     * claim somebody else can assert about a person.
     */
    private static IdentityProviderIdentity identityOf(JWTClaimsSet claims) {
      Map<String, Object> all = claims.getClaims();
      String email = text(all.get("email"));
      boolean verified = Boolean.TRUE.equals(all.get("email_verified"));
      String name = text(all.get("name"));
      if (name.isBlank()) {
        name = text(all.get("preferred_username"));
      }

      IdentityProviderIdentity.Builder identity =
          IdentityProviderIdentity.newBuilder()
              .setSubject(claims.getSubject() == null ? "" : claims.getSubject())
              .setIssuer(claims.getIssuer() == null ? "" : claims.getIssuer())
              .setEmail(email)
              .setEmailVerified(verified)
              .setDisplayName(name);

      // Everything else, as text, for an operator to map onto roles. The core
      // maps nothing by itself, and a claim it cannot read is a claim it cannot
      // act on — so the ones that are not scalars are left out rather than
      // serialised into something that looks like a value.
      Map<String, String> rest = new TreeMap<>();
      for (Map.Entry<String, Object> claim : all.entrySet()) {
        if (List.of("sub", "iss", "email", "email_verified", "name", "nonce", "aud", "exp", "iat")
            .contains(claim.getKey())) {
          continue;
        }
        String value = text(claim.getValue());
        if (!value.isBlank()) {
          rest.put(claim.getKey(), value);
        }
      }
      identity.putAllClaims(rest);
      return identity.build();
    }

    private static String text(Object value) {
      return switch (value) {
        case null -> "";
        case String string -> string;
        case Number number -> number.toString();
        case Boolean flag -> flag.toString();
        default -> "";
      };
    }
  }

  /** The health service, answering what the manifest's check asks. */
  private static final class Health extends PluginHealthGrpc.PluginHealthImplBase {

    private final Configuration configuration;

    Health(Configuration configuration) {
      this.configuration = configuration;
    }

    @Override
    public void check(HealthRequest request, StreamObserver<HealthResponse> responses) {
      List<String> missing = configuration.missing();
      HealthResponse.Builder answer =
          HealthResponse.newBuilder()
              .setState(
                  missing.isEmpty()
                      ? HealthState.HEALTH_STATE_OK
                      // NOT_CONFIGURED and not a failure: the difference matters
                      // to an operator, and `plugin-health-states.yaml` names it
                      // for this reason (REQ-PLG-015).
                      : HealthState.HEALTH_STATE_NOT_CONFIGURED)
              .setDetail(String.join("; ", missing));
      if (missing.isEmpty()) {
        answer.addChecks(Check.newBuilder().setName("configured").setPassed(true).build());
      } else {
        for (String reason : missing) {
          answer.addChecks(
              Check.newBuilder().setName("configured").setPassed(false).setDetail(reason).build());
        }
      }
      responses.onNext(answer.build());
      responses.onCompleted();
    }
  }

  /**
   * Prints the third-party licence notice and says whether that is all this run was for.
   *
   * <p>A permissive licence asks for its notice in <b>every copy</b>, and this image is a copy
   * (REQ-CON-013, [ADR-0083](../../../../../../../docs/adr/0083-the-notice-travels-inside-the-artifact.md)).
   * The notice is generated by {@code tools/notices.py} from what {@code installDist} writes into
   * {@code lib/}, committed, and packaged as a resource in this jar — the same shape the four Rust
   * plugins use, except that they compile theirs in because a {@code scratch} image has no
   * filesystem to read one from.
   *
   * <p>Before anything else in {@code main}: somebody asking for a licence should get the licence
   * rather than a warning about configuration this run was never going to use.
   *
   * @param arguments what the container was started with
   * @return true when the notice was printed and nothing else should happen
   * @throws IOException when the jar carries no notice, which is a build defect rather than a
   *     runtime condition
   */
  private static boolean licences(String[] arguments) throws IOException {
    if (Arrays.stream(arguments).noneMatch("--licences"::equals)) {
      return false;
    }
    try (InputStream notice = Main.class.getResourceAsStream("/THIRD-PARTY-NOTICES.txt")) {
      if (notice == null) {
        throw new IOException(
            "This artifact carries no third-party licence notice, which REQ-CON-013 requires of "
                + "every distributed artifact. Run `python tools/notices.py plugin-oidc`.");
      }
      notice.transferTo(System.out);
    }
    return true;
  }

}
