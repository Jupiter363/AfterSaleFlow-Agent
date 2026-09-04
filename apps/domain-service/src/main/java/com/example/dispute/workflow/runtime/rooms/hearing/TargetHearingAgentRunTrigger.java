package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;

/** Exact payload that the target dispatcher must pass to the AgentRun v2 worker. */
public record TargetHearingAgentRunTrigger(
    String schemaVersion, CaseCommandRef command, ExecuteAgentRunRequest request,
    long expectedRoomRevision, String materialSha256) {
  public static final String SCHEMA_VERSION = "production-runtime-hearing-agent-run-trigger.v1";
  public TargetHearingAgentRunTrigger {
    if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("unsupported trigger schema");
    command = Objects.requireNonNull(command, "command"); request = Objects.requireNonNull(request, "request");
    if (command.roomType().name().equals("HEARING") == false
        || !command.commandId().equals(request.command().commandId())
        || !command.caseId().equals(request.command().caseId())
        || command.roomEpoch() != request.command().roomEpoch()
        || expectedRoomRevision < 0 || materialSha256 == null || !materialSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Hearing AgentRun trigger is not an exact command binding");
    }
  }
}
