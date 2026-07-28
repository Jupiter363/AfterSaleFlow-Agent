package com.example.dispute.workflow.targete2e;

import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eActivationStores;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Target-artifact-only composition; the manifest caller cannot supply runtime authority facts. */
@Configuration(proxyBeanMethods = false)
@Profile("target-e2e & control-worker")
@ConditionalOnProperty(name = "app.target-e2e.enabled", havingValue = "true")
public class TargetE2eActivationRuntimeConfiguration {

  private static final int MAXIMUM_ACTIVATION_BYTES = 48 * 1024;
  private static final int MAXIMUM_PUBLIC_KEY_BYTES = 16 * 1024;
  private static final int MAXIMUM_FIXTURE_BYTES = 256 * 1024;

  @Bean
  TargetE2eActivationPublicKeySet targetE2eActivationPublicKeySet(
      ConfigurableEnvironment environment) {
    return TargetE2eActivationPublicKeySet.allowlisted(
        loadPublicKeys(
            required(environment, "app.target-e2e.activation-public-keys"),
            "activation"));
  }

  @Bean
  TargetE2eIsolationAttestationPublicKeySet targetE2eIsolationAttestationPublicKeySet(
      ConfigurableEnvironment environment) {
    return TargetE2eIsolationAttestationPublicKeySet.allowlisted(
        loadPublicKeys(
            required(environment, "app.target-e2e.isolation-attestation-public-keys"),
            "isolation attestation"));
  }

  @Bean(name = "targetE2eGraphMeasurementDataSource")
  DataSource targetE2eGraphMeasurementDataSource(ConfigurableEnvironment environment) {
    String url = required(environment, "app.target-e2e.measurement.graph-datasource.url");
    if (!url.startsWith("jdbc:postgresql://")) {
      throw new IllegalStateException("target E2E Graph measurement JDBC URL must use PostgreSQL");
    }
    return new DriverManagerDataSource(
        url,
        required(environment, "app.target-e2e.measurement.graph-datasource.username"),
        required(environment, "app.target-e2e.measurement.graph-datasource.password"));
  }

  @Bean
  JdbcTargetE2eActivationStores targetE2eActivationStores(
      @Qualifier("dataSource") DataSource dataSource) {
    return new JdbcTargetE2eActivationStores(dataSource, Clock.systemUTC());
  }

  @Bean
  TargetE2eSyntheticFixtureSource targetE2eSyntheticFixtureSource(
      ConfigurableEnvironment environment) {
    String fixtureSetId =
        required(environment, "app.target-e2e.measurement.case-scope.fixture-set-id");
    String pathBinding =
        required(environment, "app.target-e2e.measurement.case-scope.fixture-read-only-path");
    Path path = Path.of(pathBinding).toAbsolutePath().normalize();
    return requestedFixtureSetId -> {
      if (!TargetE2eActivationContract.same(fixtureSetId, requestedFixtureSetId)) {
        throw new IllegalArgumentException("synthetic fixture set is not deployment-configured");
      }
      return new TargetE2eSyntheticFixtureSource.ConfiguredFixture(
          pathBinding, readBounded(path, MAXIMUM_FIXTURE_BYTES, "synthetic fixture"));
    };
  }

  @Bean
  TargetE2eRuntimeMeasurementProvider targetE2eRuntimeMeasurementProvider(
      ConfigurableEnvironment environment,
      @Qualifier("dataSource") DataSource dataSource,
      @Qualifier("targetE2eGraphMeasurementDataSource") DataSource graphMeasurementDataSource,
      TargetE2eIsolationAttestationPublicKeySet isolationAttestationPublicKeys) {
    return new SpringJdbcTargetE2eRuntimeMeasurementProvider(
        environment,
        dataSource,
        graphMeasurementDataSource,
        isolationAttestationPublicKeys,
        Clock.systemUTC());
  }

  @Bean
  TargetE2eActivationManifestVerifier targetE2eActivationManifestVerifier(
      TargetE2eActivationPublicKeySet activationPublicKeys,
      TargetE2eActivationReplayStore replayStore,
      TargetE2eActivationCaseLedger caseLedger,
      TargetE2eActivationLifecycleStore lifecycleStore,
      TargetE2eSyntheticFixtureSource fixtureSource,
      TargetE2eRuntimeMeasurementProvider measurementProvider) {
    return new TargetE2eActivationManifestVerifier(
        activationPublicKeys,
        replayStore,
        caseLedger,
        lifecycleStore,
        fixtureSource,
        measurementProvider,
        Clock.systemUTC());
  }

