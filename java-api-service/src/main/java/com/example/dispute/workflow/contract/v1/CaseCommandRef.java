package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CaseCommandRef(
        String schemaVersion,
        String commandId,
        String tenantSurrogate,
        String caseId,
        long caseCommandSequence,
        CommandType commandType,
        RoomType roomType,
        long roomEpoch,
        ActorRef actorRef,
        PayloadRef payloadRef,
        long expectedProcessRevision,
        Instant occurredAt,
        Instant deadlineAt,
        String traceparent,
        String requestHash) {

    public CaseCommandRef {
        schemaVersion = version(schemaVersion, "case-command-ref.v1");
        required(commandId, "commandId");
        required(tenantSurrogate, "tenantSurrogate");
        required(caseId, "caseId");
        required(commandType, "commandType");
        required(roomType, "roomType");
        required(actorRef, "actorRef");
        required(payloadRef, "payloadRef");
        required(occurredAt, "occurredAt");
        required(deadlineAt, "deadlineAt");
        required(traceparent, "traceparent");
        required(requestHash, "requestHash");
    }
}
