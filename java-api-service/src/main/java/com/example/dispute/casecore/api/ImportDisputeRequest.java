package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ImportDisputeRequest(
        @NotBlank String sourceSystem,
        @NotBlank String externalCaseReference,
        @NotBlank String orderReference,
        String afterSalesReference,
        String logisticsReference,
        @NotBlank String userId,
        @NotBlank String merchantId,
        @NotBlank @Pattern(regexp = "USER|MERCHANT") String initiatorRole,
        @NotBlank String disputeType,
        @NotBlank String title,
        @NotBlank String description,
        @NotNull RiskLevel riskLevel) {

    ImportDisputeCommand toCommand() {
        return new ImportDisputeCommand(
                sourceSystem,
                externalCaseReference,
                orderReference,
                afterSalesReference,
                logisticsReference,
                userId,
                merchantId,
                initiatorRole,
                disputeType,
                title,
                description,
                riskLevel,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null);
    }
}
