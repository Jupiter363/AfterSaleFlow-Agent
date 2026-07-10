package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.application.AgentA2ACommand;
import com.example.dispute.hearing.application.AgentA2AMessageService;
import com.example.dispute.hearing.application.AgentA2AMessageView;
import com.example.dispute.hearing.infrastructure.persistence.entity.AgentA2AMessageEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.AgentA2AMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentA2AMessageServiceTest {

    @Mock private AgentA2AMessageRepository repository;

    @Test
    void recordsJurySilentNotesAndFindsThemForLaterJudgeRounds() {
        AgentA2AMessageService service =
                new AgentA2AMessageService(
                        repository,
                        new ObjectMapper(),
                        Clock.fixed(Instant.parse("2026-07-08T02:00:00Z"), ZoneOffset.UTC));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AgentA2AMessageView recorded =
                service.record(
                        new AgentA2ACommand(
                                "CASE_A2A",
                                2,
                                "JURY_PANEL",
                                "PRESIDING_JUDGE",
                                "JURY_SILENT_NOTE",
                                Map.of("evidence_dossier_version", 2),
                                Map.of("judge_attention", List.of("签收人身份仍需关注")),
                                "SYSTEM_AUDIT_ONLY",
                                "RUN_JURY_2"));
        ArgumentCaptor<AgentA2AMessageEntity> entity =
                ArgumentCaptor.forClass(AgentA2AMessageEntity.class);
        org.mockito.Mockito.verify(repository).save(entity.capture());
        when(repository
                        .findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(
                                "CASE_A2A", "PRESIDING_JUDGE", 3))
                .thenReturn(List.of(entity.getValue()));

        assertThat(recorded.a2aMessageId()).startsWith("A2A_");
        assertThat(recorded.roundNo()).isEqualTo(2);

        assertThat(service.findForJudge("CASE_A2A", 3))
                .singleElement()
                .satisfies(
                        note -> {
                            assertThat(note.messageType()).isEqualTo("JURY_SILENT_NOTE");
                            assertThat(note.fromAgent()).isEqualTo("JURY_PANEL");
                            assertThat(note.toAgent()).isEqualTo("PRESIDING_JUDGE");
                            assertThat(note.payloadJson()).contains("签收人身份仍需关注");
                            assertThat(note.inputRefsJson()).contains("\"evidence_dossier_version\":2");
                            assertThat(note.visibility()).isEqualTo("SYSTEM_AUDIT_ONLY");
                        });
    }

    @Test
    void checksTheExactFormalJuryReportForTheFinalRound() {
        AgentA2AMessageService service =
                new AgentA2AMessageService(
                        repository,
                        new ObjectMapper(),
                        Clock.fixed(Instant.parse("2026-07-08T02:00:00Z"), ZoneOffset.UTC));
        when(repository
                        .existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType(
                                "CASE_A2A",
                                3,
                                "JURY_PANEL",
                                AgentA2AMessageService.PRESIDING_JUDGE,
                                "JURY_REVIEW_REPORT"))
                .thenReturn(true);

        assertThat(service.hasFormalJuryReviewReport("CASE_A2A", 3)).isTrue();
    }
}
