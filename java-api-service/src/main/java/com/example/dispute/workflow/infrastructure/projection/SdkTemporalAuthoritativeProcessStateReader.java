package com.example.dispute.workflow.infrastructure.projection;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessObservation;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessState;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Incomplete;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReadResult;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Unavailable;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Verified;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.ProvisioningCommitment;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 从案件根工作流读取权威控制面状态的查询适配器。
 *
 * <p>上游是 projection reconciliation；下游只调用 {@code CaseProcessWorkflow.state()} 与
 * {@code provisioningCommitment()} Query，并用执行历史/Memo 校验 Continue-As-New 链。它不信任普通
 * 投影作为房间 authority，也不会向 workflow 发送信号或 Update。
 */
@Component
public final class SdkTemporalAuthoritativeProcessStateReader
        implements AuthoritativeProcessStateReader {

    static final String AUTHORITY_CHECKPOINT_MEMO_KEY =
            "case_process_authority_checkpoint_v1";
    private static final String INCOMPLETE_REASON =
            "CASE_PROCESS_STATE_NOT_REPAIR_COMPLETE";

    private final WorkflowClient workflowClient;
    private final DataConverter dataConverter;

    public SdkTemporalAuthoritativeProcessStateReader(WorkflowClient workflowClient) {
        this.workflowClient = Objects.requireNonNull(workflowClient, "workflowClient must not be null");
        this.dataConverter =
                Objects.requireNonNull(
                        workflowClient.getOptions().getDataConverter(),
                        "Temporal dataConverter must not be null");
    }

    /**
     * 查询指定 case workflow 的快照与最新 commitment。下游返回的 {@code Verified} 结果供投影层决定
     * 是否可安全对齐；Query 不进入 Temporal history，因此不能用于触发恢复或业务写入。
     */
    @Override
    public ReadResult read(ReconciliationTarget target) {
        try {
            CaseProcessWorkflow workflow =
                    workflowClient.newWorkflowStub(
                            CaseProcessWorkflow.class, target.temporalWorkflowId());
            CaseProcessSnapshot snapshot = workflow.state();
            if (!matchesTarget(target, snapshot)) {
                return new Unavailable("TEMPORAL_QUERY_SCOPE_MISMATCH");
            }
            ProvisioningCommitment commitment = workflow.provisioningCommitment();
            if (commitment == null) {
                return incomplete(snapshot, "TEMPORAL_PROVISIONING_COMMITMENT_MISSING");
            }
            if (!matchesSnapshot(commitment, snapshot)) {
                return incomplete(snapshot, "TEMPORAL_PROVISIONING_COMMITMENT_MISMATCH");
            }

            ProvisionRoomEpochReceipt receipt = commitment.receipt();
            HistoryVerification verification = verifyHistory(snapshot, commitment);
            if (!verification.verified()) {
                return new Unavailable(verification.reasonCode());
            }
            if (!isCompleteRepairCheckpoint(snapshot, commitment.request())) {
                return incomplete(snapshot, receipt, INCOMPLETE_REASON);
            }
            return new Verified(
                    authoritativeState(snapshot, receipt),
                    verification.verificationRef());
        } catch (WorkflowException exception) {
            return new Unavailable("TEMPORAL_QUERY_UNAVAILABLE");
        } catch (DataConverterException exception) {
            return new Unavailable("TEMPORAL_AUTHORITY_MEMO_INVALID");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return new Unavailable("TEMPORAL_SNAPSHOT_INVALID");
        } catch (RuntimeException exception) {
            return new Unavailable("TEMPORAL_AUTHORITY_HISTORY_UNAVAILABLE");
        }
    }

    private HistoryVerification verifyHistory(
            CaseProcessSnapshot snapshot, ProvisioningCommitment commitment) {
        ProvisionRoomEpochReceipt receipt = commitment.receipt();
        String firstRunId = receipt.caseWorkflowRunId();
        String currentRunId = snapshot.workflowRunId();
        WorkflowExecutionHistory firstHistory =
                workflowClient.fetchHistory(snapshot.workflowId(), firstRunId);
        WorkflowExecutionHistory currentHistory =
                firstRunId.equals(currentRunId)
                        ? firstHistory
                        : workflowClient.fetchHistory(snapshot.workflowId(), currentRunId);
        if (!hasChainAnchor(firstHistory, firstRunId)
                || !hasChainAnchor(currentHistory, firstRunId)) {
            return HistoryVerification.failed("TEMPORAL_AUTHORITY_HISTORY_INVALID");
        }

        MemoEvidence memo = latestAuthorityMemo(currentHistory);
        if (memo == null) {
            return HistoryVerification.failed("TEMPORAL_AUTHORITY_MEMO_MISSING");
        }
        ProvisionRoomEpochReceipt historicalReceipt =
                dataConverter.fromPayload(
                        memo.payload(),
                        ProvisionRoomEpochReceipt.class,
                        ProvisionRoomEpochReceipt.class);
        if (!receipt.equals(historicalReceipt)
                || !historicalReceipt.matches(commitment.request())
                || !commitment.payloadSha256().equals(historicalReceipt.provisioningSha256())) {
            return HistoryVerification.failed("TEMPORAL_AUTHORITY_MEMO_MISMATCH");
        }
        String verificationRef =
                "temporal:"
                        + snapshot.workflowId()
                        + ":"
                        + firstRunId
                        + ":"
                        + currentRunId
                        + ":memo-"
                        + memo.eventId()
                        + ":"
                        + sha256(memo.payload().toByteArray());
        return HistoryVerification.verified(verificationRef);
    }

    private static boolean hasChainAnchor(
            WorkflowExecutionHistory history, String firstRunId) {
        if (history == null || history.getEvents().isEmpty()) {
            return false;
        }
        HistoryEvent first = history.getEvents().getFirst();
        if (first.getEventType() != EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
                || !first.hasWorkflowExecutionStartedEventAttributes()) {
            return false;
        }
        var started = first.getWorkflowExecutionStartedEventAttributes();
        return firstRunId.equals(started.getFirstExecutionRunId())
                && firstRunId.equals(started.getOriginalExecutionRunId());
    }

    private static MemoEvidence latestAuthorityMemo(WorkflowExecutionHistory history) {
        MemoEvidence latest = null;
        for (HistoryEvent event : history.getEvents()) {
            if (event.getEventType() == EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
                    && event.hasWorkflowExecutionStartedEventAttributes()) {
                Payload payload =
                        event.getWorkflowExecutionStartedEventAttributes()
                                .getMemo()
                                .getFieldsMap()
                                .get(AUTHORITY_CHECKPOINT_MEMO_KEY);
                if (payload != null) {
                    latest = new MemoEvidence(event.getEventId(), payload);
                }
            }
            if (event.getEventType() != EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED
                    || !event.hasWorkflowPropertiesModifiedEventAttributes()) {
                continue;
            }
            Payload payload =
                    event.getWorkflowPropertiesModifiedEventAttributes()
                            .getUpsertedMemo()
                            .getFieldsMap()
                            .get(AUTHORITY_CHECKPOINT_MEMO_KEY);
            if (payload != null) {
                latest = new MemoEvidence(event.getEventId(), payload);
            }
        }
        return latest;
    }

    private static boolean matchesTarget(
            ReconciliationTarget target, CaseProcessSnapshot snapshot) {
        return snapshot != null
                && target.temporalWorkflowId().equals(snapshot.workflowId())
                && target.tenantSurrogate().equals(snapshot.tenantSurrogate())
                && target.caseId().equals(snapshot.caseId())
                && hasText(snapshot.workflowRunId());
    }

    private static boolean matchesSnapshot(
            ProvisioningCommitment commitment, CaseProcessSnapshot snapshot) {
        ProvisionRoomEpoch request = commitment.request();
        ProvisionRoomEpochReceipt receipt = commitment.receipt();
        return commitment.updateId().equals(request.updateId())
                && commitment.payloadSha256().equals(request.payloadSha256())
                && receipt.matches(request)
                && request.caseWorkflowId().equals(snapshot.workflowId())
                && request.tenantSurrogate().equals(snapshot.tenantSurrogate())
                && request.caseId().equals(snapshot.caseId())
                && request.roomType() == snapshot.activeRoomType()
                && request.roomEpoch() == snapshot.activeRoomEpoch()
                && request.fencingToken() == snapshot.activeFencingToken()
                && request.roomWorkflowId().equals(snapshot.activeChildWorkflowId())
                && receipt.roomWorkflowRunId().equals(snapshot.activeChildWorkflowRunId())
                && commitment.payloadSha256().equals(snapshot.activeProvisioningSha256());
    }

    private static boolean isCompleteRepairCheckpoint(
            CaseProcessSnapshot snapshot, ProvisionRoomEpoch request) {
        return snapshot.observedProcessRevision() == request.initialProcessRevision()
                && previousSequence(snapshot.nextCommandSequence())
                        == request.lastCommandSequence()
                && previousSequence(snapshot.nextCaseEventSequence())
                        == request.lastCaseEventSequence()
                && snapshot.processedCommandCount() == 0
                && snapshot.processedEventCount() == 0
                && snapshot.pendingCommandCount() == 0
                && snapshot.bufferedEventCount() == 0
                && (snapshot.blockedReason() == null
                        || "NONE".equals(snapshot.blockedReason()))
                && snapshot.protocolErrorCode() == null;
    }

    private static AuthoritativeProcessState authoritativeState(
            CaseProcessSnapshot snapshot, ProvisionRoomEpochReceipt receipt) {
        return new AuthoritativeProcessState(
                receipt.tenantSurrogate(),
                receipt.caseId(),
                receipt.macroPhase(),
                receipt.currentRoom(),
                receipt.roomPhase(),
                receipt.roomType(),
                receipt.roomEpoch(),
                receipt.initialProcessRevision(),
                receipt.initialRoomRevision(),
                receipt.fencingToken(),
                previousSequence(snapshot.nextCommandSequence()),
                previousSequence(snapshot.nextCaseEventSequence()),
                receipt.projectedDeadlineAt(),
                receipt.caseWorkflowId(),
                receipt.caseWorkflowRunId(),
                receipt.temporalBuildId(),
                receipt.projectionRef(),
                receipt.projectionSha256());
    }

    private static Incomplete incomplete(
            CaseProcessSnapshot snapshot, String reasonCode) {
        return incomplete(snapshot, null, reasonCode);
    }

    private static Incomplete incomplete(
            CaseProcessSnapshot snapshot,
            ProvisionRoomEpochReceipt verifiedReceipt,
            String reasonCode) {
        return new Incomplete(
                new AuthoritativeProcessObservation(
                        snapshot.tenantSurrogate(),
                        snapshot.caseId(),
                        snapshot.workflowId(),
                        snapshot.workflowRunId(),
                        verifiedReceipt == null
                                ? null
                                : verifiedReceipt.caseWorkflowRunId(),
                        verifiedReceipt == null
                                ? null
                                : verifiedReceipt.roomWorkflowRunId(),
                        snapshot.macroPhase(),
                        snapshot.activeRoomType(),
                        snapshot.activeRoomEpoch(),
                        snapshot.activeRoomRevision(),
                        snapshot.activeFencingToken(),
                        snapshot.observedProcessRevision(),
                        previousSequence(snapshot.nextCommandSequence()),
                        previousSequence(snapshot.nextCaseEventSequence())),
                reasonCode);
    }

    private static long previousSequence(long nextSequence) {
        return Math.max(0, nextSequence - 1);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record MemoEvidence(long eventId, Payload payload) {}

    private record HistoryVerification(
            boolean verified, String reasonCode, String verificationRef) {

        static HistoryVerification verified(String verificationRef) {
            return new HistoryVerification(true, null, verificationRef);
        }

        static HistoryVerification failed(String reasonCode) {
            return new HistoryVerification(false, reasonCode, null);
        }
    }
}
