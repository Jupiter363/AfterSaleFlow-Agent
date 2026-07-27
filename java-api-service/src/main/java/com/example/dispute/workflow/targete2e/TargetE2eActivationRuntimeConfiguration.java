package com.example.dispute.workflow.targete2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;

/** Target-artifact-only composition; the manifest caller cannot supply runtime authority facts. */
@Configuration(proxyBeanMethods = false)
@Profile("target-e2e & agent-worker")
@ConditionalOnProperty(name = "app.target-e2e.enabled", havingValue = "true")
public class TargetE2eActivationRuntimeConfiguration {

  private static final int MAXIMUM_ACTIVATION_BYTES = 48 * 1024;

  @Bean
  TargetE2eRuntimeMeasurementProvider targetE2eRuntimeMeasurementProvider(
      ConfigurableEnvironment environment,
      DataSource dataSource,
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
    return verifier.arm(readBounded(Path.of(configuredPath).toAbsolutePath().normalize()));
  }

  private static String readBounded(Path path) {
    try {
      long size = Files.size(path);
      if (!Files.isRegularFile(path) || size < 1 || size > MAXIMUM_ACTIVATION_BYTES) {
        throw new IllegalStateException("target E2E activation manifest file is invalid");
      }
      return Files.readString(path, StandardCharsets.US_ASCII).trim();
    } catch (IOException failure) {
      throw new IllegalStateException("target E2E activation manifest is unreachable", failure);
    }
  }
}
