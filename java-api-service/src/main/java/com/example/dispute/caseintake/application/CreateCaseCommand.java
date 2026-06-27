package com.example.dispute.caseintake.application;

import java.util.List;

public record CreateCaseCommand(
        String orderId,
        String afterSaleId,
        String userId,
        String merchantId,
        String description,
        List<String> attachmentIds,
        String channel) {

    public CreateCaseCommand {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }
}
