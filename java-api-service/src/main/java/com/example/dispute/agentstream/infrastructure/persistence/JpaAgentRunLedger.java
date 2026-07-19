package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
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
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaAgentRunLedger implements AgentRunLedger {

    private final AgentRunRepository runRepository;
    private final AgentRunAttemptRepository attemptRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final AgentRunCommandBindingFactory commandBindingFactory;

    public JpaAgentRunLedger(
            AgentRunRepository runRepository,
            AgentRunAttemptRepository attemptRepository,
            EntityManager entityManager,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.attemptRepository = attemptRepository;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.commandBindingFactory = new AgentRunCommandBindingFactory(objectMapper);
    }

    @Override
    @Transactional
    public LogicalRun createOrLoad(CreateLogicalRun command) {
        if (command.protocol() != AgentRunProtocol.V2) {
            throw new IllegalArgumentException("logical AgentRun creation only accepts protocol V2");
        }
        if (command.executorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY) {
            throw new IllegalArgumentException("AgentRun V2 requires the Temporal Activity executor");
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
        AgentRunEntity created = runRepository.saveAndFlush(AgentRunEntity.logicalV2(command));
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
    public Attempt startNextAttempt(
            String agentRunId, AttemptAllocation allocation, Instant startedAt) {
        if (allocation == null) {
            throw new IllegalArgumentException("allocation must not be null");
        }
        RoomGraphCommand command = allocation.command();
        requireEqual(command.logicalRunId(), agentRunId, "logicalRunId");
        AgentRunEntity run = lockRun(agentRunId);
        run.requireAttemptCommand(command);
        Binding binding = requireVerifiedBinding(run, command, allocation.binding());
        AttemptAllocation verified =
                new AttemptAllocation(allocation.attemptNo(), command, binding);
        run.bindV2Audience(
                command.actorScope().actorRole().name(),
                json(List.of(command.actorScope().audience().name())),
                json(List.of(command.actorScope().actorId())));

        Optional<AgentRunAttemptEntity> replay =
                attemptRepository.findByAgentRunIdAndAttemptNo(
                        agentRunId, allocation.attemptNo());
        if (replay.isPresent()) {
            run.requireBoundLineage(binding.logicalInputHash());
            AgentRunAttemptEntity persisted = replay.orElseThrow();
            persisted.requireSameAllocation(verified);
            requireCanonicalCommand(persisted, binding);
            requirePersistedPredecessor(persisted, binding.logicalInputHash());
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

        String previousAttemptId = null;
        boolean resetRequired = false;
        int publicSequenceOffset = 0;
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
        }

        AgentRunAttemptEntity created =
                AgentRunAttemptEntity.start(
                        agentRunId,
                        verified,
                        previousAttemptId,
                        resetRequired,
                        publicSequenceOffset,
                        startedAt);
        run.markV2AttemptStarted();
        attemptRepository.saveAndFlush(created);
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
        attempt.recordResultReady(result, json(result));
        if ("COMMITTED".equals(run.getFinalizationStatus())) {
            requireEqual(run.getCommittedAttemptId(), result.attemptId(), "committedAttemptId");
            requireEqual(run.getFinalResultHash(), result.resultHash(), "finalResultHash");
            return;
        }
        run.markV2ResultReady(result.attemptId(), result.resultHash(), result.completedAt());
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
        if (previousStatus == status) {
            return;
        }
        boolean retryable = recoveryAction == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT;
        run.markV2AttemptFailed(status, retryable, completedAt);
    }

    @Override
    @Transactional
    public void recordAttemptFailureResult(
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
        AgentRunAttemptStatus previousStatus = attempt.getAttemptStatus();
        attempt.recordFailureResult(status, result, json(result));
        if (previousStatus == status) {
            return;
        }
        run.markV2AttemptFailed(
                status,
                result.recoveryAction() == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                result.completedAt());
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
                AgentRunProtocol.V2.wireValue().equals(run.getProtocol())
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
                           and stream_protocol = 'agent-stream.v2'
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
