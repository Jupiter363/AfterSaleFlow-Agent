package com.example.dispute.workflow.targete2e.ingress.materialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.config.ActorRole;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetIntakeRuntimePinsTest {

    @Test
    void suppliesTheExplicitTargetRegistrationContract() {
        TargetIntakeRuntimePins pins = new TargetIntakeRuntimePins(
                "case-build", "agent-build", "a".repeat(64), "graph-code-build", "b".repeat(64),
                "agent-profile", "prompt", "model", "litellm", "policy", "guardrail", "tool-policy", "memory",
                "envelope-key");

        var registration = pins.registrationPins();

        assertThat(registration.graphKey()).isEqualTo(TargetTypedRoomProtocol.GRAPH_KEY);
        assertThat(registration.graphVersion()).isEqualTo("target-e2e-graph.2026-08-18.3");
        assertThat(registration.checkpointSchemaVersion())
                .isEqualTo(TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION);
        assertThat(registration.stateSchemaVersion()).isEqualTo("intake-graph-state.v2");
        assertThat(registration.outputSchemaVersion())
                .isEqualTo("target-e2e-room-proposal-source.v2");
        assertThat(pins.agentProfileId()).isEqualTo("agent-profile");
    }

    @Test
    void signsTheExactBaselinePromptProfileForEachTargetIntakeParty() {
        TargetIntakeRuntimePins pins = pins();

        var user = issue(pins, ActorRole.USER);
        var merchant = issue(pins, ActorRole.MERCHANT);

        assertThat(user.registration().promptVersion())
                .isEqualTo("DISPUTE_INTAKE_OFFICER:USER:v1");
        assertThat(merchant.registration().promptVersion())
                .isEqualTo("DISPUTE_INTAKE_OFFICER:MERCHANT:v1");
        assertThat(user.registration().modelProfileId()).isEqualTo("model");
        assertThat(merchant.registration().modelProfileId()).isEqualTo("model");
        assertThatCode(user.registration()::requireCanonicalHash).doesNotThrowAnyException();
        assertThatCode(merchant.registration()::requireCanonicalHash).doesNotThrowAnyException();
    }

    @Test
    void failsClosedForAnActorWithoutABaselineIntakePromptProfile() {
        TargetIntakeRuntimePins pins = pins();

        assertThatThrownBy(() -> pins.registrationPins(ActorRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("target Intake Prompt profile is defined only for USER or MERCHANT actors");
    }

    private static TargetIntakeRuntimePins pins() {
        return new TargetIntakeRuntimePins(
                "case-build", "agent-build", "a".repeat(64), "graph-code-build", "b".repeat(64),
                "agent-profile", "prompt", "model", "litellm", "policy", "guardrail", "tool-policy", "memory",
                "envelope-key");
    }

    private static com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding issue(
            TargetIntakeRuntimePins pins, ActorRole actorRole) {
        var contractRole =
                com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.valueOf(actorRole.name());
        var audience = actorRole == ActorRole.USER ? Audience.USER : Audience.MERCHANT;
        var actorScope = new IntakePrivateThreadRegistration.ActorScope(
                "actor-" + actorRole.name(), contractRole, audience,
                List.of("case:CASE_TARGET_001:command:INTAKE_MESSAGE"));
        var request = new IntakePrivateThreadRegistrationFactory.IssueRequest(
                "target-intake-registration-" + actorRole.name(),
                "tenant-target", "CASE_TARGET_001", 0, 1, actorScope,
                "session-" + actorRole.name(), pins.registrationPins(actorRole), WriterMode.TEMPORAL,
                Instant.parse("2026-08-02T00:00:00Z"));
        return new IntakePrivateThreadRegistrationFactory(
                () -> "grt.v1.0123456789abcdef0123456789abcdef")
                .issue(request);
    }
}
