package com.example.dispute.casecore.api;

import com.example.dispute.caseintake.application.CreateCaseCommand;
import com.example.dispute.config.ActorRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Public intake contract for the final dispute API. */
public record CreateDisputeRequest(
        @NotNull ActorRole initiatorRole,
        @Size(max = 64) String orderReference,
        @Size(max = 64) String afterSalesReference,
        @Size(max = 64) String logisticsReference,
        @NotBlank @Size(max = 128) String userId,
        @NotBlank @Size(max = 128) String merchantId,
        @NotBlank @Size(max = 4000) String description,
        @Size(max = 50) List<@Size(max = 128) String> attachmentIds,
        @Size(max = 32) String channel) {

    public CreateDisputeRequest {
        attachmentIds =
                attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        channel = channel == null || channel.isBlank() ? "WEB" : channel;
    }

    CreateCaseCommand toCommand() {
        return new CreateCaseCommand(
                orderReference,
                afterSalesReference,
                logisticsReference,
                userId,
                merchantId,
                description,
                attachmentIds,
                channel,
                initiatorRole);
    }
}
