package com.example.dispute.common.audit;

import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class AuditRecorder {

    private final AuditLogRepository repository;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public AuditRecorder(
            AuditLogRepository repository,
            AppProperties properties,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void record(
            AuthenticatedActor actor,
            String action,
            String resourceType,
            String resourceId,
            String caseId,
            Map<String, ?> before,
            Map<String, ?> after) {
        if (!properties.logging().auditEnabled()) {
            return;
        }
        repository.save(
                AuditLogEntity.record(
                        "AUDIT_" + compactUuid(),
                        caseId,
                        correlationId(TraceIdFilter.MDC_TRACE_KEY, "TRACE_INTERNAL_"),
                        correlationId(TraceIdFilter.MDC_REQUEST_KEY, "REQ_INTERNAL_"),
                        actor.actorId(),
                        actor.role().name(),
                        action,
                        resourceType,
                        resourceId,
                        writeJson(before),
                        writeJson(after)));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize audit payload", exception);
        }
    }

    private static String correlationId(String mdcKey, String prefix) {
        String value = MDC.get(mdcKey);
        return value == null || value.isBlank() ? prefix + compactUuid() : value;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
