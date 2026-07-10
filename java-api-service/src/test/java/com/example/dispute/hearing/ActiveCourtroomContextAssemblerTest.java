package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.application.ActiveCourtroomContextAssembler;
import com.example.dispute.hearing.application.AgentA2AMessageService;
import com.example.dispute.hearing.application.AgentA2AMessageView;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.tool.application.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActiveCourtroomContextAssemblerTest {

    private static final String CASE_ID = "CASE_FINAL_CONTEXT";

    @Mock private HearingRecordRepository hearingRecordRepository;
    @Mock private EvidenceDossierRepository evidenceDossierRepository;
    @Mock private HearingRoundRepository roundRepository;
    @Mock private HearingRoundPartySubmissionRepository submissionRepository;
    @Mock private AgentA2AMessageService a2aMessageService;
    @Mock private ToolRegistry toolRegistry;

    private ActiveCourtroomContextAssembler assembler;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        assembler =
                new ActiveCourtroomContextAssembler(
                        hearingRecordRepository,
                        evidenceDossierRepository,
                        roundRepository,
                        submissionRepository,
                        a2aMessageService,
                        toolRegistry,
                        objectMapper);
        lenient()
                .when(hearingRecordRepository
                        .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                                CASE_ID,
                                "C0_COURT_BOOTSTRAP",
                                1,
                                "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(Optional.of(bootstrapSnapshot()));
        lenient()
                .when(evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(CASE_ID))
                .thenReturn(Optional.empty());
        lenient().when(toolRegistry.definitions()).thenReturn(List.of());
    }

    @Test
    void finalConvergenceRejectsFormalJuryReportFromEarlierRound() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(2, "JURY_PANEL")));

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round 3");
    }

    @Test
    void finalConvergenceRejectsNonJuryFormalReport() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "EVIDENCE_CLERK")));

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JURY_PANEL");
    }

    @Test
    void finalConvergenceRejectsMissingRoundInRequiredSequence() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "JURY_PANEL")));
        when(roundRepository.findAllByCaseIdOrderByRoundNoAsc(CASE_ID))
                .thenReturn(List.of(sealedRound(1), sealedRound(3)));
        seedBothSubmissions(1);

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round 2 is missing");
    }

    @Test
    void finalConvergenceRejectsOpenRound() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "JURY_PANEL")));
        when(roundRepository.findAllByCaseIdOrderByRoundNoAsc(CASE_ID))
                .thenReturn(List.of(sealedRound(1), openRound(2), sealedRound(3)));
        seedBothSubmissions(1);

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round 2 is not sealed");
    }

    @Test
    void finalConvergenceRejectsTerminalStatusWithoutClosedAt() {
        HearingRoundEntity corruptRound = mock(HearingRoundEntity.class);
        when(corruptRound.getRoundNo()).thenReturn(1);
        when(corruptRound.getRoundStatus()).thenReturn(HearingRoundStatus.COMPLETED);
        when(corruptRound.getClosedAt()).thenReturn(null);
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "JURY_PANEL")));
        when(roundRepository.findAllByCaseIdOrderByRoundNoAsc(CASE_ID))
                .thenReturn(List.of(corruptRound, sealedRound(2), sealedRound(3)));

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round 1 is not sealed");
    }

    @Test
    void finalConvergenceRejectsRoundMissingMerchantSubmission() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "JURY_PANEL")));
        when(roundRepository.findAllByCaseIdOrderByRoundNoAsc(CASE_ID))
                .thenReturn(List.of(sealedRound(1), sealedRound(2), sealedRound(3)));
        seedBothSubmissions(1);
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(CASE_ID, 2))
                .thenReturn(List.of(submission(2, ActorRole.USER)));

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round 2 requires USER and MERCHANT");
    }

    @Test
    void finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "JURY_PANEL")));
        when(roundRepository.findAllByCaseIdOrderByRoundNoAsc(CASE_ID))
                .thenReturn(List.of(sealedRound(1), sealedRound(2), sealedRound(3)));
        seedBothSubmissions(1);
        seedBothSubmissions(2);
        seedBothSubmissions(3);

        assertThat(assembler.assembleFinalConvergence(CASE_ID, 3)
                        .path("jury_review_report")
                        .path("round_no")
                        .asInt())
                .isEqualTo(3);
        assertThat(assembler.sealedRounds(CASE_ID, 3))
                .hasSize(3)
                .allSatisfy(
                        round ->
                                assertThat(round.path("party_submissions"))
                                        .hasSize(2));
    }

    private void seedBothSubmissions(int roundNo) {
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        CASE_ID, roundNo))
                .thenReturn(
                        List.of(
                                submission(roundNo, ActorRole.USER),
                                submission(roundNo, ActorRole.MERCHANT)));
    }

    private static HearingRoundEntity sealedRound(int roundNo) {
        HearingRoundEntity round =
                HearingRoundEntity.open(
                        "ROUND_" + roundNo,
                        CASE_ID,
                        "HEARING_STATE",
                        roundNo,
                        1,
                        Instant.parse("2026-07-10T01:10:00Z").plusSeconds(roundNo),
                        Instant.parse("2026-07-10T01:00:00Z").plusSeconds(roundNo),
                        "system");
        round.complete(
                "{\"round\":" + roundNo + "}",
                null,
                Instant.parse("2026-07-10T01:05:00Z").plusSeconds(roundNo),
                "system");
        return round;
    }

    private static HearingRoundEntity openRound(int roundNo) {
        return HearingRoundEntity.open(
                "ROUND_" + roundNo,
                CASE_ID,
                "HEARING_STATE",
                roundNo,
                1,
                Instant.parse("2026-07-10T01:10:00Z").plusSeconds(roundNo),
                Instant.parse("2026-07-10T01:00:00Z").plusSeconds(roundNo),
                "system");
    }

    private static HearingRoundPartySubmissionEntity submission(
            int roundNo, ActorRole role) {
        return HearingRoundPartySubmissionEntity.submit(
                "SUB_" + roundNo + "_" + role,
                CASE_ID,
                "ROUND_" + roundNo,
                roundNo,
                role,
                role == ActorRole.USER ? "user-local" : "merchant-local",
                HearingRoundSubmissionSource.PARTY_ACTION,
                "{\"statement\":\"" + role + " round " + roundNo + "\"}",
                Instant.parse("2026-07-10T01:04:00Z").plusSeconds(roundNo));
    }

    private static AgentA2AMessageView formalReport(int roundNo, String fromAgent) {
        return new AgentA2AMessageView(
                "A2A_FORMAL_" + roundNo,
                CASE_ID,
                roundNo,
                fromAgent,
                AgentA2AMessageService.PRESIDING_JUDGE,
                "JURY_REVIEW_REPORT",
                "{\"round_no\":" + roundNo + "}",
                "{\"summary\":\"formal jury review\"}",
                "REVIEWER_VISIBLE",
                "RUN_JURY_" + roundNo,
                Instant.parse("2026-07-10T01:06:00Z"));
    }

    private static HearingRecordEntity bootstrapSnapshot() {
        return HearingRecordEntity.record(
                "HREC_BOOTSTRAP",
                CASE_ID,
                "HEARING_STATE",
                "WORKFLOW",
                "C0_COURT_BOOTSTRAP",
                1,
                "BOOTSTRAP_DOSSIER_SNAPSHOT",
                "{}",
                "{\"schema_version\":\"hearing_bootstrap_dossier.v1\"}",
                "{}",
                "hearing-bootstrap-v1",
                "deterministic",
                null,
                null,
                "system");
    }
}
