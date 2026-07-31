package com.example.dispute.workflow.targete2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TargetE2eActivationLifecycleSurfaceTest {

  private static final Path CONTROLLER =
      Path.of(
          "src/target-e2e/java/com/example/dispute/workflow/targete2e/artifact/lifecycle/"
              + "TargetE2eActivationLifecycleController.java");
  private static final Path CONFIGURATION =
      Path.of(
          "src/target-e2e/java/com/example/dispute/workflow/targete2e/artifact/"
              + "TargetE2eArtifactConfiguration.java");
  private static final Path AUTHENTICATION_FILTER =
      Path.of(
          "src/main/java/com/example/dispute/config/HeaderAuthenticationFilter.java");
  private static final Path SECURITY_CONFIGURATION =
      Path.of("src/main/java/com/example/dispute/config/SecurityConfiguration.java");

  @Test
  void lifecycleCapabilityIsTargetOnlyInternalAndNeverMutatesSqlDirectly() throws Exception {
    String controller = Files.readString(CONTROLLER);
    String configuration = Files.readString(CONFIGURATION);
    String authenticationFilter = Files.readString(AUTHENTICATION_FILTER);
    String securityConfiguration = Files.readString(SECURITY_CONFIGURATION);

    assertThat(controller)
        .contains("@Hidden")
        .contains("@Profile(\"target-e2e & api\")")
        .contains("@RequestMapping(\"/internal/target-e2e/activation/lifecycle\")")
        .contains("X-Target-E2E-Lifecycle-Capability")
        .contains("serviceCapabilityMatches")
        .contains("target.e2e.lifecycle.service-capability")
        .contains("invalid target E2E lifecycle capability")
        .doesNotContain("invalid Java service credential")
        .doesNotContain("X-Service-Secret")
        .doesNotContain("properties.security().serviceSecret()")
        .doesNotContain("@GetMapping")
        .doesNotContain("JdbcTemplate")
        .doesNotContain("PreparedStatement")
        .doesNotContain("target_e2e_activation");
    assertThat(authenticationFilter)
        .contains("SERVICE_IDENTITY_HEADER = \"X-Service-Identity\"")
        .contains("SERVICE_SECRET_HEADER = \"X-Service-Secret\"")
        .contains("isValidActorId(serviceIdentity)")
        .contains("hasValidServiceSecret(suppliedServiceSecret)")
        .doesNotContain("X-Target-E2E-Lifecycle-Capability");
    assertThat(securityConfiguration)
        .contains(".requestMatchers(\"/internal/**\")")
        .contains(".hasRole(\"SYSTEM\")");
    assertThat(configuration)
        .contains("TargetE2eActivationLifecycleStore targetE2eAgentLifecycleStore")
        .contains("new JdbcTargetE2eActivationStores(dataSource, clock)")
        .contains("TargetE2eActivationLifecycleControl.bind(lifecycleStore, binding, clock)")
        .contains("environment.acceptsProfiles(Profiles.of(\"target-e2e\"))");
    String apiConfiguration =
        Files.readString(
            Path.of(
                "src/target-e2e/java/com/example/dispute/workflow/targete2e/artifact/lifecycle/"
                    + "TargetE2eApiLifecycleConfiguration.java"));
    assertThat(apiConfiguration)
        .contains("@Profile(\"target-e2e & api\")")
        .contains("JdbcTargetE2eActivationStores store")
        .doesNotContain("new JdbcTargetE2eActivationStores(dataSource, clock)")
        .contains("target.e2e.activation.manifest-hash");
  }
}
