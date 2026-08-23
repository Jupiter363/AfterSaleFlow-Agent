package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import org.junit.jupiter.api.Test;

class ApprovalRecordDecisionActionTest {

    @Test
    void bindsTheReviewerActionForApproveModifyAndManualEscalation() {
        ApprovalRecordEntity approved = record(ApprovalDecisionType.APPROVE, "1");
        approved.bindDecisionActions("REFUND_ONLY", "REFUND_ONLY");
        assertThat(approved.getAiDecisionAction()).isEqualTo("REFUND_ONLY");
        assertThat(approved.getReviewerDecisionAction()).isEqualTo("REFUND_ONLY");

        ApprovalRecordEntity modified = record(ApprovalDecisionType.MODIFY_AND_APPROVE, "2");
        modified.bindDecisionActions("REFUND_ONLY", "REPLACE");
        assertThat(modified.getAiDecisionAction()).isEqualTo("REFUND_ONLY");
        assertThat(modified.getReviewerDecisionAction()).isEqualTo("REPLACE");

        ApprovalRecordEntity escalated = record(ApprovalDecisionType.ESCALATE_MANUAL, "3");
        escalated.bindDecisionActions("REFUND_ONLY", "ESCALATE_MANUAL");
        assertThat(escalated.getAiDecisionAction()).isEqualTo("REFUND_ONLY");
        assertThat(escalated.getReviewerDecisionAction()).isEqualTo("ESCALATE_MANUAL");
    }

    @Test
    void escalationCanNeverBecomeTheAiDecisionActionOrAnExecutableReviewAction() {
        assertThatThrownBy(() -> record(ApprovalDecisionType.APPROVE, "4")
                .bindDecisionActions("ESCALATE_MANUAL", "ESCALATE_MANUAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Judge decision code");
        assertThatThrownBy(() -> record(ApprovalDecisionType.MODIFY_AND_APPROVE, "5")
                .bindDecisionActions("REFUND_ONLY", "ESCALATE_MANUAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    private static ApprovalRecordEntity record(ApprovalDecisionType decision, String suffix) {
        return ApprovalRecordEntity.record(
                "APPROVAL_" + suffix,
                "CASE_1",
                "TASK_1",
                "PLAN_1",
                "reviewer-local",
                "PLATFORM_REVIEWER",
                decision,
                "{}",
                "{}",
                "reviewed",
                "hash-" + suffix);
    }
}
