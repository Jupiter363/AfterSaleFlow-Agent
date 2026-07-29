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

  @Test
  void lifecycleCapabilityIsTargetOnlyInternalAndNeverMutatesSqlDirectly() throws Exception {
    String controller = Files.readString(CONTROLLER);
    String configuration = Files.readString(CONFIGURATION);

    assertThat(controller)
        .contains("@Hidden")
        .contains("@Profile(\"target-e2e & api\")")
        .contains("@RequestMapping(\"/internal/target-e2e/activation/lifecycle\")")
        .contains("X-Service-Secret")
        .contains("serviceCapabilityMatches")
        .contains("target.e2e.lifecycle.service-capability")
        .doesNotContain("properties.security().serviceSecret()")
        .doesNotContain("@GetMapping")
        .doesNotContain("JdbcTemplate")
        .doesNotContain("PreparedStatement")
        .doesNotContain("target_e2e_activation");
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
