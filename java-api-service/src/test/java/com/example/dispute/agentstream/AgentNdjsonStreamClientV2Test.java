package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentStreamProtocolException;
import com.example.dispute.agentstream.application.AgentStreamFrame;
import com.example.dispute.agentstream.infrastructure.AgentNdjsonStreamClient;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentNdjsonStreamClientV2Test {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    @Test
    void parsesAttemptScopedAllowlistedEventsAndHashBoundFinal() {
        var state = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Set.of("room_utterance"));

        assertThat(parse(state, event(0, "attempt_started", "{\"node\":\"evidence_turn\"}"))
                        .eventType())
                .isEqualTo(StreamEventType.ATTEMPT_STARTED);
        assertThat(parse(state, event(
                                1,
                                "visible_delta",
                                "{\"node\":\"evidence_turn\",\"field\":\"room_utterance\",\"delta\":\"公开文本\"}"))
                        .payload()
                        .delta())
                .isEqualTo("公开文本");
        assertThat(parse(state, event(
                                2,
                                "final",
                                "{\"final_result_ref\":\"urn:result:1\",\"final_result_hash\":\""
                                        + "a".repeat(64)
                                        + "\"}"))
                        .payload()
                        .finalResultHash())
                .isEqualTo("a".repeat(64));
    }

    @Test
    void preservesWhitespaceOnlyVisibleDeltasWithoutNormalizingAdjacentOutput() {
        var state = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Set.of("room_utterance"));
        parse(state, event(0, "attempt_started", "{\"node\":\"evidence_turn\"}"));

        List<String> deltas = List.of(
                parse(state, event(
                                1,
                                "visible_delta",
                                "{\"node\":\"evidence_turn\",\"field\":\"room_utterance\","
                                        + "\"delta\":\"before\"}"))
                        .payload()
                        .delta(),
                parse(state, event(
                                2,
                                "visible_delta",
                                "{\"node\":\"evidence_turn\",\"field\":\"room_utterance\","
                                        + "\"delta\":\" \"}"))
                        .payload()
                        .delta(),
                parse(state, event(
                                3,
                                "visible_delta",
                                "{\"node\":\"evidence_turn\",\"field\":\"room_utterance\","
                                        + "\"delta\":\"\\n\"}"))
                        .payload()
                        .delta(),
                parse(state, event(
                                4,
                                "visible_delta",
                                "{\"node\":\"evidence_turn\",\"field\":\"room_utterance\","
                                        + "\"delta\":\"\\t\"}"))
                        .payload()
                        .delta(),
                parse(state, event(
                                5,
                                "visible_delta",
                                "{\"node\":\"evidence_turn\",\"field\":\"room_utterance\","
                                        + "\"delta\":\"after\"}"))
                        .payload()
                        .delta());

        String combined = String.join("", deltas);
        assertThat(deltas).containsExactly("before", " ", "\n", "\t", "after");
        assertThat(combined).isEqualTo("before \n\tafter");
        assertThat(combined.getBytes(StandardCharsets.UTF_8))
                .isEqualTo("before \n\tafter".getBytes(StandardCharsets.UTF_8));

        var emptyDelta = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Set.of("room_utterance"));
        parse(emptyDelta, event(0, "attempt_started", "{\"node\":\"evidence_turn\"}"));
        assertThatThrownBy(() -> parse(emptyDelta, event(
                        1,
                        "visible_delta",
                        "{\"node\":\"evidence_turn\",\"field\":\"room_utterance\",\"delta\":\"\"}")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("missing delta");
    }

    @Test
    void rejectsHiddenFieldsWrongAttemptsAndEventsAfterTerminal() {
        var state = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Set.of("room_utterance"));
        parse(state, event(0, "attempt_started", "{\"node\":\"evidence_turn\"}"));

        assertThatThrownBy(() -> parse(state, event(
                        1,
                        "visible_delta",
                        "{\"node\":\"evidence_turn\",\"field\":\"reasoning_content\",\"delta\":\"secret\"}")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("non-public field");

        String wrongAttempt = event(1, "error", "{\"error_code\":\"FAILED\",\"retryable\":false}")
                .replace("attempt-1", "attempt-2");
        assertThatThrownBy(() -> parse(state, wrongAttempt))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("identity");

        parse(state, event(1, "error", "{\"error_code\":\"FAILED\",\"retryable\":false}"));
        assertThatThrownBy(() -> parse(state, event(2, "usage", usage())))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("terminal state");
    }

    @Test
    void rejectsUnknownPayloadFieldsInsteadOfPersistingRawModelData() {
        var state = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Set.of("room_utterance"));
        parse(state, event(0, "attempt_started", "{\"node\":\"evidence_turn\"}"));

        assertThatThrownBy(() -> parse(state, event(
                        1,
                        "visible_delta",
                        "{\"node\":\"evidence_turn\",\"field\":\"room_utterance\",\"delta\":\"ok\",\"raw_response\":{}}")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("unknown fields");
    }

    @Test
    void enforcesVisibleFieldsPerProducingNode() {
        var state = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1",
                "attempt-1",
                Audience.USER,
                Map.of(
                        "public_node", Set.of("answer"),
                        "private_node", Set.of("summary")));
        parse(state, event(0, "attempt_started", "{\"node\":\"public_node\"}"));

        assertThatThrownBy(() -> parse(
                        state,
                        event(
                                1,
                                "visible_delta",
                                "{\"node\":\"private_node\",\"field\":\"answer\","
                                        + "\"delta\":\"must-not-cross-node-policy\"}")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("non-public field");
    }

    @Test
    void requiresContiguousSequencesAndATerminalAtEndOfStream() {
        var gap = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Map.of());
        parse(gap, event(0, "attempt_started", "{\"node\":\"start\"}"));
        assertThatThrownBy(() -> parse(gap, event(2, "usage", usage())))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("sequence");

        var incomplete = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Map.of());
        parse(incomplete, event(0, "attempt_started", "{\"node\":\"start\"}"));
        assertThatThrownBy(incomplete::assertComplete)
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("terminal");

        var complete = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Map.of());
        parse(complete, event(0, "attempt_started", "{\"node\":\"start\"}"));
        parse(
                complete,
                event(
                        1,
                        "final",
                        "{\"final_result_ref\":\"urn:result:1\","
                                + "\"final_result_hash\":\""
                                + "a".repeat(64)
                                + "\"}"));
        complete.assertComplete();
    }

    @Test
    void rejectsNegativeSequencesAndFractionalUsage() {
        var negative = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Map.of());
        assertThatThrownBy(() -> parse(
                        negative,
                        event(-1, "attempt_started", "{\"node\":\"start\"}")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("sequence");

        var fractional = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Map.of());
        parse(fractional, event(0, "attempt_started", "{\"node\":\"start\"}"));
        assertThatThrownBy(() -> parse(
                        fractional,
                        event(
                                1,
                                "usage",
                                "{\"usage\":{\"input_tokens\":1.5,"
                                        + "\"output_tokens\":2,\"total_tokens\":3}}")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("usage");
    }

    @Test
    void frozenV2ReaderParsesAttemptResetForCompatibilityWithoutMakingItTerminal() {
        var state = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Set.of("room_utterance"));
        parse(state, event(0, "attempt_started", "{\"node\":\"evidence_turn\"}"));

        var reset = parse(
                state,
                event(
                        1,
                        "attempt_reset",
                        "{\"reset_attempt_id\":\"attempt-previous\","
                                + "\"reason_code\":\"VISIBLE_OUTPUT_SUPERSEDED\"}"));
        var finalEvent = parse(
                state,
                event(
                        2,
                        "final",
                        "{\"final_result_ref\":\"urn:result:1\",\"final_result_hash\":\""
                                + "a".repeat(64)
                                + "\"}"));

        assertThat(reset.eventType()).isEqualTo(StreamEventType.ATTEMPT_RESET);
        assertThat(reset.payload().resetAttemptId()).isEqualTo("attempt-previous");
        assertThat(reset.payload().reasonCode()).isEqualTo("VISIBLE_OUTPUT_SUPERSEDED");
        assertThat(finalEvent.eventType()).isEqualTo(StreamEventType.FINAL);
    }

    @Test
    void frozenV2ReaderRejectsSelfReferentialAndMalformedAttemptResetPayloads() {
        var selfReference = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Set.of("room_utterance"));
        parse(
                selfReference,
                event(0, "attempt_started", "{\"node\":\"evidence_turn\"}"));

        assertThatThrownBy(() -> parse(
                        selfReference,
                        event(
                                1,
                                "attempt_reset",
                                "{\"reset_attempt_id\":\"attempt-1\","
                                        + "\"reason_code\":\"VISIBLE_OUTPUT_SUPERSEDED\"}")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("older attempt");

        var missingReason = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Set.of("room_utterance"));
        parse(
                missingReason,
                event(0, "attempt_started", "{\"node\":\"evidence_turn\"}"));
        assertThatThrownBy(() -> parse(
                        missingReason,
                        event(
                                1,
                                "attempt_reset",
                                "{\"reset_attempt_id\":\"attempt-previous\"}")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("reason_code");
    }

    @Test
    void rejectsIdentifiersAndReferencesOutsideTheFrozenSchema() {
        assertThatThrownBy(
                        () ->
                                new AgentNdjsonStreamClient.V2ProtocolState(
                                        "run with spaces",
                                        "attempt-1",
                                        Audience.USER,
                                        Set.of("room_utterance")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("identifier");

        var state = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Set.of("room_utterance"));
        parse(state, event(0, "attempt_started", "{\"node\":\"evidence_turn\"}"));
        assertThatThrownBy(
                        () ->
                                parse(
                                        state,
                                        event(
                                                1,
                                                "final",
                                                "{\"final_result_ref\":\"urn:"
                                                        + "x".repeat(1025)
                                                        + "\",\"final_result_hash\":\""
                                                        + "a".repeat(64)
                                                        + "\"}")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("reference");

        var malformedReference = new AgentNdjsonStreamClient.V2ProtocolState(
                "run-1", "attempt-1", Audience.USER, Set.of("room_utterance"));
        parse(
                malformedReference,
                event(0, "attempt_started", "{\"node\":\"evidence_turn\"}"));
        assertThatThrownBy(() -> parse(
                        malformedReference,
                        event(
                                1,
                                "final",
                                "{\"final_result_ref\":\"urn:\","
                                        + "\"final_result_hash\":\""
                                        + "a".repeat(64)
                                        + "\"}")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("reference");
    }

    @Test
    void v1AdapterProjectsOnlyPublicPreviewAndRejectsRawFinal() {
        AgentStreamFrame visible = new AgentStreamFrame(
                1, "visible_delta", "evidence_turn", "room_utterance", "public",
                null, null, null, null, null);
        var adapted = AgentNdjsonStreamClient.adaptV1Frame(
                visible,
                "run-1",
                "attempt-1",
                Audience.USER,
                "evidence_turn",
                Set.of("room_utterance"),
                Instant.parse("2026-07-19T00:00:00Z"));
        assertThat(adapted.payload().delta()).isEqualTo("public");

        AgentStreamFrame rawFinal = new AgentStreamFrame(
                2, "final", null, null, null, null, null, null,
                MAPPER.createObjectNode().put("reasoning_content", "secret"), null);
        assertThatThrownBy(() -> AgentNdjsonStreamClient.adaptV1Frame(
                        rawFinal,
                        "run-1",
                        "attempt-1",
                        Audience.USER,
                        "evidence_turn",
                        Set.of("room_utterance"),
                        Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("persisted result reference");

        AgentStreamFrame oversized = new AgentStreamFrame(
                3,
                "visible_delta",
                "evidence_turn",
                "room_utterance",
                "x".repeat(4097),
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(
                        () ->
                                AgentNdjsonStreamClient.adaptV1Frame(
                                        oversized,
                                        "run-1",
                                        "attempt-1",
                                        Audience.USER,
                                        "evidence_turn",
                                        Set.of("room_utterance"),
                                        Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("visible delta");
    }

    private static com.example.dispute.workflow.contract.v1.AgentStreamEvent parse(
            AgentNdjsonStreamClient.V2ProtocolState state, String event) {
        return AgentNdjsonStreamClient.parseV2Line(MAPPER, event, state);
    }

    private static String event(long sequence, String type, String payload) {
        return "{\"schema_version\":\"agent-stream.v2\",\"run_id\":\"run-1\","
                + "\"attempt_id\":\"attempt-1\",\"sequence_no\":"
                + sequence
                + ",\"event_type\":\""
                + type
                + "\",\"audience\":\"USER\",\"occurred_at\":\"2026-07-19T00:00:00Z\",\"payload\":"
                + payload
                + "}";
    }

    private static String usage() {
        return "{\"usage\":{\"input_tokens\":1,\"output_tokens\":2,\"total_tokens\":3}}";
    }
}
