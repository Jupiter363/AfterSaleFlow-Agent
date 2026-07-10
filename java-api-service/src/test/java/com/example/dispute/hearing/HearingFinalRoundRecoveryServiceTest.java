package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.DisputeProperties;
import com.example.dispute.hearing.application.HearingCourtOrchestrator;
import com.example.dispute.hearing.application.HearingFinalRoundRecoveryService;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class HearingFinalRoundRecoveryServiceTest {

    @Mock private HearingRoundRepository roundRepository;
    @Mock private AdjudicationDraftRepository draftRepository;
    @Mock private HearingCourtOrchestrator courtOrchestrator;
    @Mock private HearingWorkflowCoordinator workflowCoordinator;
    @Mock private DisputeProperties disputeProperties;

    private HearingFinalRoundRecoveryService service;

    @BeforeEach
    void setUp() {
        when(disputeProperties.maxHearingRounds()).thenReturn(3);
        service =
                new HearingFinalRoundRecoveryService(
                        roundRepository,
                        draftRepository,
                        courtOrchestrator,
                        workflowCoordinator,
                        disputeProperties);
    }

    @Test
    void repairsFormalJuryReportBeforeResignalingFinalRound() {
        HearingRoundEntity round = finalRound("CASE_RECOVER");
        when(roundRepository.findFinalRoundsWithoutDraftAfter(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        isNull(),
                        eq(""),
                        any(Pageable.class)))
                .thenReturn(List.of(round));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_RECOVER", 4))
                .thenReturn(Optional.empty());
        when(courtOrchestrator.hasCompleteFormalJuryReport("CASE_RECOVER", 3))
                .thenReturn(true);
        when(workflowCoordinator.roundCompletedNow("CASE_RECOVER", 3, false))
                .thenReturn(true);

        assertThat(service.recoverFinalRoundsWithoutDraft(10)).isEqualTo(1);

        InOrder order = inOrder(courtOrchestrator, workflowCoordinator);
        order.verify(courtOrchestrator)
                .afterRoundClosed("CASE_RECOVER", 3, true, "TRACE_HEARING_FINAL_RECOVERY_3");
        order.verify(courtOrchestrator)
                .hasCompleteFormalJuryReport("CASE_RECOVER", 3);
        order.verify(workflowCoordinator)
                .roundCompletedNow("CASE_RECOVER", 3, false);
    }

    @Test
    void doesNotSignalWhenFormalJuryReportStillCannotBePersisted() {
        HearingRoundEntity round = finalRound("CASE_REPORT_MISSING");
        when(roundRepository.findFinalRoundsWithoutDraftAfter(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        isNull(),
                        eq(""),
                        any(Pageable.class)))
                .thenReturn(List.of(round));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_REPORT_MISSING", 4))
                .thenReturn(Optional.empty());
        when(courtOrchestrator.hasCompleteFormalJuryReport("CASE_REPORT_MISSING", 3))
                .thenReturn(false);

        assertThat(service.recoverFinalRoundsWithoutDraft(10)).isZero();

        verify(workflowCoordinator, never())
                .roundCompletedNow(any(), any(Integer.class), any(Boolean.class));
    }

    @Test
    void doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A() {
        HearingRoundEntity round = finalRound("CASE_A2A_MISSING");
        when(roundRepository.findFinalRoundsWithoutDraftAfter(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        isNull(),
                        eq(""),
                        any(Pageable.class)))
                .thenReturn(List.of(round));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_A2A_MISSING", 4))
                .thenReturn(Optional.empty());
        when(courtOrchestrator.hasCompleteFormalJuryReport("CASE_A2A_MISSING", 3))
                .thenReturn(false);

        assertThat(service.recoverFinalRoundsWithoutDraft(10)).isZero();

        verify(workflowCoordinator, never())
                .roundCompletedNow(any(), any(Integer.class), any(Boolean.class));
    }

    @Test
    void skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion() {
        HearingRoundEntity round = finalRound("CASE_ALREADY_DRAFTED");
        when(roundRepository.findFinalRoundsWithoutDraftAfter(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        isNull(),
                        eq(""),
                        any(Pageable.class)))
                .thenReturn(List.of(round));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_ALREADY_DRAFTED", 4))
                .thenReturn(Optional.of(mock()));

        assertThat(service.recoverFinalRoundsWithoutDraft(10)).isZero();

        verify(courtOrchestrator, never())
                .afterRoundClosed(any(), any(Integer.class), any(Boolean.class), any());
        verify(workflowCoordinator, never())
                .roundCompletedNow(any(), any(Integer.class), any(Boolean.class));
    }

    @Test
    void rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate() {
        HearingRoundEntity permanentlyFailing =
                finalRound(
                        "ROUND_PERMANENT_FAILURE",
                        "CASE_PERMANENT_FAILURE",
                        Instant.parse("2026-07-10T01:00:00Z"));
        HearingRoundEntity recoverable =
                finalRound(
                        "ROUND_RECOVERABLE",
                        "CASE_RECOVERABLE",
                        Instant.parse("2026-07-10T01:01:00Z"));
        when(roundRepository.findFinalRoundsWithoutDraftAfter(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        isNull(),
                        eq(""),
                        any(Pageable.class)))
                .thenReturn(List.of(permanentlyFailing));
        when(roundRepository.findFinalRoundsWithoutDraftAfter(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        eq(Instant.parse("2026-07-10T01:00:00Z")),
                        eq("ROUND_PERMANENT_FAILURE"),
                        any(Pageable.class)))
                .thenReturn(List.of(recoverable));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_PERMANENT_FAILURE", 4))
                .thenReturn(Optional.empty());
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_RECOVERABLE", 4))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new IllegalStateException("permanent recovery failure"))
                .when(courtOrchestrator)
                .afterRoundClosed(
                        "CASE_PERMANENT_FAILURE",
                        3,
                        true,
                        "TRACE_HEARING_FINAL_RECOVERY_3");
        when(courtOrchestrator.hasCompleteFormalJuryReport("CASE_RECOVERABLE", 3))
                .thenReturn(true);
        when(workflowCoordinator.roundCompletedNow("CASE_RECOVERABLE", 3, false))
                .thenReturn(true);

        assertThat(service.recoverFinalRoundsWithoutDraft(1)).isZero();
        assertThat(service.recoverFinalRoundsWithoutDraft(1)).isEqualTo(1);

        verify(courtOrchestrator)
                .afterRoundClosed(
                        "CASE_RECOVERABLE",
                        3,
                        true,
                        "TRACE_HEARING_FINAL_RECOVERY_3");
        verify(workflowCoordinator)
                .roundCompletedNow("CASE_RECOVERABLE", 3, false);
    }

    private static HearingRoundEntity finalRound(String caseId) {
        return finalRound(
                "ROUND_" + caseId,
                caseId,
                Instant.parse("2026-07-10T01:00:00Z"));
    }

    private static HearingRoundEntity finalRound(
            String roundId, String caseId, Instant closedAt) {
        HearingRoundEntity round = mock(HearingRoundEntity.class);
        when(round.getId()).thenReturn(roundId);
        when(round.getCaseId()).thenReturn(caseId);
        when(round.getClosedAt()).thenReturn(closedAt);
        return round;
    }
}
