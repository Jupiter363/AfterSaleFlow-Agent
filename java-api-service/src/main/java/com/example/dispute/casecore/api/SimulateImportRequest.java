package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.SimulateExternalImportCommand;
import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.RiskLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SimulateImportRequest(
        @Min(1) @Max(1) int count,
        @Size(max = 256) String scenario,
        RiskLevel riskLevelHint,
        @NotNull ActorRole initiatorRoleHint,
        @NotBlank @Size(max = 128) String currentActorId,
        @NotBlank @Size(max = 128) String counterpartyActorId,
        @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                String simulationBatchId) {

    public SimulateExternalImportCommand toCommand() {
        return toCommand(null);
    }

    public SimulateExternalImportCommand toCommand(String fallbackBatchId) {
        String batchId =
                simulationBatchId == null || simulationBatchId.isBlank()
                        ? fallbackBatchId
                        : simulationBatchId;
        return new SimulateExternalImportCommand(
                count,
                scenario,
                riskLevelHint,
                initiatorRoleHint,
                currentActorId,
                counterpartyActorId,
                batchId);
    }
}
