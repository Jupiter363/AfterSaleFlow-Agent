package com.example.dispute.evidence.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record BuildDossierResult(
        String id,
        String caseId,
        int version,
        Map<String, Object> summary,
        List<TimelineEntry> timeline,
        List<Map<String, Object>> matrix) {

    public record TimelineEntry(
            String evidenceId,
            String evidenceType,
            String sourceType,
            OffsetDateTime occurredAt,
            String description) {}
}
