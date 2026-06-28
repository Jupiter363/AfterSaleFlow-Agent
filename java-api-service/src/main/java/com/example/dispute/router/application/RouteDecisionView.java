package com.example.dispute.router.application;

import com.example.dispute.domain.model.RouteType;
import java.time.OffsetDateTime;

public record RouteDecisionView(
        String id,
        String caseId,
        RouteType routeType,
        String reasonCode,
        String reasonDetail,
        boolean requiresAdditionalEvidence,
        int dossierVersion,
        String policyRuleId,
        FlowConclusionView conclusion,
        OffsetDateTime createdAt) {}
