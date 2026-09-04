package com.example.dispute.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionApiAuthority;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionActivationStores;
import com.example.dispute.workflow.runtime.temporal.TargetRoomEpochSelectionAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

class ProductionActivationRuntimeConfigurationTest {

  private static final Path TARGET_CONTROL_CONFIGURATION =
      Path.of(
          "src/production-runtime/java/com/example/dispute/workflow/runtime/artifact/"
              + "ProductionControlConfiguration.java");

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
            .withProperty("app.production-runtime.activation-public-keys", "activation-key=" + activationKey)
            .withProperty(
                "app.production-runtime.isolation-attestation-public-keys",
                "attestation-key=" + attestationKey)
            .withProperty(
                "app.production-runtime.measurement.graph-datasource.url",
                "jdbc:postgresql://graph-db:5432/production_graph")
            .withProperty(
                "app.production-runtime.measurement.graph-datasource.username", "graph_measurement")
            .withProperty(
                "app.production-runtime.measurement.graph-datasource.password", "measurement-secret")
            .withProperty(
                "app.production-runtime.measurement.case-scope.fixture-set-id", "fixture-set-1")
            .withProperty(
                "app.production-runtime.measurement.case-scope.fixture-read-only-path",
                fixture.toString());
    ProductionActivationRuntimeConfiguration configuration =
        new ProductionActivationRuntimeConfiguration();

    assertThat(configuration.productionActivationPublicKeySet(environment).resolve("activation-key"))
        .isPresent();
    assertThat(
            configuration
                .productionIsolationAttestationPublicKeySet(environment)
                .resolve("attestation-key"))
        .isPresent();
    DriverManagerDataSource graphDataSource =
        (DriverManagerDataSource) configuration.productionGraphMeasurementDataSource(environment);
    assertThat(graphDataSource.getUrl()).isEqualTo("jdbc:postgresql://graph-db:5432/production_graph");
    assertThat(
            ProductionActivationRuntimeConfiguration.class
                .getDeclaredMethod(
                    "productionGraphMeasurementDataSource", ConfigurableEnvironment.class)
                .isAnnotationPresent(Bean.class))
        .isFalse();
    ProductionSyntheticFixtureSource.ConfiguredFixture loaded =
        configuration.productionSyntheticFixtureSource(environment).loadConfigured("fixture-set-1");
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
            .withProperty("app.production-runtime.activation-public-keys", "activation-key=" + rsaKey);

    assertThatThrownBy(
            () ->
                new ProductionActivationRuntimeConfiguration()
                    .productionActivationPublicKeySet(environment))
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
                "app.production-runtime.measurement.case-scope.fixture-set-id", "fixture-set-1")
            .withProperty(
                "app.production-runtime.measurement.case-scope.fixture-read-only-path",
                fixture.toString());
    ProductionSyntheticFixtureSource source =
        new ProductionActivationRuntimeConfiguration()
            .productionSyntheticFixtureSource(environment);

    assertThatThrownBy(() -> source.loadConfigured("fixture-set-2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not deployment-configured");
  }

  @Test
  void activationAuthorityOverridesControlWorkerLazyInitialization() throws Exception {
    Lazy lazy =
        ProductionActivationRuntimeConfiguration.class
            .getDeclaredMethod(
                "productionActivationAuthority",
                ProductionActivationManifestVerifier.class,
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
        configuration.indexOf("new ProductionTemporalWorkerRegistration.Registration(");

    assertThat(configuration)
        .contains(
            "ObjectProvider<ProductionActivationAuthority> "
                + "productionActivationAuthorityProvider")
        .contains("productionActivationAuthorityProvider.getIfUnique()")
        .contains("environment.getProperty(\"app.production-runtime.enabled\", Boolean.class, false)");
    assertThat(authorityRequirement).isGreaterThanOrEqualTo(0);
    assertThat(registrationConstruction).isGreaterThan(authorityRequirement);
  }

  @Test
  void targetOutcomeCompletionIsRegisteredForBothParentAndOutcomeRoomWorkers()
      throws Exception {
    String configuration = Files.readString(TARGET_CONTROL_CONFIGURATION);
    int registrationStart =
        configuration.indexOf("new ProductionTemporalWorkerRegistration.Registration(");
    int registrationEnd = configuration.indexOf("return () -> registration;", registrationStart);

    assertThat(registrationStart).isGreaterThanOrEqualTo(0);
    assertThat(registrationEnd).isGreaterThan(registrationStart);
    assertThat(
            configuration
                .substring(registrationStart, registrationEnd)
                .split("targetOutcomeCompletionActivities", -1))
        .hasSize(3);
  }

  @Test
  void targetControlPublishesSingleSharedActivationStoreAndReusesItForAllAuthorities()
      throws Exception {
    String configuration =
        Files.readString(TARGET_CONTROL_CONFIGURATION).replace("\r\n", "\n");

    assertThat(TargetRoomEpochSelectionAuthority.class)
        .isAssignableFrom(JdbcProductionApiAuthority.class);
    assertThat(ProductionActivationLifecycleStore.class)
        .isAssignableFrom(JdbcProductionActivationStores.class);
    assertThat(configuration)
        .contains(
            "@Bean\n"
                + "  JdbcProductionActivationStores productionControlActivationStores(\n"
                + "      DataSource dataSource) {\n"
                + "    return new JdbcProductionActivationStores(dataSource, Clock.systemUTC());\n"
                + "  }")
        .contains(
            "@Bean\n"
                + "  JdbcProductionApiAuthority targetRoomEpochSelectionAuthority(\n"
                + "      DataSource dataSource, Environment environment,\n"
                + "      JdbcProductionActivationStores productionControlActivationStores) {\n"
                + "    Clock clock = Clock.systemUTC();\n"
                + "    return new JdbcProductionApiAuthority(\n"
                + "        dataSource,\n"
                + "        productionControlActivationStores,\n"
                + "        required(environment, \"production.runtime.activation.id\"),\n"
                + "        clock);\n"
                + "  }")
        .contains(
            "ProductionActivationLifecycleStore productionControlActivationStores,")
        .contains(
            "productionControlActivationStores, evidenceDossierFreezer,")
        .contains("JdbcProductionApiAuthority targetRoomEpochSelectionAuthority,")
        .contains(
            "new TargetHearingInternalStageMaterializer(\n"
                + "        targetRoomEpochSelectionAuthority,")
        .doesNotContain("var apiAuthority")
        .doesNotContain("ObjectProvider<ProductionActivationLifecycleStore>")
        .doesNotContain("new JdbcProductionActivationStores(dataSource, clock)");
    assertThat(
            configuration.split(
                "JdbcProductionApiAuthority targetRoomEpochSelectionAuthority\\(", -1))
        .hasSize(2);
    assertThat(configuration.split("new JdbcProductionApiAuthority\\(", -1)).hasSize(2);
    assertThat(
            configuration.split(
                "JdbcProductionActivationStores productionControlActivationStores\\(", -1))
        .hasSize(2);
    assertThat(configuration.split("new JdbcProductionActivationStores\\(", -1)).hasSize(2);
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
