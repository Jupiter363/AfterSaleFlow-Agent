package com.example.dispute.workflow.runtime.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionActivationLifecycleSurfaceTest {

  private static final Path CONTROLLER =
      Path.of(
          "src/production-runtime/java/com/example/dispute/workflow/runtime/artifact/lifecycle/"
              + "ProductionActivationLifecycleController.java");
  private static final Path CONFIGURATION =
      Path.of(
          "src/production-runtime/java/com/example/dispute/workflow/runtime/artifact/"
              + "ProductionArtifactConfiguration.java");
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
        .contains("@Profile(\"production-runtime & api\")")
        .contains("@RequestMapping(\"/internal/production-runtime/activation/lifecycle\")")
        .contains("X-Production-Runtime-Lifecycle-Capability")
        .contains("serviceCapabilityMatches")
        .contains("production.runtime.lifecycle.service-capability")
        .contains("invalid production runtime lifecycle capability")
        .doesNotContain("invalid Java service credential")
        .doesNotContain("X-Service-Secret")
        .doesNotContain("properties.security().serviceSecret()")
        .doesNotContain("@GetMapping")
        .doesNotContain("JdbcTemplate")
        .doesNotContain("PreparedStatement")
        .doesNotContain("production_runtime_activation");
    assertThat(authenticationFilter)
        .contains("SERVICE_IDENTITY_HEADER = \"X-Service-Identity\"")
        .contains("SERVICE_SECRET_HEADER = \"X-Service-Secret\"")
        .contains("isValidActorId(serviceIdentity)")
        .contains("hasValidServiceSecret(suppliedServiceSecret)")
        .doesNotContain("X-Production-Runtime-Lifecycle-Capability");
    assertThat(securityConfiguration)
        .contains(".requestMatchers(\"/internal/**\")")
        .contains(".hasRole(\"SYSTEM\")");
    assertThat(configuration)
        .contains("ProductionActivationLifecycleStore productionAgentLifecycleStore")
        .contains("new JdbcProductionActivationStores(dataSource, clock)")
        .contains("ProductionActivationLifecycleControl.bind(lifecycleStore, binding, clock)")
        .contains("environment.acceptsProfiles(Profiles.of(\"production-runtime\"))");
    String apiConfiguration =
        Files.readString(
            Path.of(
                "src/production-runtime/java/com/example/dispute/workflow/runtime/artifact/lifecycle/"
                    + "ProductionApiLifecycleConfiguration.java"));
    assertThat(apiConfiguration)
        .contains("@Profile(\"production-runtime & api\")")
        .contains("JdbcProductionActivationStores store")
        .doesNotContain("new JdbcProductionActivationStores(dataSource, clock)")
        .contains("production.runtime.activation.manifest-hash");
  }
}
