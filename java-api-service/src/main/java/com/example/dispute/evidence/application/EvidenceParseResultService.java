package com.example.dispute.evidence.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceParseResultService {

    private final EvidenceItemRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditRecorder auditRecorder;

    public EvidenceParseResultService(
            EvidenceItemRepository repository,
            ObjectMapper objectMapper,
            AuditRecorder auditRecorder) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.auditRecorder = auditRecorder;
    }

    @Transactional
    public void apply(
            String evidenceId,
            ParseResultCommand command,
            AuthenticatedActor actor) {
        if (actor.role() != ActorRole.SYSTEM) {
            throw new ForbiddenException("only an internal service can update parse results");
        }
        EvidenceItemEntity entity =
                repository
                        .findById(evidenceId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.EVIDENCE_NOT_FOUND,
                                                "evidence not found",
                                                Map.of("evidence_id", evidenceId)));
        String before = entity.getParseStatus().name();
        Map<String, Object> extraction = new LinkedHashMap<>(command.metadata());
        if ("SUCCEEDED".equals(command.status())) {
            entity.applyParseSuccess(
                    command.text(), writeJson(extraction), actor.actorId());
        } else if ("FAILED".equals(command.status())) {
            extraction.put(
                    "error_code",
                    command.errorCode() == null
                            ? "PARSE_FAILED"
                            : command.errorCode());
            entity.applyParseFailure(writeJson(extraction), actor.actorId());
        } else {
            throw new IllegalArgumentException("unsupported parse result status");
        }
        auditRecorder.record(
                actor,
                "EVIDENCE_PARSED",
                "EVIDENCE_ITEM",
                evidenceId,
                entity.getCaseId(),
                Map.of("parse_status", before),
                Map.of("parse_status", entity.getParseStatus().name()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize extraction metadata", exception);
        }
    }
}
