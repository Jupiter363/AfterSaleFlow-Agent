package com.example.dispute.workflow.contract.v1;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.dispute.workflow.contract.v1.AgentStreamEventV4.EventType;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4.FrameType;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4.Payload;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4.ValueKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentStreamEventV4FramePayloadTest {

    @Test
    void frameFactoriesSatisfyTheirExactPublicEventContracts() {
        assertValid(
                EventType.PUBLIC_FRAME_START,
                Payload.frameStartPayload(
                        "FRAME_1", FrameType.DIALOGUE_FRAME, 1, "RECEIPT_1", "registry.v1"));
        assertValid(
                EventType.PUBLIC_FRAME_PROJECTION_ITEM,
                Payload.projectionItemPayload(
                        "FRAME_1",
                        FrameType.DIALOGUE_FRAME,
                        1,
                        0,
                        1,
                        "ITEM_1",
                        "PUBLIC_TEXT",
                        "room.utterance",
                        ValueKind.TEXT,
                        null,
                        "公开文本",
                        "a".repeat(64)));
        assertValid(
                EventType.PUBLIC_FRAME_INTERRUPTED,
                Payload.interruptedPayload(
                        "FRAME_1",
                        FrameType.DIALOGUE_FRAME,
                        1,
                        1,
                        "OUTPUT_SCHEMA_INVALID",
                        true));
        assertValid(
                EventType.FRAME_GENERATION_RESET,
                Payload.generationResetPayload(
                        "FRAME_1",
                        "FRAME_2",
                        FrameType.DIALOGUE_FRAME,
                        1,
                        2,
                        "OUTPUT_SCHEMA_INVALID"));
        assertValid(
                EventType.USAGE,
                Payload.usagePayload(
                        FrameType.DIALOGUE_FRAME, 2, new Usage(10, 5, 15)));
    }

    private static void assertValid(EventType eventType, Payload payload) {
        assertThatCode(() -> new AgentStreamEventV4(
                        "agent-stream.v4",
                        "RUN_1",
                        "ATTEMPT_1",
                        0,
                        eventType,
                        Audience.USER,
                        Instant.parse("2026-08-25T01:00:00Z"),
                        payload))
                .doesNotThrowAnyException();
    }
}
