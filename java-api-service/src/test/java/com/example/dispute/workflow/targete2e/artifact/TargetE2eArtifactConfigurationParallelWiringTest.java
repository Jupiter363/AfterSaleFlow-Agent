package com.example.dispute.workflow.targete2e.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TargetE2eArtifactConfigurationParallelWiringTest {

  private static final Path CONFIGURATION_SOURCE =
      Path.of(
          "src/target-e2e/java/com/example/dispute/workflow/targete2e/artifact/"
              + "TargetE2eArtifactConfiguration.java");

  @Test
  void exposesOneProfileSelectingGatewayWithLegacyAndParallelDelegates() throws IOException {
    String source = Files.readString(CONFIGURATION_SOURCE);
    String gateway = method(source, "targetE2EAgentRunExecutionGateway");

    assertThat(source)
        .contains("targetE2EIntakeParallelFrameExecutionClient")
        .contains("targetE2EIntakeParallelAssemblyContextResolver")
        .contains("targetE2EIntakeParallelAssemblyCoordinator");
    assertThat(gateway)
        .contains("new DurableAgentRunExecutionGateway(")
        .contains("new TargetE2EIntakeParallelExecutionGateway(")
        .contains("new TargetE2EIntakeParallelGraphReconciliationClient(assemblyCoordinator)")
        .contains("new ProfileSelectingAgentRunExecutionGateway(legacy, parallel)");
    assertThat(occurrences(source, "AgentRunExecutionGateway targetE2EAgentRunExecutionGateway("))
        .isEqualTo(1);
    assertThat(source)
        .doesNotContain("@Bean\n    TargetE2EIntakeParallelExecutionGateway");
  }

  @Test
  void ownsTheAgentMaterialStoreRequiredByTheParallelContextResolver() throws IOException {
    String source = Files.readString(CONFIGURATION_SOURCE);
    String materialStore = method(source, "targetE2eAgentIntakeCommandMaterialStore");

    assertThat(
            occurrences(
                source,
                "TargetIntakeCommandMaterialStore targetE2eAgentIntakeCommandMaterialStore("))
        .isEqualTo(1);
    assertThat(materialStore)
        .contains("DataSource dataSource")
        .contains("TargetE2EActivationLedger targetE2eAgentActivationLedger")
        .contains("ObjectMapper objectMapper")
        .contains("new JdbcTargetIntakeCommandMaterialStore(")
        .contains("dataSource, targetE2eAgentActivationLedger, objectMapper");
  }

  private static String method(String source, String methodName) {
    int start = source.indexOf(methodName);
    int end = source.indexOf("\n    @Bean", start + methodName.length());
    return source.substring(start, end < 0 ? source.length() : end);
  }

  private static int occurrences(String source, String value) {
    int count = 0;
    int cursor = 0;
    while ((cursor = source.indexOf(value, cursor)) >= 0) {
      count++;
      cursor += value.length();
    }
    return count;
  }
}
