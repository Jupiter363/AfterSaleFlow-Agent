package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record CaseDomainEventRef(
        String schemaVersion,
        String eventId,
        String tenantSurrogate,
        String caseId,
        long caseEventSequence,
        String eventType,
        RoomType roomType,
        long roomEpoch,
        PayloadRef payloadRef,
        Instant occurredAt,
        String traceparent) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TRACEPARENT =
            Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    public CaseDomainEventRef {
        if (!"case-domain-event-ref.v1".equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "schemaVersion must be case-domain-event-ref.v1");
        }
        requireText(eventId, "eventId");
        requireText(tenantSurrogate, "tenantSurrogate");
        requireText(caseId, "caseId");
        requireText(eventType, "eventType");
        if (caseEventSequence < 1) {
            throw new IllegalArgumentException("caseEventSequence must be positive");
        }
        if (roomEpoch < 0) {
            throw new IllegalArgumentException("roomEpoch must not be negative");
        }
        Objects.requireNonNull(payloadRef, "payloadRef must not be null");
        if (!SHA256.matcher(payloadRef.sha256()).matches()) {
            throw new IllegalArgumentException("payloadRef sha256 is invalid");
        }
        if (payloadRef.sizeBytes() < 0) {
            throw new IllegalArgumentException("payloadRef sizeBytes must not be negative");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (traceparent == null || !TRACEPARENT.matcher(traceparent).matches()) {
            throw new IllegalArgumentException("traceparent is invalid");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
