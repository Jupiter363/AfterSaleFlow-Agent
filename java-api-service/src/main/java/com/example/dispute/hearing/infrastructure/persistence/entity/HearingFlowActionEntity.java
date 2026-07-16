package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowSubmissionStatus;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable structured output or party terminal action in hearing_flow.v2. */
@Entity
@Table(name = "hearing_flow_action")
public class HearingFlowActionEntity extends AbstractEntity {

    @Column(name = "flow_instance_id", length = 64, nullable = false)
    private String flowInstanceId;

    @Column(name = "stage_id", length = 64, nullable = false)
    private String stageId;

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 32, nullable = false)
    private HearingFlowActionType actionType;

    @Column(name = "schema_version", length = 64, nullable = false)
    private String schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", length = 32)
    private ActorRole participantRole;

    @Column(name = "participant_id", length = 128)
    private String participantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "submission_status", length = 32)
    private HearingFlowSubmissionStatus submissionStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    @Column(name = "agent_run_id", length = 64)
    private String agentRunId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected HearingFlowActionEntity() {}

    private HearingFlowActionEntity(String id) {
        super(id);
    }

    public static HearingFlowActionEntity agentOutput(
            String id,
            String flowInstanceId,
            String stageId,
            String caseId,
            HearingFlowActionType actionType,
            String payloadJson,
            String contentHash,
            String agentRunId,
            Instant now,
            String actorId) {
        if (actionType == null || actionType.isPartyAction()) {
            throw new IllegalArgumentException("agent output requires a non-party action type");
        }
        return create(
                id,
                flowInstanceId,
                stageId,
                caseId,
                actionType,
                actionType.schemaVersion(),
                null,
                null,
                null,
                payloadJson,
                contentHash,
                required(agentRunId, "agentRunId"),
                now,
                actorId);
    }

    public static HearingFlowActionEntity partyAction(
            String id,
            String flowInstanceId,
            String stageId,
            String caseId,
            HearingFlowActionType actionType,
            String participantId,
            ActorRole participantRole,
            HearingFlowSubmissionStatus submissionStatus,
            String payloadJson,
            String contentHash,
            Instant now,
            String actorId) {
        if (actionType == null || !actionType.isPartyAction()) {
            throw new IllegalArgumentException("party submission requires a party action type");
        }
        if (participantRole != ActorRole.USER && participantRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("participantRole must be USER or MERCHANT");
        }
        return partyActionWithSchema(
                id,
                flowInstanceId,
                stageId,
                caseId,
                actionType,
                actionType.schemaVersion(),
                participantId,
                participantRole,
                submissionStatus,
                payloadJson,
                contentHash,
                now,
                actorId);
    }

    public static HearingFlowActionEntity partyActionWithSchema(
            String id,
            String flowInstanceId,
            String stageId,
            String caseId,
            HearingFlowActionType actionType,
            String schemaVersion,
            String participantId,
            ActorRole participantRole,
            HearingFlowSubmissionStatus submissionStatus,
            String payloadJson,
            String contentHash,
            Instant now,
            String actorId) {
        if (actionType == null || !actionType.isPartyAction()) {
            throw new IllegalArgumentException("party submission requires a party action type");
        }
        if (!actionType.acceptsSchemaVersion(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion is not valid for actionType");
        }
        if (participantRole != ActorRole.USER && participantRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("participantRole must be USER or MERCHANT");
        }
        return create(
                id,
                flowInstanceId,
                stageId,
                caseId,
                actionType,
                schemaVersion,
                required(participantId, "participantId"),
                participantRole,
                required(submissionStatus, "submissionStatus"),
                payloadJson,
                contentHash,
                null,
                now,
                actorId);
    }

    private static HearingFlowActionEntity create(
            String id,
            String flowInstanceId,
            String stageId,
            String caseId,
            HearingFlowActionType actionType,
            String schemaVersion,
            String participantId,
            ActorRole participantRole,
            HearingFlowSubmissionStatus submissionStatus,
            String payloadJson,
            String contentHash,
            String agentRunId,
            Instant now,
            String actorId) {
        HearingFlowActionEntity entity = new HearingFlowActionEntity(required(id, "id"));
        entity.flowInstanceId = required(flowInstanceId, "flowInstanceId");
        entity.stageId = required(stageId, "stageId");
        entity.caseId = required(caseId, "caseId");
        entity.actionType = actionType;
        entity.schemaVersion = required(schemaVersion, "schemaVersion");
        entity.participantId = participantId;
        entity.participantRole = participantRole;
        entity.submissionStatus = submissionStatus;
        entity.payloadJson = required(payloadJson, "payloadJson");
        entity.contentHash = requiredHash(contentHash);
        entity.agentRunId = agentRunId;
        entity.createdAt = required(now, "now");
        entity.createdBy = required(actorId, "actorId");
        return entity;
    }

    public String getFlowInstanceId() { return flowInstanceId; }
    public String getStageId() { return stageId; }
    public String getCaseId() { return caseId; }
    public HearingFlowActionType getActionType() { return actionType; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getParticipantId() { return participantId; }
    public ActorRole getParticipantRole() { return participantRole; }
    public HearingFlowSubmissionStatus getSubmissionStatus() { return submissionStatus; }
    public String getPayloadJson() { return payloadJson; }
    public String getContentHash() { return contentHash; }
    public String getAgentRunId() { return agentRunId; }

    private static String requiredHash(String value) {
        String hash = required(value, "contentHash");
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 value");
        }
        return hash;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
