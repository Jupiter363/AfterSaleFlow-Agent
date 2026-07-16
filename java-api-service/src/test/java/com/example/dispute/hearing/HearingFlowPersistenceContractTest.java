package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowSubmissionStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HearingFlowPersistenceContractTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    @Test
    void flowCursorUsesIndependentStagesAndOnlyPartyStagesCarryDeadline() {
        HearingFlowInstanceEntity flow =
                HearingFlowInstanceEntity.start(
                        "HEARING_FLOW_1", "CASE_1", "HEARING_1", NOW, "system");

        flow.advance(
                HearingFlowStage.CASE_INTRODUCTION,
                2,
                null,
                NOW.plusSeconds(1),
                "system");

        assertThat(flow.getSchemaVersion()).isEqualTo("hearing_flow.v2");
        assertThat(flow.getCurrentStage()).isEqualTo(HearingFlowStage.CASE_INTRODUCTION);
        assertThat(flow.getStageSequence()).isEqualTo(2);
        assertThat(flow.getSharedDeadlineAt()).isNull();

        assertThatThrownBy(
                        () ->
                                flow.advance(
                                        HearingFlowStage.PARTY_ANSWERS_OPEN,
                                        3,
                                        null,
                                        NOW.plusSeconds(2),
                                        "system"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared deadline");
    }

    @Test
    void autoTimeoutIsSubmissionStatusOnPartyActionNotAnotherProvenanceMode() {
        HearingFlowActionEntity action =
                HearingFlowActionEntity.partyAction(
                        "ANSWER_USER",
                        "HEARING_FLOW_1",
                        "STAGE_5",
                        "CASE_1",
                        HearingFlowActionType.ANSWER_BUNDLE,
                        "user-1",
                        ActorRole.USER,
                        HearingFlowSubmissionStatus.AUTO_TIMEOUT,
                        "{\"schema_version\":\"hearing_answer_bundle.v1\"}",
                        HASH_A,
                        NOW,
                        "hearing-timeout");

        assertThat(action.getSubmissionStatus())
                .isEqualTo(HearingFlowSubmissionStatus.AUTO_TIMEOUT);
        assertThat(action.getParticipantId()).isEqualTo("user-1");
        assertThat(action.getParticipantRole()).isEqualTo(ActorRole.USER);
        assertThat(action.getAgentRunId()).isNull();
    }

    @Test
    void decisionArtifactsUseCanonicalSchemasAndExactParentIdHashChain() {
        HearingFlowArtifactEntity proposal =
                HearingFlowArtifactEntity.judgeProposal(
                        "PROPOSAL_1",
                        "CASE_1",
                        "HEARING_FLOW_1",
                        "TRIAL_DOSSIER_1",
                        HASH_A,
                        HASH_B,
                        "{}",
                        "AGENT_RUN_1",
                        NOW,
                        "judge");
        HearingFlowArtifactEntity report =
                HearingFlowArtifactEntity.juryReviewReport(
                        "REPORT_1",
                        "CASE_1",
                        "HEARING_FLOW_1",
                        "TRIAL_DOSSIER_1",
                        HASH_A,
                        proposal.getId(),
                        proposal.getContentHash(),
                        HASH_C,
                        "{}",
                        "AGENT_RUN_2",
                        NOW,
                        "jury");

        assertThat(proposal.getArtifactType()).isEqualTo(HearingArtifactType.JUDGE_PROPOSAL);
        assertThat(proposal.getSchemaVersion()).isEqualTo("judge_proposal.v1");
        assertThat(report.getSchemaVersion()).isEqualTo("jury_review_report.v1");
        assertThat(report.getProposalId()).isEqualTo("PROPOSAL_1");
        assertThat(report.getProposalContentHash()).isEqualTo(HASH_B);

        assertThatThrownBy(
                        () ->
                                HearingFlowArtifactEntity.adjudicationDraft(
                                        "DRAFT_1",
                                        "CASE_1",
                                        "HEARING_FLOW_1",
                                        "TRIAL_DOSSIER_1",
                                        HASH_A,
                                        "PROPOSAL_1",
                                        HASH_B,
                                        "REPORT_1",
                                        null,
                                        HASH_C,
                                        "{}",
                                        "AGENT_RUN_3",
                                        NOW,
                                        "judge"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent id/hash chain");
    }
}
