package com.example.dispute.workflow.application.intake.parallel;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.EventAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Technical exact-three assembly artifact store.
 *
 * <p>This boundary may freeze immutable Proposal/Graph artifacts and move one Frame set from
 * {@link AssemblyState#COLLECTING} to {@link AssemblyState#READY}. It must never write formal
 * Intake business state, a durable FINAL event, RESULT_READY, or a terminal receipt.
 */
public interface IntakeParallelAssemblyStore {

    ExactThreeInputs loadExactThree(AssemblyLookup lookup);

    ReadyReceipt publishReady(PublishReady command);

    Optional<ReadyArtifact> loadReady(ReadyLookup lookup);

    record AssemblyLookup(
            String frameSetId,
            String runId,
            String attemptId,
            String commandId,
            String commandRequestSha256) {

        public AssemblyLookup {
            frameSetId = identifier(frameSetId, "frameSetId");
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            commandId = identifier(commandId, "commandId");
            commandRequestSha256 = sha256(commandRequestSha256, "commandRequestSha256");
        }
    }

    record FrameSetAuthority(
            String frameSetId,
            String runId,
            String attemptId,
            String commandId,
            String commandRequestSha256,
            String tenantSurrogate,
            String caseId,
            String roomId,
            long roomEpoch,
            long fencingToken,
            String threadId,
            String actorScopeSha256,
            String agentSessionId,
            EventAuthority eventAuthority,
            String contextEnvelopeSha256,
            String modelContextViewSha256,
            String executionProfileId,
            String projectionRegistryVersion,
            String modelProfileId,
            Instant turnDeadlineAt,
            long frameSetVersion) {

        public FrameSetAuthority {
            frameSetId = identifier(frameSetId, "frameSetId");
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            commandId = identifier(commandId, "commandId");
            commandRequestSha256 = sha256(commandRequestSha256, "commandRequestSha256");
            tenantSurrogate = identifier(tenantSurrogate, "tenantSurrogate");
            caseId = identifier(caseId, "caseId");
            roomId = identifier(roomId, "roomId");
            nonNegative(roomEpoch, "roomEpoch");
            positive(fencingToken, "fencingToken");
            threadId = identifier(threadId, "threadId");
            actorScopeSha256 = sha256(actorScopeSha256, "actorScopeSha256");
            agentSessionId = identifier(agentSessionId, "agentSessionId");
            eventAuthority = Objects.requireNonNull(eventAuthority, "eventAuthority");
            contextEnvelopeSha256 = sha256(contextEnvelopeSha256, "contextEnvelopeSha256");
            modelContextViewSha256 = sha256(modelContextViewSha256, "modelContextViewSha256");
            if (!"PARALLEL_FRAMES_V1".equals(executionProfileId)) {
                throw new IllegalArgumentException("executionProfileId must be PARALLEL_FRAMES_V1");
            }
            projectionRegistryVersion = identifier(
                    projectionRegistryVersion, "projectionRegistryVersion");
            modelProfileId = identifier(modelProfileId, "modelProfileId");
            turnDeadlineAt = Objects.requireNonNull(turnDeadlineAt, "turnDeadlineAt");
            nonNegative(frameSetVersion, "frameSetVersion");
        }
    }

    record SealedFrameRecord(
            FrameType frameType,
            long generation,
            String frameId,
            String resultId,
            String canonicalResultJson,
            String resultSha256,
            String publicProjectionSha256,
            long nextLocalIndex,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long latencyMs,
            int providerCallCount) {

        public SealedFrameRecord {
            frameType = Objects.requireNonNull(frameType, "frameType");
            positive(generation, "generation");
            frameId = identifier(frameId, "frameId");
            resultId = identifier(resultId, "resultId");
            canonicalResultJson = bounded(canonicalResultJson, "canonicalResultJson", 262_144);
            resultSha256 = sha256(resultSha256, "resultSha256");
            publicProjectionSha256 = sha256(
                    publicProjectionSha256, "publicProjectionSha256");
            nonNegative(nextLocalIndex, "nextLocalIndex");
            nonNegative(inputTokens, "inputTokens");
            nonNegative(outputTokens, "outputTokens");
            nonNegative(totalTokens, "totalTokens");
            nonNegative(latencyMs, "latencyMs");
            if (totalTokens != Math.addExact(inputTokens, outputTokens)) {
                throw new IllegalArgumentException("totalTokens must equal inputTokens + outputTokens");
            }
            if (providerCallCount < 1 || providerCallCount > 2) {
                throw new IllegalArgumentException("providerCallCount must be 1 or 2");
            }
        }
    }

    record ExactThreeInputs(
            FrameSetAuthority authority, Map<FrameType, SealedFrameRecord> frames) {

        public ExactThreeInputs {
            authority = Objects.requireNonNull(authority, "authority");
            frames = exactFrames(frames, "frames");
            frames.forEach((type, frame) -> {
                if (frame.frameType() != type) {
                    throw new IllegalArgumentException("Frame map key differs from sealed authority");
                }
            });
        }
    }

    record SelectedFrameProof(
            FrameType frameType,
            long generation,
            String frameId,
            String resultId,
            String resultSha256,
            String publicProjectionSha256) {

        public SelectedFrameProof {
            frameType = Objects.requireNonNull(frameType, "frameType");
            positive(generation, "generation");
            frameId = identifier(frameId, "frameId");
            resultId = identifier(resultId, "resultId");
            resultSha256 = sha256(resultSha256, "resultSha256");
            publicProjectionSha256 = sha256(
                    publicProjectionSha256, "publicProjectionSha256");
        }

        public static SelectedFrameProof from(SealedFrameRecord frame) {
            Objects.requireNonNull(frame, "frame");
            return new SelectedFrameProof(
                    frame.frameType(),
                    frame.generation(),
                    frame.frameId(),
                    frame.resultId(),
                    frame.resultSha256(),
                    frame.publicProjectionSha256());
        }
    }

    record ReadyArtifact(
            String inputSetSha256,
            String proposalArtifactId,
            String proposalUri,
            String proposalSha256,
            byte[] canonicalProposalBytes,
            String profileManifestId,
            String resultArtifactId,
            String resultRef,
            String graphResultSha256,
            byte[] canonicalGraphResultBytes,
            byte[] canonicalCommandEnvelopeBytes,
            String commandEnvelopeSha256,
            byte[] canonicalProposalSourceBytes,
            String targetProposalSha256,
            byte[] canonicalResultEnvelopeBytes,
            String resultEnvelopeSha256,
            String checkpointNs,
            String registryBindingSha256,
            String toolPolicyVersion) {

        public ReadyArtifact {
            inputSetSha256 = sha256(inputSetSha256, "inputSetSha256");
            proposalSha256 = sha256(proposalSha256, "proposalSha256");
            proposalArtifactId = identifier(proposalArtifactId, "proposalArtifactId");
            String expectedProposalId = "intake.proposal." + proposalSha256.substring(0, 32);
            if (!expectedProposalId.equals(proposalArtifactId)) {
                throw new IllegalArgumentException("proposalArtifactId is not content addressed");
            }
            String expectedProposalUri = "urn:target-e2e:proposal:intake:" + proposalSha256;
            if (!expectedProposalUri.equals(proposalUri)) {
                throw new IllegalArgumentException("proposalUri is not canonical");
            }
            canonicalProposalBytes = bytes(canonicalProposalBytes, 65_536, "canonicalProposalBytes");
            profileManifestId = identifier(profileManifestId, "profileManifestId");
            graphResultSha256 = sha256(graphResultSha256, "graphResultSha256");
            resultArtifactId = identifier(resultArtifactId, "resultArtifactId");
            String expectedResultId = "intake.graph-result." + graphResultSha256.substring(0, 32);
            if (!expectedResultId.equals(resultArtifactId)) {
                throw new IllegalArgumentException("resultArtifactId is not content addressed");
            }
            String expectedResultRef = "urn:target-e2e:result:intake:" + graphResultSha256;
            if (!expectedResultRef.equals(resultRef)) {
                throw new IllegalArgumentException("resultRef is not canonical");
            }
            canonicalGraphResultBytes = bytes(
                    canonicalGraphResultBytes, 131_072, "canonicalGraphResultBytes");
            canonicalCommandEnvelopeBytes = bytes(
                    canonicalCommandEnvelopeBytes, 65_536, "canonicalCommandEnvelopeBytes");
            commandEnvelopeSha256 = sha256(commandEnvelopeSha256, "commandEnvelopeSha256");
            canonicalProposalSourceBytes = bytes(
                    canonicalProposalSourceBytes, 65_536, "canonicalProposalSourceBytes");
            targetProposalSha256 = sha256(targetProposalSha256, "targetProposalSha256");
            canonicalResultEnvelopeBytes = bytes(
                    canonicalResultEnvelopeBytes, 131_072, "canonicalResultEnvelopeBytes");
            resultEnvelopeSha256 = sha256(resultEnvelopeSha256, "resultEnvelopeSha256");
            checkpointNs = identifier(checkpointNs, "checkpointNs");
            registryBindingSha256 = sha256(registryBindingSha256, "registryBindingSha256");
            toolPolicyVersion = identifier(toolPolicyVersion, "toolPolicyVersion");
        }

        @Override
        public byte[] canonicalProposalBytes() {
            return canonicalProposalBytes.clone();
        }

        @Override
        public byte[] canonicalGraphResultBytes() {
            return canonicalGraphResultBytes.clone();
        }

        @Override
        public byte[] canonicalCommandEnvelopeBytes() {
            return canonicalCommandEnvelopeBytes.clone();
        }

        @Override
        public byte[] canonicalProposalSourceBytes() {
            return canonicalProposalSourceBytes.clone();
        }

        @Override
        public byte[] canonicalResultEnvelopeBytes() {
            return canonicalResultEnvelopeBytes.clone();
        }
    }

    record PublishReady(
            AssemblyLookup lookup,
            long expectedFrameSetVersion,
            Map<FrameType, SelectedFrameProof> selectedFrames,
            ReadyArtifact artifact) {

        public PublishReady {
            lookup = Objects.requireNonNull(lookup, "lookup");
            nonNegative(expectedFrameSetVersion, "expectedFrameSetVersion");
            selectedFrames = exactFrames(selectedFrames, "selectedFrames");
            selectedFrames.forEach((type, proof) -> {
                if (proof.frameType() != type) {
                    throw new IllegalArgumentException("selected Frame proof has a foreign type");
                }
            });
            artifact = Objects.requireNonNull(artifact, "artifact");
        }
    }

    record ReadyLookup(
            String runId, String attemptId, String commandId, String commandRequestSha256) {

        public ReadyLookup {
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            commandId = identifier(commandId, "commandId");
            commandRequestSha256 = sha256(commandRequestSha256, "commandRequestSha256");
        }
    }

    record ReadyReceipt(
            boolean inserted,
            AssemblyState state,
            long frameSetVersion,
            ReadyArtifact artifact) {

        public ReadyReceipt {
            state = Objects.requireNonNull(state, "state");
            if (state != AssemblyState.READY && state != AssemblyState.COMMITTED) {
                throw new IllegalArgumentException("ready receipt requires READY or COMMITTED");
            }
            nonNegative(frameSetVersion, "frameSetVersion");
            artifact = Objects.requireNonNull(artifact, "artifact");
        }
    }

    final class AssemblyConflictException extends IllegalStateException {
        private final String code;

        public AssemblyConflictException(String code, String message) {
            super(message);
            this.code = identifier(code, "code");
        }

        public String code() {
            return code;
        }
    }

    Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static String bounded(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " must contain 1.." + maximum + " characters");
        }
        return value;
    }

    private static byte[] bytes(byte[] value, int maximum, String field) {
        if (value == null || value.length < 2 || value.length > maximum) {
            throw new IllegalArgumentException(field + " size is invalid");
        }
        return value.clone();
    }

    private static void positive(long value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }

    private static <T> Map<FrameType, T> exactFrames(
            Map<FrameType, T> supplied, String field) {
        Objects.requireNonNull(supplied, field);
        if (!supplied.keySet().equals(Set.of(FrameType.values()))) {
            throw new IllegalArgumentException(field + " must contain the exact three Frame types");
        }
        EnumMap<FrameType, T> copy = new EnumMap<>(FrameType.class);
        for (FrameType type : FrameType.values()) {
            copy.put(type, Objects.requireNonNull(supplied.get(type), type.name()));
        }
        return Map.copyOf(copy);
    }
}
