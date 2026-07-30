package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Context;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.Attempt;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.RecoveryState;
import com.example.dispute.agentstream.application.AgentRunV2NextAttemptFactory;
import com.example.dispute.agentstream.application.AgentRunV2RecoveryService;
import com.example.dispute.agentstream.application.AgentRunV2RetryPreparation;
import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRunV2RecoveryServiceTest {

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

    private AgentRunLedger ledger;
    private AgentRunV2NextAttemptFactory factory;
    private AgentRunStreamEventService streamEventService;
    private AgentRunV2RetryPreparation preparation;
    private AgentRunCommandBindingFactory bindingFactory;
    private RoomGraphCommand firstCommand;
    private RoomGraphCommand secondCommand;
    private Attempt firstAttempt;
    private Attempt secondAttempt;
    private AttemptAllocation allocation;

    @BeforeEach
    void setUp() throws Exception {
        ledger = mock(AgentRunLedger.class);
        factory = mock(AgentRunV2NextAttemptFactory.class);
        streamEventService = mock(AgentRunStreamEventService.class);
        preparation = mock(AgentRunV2RetryPreparation.class);
        bindingFactory = new AgentRunCommandBindingFactory(MAPPER);
        JsonNode wrapper = MAPPER.readTree(FIXTURE.toFile());
        firstCommand = MAPPER.treeToValue(wrapper.required("instance"), RoomGraphCommand.class);
        secondCommand = secondCommand();
        firstAttempt = failedAttempt(
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT, true);
        secondAttempt = runningAttempt(secondCommand, 2, firstCommand.attemptId());
        allocation = new AttemptAllocation(2, secondCommand, binding(secondCommand));
        when(preparation.supports(any())).thenReturn(true);
        when(factory.verifiedCommand(any())).thenAnswer(invocation -> {
            RecoveryState state = invocation.getArgument(0);
            return state.latestAttempt().attemptNo() == 1 ? firstCommand : secondCommand;
        });
    }

    @Test
    void atomicallyPreparesPersistsAndAllocatesANextAttempt() {
        RecoveryState state = state("PENDING", firstAttempt);
        when(ledger.lockV2RecoveryState(firstCommand.logicalRunId()))
                .thenReturn(Optional.of(state));
        when(preparation.prepareNextAttempt(state, factory, NOW)).thenReturn(allocation);
        when(ledger.startNextAttempt(firstCommand.logicalRunId(), allocation, NOW))
                .thenReturn(secondAttempt);

        ExecuteAgentRunRequest request = service(List.of(preparation))
                .prepare(firstCommand.logicalRunId())
                .orElseThrow();

        assertThat(request.attemptNo()).isEqualTo(2);
        assertThat(request.attemptId()).isEqualTo(secondCommand.attemptId());
        assertThat(request.previousAttemptId()).isEqualTo(firstCommand.attemptId());
        verify(preparation).persistAllocatedRequest(state, request);
    }

    @Test
    void repeatedMultiJvmScanReplaysTheSameAllocatedAttemptWithoutReallocation() {
        RecoveryState pending = state("PENDING", firstAttempt);
        RecoveryState running = state("RUNNING", secondAttempt);
        when(ledger.lockV2RecoveryState(firstCommand.logicalRunId()))
                .thenReturn(Optional.of(pending), Optional.of(running));
        when(preparation.prepareNextAttempt(pending, factory, NOW)).thenReturn(allocation);
        when(ledger.startNextAttempt(firstCommand.logicalRunId(), allocation, NOW))
                .thenReturn(secondAttempt);
        when(factory.verifiedCommand(running)).thenReturn(secondCommand);
        AgentRunV2RecoveryService firstJvm = service(List.of(preparation));
        AgentRunV2RecoveryService secondJvm = service(List.of(preparation));

        ExecuteAgentRunRequest allocated =
                firstJvm.prepare(firstCommand.logicalRunId()).orElseThrow();
        ExecuteAgentRunRequest replayed =
                secondJvm.prepare(firstCommand.logicalRunId()).orElseThrow();

        assertThat(replayed).isEqualTo(allocated);
        verify(ledger, times(1))
                .startNextAttempt(firstCommand.logicalRunId(), allocation, NOW);
        verify(preparation).verifyAllocatedRequest(running, replayed);
    }

    @Test
    void replaysTheInitialRunningAttemptAcrossTheAllocationToStartCrashGap() {
        RecoveryState initialRunning = state(
                "RUNNING",
                runningAttempt(firstCommand, 1, null));
        when(ledger.lockV2RecoveryState(firstCommand.logicalRunId()))
                .thenReturn(Optional.of(initialRunning));

        ExecuteAgentRunRequest request = service(List.of(preparation))
                .prepare(firstCommand.logicalRunId())
                .orElseThrow();

        assertThat(request.attemptNo()).isEqualTo(1);
        assertThat(request.previousAttemptId()).isNull();
        verify(ledger, never()).startNextAttempt(any(), any(), any());
        verify(preparation).verifyAllocatedRequest(initialRunning, request);
    }

    @Test
    void refusesAllocationWhenNoLanePreparerOwnsTheCandidate() {
        RecoveryState state = state("PENDING", firstAttempt);
        when(ledger.lockV2RecoveryState(firstCommand.logicalRunId()))
                .thenReturn(Optional.of(state));

        AgentRunV2RecoveryService unconfigured = service(List.of());

        assertThat(unconfigured.isRecoveryConfigured()).isFalse();
        assertThat(unconfigured.prepare(firstCommand.logicalRunId())).isEmpty();

        verify(ledger, never()).startNextAttempt(any(), any(), any());
        verify(ledger)
                .terminalizeV2RecoveryCandidate(
                        firstCommand.logicalRunId(),
                        firstCommand.attemptId(),
                        1,
                        "AGENT_RUN_RECOVERY_PREPARER_MISSING",
                        NOW);
    }

    @Test
    void terminalReceiptPrioritySuppressesRecoveryBeforeAnyAllocationDecision() {
        when(ledger.lockV2RecoveryState(firstCommand.logicalRunId()))
                .thenReturn(Optional.empty());

        assertThat(service(List.of(preparation)).prepare(firstCommand.logicalRunId()))
                .isEmpty();

        verifyNoInteractions(factory, preparation);
        verify(ledger, never()).startNextAttempt(any(), any(), any());
    }

    @Test
    void rejectsAFailedPredecessorWithoutDurableNextAttemptAuthorization() {
        Attempt unauthorized = failedAttempt(AgentRunRecoveryAction.FAIL_LOGICAL_RUN, false);
        RecoveryState state = state("PENDING", unauthorized);
        when(ledger.lockV2RecoveryState(firstCommand.logicalRunId()))
                .thenReturn(Optional.of(state));

        assertThat(service(List.of(preparation)).prepare(firstCommand.logicalRunId()))
                .isEmpty();

        verify(ledger, never()).startNextAttempt(any(), any(), any());
        verify(ledger)
                .terminalizeV2RecoveryCandidate(
                        firstCommand.logicalRunId(),
                        firstCommand.attemptId(),
                        1,
                        "AGENT_RUN_RECOVERY_AUTHORIZATION_INVALID",
                        NOW);
    }

    @Test
    void terminalizesAnExpiredCandidateInsteadOfLeavingItInTheTopTwenty() {
        RecoveryState expired = state("PENDING", firstAttempt, 3, NOW);
        when(ledger.lockV2RecoveryState(firstCommand.logicalRunId()))
                .thenReturn(Optional.of(expired));

        assertThat(service(List.of(preparation)).prepare(firstCommand.logicalRunId()))
                .isEmpty();

        verify(ledger)
                .terminalizeV2RecoveryCandidate(
                        firstCommand.logicalRunId(),
                        firstCommand.attemptId(),
                        1,
                        "AGENT_RUN_RECOVERY_DEADLINE_EXCEEDED",
                        NOW);
        verify(streamEventService).wakeUpAfterCommit(
                firstCommand.logicalRunId(),
                firstCommand.attemptId(),
                firstAttempt.lastSequenceNo() + 1L);
    }

    @Test
    void terminalizesAnExhaustedLogicalAttemptLimit() {
        RecoveryState exhausted = state(
                "PENDING", firstAttempt, 1, firstCommand.deadlineAt());
        when(ledger.lockV2RecoveryState(firstCommand.logicalRunId()))
                .thenReturn(Optional.of(exhausted));

        assertThat(service(List.of(preparation)).prepare(firstCommand.logicalRunId()))
                .isEmpty();

        verify(ledger)
                .terminalizeV2RecoveryCandidate(
                        firstCommand.logicalRunId(),
                        firstCommand.attemptId(),
                        1,
                        "AGENT_RUN_RECOVERY_ATTEMPT_LIMIT_EXHAUSTED",
                        NOW);
    }

    @Test
    void terminalizesAnExhaustedProviderBudget() throws Exception {
        RoomGraphCommand exhaustedCommand = withProviderBudget(0);
        Attempt exhaustedAttempt = failedAttempt(
                exhaustedCommand,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                true);
        RecoveryState exhausted = state("PENDING", exhaustedAttempt);
        when(ledger.lockV2RecoveryState(firstCommand.logicalRunId()))
                .thenReturn(Optional.of(exhausted));
        when(factory.verifiedCommand(exhausted)).thenReturn(exhaustedCommand);

        assertThat(service(List.of(preparation)).prepare(firstCommand.logicalRunId()))
                .isEmpty();

        verify(ledger)
                .terminalizeV2RecoveryCandidate(
                        firstCommand.logicalRunId(),
                        firstCommand.attemptId(),
                        1,
                        "AGENT_RUN_RECOVERY_PROVIDER_BUDGET_EXHAUSTED",
                        NOW);
    }

    private AgentRunV2RecoveryService service(List<AgentRunV2RetryPreparation> preparations) {
        return new AgentRunV2RecoveryService(
                ledger,
                factory,
                streamEventService,
                preparations,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RecoveryState state(String status, Attempt attempt) {
        return state(status, attempt, 3, firstCommand.deadlineAt());
    }

    private RecoveryState state(
            String status, Attempt attempt, int attemptLimit, Instant deadlineAt) {
        var firstBinding = binding(firstCommand);
        LogicalRun logical = new LogicalRun(
                firstCommand.logicalRunId(),
                firstCommand.caseId(),
                "logical-key-001",
                AgentRunProtocol.V2,
                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                "EPOCH_INTAKE_001",
                firstCommand.roomEpoch(),
                firstCommand.processRevision(),
                7,
                status,
                null,
                null,
                "agent-run-lineage.v1",
                firstBinding.logicalInputHash(),
                attemptLimit,
                deadlineAt,
                1);
        return new RecoveryState(
                logical, attempt, "ROOM_INTAKE_001", "INTAKE", "logical-key-001");
    }

    private Attempt failedAttempt(AgentRunRecoveryAction action, boolean retryable) {
        return failedAttempt(firstCommand, action, retryable);
    }

    private Attempt failedAttempt(
            RoomGraphCommand failedCommand,
            AgentRunRecoveryAction action,
            boolean retryable) {
        var binding = binding(failedCommand);
        ExecuteAgentRunResult result = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                failedCommand.logicalRunId(),
                failedCommand.logicalRunId(),
                failedCommand.attemptId(),
                1,
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                0,
                false,
                "MODEL_PROVIDER_UNAVAILABLE",
                retryable,
                action,
                NOW);
        return new Attempt(
                failedCommand.attemptId(),
                failedCommand.logicalRunId(),
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
                failedCommand.commandId(),
                binding.commandRequestHash(),
                binding.logicalInputHash(),
                binding.canonicalCommandJson(),
                null,
                false,
                0,
                action.name(),
                "MODEL_PROVIDER_UNAVAILABLE",
                result);
    }

    private Attempt runningAttempt(
            RoomGraphCommand command, long attemptNo, String previousAttemptId) {
        var binding = binding(command);
        return new Attempt(
                command.attemptId(),
                command.logicalRunId(),
                attemptNo,
                AgentRunAttemptStatus.RUNNING,
                false,
                false,
                0,
                NOW,
                NOW,
                null,
                1,
                "agent-run-attempt-lineage.v1",
                command.commandId(),
                binding.commandRequestHash(),
                binding.logicalInputHash(),
                binding.canonicalCommandJson(),
                previousAttemptId,
                false,
                0,
                null);
    }

    private AgentRunCommandBindingFactory.Binding binding(RoomGraphCommand command) {
        return bindingFactory.bind(
                new Context(
                        "ROOM_INTAKE_001",
                        "EPOCH_INTAKE_001",
                        "INTAKE",
                        "logical-key-001"),
                command);
    }

    private RoomGraphCommand secondCommand() throws Exception {
        ObjectNode body = MAPPER.valueToTree(firstCommand);
        body.put("command_id", "graph-cmd-002");
        body.put("attempt_id", "attempt-002");
        ((ObjectNode) body.required("invocation_context"))
                .put("envelope_nonce", "nonce-002");
        ((ObjectNode) body.required("retry_budget"))
                .put("provider_attempts_remaining", 1);
        body.remove("request_hash");
        body.put("request_hash", ContractJson.sha256Hex(body));
        return MAPPER.treeToValue(body, RoomGraphCommand.class);
    }

    private RoomGraphCommand withProviderBudget(int remaining) throws Exception {
        ObjectNode body = MAPPER.valueToTree(firstCommand);
        ((ObjectNode) body.required("retry_budget"))
                .put("provider_attempts_remaining", remaining);
        body.remove("request_hash");
        body.put("request_hash", ContractJson.sha256Hex(body));
        return MAPPER.treeToValue(body, RoomGraphCommand.class);
    }
}
