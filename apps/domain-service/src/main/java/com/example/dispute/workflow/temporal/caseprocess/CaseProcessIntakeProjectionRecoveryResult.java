package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionOutcome;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionResult;
import java.util.Objects;

/**
 * Intake 投影完成恢复 Update 的确认结果。
 *
 * <p>上游：CaseProcessWorkflowImpl 在匹配的投影完成结果返回后构造；Temporal 角色：恢复 Update 返回 payload；
 * 下游：恢复调用方依据 {@link Disposition} 区分首次采用与已采用重放，工作流仅在请求中的命令、事件、epoch、
 * fencing token 和 revision 全部匹配时推进事件游标并清除 DOMAIN_EVENT 协议错误。
 */
public record CaseProcessIntakeProjectionRecoveryResult(
    String schemaVersion,
    Disposition disposition,
    CaseProcessIntakeProjectionRecoveryRequest request,
    CompleteConsumedIntakeProjectionResult projectionResult) {

  public static final String SCHEMA_VERSION =
      "case-process-intake-projection-recovery-result.v1";

  public enum Disposition {
    ADOPTED,
    ALREADY_ADOPTED
  }

  public CaseProcessIntakeProjectionRecoveryResult {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be case-process-intake-projection-recovery-result.v1");
    }
    disposition = Objects.requireNonNull(disposition, "disposition must not be null");
    request = Objects.requireNonNull(request, "request must not be null");
    projectionResult =
        Objects.requireNonNull(projectionResult, "projectionResult must not be null");
    CompleteConsumedIntakeProjectionOutcome outcome = projectionResult.outcome();
    Disposition expectedDisposition =
        outcome == CompleteConsumedIntakeProjectionOutcome.APPLIED
            ? Disposition.ADOPTED
            : Disposition.ALREADY_ADOPTED;
    if (disposition != expectedDisposition
        || !matches(request.projectionCommand(), projectionResult)) {
      throw new IllegalArgumentException(
          "projection result does not match the acknowledged recovery authority");
    }
  }

  private static boolean matches(
      CompleteConsumedIntakeProjectionCommand command,
      CompleteConsumedIntakeProjectionResult result) {
    return command.eventId().equals(result.eventId())
        && command.caseEventSequence() == result.caseEventSequence()
        && command.lastCommandSequence() == result.lastCommandSequence()
        && command.processRevision() == result.processRevision()
        && command.roomRevision() == result.roomRevision()
        && command.roomEpoch() == result.roomEpoch()
        && command.fencingToken() == result.fencingToken()
        && command.temporalWorkflowId().equals(result.temporalWorkflowId())
        && command.firstExecutionRunId().equals(result.firstExecutionRunId())
        && command.activeChildRunId().equals(result.activeChildRunId());
  }
}
