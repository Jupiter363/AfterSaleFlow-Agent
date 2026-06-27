package com.example.dispute.caseintake.api;

import com.example.dispute.caseintake.application.CreateCaseCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateCaseRequest(
        @Size(max = 64) String orderId,
        @Size(max = 64) String afterSaleId,
        @NotBlank @Size(max = 128) String userId,
        @NotBlank @Size(max = 128) String merchantId,
        @NotBlank @Size(max = 4000) String description,
        @Size(max = 50) List<@Size(max = 128) String> attachmentIds,
        @NotBlank @Size(max = 32) String channel) {

    public CreateCaseRequest {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }

    CreateCaseCommand toCommand() {
        return new CreateCaseCommand(
                orderId,
                afterSaleId,
                userId,
                merchantId,
                description,
                attachmentIds,
                channel);
    }
}
