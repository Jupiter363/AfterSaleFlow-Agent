package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;

/** Resolves the Java-owned room fence from one exact durable logical-run and attempt identity. */
@FunctionalInterface
public interface TargetE2EAgentRunIdentityResolver {

  DurableIdentity resolve(ExecuteAgentRunRequest request);

  record DurableIdentity(
      String agentRunId,
      String attemptId,
      long attemptNo,
      String tenantSurrogate,
      String caseId,
      RoomType roomType,
      long roomEpoch,
      long processRevision,
      String commandId,
      String requestHash,
      String logicalInputHash,
      long roomFencingToken) {

    public DurableIdentity {
      require(agentRunId, "agentRunId");
      require(attemptId, "attemptId");
      if (attemptNo < 1) {
        throw new IllegalArgumentException("attemptNo must be positive");
      }
      require(tenantSurrogate, "tenantSurrogate");
      require(caseId, "caseId");
      Objects.requireNonNull(roomType, "roomType");
      if (roomEpoch < 0 || processRevision < 0) {
        throw new IllegalArgumentException("room epoch and process revision must not be negative");
      }
      require(commandId, "commandId");
      requireSha256(requestHash, "requestHash");
      requireSha256(logicalInputHash, "logicalInputHash");
      if (roomFencingToken < 1 || roomFencingToken > 9_007_199_254_740_991L) {
        throw new IllegalArgumentException("roomFencingToken is outside the JSON-safe range");
      }
    }

    public static DurableIdentity from(ExecuteAgentRunRequest request, long roomFencingToken) {
      Objects.requireNonNull(request, "request");
      var command = request.command();
      return new DurableIdentity(
          request.agentRunId(),
          request.attemptId(),
          request.attemptNo(),
          command.tenantSurrogate(),
          command.caseId(),
          command.roomType(),
          command.roomEpoch(),
          command.processRevision(),
          command.commandId(),
          command.requestHash(),
          request.logicalInputHash(),
          roomFencingToken);
    }

    public long requireExact(ExecuteAgentRunRequest request) {
      Objects.requireNonNull(request, "request");
      var command = request.command();
      boolean exact =
          agentRunId.equals(request.agentRunId())
              && agentRunId.equals(request.logicalRunId())
              && attemptId.equals(request.attemptId())
              && attemptNo == request.attemptNo()
              && tenantSurrogate.equals(command.tenantSurrogate())
              && caseId.equals(command.caseId())
              && roomType == command.roomType()
              && roomEpoch == command.roomEpoch()
              && processRevision == command.processRevision()
              && commandId.equals(command.commandId())
              && TargetE2EGraphEnvelopeCodec.constantTimeEquals(
                  requestHash, command.requestHash())
              && TargetE2EGraphEnvelopeCodec.constantTimeEquals(
                  logicalInputHash, request.logicalInputHash());
      if (!exact) {
        throw new IllegalStateException(
            "resolved durable AgentRun identity differs from the execution request");
      }
      return roomFencingToken;
    }

    private static void require(String value, String field) {
      if (value == null || value.isBlank() || value.length() > 128) {
        throw new IllegalArgumentException(field + " is not bounded");
      }
    }

    private static void requireSha256(String value, String field) {
      if (value == null || !value.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException(field + " must be lowercase SHA-256");
      }
    }
  }
}
