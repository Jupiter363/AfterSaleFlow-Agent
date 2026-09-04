package com.example.dispute.workflow.runtime.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionApiConfigurationAssemblyTest {

  private static final Path CONFIGURATION_SOURCE =
      Path.of(
          "src/production-runtime/java/com/example/dispute/workflow/runtime/artifact/"
              + "ProductionApiConfiguration.java");
  private static final Path SYNTHETIC_CASE_ID_FACTORY_SOURCE =
      Path.of(
          "src/production-runtime/java/com/example/dispute/workflow/runtime/artifact/"
              + "ProductionSyntheticCaseIdFactory.java");

  @Test
  void exchangeAssemblyReadsBrowserMessagesAndWritesGraphProposals() throws IOException {
    String source = Files.readString(CONFIGURATION_SOURCE);
    String payloadReader = method(source, "productionIntakeExchangePayloadReader");
    String proposalPublisher = method(source, "productionIntakeExchangeProposalPublisher");
    String exchangeService = method(source, "productionIntakeExchangeService");

    assertThat(source)
        .contains("INTAKE_EXCHANGE_PAYLOAD_PREFIX = \"browser-messages\"")
        .contains("INTAKE_EXCHANGE_PROPOSAL_PREFIX = \"graph-proposals\"");
    assertThat(payloadReader).contains("INTAKE_EXCHANGE_PAYLOAD_PREFIX");
    assertThat(proposalPublisher).contains("INTAKE_EXCHANGE_PROPOSAL_PREFIX");
    assertThat(exchangeService)
        .contains("@Qualifier(\"productionIntakeExchangePayloadReader\")")
        .contains("@Qualifier(\"productionIntakeExchangeProposalPublisher\")")
        .contains("new IntakePrivateObjectStoreExchangeAdapter(payloadReader, proposalStore)");
  }

  @Test
  void intakeMaterializationAssemblyActivatesOnlyTheAuthenticatedParty() throws IOException {
    String source = Files.readString(CONFIGURATION_SOURCE);
    String materializer = method(source, "targetIntakeMaterializer");

    assertThat(materializer)
        .contains("ParticipantService participants")
        .contains("accessSessions, agentSessions, participants");
  }

  @Test
  void retryPreparationDoesNotDependOnTheAgentWorkerCodecBean() throws IOException {
    String source = Files.readString(CONFIGURATION_SOURCE);
    String preparation = method(source, "productionAgentRunV2RetryPreparation");

    assertThat(preparation)
        .contains("new ProductionGraphEnvelopeCodec(objectMapper)")
        .doesNotContain("ProductionGraphEnvelopeCodec envelopes");
  }

  @Test
  void syntheticCaseAllocationPublishesStableActivationFailureCodes() throws IOException {
    String source = Files.readString(SYNTHETIC_CASE_ID_FACTORY_SOURCE);

    assertThat(source)
        .contains("ErrorCode.PRODUCTION_RUNTIME_ACTIVATION_UNAVAILABLE")
        .contains("ErrorCode.PRODUCTION_RUNTIME_ACTIVATION_EXPIRED")
        .contains("ErrorCode.PRODUCTION_RUNTIME_CASE_CAPACITY_EXHAUSTED")
        .doesNotContain("production runtime activation is not registered")
        .doesNotContain("production runtime synthetic activation scope is not live and exact");
  }

  private static String method(String source, String methodName) {
    int start = source.indexOf(methodName);
    int end = source.indexOf("\n  @Bean", start + methodName.length());
    return source.substring(start, end < 0 ? source.length() : end);
  }
}
