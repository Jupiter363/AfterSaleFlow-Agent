package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyContextResolver;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.FrameSetAuthority;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.CommandLookup;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.MaterialSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeParallelTurnContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Objects;

/** Resolves exact parallel turn material from the immutable admitted command artifact. */
public final class MaterializedIntakeParallelAssemblyContextResolver
        implements IntakeParallelAssemblyContextResolver {

    private static final String MISSING = "INTAKE_PARALLEL_CONTEXT_MISSING";
    private static final String CONFLICT = "INTAKE_PARALLEL_CONTEXT_CONFLICT";

    private final TargetIntakeCommandMaterialStore materialStore;
    private final ObjectMapper mapper;

    public MaterializedIntakeParallelAssemblyContextResolver(
            TargetIntakeCommandMaterialStore materialStore, ObjectMapper mapper) {
        this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    }

    @Override
    public TrustedTurnContext resolve(
            ExecuteAgentRunRequest request, FrameSetAuthority frameSetAuthority) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(frameSetAuthority, "frameSetAuthority");
        RoomGraphCommand command = request.command();
        if (!ExecuteAgentRunRequest.isParallelIntakeCommand(command)) {
            throw conflict("only the explicit parallel Intake profile may resolve turn context");
        }
        requireFrameSetMatchesRequest(request, frameSetAuthority);

        MaterialSnapshot material = materialStore
                .readByRoute(new CommandLookup(
                        command.tenantSurrogate(),
                        command.caseId(),
                        command.commandId(),
                        command.roomEpoch(),
                        frameSetAuthority.fencingToken()))
                .orElseThrow(() -> new AssemblyConflictException(
                        MISSING, "parallel Intake command material was not found"));
        requireMaterialMatchesRequest(material, request, frameSetAuthority);

        IntakeTargetAgentRunContext target = material.context().targetAgentRun();
        IntakeParallelTurnContext turn = target.parallelTurnContext();
        try {
            turn.requireMatches(command);
        } catch (IllegalArgumentException error) {
            throw conflict("frozen parallel turn context differs from graph command");
        }
        if (turn.cognitiveRevision()
                        != frameSetAuthority.eventAuthority().logicalSequence()
                || !turn.sourceEventSha256().equals(command.eventRef().sha256())
                || !turn.sourceSnapshotSha256().equals(command.domainSnapshotRef().sha256())
                || !turn.executionModel().equals(frameSetAuthority.modelProfileId())) {
            throw conflict("frozen parallel turn context differs from Frame-set authority");
        }
        return new TrustedTurnContext(
                turn.sourceMessageId(),
                turn.currentMessageText(),
                turn.cognitiveRevision(),
                turn.previousDossier(),
                turn.executionProvider(),
                turn.executionModel());
    }

    private void requireFrameSetMatchesRequest(
            ExecuteAgentRunRequest request, FrameSetAuthority authority) {
        RoomGraphCommand command = request.command();
        String actorScopeHash = ContractJson.sha256Hex(mapper.valueToTree(command.actorScope()));
        if (!request.agentRunId().equals(authority.runId())
                || !request.attemptId().equals(authority.attemptId())
                || !command.commandId().equals(authority.commandId())
                || !command.requestHash().equals(authority.commandRequestSha256())
                || !command.requestHash()
                        .equals(authority.eventAuthority().commandRequestSha256())
                || !command.tenantSurrogate().equals(authority.tenantSurrogate())
                || !command.caseId().equals(authority.caseId())
                || !command.threadId().equals(authority.threadId())
                || command.roomEpoch() != authority.roomEpoch()
                || !actorScopeHash.equals(authority.actorScopeSha256())
                || !command.invocationContext()
                        .modelProfileId()
                        .equals(authority.modelProfileId())
                || !sameInstant(command.deadlineAt(), authority.turnDeadlineAt())) {
            throw conflict("Frame-set authority differs from the current AgentRun request");
        }
    }

    private static void requireMaterialMatchesRequest(
            MaterialSnapshot material,
            ExecuteAgentRunRequest request,
            FrameSetAuthority authority) {
        if (material == null || material.admission() == null || material.context() == null) {
            throw conflict("parallel Intake command material is incomplete");
        }
        CommandAdmission admission = material.admission();
        IntakeTargetAgentRunContext target = material.context().targetAgentRun();
        if (target == null || target.parallelTurnContext() == null) {
            throw conflict("parallel Intake command material has no frozen turn context");
        }
        RoomGraphCommand command = request.command();
        if (!admission.tenantSurrogate().equals(authority.tenantSurrogate())
                || !admission.caseId().equals(authority.caseId())
                || !admission.commandId().equals(authority.commandId())
                || admission.roomEpoch() != authority.roomEpoch()
                || admission.roomFencingToken() != authority.fencingToken()
                || !admission.commandHash().equals(target.commandHash())
                || !admission.commandEnvelopeHash().equals(target.commandEnvelopeHash())
                || target.roomFencingToken() != authority.fencingToken()
                || !material.context().threadId().equals(authority.threadId())
                || !material.context().agentSessionId().equals(authority.agentSessionId())
                || material.context().deadlineEpochMillis() != command.deadlineAt().toEpochMilli()
                || !request.equals(target.request())) {
            throw conflict("immutable command material differs from Frame-set authority");
        }
    }

    private static boolean sameInstant(Instant left, Instant right) {
        return left.equals(right) || left.toEpochMilli() == right.toEpochMilli();
    }

    private static AssemblyConflictException conflict(String message) {
        return new AssemblyConflictException(CONFLICT, message);
    }
}
