package com.example.dispute.caseintake.application;

import com.example.dispute.domain.model.RiskLevel;
import java.util.List;

public record IntakeAnalysis(
        String caseType,
        String disputeType,
        RiskLevel riskLevel,
        boolean potentialDispute,
        List<String> missingSlots,
        String title,
        String normalizedDescription) {

    public IntakeAnalysis {
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }
}
