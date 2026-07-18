package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.List;

public record CaseProcessSnapshot(
    String schemaVersion,
    String workflowId,
    String workflowRunId,
    String tenantSurrogate,
    String caseId,
    String macroPhase,
    RoomType activeRoomType,
    long activeRoomEpoch,
    String activeChildWorkflowId,
    long observedProcessRevision,
    long nextCommandSequence,
    long nextCaseEventSequence,
    long processedCommandCount,
    long processedEventCount,
    int pendingCommandCount,
    int bufferedEventCount,
    int recentCommandCount,
    long highestObservedCommandSequence,
    long highestObservedEventSequence,
    int runGeneration,
    String blockedReason,
    String protocolErrorCode,
    List<String> recentCommandIds,
    long activeFencingToken,
    String activeChildWorkflowRunId,
    int provisioningCommitmentCount,
    String activeProvisioningSha256) {

  public CaseProcessSnapshot(
      String schemaVersion,
      String workflowId,
      String workflowRunId,
      String tenantSurrogate,
      String caseId,
      String macroPhase,
      RoomType activeRoomType,
      long activeRoomEpoch,
      String activeChildWorkflowId,
      long observedProcessRevision,
      long nextCommandSequence,
      long nextCaseEventSequence,
      long processedCommandCount,
      long processedEventCount,
      int pendingCommandCount,
      int bufferedEventCount,
      int recentCommandCount,
      long highestObservedCommandSequence,
      long highestObservedEventSequence,
      int runGeneration,
      String blockedReason,
      String protocolErrorCode,
      List<String> recentCommandIds) {
    this(
        schemaVersion,
        workflowId,
        workflowRunId,
        tenantSurrogate,
        caseId,
        macroPhase,
        activeRoomType,
        activeRoomEpoch,
        activeChildWorkflowId,
        observedProcessRevision,
        nextCommandSequence,
        nextCaseEventSequence,
        processedCommandCount,
        processedEventCount,
        pendingCommandCount,
        bufferedEventCount,
        recentCommandCount,
        highestObservedCommandSequence,
        highestObservedEventSequence,
        runGeneration,
        blockedReason,
        protocolErrorCode,
        recentCommandIds,
        0,
        null,
        0,
        null);
  }

  public CaseProcessSnapshot {
    if (!"case-process-snapshot.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be case-process-snapshot.v1");
    }
    recentCommandIds = List.copyOf(recentCommandIds);
    if (activeFencingToken < 0 || provisioningCommitmentCount < 0) {
      throw new IllegalArgumentException("provisioning snapshot counters are invalid");
    }
  }
}
