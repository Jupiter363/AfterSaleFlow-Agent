package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.fasterxml.jackson.core.JsonProcessingException;
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

    public JpaAgentRunLedger(
            AgentRunRepository runRepository,
            AgentRunAttemptRepository attemptRepository,
            EntityManager entityManager,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.attemptRepository = attemptRepository;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public LogicalRun createOrLoad(CreateLogicalRun command) {
        if (command.protocol() != AgentRunProtocol.V2) {
            throw new IllegalArgumentException("logical AgentRun creation only accepts protocol V2");
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
            String agentRunId, ExecuteAgentRunRequest request, Instant startedAt) {
        AgentRunEntity run = lockRun(agentRunId);
        run.requireAttemptRequest(request);
        run.bindV2Audience(
                request.command().actorScope().actorRole().name(),
                json(List.of(request.command().actorScope().audience().name())),
                json(List.of(request.command().actorScope().actorId())));

        Optional<AgentRunAttemptEntity> replay =
                attemptRepository.findByAgentRunIdAndAttemptNo(agentRunId, request.attemptNo());
        if (replay.isPresent()) {
            replay.orElseThrow().requireSameRequest(request);
            return attempt(replay.orElseThrow());
        }
        attemptRepository
                .findById(request.attemptId())
                .ifPresent(
                        ignored -> {
                            throw new IllegalStateException(
                                    "attemptId is already bound to another attempt");
                        });

        long nextAttemptNo = attemptRepository.findMaxAttemptNoByAgentRunId(agentRunId) + 1;
        if (request.attemptNo() != nextAttemptNo) {
            throw new IllegalStateException(
                    "attemptNo must allocate the next value while the logical run is locked");
        }
        if (nextAttemptNo > run.getAttemptLimit()) {
            throw new IllegalStateException("logical AgentRun attempt limit is exhausted");
        }

        AgentRunAttemptEntity created =
                AgentRunAttemptEntity.start(agentRunId, request, startedAt);
        run.markV2AttemptStarted();
        attemptRepository.saveAndFlush(created);
        return attempt(created);
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
            boolean retryable,
            Instant completedAt) {
        AgentRunEntity run = lockRun(agentRunId);
        AgentRunAttemptEntity attempt =
                attemptRepository
                        .findByIdForUpdate(attemptId)
                        .orElseThrow(() -> new IllegalStateException("AgentRun attempt was not found"));
        requireEqual(attempt.getAgentRunId(), agentRunId, "agentRunId");
        requireEqual(attempt.getAttemptNo(), attemptNo, "attemptNo");
        AgentRunAttemptStatus previousStatus = attempt.getAttemptStatus();
        attempt.recordFailure(status, errorCode, retryable, completedAt);
        if (previousStatus == status) {
            return;
        }
        run.markV2AttemptFailed(status, retryable, completedAt);
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
                entity.getLastSequenceNo(),
                instant(entity.getLastHeartbeatAt()),
                instant(entity.getStartedAt()),
                instant(entity.getCompletedAt()),
                entity.getAttemptVersion());
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AgentRun persistence JSON encoding failed", exception);
        }
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
