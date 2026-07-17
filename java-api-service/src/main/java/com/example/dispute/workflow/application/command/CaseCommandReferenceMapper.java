package com.example.dispute.workflow.application.command;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

public final class CaseCommandReferenceMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private CaseCommandReferenceMapper() {}

    public static CaseCommandRef fromEntity(
            CaseCommandEntity entity, ObjectMapper objectMapper) {
        List<String> scopes;
        try {
            scopes = objectMapper.readValue(entity.getActorScopesJson(), STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored actor scopes are invalid", exception);
        }
        return new CaseCommandRef(
                "case-command-ref.v1",
                entity.getCommandId(),
                entity.getTenantSurrogate(),
                entity.getCaseId(),
                entity.getCaseCommandSequence(),
                entity.getCommandType(),
                entity.getRoomType(),
                entity.getRoomEpoch(),
                new ActorRef(entity.getActorId(), entity.getActorRole(), scopes),
                new PayloadRef(
                        entity.getPayloadSchemaVersion(),
                        entity.getPayloadUri(),
                        entity.getPayloadSha256(),
                        entity.getPayloadSizeBytes()),
                entity.getExpectedProcessRevision(),
                entity.getOccurredAt().toInstant(),
                entity.getDeadlineAt().toInstant(),
                entity.getTraceparent(),
                entity.getRequestHash());
    }
}
