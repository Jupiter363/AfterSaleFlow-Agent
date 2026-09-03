package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.RecoveryErrorOrigin;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;

/**
 * 过期 Target Evidence terminal-no-commit 收敛后确认的、哈希绑定的恢复 Update 结果。
 *
 * <p>上游：CaseProcessWorkflowImpl 在 {@code recoverExpiredTargetEvidenceTerminalNoCommit} Activity 成功后构造；Temporal
 * 角色：恢复 Update 返回 payload；下游：调用方据此观察已恢复或幂等重放，工作流以 request/result hash、receipt
 * 和 revision 坐标保存 commitment，并且只在这些坐标一致时清除 COMMAND 协议错误。
 */
public record CaseProcessExpiredTargetEvidenceTerminalRecoveryResult(
    String schemaVersion,
    Disposition disposition,
    CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest request,
    String requestSha256,
    TargetRoomAgentRunTerminalNoCommit authority,
    CaseCommandLedgerState commandStatus,
    String receiptUri,
    String receiptSha256,
    long processRevision,
    long roomRevision,
    long lastCommandSequence,
    long lastCaseEventSequence,
    long nextCommandSequence,
    long processedCommandCount,
    long nextCaseEventSequence,
    long processedEventCount,
    String clearedProtocolErrorCode,
    RecoveryErrorOrigin clearedProtocolErrorOrigin) {

  public static final String SCHEMA_VERSION =
      "case-process-expired-target-evidence-terminal-recovery-result.v1";
  private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL_MAPPER =
      JsonMapper.builder().findAndAddModules().build();

  static {
    CANONICAL_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public enum Disposition {
    RECOVERED,
    IDEMPOTENT_REPLAY
  }

  public CaseProcessExpiredTargetEvidenceTerminalRecoveryResult {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be case-process-expired-target-evidence-terminal-recovery-result.v1");
    }
    disposition = Objects.requireNonNull(disposition, "disposition must not be null");
    request = Objects.requireNonNull(request, "request must not be null");
    authority = Objects.requireNonNull(authority, "authority must not be null");
    commandStatus = Objects.requireNonNull(commandStatus, "commandStatus must not be null");
    clearedProtocolErrorOrigin =
        Objects.requireNonNull(
            clearedProtocolErrorOrigin, "clearedProtocolErrorOrigin must not be null");
    if (!request.requestSha256().equals(requestSha256)) {
      throw new IllegalArgumentException("requestSha256 does not match the recovery request");
    }
    ProcessedCommandIdentity previous = request.previousCommand();
    if (!previous.commandId().equals(authority.command().commandId())
        || previous.caseCommandSequence() != authority.command().caseCommandSequence()
        || !previous.requestHash().equals(authority.command().requestHash())
        || !request.tenantSurrogate().equals(authority.command().tenantSurrogate())
        || !request.caseId().equals(authority.command().caseId())
        || request.expectedProcessRevision() != authority.command().expectedProcessRevision()
        || request.expectedRoomRevision() != authority.expectedRoomRevision()
        || Math.decrementExact(request.expectedNextCaseEventSequence())
            != authority.expectedLastCaseEventSequence()) {
      throw new IllegalArgumentException("terminal authority does not match the recovery request");
    }
    if (commandStatus != CaseCommandLedgerState.FAILED
        || !authority.receiptUri().equals(receiptUri)
        || !authority.receiptSha256().equals(receiptSha256)
        || processRevision != request.expectedProcessRevision()
        || roomRevision != request.expectedRoomRevision()
        || lastCommandSequence != previous.caseCommandSequence()
        || lastCaseEventSequence != Math.decrementExact(request.expectedNextCaseEventSequence())
        || nextCommandSequence != request.expectedNextCommandSequence()
        || processedCommandCount != request.expectedProcessedCommandCount()
        || nextCaseEventSequence != request.expectedNextCaseEventSequence()
        || processedEventCount != request.expectedProcessedEventCount()
        || !request.expectedProtocolErrorCode().equals(clearedProtocolErrorCode)
        || clearedProtocolErrorOrigin != RecoveryErrorOrigin.COMMAND) {
      throw new IllegalArgumentException("recovery result conflicts with exact durable authority");
    }
  }

  public String recoveryId() {
    return request.recoveryId();
  }

  public ProcessedCommandIdentity commandIdentity() {
    return request.previousCommand();
  }

  public java.time.Instant actualExpiredAt() {
    return request.actualExpiredAt();
  }

  public String resultSha256() {
    return ContractJson.sha256Hex(CANONICAL_MAPPER.valueToTree(this));
  }
}
