package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.SimulateExternalImportCommand;
import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.RiskLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SimulateImportRequest(
        @Min(1) @Max(10) int count,
        @Size(max = 256) String scenario,
        RiskLevel riskLevelHint,
        @NotNull ActorRole initiatorRoleHint,
        @NotBlank @Size(max = 128) String currentActorId,
        @NotBlank @Size(max = 128) String counterpartyActorId) {

    public SimulateExternalImportCommand toCommand() {
        return new SimulateExternalImportCommand(
                count,
                scenario,
                riskLevelHint,
                initiatorRoleHint,
                currentActorId,
                counterpartyActorId);
    }
}
