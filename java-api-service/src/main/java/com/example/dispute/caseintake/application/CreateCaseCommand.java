package com.example.dispute.caseintake.application;

import com.example.dispute.config.ActorRole;
import java.util.List;

public record CreateCaseCommand(
        String orderId,
        String afterSaleId,
        String logisticsId,
        String userId,
        String merchantId,
        String description,
        List<String> attachmentIds,
        String channel,
        ActorRole initiatorRole) {

    public CreateCaseCommand {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        initiatorRole = initiatorRole == null ? ActorRole.USER : initiatorRole;
    }

    public CreateCaseCommand(
            String orderId,
            String afterSaleId,
            String logisticsId,
            String userId,
            String merchantId,
            String description,
            List<String> attachmentIds,
            String channel) {
        this(
                orderId,
                afterSaleId,
                logisticsId,
                userId,
                merchantId,
                description,
                attachmentIds,
                channel,
                ActorRole.USER);
    }
}
