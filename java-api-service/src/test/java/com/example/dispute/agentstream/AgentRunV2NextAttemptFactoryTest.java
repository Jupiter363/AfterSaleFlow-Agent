package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Context;
import com.example.dispute.agentstream.application.AgentRunLedger.Attempt;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.RecoveryState;
import com.example.dispute.agentstream.application.AgentRunV2NextAttemptFactory;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRunV2NextAttemptFactoryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private static final Path FIXTURE = Path.of(
            "..",
            "contracts",
            "agent-platform",
            "v1",
            "fixtures",
            "valid",
            "room-graph-command-valid.json");
    private static final Instant NOW = Instant.parse("2026-07-17T08:01:00Z");

    private RoomGraphCommand command;
    private AgentRunCommandBindingFactory bindingFactory;
    private AgentRunV2NextAttemptFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURE.toFile());
        command = MAPPER.treeToValue(wrapper.required("instance"), RoomGraphCommand.class);
        bindingFactory = new AgentRunCommandBindingFactory(MAPPER);
        factory = new AgentRunV2NextAttemptFactory(MAPPER, bindingFactory);
    }

    @Test
    void deterministicallyRebindsIdentityAndNeverIncreasesResidualBudget() {
        RecoveryState state = recoveryState(command, canonicalAttempt(command));

        AttemptAllocation first = factory.next(state);
        AttemptAllocation replay = factory.next(state);

        assertThat(replay).isEqualTo(first);
        assertThat(first.attemptNo()).isEqualTo(2);
        assertThat(first.command().commandId()).isNotEqualTo(command.commandId());
        assertThat(first.command().attemptId()).isNotEqualTo(command.attemptId());
        assertThat(first.command().invocationContext().envelopeNonce())
                .isNotEqualTo(command.invocationContext().envelopeNonce());
        assertThat(first.command().logicalRunId()).isEqualTo(command.logicalRunId());
        assertThat(first.command().deadlineAt()).isEqualTo(command.deadlineAt());
        assertThat(first.command().retryBudget().providerAttemptsRemaining())
                .isEqualTo(1);
        assertThat(first.command().retryBudget().activityAttemptsRemaining())
                .isEqualTo(command.retryBudget().activityAttemptsRemaining());
        assertThat(first.command().retryBudget().repairsRemaining())
                .isEqualTo(command.retryBudget().repairsRemaining());
        assertThat(first.binding().logicalInputHash())
                .isEqualTo(state.logicalRun().logicalInputHash());
        assertThat(first.binding().commandRequestHash())
                .isEqualTo(first.command().requestHash());
    }

    @Test
    void rejectsTamperedCanonicalCommandBeforeDerivingAnotherAttempt() {
        Attempt persisted = canonicalAttempt(command);
        Attempt tampered = new Attempt(
                persisted.attemptId(),
                persisted.agentRunId(),
                persisted.attemptNo(),
                persisted.status(),
                persisted.publicOutputEmitted(),
                persisted.finalFrameObserved(),
                persisted.lastSequenceNo(),
                persisted.lastHeartbeatAt(),
                persisted.startedAt(),
                persisted.completedAt(),
                persisted.version(),
                persisted.lineageSchemaVersion(),
                persisted.commandId(),
                persisted.commandRequestHash(),
                persisted.logicalInputHash(),
                persisted.canonicalCommandJson()
                        .replace("graph-cmd-001", "graph-cmd-tampered"),
                persisted.previousAttemptId(),
                persisted.resetRequired(),
                persisted.publicSequenceOffset(),
                persisted.terminationCode(),
                persisted.errorCode(),
                persisted.durableFailureResult());

        assertThatThrownBy(() -> factory.next(recoveryState(command, tampered)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lineage");
    }

    @Test
    void decrementsTheLastProviderAttemptToZeroAndRejectsAnExhaustedPredecessor()
            throws Exception {
        RoomGraphCommand lastProviderAttempt = withProviderBudget(1);
        AttemptAllocation finalBudget = factory.next(recoveryState(
                lastProviderAttempt, canonicalAttempt(lastProviderAttempt)));

        assertThat(finalBudget.command().retryBudget().providerAttemptsRemaining())
                .isZero();

        RoomGraphCommand exhausted = withProviderBudget(0);
        assertThatThrownBy(() -> factory.next(recoveryState(exhausted, canonicalAttempt(exhausted))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no residual provider retry budget");
    }

    private RecoveryState recoveryState(RoomGraphCommand source, Attempt attempt) {
        var binding = binding(source);
        LogicalRun logical = new LogicalRun(
                source.logicalRunId(),
                source.caseId(),
                "logical-key-001",
                AgentRunProtocol.V2,
                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                "EPOCH_INTAKE_001",
                source.roomEpoch(),
                source.processRevision(),
                7,
                "PENDING",
                null,
                null,
                "agent-run-lineage.v1",
                binding.logicalInputHash(),
                3,
                source.deadlineAt(),
                1);
        return new RecoveryState(
                logical, attempt, "ROOM_INTAKE_001", "INTAKE", "logical-key-001");
    }

    private Attempt canonicalAttempt(RoomGraphCommand source) {
        var binding = binding(source);
        ExecuteAgentRunResult failure = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                source.logicalRunId(),
                source.logicalRunId(),
                source.attemptId(),
                1,
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                0,
                false,
                "MODEL_PROVIDER_UNAVAILABLE",
                true,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                NOW);
        return new Attempt(
                source.attemptId(),
                source.logicalRunId(),
                1,
                AgentRunAttemptStatus.FAILED,
                false,
                false,
                0,
                NOW,
                NOW,
                NOW,
                1,
                "agent-run-attempt-lineage.v1",
                source.commandId(),
                binding.commandRequestHash(),
                binding.logicalInputHash(),
                binding.canonicalCommandJson(),
                null,
                false,
                0,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT.name(),
                "MODEL_PROVIDER_UNAVAILABLE",
                failure);
    }

    private AgentRunCommandBindingFactory.Binding binding(RoomGraphCommand source) {
        return bindingFactory.bind(
                new Context(
                        "ROOM_INTAKE_001",
                        "EPOCH_INTAKE_001",
                        "INTAKE",
                        "logical-key-001"),
                source);
    }

    private RoomGraphCommand withProviderBudget(int remaining) throws Exception {
        ObjectNode body = MAPPER.valueToTree(command);
        ((ObjectNode) body.required("retry_budget"))
                .put("provider_attempts_remaining", remaining);
        body.remove("request_hash");
        body.put("request_hash", ContractJson.sha256Hex(body));
        return MAPPER.treeToValue(body, RoomGraphCommand.class);
    }
}
