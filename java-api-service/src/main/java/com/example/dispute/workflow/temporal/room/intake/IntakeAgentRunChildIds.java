package com.example.dispute.workflow.temporal.room.intake;

import com.example.dispute.workflow.contract.v1.AgentRunWorkflowIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Deterministic and bounded Temporal child identity. */
public final class IntakeAgentRunChildIds {

  private static final String PREFIX = "intake-agent-run:";
  private static final int MAX_LENGTH = 128;
  private static final Pattern IDENTIFIER =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

  private IntakeAgentRunChildIds() {}

  public static String forCommand(IntakeWorkflowCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("command must not be null");
    }
    IntakeCommandExecutionContext context = command.executionContext();
    if (context != null && context.isTargetAgentRun()) {
      return AgentRunWorkflowIds.forLogicalRun(
          context.targetAgentRun().request().logicalRunId());
    }
    String identity = command.caseId() + ":" + command.roomEpoch() + ":" + command.commandId();
    String readable = PREFIX + identity;
    if (readable.length() <= MAX_LENGTH && IDENTIFIER.matcher(readable).matches()) {
      return readable;
    }
    return PREFIX + sha256(identity);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
