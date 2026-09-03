package com.example.dispute.workflow.targete2e.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TargetE2eApiConfigurationAssemblyTest {

  private static final Path CONFIGURATION_SOURCE =
      Path.of(
          "src/target-e2e/java/com/example/dispute/workflow/targete2e/artifact/"
              + "TargetE2eApiConfiguration.java");
  private static final Path SYNTHETIC_CASE_ID_FACTORY_SOURCE =
      Path.of(
          "src/target-e2e/java/com/example/dispute/workflow/targete2e/artifact/"
              + "TargetE2eSyntheticCaseIdFactory.java");

  @Test
  void exchangeAssemblyReadsBrowserMessagesAndWritesGraphProposals() throws IOException {
    String source = Files.readString(CONFIGURATION_SOURCE);
    String payloadReader = method(source, "targetE2eIntakeExchangePayloadReader");
    String proposalPublisher = method(source, "targetE2eIntakeExchangeProposalPublisher");
    String exchangeService = method(source, "targetE2eIntakeExchangeService");

    assertThat(source)
        .contains("INTAKE_EXCHANGE_PAYLOAD_PREFIX = \"browser-messages\"")
        .contains("INTAKE_EXCHANGE_PROPOSAL_PREFIX = \"graph-proposals\"");
    assertThat(payloadReader).contains("INTAKE_EXCHANGE_PAYLOAD_PREFIX");
    assertThat(proposalPublisher).contains("INTAKE_EXCHANGE_PROPOSAL_PREFIX");
    assertThat(exchangeService)
        .contains("@Qualifier(\"targetE2eIntakeExchangePayloadReader\")")
        .contains("@Qualifier(\"targetE2eIntakeExchangeProposalPublisher\")")
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
    String preparation = method(source, "targetE2eAgentRunV2RetryPreparation");

    assertThat(preparation)
        .contains("new TargetE2EGraphEnvelopeCodec(objectMapper)")
        .doesNotContain("TargetE2EGraphEnvelopeCodec envelopes");
  }

  @Test
  void syntheticCaseAllocationPublishesStableActivationFailureCodes() throws IOException {
    String source = Files.readString(SYNTHETIC_CASE_ID_FACTORY_SOURCE);

    assertThat(source)
        .contains("ErrorCode.TARGET_E2E_ACTIVATION_UNAVAILABLE")
        .contains("ErrorCode.TARGET_E2E_ACTIVATION_EXPIRED")
        .contains("ErrorCode.TARGET_E2E_CASE_CAPACITY_EXHAUSTED")
        .doesNotContain("target E2E activation is not registered")
        .doesNotContain("target E2E synthetic activation scope is not live and exact");
  }

  private static String method(String source, String methodName) {
    int start = source.indexOf(methodName);
    int end = source.indexOf("\n  @Bean", start + methodName.length());
    return source.substring(start, end < 0 ? source.length() : end);
  }
}
