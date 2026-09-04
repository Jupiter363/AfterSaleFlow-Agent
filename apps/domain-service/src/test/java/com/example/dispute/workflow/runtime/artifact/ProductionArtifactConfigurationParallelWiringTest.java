package com.example.dispute.workflow.runtime.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionArtifactConfigurationParallelWiringTest {

  private static final Path CONFIGURATION_SOURCE =
      Path.of(
          "src/production-runtime/java/com/example/dispute/workflow/runtime/artifact/"
              + "ProductionArtifactConfiguration.java");

  @Test
  void exposesOneProfileSelectingGatewayWithLegacyAndParallelDelegates() throws IOException {
    String source = Files.readString(CONFIGURATION_SOURCE);
    String gateway = method(source, "productionAgentRunExecutionGateway");

    assertThat(source)
        .contains("productionIntakeParallelFrameExecutionClient")
        .contains("productionIntakeParallelAssemblyContextResolver")
        .contains("productionIntakeParallelAssemblyCoordinator");
    assertThat(gateway)
        .contains("new DurableAgentRunExecutionGateway(")
        .contains("new ProductionIntakeParallelExecutionGateway(")
        .contains("new ProductionIntakeParallelGraphReconciliationClient(assemblyCoordinator)")
        .contains("new ProfileSelectingAgentRunExecutionGateway(legacy, parallel)");
    assertThat(occurrences(source, "AgentRunExecutionGateway productionAgentRunExecutionGateway("))
        .isEqualTo(1);
    assertThat(source)
        .doesNotContain("@Bean\n    ProductionIntakeParallelExecutionGateway");
  }

  @Test
  void ownsTheAgentMaterialStoreRequiredByTheParallelContextResolver() throws IOException {
    String source = Files.readString(CONFIGURATION_SOURCE);
    String materialStore = method(source, "productionAgentIntakeCommandMaterialStore");

    assertThat(
            occurrences(
                source,
                "TargetIntakeCommandMaterialStore productionAgentIntakeCommandMaterialStore("))
        .isEqualTo(1);
    assertThat(materialStore)
        .contains("DataSource dataSource")
        .contains("ProductionActivationLedger productionAgentActivationLedger")
        .contains("ObjectMapper objectMapper")
        .contains("new JdbcTargetIntakeCommandMaterialStore(")
        .contains("dataSource, productionAgentActivationLedger, objectMapper");
  }

  @Test
  void pinsFinalizationRuntimeContextToTheCurrentDeploymentIdentity() throws IOException {
    String source = Files.readString(CONFIGURATION_SOURCE);
    String runtimeContext = method(source, "productionFinalizationRuntimeContextProvider");

    assertThat(runtimeContext)
        .contains("ProductionAgentDeploymentBinding deploymentBinding")
        .contains("Environment environment")
        .contains("deploymentBinding.agentBuildId()")
        .contains("deploymentBinding.activationId()")
        .contains("deploymentBinding.manifestHash()")
        .contains("required(environment, \"production.runtime.isolated-domain-db-binding-hash\")");
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
