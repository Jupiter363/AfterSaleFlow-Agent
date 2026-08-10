package com.example.dispute.workflow.targete2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eApiAuthority;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochSelectionAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

class TargetE2eActivationRuntimeConfigurationTest {

  private static final Path TARGET_CONTROL_CONFIGURATION =
      Path.of(
          "src/target-e2e/java/com/example/dispute/workflow/targete2e/artifact/"
              + "TargetE2eControlConfiguration.java");

  @TempDir Path temporaryDirectory;

  @Test
  void loadsIndependentTrustSetsFixtureAndGraphMeasurementDataSource() throws Exception {
    KeyPair activation = p256();
    KeyPair attestation = p256();
    Path activationKey = writePem("activation.pem", activation);
    Path attestationKey = writePem("attestation.pem", attestation);
    Path fixture = temporaryDirectory.resolve("fixture.json");
    byte[] fixtureBytes = "{\"schemaVersion\":\"test\"}".getBytes(StandardCharsets.US_ASCII);
    Files.write(fixture, fixtureBytes);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("app.target-e2e.activation-public-keys", "activation-key=" + activationKey)
            .withProperty(
                "app.target-e2e.isolation-attestation-public-keys",
                "attestation-key=" + attestationKey)
            .withProperty(
                "app.target-e2e.measurement.graph-datasource.url",
                "jdbc:postgresql://graph-db:5432/target_graph")
            .withProperty(
                "app.target-e2e.measurement.graph-datasource.username", "graph_measurement")
            .withProperty(
                "app.target-e2e.measurement.graph-datasource.password", "measurement-secret")
            .withProperty(
                "app.target-e2e.measurement.case-scope.fixture-set-id", "fixture-set-1")
            .withProperty(
                "app.target-e2e.measurement.case-scope.fixture-read-only-path",
                fixture.toString());
    TargetE2eActivationRuntimeConfiguration configuration =
        new TargetE2eActivationRuntimeConfiguration();

    assertThat(configuration.targetE2eActivationPublicKeySet(environment).resolve("activation-key"))
        .isPresent();
    assertThat(
            configuration
                .targetE2eIsolationAttestationPublicKeySet(environment)
                .resolve("attestation-key"))
        .isPresent();
    DriverManagerDataSource graphDataSource =
        (DriverManagerDataSource) configuration.targetE2eGraphMeasurementDataSource(environment);
    assertThat(graphDataSource.getUrl()).isEqualTo("jdbc:postgresql://graph-db:5432/target_graph");
    assertThat(
            TargetE2eActivationRuntimeConfiguration.class
                .getDeclaredMethod(
                    "targetE2eGraphMeasurementDataSource", ConfigurableEnvironment.class)
                .isAnnotationPresent(Bean.class))
        .isFalse();
    TargetE2eSyntheticFixtureSource.ConfiguredFixture loaded =
        configuration.targetE2eSyntheticFixtureSource(environment).loadConfigured("fixture-set-1");
    assertThat(loaded.readOnlyPathBinding()).isEqualTo(fixture.toString());
    assertThat(loaded.bytes()).isEqualTo(fixtureBytes);
  }

  @Test
  void rejectsNonEcTrustMaterial() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    Path rsaKey = writePem("rsa.pem", generator.generateKeyPair());
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("app.target-e2e.activation-public-keys", "activation-key=" + rsaKey);

    assertThatThrownBy(
            () ->
                new TargetE2eActivationRuntimeConfiguration()
                    .targetE2eActivationPublicKeySet(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("activation public key is invalid");
  }

  @Test
  void fixtureSourceRejectsAnUnconfiguredFixtureSet() throws Exception {
    Path fixture = temporaryDirectory.resolve("fixture.json");
    Files.writeString(fixture, "{}");
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(
                "app.target-e2e.measurement.case-scope.fixture-set-id", "fixture-set-1")
            .withProperty(
                "app.target-e2e.measurement.case-scope.fixture-read-only-path",
                fixture.toString());
    TargetE2eSyntheticFixtureSource source =
        new TargetE2eActivationRuntimeConfiguration()
            .targetE2eSyntheticFixtureSource(environment);

    assertThatThrownBy(() -> source.loadConfigured("fixture-set-2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not deployment-configured");
  }

  @Test
  void activationAuthorityOverridesControlWorkerLazyInitialization() throws Exception {
    Lazy lazy =
        TargetE2eActivationRuntimeConfiguration.class
            .getDeclaredMethod(
                "targetE2eActivationAuthority",
                TargetE2eActivationManifestVerifier.class,
                ConfigurableEnvironment.class)
            .getAnnotation(Lazy.class);

    assertThat(lazy).isNotNull();
    assertThat(lazy.value()).isFalse();
  }

  @Test
  void targetControlRegistrationResolvesActivationAuthorityBeforeWorkerRegistration()
      throws Exception {
    String configuration = Files.readString(TARGET_CONTROL_CONFIGURATION);
    int authorityRequirement =
        configuration.indexOf("requireArmedActivationAuthorityIfEnabled(");
    int registrationConstruction =
        configuration.indexOf("new TargetTemporalWorkerRegistration.Registration(");

    assertThat(configuration)
        .contains(
            "ObjectProvider<TargetE2eActivationAuthority> "
                + "targetE2eActivationAuthorityProvider")
        .contains("targetE2eActivationAuthorityProvider.getIfUnique()")
        .contains("environment.getProperty(\"app.target-e2e.enabled\", Boolean.class, false)");
    assertThat(authorityRequirement).isGreaterThanOrEqualTo(0);
    assertThat(registrationConstruction).isGreaterThan(authorityRequirement);
  }

  @Test
  void targetControlPublishesSingleRoomEpochSelectionAuthorityAndReusesItForHearingMaterializer()
      throws Exception {
    String configuration =
        Files.readString(TARGET_CONTROL_CONFIGURATION).replace("\r\n", "\n");

    assertThat(TargetRoomEpochSelectionAuthority.class)
        .isAssignableFrom(JdbcTargetE2eApiAuthority.class);
    assertThat(configuration)
        .contains(
            "@Bean\n"
                + "  JdbcTargetE2eApiAuthority targetRoomEpochSelectionAuthority(\n"
                + "      DataSource dataSource, Environment environment) {\n"
                + "    Clock clock = Clock.systemUTC();\n"
                + "    return new JdbcTargetE2eApiAuthority(\n"
                + "        dataSource,\n"
                + "        new JdbcTargetE2eActivationStores(dataSource, clock),\n"
                + "        required(environment, \"target.e2e.activation.id\"),\n"
                + "        clock);\n"
                + "  }")
        .contains("JdbcTargetE2eApiAuthority targetRoomEpochSelectionAuthority,")
        .contains(
            "new TargetHearingInternalStageMaterializer(\n"
                + "        targetRoomEpochSelectionAuthority,")
        .doesNotContain("var apiAuthority");
    assertThat(
            configuration.split(
                "JdbcTargetE2eApiAuthority targetRoomEpochSelectionAuthority\\(", -1))
        .hasSize(2);
    assertThat(configuration.split("new JdbcTargetE2eApiAuthority\\(", -1)).hasSize(2);
  }

  private KeyPair p256() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private Path writePem(String name, KeyPair keyPair) throws Exception {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(keyPair.getPublic().getEncoded());
    Path path = temporaryDirectory.resolve(name);
    Files.writeString(
        path,
        "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n",
        StandardCharsets.US_ASCII);
    return path;
  }
}
