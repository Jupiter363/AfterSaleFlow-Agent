package com.example.dispute.workflow.targete2e.artifact.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TargetE2eAgentRunV2RetryPreparationTest {

  private static final Path SOURCE = Path.of(
      "src/target-e2e/java/com/example/dispute/workflow/targete2e/artifact/recovery/"
          + "TargetE2eAgentRunV2RetryPreparation.java");

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

  private static String method(String source, String methodName) {
    int start = source.indexOf(methodName);
    int end = source.indexOf("\n  @Override", start + methodName.length());
    return source.substring(start, end < 0 ? source.length() : end);
  }
}
