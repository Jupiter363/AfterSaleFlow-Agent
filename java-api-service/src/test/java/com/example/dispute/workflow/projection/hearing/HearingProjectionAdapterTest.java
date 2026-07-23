package com.example.dispute.workflow.projection.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.hearing.application.HearingFlowView;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HearingProjectionAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HearingProjectionAdapter adapter = new HearingProjectionAdapter();

    @Test
    void mapsTheExistingPayloadShapeFromAValidatedStageCursor() {
        ObjectNode questionSet =
                objectMapper
                        .createObjectNode()
                        .put("schema_version", "hearing_question_set.v1")
                        .put("question_set_id", "QUESTION-1");
        HearingProjectionSnapshot snapshot =
                new HearingProjectionSnapshot(
                        "hearing_flow.v2",
                        HearingFlowStage.PARTY_ANSWERS_OPEN,
                        5,
                        "WAITING_PARTIES",
                        "ACTIVE",
                        Instant.parse("2026-07-24T01:20:00Z"),
                        Instant.parse("2026-07-24T01:20:00Z"),
                        Map.of("USER", "SUBMITTED", "MERCHANT", "PENDING"),
                        List.of(
                                new HearingFlowView.ParticipantStatus(
                                        "user-1", "USER", "SUBMITTED"),
                                new HearingFlowView.ParticipantStatus(
                                        "merchant-1", "MERCHANT", "PENDING")),
                        false,
                        null,
                        questionSet,
                        null,
                        null,
                        Map.of());
        questionSet.put("question_set_id", "MUTATED");

        HearingFlowView view = adapter.adapt(snapshot);

        assertThat(view.status().flowSchemaVersion()).isEqualTo("hearing_flow.v2");
        assertThat(view.status().flowStage()).isEqualTo("PARTY_ANSWERS_OPEN");
        assertThat(view.status().stageCode()).isEqualTo("PARTY_ANSWERS_OPEN");
        assertThat(view.status().stageSequence()).isEqualTo(5);
        assertThat(view.status().partyStatuses())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("USER", "SUBMITTED", "MERCHANT", "PENDING"));
        assertThat(view.questionSet().path("question_set_id").asText()).isEqualTo("QUESTION-1");
    }

    @Test
    void rejectsAStageSequenceMismatchInsteadOfInferringProgress() {
        HearingProjectionSnapshot snapshot = baseline(HearingFlowStage.JURY_REVIEWING, 13);

        assertThatThrownBy(() -> adapter.adapt(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage sequence");
    }

    @Test
    void rejectsPartyStatusOutsideTheTwoAuthorizedPartyStages() {
        HearingProjectionSnapshot snapshot =
                new HearingProjectionSnapshot(
                        "hearing_flow.v2",
                        HearingFlowStage.INTAKE_SYNTHESIZING,
                        6,
                        "RUNNING",
                        "ACTIVE",
                        null,
                        null,
                        Map.of("USER", "SUBMITTED"),
                        List.of(),
                        false,
                        null,
                        null,
                        null,
                        null,
                        Map.of());

        assertThatThrownBy(() -> adapter.adapt(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("party status");
    }

    private static HearingProjectionSnapshot baseline(HearingFlowStage stage, long sequence) {
        return new HearingProjectionSnapshot(
                "hearing_flow.v2",
                stage,
                sequence,
                "RUNNING",
                "ACTIVE",
                null,
                null,
                Map.of(),
                List.of(),
                false,
                null,
                null,
                null,
                null,
                Map.of());
    }
}
