package com.example.dispute.evaluation.application;

import com.example.dispute.domain.model.CaseStatus;
import java.time.OffsetDateTime;

public record ClosureView(
        String caseId,
        CaseStatus caseStatus,
        OffsetDateTime closedAt,
        String evaluationTraceId,
        String evaluationStatus) {}
