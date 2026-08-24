package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal.ProfileVersions;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyContextResolver;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyContextResolver.TrustedTurnContext;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyLookup;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ExactThreeInputs;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.PublishReady;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyLookup;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.SelectedFrameProof;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAssembler;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAssembler.AssemblyCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAssembler.AssemblyOutput;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAssembler.SealedFrame;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Java-owned exact-three coordinator for the target Intake parallel execution profile.
 *
 * <p>This component performs technical assembly only. It may publish immutable READY artifacts,
 * but it never appends the durable FINAL event, advances an AgentRun to RESULT_READY, or writes
 * formal Intake business state.
 */
public final class TargetE2EIntakeParallelAssemblyCoordinator {

    public static final String AGENT_PROFILE_ID =
            ExecuteAgentRunRequest.PARALLEL_INTAKE_AGENT_PROFILE_ID;
    public static final String EXECUTION_PROFILE_ID = "PARALLEL_FRAMES_V1";
    public static final String EXECUTION_OUTPUT_SCHEMA =
            ExecuteAgentRunRequest.PARALLEL_INTAKE_OUTPUT_SCHEMA;
    private static final String PROPOSAL_PAYLOAD_SCHEMA = "intake-turn-proposal.v2";
    private static final String TARGET_PROPOSAL_SCHEMA = "target-e2e-intake-proposal.v1";
    private static final String READY_RACE_CODE = "INTAKE_PARALLEL_ASSEMBLY_NOT_COLLECTING";

    private final String activationId;
    private final TargetE2EAgentRunIdentityResolver identityResolver;
    private final GraphRegistryBindingPolicy registryBindingPolicy;
    private final IntakeParallelAssemblyContextResolver contextResolver;
    private final IntakeParallelAssemblyStore assemblyStore;
    private final IntakeParallelFrameAssembler assembler;
    private final TargetE2EGraphEnvelopeCodec envelopeCodec;
    private final ObjectMapper mapper;

