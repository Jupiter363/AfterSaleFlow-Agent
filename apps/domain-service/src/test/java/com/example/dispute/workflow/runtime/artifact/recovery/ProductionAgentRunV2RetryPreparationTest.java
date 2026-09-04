package com.example.dispute.workflow.runtime.artifact.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionAgentRunV2RetryPreparationTest {

  private static final Path SOURCE = Path.of(
      "src/production-runtime/java/com/example/dispute/workflow/runtime/artifact/recovery/"
          + "ProductionAgentRunV2RetryPreparation.java");

  @Test
  void reservesInitialAttemptReplayForTheTargetRoomWorkflow() throws IOException {
    String source = Files.readString(SOURCE);
    String policy = method(source, "mayReplayInitialAttemptFromRecovery");
    String supports = method(source, "supports");

    assertThat(policy)
        .contains("Objects.requireNonNull(state, \"state\");")
        .contains("return false;");
    assertThat(supports)
        .contains("state.logicalRun().protocol() != AgentRunProtocol.V3")
        .doesNotContain("state.logicalRun().protocol() != AgentRunProtocol.V2");
  }

  @Test
  void hearingRetryCarriesTheExactPredecessorPartyStageAuthority() throws IOException {
    String source = Files.readString(SOURCE);
    String persistHearing = privateMethod(source, "persistHearing");

    assertThat(persistHearing)
        .contains("TargetHearingCommandMaterial source = previous.material();")
        .contains("source.schemaVersion(), admission, request, source.partyStageAuthority()")
        .contains("rebind(previous.admission(), source.request().command()")
        .doesNotContain(
            "TargetHearingCommandMaterial.SCHEMA_VERSION, admission, request,\n"
                + "        sealed.commandHash()");
  }

  private static String method(String source, String methodName) {
    int start = source.indexOf(methodName);
    int end = source.indexOf("\n  @Override", start + methodName.length());
    return source.substring(start, end < 0 ? source.length() : end);
  }

  private static String privateMethod(String source, String methodName) {
    String declaration = "\n  private void " + methodName + "(";
    int start = source.indexOf(declaration);
    int end = source.indexOf("\n  private ", start + declaration.length());
    return source.substring(start, end < 0 ? source.length() : end);
  }
}
