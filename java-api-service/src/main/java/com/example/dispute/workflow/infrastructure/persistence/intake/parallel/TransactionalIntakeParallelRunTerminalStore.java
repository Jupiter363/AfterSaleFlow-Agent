package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter.EventWriteCommand;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter.TerminalWriteReceipt;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyLookup;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore.DurableProgress;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore.TerminalCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore.TerminalConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore.TerminalReceipt;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL/JPA owner of the atomic V4 FINAL + RESULT_READY technical transaction. */
@Repository
public class TransactionalIntakeParallelRunTerminalStore
        implements IntakeParallelRunTerminalStore {

    private static final String RECEIPT_SCHEMA = "intake-parallel-final-receipt.v1";
    private static final String EVENT_SCHEMA = "intake-parallel-final-event.v1";

    private final AgentRunRepository runRepository;
    private final AgentRunAttemptRepository attemptRepository;
    private final IntakeParallelAssemblyStore assemblyStore;
    private final PostgresAgentRunV4EventWriter eventWriter;
    private final ObjectMapper objectMapper;

    public TransactionalIntakeParallelRunTerminalStore(
            AgentRunRepository runRepository,
            AgentRunAttemptRepository attemptRepository,
            IntakeParallelAssemblyStore assemblyStore,
            PostgresAgentRunV4EventWriter eventWriter,
            ObjectMapper objectMapper) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.assemblyStore = Objects.requireNonNull(assemblyStore, "assemblyStore");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    @Override
    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public DurableProgress loadProgress(ExecuteAgentRunRequest request) {
        requireParallelRequest(request);
        AgentRunEntity run = runRepository
                .findById(request.agentRunId())
                .orElseThrow(() -> conflict(
                        "INTAKE_PARALLEL_PROGRESS_RUN_MISSING",
                        "parallel Intake AgentRun was not found"));
        AgentRunAttemptEntity attempt = attemptRepository
                .findById(request.attemptId())
                .orElseThrow(() -> conflict(
                        "INTAKE_PARALLEL_PROGRESS_ATTEMPT_MISSING",
                        "parallel Intake AgentRun attempt was not found"));
        run.requireAttemptRequest(request);
        attempt.requireAllocatedRequest(request);
        requireEqual(run.getProtocol(), AgentRunProtocol.V4.wireValue(), "streamProtocol");
        return new DurableProgress(
                attempt.getLastSequenceNo(),
                attempt.isPublicOutputEmitted(),
                attempt.isFinalFrameObserved()
                        || attempt.getAttemptStatus() == AgentRunAttemptStatus.RESULT_READY
                        || attempt.getAttemptStatus() == AgentRunAttemptStatus.COMPLETED);
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public TerminalReceipt appendOrLoad(TerminalCommand terminalCommand) {
        Objects.requireNonNull(terminalCommand, "terminalCommand");
        ExecuteAgentRunRequest request = terminalCommand.request();
        GraphReconcileResponse reconciliation = terminalCommand.reconciliation();
        requireParallelRequest(request);

        AgentRunEntity run = runRepository
                .findByIdForUpdate(request.agentRunId())
                .orElseThrow(() -> conflict(
                        "INTAKE_PARALLEL_TERMINAL_RUN_MISSING",
                        "parallel Intake AgentRun was not found"));
        AgentRunAttemptEntity attempt = attemptRepository
                .findByIdForUpdate(request.attemptId())
                .orElseThrow(() -> conflict(
                        "INTAKE_PARALLEL_TERMINAL_ATTEMPT_MISSING",
                        "parallel Intake AgentRun attempt was not found"));
        run.requireAttemptRequest(request);
        attempt.requireAllocatedRequest(request);
        requireEqual(run.getProtocol(), AgentRunProtocol.V4.wireValue(), "streamProtocol");
        requireEqual(run.getFinalizationStatus(), expectedFinalization(run), "finalizationStatus");
        requireAudience(run, request.command());

        ReadyLookup lookup = new ReadyLookup(
                request.agentRunId(),
                request.attemptId(),
                request.command().commandId(),
                request.command().requestHash());
        ReadyAuthority ready = assemblyStore.lockReadyForTerminal(lookup);
        validateReconciliation(request, reconciliation, ready.artifact());

        TerminalPhase phase = terminalPhase(run, attempt, ready);
        long previousSequence = phase.replay()
                ? Math.subtractExact(attempt.getLastSequenceNo(), 1L)
                : attempt.getLastSequenceNo();
        long terminalSequence = Math.addExact(previousSequence, 1L);
        Instant terminalAt = ready.readyAt();
        String finalReceiptId = finalReceiptId(
                request, ready.artifact(), terminalSequence, terminalAt);
        String eventId = eventId(
                request, terminalSequence, terminalAt, finalReceiptId, ready.artifact());
        ExecuteAgentRunResult result = completedResult(
                request, reconciliation.result(), terminalSequence, terminalAt);
        String canonicalResultJson = canonicalJson(result);

        TerminalWriteReceipt persisted = eventWriter
                .appendOrLoadExactTerminalInCurrentTransaction(new EventWriteCommand(
                        eventId,
                        request.agentRunId(),
                        request.attemptId(),
                        terminalSequence,
                        AgentStreamEventV4.EventType.FINAL,
                        request.command().actorScope().audience(),
                        terminalAt,
                        AgentStreamEventV4.Payload.finalPayload(
                                finalReceiptId, ready.artifact().graphResultSha256()),
                        request.command().actorScope().actorId(),
                        canonicalJson(List.of(request.command().actorScope().actorId()))));
        if (persisted.inserted() == phase.replay()) {
            throw conflict(
                    "INTAKE_PARALLEL_TERMINAL_REPLAY_CONFLICT",
                    phase.replay()
                            ? "terminal replay inserted a second FINAL"
                            : "new terminalization found a pre-existing FINAL");
        }

        attempt.recordV4ResultReady(result, canonicalResultJson, previousSequence);
        if (phase == TerminalPhase.NEW || phase == TerminalPhase.RESULT_READY_REPLAY) {
            run.markV4ResultReady(
                    request.attemptId(), ready.artifact().graphResultSha256(), terminalAt);
        } else {
            requireEqual(run.getCommittedAttemptId(), request.attemptId(), "committedAttemptId");
            requireEqual(
                    run.getFinalResultHash(),
                    ready.artifact().graphResultSha256(),
                    "finalResultHash");
            requireEqual(run.getFinalStreamSequenceNo(), terminalSequence, "finalStreamSequenceNo");
        }
        attemptRepository.save(attempt);
        runRepository.saveAndFlush(run);

        return new TerminalReceipt(
                result,
                ready.artifact().resultRef(),
                finalReceiptId,
                persisted.eventId(),
                persisted.eventSha256(),
                persisted.inserted(),
                persisted.durableHighWatermark());
    }

    private static void requireParallelRequest(ExecuteAgentRunRequest request) {
        if (request == null
                || !ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                || !AgentRunProtocol.V4.wireValue().equals(request.streamProtocol())
                || request.attemptNo() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_TERMINAL_PROFILE_INVALID",
                    "V4 terminalization requires the explicit parallel Intake attempt");
        }
    }

    private static String expectedFinalization(AgentRunEntity run) {
        if ("RUNNING".equals(run.getRunStatus()) || "RESULT_READY".equals(run.getRunStatus())) {
            return "UNCOMMITTED";
        }
        if ("COMPLETED".equals(run.getRunStatus())) {
            return "COMMITTED";
        }
        throw conflict(
                "INTAKE_PARALLEL_TERMINAL_RUN_STATE_INVALID",
                "parallel Intake run is not terminalizable or replayable");
    }

    private TerminalPhase terminalPhase(
            AgentRunEntity run, AgentRunAttemptEntity attempt, ReadyAuthority ready) {
        if ("RUNNING".equals(run.getRunStatus())
                && attempt.getAttemptStatus() == AgentRunAttemptStatus.RUNNING) {
            if (ready.state() != AssemblyState.READY || attempt.isFinalFrameObserved()) {
                throw conflict(
                        "INTAKE_PARALLEL_TERMINAL_PRECONDITION_INVALID",
                        "new V4 FINAL requires READY assembly and no prior final observation");
            }
            return TerminalPhase.NEW;
        }
        if ("RESULT_READY".equals(run.getRunStatus())
                && attempt.getAttemptStatus() == AgentRunAttemptStatus.RESULT_READY) {
            return TerminalPhase.RESULT_READY_REPLAY;
        }
        if ("COMPLETED".equals(run.getRunStatus())
                && attempt.getAttemptStatus() == AgentRunAttemptStatus.COMPLETED
                && ready.state() == AssemblyState.COMMITTED) {
            return TerminalPhase.COMMITTED_REPLAY;
        }
        throw conflict(
                "INTAKE_PARALLEL_TERMINAL_STATE_SPLIT",
                "run, attempt, and assembly do not expose one terminal authority");
    }

    private void requireAudience(AgentRunEntity run, RoomGraphCommand command) {
        requireEqual(
                canonicalStoredJson(run.getStreamAudienceJson(), "streamAudienceJson"),
                canonicalJson(List.of(command.actorScope().audience().name())),
                "streamAudienceJson");
        requireEqual(
                canonicalStoredJson(
                        run.getStreamAudienceActorIdsJson(), "streamAudienceActorIdsJson"),
                canonicalJson(List.of(command.actorScope().actorId())),
                "streamAudienceActorIdsJson");
    }

    private void validateReconciliation(
            ExecuteAgentRunRequest request,
            GraphReconcileResponse reconciliation,
            ReadyArtifact artifact) {
        RoomGraphCommand command = request.command();
        RoomGraphResult result = reconciliation.result();
        boolean exact = command.threadId().equals(reconciliation.threadId())
                && command.commandId().equals(reconciliation.commandId())
                && command.requestHash().equals(reconciliation.requestHash())
                && request.logicalRunId().equals(reconciliation.logicalRunId())
                && request.attemptId().equals(reconciliation.attemptId())
                && command.graphKey().equals(reconciliation.graphKey())
                && command.graphVersion().equals(reconciliation.graphVersion())
                && command.checkpointSchemaVersion().equals(
                        reconciliation.checkpointSchemaVersion())
                && artifact.checkpointNs().equals(reconciliation.checkpointNs())
                && result.checkpointId().equals(reconciliation.checkpointId())
                && artifact.resultRef().equals(reconciliation.resultRef())
                && artifact.graphResultSha256().equals(reconciliation.resultHash())
                && artifact.registryBindingSha256().equals(
                        reconciliation.registryBindingHash())
                && artifact.toolPolicyVersion().equals(reconciliation.toolPolicyVersion())
                && MessageDigest.isEqual(
                        artifact.canonicalGraphResultBytes(),
                        ContractJson.canonicalize(objectMapper.valueToTree(result)));
        if (!exact) {
            throw conflict(
                    "INTAKE_PARALLEL_TERMINAL_RESULT_CONFLICT",
                    "reconciliation result differs from immutable READY authority");
        }
    }

    private static ExecuteAgentRunResult completedResult(
            ExecuteAgentRunRequest request,
            RoomGraphResult graphResult,
            long terminalSequence,
            Instant terminalAt) {
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graphResult,
                graphResult.outputHash(),
                terminalSequence,
                true,
                null,
                false,
                null,
                terminalAt);
    }

    private String finalReceiptId(
            ExecuteAgentRunRequest request,
            ReadyArtifact artifact,
            long terminalSequence,
            Instant terminalAt) {
        ObjectNode identity = identity(RECEIPT_SCHEMA, request, terminalSequence, terminalAt);
        identity.put("result_ref", artifact.resultRef());
        identity.put("result_hash", artifact.graphResultSha256());
        return "IPFTR_" + ContractJson.sha256Hex(identity);
    }

    private String eventId(
            ExecuteAgentRunRequest request,
            long terminalSequence,
            Instant terminalAt,
            String finalReceiptId,
            ReadyArtifact artifact) {
        ObjectNode identity = identity(EVENT_SCHEMA, request, terminalSequence, terminalAt);
        identity.put("event_type", AgentStreamEventV4.EventType.FINAL.wireValue());
        identity.put("audience", request.command().actorScope().audience().name());
        identity.put("final_receipt_id", finalReceiptId);
        identity.put("final_result_hash", artifact.graphResultSha256());
        return ContractJson.sha256Hex(identity);
    }

    private ObjectNode identity(
            String schema,
            ExecuteAgentRunRequest request,
            long terminalSequence,
            Instant terminalAt) {
        ObjectNode identity = objectMapper.createObjectNode();
        identity.put("schema_version", schema);
        identity.put("run_id", request.agentRunId());
        identity.put("attempt_id", request.attemptId());
        identity.put("command_id", request.command().commandId());
        identity.put("request_hash", request.command().requestHash());
        identity.put("terminal_sequence", terminalSequence);
        identity.put("occurred_at", terminalAt.toString());
        return identity;
    }

    private String canonicalStoredJson(String value, String field) {
        try {
            JsonNode document = objectMapper.readTree(value);
            if (document == null) {
                throw conflict(
                        "INTAKE_PARALLEL_TERMINAL_AUDIENCE_CORRUPT",
                        field + " is empty JSON");
            }
            return ContractJson.canonicalString(document);
        } catch (JsonProcessingException failure) {
            throw conflict(
                    "INTAKE_PARALLEL_TERMINAL_AUDIENCE_CORRUPT",
                    field + " is not valid canonicalizable JSON");
        }
    }

    private String canonicalJson(Object value) {
        return ContractJson.canonicalString(objectMapper.valueToTree(value));
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw conflict(
                    "INTAKE_PARALLEL_TERMINAL_AUTHORITY_CONFLICT",
                    field + " differs from terminal authority");
        }
    }

    private static TerminalConflictException conflict(String code, String message) {
        return new TerminalConflictException(code, message);
    }

    private enum TerminalPhase {
        NEW(false),
        RESULT_READY_REPLAY(true),
        COMMITTED_REPLAY(true);

        private final boolean replay;

        TerminalPhase(boolean replay) {
            this.replay = replay;
        }

        boolean replay() {
            return replay;
        }
    }
}
