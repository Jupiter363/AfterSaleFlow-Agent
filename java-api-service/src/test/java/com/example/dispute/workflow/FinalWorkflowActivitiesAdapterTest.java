package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.application.HearingOutcomeOrchestrationResult;
import com.example.dispute.hearing.application.HearingOutcomeOrchestrationService;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.infrastructure.persistence.entity.SettlementProposalEntity;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.DeliberationReportRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementProposalRepository;
import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.workflow.application.CaseFulfillmentDisputeActivitiesImpl;
import com.example.dispute.workflow.application.FinalWorkflowActivitiesAdapter;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinalWorkflowActivitiesAdapterTest {

    @Test
    void finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration() {
        CaseFulfillmentDisputeActivitiesImpl legacy =
                mock(CaseFulfillmentDisputeActivitiesImpl.class);
        when(legacy.analyzeHearing(any()))
                .thenReturn(
                        new HearingAnalysisActivityResult(
                                false,
                                true,
                                "DRAFT_FINAL_C6",
                                "COMPLETED"));
        FinalWorkflowActivitiesAdapter adapter =
                new FinalWorkflowActivitiesAdapter(
                        legacy,
                        mock(ApprovalRecordRepository.class),
                        mock(ReviewPacketRepository.class),
                        mock(ReviewTaskRepository.class),
                        mock(DeliberationReportRepository.class),
                        mock(AdjudicationDraftRepository.class),
                        mock(HearingStateRepository.class),
                        mock(SettlementProposalRepository.class),
                        new ObjectMapper().findAndRegisterModules(),
                        mock(HearingRoundService.class),
                        mock(HearingOutcomeOrchestrationService.class),
                        mock(CasePhaseClockRepository.class),
                        Clock.systemUTC());

        for (String stage :
                List.of(
                        "C1_ISSUE_FRAMING",
                        "C2_EVIDENCE_GAP",
                        "C3_EVIDENCE_REQUEST",
                        "C4_EVIDENCE_CROSS_CHECK",
                        "C5_RULE_APPLICATION")) {
            var result =
                    adapter.runStage(
                            "CASE_C6_ONLY",
                            "WORKFLOW_C6_ONLY",
                            stage,
                            3,
                            2,
                            false,
                            true,
                            3);
            assertThat(result.draftId()).isNull();
        }
        verify(legacy, never()).analyzeHearing(any());

        var draftStage =
                adapter.runStage(
                        "CASE_C6_ONLY",
                        "WORKFLOW_C6_ONLY",
                        "C6_DRAFT_GENERATION",
                        3,
                        2,
                        false,
                        true,
                        3);

        assertThat(draftStage.draftId()).isEqualTo("DRAFT_FINAL_C6");
        verify(legacy, times(1)).analyzeHearing(any());
    }

    @Test
    void completedSharedHearingCreatesReviewTaskForHumanGate() {
        CaseFulfillmentDisputeActivitiesImpl legacy =
                mock(CaseFulfillmentDisputeActivitiesImpl.class);
        CasePhaseClockRepository phaseClockRepository =
                mock(CasePhaseClockRepository.class);
        when(phaseClockRepository.findByCaseIdAndClockType(
                        "CASE_shared_hearing", PhaseClockType.HEARING))
                .thenReturn(Optional.empty());
        AdjudicationDraftRepository draftRepository = mock(AdjudicationDraftRepository.class);
        HearingStateRepository hearingStateRepository = mock(HearingStateRepository.class);
        SettlementProposalRepository settlementProposalRepository =
                mock(SettlementProposalRepository.class);
        when(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc("CASE_shared_hearing"))
                .thenReturn(Optional.empty());
        HearingStateEntity hearingState =
                HearingStateEntity.start(
                        "HEARING_shared",
                        "CASE_shared_hearing",
                        "hearing-window-CASE_shared_hearing",
                        "temporal-worker");
        when(hearingStateRepository.findByCaseId("CASE_shared_hearing"))
                .thenReturn(Optional.of(hearingState));
        SettlementProposalEntity settlement =
                SettlementProposalEntity.propose(
                        "SETTLEMENT_shared",
                        "CASE_shared_hearing",
                        1,
                        ActorRole.MERCHANT,
                        "merchant-local",
                        "商家同意补发正确型号 A-2026，用户退回错发商品。",
                        "{\"source\":\"PARTY_CONSENSUS\"}",
                        null,
                        Instant.now(),
                        "TRACE_shared");
        settlement.confirm("system", Instant.now());
        when(settlementProposalRepository.findTopByCaseIdOrderByProposalVersionDesc(
                        "CASE_shared_hearing"))
                .thenReturn(Optional.of(settlement));
        HearingOutcomeOrchestrationService outcomeOrchestration =
                mock(HearingOutcomeOrchestrationService.class);
        when(outcomeOrchestration.orchestrate(
                        "CASE_shared_hearing", "temporal-worker"))
                .thenReturn(
                        new HearingOutcomeOrchestrationResult(
                                "CASE_shared_hearing",
                                "REMEDY_shared_hearing",
                                "REVIEW_shared_hearing",
                                true,
                                true,
                                "REVIEW_GATE_READY"));
        FinalWorkflowActivitiesAdapter adapter =
                new FinalWorkflowActivitiesAdapter(
                        legacy,
                        mock(ApprovalRecordRepository.class),
                        mock(ReviewPacketRepository.class),
                        mock(ReviewTaskRepository.class),
                        mock(DeliberationReportRepository.class),
                        draftRepository,
                        hearingStateRepository,
                        settlementProposalRepository,
                        new ObjectMapper().findAndRegisterModules(),
                        mock(HearingRoundService.class),
                        outcomeOrchestration,
                        phaseClockRepository,
                        Clock.systemUTC());

        adapter.complete(
                "CASE_shared_hearing",
                "hearing-window-CASE_shared_hearing",
                "SETTLEMENT_CONFIRMED",
                false,
                false,
                1,
                "SETTLEMENT_CONFIRMED");

        verify(legacy)
                .completeHearing(
                        "CASE_shared_hearing",
                        "hearing-window-CASE_shared_hearing",
                        false,
                        false);
        verify(draftRepository)
                .save(
                        argThat(
                                draft ->
                                        draft.getDraftStatus()
                                                        .equals("SETTLEMENT_CONFIRMED")
                                                && draft.getRecommendedDecision()
                                                        .equals(
                                                                "RESHIP_BY_CONFIRMED_SETTLEMENT")
                                                && draft.getDraftText()
                                                        .contains("商家同意补发正确型号")));
        verify(outcomeOrchestration)
                .orchestrate("CASE_shared_hearing", "temporal-worker");
        verify(legacy, never())
                .planRemedy(
                        "CASE_shared_hearing",
                        "hearing-window-CASE_shared_hearing");
        verify(legacy, never())
                .createReviewTask("CASE_shared_hearing", "REMEDY_shared_hearing");
    }
}
