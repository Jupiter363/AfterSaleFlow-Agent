package com.example.dispute.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record AuditLogView(
        String id,
        String caseId,
        String traceId,
        String requestId,
        String actorId,
        String role,
        String service,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        JsonNode before,
        JsonNode after,
        JsonNode metadata,
        OffsetDateTime createdAt) {}
