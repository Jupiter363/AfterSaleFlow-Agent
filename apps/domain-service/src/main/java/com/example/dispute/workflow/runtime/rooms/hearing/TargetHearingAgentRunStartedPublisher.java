package com.example.dispute.workflow.runtime.rooms.hearing;

import java.time.Instant;
import java.util.Objects;

/** Durable discovery seam for an automatically materialized Target Hearing AgentRun. */
@FunctionalInterface
public interface TargetHearingAgentRunStartedPublisher {

  void publish(Event event);

  TargetHearingAgentRunStartedPublisher NOOP = ignored -> {};

  record Event(
      String tenantSurrogate,
      String caseId,
      String roomId,
      long roomEpoch,
      long fencingToken,
      String flowInstanceId,
      String stageCode,
      int stageSequence,
      String operation,
      String commandId,
      String agentRunId,
      String attemptId,
      String status,
      Instant startedAt) {
    public Event {
      tenantSurrogate = text(tenantSurrogate, "tenantSurrogate");
      caseId = text(caseId, "caseId");
      roomId = text(roomId, "roomId");
      flowInstanceId = text(flowInstanceId, "flowInstanceId");
      stageCode = text(stageCode, "stageCode");
      operation = text(operation, "operation");
      commandId = text(commandId, "commandId");
      agentRunId = text(agentRunId, "agentRunId");
      attemptId = text(attemptId, "attemptId");
      status = text(status, "status");
      startedAt = Objects.requireNonNull(startedAt, "startedAt");
      if (roomEpoch < 0 || fencingToken < 1 || stageSequence < 1) {
        throw new IllegalArgumentException("Hearing AgentRun start coordinates are invalid");
      }
    }

    private static String text(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " must not be blank");
      }
      return value;
    }
  }
}
