package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.agentstream.application.AgentExecutionManifestStore;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.AgentExecutionManifestEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ManifestTerminalStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.AgentExecutionManifestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaAgentExecutionManifestStore implements AgentExecutionManifestStore {

    private final AgentRunRepository runRepository;
    private final AgentRunAttemptRepository attemptRepository;
    private final AgentExecutionManifestRepository manifestRepository;
    private final ObjectMapper objectMapper;

    public JpaAgentExecutionManifestStore(
            AgentRunRepository runRepository,
            AgentRunAttemptRepository attemptRepository,
            AgentExecutionManifestRepository manifestRepository,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.attemptRepository = attemptRepository;
        this.manifestRepository = manifestRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AgentRunFinalizationReceipt append(ManifestCommit commit) {
        AgentExecutionManifest manifest = commit.manifest();
        String logicalRunId = manifest.agentRun().logicalRunId();
        AgentRunEntity run =
                runRepository
                        .findByIdForUpdate(logicalRunId)
                        .orElseThrow(() -> new IllegalStateException("logical AgentRun was not found"));
        requireCommitIdentity(run, commit);

        Optional<AgentExecutionManifestEntity> existing =
                manifestRepository.findByTenantSurrogateAndCaseIdAndLogicalAgentRunId(
                        manifest.tenantSurrogate(), manifest.caseId(), logicalRunId);
        if (existing.isPresent()) {
            AgentExecutionManifestEntity persisted = existing.orElseThrow();
            if (persisted.getTerminalStatus() == ManifestTerminalStatus.LEGACY_IMPORTED) {
                throw new IllegalStateException("legacy manifest cannot be replaced by a formal manifest");
            }
            requireEqual(persisted.getAttemptId(), manifest.agentRun().attemptId(), "attemptId");
            requireEqual(persisted.getManifestSha256(), commit.manifestHash(), "manifestHash");
            requireEqual(persisted.getOutputSha256(), commit.finalResultHash(), "finalResultHash");
            return receipt(run, persisted, CommitStatus.ALREADY_COMMITTED);
        }
        if ("COMMITTED".equals(run.getFinalizationStatus())) {
            throw new IllegalStateException("logical AgentRun is committed without its manifest row");
        }

        AgentRunAttemptEntity attempt =
                attemptRepository
                        .findByIdForUpdate(manifest.agentRun().attemptId())
                        .orElseThrow(() -> new IllegalStateException("AgentRun attempt was not found"));
        requireEqual(attempt.getAgentRunId(), run.getId(), "agentRunId");
        requireEqual(attempt.getResultHash(), commit.finalResultHash(), "finalResultHash");
        attempt.markCommitted(manifest);

        AgentExecutionManifestEntity entity =
                AgentExecutionManifestEntity.formal(
                        manifest,
                        commit.roomType(),
                        commit.manifestUri(),
                        commit.manifestHash(),
                        json(manifest.inputs()));
        manifestRepository.saveAndFlush(entity);
        run.commitV2Final(
                attempt.getId(),
                commit.finalResultHash(),
                entity.getId(),
                commit.manifestHash(),
                commit.finalStreamSequenceNo(),
                manifest.finalizedAt());
        return receipt(run, entity, CommitStatus.COMMITTED);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRunFinalizationReceipt> findCommitted(String logicalRunId) {
        List<AgentExecutionManifestEntity> committed =
                manifestRepository.findAllByLogicalAgentRunId(logicalRunId).stream()
                        .filter(entity -> entity.getTerminalStatus() == ManifestTerminalStatus.COMPLETED)
                        .toList();
        if (committed.size() > 1) {
            throw new IllegalStateException("logicalRunId is ambiguous across manifest scopes");
        }
        if (committed.isEmpty()) {
            return Optional.empty();
        }
        AgentRunEntity run =
                runRepository
                        .findById(logicalRunId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "manifest logical AgentRun was not found"));
        return Optional.of(
                receipt(run, committed.getFirst(), CommitStatus.ALREADY_COMMITTED));
    }

    private void requireCommitIdentity(AgentRunEntity run, ManifestCommit commit) {
        AgentExecutionManifest manifest = commit.manifest();
        requireEqual(run.getTenantSurrogate(), manifest.tenantSurrogate(), "tenantSurrogate");
        requireEqual(run.getCaseId(), manifest.caseId(), "caseId");
        requireEqual(
                run.getLogicalIdempotencyKey(),
                manifest.agentRun().logicalIdempotencyKey(),
                "logicalIdempotencyKey");
        requireEqual(run.getRoomType(), commit.roomType(), "roomType");
        requireEqual(run.getRoomEpoch(), manifest.roomEpoch(), "roomEpoch");
        requireEqual(run.getProcessRevision(), manifest.processRevision(), "processRevision");
        requireEqual(run.getFencingToken(), manifest.fencingToken(), "fencingToken");
        requireEqual(manifest.output().sha256(), commit.finalResultHash(), "outputHash");
        requireEqual(run.getFinalResultHash(), commit.finalResultHash(), "finalResultHash");
        sha256(commit.manifestHash(), "manifestHash");
        sha256(commit.finalResultHash(), "finalResultHash");
        if (commit.finalStreamSequenceNo() < 0) {
            throw new IllegalArgumentException("finalStreamSequenceNo must not be negative");
        }
    }

    private AgentRunFinalizationReceipt receipt(
            AgentRunEntity run,
            AgentExecutionManifestEntity manifest,
            CommitStatus status) {
        AgentRunAttemptEntity attempt =
                attemptRepository
                        .findById(manifest.getAttemptId())
                        .orElseThrow(() -> new IllegalStateException("manifest attempt was not found"));
        requireEqual(run.getCommittedAttemptId(), attempt.getId(), "committedAttemptId");
        requireEqual(run.getCommittedManifestHash(), manifest.getManifestSha256(), "manifestHash");
        return new AgentRunFinalizationReceipt(
                AgentRunFinalizationReceipt.SCHEMA_VERSION,
                run.getId(),
                manifest.getLogicalAgentRunId(),
                attempt.getId(),
                attempt.getAttemptNo(),
                manifest.getFencingToken(),
                manifest.getOutputSha256(),
                manifest.getId(),
                manifest.getManifestSha256(),
                run.getFinalStreamSequenceNo(),
                status,
                manifest.getFinalizedAt().toInstant());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("manifest input reference encoding failed", exception);
        }
    }

    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new IllegalStateException(field + " conflicts with the formal manifest commit");
        }
    }
}
