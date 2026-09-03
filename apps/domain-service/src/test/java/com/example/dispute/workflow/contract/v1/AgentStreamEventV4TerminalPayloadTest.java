package com.example.dispute.workflow.contract.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.AgentStreamEventV4.DeliveryClass;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4.EventType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentStreamEventV4TerminalPayloadTest {

    @Test
    void constructsTheMinimalFinalPayloadWithoutAResultReference() {
        var payload = AgentStreamEventV4.Payload.finalPayload(
                "IPFTR_1", "f".repeat(64));

        new AgentStreamEventV4(
                "agent-stream.v4",
                "RUN_1",
                "ATTEMPT_1",
                7,
                EventType.FINAL,
                Audience.USER,
                Instant.parse("2026-08-25T00:00:00Z"),
                payload);

        assertThat(payload.finalReceiptId()).isEqualTo("IPFTR_1");
        assertThat(payload.finalResultHash()).isEqualTo("f".repeat(64));
        assertThat(payload.deliveryClass()).isEqualTo(DeliveryClass.DURABLE_TERMINAL);
        assertThat(payload.resultSha256()).isNull();
    }

    @Test
    void constructsTheMinimalTerminalErrorPayload() {
        var payload = AgentStreamEventV4.Payload.errorPayload(
                "INTAKE_PARALLEL_FAILED", false);

        new AgentStreamEventV4(
                "agent-stream.v4",
                "RUN_1",
                "ATTEMPT_1",
                8,
                EventType.ERROR,
                Audience.USER,
                Instant.parse("2026-08-25T00:00:01Z"),
                payload);

        assertThat(payload.errorCode()).isEqualTo("INTAKE_PARALLEL_FAILED");
        assertThat(payload.retryable()).isFalse();
        assertThat(payload.deliveryClass()).isEqualTo(DeliveryClass.DURABLE_TERMINAL);
    }
}
