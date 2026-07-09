package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_a2a_message")
public class AgentA2AMessageEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "round_no", nullable = false)
    private int roundNo;

    @Column(name = "from_agent", length = 64, nullable = false)
    private String fromAgent;

    @Column(name = "to_agent", length = 64, nullable = false)
    private String toAgent;

    @Column(name = "message_type", length = 64, nullable = false)
    private String messageType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_refs_json", nullable = false, columnDefinition = "jsonb")
    private String inputRefsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "visibility", length = 32, nullable = false)
    private String visibility;

    @Column(name = "agent_run_id", length = 64)
    private String agentRunId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected AgentA2AMessageEntity() {}

    private AgentA2AMessageEntity(String id) {
        super(id);
    }

    public static AgentA2AMessageEntity create(
            String id,
            String caseId,
            int roundNo,
            String fromAgent,
            String toAgent,
            String messageType,
            String inputRefsJson,
            String payloadJson,
            String visibility,
            String agentRunId,
            Instant now,
            String actorId) {
        if (roundNo < 1) {
            throw new IllegalArgumentException("A2A round number must be positive");
        }
        AgentA2AMessageEntity entity = new AgentA2AMessageEntity(id);
        entity.caseId = required(caseId, "caseId");
        entity.roundNo = roundNo;
        entity.fromAgent = required(fromAgent, "fromAgent");
        entity.toAgent = required(toAgent, "toAgent");
        entity.messageType = required(messageType, "messageType");
        entity.inputRefsJson = inputRefsJson == null || inputRefsJson.isBlank() ? "{}" : inputRefsJson;
        entity.payloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        entity.visibility = required(visibility, "visibility");
        entity.agentRunId = agentRunId == null || agentRunId.isBlank() ? null : agentRunId;
        entity.createdAt = now;
        entity.createdBy = required(actorId, "actorId");
        return entity;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public String getCaseId() {
        return caseId;
    }

    public int getRoundNo() {
        return roundNo;
    }

    public String getFromAgent() {
        return fromAgent;
    }

    public String getToAgent() {
        return toAgent;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getInputRefsJson() {
        return inputRefsJson;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getVisibility() {
        return visibility;
    }

    public String getAgentRunId() {
        return agentRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
