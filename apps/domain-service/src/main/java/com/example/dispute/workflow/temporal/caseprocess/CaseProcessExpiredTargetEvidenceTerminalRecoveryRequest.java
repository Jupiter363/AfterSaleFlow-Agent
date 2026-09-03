package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.RecoveryErrorOrigin;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;

/**
 * 恢复一条已消费且过期的 Target Evidence 命令的稳定 Workflow authority。
 *
 * <p>上游：持有恢复权限的调用方提交 {@link CaseProcessWorkflow} 的过期 Evidence recovery Update；Temporal
 * 角色：恢复 Update 的请求 payload；
 * 下游：CaseProcessWorkflowImpl 以该对象校验当前 Evidence child、游标、revision 与 COMMAND 错误后调用命令
 * 生命周期 Activity。由 canonical 字段导出的 recoveryId 排除 Continue-As-New run id，因此是同一已消费命令
 * 的幂等 replay 边界。
 */
public record CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest(
    String schemaVersion,
    String recoveryId,
    String workflowId,
    String firstExecutionRunId,
    String tenantSurrogate,
    String caseId,
    long expectedNextCommandSequence,
    long expectedProcessedCommandCount,
    long expectedNextCaseEventSequence,
    long expectedProcessedEventCount,
    long expectedProcessRevision,
    long expectedRoomRevision,
    String expectedProtocolErrorCode,
    RecoveryErrorOrigin expectedProtocolErrorOrigin,
    Instant actualExpiredAt,
    ProcessedCommandIdentity previousCommand) {

  public static final String SCHEMA_VERSION =
      "case-process-expired-target-evidence-terminal-recovery-request.v1";
  public static final String RECOVERY_ID_SCHEMA =
      "case-process-expired-target-evidence-terminal-recovery-id.v1";
  public static final String RECOVERY_ID_PREFIX =
      "expired-target-evidence-terminal-no-commit:";
  private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL_MAPPER =
      JsonMapper.builder().findAndAddModules().build();

  static {
    CANONICAL_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be case-process-expired-target-evidence-terminal-recovery-request.v1");
    }
    requireText(workflowId, "workflowId");
    requireText(firstExecutionRunId, "firstExecutionRunId");
    requireText(tenantSurrogate, "tenantSurrogate");
    requireText(caseId, "caseId");
    requireText(expectedProtocolErrorCode, "expectedProtocolErrorCode");
    previousCommand = Objects.requireNonNull(previousCommand, "previousCommand must not be null");
    actualExpiredAt = Objects.requireNonNull(actualExpiredAt, "actualExpiredAt must not be null");
    if (!CaseProcessWorkflowProtocol.caseWorkflowId(tenantSurrogate, caseId).equals(workflowId)) {
      throw new IllegalArgumentException("workflowId does not match tenant/case authority");
    }
    if (expectedNextCommandSequence < 2
        || expectedProcessedCommandCount < 1
        || expectedNextCaseEventSequence < 1
        || expectedProcessedEventCount < 0
        || expectedProcessRevision < 0
        || expectedRoomRevision < 0
        || expectedNextCommandSequence != Math.incrementExact(previousCommand.caseCommandSequence())
        || expectedProcessedCommandCount != expectedNextCommandSequence - 1
        || expectedProcessedEventCount != expectedNextCaseEventSequence - 1) {
      throw new IllegalArgumentException("expired Evidence recovery counters are invalid");
    }
    if (expectedProtocolErrorOrigin != RecoveryErrorOrigin.COMMAND) {
      throw new IllegalArgumentException("expired Evidence recovery requires COMMAND error authority");
    }
    String expectedRecoveryId =
        recoveryId(
            workflowId,
            firstExecutionRunId,
            previousCommand,
            actualExpiredAt);
    if (!expectedRecoveryId.equals(recoveryId)) {
      throw new IllegalArgumentException("recoveryId does not match its deterministic authority");
    }
  }

  /**
   * Hashes canonical JSON containing the stable workflow execution, previous command identity, and
   * persisted expiration instant. The current Continue-As-New run id is intentionally excluded.
   */
  public static String recoveryId(
      String workflowId,
      String firstExecutionRunId,
      ProcessedCommandIdentity previousCommand,
      Instant actualExpiredAt) {
    requireText(workflowId, "workflowId");
    requireText(firstExecutionRunId, "firstExecutionRunId");
    Objects.requireNonNull(previousCommand, "previousCommand must not be null");
    Objects.requireNonNull(actualExpiredAt, "actualExpiredAt must not be null");
    ObjectNode identity = CANONICAL_MAPPER.createObjectNode();
    identity.put("schemaVersion", RECOVERY_ID_SCHEMA);
    identity.put("workflowId", workflowId);
    identity.put("firstExecutionRunId", firstExecutionRunId);
    identity.put("commandId", previousCommand.commandId());
    identity.put("caseCommandSequence", previousCommand.caseCommandSequence());
    identity.put("requestHash", previousCommand.requestHash());
    identity.put("actualExpiredAt", actualExpiredAt.toString());
    return RECOVERY_ID_PREFIX + ContractJson.sha256Hex(identity);
  }

  public String requestSha256() {
    return ContractJson.sha256Hex(CANONICAL_MAPPER.valueToTree(this));
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
