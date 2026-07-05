package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.RiskLevel;

public record SimulateExternalImportCommand(
        int count,
        String scenario,
        RiskLevel riskLevelHint,
        ActorRole initiatorRoleHint,
        String currentActorId,
        String counterpartyActorId,
        String simulationBatchId) {

    public SimulateExternalImportCommand(
            int count,
            String scenario,
            RiskLevel riskLevelHint,
            ActorRole initiatorRoleHint,
            String currentActorId,
            String counterpartyActorId) {
        this(
                count,
                scenario,
                riskLevelHint,
                initiatorRoleHint,
                currentActorId,
                counterpartyActorId,
                "default");
    }

    public SimulateExternalImportCommand {
        if (count < 1 || count > 10) {
            throw new IllegalArgumentException("count must be between 1 and 10");
        }
        if (scenario == null || scenario.isBlank()) {
            scenario = "履约争议订单";
        }
        if (riskLevelHint == null) {
            riskLevelHint = RiskLevel.MEDIUM;
        }
        if (initiatorRoleHint != ActorRole.USER && initiatorRoleHint != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("initiatorRoleHint must be USER or MERCHANT");
        }
        if (currentActorId == null || currentActorId.isBlank()) {
            throw new IllegalArgumentException("currentActorId must not be blank");
        }
        if (counterpartyActorId == null || counterpartyActorId.isBlank()) {
            throw new IllegalArgumentException("counterpartyActorId must not be blank");
        }
        if (simulationBatchId == null || simulationBatchId.isBlank()) {
            simulationBatchId = "default";
        }
    }
}
