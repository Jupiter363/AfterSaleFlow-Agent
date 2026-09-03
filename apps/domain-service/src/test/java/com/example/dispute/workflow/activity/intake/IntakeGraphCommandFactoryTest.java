package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingConflictException;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory.CommandRequest;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IntakeGraphCommandFactoryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void buildsAnExactProposalOnlyReferenceCommandWithCanonicalHash() {
        var binding = IntakeTestFixtures.binding();
        var request = request(
                binding,
                IntakeTestFixtures.snapshot(binding),
                IntakeTestFixtures.event(binding));

        var command = new IntakeGraphCommandFactory().create(request);
        var encoded = MAPPER.valueToTree(command);

        assertThat(command.graphKey()).isEqualTo("intake.v2");
        assertThat(command.graphVersion()).isEqualTo("2.0.0");
        assertThat(command.checkpointSchemaVersion()).isEqualTo("intake-checkpoint.v2");
        assertThat(command.domainSnapshotRef().schemaVersion())
                .isEqualTo("intake-domain-snapshot.v2");
        assertThat(command.eventRef().schemaVersion()).isEqualTo("intake-turn-event.v2");
        assertThat(command.invocationContext().toolCapabilities()).isEmpty();
        assertThat(command.requestHash()).isEqualTo(IntakeContractHashes.graphCommandHash(command));
        assertThat(encoded.has("agent_session_id")).isFalse();
        assertThat(encoded.toString())
                .doesNotContain("AGENT_SESSION_P4_USER_1")
                .doesNotContain("memory_frame")
                .doesNotContain("execute_tool")
                .doesNotContain("open_evidence");
    }

    @Test
    void firstCommandUsesTheSingleSnapshotAndNoEventReference() {
        var binding = IntakeTestFixtures.binding();
        var command = new IntakeGraphCommandFactory()
                .create(request(binding, IntakeTestFixtures.snapshot(binding), null));

        assertThat(command.domainSnapshotRef()).isNotNull();
        assertThat(command.eventRef()).isNull();
        assertThat(MAPPER.valueToTree(command).has("event_ref")).isFalse();
    }

    @Test
    void rejectsCrossScopeEventReferences() {
        var binding = IntakeTestFixtures.binding();
        var valid = IntakeTestFixtures.event(binding);
        var crossScope = new IntakeEventReference(
                valid.bindingId(),
                valid.threadRegistrationId(),
                valid.eventId(),
                valid.messageId(),
                valid.tenantSurrogate(),
                valid.caseId(),
                valid.roomEpoch(),
                valid.fencingToken(),
                valid.threadId(),
                "a".repeat(64),
                valid.agentSessionId(),
                valid.payloadRef(),
                valid.objectVersion(),
                valid.sequenceNo(),
                valid.domainRevision(),
                Audience.USER,
                valid.occurredAt(),
                valid.createdAt());

        assertThatThrownBy(
                        () ->
                                new IntakeGraphCommandFactory()
                                        .create(
                                                request(
                                                        binding,
                                                        IntakeTestFixtures.snapshot(binding),
                                                        crossScope)))
                .isInstanceOf(IntakeGraphBindingConflictException.class)
                .hasMessageContaining("event");
    }

    private static CommandRequest request(
            com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding binding,
            com.example.dispute.workflow.application.intake.IntakeSnapshotReference snapshot,
            IntakeEventReference event) {
        return new CommandRequest(
                "COMMAND_P4_USER_2",
                "RUN_P4_USER_2",
                "ATTEMPT_P4_USER_2_1",
                binding,
                snapshot,
                event,
                5,
                "INTAKE_ACTIVE",
                2,
                "intake-agent.v2",
                2,
                3,
                1,
                Instant.parse("2026-07-20T08:03:00Z"),
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "graph-envelope.synthetic.v1",
                "nonce-p4-user-2");
    }
}