  @Bean
  TargetE2eActivationAuthority targetE2eActivationAuthority(
      TargetE2eActivationManifestVerifier verifier, ConfigurableEnvironment environment) {
    String configuredPath = environment.getProperty("app.target-e2e.activation-manifest-path");
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalStateException("target E2E activation manifest path is required");
    }
    TargetE2eActivationAuthority authority =
        verifier.arm(readBounded(Path.of(configuredPath).toAbsolutePath().normalize()));
    ActivationDecision probe = authority.authorize(null);
    if (probe.reason() != ActivationDecision.Reason.WRONG_TARGET) {
      throw new IllegalStateException(
          "target E2E activation did not arm: " + probe.reason().name());
    }
    return authority;
  }

  private static String readBounded(Path path) {
    return new String(
            readBounded(path, MAXIMUM_ACTIVATION_BYTES, "target E2E activation manifest"),
            StandardCharsets.US_ASCII)
        .trim();
  }

  private static byte[] readBounded(Path path, int maximumBytes, String label) {
    try {
      long size = Files.size(path);
      if (Files.isSymbolicLink(path)
          || !Files.isRegularFile(path)
          || size < 1
          || size > maximumBytes) {
        throw new IllegalStateException(label + " file is invalid");
      }
      byte[] bytes = Files.readAllBytes(path);
      if (bytes.length < 1 || bytes.length > maximumBytes) {
        throw new IllegalStateException(label + " file is invalid");
      }
      return bytes;
    } catch (IOException failure) {
      throw new IllegalStateException(label + " is unreachable", failure);
    }
  }

  private static Map<String, ECPublicKey> loadPublicKeys(String specification, String label) {
    Map<String, ECPublicKey> keys = new LinkedHashMap<>();
    for (String entry : specification.split(";", -1)) {
      int separator = entry.indexOf('=');
      if (separator < 1 || separator == entry.length() - 1) {
        throw new IllegalStateException(label + " public key specification is invalid");
      }
      String keyId = entry.substring(0, separator).trim();
      String configuredPath = entry.substring(separator + 1).trim();
      if (keyId.isEmpty() || configuredPath.isEmpty() || keys.containsKey(keyId)) {
        throw new IllegalStateException(label + " public key specification is invalid");
      }
      keys.put(keyId, readPublicKey(Path.of(configuredPath).toAbsolutePath().normalize(), label));
    }
    if (keys.isEmpty() || keys.size() > 16) {
      throw new IllegalStateException(label + " public key count must be inside 1..16");
    }
    return Map.copyOf(keys);
  }

  private static ECPublicKey readPublicKey(Path path, String label) {
    byte[] bytes = readBounded(path, MAXIMUM_PUBLIC_KEY_BYTES, label + " public key");
    for (byte value : bytes) {
      if ((value & 0x80) != 0) {
        throw new IllegalStateException(label + " public key PEM must be ASCII");
      }
    }
    String pem = new String(bytes, StandardCharsets.US_ASCII).replace("\r\n", "\n").trim();
    String begin = "-----BEGIN PUBLIC KEY-----";
    String end = "-----END PUBLIC KEY-----";
    if (!pem.startsWith(begin + "\n") || !pem.endsWith("\n" + end)) {
      throw new IllegalStateException(label + " public key must be an X.509 PUBLIC KEY PEM");
    }
    String body = pem.substring(begin.length() + 1, pem.length() - end.length() - 1);
    if (body.isBlank()) {
      throw new IllegalStateException(label + " public key PEM body is empty");
    }
    for (int index = 0; index < body.length(); index++) {
      char value = body.charAt(index);
      if (!(value == '\n'
          || value == ' '
          || value == '\t'
          || value == '='
          || value == '+'
          || value == '/'
          || (value >= '0' && value <= '9')
          || (value >= 'A' && value <= 'Z')
          || (value >= 'a' && value <= 'z'))) {
        throw new IllegalStateException(label + " public key PEM body is invalid");
      }
    }
    try {
      byte[] encoded = Base64.getMimeDecoder().decode(body);
      try {
        return (ECPublicKey)
            KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
      } finally {
        java.util.Arrays.fill(encoded, (byte) 0);
      }
    } catch (ClassCastException | IllegalArgumentException | GeneralSecurityException failure) {
      throw new IllegalStateException(label + " public key is invalid", failure);
    }
  }

  private static String required(ConfigurableEnvironment environment, String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required target E2E property is absent: " + property);
    }
    return value.trim();
  }
}
