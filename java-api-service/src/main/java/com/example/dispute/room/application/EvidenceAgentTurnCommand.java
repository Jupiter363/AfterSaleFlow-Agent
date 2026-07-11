package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public record EvidenceAgentTurnCommand(
        @JsonProperty("context_envelope") EvidenceContextEnvelopeV1 contextEnvelope,
        @JsonProperty("agent_context") AgentInvocationContext agentContext) {

    public EvidenceAgentTurnCommand {
        Objects.requireNonNull(contextEnvelope, "contextEnvelope must not be null");
        Objects.requireNonNull(agentContext, "agentContext must not be null");
    }
}
