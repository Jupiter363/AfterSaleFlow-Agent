package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.RecoveryState;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Binding;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Context;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationFailureRecorder;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaAgentRunLedger implements AgentRunLedger {

    private final AgentRunRepository runRepository;
    private final AgentRunAttemptRepository attemptRepository;
    private final AgentRunStreamEventRepository eventRepository;
    private final PostgresAgentRunV2EventStore recoveryEventStore;
    private final PostgresAgentRunV4EventWriter v4EventWriter;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final ObjectMapper streamObjectMapper;
    private final AgentRunCommandBindingFactory commandBindingFactory;

    public JpaAgentRunLedger(
            AgentRunRepository runRepository,
            AgentRunAttemptRepository attemptRepository,
            AgentRunStreamEventRepository eventRepository,
            PostgresAgentRunV2EventStore recoveryEventStore,
            PostgresAgentRunV4EventWriter v4EventWriter,
            EntityManager entityManager,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.recoveryEventStore = recoveryEventStore;
        this.v4EventWriter = v4EventWriter;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.streamObjectMapper = objectMapper.copy();
        this.streamObjectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.streamObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.commandBindingFactory = new AgentRunCommandBindingFactory(objectMapper);
    }

    @Override
    @Transactional
    public LogicalRun createOrLoad(CreateLogicalRun command) {
        if (command.protocol() != AgentRunProtocol.V3
                && command.protocol() != AgentRunProtocol.V4) {
            throw new IllegalArgumentException(
                    "logical AgentRun creation accepts only protocol V3 or V4");
        }
        if (command.executorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY) {
            throw new IllegalArgumentException(
                    "versioned AgentRun requires the Temporal Activity executor");
        }
        lockLogicalKey(command.caseId(), command.logicalIdempotencyKey());
        Optional<AgentRunEntity> existing =
                runRepository.findByCaseIdAndLogicalIdempotencyKey(
                        command.caseId(), command.logicalIdempotencyKey());
        if (existing.isPresent()) {
            existing.orElseThrow().requireSameLogicalCommand(command);
            return logical(existing.orElseThrow());
        }
        if (runRepository.existsById(command.agentRunId())) {
            throw new IllegalStateException("agentRunId is already bound to another logical run");
        }
        AgentRunEntity created = runRepository.saveAndFlush(
                command.protocol() == AgentRunProtocol.V4
                        ? AgentRunEntity.logicalV4(command)
                        : AgentRunEntity.logicalV3(command));
        return logical(created);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LogicalRun> findByLogicalKey(
            String caseId, String logicalIdempotencyKey) {
        return runRepository
                .findByCaseIdAndLogicalIdempotencyKey(caseId, logicalIdempotencyKey)
                .map(this::logical);
    }

    @Override
    @Transactional
    public Optional<RecoveryState> lockV2RecoveryState(String agentRunId) {
        AgentRunEntity run = runRepository.findByIdForUpdate(agentRunId).orElse(null);
        if (run == null) {
            return Optional.empty();
        }
        if (!AgentRunProtocol.V3.wireValue().equals(run.getProtocol())
                || run.getExecutorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY) {
            throw new IllegalStateException("recovery candidate is not a Temporal AgentRun V3");
        }
        if (!"PENDING".equals(run.getRunStatus()) && !"RUNNING".equals(run.getRunStatus())) {
            return Optional.empty();
        }
        if ("COMMITTED".equals(run.getFinalizationStatus())
                || run.getResultReadyAttemptId() != null
                || run.getCommittedAttemptId() != null
                || run.getFinalResultHash() != null) {
            return Optional.empty();
        }
        if (!"UNCOMMITTED".equals(run.getFinalizationStatus())) {
            throw new IllegalStateException("recovery candidate has an unknown finalization state");
        }

        long persistedCount = attemptRepository.countByAgentRunId(agentRunId);
        long latestAttemptNo = attemptRepository.findMaxAttemptNoByAgentRunId(agentRunId);
        if (latestAttemptNo < 1 || persistedCount != latestAttemptNo) {
            throw new IllegalStateException(
                    "persisted attempts are not a contiguous sequence from one");
        }
        AgentRunAttemptEntity latest = attemptRepository
                .findByAgentRunIdAndAttemptNoForUpdate(agentRunId, latestAttemptNo)
                .orElseThrow(() -> new IllegalStateException("latest AgentRun attempt was not found"));
        if (latest.getAttemptStatus() == AgentRunAttemptStatus.RESULT_READY
                || latest.getAttemptStatus() == AgentRunAttemptStatus.COMPLETED) {
            return Optional.empty();
        }
        return Optional.of(new RecoveryState(
                logical(run),
                attempt(latest),
                run.getRoomId(),
                run.getStreamOperation(),
                run.getLogicalIdempotencyKey()));
    }

    @Override
    @Transactional
    public void terminalizeV2RecoveryCandidate(
            String agentRunId,
            String attemptId,
            long attemptNo,
            String errorCode,
            Instant completedAt) {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        Instant terminalAt = java.util.Objects.requireNonNull(completedAt, "completedAt")
                .truncatedTo(ChronoUnit.MICROS);
        AgentRunEntity run = lockRun(agentRunId);
        if (!AgentRunProtocol.V3.wireValue().equals(run.getProtocol())
                || run.getExecutorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY) {
            throw new IllegalStateException("recovery candidate is not a Temporal AgentRun V3");
        }
        long latestAttemptNo = attemptRepository.findMaxAttemptNoByAgentRunId(agentRunId);
        requireEqual(latestAttemptNo, attemptNo, "latestAttemptNo");
        AgentRunAttemptEntity attempt = attemptRepository
                .findByAgentRunIdAndAttemptNoForUpdate(agentRunId, attemptNo)
                .orElseThrow(() -> new IllegalStateException("latest AgentRun attempt was not found"));
        requireEqual(attempt.getId(), attemptId, "attemptId");
        if ("FAILED".equals(run.getRunStatus())) {
            requireV3RecoveryTerminalProjection(run, attempt, errorCode, terminalAt);
            return;
        }
        if (!"PENDING".equals(run.getRunStatus()) && !"RUNNING".equals(run.getRunStatus())) {
            throw new IllegalStateException("recovery candidate has a conflicting terminal state");
        }
        if (!"UNCOMMITTED".equals(run.getFinalizationStatus())
                || run.getResultReadyAttemptId() != null
                || run.getCommittedAttemptId() != null
                || run.getFinalResultHash() != null
                || run.getCommittedManifestId() != null
                || run.getCommittedManifestHash() != null
                || run.getFinalStreamSequenceNo() != null
                || run.getFinalizedAt() != null) {
            throw new IllegalStateException(
                    "recovery candidate conflicts with result or commit authority");
        }
        if (attempt.getAttemptStatus() == AgentRunAttemptStatus.RESULT_READY
                || attempt.getAttemptStatus() == AgentRunAttemptStatus.COMPLETED) {
            throw new IllegalStateException("recovery candidate attempt already produced a result");
        }
        AgentRunAttemptStatus recoveryAttemptStatus = attempt.getAttemptStatus();
        RecoveryTerminalPosition terminalPosition = requireRecoveryTerminalPosition(
                run, attempt, recoveryAttemptStatus);
        if (recoveryAttemptStatus == AgentRunAttemptStatus.RUNNING) {
            attempt.recordFailure(
                    AgentRunAttemptStatus.FAILED,
                    errorCode,
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                    terminalAt);
        }
        run.markFailed(
                errorCode,
                AgentRunEntity.V3_LOGICAL_FAILURE_MESSAGE,
                false,
                null);
        // markFailed owns the public GET error projection; the V3 transition restores the
        // authority-supplied terminal timestamp instead of retaining its wall-clock timestamp.
        run.markV3AttemptFailed(AgentRunAttemptStatus.FAILED, false, terminalAt);
        attempt.advanceRecoveryTerminalErrorSequence(terminalPosition.sequenceNo());
        persistRecoveryTerminalError(
                run, attempt, errorCode, terminalAt, terminalPosition);
        requireV3RecoveryTerminalProjection(run, attempt, errorCode, terminalAt);
    }

    @Override
    @Transactional
    public Attempt startNextAttempt(
            String agentRunId, AttemptAllocation allocation, Instant startedAt) {
        if (allocation == null) {
            throw new IllegalArgumentException("allocation must not be null");
        }
        Instant persistedStartedAt = java.util.Objects.requireNonNull(
                        startedAt, "startedAt must not be null")
                .truncatedTo(ChronoUnit.MICROS);
        RoomGraphCommand command = allocation.command();
        requireEqual(command.logicalRunId(), agentRunId, "logicalRunId");
        AgentRunEntity run = lockRun(agentRunId);
        run.requireAttemptCommand(command);
        AgentRunProtocol runProtocol = supportedExecutionProtocol(run);
        if (runProtocol == AgentRunProtocol.V4
                && !ExecuteAgentRunRequest.isParallelIntakeCommand(command)) {
            throw new IllegalStateException(
                    "agent-stream.v4 requires an exact parallel Intake command");
        }
        Binding binding = requireVerifiedBinding(run, command, allocation.binding());
        AttemptAllocation verified =
                new AttemptAllocation(allocation.attemptNo(), command, binding);
        String actorRole = command.actorScope().actorRole().name();
        String audienceJson = json(List.of(command.actorScope().audience().name()));
        String actorIdsJson = json(List.of(command.actorScope().actorId()));
        if (runProtocol == AgentRunProtocol.V4) {
            run.bindV4Audience(actorRole, audienceJson, actorIdsJson);
        } else {
            run.bindV3Audience(actorRole, audienceJson, actorIdsJson);
        }

        Optional<AgentRunAttemptEntity> replay =
                attemptRepository.findByAgentRunIdAndAttemptNo(
                        agentRunId, allocation.attemptNo());
        if (replay.isPresent()) {
            run.requireBoundLineage(binding.logicalInputHash());
            AgentRunAttemptEntity persisted = replay.orElseThrow();
            persisted.requireSameAllocation(verified);
            requireCanonicalCommand(persisted, binding);
            requirePersistedPredecessor(persisted, binding.logicalInputHash());
            requireProtocolAdmission(runProtocol, persisted, command);
            return attempt(persisted);
        }
        attemptRepository
                .findById(command.attemptId())
                .ifPresent(
                        ignored -> {
                            throw new IllegalStateException(
                                    "attemptId is already bound to another attempt");
                        });
        attemptRepository
                .findByAgentRunIdAndCommandId(agentRunId, command.commandId())
                .ifPresent(
                        ignored -> {
                            throw new IllegalStateException(
                                    "commandId is already bound to another attempt");
                        });

        long persistedCount = attemptRepository.countByAgentRunId(agentRunId);
        long maxAttemptNo = attemptRepository.findMaxAttemptNoByAgentRunId(agentRunId);
        if (persistedCount != maxAttemptNo) {
            throw new IllegalStateException(
                    "persisted attempts are not a contiguous sequence from one");
        }
        long nextAttemptNo = maxAttemptNo + 1;
        if (allocation.attemptNo() != nextAttemptNo) {
            throw new IllegalStateException(
                    "attemptNo must allocate the next value while the logical run is locked");
        }
        if (nextAttemptNo > run.getAttemptLimit()) {
            throw new IllegalStateException("logical AgentRun attempt limit is exhausted");
        }
        if (runProtocol == AgentRunProtocol.V4 && nextAttemptNo != 1) {
            throw new IllegalStateException(
                    "parallel Intake owns retries inside Frame generations, not outer attempts");
        }

        String previousAttemptId = null;
        boolean resetRequired = false;
        int publicSequenceOffset = 0;
        String resetReasonCode = null;
        if (nextAttemptNo == 1) {
            requireEqual(run.getRequestHash(), binding.commandRequestHash(), "commandRequestHash");
            run.bindFirstAttemptLineage(binding.logicalInputHash());
        } else {
            run.requireBoundLineage(binding.logicalInputHash());
            AgentRunAttemptEntity predecessor =
                    attemptRepository
                            .findByAgentRunIdAndAttemptNo(agentRunId, nextAttemptNo - 1)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "immediately preceding attempt was not found"));
            predecessor.requireCanPrecede(nextAttemptNo, binding.logicalInputHash());
            requireResidualBudgetDoesNotIncrease(
                    predecessor.getCommandJson(), binding.canonicalCommandJson());
            previousAttemptId = predecessor.getId();
            resetRequired = hasDurableVisibleOutput(agentRunId, predecessor.getId());
            publicSequenceOffset = resetRequired ? 1 : 0;
            resetReasonCode = predecessor.getTerminationCode();
        }

        AgentRunAttemptEntity created;
        if (runProtocol == AgentRunProtocol.V4) {
            created = AgentRunAttemptEntity.startV4(agentRunId, verified, persistedStartedAt);
            run.markV4AttemptStarted();
        } else {
            created = AgentRunAttemptEntity.start(
                    agentRunId,
                    verified,
                    previousAttemptId,
                    resetRequired,
                    publicSequenceOffset,
                    persistedStartedAt);
            run.markV3AttemptStarted();
        }
        attemptRepository.saveAndFlush(created);
        if (runProtocol == AgentRunProtocol.V3) {
            persistPublicPrelude(created, command, resetReasonCode);
        }
        requireProtocolAdmission(runProtocol, created, command);
        return attempt(created);
    }

    @Override
    @Transactional(readOnly = true)
    public Attempt requireAllocatedAttempt(ExecuteAgentRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        AgentRunEntity run =
                runRepository
                        .findById(request.agentRunId())
                        .orElseThrow(
                                () -> new IllegalStateException("logical AgentRun was not found"));
        run.requireAttemptRequest(request);
        Binding binding = verifiedBinding(run, request.command());
        requireEqual(
                binding.logicalInputHash(), request.logicalInputHash(), "logicalInputHash");
        AgentRunAttemptEntity persisted =
                attemptRepository
                        .findById(request.attemptId())
                        .orElseThrow(
                                () -> new IllegalStateException("AgentRun attempt was not found"));
        persisted.requireAllocatedRequest(request);
        requireCanonicalCommand(persisted, binding);
        requirePersistedPredecessor(persisted, binding.logicalInputHash());
        requireProtocolAdmission(
                AgentRunProtocol.V4.wireValue().equals(request.streamProtocol())
                        ? AgentRunProtocol.V4
                        : AgentRunProtocol.V3,
                persisted,
                request.command());
        return attempt(persisted);
    }

    @Override
    @Transactional
    public void recordHeartbeat(AgentRunAttemptHeartbeat heartbeat) {
        AgentRunAttemptEntity attempt =
                attemptRepository
                        .findByIdForUpdate(heartbeat.attemptId())
                        .orElseThrow(() -> new IllegalStateException("AgentRun attempt was not found"));
        attempt.recordHeartbeat(heartbeat);
    }

    @Override
    @Transactional
    public void recordResultReady(ExecuteAgentRunResult result) {
        AgentRunEntity run = lockRun(result.agentRunId());
        AgentRunAttemptEntity attempt =
                attemptRepository
                        .findByIdForUpdate(result.attemptId())
                        .orElseThrow(() -> new IllegalStateException("AgentRun attempt was not found"));
        if (AgentRunProtocol.V4.wireValue().equals(run.getProtocol())) {
            throw new IllegalStateException(
                    "agent-stream.v4 RESULT_READY is owned by the atomic parallel terminal store");
        }
        attempt.recordResultReady(result, json(result));
        if ("COMMITTED".equals(run.getFinalizationStatus())) {
            requireEqual(run.getCommittedAttemptId(), result.attemptId(), "committedAttemptId");
            requireEqual(run.getFinalResultHash(), result.resultHash(), "finalResultHash");
            return;
        }
        run.markV3ResultReady(result.attemptId(), result.resultHash(), result.completedAt());
    }

    @Override
    @Transactional
    public AgentRunFinalizationFailureRecorder.Receipt recordFinalizationFailure(
            AgentRunFinalizationFailureRecorder.Command command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        AgentRunEntity run = lockRun(command.agentRunId());
        AgentRunAttemptEntity attempt = attemptRepository
                .findByIdForUpdate(command.attemptId())
                .orElseThrow(() -> new IllegalStateException("AgentRun attempt was not found"));
        requireEqual(run.getId(), command.logicalRunId(), "logicalRunId");
        requireEqual(attempt.getAgentRunId(), command.agentRunId(), "agentRunId");
        requireEqual(attempt.getAttemptNo(), command.attemptNo(), "attemptNo");
        AgentRunAttemptStatus terminalStatus = command.publicOutputEmitted()
                ? AgentRunAttemptStatus.ABORTED
                : AgentRunAttemptStatus.FAILED;
        boolean attemptReplayed = attempt.recordFinalizationFailure(
                command.agentRunId(),
                command.attemptNo(),
                command.commandId(),
                command.commandRequestHash(),
                command.resultHash(),
                command.finalSequenceNo(),
                command.publicOutputEmitted(),
                terminalStatus,
                command.safeErrorCode());
        boolean runReplayed = run.recordV3FinalizationFailure(
                command.attemptId(),
                command.resultHash(),
                terminalStatus,
                command.safeErrorCode(),
                attempt.getCompletedAt().toInstant());
        if (attemptReplayed != runReplayed) {
            throw new IllegalStateException(
                    "finalization failure replay state is not atomic");
        }

        entityManager.flush();
        long terminalSequenceNo = Math.addExact(command.finalSequenceNo(), 1L);
        AgentStreamEvent terminalError = new AgentStreamEvent(
                "agent-stream.v3",
                command.agentRunId(),
                command.attemptId(),
                terminalSequenceNo,
                StreamEventType.ERROR,
                requireV3Audience(run),
                attempt.getCompletedAt().toInstant(),
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        command.safeErrorCode(),
                        false));
        AgentRunV2StreamStore.AppendReceipt eventReceipt =
                recoveryEventStore.appendFinalizationErrorInCurrentTransaction(terminalError);
        requireEqual(eventReceipt.durableHighWatermark(), terminalSequenceNo,
                "finalizationErrorHighWatermark");
        if (attemptReplayed == eventReceipt.inserted()) {
            throw new IllegalStateException(
                    "finalization failure ledger and stream replay state conflict");
        }
        return new AgentRunFinalizationFailureRecorder.Receipt(
                command.agentRunId(),
                command.attemptId(),
                command.resultHash(),
                terminalSequenceNo,
                terminalStatus,
                command.safeErrorCode(),
                attemptReplayed);
    }

    @Override
    @Transactional
    public void recordAttemptFailure(
            String agentRunId,
            String attemptId,
            long attemptNo,
            AgentRunAttemptStatus status,
            String errorCode,
            AgentRunRecoveryAction recoveryAction,
            Instant completedAt) {
        AgentRunEntity run = lockRun(agentRunId);
        AgentRunAttemptEntity attempt =
                attemptRepository
                        .findByIdForUpdate(attemptId)
                        .orElseThrow(() -> new IllegalStateException("AgentRun attempt was not found"));
        requireEqual(attempt.getAgentRunId(), agentRunId, "agentRunId");
        requireEqual(attempt.getAttemptNo(), attemptNo, "attemptNo");
        AgentRunAttemptStatus previousStatus = attempt.getAttemptStatus();
        attempt.recordFailure(status, errorCode, recoveryAction, completedAt);
        Instant durableCompletedAt = attempt.getCompletedAt().toInstant();
        if (previousStatus == status) {
            requireV3AttemptFailureProjection(
                    run, status, errorCode, recoveryAction, durableCompletedAt);
            return;
        }
        projectV3AttemptFailure(run, status, errorCode, recoveryAction, durableCompletedAt);
        requireV3AttemptFailureProjection(
                run, status, errorCode, recoveryAction, durableCompletedAt);
    }

    @Override
    @Transactional
    public ExecuteAgentRunResult recordAttemptFailureResult(
            AgentRunAttemptStatus status, ExecuteAgentRunResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        AgentRunEntity run = lockRun(result.agentRunId());
        AgentRunAttemptEntity attempt =
                attemptRepository
                        .findByIdForUpdate(result.attemptId())
                        .orElseThrow(() -> new IllegalStateException("AgentRun attempt was not found"));
        requireEqual(attempt.getAgentRunId(), result.agentRunId(), "agentRunId");
        requireEqual(attempt.getAttemptNo(), result.attemptNo(), "attemptNo");
        ExecuteAgentRunResult replay = durableFailureResult(attempt);
        if (AgentRunProtocol.V4.wireValue().equals(run.getProtocol())) {
            return recordV4AttemptFailureResult(status, result, run, attempt, replay);
        }
        if (!AgentRunProtocol.V3.wireValue().equals(run.getProtocol())) {
            throw new IllegalStateException(
                    "Activity failure result requires an exact V3 or V4 AgentRun");
        }
        if (replay != null) {
            if (result.lastSequenceNo() != replay.lastSequenceNo()) {
                throw new IllegalStateException(
                        "durableFailureResultSequence conflicts with the persisted AgentRun state");
            }
            requireEqual(replay, result, "durableFailureResult");
            requireDurableFailureReplayState(run, attempt, status, replay);
            return replay;
        }
        AgentRunAttemptStatus previousStatus = attempt.getAttemptStatus();
        if (result.recoveryAction() == AgentRunRecoveryAction.FAIL_LOGICAL_RUN
                && previousStatus != AgentRunAttemptStatus.RUNNING) {
            throw new IllegalStateException(
                    "Activity failure terminal requires a running attempt without a durable result");
        }
        FailureTerminalPosition terminal = result.recoveryAction()
                        == AgentRunRecoveryAction.FAIL_LOGICAL_RUN
                ? requireActivityFailureTerminalPosition(run, attempt, result)
                : null;
        ExecuteAgentRunResult durableResult = terminal != null
                ? withLastSequence(result, terminal.sequenceNo())
                : result;
        if (terminal != null && terminal.appendRequired()) {
            attempt.recordFailureResultWithTerminal(
                    status, result, durableResult, json(durableResult));
        } else {
            attempt.recordFailureResult(status, durableResult, json(durableResult));
        }
        if (previousStatus == status) {
            requireV3AttemptFailureProjection(
                    run,
                    status,
                    durableResult.errorCode(),
                    durableResult.recoveryAction(),
                    durableResult.completedAt());
            return durableResult;
        }
        projectV3AttemptFailure(
                run,
                status,
                durableResult.errorCode(),
                durableResult.recoveryAction(),
                durableResult.completedAt());
        requireV3AttemptFailureProjection(
                run,
                status,
                durableResult.errorCode(),
                durableResult.recoveryAction(),
                durableResult.completedAt());
        if (terminal != null && terminal.appendRequired()) {
            persistFailureTerminalError(
                    run, attempt, durableResult, terminal);
        }
        return durableResult;
    }

    private ExecuteAgentRunResult recordV4AttemptFailureResult(
            AgentRunAttemptStatus status,
            ExecuteAgentRunResult sourceResult,
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            ExecuteAgentRunResult replay) {
        requireV4FailureAuthority(run, attempt, sourceResult);
        if (sourceResult.recoveryAction() != AgentRunRecoveryAction.FAIL_LOGICAL_RUN
                || sourceResult.retryable()) {
            throw new IllegalStateException(
                    "agent-stream.v4 failure must close the single outer attempt");
        }

        if (replay != null) {
            long durableSourceSequence = Math.subtractExact(replay.lastSequenceNo(), 1L);
            boolean exactSourceOrDurable = sourceResult.lastSequenceNo() == durableSourceSequence
                    || sourceResult.lastSequenceNo() == replay.lastSequenceNo();
            requireEqual(exactSourceOrDurable, true, "v4FailureReplaySequence");
            requireEqual(
                    withLastSequence(sourceResult, replay.lastSequenceNo()),
                    replay,
                    "v4DurableFailureResult");
            requireV4FailureProjection(run, attempt, status, replay);
            PostgresAgentRunV4EventWriter.TerminalWriteReceipt terminal =
                    appendV4FailureTerminal(run, attempt, replay);
            requireEqual(terminal.inserted(), false, "v4FailureTerminalReplayInserted");
            return replay;
        }

        requireEqual(run.getRunStatus(), "RUNNING", "v4FailureRunStatus");
        requireEqual(
                attempt.getAttemptStatus(), AgentRunAttemptStatus.RUNNING, "v4FailureAttemptStatus");
        requireEqual(
                sourceResult.lastSequenceNo(), attempt.getLastSequenceNo(), "v4FailureSourceSequence");
        requireEqual(
                sourceResult.publicOutputEmitted(),
                attempt.isPublicOutputEmitted(),
                "v4FailurePublicOutputEmitted");
        requireEqual(attempt.isFinalFrameObserved(), false, "v4FailureFinalFrameObserved");

        long terminalSequence = Math.addExact(sourceResult.lastSequenceNo(), 1L);
        ExecuteAgentRunResult durableResult = withLastSequence(sourceResult, terminalSequence);
        PostgresAgentRunV4EventWriter.TerminalWriteReceipt terminal =
                appendV4FailureTerminal(run, attempt, durableResult);
        requireEqual(terminal.inserted(), true, "v4FailureTerminalInserted");
        requireEqual(
                terminal.durableHighWatermark(),
                terminalSequence,
                "v4FailureTerminalHighWatermark");

        attempt.recordFailureResultWithTerminal(
                status, sourceResult, durableResult, json(durableResult));
        projectV4AttemptFailure(run, status, durableResult);
        requireV4FailureProjection(run, attempt, status, durableResult);
        attemptRepository.save(attempt);
        runRepository.saveAndFlush(run);
        return durableResult;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRunFinalizationReceipt> committedReceipt(String agentRunId) {
        return runRepository.findById(agentRunId).flatMap(this::receipt);
    }

    private Optional<AgentRunFinalizationReceipt> receipt(AgentRunEntity run) {
        if (!"COMMITTED".equals(run.getFinalizationStatus())) {
            return Optional.empty();
        }
        AgentRunAttemptEntity attempt =
                attemptRepository
                        .findById(run.getCommittedAttemptId())
                        .orElseThrow(() -> new IllegalStateException("committed attempt was not found"));
        requireEqual(attempt.getAgentRunId(), run.getId(), "agentRunId");
        requireEqual(
                attempt.getAttemptStatus(), AgentRunAttemptStatus.COMPLETED, "attemptStatus");
        requireEqual(attempt.getResultHash(), run.getFinalResultHash(), "finalResultHash");
        return Optional.of(
                new AgentRunFinalizationReceipt(
                        AgentRunFinalizationReceipt.SCHEMA_VERSION,
                        run.getId(),
                        run.getId(),
                        attempt.getId(),
                        attempt.getAttemptNo(),
                        run.getFencingToken(),
                        run.getFinalResultHash(),
                        run.getCommittedManifestId(),
                        run.getCommittedManifestHash(),
                        run.getFinalStreamSequenceNo(),
                        CommitStatus.COMMITTED,
                        run.getFinalizedAt().toInstant()));
    }

    private LogicalRun logical(AgentRunEntity run) {
        return new LogicalRun(
                run.getId(),
                run.getCaseId(),
                run.getLogicalIdempotencyKey(),
                AgentRunProtocol.V4.wireValue().equals(run.getProtocol())
                        ? AgentRunProtocol.V4
                        : AgentRunProtocol.V3.wireValue().equals(run.getProtocol())
                                ? AgentRunProtocol.V3
                                : AgentRunProtocol.V2.wireValue().equals(run.getProtocol())
                                        ? AgentRunProtocol.V2
                                        : AgentRunProtocol.V1,
                run.getExecutorKind(),
                run.getRoomEpochId(),
                run.getRoomEpoch(),
                run.getProcessRevision(),
                run.getFencingToken(),
                run.getRunStatus(),
                run.getCommittedAttemptId(),
                run.getFinalResultHash(),
                run.getLineageSchemaVersion(),
                run.getLogicalInputHash(),
                run.getAttemptLimit(),
                run.getDeadlineAt() == null ? null : run.getDeadlineAt().toInstant(),
                run.getLogicalVersion());
    }

    private Attempt attempt(AgentRunAttemptEntity entity) {
        return new Attempt(
                entity.getId(),
                entity.getAgentRunId(),
                entity.getAttemptNo(),
                entity.getAttemptStatus(),
                entity.isPublicOutputEmitted(),
                entity.isFinalFrameObserved(),
                entity.getLastSequenceNo(),
                instant(entity.getLastHeartbeatAt()),
                instant(entity.getStartedAt()),
                instant(entity.getCompletedAt()),
                entity.getAttemptVersion(),
                entity.getLineageSchemaVersion(),
                entity.getCommandId(),
                entity.getCommandRequestHash(),
                entity.getLogicalInputHash(),
                canonicalJson(entity.getCommandJson()),
                entity.getPreviousAttemptId(),
                entity.isResetRequired(),
                entity.getPublicSequenceOffset(),
                entity.getTerminationCode(),
                entity.getErrorCode(),
                durableFailureResult(entity));
    }

    private ExecuteAgentRunResult durableFailureResult(AgentRunAttemptEntity entity) {
        if (entity.getAttemptStatus() != AgentRunAttemptStatus.FAILED
                && entity.getAttemptStatus() != AgentRunAttemptStatus.ABORTED) {
            return null;
        }
        String encoded = entity.getResultJson();
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            ExecuteAgentRunResult result =
                    objectMapper.readValue(encoded, ExecuteAgentRunResult.class);
            entity.requireDurableFailureResult(result);
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "persisted AgentRun failure result cannot be decoded", exception);
        }
    }

    private void requireDurableFailureReplayState(
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            AgentRunAttemptStatus suppliedStatus,
            ExecuteAgentRunResult replay) {
        requireEqual(
                attempt.getAttemptStatus(), suppliedStatus, "durableFailureAttemptStatus");
        requireEqual(
                replay.retryable(),
                replay.recoveryAction() == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                "durableFailureRetryable");
        requireV3AttemptFailureProjection(
                run,
                suppliedStatus,
                replay.errorCode(),
                replay.recoveryAction(),
                replay.completedAt());
        requireEqual(run.getFinalizationStatus(), "UNCOMMITTED", "durableFailureFinalizationStatus");
        requireEqual(run.getResultReadyAttemptId(), null, "durableFailureResultReadyAttemptId");
        requireEqual(run.getCommittedAttemptId(), null, "durableFailureCommittedAttemptId");
        requireEqual(run.getFinalResultHash(), null, "durableFailureFinalResultHash");
        if (replay.recoveryAction() != AgentRunRecoveryAction.FAIL_LOGICAL_RUN) {
            return;
        }

        long highWatermark = eventRepository.findMaxV2Sequence(run.getId(), attempt.getId());
        requireEqual(highWatermark, replay.lastSequenceNo(), "durableFailureHighWatermark");
        AgentRunStreamEventEntity persisted = eventRepository
                .findV2Event(run.getId(), attempt.getId(), highWatermark)
                .orElseThrow(() -> new IllegalStateException(
                        "durable failure terminal event is missing"));
        persisted.requireCompatibilityBinding();
        persisted.canonicalPayloadHash(streamObjectMapper);
        AgentStreamEvent terminal;
        try {
            terminal = streamObjectMapper.readValue(
                    persisted.getPayloadJson(), AgentStreamEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "durable failure terminal event is invalid", exception);
        }
        Audience audience = requireV3Audience(run);
        requireEqual(persisted.getAgentRunId(), run.getId(), "durableFailureStoredRunId");
        requireEqual(
                persisted.getAgentRunAttemptId(),
                attempt.getId(),
                "durableFailureStoredAttemptId");
        requireEqual(
                persisted.getSequenceNo(), highWatermark, "durableFailureStoredSequenceNo");
        requireEqual(
                persisted.getStreamProtocol(),
                AgentRunProtocol.V3.wireValue(),
                "durableFailureStoredProtocol");
        requireEqual(
                persisted.getEventType(),
                StreamEventType.ERROR.wireValue(),
                "durableFailureStoredEventType");
        requireEqual(persisted.getAudience(), audience, "durableFailureStoredAudience");
        requireEqual(terminal.runId(), run.getId(), "durableFailureRunId");
        requireEqual(terminal.attemptId(), attempt.getId(), "durableFailureAttemptId");
        requireEqual(terminal.sequenceNo(), highWatermark, "durableFailureSequenceNo");
        requireEqual(
                terminal.schemaVersion(),
                AgentRunProtocol.V3.wireValue(),
                "durableFailureProtocol");
        requireEqual(terminal.eventType(), StreamEventType.ERROR, "durableFailureEventType");
        requireEqual(terminal.audience(), audience, "durableFailureAudience");
        requireEqual(
                terminal.payload().errorCode(), replay.errorCode(), "durableFailureErrorCode");
        requireEqual(
                terminal.payload().retryable(), replay.retryable(), "durableFailureErrorRetryable");
    }

    private static void projectV3AttemptFailure(
            AgentRunEntity run,
            AgentRunAttemptStatus status,
            String errorCode,
            AgentRunRecoveryAction recoveryAction,
            Instant completedAt) {
        boolean retryable = recoveryAction == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT;
        if (!retryable) {
            run.markFailed(
                    errorCode,
                    AgentRunEntity.V3_LOGICAL_FAILURE_MESSAGE,
                    false,
                    null);
        }
        run.markV3AttemptFailed(status, retryable, completedAt);
    }

    private static void requireV3AttemptFailureProjection(
            AgentRunEntity run,
            AgentRunAttemptStatus status,
            String errorCode,
            AgentRunRecoveryAction recoveryAction,
            Instant completedAt) {
        boolean retryable = recoveryAction == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT;
        requireEqual(
                run.getRunStatus(), retryable ? "PENDING" : status.name(), "v3FailureRunStatus");
        requireEqual(
                run.getErrorCode(), retryable ? null : errorCode, "v3FailureRunErrorCode");
        requireEqual(
                run.getErrorRetryable(), retryable ? null : Boolean.FALSE, "v3FailureRunRetryable");
        requireEqual(
                run.getErrorMessage(),
                retryable ? null : AgentRunEntity.V3_LOGICAL_FAILURE_MESSAGE,
                "v3FailureRunErrorMessage");
        requireEqual(
                run.getStopReason(),
                retryable ? null : AgentRunEntity.V3_LOGICAL_FAILURE_STOP_REASON,
                "v3FailureRunStopReason");
        Instant runCompletedAt =
                run.getCompletedAt() == null ? null : run.getCompletedAt().toInstant();
        requireEqual(
                runCompletedAt, retryable ? null : completedAt, "v3FailureRunCompletedAt");
    }

    private static void projectV4AttemptFailure(
            AgentRunEntity run,
            AgentRunAttemptStatus status,
            ExecuteAgentRunResult durableResult) {
        run.markFailed(
                durableResult.errorCode(),
                AgentRunEntity.V4_LOGICAL_FAILURE_MESSAGE,
                false,
                null);
        run.markV4AttemptFailed(status, false, durableResult.completedAt());
    }

    private void requireV4FailureAuthority(
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            ExecuteAgentRunResult result) {
        if (!AgentRunProtocol.V4.wireValue().equals(run.getProtocol())
                || run.getExecutorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY) {
            throw new IllegalStateException(
                    "agent-stream.v4 failure requires an exact Temporal AgentRun V4");
        }
        requireEqual(run.getId(), result.logicalRunId(), "v4FailureLogicalRunId");
        requireEqual(run.getFinalizationStatus(), "UNCOMMITTED", "v4FailureFinalizationStatus");
        requireEqual(run.getResultReadyAttemptId(), null, "v4FailureResultReadyAttemptId");
        requireEqual(run.getCommittedAttemptId(), null, "v4FailureCommittedAttemptId");
        requireEqual(run.getFinalResultHash(), null, "v4FailureFinalResultHash");
        requireEqual(run.getCommittedManifestId(), null, "v4FailureCommittedManifestId");
        requireEqual(run.getCommittedManifestHash(), null, "v4FailureCommittedManifestHash");
        requireEqual(run.getFinalStreamSequenceNo(), null, "v4FailureFinalSequenceNo");
        requireEqual(run.getFinalizedAt(), null, "v4FailureFinalizedAt");
        requireEqual(attempt.getAttemptNo(), 1L, "v4FailureAttemptNo");
        requireEqual(attempt.getPreviousAttemptId(), null, "v4FailurePreviousAttemptId");
        requireEqual(attempt.isResetRequired(), false, "v4FailureResetRequired");
        requireEqual(attempt.getPublicSequenceOffset(), 0, "v4FailurePublicSequenceOffset");
        attempt.requireProofCarryingLineage();
        requireEqual(result.outcome(), ExecuteAgentRunResult.Outcome.FAILED, "v4FailureOutcome");
    }

    private void requireV4FailureProjection(
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            AgentRunAttemptStatus status,
            ExecuteAgentRunResult replay) {
        AgentRunAttemptStatus expectedStatus = replay.publicOutputEmitted()
                ? AgentRunAttemptStatus.ABORTED
                : AgentRunAttemptStatus.FAILED;
        requireEqual(status, expectedStatus, "v4FailureAttemptStatus");
        requireEqual(attempt.getAttemptStatus(), status, "v4DurableFailureAttemptStatus");
        attempt.requireDurableFailureResult(replay);
        requireEqual(
                attempt.getLastSequenceNo(), replay.lastSequenceNo(), "v4DurableFailureSequence");
        requireEqual(
                attempt.isPublicOutputEmitted(),
                replay.publicOutputEmitted(),
                "v4DurableFailurePublicOutput");
        requireEqual(attempt.isFinalFrameObserved(), false, "v4DurableFailureFinalFrame");
        requireEqual(run.getRunStatus(), status.name(), "v4FailureRunStatus");
        requireEqual(run.getErrorCode(), replay.errorCode(), "v4FailureRunErrorCode");
        requireEqual(run.getErrorRetryable(), Boolean.FALSE, "v4FailureRunRetryable");
        requireEqual(
                run.getErrorMessage(),
                AgentRunEntity.V4_LOGICAL_FAILURE_MESSAGE,
                "v4FailureRunErrorMessage");
        requireEqual(
                run.getStopReason(),
                AgentRunEntity.V4_LOGICAL_FAILURE_STOP_REASON,
                "v4FailureRunStopReason");
        requireEqual(
                run.getCompletedAt() == null ? null : run.getCompletedAt().toInstant(),
                replay.completedAt(),
                "v4FailureRunCompletedAt");
    }

    private PostgresAgentRunV4EventWriter.TerminalWriteReceipt appendV4FailureTerminal(
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            ExecuteAgentRunResult durableResult) {
        V4AudienceBinding binding = requireV4AudienceBinding(run);
        long terminalSequence = durableResult.lastSequenceNo();
        String eventId = v4FailureEventId(
                attempt,
                binding,
                terminalSequence,
                durableResult.completedAt(),
                durableResult.errorCode());
        return v4EventWriter.appendOrLoadExactTerminalInCurrentTransaction(
                new PostgresAgentRunV4EventWriter.EventWriteCommand(
                        eventId,
                        run.getId(),
                        attempt.getId(),
                        terminalSequence,
                        AgentStreamEventV4.EventType.ERROR,
                        binding.audience(),
                        durableResult.completedAt(),
                        AgentStreamEventV4.Payload.errorPayload(
                                durableResult.errorCode(), false),
                        binding.actorId(),
                        binding.audienceActorIdsJson()));
    }

    private String v4FailureEventId(
            AgentRunAttemptEntity attempt,
            V4AudienceBinding binding,
            long terminalSequence,
            Instant terminalAt,
            String errorCode) {
        ObjectNode identity = streamObjectMapper.createObjectNode();
        identity.put("schema_version", "agent-run-v4-failure-terminal-id.v1");
        identity.put("run_id", attempt.getAgentRunId());
        identity.put("attempt_id", attempt.getId());
        identity.put("attempt_no", attempt.getAttemptNo());
        identity.put("command_id", attempt.getCommandId());
        identity.put("command_request_sha256", attempt.getCommandRequestHash());
        identity.put("terminal_sequence", terminalSequence);
        identity.put("event_type", AgentStreamEventV4.EventType.ERROR.wireValue());
        identity.put("audience", binding.audience().name());
        identity.put("actor_id", binding.actorId());
        identity.put("occurred_at", terminalAt.toString());
        identity.put("error_code", errorCode);
        identity.put("retryable", false);
        return ContractJson.sha256Hex(identity);
    }

    private V4AudienceBinding requireV4AudienceBinding(AgentRunEntity run) {
        try {
            List<Audience> audiences = streamObjectMapper.readValue(
                    run.getStreamAudienceJson(), new TypeReference<>() {});
            List<String> actorIds = streamObjectMapper.readValue(
                    run.getStreamAudienceActorIdsJson(), new TypeReference<>() {});
            if (audiences == null
                    || audiences.size() != 1
                    || audiences.getFirst() == null
                    || actorIds == null
                    || actorIds.size() != 1
                    || actorIds.getFirst() == null
                    || actorIds.getFirst().isBlank()) {
                throw new IllegalStateException(
                        "agent-stream.v4 failure requires one audience and one actor binding");
            }
            String canonicalActorIds = ContractJson.canonicalString(
                    streamObjectMapper.valueToTree(List.of(actorIds.getFirst())));
            return new V4AudienceBinding(
                    audiences.getFirst(), actorIds.getFirst(), canonicalActorIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "agent-stream.v4 failure has an invalid audience binding", exception);
        }
    }

    private void requireV3RecoveryTerminalProjection(
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            String errorCode,
            Instant terminalAt) {
        requireEqual(run.getRunStatus(), "FAILED", "recoveryWinnerRunStatus");
        requireEqual(run.getErrorCode(), errorCode, "recoveryWinnerErrorCode");
        requireEqual(run.getErrorRetryable(), Boolean.FALSE, "recoveryWinnerRetryable");
        requireEqual(
                run.getErrorMessage(),
                AgentRunEntity.V3_LOGICAL_FAILURE_MESSAGE,
                "recoveryWinnerErrorMessage");
        requireEqual(
                run.getStopReason(),
                AgentRunEntity.V3_LOGICAL_FAILURE_STOP_REASON,
                "recoveryWinnerStopReason");
        requireEqual(
                run.getCompletedAt() == null ? null : run.getCompletedAt().toInstant(),
                terminalAt,
                "recoveryWinnerCompletedAt");
        requireEqual(run.getFinalizationStatus(), "UNCOMMITTED", "recoveryWinnerFinalizationStatus");
        requireEqual(run.getResultReadyAttemptId(), null, "recoveryWinnerResultReadyAttemptId");
        requireEqual(run.getCommittedAttemptId(), null, "recoveryWinnerCommittedAttemptId");
        requireEqual(run.getFinalResultHash(), null, "recoveryWinnerFinalResultHash");
        requireEqual(run.getCommittedManifestId(), null, "recoveryWinnerCommittedManifestId");
        requireEqual(run.getCommittedManifestHash(), null, "recoveryWinnerCommittedManifestHash");
        requireEqual(run.getFinalStreamSequenceNo(), null, "recoveryWinnerFinalSequenceNo");
        requireEqual(run.getFinalizedAt(), null, "recoveryWinnerFinalizedAt");

        requireEqual(attempt.getAgentRunId(), run.getId(), "recoveryWinnerAttemptRunId");
        requireEqual(attempt.isFinalFrameObserved(), false, "recoveryWinnerFinalFrameObserved");
        Instant attemptCompletedAt =
                attempt.getCompletedAt() == null ? null : attempt.getCompletedAt().toInstant();
        boolean directFailure = attempt.getAttemptStatus() == AgentRunAttemptStatus.FAILED
                && errorCode.equals(attempt.getErrorCode())
                && Boolean.FALSE.equals(attempt.getErrorRetryable())
                && AgentRunRecoveryAction.FAIL_LOGICAL_RUN.name()
                        .equals(attempt.getTerminationCode());
        boolean retryablePredecessor = (attempt.getAttemptStatus() == AgentRunAttemptStatus.FAILED
                        || attempt.getAttemptStatus() == AgentRunAttemptStatus.ABORTED
                        || attempt.getAttemptStatus() == AgentRunAttemptStatus.CANCELLED)
                && attempt.getErrorCode() != null
                && !attempt.getErrorCode().isBlank()
                && Boolean.TRUE.equals(attempt.getErrorRetryable())
                && AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT.name()
                        .equals(attempt.getTerminationCode());
        if (!directFailure && !retryablePredecessor) {
            throw new IllegalStateException(
                    "recovery winner attempt conflicts with its durable failure authority");
        }
        if (attemptCompletedAt == null
                || (directFailure && !attemptCompletedAt.equals(terminalAt))
                || (retryablePredecessor && attemptCompletedAt.isAfter(terminalAt))) {
            throw new IllegalStateException(
                    "recovery winner attempt time conflicts with terminal authority");
        }

        long highWatermark = eventRepository.findMaxV2Sequence(run.getId(), attempt.getId());
        requireEqual(
                highWatermark,
                attempt.getLastSequenceNo(),
                "recoveryWinnerTerminalHighWatermark");
        AgentRunStreamEventEntity persisted = eventRepository
                .findV2Event(run.getId(), attempt.getId(), highWatermark)
                .orElseThrow(() -> new IllegalStateException(
                        "recovery winner terminal event is missing"));
        persisted.requireCompatibilityBinding();
        persisted.canonicalPayloadHash(streamObjectMapper);
        Audience audience = requireV3Audience(run);
        AgentStreamEvent terminal;
        try {
            terminal = streamObjectMapper.readValue(
                    persisted.getPayloadJson(), AgentStreamEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "recovery winner terminal event is invalid", exception);
        }
        AgentStreamEvent expected = new AgentStreamEvent(
                AgentRunProtocol.V3.wireValue(),
                run.getId(),
                attempt.getId(),
                highWatermark,
                StreamEventType.ERROR,
                audience,
                terminalAt,
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        errorCode,
                        false));
        requireEqual(persisted.getAgentRunId(), run.getId(), "recoveryWinnerStoredRunId");
        requireEqual(
                persisted.getAgentRunAttemptId(),
                attempt.getId(),
                "recoveryWinnerStoredAttemptId");
        requireEqual(
                persisted.getSequenceNo(), highWatermark, "recoveryWinnerStoredSequenceNo");
        requireEqual(
                persisted.getStreamProtocol(),
                AgentRunProtocol.V3.wireValue(),
                "recoveryWinnerStoredProtocol");
        requireEqual(
                persisted.getEventType(),
                StreamEventType.ERROR.wireValue(),
                "recoveryWinnerStoredEventType");
        requireEqual(persisted.getAudience(), audience, "recoveryWinnerStoredAudience");
        requireEqual(
                persisted.getCreatedAt() == null ? null : persisted.getCreatedAt().toInstant(),
                terminalAt,
                "recoveryWinnerStoredOccurredAt");
        requireEqual(terminal, expected, "recoveryWinnerTerminalEvent");
    }

    private FailureTerminalPosition requireActivityFailureTerminalPosition(
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            ExecuteAgentRunResult result) {
        if (!AgentRunProtocol.V3.wireValue().equals(run.getProtocol())
                || run.getExecutorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY) {
            throw new IllegalStateException(
                    "Activity failure terminal requires a Temporal AgentRun V3");
        }
        requireEqual(run.getId(), result.logicalRunId(), "logicalRunId");
        requireEqual(run.getRunStatus(), "RUNNING", "runStatus");
        requireEqual(run.getFinalizationStatus(), "UNCOMMITTED", "finalizationStatus");
        requireEqual(run.getResultReadyAttemptId(), null, "resultReadyAttemptId");
        requireEqual(run.getCommittedAttemptId(), null, "committedAttemptId");
        requireEqual(run.getFinalResultHash(), null, "finalResultHash");
        requireEqual(
                attempt.getAttemptStatus(), AgentRunAttemptStatus.RUNNING, "attemptStatus");
        requireEqual(
                result.recoveryAction(),
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                "recoveryAction");
        requireEqual(result.retryable(), false, "retryable");

        long highWatermark = eventRepository.findMaxV2Sequence(run.getId(), attempt.getId());
        requireEqual(highWatermark, attempt.getLastSequenceNo(), "activityFailureHighWatermark");
        AgentRunStreamEventEntity persisted = eventRepository
                .findV2Event(run.getId(), attempt.getId(), highWatermark)
                .orElseThrow(() -> new IllegalStateException(
                        "Activity failure source high-watermark event is missing"));
        persisted.requireCompatibilityBinding();
        persisted.canonicalPayloadHash(streamObjectMapper);
        AgentStreamEvent lastEvent;
        try {
            lastEvent = streamObjectMapper.readValue(
                    persisted.getPayloadJson(), AgentStreamEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Activity failure source event is invalid", exception);
        }
        Audience audience = requireV3Audience(run);
        requireEqual(persisted.getAgentRunId(), run.getId(), "activityFailureStoredRunId");
        requireEqual(
                persisted.getAgentRunAttemptId(),
                attempt.getId(),
                "activityFailureStoredAttemptId");
        requireEqual(
                persisted.getSequenceNo(), highWatermark, "activityFailureStoredSequenceNo");
        requireEqual(
                persisted.getStreamProtocol(),
                AgentRunProtocol.V3.wireValue(),
                "activityFailureStoredProtocol");
        requireEqual(persisted.getAudience(), audience, "activityFailureStoredAudience");
        requireEqual(lastEvent.runId(), run.getId(), "activityFailureRunId");
        requireEqual(lastEvent.attemptId(), attempt.getId(), "activityFailureAttemptId");
        requireEqual(lastEvent.sequenceNo(), highWatermark, "activityFailureSequenceNo");
        requireEqual(
                lastEvent.eventType().wireValue(),
                persisted.getEventType(),
                "activityFailureEventType");
        requireEqual(lastEvent.audience(), audience, "activityFailureAudience");
        if (lastEvent.eventType() == StreamEventType.ERROR) {
            requireEqual(
                    lastEvent.payload().errorCode(),
                    result.errorCode(),
                    "activityFailureExistingErrorCode");
            requireEqual(
                    lastEvent.payload().retryable(),
                    result.retryable(),
                    "activityFailureExistingErrorRetryable");
            boolean resultEndsAtSourceOrImmediatelyBeforeError =
                    result.lastSequenceNo() == highWatermark
                            || (highWatermark > 0L
                                    && result.lastSequenceNo() == highWatermark - 1L);
            requireEqual(
                    resultEndsAtSourceOrImmediatelyBeforeError,
                    true,
                    "activityFailureResultSequenceNo");
            return new FailureTerminalPosition(highWatermark, audience, false);
        }
        requireEqual(highWatermark, result.lastSequenceNo(), "activityFailureResultSequenceNo");
        if (lastEvent.eventType() == StreamEventType.FINAL
                || lastEvent.eventType() == StreamEventType.ATTEMPT_ABORTED) {
            throw new IllegalStateException(
                    "Activity failure source already has a terminal stream event");
        }
        return new FailureTerminalPosition(
                Math.addExact(highWatermark, 1L), audience, true);
    }

    private void persistFailureTerminalError(
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            ExecuteAgentRunResult result,
            FailureTerminalPosition position) {
        AgentStreamEvent terminal = new AgentStreamEvent(
                AgentRunProtocol.V3.wireValue(),
                run.getId(),
                attempt.getId(),
                position.sequenceNo(),
                StreamEventType.ERROR,
                position.audience(),
                result.completedAt(),
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        result.errorCode(),
                        false));
        entityManager.flush();
        AgentRunV2StreamStore.AppendReceipt receipt =
                recoveryEventStore.appendRecoveryErrorInCurrentTransaction(terminal);
        requireEqual(receipt.inserted(), true, "activityFailureTerminalInserted");
        requireEqual(
                receipt.durableHighWatermark(),
                position.sequenceNo(),
                "activityFailureTerminalHighWatermark");
    }

    private static ExecuteAgentRunResult withLastSequence(
            ExecuteAgentRunResult source, long lastSequenceNo) {
        return new ExecuteAgentRunResult(
                source.schemaVersion(),
                source.agentRunId(),
                source.logicalRunId(),
                source.attemptId(),
                source.attemptNo(),
                source.outcome(),
                source.graphResult(),
                source.resultHash(),
                lastSequenceNo,
                source.publicOutputEmitted(),
                source.errorCode(),
                source.retryable(),
                source.recoveryAction(),
                source.completedAt());
    }

    private Binding requireVerifiedBinding(
            AgentRunEntity run, RoomGraphCommand command, Binding supplied) {
        Binding verified = verifiedBinding(run, command);
        requireEqual(
                verified.logicalInputHash(), supplied.logicalInputHash(), "logicalInputHash");
        requireEqual(
                verified.commandRequestHash(),
                supplied.commandRequestHash(),
                "commandRequestHash");
        requireEqual(
                verified.canonicalCommandJson(),
                supplied.canonicalCommandJson(),
                "canonicalCommandJson");
        return verified;
    }

    private Binding verifiedBinding(AgentRunEntity run, RoomGraphCommand command) {
        return commandBindingFactory.bind(
                new Context(
                        run.getRoomId(),
                        run.getRoomEpochId(),
                        run.getStreamOperation(),
                        run.getLogicalIdempotencyKey()),
                command);
    }

    private void requirePersistedPredecessor(
            AgentRunAttemptEntity attempt, String logicalInputHash) {
        attempt.requireProofCarryingLineage();
        if (attempt.getAttemptNo() == 1) {
            requireEqual(attempt.getPreviousAttemptId(), null, "previousAttemptId");
            requireEqual(attempt.isResetRequired(), false, "resetRequired");
            requireEqual(attempt.getPublicSequenceOffset(), 0, "publicSequenceOffset");
            return;
        }
        AgentRunAttemptEntity predecessor =
                attemptRepository
                        .findByAgentRunIdAndAttemptNo(
                                attempt.getAgentRunId(), attempt.getAttemptNo() - 1)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "immediately preceding attempt was not found"));
        predecessor.requireCanPrecede(attempt.getAttemptNo(), logicalInputHash);
        requireResidualBudgetDoesNotIncrease(
                predecessor.getCommandJson(), attempt.getCommandJson());
        requireEqual(attempt.getPreviousAttemptId(), predecessor.getId(), "previousAttemptId");
        boolean resetRequired = hasDurableVisibleOutput(
                attempt.getAgentRunId(), predecessor.getId());
        requireEqual(attempt.isResetRequired(), resetRequired, "resetRequired");
        requireEqual(
                attempt.getPublicSequenceOffset(),
                resetRequired ? 1 : 0,
                "publicSequenceOffset");
    }

    private void requireCanonicalCommand(AgentRunAttemptEntity attempt, Binding binding) {
        requireEqual(
                canonicalJson(attempt.getCommandJson()),
                binding.canonicalCommandJson(),
                "canonicalCommandJson");
    }

    private static AgentRunProtocol supportedExecutionProtocol(AgentRunEntity run) {
        if (AgentRunProtocol.V3.wireValue().equals(run.getProtocol())) {
            return AgentRunProtocol.V3;
        }
        if (AgentRunProtocol.V4.wireValue().equals(run.getProtocol())) {
            return AgentRunProtocol.V4;
        }
        throw new IllegalStateException(
                "AgentRun execution accepts only an exact V3 or V4 protocol row");
    }

    private void requireProtocolAdmission(
            AgentRunProtocol protocol,
            AgentRunAttemptEntity attempt,
            RoomGraphCommand command) {
        if (protocol == AgentRunProtocol.V3) {
            requirePersistedPrelude(attempt, command);
            return;
        }
        if (protocol != AgentRunProtocol.V4
                || !ExecuteAgentRunRequest.isParallelIntakeCommand(command)
                || attempt.getAttemptNo() != 1
                || attempt.getPreviousAttemptId() != null
                || attempt.isResetRequired()
                || attempt.getPublicSequenceOffset() != 0) {
            throw new IllegalStateException(
                    "agent-stream.v4 attempt admission differs from the parallel Intake authority");
        }
    }

    private void persistPublicPrelude(
            AgentRunAttemptEntity attempt, RoomGraphCommand command, String resetReasonCode) {
        List<PublicPreludeEvent> prelude = publicPrelude(attempt, command, resetReasonCode);
        // These immutable public rows are also the durable outbox. Redis carries only a
        // best-effort high-watermark hint; replay always reads these PostgreSQL facts.
        eventRepository.saveAllAndFlush(prelude.stream()
                .map(item -> AgentRunStreamEventEntity.createV2Prelude(
                        "ARSE2_" + UUID.randomUUID().toString().replace("-", ""),
                        item.event().runId(),
                        item.event().attemptId(),
                        item.event().sequenceNo(),
                        item.event().eventType().wireValue(),
                        item.event().audience(),
                        item.canonicalJson(),
                        item.payloadHash(),
                        item.event().occurredAt()))
                .toList());
    }

    private void requirePersistedPrelude(
            AgentRunAttemptEntity attempt, RoomGraphCommand command) {
        List<PublicPreludeEvent> expected = publicPrelude(
                attempt,
                command,
                attempt.isResetRequired()
                        ? AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT.name()
                        : null);
        for (PublicPreludeEvent item : expected) {
            AgentRunStreamEventEntity persisted = eventRepository
                    .findV2Event(
                            attempt.getAgentRunId(),
                            attempt.getId(),
                            item.event().sequenceNo())
                    .orElseThrow(() -> new IllegalStateException(
                            "allocated AgentRun attempt is missing its durable public prelude"));
            requireEqual(
                    persisted.getAgentRunId(), item.event().runId(), "publicPreludeRunId");
            requireEqual(
                    persisted.getAgentRunAttemptId(),
                    item.event().attemptId(),
                    "publicPreludeAttemptId");
            requireEqual(
                    persisted.getSequenceNo(),
                    item.event().sequenceNo(),
                    "publicPreludeSequenceNo");
            requireEqual(
                    persisted.getEventType(),
                    item.event().eventType().wireValue(),
                    "publicPreludeEventType");
            requireEqual(
                    persisted.getStreamProtocol(),
                    AgentRunProtocol.V3.wireValue(),
                    "publicPreludeProtocol");
            requireEqual(
                    persisted.getAudience(), item.event().audience(), "publicPreludeAudience");
            requireEqual(
                    persisted.getCreatedAt().toInstant(),
                    item.event().occurredAt(),
                    "publicPreludeOccurredAt");

            JsonNode persistedJson = streamJson(persisted.getPayloadJson());
            String persistedHash = ContractJson.sha256Hex(persistedJson);
            requireEqual(
                    persisted.getPayloadHash(), persistedHash, "publicPreludeStoredHash");
            requireEqual(
                    persisted.getPayloadHash(), item.payloadHash(), "publicPreludePayloadHash");
            requireEqual(
                    ContractJson.canonicalString(persistedJson),
                    item.canonicalJson(),
                    "publicPreludePayloadJson");
        }
        if (!attempt.isResetRequired()) {
            eventRepository
                    .findV2Event(attempt.getAgentRunId(), attempt.getId(), 1)
                    .filter(event -> StreamEventType.ATTEMPT_RESET.wireValue()
                            .equals(event.getEventType()))
                    .ifPresent(ignored -> {
                        throw new IllegalStateException(
                                "allocated AgentRun attempt has an unauthorized reset prelude");
                    });
        }
        if (attempt.getLastSequenceNo() < attempt.getPublicSequenceOffset()) {
            throw new IllegalStateException(
                    "allocated AgentRun attempt progress is behind its durable public prelude");
        }
    }

    private List<PublicPreludeEvent> publicPrelude(
            AgentRunAttemptEntity attempt, RoomGraphCommand command, String resetReasonCode) {
        Instant occurredAt = attempt.getStartedAt().toInstant();
        AgentStreamEvent started = new AgentStreamEvent(
                AgentRunProtocol.V3.wireValue(),
                attempt.getAgentRunId(),
                attempt.getId(),
                0,
                StreamEventType.ATTEMPT_STARTED,
                command.actorScope().audience(),
                occurredAt,
                new AgentStreamEvent.Payload(
                        command.graphKey(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
        if (!attempt.isResetRequired()) {
            return List.of(publicPreludeEvent(started));
        }
        requireEqual(
                resetReasonCode,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT.name(),
                "resetReasonCode");
        AgentStreamEvent reset = new AgentStreamEvent(
                AgentRunProtocol.V3.wireValue(),
                attempt.getAgentRunId(),
                attempt.getId(),
                1,
                StreamEventType.ATTEMPT_RESET,
                command.actorScope().audience(),
                occurredAt,
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        resetReasonCode,
                        attempt.getPreviousAttemptId(),
                        null,
                        null,
                        null,
                        null));
        return List.of(publicPreludeEvent(started), publicPreludeEvent(reset));
    }

    private PublicPreludeEvent publicPreludeEvent(AgentStreamEvent event) {
        JsonNode json = streamObjectMapper.valueToTree(event);
        return new PublicPreludeEvent(
                event,
                ContractJson.canonicalString(json),
                ContractJson.sha256Hex(json));
    }

    private void persistRecoveryTerminalError(
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            String errorCode,
            Instant occurredAt,
            RecoveryTerminalPosition position) {
        AgentStreamEvent terminal = new AgentStreamEvent(
                AgentRunProtocol.V3.wireValue(),
                run.getId(),
                attempt.getId(),
                position.sequenceNo(),
                StreamEventType.ERROR,
                position.audience(),
                occurredAt,
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        errorCode,
                        false));
        // Flush the managed cursor/status first so JDBC observes the same recovery terminal
        // authority. The enclosing transaction still rolls back this flush together with both
        // stream stores and the target delivery high-watermark if the append fails.
        entityManager.flush();
        var receipt = recoveryEventStore.appendRecoveryErrorInCurrentTransaction(terminal);
        requireEqual(
                receipt.durableHighWatermark(),
                terminal.sequenceNo(),
                "recoveryTerminalDurableHighWatermark");
    }

    private RecoveryTerminalPosition requireRecoveryTerminalPosition(
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            AgentRunAttemptStatus attemptStatus) {
        boolean pending = "PENDING".equals(run.getRunStatus());
        if (pending
                && attemptStatus != AgentRunAttemptStatus.FAILED
                && attemptStatus != AgentRunAttemptStatus.ABORTED
                && attemptStatus != AgentRunAttemptStatus.CANCELLED) {
            throw new IllegalStateException(
                    "PENDING recovery requires a terminal failed predecessor");
        }
        if (!pending && attemptStatus != AgentRunAttemptStatus.RUNNING) {
            throw new IllegalStateException(
                    "RUNNING recovery requires a running latest attempt");
        }
        long highWatermark = eventRepository.findMaxV2Sequence(run.getId(), attempt.getId());
        requireEqual(
                highWatermark,
                attempt.getLastSequenceNo(),
                "recoveryTerminalSourceHighWatermark");
        AgentRunStreamEventEntity persisted = eventRepository
                .findV2Event(run.getId(), attempt.getId(), highWatermark)
                .orElseThrow(() -> new IllegalStateException(
                        "recovery terminal source high-watermark event is missing"));
        persisted.requireCompatibilityBinding();
        persisted.canonicalPayloadHash(streamObjectMapper);
        AgentStreamEvent lastEvent;
        try {
            lastEvent = streamObjectMapper.readValue(
                    persisted.getPayloadJson(), AgentStreamEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "recovery terminal source event is invalid", exception);
        }
        Audience audience = requireV3Audience(run);
        requireEqual(persisted.getAgentRunId(), run.getId(), "recoveryTerminalStoredRunId");
        requireEqual(
                persisted.getAgentRunAttemptId(),
                attempt.getId(),
                "recoveryTerminalStoredAttemptId");
        requireEqual(
                persisted.getSequenceNo(), highWatermark, "recoveryTerminalStoredSequenceNo");
        requireEqual(
                persisted.getStreamProtocol(),
                AgentRunProtocol.V3.wireValue(),
                "recoveryTerminalStoredProtocol");
        requireEqual(
                persisted.getAudience(), audience, "recoveryTerminalStoredAudience");
        requireEqual(lastEvent.runId(), run.getId(), "recoveryTerminalRunId");
        requireEqual(lastEvent.attemptId(), attempt.getId(), "recoveryTerminalAttemptId");
        requireEqual(lastEvent.sequenceNo(), highWatermark, "recoveryTerminalSequenceNo");
        requireEqual(lastEvent.eventType().wireValue(), persisted.getEventType(),
                "recoveryTerminalEventType");
        requireEqual(lastEvent.audience(), audience, "recoveryTerminalAudience");
        if (lastEvent.eventType() == StreamEventType.ERROR
                || lastEvent.eventType() == StreamEventType.FINAL) {
            throw new IllegalStateException(
                    "recovery candidate already has a global terminal event");
        }
        if (pending && lastEvent.eventType() != StreamEventType.ATTEMPT_ABORTED) {
            throw new IllegalStateException(
                    "PENDING recovery predecessor must end with attempt_aborted");
        }
        if (!pending && lastEvent.eventType() == StreamEventType.ATTEMPT_ABORTED) {
            throw new IllegalStateException(
                    "RUNNING recovery cannot follow an attempt terminal event");
        }
        return new RecoveryTerminalPosition(Math.addExact(highWatermark, 1L), audience);
    }

    private Audience requireV3Audience(AgentRunEntity run) {
        try {
            List<Audience> audiences = streamObjectMapper.readValue(
                    run.getStreamAudienceJson(), new TypeReference<>() {});
            if (audiences == null || audiences.size() != 1 || audiences.getFirst() == null) {
                throw new IllegalStateException(
                        "V3 recovery terminal requires exactly one stream audience");
            }
            return audiences.getFirst();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "V3 recovery terminal has an invalid stream audience", exception);
        }
    }

    private JsonNode streamJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("persisted public prelude JSON is missing");
        }
        try {
            return streamObjectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "persisted public prelude JSON is invalid", exception);
        }
    }

    private record PublicPreludeEvent(
            AgentStreamEvent event, String canonicalJson, String payloadHash) {}

    private record FailureTerminalPosition(
            long sequenceNo, Audience audience, boolean appendRequired) {}

    private record V4AudienceBinding(
            Audience audience, String actorId, String audienceActorIdsJson) {}

    private record RecoveryTerminalPosition(long sequenceNo, Audience audience) {}

    private AgentRunEntity lockRun(String agentRunId) {
        return runRepository
                .findByIdForUpdate(agentRunId)
                .orElseThrow(() -> new IllegalStateException("logical AgentRun was not found"));
    }

    private void lockLogicalKey(String caseId, String key) {
        entityManager
                .createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .setParameter("lockKey", caseId + ':' + key)
                .getSingleResult();
    }

    private boolean hasDurableVisibleOutput(String agentRunId, String attemptId) {
        Number count = (Number) entityManager
                .createNativeQuery(
                        """
                        select count(*)
                          from agent_run_stream_event
                         where agent_run_id = :runId
                           and agent_run_attempt_id = :attemptId
                           and stream_protocol = 'agent-stream.v3'
                           and event_type = 'visible_delta'
                        """)
                .setParameter("runId", agentRunId)
                .setParameter("attemptId", attemptId)
                .getSingleResult();
        return count != null && count.longValue() > 0;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AgentRun persistence JSON encoding failed", exception);
        }
    }

    private String canonicalJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("persisted canonicalCommandJson is missing");
        }
        try {
            return ContractJson.canonicalString(objectMapper.readTree(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "persisted canonicalCommandJson is not valid JSON", exception);
        }
    }

    private void requireResidualBudgetDoesNotIncrease(
            String previousCommandJson, String currentCommandJson) {
        JsonNode previous = commandJson(previousCommandJson)
                .required("retry_budget");
        JsonNode current = commandJson(currentCommandJson)
                .required("retry_budget");
        for (String field : List.of(
                "provider_attempts_remaining",
                "activity_attempts_remaining",
                "repairs_remaining")) {
            int previousValue = nonNegativeBudget(previous.required(field), field);
            int currentValue = nonNegativeBudget(current.required(field), field);
            if (currentValue > previousValue) {
                throw new IllegalStateException(
                        "residual retry budget cannot increase across AgentRun attempts");
            }
        }
    }

    private JsonNode commandJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("persisted canonicalCommandJson is missing");
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "persisted canonicalCommandJson is not valid JSON", exception);
        }
    }

    private static int nonNegativeBudget(JsonNode value, String field) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalStateException(field + " is not a non-negative integer");
        }
        return value.intValue();
    }

    private static Instant instant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new IllegalStateException(field + " conflicts with persisted AgentRun state");
        }
    }
}
