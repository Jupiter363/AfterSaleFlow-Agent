package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyLookup;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Resolves V4's private resultRef by joining the public FINAL to its exact READY artifact. */
public final class IntakeParallelV4DurableFinalAuthorityResolver
        implements TargetE2eDurableFinalAuthorityResolver {

    private final TargetE2eV4FinalAuthoritySource terminalSource;
    private final IntakeParallelAssemblyStore assemblyStore;
    private final ObjectMapper objectMapper;

    public IntakeParallelV4DurableFinalAuthorityResolver(
            TargetE2eV4FinalAuthoritySource terminalSource,
            IntakeParallelAssemblyStore assemblyStore,
            ObjectMapper objectMapper) {
        this.terminalSource = Objects.requireNonNull(terminalSource, "terminalSource");
        this.assemblyStore = Objects.requireNonNull(assemblyStore, "assemblyStore");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Override
    public String requireResultRef(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        if (!ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                || !"agent-stream.v4".equals(request.streamProtocol())) {
            throw new IllegalStateException(
                    "agent-stream.v4 durable final requires the exact parallel Intake profile");
        }
        var terminal = terminalSource
                .load(request.agentRunId(), request.attemptId(), result.lastSequenceNo())
                .orElseThrow(() -> new IllegalStateException(
                        "target parallel AgentRun durable final is absent"));
        AgentStreamEventV4 event = terminal.event();
        AgentStreamEventV4.Payload payload = event.payload();
        boolean exactTerminal = event.eventType() == AgentStreamEventV4.EventType.FINAL
                && event.sequenceNo() == result.lastSequenceNo()
                && request.agentRunId().equals(event.runId())
                && request.attemptId().equals(event.attemptId())
                && request.command().actorScope().audience() == event.audience()
                && request.command().actorScope().actorId().equals(terminal.actorId())
                && payload.deliveryClass() == AgentStreamEventV4.DeliveryClass.DURABLE_TERMINAL
                && payload.finalReceiptId() != null
                && !payload.finalReceiptId().isBlank()
                && result.resultHash().equals(payload.finalResultHash())
                && terminal.durableHighWatermark() == result.lastSequenceNo();
        if (!exactTerminal) {
            throw new IllegalStateException(
                    "target parallel AgentRun durable final conflicts with completed result");
        }
        ReadyArtifact artifact = assemblyStore
                .loadReady(new ReadyLookup(
                        request.agentRunId(),
                        request.attemptId(),
                        request.command().commandId(),
                        request.command().requestHash()))
                .orElseThrow(() -> new IllegalStateException(
                        "target parallel AgentRun READY artifact is absent"));
        byte[] completedBytes = ContractJson.canonicalize(
                objectMapper.valueToTree(result.graphResult()));
        boolean exactArtifact = artifact.graphResultSha256().equals(result.resultHash())
                && result.resultHash().equals(result.graphResult().outputHash())
                && MessageDigest.isEqual(
                        artifact.canonicalGraphResultBytes(), completedBytes)
                && artifact.resultRef().equals(
                        "urn:target-e2e:result:intake:" + result.resultHash());
        if (!exactArtifact) {
            throw new IllegalStateException(
                    "target parallel AgentRun READY artifact conflicts with completed result");
        }
        return artifact.resultRef();
    }
}