    public TargetE2EIntakeParallelAssemblyCoordinator(
            String activationId,
            TargetE2EAgentRunIdentityResolver identityResolver,
            GraphRegistryBindingPolicy registryBindingPolicy,
            IntakeParallelAssemblyContextResolver contextResolver,
            IntakeParallelAssemblyStore assemblyStore,
            IntakeParallelFrameAssembler assembler,
            TargetE2EGraphEnvelopeCodec envelopeCodec,
            ObjectMapper objectMapper) {
        TargetE2EGraphCommandEnvelope.requirePattern(
                activationId, TargetE2EGraphCommandEnvelope.ACTIVATION_ID, "activationId");
        this.activationId = activationId;
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.registryBindingPolicy =
                Objects.requireNonNull(registryBindingPolicy, "registryBindingPolicy");
        this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver");
        this.assemblyStore = Objects.requireNonNull(assemblyStore, "assemblyStore");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.envelopeCodec = Objects.requireNonNull(envelopeCodec, "envelopeCodec");
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    public AssemblyResult assembleReady(
            ExecuteAgentRunRequest request,
            String frameSetId,
            AgentRunCancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken")
                .throwIfCancellationRequested();
        RoomGraphCommand command = requireParallelRequest(request);
        long roomFencingToken = Objects.requireNonNull(
                        identityResolver.resolve(request),
                        "durable AgentRun identity resolver returned no identity")
                .requireExact(request);
        GraphRegistryBindingPolicy.ExpectedBinding registryBinding =
                GraphRegistryBindingPolicy.requireExpected(
                        registryBindingPolicy, GraphStreamVisibilityPolicy.Binding.from(command));
        ReadyLookup readyLookup = new ReadyLookup(
                request.agentRunId(),
                request.attemptId(),
                command.commandId(),
                command.requestHash());
        Optional<ReadyArtifact> replay = assemblyStore.loadReady(readyLookup);
        if (replay.isPresent()) {
            return replayResult(
                    request,
                    roomFencingToken,
                    registryBinding,
                    replay.orElseThrow());
        }

        AssemblyLookup lookup = new AssemblyLookup(
                frameSetId,
                request.agentRunId(),
                request.attemptId(),
                command.commandId(),
                command.requestHash());
        ExactThreeInputs inputs;
        try {
            inputs = assemblyStore.loadExactThree(lookup);
        } catch (AssemblyConflictException race) {
            if (!READY_RACE_CODE.equals(race.code())) {
                throw race;
            }
            ReadyArtifact raced = assemblyStore.loadReady(readyLookup).orElseThrow(() -> race);
            return replayResult(
                    request, roomFencingToken, registryBinding, raced);
        }
        requireRequestAuthority(request, roomFencingToken, inputs);
        TrustedTurnContext context = Objects.requireNonNull(
                contextResolver.resolve(request, inputs.authority()),
                "parallel Intake context resolver returned no context");
        cancellationToken.throwIfCancellationRequested();

        AssemblyOutput output = assembler.assemble(assemblyCommand(
                request, inputs, context, registryBinding));
        ReadyArtifact artifact = readyArtifact(
                command,
                roomFencingToken,
                inputs,
                context,
                registryBinding,
                output);
        var receipt = assemblyStore.publishReady(new PublishReady(
                lookup,
                inputs.authority().frameSetVersion(),
                selectedFrames(inputs),
                artifact));
        return new AssemblyResult(
                receipt.inserted(), receipt.artifact(), output.graphResult());
    }

    private AssemblyResult replayResult(
            ExecuteAgentRunRequest request,
            long roomFencingToken,
            GraphRegistryBindingPolicy.ExpectedBinding registryBinding,
            ReadyArtifact artifact) {
        TargetE2EGraphCommandEnvelope storedCommand =
                envelopeCodec.decodeCommand(artifact.canonicalCommandEnvelopeBytes());
        if (!activationId.equals(storedCommand.activationId())
                || roomFencingToken != storedCommand.roomFencingToken()
                || !request.command().equals(storedCommand.command())
                || !artifact.commandEnvelopeSha256().equals(
                        storedCommand.commandEnvelopeHash())
                || !artifact.registryBindingSha256().equals(
                        registryBinding.registryBindingHash())
                || !artifact.toolPolicyVersion().equals(
                        registryBinding.toolPolicyVersion())) {
            throw new AssemblyConflictException(
                    "INTAKE_PARALLEL_READY_REQUEST_CONFLICT",
                    "READY artifact differs from the current command or registry authority");
        }
        byte[] proposalSource = envelopeCodec.validateProposalSource(
                artifact.canonicalProposalSourceBytes(),
                request.command(),
                artifact.targetProposalSha256());
        TargetE2EGraphResultEnvelope resultEnvelope = envelopeCodec.decodeResult(
                artifact.canonicalResultEnvelopeBytes(), storedCommand, proposalSource);
        if (!artifact.graphResultSha256().equals(resultEnvelope.resultHash())
                || !artifact.graphResultSha256().equals(resultEnvelope.result().outputHash())
                || !artifact.resultEnvelopeSha256().equals(
                        resultEnvelope.resultEnvelopeHash())) {
            throw new AssemblyConflictException(
                    "INTAKE_PARALLEL_READY_RESULT_CONFLICT",
                    "READY result envelope differs from immutable artifact authority");
        }
        return new AssemblyResult(false, artifact, resultEnvelope.result());
    }

    private AssemblyCommand assemblyCommand(
            ExecuteAgentRunRequest request,
            ExactThreeInputs inputs,
            TrustedTurnContext context,
            GraphRegistryBindingPolicy.ExpectedBinding registryBinding) {
        RoomGraphCommand command = request.command();
        var authority = inputs.authority();
        var invocation = command.invocationContext();
        var profiles = new ProfileVersions(
                command.graphVersion(),
                command.checkpointSchemaVersion(),
                invocation.promptProfileId(),
                invocation.modelProfileId(),
                PROPOSAL_PAYLOAD_SCHEMA,
                invocation.policyVersion(),
                invocation.guardrailVersion(),
                registryBinding.toolPolicyVersion());
        return new AssemblyCommand(
                command.commandId(),
                request.logicalRunId(),
                request.attemptId(),
                command.caseId(),
                command.roomEpoch(),
                command.threadId(),
                command.actorScope().actorRole().name(),
                authority.actorScopeSha256(),
                authority.agentSessionId(),
                context.cognitiveRevision(),
                command.domainSnapshotRef().sha256(),
                Objects.requireNonNull(command.eventRef(), "parallel Intake eventRef")
                        .sha256(),
                context.sourceMessageId(),
                context.currentMessageText(),
                authority.eventAuthority().eventBindingId(),
                authority.eventAuthority().bindingGeneration(),
                authority.eventAuthority().authorityVersion(),
                authority.contextEnvelopeSha256(),
                authority.modelContextViewSha256(),
                authority.executionProfileId(),
                command.graphKey(),
                invocation.outputSchemaVersion(),
                profiles,
                context.previousDossier(),
                sealedFrames(inputs));
    }

    private ReadyArtifact readyArtifact(
            RoomGraphCommand command,
            long roomFencingToken,
            ExactThreeInputs inputs,
            TrustedTurnContext context,
            GraphRegistryBindingPolicy.ExpectedBinding registryBinding,
            AssemblyOutput output) {
        TargetE2EGraphCommandEnvelope commandEnvelope =
                envelopeCodec.wrapCommand(activationId, roomFencingToken, command);
        byte[] commandEnvelopeBytes = envelopeCodec.encodeCommand(commandEnvelope);
        TargetE2ERoomProposalSource proposalSource = proposalSource(command, output);
        JsonNode proposalSourceNode = mapper.valueToTree(proposalSource);
        byte[] proposalSourceBytes = ContractJson.canonicalize(proposalSourceNode);
        String targetProposalSha256 =
                ContractJson.sha256Hex(proposalSourceNode.required("proposal"));
        TargetE2EGraphResultEnvelope resultEnvelope = envelopeCodec.wrapResult(
                commandEnvelope,
                output.graphResult(),
                proposalSourceNode,
                context.executionProvider(),
                context.executionModel());
        byte[] resultEnvelopeBytes = envelopeCodec.encodeResult(
                resultEnvelope, commandEnvelope, proposalSourceNode);
        String graphResultHash = output.graphResult().outputHash();
        String resultArtifactId =
                "intake.graph-result." + graphResultHash.substring(0, 32);
        String resultRef = "urn:target-e2e:result:intake:" + graphResultHash;
        return new ReadyArtifact(
                output.inputSetSha256(),
                output.artifactId(),
                output.artifactUri(),
                output.proposalSha256(),
                output.canonicalProposalBytes(),
                profileManifestId(command, inputs, registryBinding),
                resultArtifactId,
                resultRef,
                graphResultHash,
                output.canonicalGraphResultBytes(),
                commandEnvelopeBytes,
                commandEnvelope.commandEnvelopeHash(),
                proposalSourceBytes,
                targetProposalSha256,
                resultEnvelopeBytes,
                resultEnvelope.resultEnvelopeHash(),
                checkpointNamespace(command),
                registryBinding.registryBindingHash(),
                registryBinding.toolPolicyVersion());
    }

    private static TargetE2ERoomProposalSource proposalSource(
            RoomGraphCommand command, AssemblyOutput output) {
        String targetProposalId =
                "target-proposal." + output.proposalSha256().substring(0, 32);
        return new TargetE2ERoomProposalSource(
                TargetE2ERoomProposalSource.SCHEMA_VERSION,
                RoomType.INTAKE,
                new TargetE2ERoomProposalSource.Proposal(
                        TARGET_PROPOSAL_SCHEMA,
                        targetProposalId,
                        command.commandId(),
                        command.logicalRunId(),
                        command.attemptId(),
                        PROPOSAL_PAYLOAD_SCHEMA,
                        output.artifactUri(),
                        output.proposalSha256(),
                        TargetE2ERoomProposalSource.TerminalClass.COMPLETED,
                        false));
    }

    private String profileManifestId(
            RoomGraphCommand command,
            ExactThreeInputs inputs,
            GraphRegistryBindingPolicy.ExpectedBinding registryBinding) {
        ObjectNode manifest = JsonNodeFactory.instance.objectNode();
        manifest.put("schema_version", "intake.parallel-profile-manifest.v1");
        manifest.put("activation_id", activationId);
        manifest.put("execution_profile_id", inputs.authority().executionProfileId());
        manifest.put("graph_version", command.graphVersion());
        manifest.put("checkpoint_schema_version", command.checkpointSchemaVersion());
        manifest.put("prompt_profile_id", command.invocationContext().promptProfileId());
        manifest.put("model_profile_id", command.invocationContext().modelProfileId());
        manifest.put("output_schema_version", command.invocationContext().outputSchemaVersion());
        manifest.put("policy_version", command.invocationContext().policyVersion());
        manifest.put("guardrail_version", command.invocationContext().guardrailVersion());
        manifest.put(
                "projection_registry_version",
                inputs.authority().projectionRegistryVersion());
        manifest.put("registry_binding_sha256", registryBinding.registryBindingHash());
        manifest.put("tool_policy_version", registryBinding.toolPolicyVersion());
        return "IPMF_" + ContractJson.sha256Hex(manifest).substring(0, 32);
    }

    private static String checkpointNamespace(RoomGraphCommand command) {
        ObjectNode identity = JsonNodeFactory.instance.objectNode();
        identity.put("schema_version", "intake.parallel-checkpoint-namespace.v1");
        identity.put("thread_id", command.threadId());
        identity.put("attempt_id", command.attemptId());
        identity.put("request_hash", command.requestHash());
        return "ipckns." + ContractJson.sha256Hex(identity).substring(0, 32);
    }

    private static Map<FrameType, SealedFrame> sealedFrames(ExactThreeInputs inputs) {
        EnumMap<FrameType, SealedFrame> frames = new EnumMap<>(FrameType.class);
        inputs.frames().forEach((type, frame) -> frames.put(
                type,
                new SealedFrame(
                        type,
                        frame.generation(),
                        frame.frameId(),
                        frame.canonicalResultJson(),
                        frame.resultSha256(),
                        frame.publicProjectionSha256(),
                        frame.nextLocalIndex(),
                        frame.inputTokens(),
                        frame.outputTokens())));
        return Map.copyOf(frames);
    }

    private static Map<FrameType, SelectedFrameProof> selectedFrames(
            ExactThreeInputs inputs) {
        EnumMap<FrameType, SelectedFrameProof> proofs = new EnumMap<>(FrameType.class);
        inputs.frames().forEach((type, frame) ->
                proofs.put(type, SelectedFrameProof.from(frame)));
        return Map.copyOf(proofs);
    }

    private static RoomGraphCommand requireParallelRequest(ExecuteAgentRunRequest request) {
        RoomGraphCommand command = request.command();
        var invocation = command.invocationContext();
        boolean exact = command.roomType() == RoomType.INTAKE
                && AGENT_PROFILE_ID.equals(invocation.agentProfileId())
                && EXECUTION_OUTPUT_SCHEMA.equals(invocation.outputSchemaVersion())
                && command.eventRef() != null
                && (command.actorScope().actorRole() == ActorRole.USER
                        || command.actorScope().actorRole() == ActorRole.MERCHANT);
        if (!exact) {
            throw new AssemblyConflictException(
                    "INTAKE_PARALLEL_PROFILE_INVALID",
                    "parallel Intake assembly requires the explicit ROOM_MESSAGE profile");
        }
        return command;
    }

    private static void requireRequestAuthority(
            ExecuteAgentRunRequest request,
            long roomFencingToken,
            ExactThreeInputs inputs) {
        RoomGraphCommand command = request.command();
        var authority = inputs.authority();
        boolean exact = request.agentRunId().equals(authority.runId())
                && request.attemptId().equals(authority.attemptId())
                && command.commandId().equals(authority.commandId())
                && command.requestHash().equals(authority.commandRequestSha256())
                && command.tenantSurrogate().equals(authority.tenantSurrogate())
                && command.caseId().equals(authority.caseId())
                && command.roomEpoch() == authority.roomEpoch()
                && roomFencingToken == authority.fencingToken()
                && command.threadId().equals(authority.threadId())
                && command.invocationContext().modelProfileId().equals(authority.modelProfileId())
                && EXECUTION_PROFILE_ID.equals(authority.executionProfileId())
                && command.deadlineAt().truncatedTo(ChronoUnit.MICROS)
                        .equals(authority.turnDeadlineAt());
        if (!exact) {
            throw new AssemblyConflictException(
                    "INTAKE_PARALLEL_COMMAND_AUTHORITY_CONFLICT",
                    "Frame set differs from the immutable execution request");
        }
    }

    public record AssemblyResult(
            boolean newlyPublished,
            ReadyArtifact artifact,
            RoomGraphResult graphResult) {

        public AssemblyResult {
            artifact = Objects.requireNonNull(artifact, "artifact");
            graphResult = Objects.requireNonNull(graphResult, "graphResult");
            if (!artifact.graphResultSha256().equals(graphResult.outputHash())) {
                throw new IllegalArgumentException(
                        "assembly result differs from immutable Graph artifact");
            }
        }
    }
}
