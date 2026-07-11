package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.RiskLevel;
import java.math.BigDecimal;

public record SimulatedExternalDisputeTemplate(
        int templateNo,
        String title,
        String description,
        String originalStatement,
        String disputeType,
        RiskLevel riskLevel,
        String requestedResolution,
        BigDecimal requestedAmount,
        String requestedItems,
        String requestReason,
        String respondentAttitude,
        String respondentPosition) {

    public SimulatedExternalDisputeTemplate {
        if (templateNo < 1 || templateNo > 20) {
            throw new IllegalArgumentException("templateNo must be between 1 and 20");
        }
        requireText(title, "title");
        requireText(description, "description");
        requireText(originalStatement, "originalStatement");
        requireText(disputeType, "disputeType");
        if (riskLevel == null) {
            throw new IllegalArgumentException("riskLevel must not be null");
        }
        requireText(requestedResolution, "requestedResolution");
        requireText(requestedItems, "requestedItems");
        requireText(requestReason, "requestReason");
        requireText(respondentAttitude, "respondentAttitude");
        requireText(respondentPosition, "respondentPosition");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public InitiatorPerspective forInitiator(ActorRole initiatorRole) {
        if (initiatorRole == null) {
            throw new IllegalArgumentException("initiatorRole must not be null");
        }
        if (initiatorRole == ActorRole.USER) {
            return new InitiatorPerspective(
                    requestedResolution,
                    requestedAmount,
                    requestedItems,
                    requestReason,
                    originalStatement,
                    respondentAttitude,
                    respondentPosition);
        }
        if (initiatorRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException(
                    "simulated dispute initiator must be USER or MERCHANT");
        }

        String merchantPosition = asMerchantFirstPerson(respondentPosition);
        return new InitiatorPerspective(
                merchantRequestedResolution(),
                merchantRequestedAmount(),
                requestedItems,
                merchantPosition + "希望平台核验双方陈述和相关材料后确认处理方向。",
                merchantPosition + "用户的诉求是：" + requestReason,
                respondentAttitudeForMerchantInitiator(),
                "对方主张：" + requestReason);
    }

    private String merchantRequestedResolution() {
        return "AGREE".equals(respondentAttitude)
                ? requestedResolution
                : "VERIFY_OR_EXPLAIN_ONLY";
    }

    private BigDecimal merchantRequestedAmount() {
        return "AGREE".equals(respondentAttitude) ? requestedAmount : null;
    }

    private String respondentAttitudeForMerchantInitiator() {
        return switch (respondentAttitude) {
            case "NEED_MORE_INFO" -> "ALTERNATIVE_PROPOSED";
            default -> respondentAttitude;
        };
    }

    private static String asMerchantFirstPerson(String position) {
        String firstPerson;
        if (position.startsWith("对方称")) {
            firstPerson = "我们说明" + position.substring("对方称".length());
        } else if (position.startsWith("对方")) {
            firstPerson = "我们" + position.substring("对方".length());
        } else {
            firstPerson = "我们说明：" + position;
        }
        return firstPerson.endsWith("。") ? firstPerson : firstPerson + "。";
    }

    public record InitiatorPerspective(
            String requestedResolution,
            BigDecimal requestedAmount,
            String requestedItems,
            String requestReason,
            String originalStatement,
            String respondentAttitude,
            String respondentPosition) {

        public InitiatorPerspective {
            requireText(requestedResolution, "requestedResolution");
            requireText(requestedItems, "requestedItems");
            requireText(requestReason, "requestReason");
            requireText(originalStatement, "originalStatement");
            requireText(respondentAttitude, "respondentAttitude");
            requireText(respondentPosition, "respondentPosition");
        }
    }
}
