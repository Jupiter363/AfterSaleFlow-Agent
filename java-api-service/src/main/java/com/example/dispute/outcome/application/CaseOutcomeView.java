package com.example.dispute.outcome.application;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.executor.application.ActionRecordView;
import java.time.OffsetDateTime;
import java.util.List;

public record CaseOutcomeView(
        String caseId,
        String title,
        CaseStatus caseStatus,
        OffsetDateTime closedAt,
        FinalDecisionView finalDecision,
        AdjudicationDraftView adjudicationDraft,
        List<ActionRecordView> actions) {}
