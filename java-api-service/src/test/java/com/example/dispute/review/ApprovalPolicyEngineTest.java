package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.review.domain.ApprovalPolicyEngine;
import com.example.dispute.review.domain.ApprovalPolicyInput;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalPolicyEngineTest {

    private final ApprovalPolicyEngine engine =
            new ApprovalPolicyEngine(
                    new BigDecimal("500.00"), new BigDecimal("300.00"));

    @Test
    void everyPlanRequiresRealPlatformReview() {
        var decision =
                engine.evaluate(
                        new ApprovalPolicyInput(
                                RiskLevel.LOW,
                                BigDecimal.ZERO,
                                List.of("QUERY_LOGISTICS"),
                                null,
                                false));

        assertThat(decision.requiredRole()).isEqualTo("PLATFORM_REVIEWER");
        assertThat(decision.requiredApprovals())
                .containsExactly("PLATFORM_HUMAN_REVIEW");
        assertThat(decision.autoApprove()).isFalse();
    }

    @Test
    void highValueRefundAndReshipBecomeUrgentRiskReview() {
        var refund =
                engine.evaluate(
                        new ApprovalPolicyInput(
                                RiskLevel.HIGH,
                                new BigDecimal("800.00"),
                                List.of("REFUND"),
                                "ITEM_SWAP_DISPUTE",
                                false));
        var reship =
                engine.evaluate(
                        new ApprovalPolicyInput(
                                RiskLevel.MEDIUM,
                                new BigDecimal("350.00"),
                                List.of("RESHIP"),
                                null,
                                false));

        assertThat(refund.priority()).isEqualTo("URGENT");
        assertThat(refund.requiredApprovals())
                .contains(
                        "PLATFORM_HUMAN_REVIEW",
                        "RISK_CONTROL_REVIEW");
        assertThat(refund.riskFlags())
                .contains("HIGH_VALUE_REFUND", "ITEM_SWAP_DISPUTE");
        assertThat(reship.riskFlags()).contains("HIGH_VALUE_RESHIP");
    }

    @Test
    void insufficientEvidenceIsNeverAutoApproved() {
        var decision =
                engine.evaluate(
                        new ApprovalPolicyInput(
                                RiskLevel.MEDIUM,
                                new BigDecimal("50.00"),
                                List.of("REFUND"),
                                "NON_RECEIPT",
                                true));

        assertThat(decision.priority()).isEqualTo("URGENT");
        assertThat(decision.riskFlags()).contains("EVIDENCE_INSUFFICIENT");
        assertThat(decision.autoApprove()).isFalse();
    }
}
