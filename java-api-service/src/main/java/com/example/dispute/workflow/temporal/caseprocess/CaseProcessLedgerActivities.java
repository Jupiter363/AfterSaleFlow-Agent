package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

/**
 * CaseProcessWorkflowImpl 用于重读命令/领域事件账本和记录 sequence 缺口的 Temporal Activity 合同。
 *
 * <p>上游是工作流的重放、缺口恢复和命令调度分支；下游实现
 * {@code CaseProcessLedgerActivitiesImpl} 通过案件命令、领域事件和 reconciliation issue 仓库提供确定性输入
 * 或持久化问题记录。读取结果本身不推进 workflow cursor，cursor 仍由工作流 history
 * 控制。
 */
@ActivityInterface
public interface CaseProcessLedgerActivities {

    /**
     * 上游：CaseProcessWorkflowImpl 的命令序列重读分支。
     *
     * <p>Temporal 角色：只读 Activity；下游：实现从 CaseCommand 账本返回有界、升序命令范围，供工作流
     * 在自身 replay 边界内决定是否消费。
     */
    @ActivityMethod(name = "LoadCaseCommands")
    List<CaseCommandRef> loadCaseCommands(LoadSequenceRange request);

    /**
     * 上游：CaseProcessWorkflowImpl 需要确认命令 durable state 的分支。
     *
     * <p>Temporal 角色：只读 Activity；下游：实现读取 CaseCommand ledger state，供工作流避免把已终态
     * 命令再次路由。
     */
    @ActivityMethod(name = "LoadCaseCommandLedgerEntries")
    List<CaseCommandLedgerEntry> loadCaseCommandLedgerEntries(LoadSequenceRange request);

    /**
     * 上游：CaseProcessWorkflowImpl 的领域事件重放/缺口恢复分支。
     *
     * <p>Temporal 角色：只读 Activity；下游：实现从 CaseTimelineEvent 仓库返回有界、升序事件范围，事件
     * 是否消费仍由 workflow 的 sequence/replay 校验决定。
     */
    @ActivityMethod(name = "LoadDomainEvents")
    List<CaseDomainEventRef> loadDomainEvents(LoadSequenceRange request);

    /**
     * 上游：CaseProcessWorkflowImpl 收到 {@link CaseProcessWorkflow#retrySequenceGap()} 后的恢复分支。
     *
     * <p>Temporal 角色：写入 Activity；下游：实现向 reconciliation issue 仓库写入幂等缺口记录，为人工或
     * 后续恢复保留 durable boundary，但不伪造缺失的命令或事件。
     */
    @ActivityMethod(name = "ReportCaseProcessSequenceGap")
    void reportSequenceGap(SequenceGapReport report);

    enum SequenceStream {
        COMMAND,
        DOMAIN_EVENT
    }

    enum CaseCommandLedgerState {
        PENDING_ORCHESTRATION,
        ORCHESTRATION_ACCEPTED,
        APPLIED,
        SHADOW_COMPLETED,
        REJECTED,
        FAILED,
        EXPIRED;

        public boolean routable() {
            return this == PENDING_ORCHESTRATION || this == ORCHESTRATION_ACCEPTED;
        }

        public boolean successfulTerminal() {
            return this == APPLIED || this == SHADOW_COMPLETED;
        }
    }

    record CaseCommandLedgerEntry(
            String schemaVersion,
            CaseCommandRef command,
            CaseCommandLedgerState state) {

        public CaseCommandLedgerEntry {
            if (!"case-command-ledger-entry.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be case-command-ledger-entry.v1");
            }
            if (command == null) {
                throw new IllegalArgumentException("command must not be null");
            }
            if (state == null) {
                throw new IllegalArgumentException("state must not be null");
            }
        }
    }

    record LoadSequenceRange(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            long fromSequenceInclusive,
            long toSequenceInclusive,
            int limit) {

        public LoadSequenceRange {
            if (!"load-sequence-range.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be load-sequence-range.v1");
            }
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            if (fromSequenceInclusive < 1
                    || toSequenceInclusive < fromSequenceInclusive) {
                throw new IllegalArgumentException("sequence range is invalid");
            }
            if (limit < 1 || limit > 128) {
                throw new IllegalArgumentException("limit must be between 1 and 128");
            }
            if (toSequenceInclusive - fromSequenceInclusive + 1 > limit) {
                throw new IllegalArgumentException("sequence range exceeds limit");
            }
        }
    }

    record SequenceGapReport(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            String workflowId,
            String workflowRunId,
            SequenceStream stream,
            long expectedSequence,
            long highestObservedSequence,
            int recoveryAttempts,
            String reasonCode) {

        public SequenceGapReport {
            if (!"sequence-gap-report.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be sequence-gap-report.v1");
            }
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            requireText(workflowId, "workflowId");
            requireText(workflowRunId, "workflowRunId");
            if (stream == null) {
                throw new IllegalArgumentException("stream must not be null");
            }
            if (expectedSequence < 1 || highestObservedSequence < expectedSequence) {
                throw new IllegalArgumentException("gap sequence is invalid");
            }
            if (recoveryAttempts < 1) {
                throw new IllegalArgumentException("recoveryAttempts must be positive");
            }
            requireText(reasonCode, "reasonCode");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
