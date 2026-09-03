package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "case_command")
public class CaseCommandEntity extends AbstractEntity {

    @Column(name = "command_id", length = 128, nullable = false, updatable = false)
    private String commandId;

    @Column(name = "tenant_surrogate", length = 128, nullable = false, updatable = false)
    private String tenantSurrogate;

    @Column(name = "case_id", length = 64, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "case_command_sequence", nullable = false, updatable = false)
    private long caseCommandSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", length = 64, nullable = false, updatable = false)
    private CommandType commandType;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, nullable = false, updatable = false)
    private RoomType roomType;

    @Column(name = "room_epoch", nullable = false, updatable = false)
    private long roomEpoch;

    @Column(name = "actor_id", length = 128, nullable = false, updatable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", length = 32, nullable = false, updatable = false)
    private ActorRole actorRole;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actor_scopes_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String actorScopesJson;

    @Column(name = "payload_schema_version", length = 128, nullable = false, updatable = false)
    private String payloadSchemaVersion;

    @Column(name = "payload_uri", length = 1024, nullable = false, updatable = false)
    private String payloadUri;

    @Column(name = "payload_sha256", length = 64, nullable = false, updatable = false)
    private String payloadSha256;

    @Column(name = "payload_size_bytes", nullable = false, updatable = false)
    private long payloadSizeBytes;

    @Column(name = "expected_process_revision", nullable = false, updatable = false)
    private long expectedProcessRevision;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "deadline_at", nullable = false, updatable = false)
    private OffsetDateTime deadlineAt;

    @Column(name = "traceparent", length = 55, nullable = false, updatable = false)
    private String traceparent;

    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_status", length = 32, nullable = false)
    private CommandStatus commandStatus;

    @Column(name = "status_reason_code", length = 64)
    private String statusReasonCode;

    @Column(name = "result_uri", length = 1024)
    private String resultUri;

    @Column(name = "result_sha256", length = 64)
    private String resultSha256;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private OffsetDateTime acceptedAt;

    @Column(name = "orchestrated_at")
    private OffsetDateTime orchestratedAt;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CaseCommandEntity() {}

    private CaseCommandEntity(String id) {
        super(id);
    }

    public static CaseCommandEntity pending(
            String id,
            CaseCommandRef command,
            String actorScopesJson,
            OffsetDateTime acceptedAt) {
        Objects.requireNonNull(command, "command must not be null");
        CaseCommandEntity entity = new CaseCommandEntity(id);
        entity.commandId = command.commandId();
        entity.tenantSurrogate = command.tenantSurrogate();
        entity.caseId = command.caseId();
        entity.caseCommandSequence = command.caseCommandSequence();
        entity.commandType = command.commandType();
        entity.roomType = command.roomType();
        entity.roomEpoch = command.roomEpoch();
        entity.actorId = command.actorRef().actorId();
        entity.actorRole = command.actorRef().actorRole();
        entity.actorScopesJson = Objects.requireNonNull(actorScopesJson, "actorScopesJson");
        entity.payloadSchemaVersion = command.payloadRef().schemaVersion();
        entity.payloadUri = command.payloadRef().uri();
        entity.payloadSha256 = command.payloadRef().sha256();
        entity.payloadSizeBytes = command.payloadRef().sizeBytes();
        entity.expectedProcessRevision = command.expectedProcessRevision();
        entity.occurredAt = OffsetDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC);
        entity.deadlineAt = OffsetDateTime.ofInstant(command.deadlineAt(), ZoneOffset.UTC);
        entity.traceparent = command.traceparent();
        entity.requestHash = command.requestHash();
        entity.commandStatus = CommandStatus.PENDING_ORCHESTRATION;
        entity.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        entity.createdAt = acceptedAt;
        entity.updatedAt = acceptedAt;
        return entity;
    }

    public String getCommandId() {
        return commandId;
    }

    public String getTenantSurrogate() {
        return tenantSurrogate;
    }

    public String getCaseId() {
        return caseId;
    }

    public long getCaseCommandSequence() {
        return caseCommandSequence;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public CommandStatus getCommandStatus() {
        return commandStatus;
    }

    public CommandType getCommandType() {
        return commandType;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public long getRoomEpoch() {
        return roomEpoch;
    }

    public String getActorId() {
        return actorId;
    }

    public ActorRole getActorRole() {
        return actorRole;
    }

    public String getActorScopesJson() {
        return actorScopesJson;
    }

    public String getPayloadSchemaVersion() {
        return payloadSchemaVersion;
    }

    public String getPayloadUri() {
        return payloadUri;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }

    public long getPayloadSizeBytes() {
        return payloadSizeBytes;
    }

    public long getExpectedProcessRevision() {
        return expectedProcessRevision;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public OffsetDateTime getDeadlineAt() {
        return deadlineAt;
    }

    public String getTraceparent() {
        return traceparent;
    }

    public OffsetDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public String getStatusReasonCode() {
        return statusReasonCode;
    }

    public OffsetDateTime getOrchestratedAt() {
        return orchestratedAt;
    }

    public String getResultUri() {
        return resultUri;
    }

    public String getResultSha256() {
        return resultSha256;
    }

    public OffsetDateTime getAppliedAt() {
        return appliedAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void markOrchestrationAccepted(OffsetDateTime acceptedByOrchestratorAt) {
        Objects.requireNonNull(
                acceptedByOrchestratorAt, "acceptedByOrchestratorAt must not be null");
        if (commandStatus != CommandStatus.PENDING_ORCHESTRATION) {
            return;
        }
        commandStatus = CommandStatus.ORCHESTRATION_ACCEPTED;
        statusReasonCode = null;
        orchestratedAt = acceptedByOrchestratorAt;
        updatedAt = acceptedByOrchestratorAt;
    }

    public void markOrchestrationFailed(String reasonCode, OffsetDateTime failedAt) {
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        if (reasonCode.isBlank() || reasonCode.length() > 64) {
            throw new IllegalArgumentException("reasonCode is invalid");
        }
        if (commandStatus != CommandStatus.PENDING_ORCHESTRATION) {
            return;
        }
        commandStatus = CommandStatus.FAILED;
        statusReasonCode = reasonCode;
        updatedAt = failedAt;
    }

    public void markAcceptedOrchestrationTerminalNoCommit(
            String reasonCode,
            String terminalReceiptUri,
            String terminalReceiptSha256,
            OffsetDateTime failedAt) {
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(terminalReceiptUri, "terminalReceiptUri must not be null");
        Objects.requireNonNull(terminalReceiptSha256, "terminalReceiptSha256 must not be null");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        if (reasonCode.isBlank() || reasonCode.length() > 64) {
            throw new IllegalArgumentException("reasonCode is invalid");
        }
        if (terminalReceiptUri.isBlank()
                || terminalReceiptUri.length() > 1024
                || !terminalReceiptSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("terminal receipt identity is invalid");
        }
        if (commandStatus == CommandStatus.FAILED) {
            if (!reasonCode.equals(statusReasonCode)
                    || !terminalReceiptUri.equals(resultUri)
                    || !terminalReceiptSha256.equals(resultSha256)
                    || appliedAt != null) {
                throw new IllegalStateException(
                        "failed command is bound to another terminal authority");
            }
            return;
        }
        if (commandStatus != CommandStatus.ORCHESTRATION_ACCEPTED) {
            throw new IllegalStateException(
                    "only an accepted orchestration can terminate without a commit");
        }
        if (resultUri != null || resultSha256 != null || appliedAt != null) {
            throw new IllegalStateException(
                    "accepted orchestration already carries terminal result authority");
        }
        commandStatus = CommandStatus.FAILED;
        statusReasonCode = reasonCode;
        resultUri = terminalReceiptUri;
        resultSha256 = terminalReceiptSha256;
        updatedAt = failedAt;
    }

    public void markExpired(String reasonCode, OffsetDateTime expiredAt) {
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(expiredAt, "expiredAt must not be null");
        if (reasonCode.isBlank() || reasonCode.length() > 64) {
            throw new IllegalArgumentException("reasonCode is invalid");
        }
        if (commandStatus == CommandStatus.EXPIRED) {
            if (!reasonCode.equals(statusReasonCode)) {
                throw new IllegalStateException("expired command has another reason");
            }
            return;
        }
        if (commandStatus != CommandStatus.PENDING_ORCHESTRATION
                && commandStatus != CommandStatus.ORCHESTRATION_ACCEPTED) {
            throw new IllegalStateException("command cannot transition to EXPIRED");
        }
        commandStatus = CommandStatus.EXPIRED;
        statusReasonCode = reasonCode;
        updatedAt = expiredAt;
    }

    /**
     * Converts one exact expired orchestration to a terminal-no-commit failure while retaining the
     * persisted expiration instant as immutable replay authority.
     */
    public void markExpiredOrchestrationTerminalNoCommit(
            String expectedExpirationReasonCode,
            OffsetDateTime actualExpiredAt,
            String terminalReasonCode,
            String terminalReceiptUri,
            String terminalReceiptSha256,
            OffsetDateTime terminalAt) {
        Objects.requireNonNull(
                expectedExpirationReasonCode, "expectedExpirationReasonCode must not be null");
        Objects.requireNonNull(actualExpiredAt, "actualExpiredAt must not be null");
        Objects.requireNonNull(terminalReasonCode, "terminalReasonCode must not be null");
        Objects.requireNonNull(terminalReceiptUri, "terminalReceiptUri must not be null");
        Objects.requireNonNull(terminalReceiptSha256, "terminalReceiptSha256 must not be null");
        Objects.requireNonNull(terminalAt, "terminalAt must not be null");
        if (expectedExpirationReasonCode.isBlank()
                || expectedExpirationReasonCode.length() > 64
                || terminalReasonCode.isBlank()
                || terminalReasonCode.length() > 64) {
            throw new IllegalArgumentException("terminal reason authority is invalid");
        }
        if (terminalReceiptUri.isBlank()
                || terminalReceiptUri.length() > 1024
                || !terminalReceiptSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("terminal receipt identity is invalid");
        }
        if (orchestratedAt == null
                || updatedAt == null
                || !updatedAt.toInstant().equals(actualExpiredAt.toInstant())
                || orchestratedAt.toInstant().isAfter(terminalAt.toInstant())
                || !terminalAt.toInstant().isBefore(deadlineAt.toInstant())
                || deadlineAt.toInstant().isAfter(actualExpiredAt.toInstant())) {
            throw new IllegalStateException(
                    "expired orchestration chronology conflicts with terminal authority");
        }
        if (commandStatus == CommandStatus.FAILED) {
            if (!terminalReasonCode.equals(statusReasonCode)
                    || !terminalReceiptUri.equals(resultUri)
                    || !terminalReceiptSha256.equals(resultSha256)
                    || appliedAt != null) {
                throw new IllegalStateException(
                        "failed expired command is bound to another terminal authority");
            }
            return;
        }
        if (commandStatus != CommandStatus.EXPIRED
                || !expectedExpirationReasonCode.equals(statusReasonCode)
                || resultUri != null
                || resultSha256 != null
                || appliedAt != null) {
            throw new IllegalStateException(
                    "only the exact expired orchestration can converge without a commit");
        }
        commandStatus = CommandStatus.FAILED;
        statusReasonCode = terminalReasonCode;
        resultUri = terminalReceiptUri;
        resultSha256 = terminalReceiptSha256;
        // Deliberately preserve updatedAt == actualExpiredAt as the historic expiration authority.
    }

    public void markShadowCompleted(OffsetDateTime completedAt) {
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (commandStatus == CommandStatus.SHADOW_COMPLETED) {
            return;
        }
        if (commandStatus != CommandStatus.PENDING_ORCHESTRATION
                && commandStatus != CommandStatus.ORCHESTRATION_ACCEPTED) {
            throw new IllegalStateException("command cannot transition to SHADOW_COMPLETED");
        }
        commandStatus = CommandStatus.SHADOW_COMPLETED;
        statusReasonCode = null;
        if (orchestratedAt == null) {
            orchestratedAt = completedAt;
        }
        updatedAt = completedAt;
    }

    public void markApplied(
            String appliedResultUri,
            String appliedResultSha256,
            OffsetDateTime appliedByDomainAt) {
        Objects.requireNonNull(appliedByDomainAt, "appliedByDomainAt must not be null");
        if ((appliedResultUri == null) != (appliedResultSha256 == null)) {
            throw new IllegalArgumentException("applied result reference is incomplete");
        }
        if (commandStatus == CommandStatus.APPLIED) {
            if (!Objects.equals(resultUri, appliedResultUri)
                    || !Objects.equals(resultSha256, appliedResultSha256)) {
                throw new IllegalStateException(
                        "applied command is bound to another result");
            }
            return;
        }
        if (commandStatus != CommandStatus.PENDING_ORCHESTRATION
                && commandStatus != CommandStatus.ORCHESTRATION_ACCEPTED) {
            throw new IllegalStateException("command cannot transition to APPLIED");
        }
        commandStatus = CommandStatus.APPLIED;
        statusReasonCode = null;
        resultUri = appliedResultUri;
        resultSha256 = appliedResultSha256;
        if (orchestratedAt == null) {
            orchestratedAt = appliedByDomainAt;
        }
        appliedAt = appliedByDomainAt;
        updatedAt = appliedByDomainAt;
    }
}
