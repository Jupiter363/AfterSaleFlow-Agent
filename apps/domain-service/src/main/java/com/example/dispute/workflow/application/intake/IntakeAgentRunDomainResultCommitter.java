package com.example.dispute.workflow.application.intake;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.Objects;

/**
 * Intake room writer selected by the existing outer AgentRun formal transaction.
 *
 * <p>This adapter has no framework stereotype. The outer committer supplies the canonical
 * execution manifest, this adapter commits the room-owned facts and operation receipt, and the
 * manifest store then terminalizes AgentRun in the same transaction.
 */
public final class IntakeAgentRunDomainResultCommitter
        implements AgentRunDomainResultCommitter {

    private final IntakeAgentRunFinalizationRequestResolver requestResolver;
    private final IntakeGraphResultFinalizer finalizer;
    private final String expectedGraphKey;

    public IntakeAgentRunDomainResultCommitter(
            IntakeAgentRunFinalizationRequestResolver requestResolver,
            IntakeGraphResultFinalizer finalizer) {
        this(requestResolver, finalizer, IntakeGraphResultFinalizer.LEGACY_GRAPH_KEY);
    }

    public IntakeAgentRunDomainResultCommitter(
            IntakeAgentRunFinalizationRequestResolver requestResolver,
            IntakeGraphResultFinalizer finalizer,
            String expectedGraphKey) {
        this.requestResolver = Objects.requireNonNull(requestResolver, "requestResolver");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
        if (!IntakeGraphResultFinalizer.LEGACY_GRAPH_KEY.equals(expectedGraphKey)
                && !IntakeGraphResultFinalizer.TARGET_E2E_GRAPH_KEY.equals(expectedGraphKey)) {
            throw new IllegalArgumentException("expectedGraphKey is not an allowed Intake graph");
        }
        this.expectedGraphKey = expectedGraphKey;
    }

    @Override
    public boolean supports(RoomType roomType, String graphKey) {
        return roomType == RoomType.INTAKE && expectedGraphKey.equals(graphKey);
    }

    @Override
    public CommitReceipt commit(CommitCommand command) {
        Objects.requireNonNull(command, "command");
        IntakeGraphFinalizationRequest request = Objects.requireNonNull(
                requestResolver.resolve(command),
                "Intake finalization request resolver returned null");
        requireOuterCommitBinding(command, request);
        IntakeFinalizationReceipt receipt = finalizer.finalizeResult(request);
        var actor = request.threadBinding().registration().actorScope();
        return new CommitReceipt(
                receipt.formalMessageId(),
                receipt.caseId(),
                receipt.roomEpoch(),
                receipt.processRevision(),
                request.authority().stageCode(),
                request.authority().stageSequence(),
                actor.actorId(),
                actor.actorRole(),
                actor.audience(),
                receipt.fencingToken(),
                receipt.resultHash());
    }

    private void requireOuterCommitBinding(
            CommitCommand outer, IntakeGraphFinalizationRequest request) {
        var executeRequest = outer.request();
        var executeResult = outer.result();
        AgentExecutionManifest manifest = outer.manifest();
        var authority = request.authority();
        if (!executeRequest.command().equals(request.command())
                || !executeResult.graphResult().equals(request.result())
                || !executeRequest.agentRunId().equals(authority.logicalRunId())
                || !executeRequest.logicalRunId().equals(authority.logicalRunId())
                || !executeRequest.attemptId().equals(authority.attemptId())
                || !executeResult.agentRunId().equals(authority.logicalRunId())
                || !executeResult.logicalRunId().equals(authority.logicalRunId())
                || !executeResult.attemptId().equals(authority.attemptId())
                || !executeResult.resultHash().equals(authority.resultHash())
                || !manifest.tenantSurrogate().equals(authority.tenantSurrogate())
                || !manifest.caseId().equals(authority.caseId())
                || manifest.roomEpoch() != authority.roomEpoch()
                || manifest.processRevision() != authority.processRevision()
                || manifest.fencingToken() != authority.fencingToken()
                || !manifest.agentRun().logicalRunId().equals(authority.logicalRunId())
                || !manifest.agentRun().attemptId().equals(authority.attemptId())
                || !manifest.graph().graphKey().equals(expectedGraphKey)
                || !manifest.graph().graphVersion()
                        .equals(authority.profileVersions().graphVersion())
                || !manifest.graph().checkpointSchemaVersion()
                        .equals(authority.profileVersions().checkpointSchemaVersion())
                || !manifest.graph().checkpointId().equals(authority.checkpointId())
                || manifest.graph().cognitiveRevision() != authority.cognitiveRevision()
                || !manifest.model().requestHash().equals(request.command().requestHash())
                || !manifest.model().promptVersion()
                        .equals(authority.profileVersions().promptVersion())
                || !manifest.model().modelProfileId()
                        .equals(authority.profileVersions().modelProfileId())
                || !manifest.model().responseHash().equals(authority.resultHash())
                || !manifest.output().sha256().equals(authority.resultHash())
                || !manifest.policyVersion().equals(authority.profileVersions().policyVersion())
                || !manifest.guardrailVersion()
                        .equals(authority.profileVersions().guardrailVersion())) {
            throw new IntakeFinalizationRejectedException(
                    "INTAKE_AGENT_RUN_OUTER_COMMIT_MISMATCH",
                    "outer AgentRun commit does not match the trusted Intake finalization request");
        }
    }
}
