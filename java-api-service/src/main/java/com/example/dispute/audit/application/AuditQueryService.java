package com.example.dispute.audit.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {

    private static final List<ActorRole> ALLOWED_ROLES =
            List.of(
                    ActorRole.CUSTOMER_SERVICE,
                    ActorRole.PLATFORM_REVIEWER,
                    ActorRole.ADMIN,
                    ActorRole.SYSTEM);

    private final AuditLogRepository auditRepository;
    private final FulfillmentCaseRepository caseRepository;
    private final ObjectMapper objectMapper;

    public AuditQueryService(
            AuditLogRepository auditRepository,
            FulfillmentCaseRepository caseRepository,
            ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.caseRepository = caseRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<AuditLogView> listForCase(
            String caseId, AuthenticatedActor actor) {
        if (!ALLOWED_ROLES.contains(actor.role())) {
            throw new ForbiddenException("actor cannot view case audit logs");
        }
        if (!caseRepository.existsById(caseId)) {
            throw new NotFoundException(
                    ErrorCode.CASE_NOT_FOUND,
                    "case not found",
                    Map.of("case_id", caseId));
        }
        return auditRepository.findAllByCaseIdOrderByCreatedAtDesc(caseId)
                .stream()
                .map(this::toView)
                .toList();
    }

    private AuditLogView toView(AuditLogEntity entity) {
        return new AuditLogView(
                entity.getId(),
                entity.getCaseId(),
                entity.getTraceId(),
                entity.getRequestId(),
                entity.getUserId(),
                entity.getRole(),
                entity.getService(),
                entity.getAction(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getOutcome(),
                readJson(entity.getBeforeJson()),
                readJson(entity.getAfterJson()),
                readJson(entity.getMetadataJson()),
                entity.getCreatedAt());
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted audit json", exception);
        }
    }
}
