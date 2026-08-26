package com.example.dispute.workflow.application.intake.parallel;

import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Technical, attempt-scoped staging boundary for the three-Frame Intake execution profile.
 *
 * <p>Implementations may persist public provisional stream events, immutable per-Frame results,
 * and the current per-Frame slot. They must never write the formal dossier, room message, Intake
 * phase, command completion, or terminal business receipt.
 */
public interface IntakeParallelFrameStagingPort {

    String RETRY_VALIDATION_PATH = "$";

    FrameSetReceipt admit(FrameSetAdmission admission);

    IngressReceipt append(IngressCommand command);

    FrameSealReceipt seal(FrameSealCommand command);

    FrameRetryReceipt admitRetry(FrameRetryAdmission admission);

    ExecutionPlan planExecution(FrameSetAdmission admission);

    PublishedAdmissionReceipt publishAdmissionReceipt(
            AdmissionReceiptPublication publication);

    Optional<PublishedAdmissionReceipt> findCurrentAdmissionReceipt(
            AdmissionReceiptLookup lookup);

    AbandonmentReceipt applyAbandonment(AbandonmentApplication application);

    Optional<AssemblyView> findAssembly(String frameSetId);

    Optional<ExactThreeCompletion> findExactThreeCompletion(
            String frameSetId, String runId, String attemptId);

    enum FrameType {
        DIALOGUE_FRAME("intake_turn_dialogue_frame", "intake-dialogue-frame.v2"),
        DOSSIER_FRAME("intake_turn_dossier_frame", "intake-dossier-frame.v2"),
        QUALITY_FRAME("intake_turn_quality_frame", "intake-quality-frame.v1");

        private final String promptProfileId;
        private final String outputSchemaId;

        FrameType(String promptProfileId, String outputSchemaId) {
            this.promptProfileId = promptProfileId;
            this.outputSchemaId = outputSchemaId;
        }

        public String promptProfileId() {
            return promptProfileId;
        }

        public String outputSchemaId() {
            return outputSchemaId;
        }
    }

    enum AssemblyState {
        COLLECTING,
        READY,
        COMMITTED,
        FAILED_UNCOMMITTED
    }

    enum SlotState {
        ADMITTED,
        STARTED,
        SEALED,
        FAILED,
        AMBIGUOUS
    }

    enum ExecutionAction {
        SKIP_SEALED,
        RUN_CURRENT,
        RUN_RETRY
    }

    enum IngressKind {
        PUBLIC_FRAME_START(AgentStreamEventV4.EventType.PUBLIC_FRAME_START, false),
        PUBLIC_FRAME_PROJECTION_ITEM(
                AgentStreamEventV4.EventType.PUBLIC_FRAME_PROJECTION_ITEM, true),
        ACTIVE_FRAME_SNAPSHOT(AgentStreamEventV4.EventType.ACTIVE_FRAME_SNAPSHOT, false),
        FRAME_GENERATION_RESET(AgentStreamEventV4.EventType.FRAME_GENERATION_RESET, false),
        PUBLIC_FRAME_SEALED(AgentStreamEventV4.EventType.PUBLIC_FRAME_SEALED, false),
        PUBLIC_FRAME_INTERRUPTED(
                AgentStreamEventV4.EventType.PUBLIC_FRAME_INTERRUPTED, false),
        USAGE(AgentStreamEventV4.EventType.USAGE, false);

        private final AgentStreamEventV4.EventType publicEventType;
        private final boolean requiresLocalIndex;

        IngressKind(
                AgentStreamEventV4.EventType publicEventType, boolean requiresLocalIndex) {
            this.publicEventType = publicEventType;
            this.requiresLocalIndex = requiresLocalIndex;
        }

        public AgentStreamEventV4.EventType publicEventType() {
            return publicEventType;
        }

        public boolean requiresLocalIndex() {
            return requiresLocalIndex;
        }
    }

    record EventAuthority(
            String eventBindingId,
            String threadRegistrationId,
            long logicalSequence,
            long bindingGeneration,
            long authorityVersion,
            String commandRequestSha256) {

        public EventAuthority {
            eventBindingId = identifier(eventBindingId, "eventBindingId");
            threadRegistrationId = identifier(threadRegistrationId, "threadRegistrationId");
            commandRequestSha256 = sha256(commandRequestSha256, "commandRequestSha256");
            positive(logicalSequence, "logicalSequence");
            positive(bindingGeneration, "bindingGeneration");
            nonNegative(authorityVersion, "authorityVersion");
        }
    }

    record FrameManifest(
            FrameType frameType,
            long generation,
            String frameId,
            String promptProfileId,
            String outputSchemaId,
            String modelProfileId,
            String frameModelInputSha256,
            String framePromptSha256) {

        public FrameManifest {
            frameType = Objects.requireNonNull(frameType, "frameType");
            positive(generation, "generation");
            wireInteger(generation, "generation");
            frameId = identifier(frameId, "frameId");
            promptProfileId = identifier(promptProfileId, "promptProfileId");
            outputSchemaId = identifier(outputSchemaId, "outputSchemaId");
            modelProfileId = identifier(modelProfileId, "modelProfileId");
            frameModelInputSha256 = sha256(frameModelInputSha256, "frameModelInputSha256");
            framePromptSha256 = sha256(framePromptSha256, "framePromptSha256");
            if (!frameType.promptProfileId().equals(promptProfileId)) {
                throw new IllegalArgumentException(
                        "promptProfileId does not match " + frameType);
            }
            if (!frameType.outputSchemaId().equals(outputSchemaId)) {
                throw new IllegalArgumentException(
                        "outputSchemaId does not match " + frameType);
            }
        }
    }

    record FrameSetAdmission(
            String frameSetId,
            String runId,
            String attemptId,
            String commandId,
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
            List<FrameManifest> manifests) {

        public FrameSetAdmission {
            frameSetId = identifier(frameSetId, "frameSetId");
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            commandId = identifier(commandId, "commandId");
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
            executionProfileId = identifier(executionProfileId, "executionProfileId");
            projectionRegistryVersion =
                    identifier(projectionRegistryVersion, "projectionRegistryVersion");
            modelProfileId = identifier(modelProfileId, "modelProfileId");
            turnDeadlineAt = Objects.requireNonNull(turnDeadlineAt, "turnDeadlineAt")
                    .truncatedTo(ChronoUnit.MICROS);
            manifests = List.copyOf(Objects.requireNonNull(manifests, "manifests"));
            if (!"PARALLEL_FRAMES_V1".equals(executionProfileId)) {
                throw new IllegalArgumentException(
                        "parallel Frame staging requires PARALLEL_FRAMES_V1");
            }
            if (manifests.size() != FrameType.values().length) {
                throw new IllegalArgumentException("admission requires exactly three manifests");
            }
            Set<FrameType> observed = new HashSet<>();
            Set<String> frameIds = new HashSet<>();
            for (FrameManifest manifest : manifests) {
                if (!observed.add(manifest.frameType())) {
                    throw new IllegalArgumentException(
                            "admission contains duplicate Frame type " + manifest.frameType());
                }
                if (!frameIds.add(manifest.frameId())) {
                    throw new IllegalArgumentException("admission contains duplicate frameId");
                }
                if (manifest.generation() != 1) {
                    throw new IllegalArgumentException(
                            "initial exact-three admission requires generation 1");
                }
                if (!modelProfileId.equals(manifest.modelProfileId())) {
                    throw new IllegalArgumentException(
                            "Frame manifest modelProfileId drifted from admission");
                }
            }
            if (observed.size() != FrameType.values().length) {
                throw new IllegalArgumentException("admission is missing a required Frame type");
            }
        }

        public Map<FrameType, FrameManifest> manifestsByType() {
            Map<FrameType, FrameManifest> indexed = new EnumMap<>(FrameType.class);
            for (FrameManifest manifest : manifests) {
                indexed.put(manifest.frameType(), manifest);
            }
            return Map.copyOf(indexed);
        }
    }

    record FrameSetReceipt(
            String frameSetId,
            boolean inserted,
            String receiptId,
            AssemblyState assemblyState) {

        public FrameSetReceipt {
            frameSetId = identifier(frameSetId, "frameSetId");
            receiptId = identifier(receiptId, "receiptId");
            assemblyState = Objects.requireNonNull(assemblyState, "assemblyState");
        }
    }

    record AdmissionReceiptPublication(
            FrameSetAdmission admission,
            FrameSetReceipt frameSetReceipt,
            ExecutionPlan executionPlan,
            String encodedReceipt,
            String receiptSha256) {

        public AdmissionReceiptPublication {
            admission = Objects.requireNonNull(admission, "admission");
            frameSetReceipt = Objects.requireNonNull(frameSetReceipt, "frameSetReceipt");
            executionPlan = Objects.requireNonNull(executionPlan, "executionPlan");
            encodedReceipt = bounded(encodedReceipt, "encodedReceipt", 16 * 1024);
            receiptSha256 = sha256(receiptSha256, "receiptSha256");
            if (!admission.frameSetId().equals(frameSetReceipt.frameSetId())
                    || !admission.frameSetId().equals(executionPlan.frameSetId())
                    || !admission.runId().equals(executionPlan.runId())
                    || !admission.attemptId().equals(executionPlan.attemptId())) {
                throw new IllegalArgumentException(
                        "parallel admission receipt publication crossed execution authority");
            }
        }
    }

    record AdmissionReceiptLookup(
            String runId,
            String attemptId,
            String commandId,
            String commandRequestSha256) {

        public AdmissionReceiptLookup {
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            commandId = identifier(commandId, "commandId");
            commandRequestSha256 = sha256(commandRequestSha256, "commandRequestSha256");
        }
    }

    record PublishedAdmissionReceipt(
            String frameSetId,
            String runId,
            String attemptId,
            String commandId,
            String commandRequestSha256,
            long receiptGeneration,
            String encodedReceipt,
            String receiptSha256) {

        public PublishedAdmissionReceipt {
            frameSetId = identifier(frameSetId, "frameSetId");
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            commandId = identifier(commandId, "commandId");
            commandRequestSha256 = sha256(commandRequestSha256, "commandRequestSha256");
            positive(receiptGeneration, "receiptGeneration");
            encodedReceipt = bounded(encodedReceipt, "encodedReceipt", 16 * 1024);
            receiptSha256 = sha256(receiptSha256, "receiptSha256");
        }
    }

    record AbandonmentApplication(
            String frameSetId,
            String runId,
            String attemptId,
            String commandId,
            String commandRequestSha256,
            String threadId,
            String admissionReceiptSha256,
            String authoritySha256,
            String abandonmentId,
            String executionId,
            long providerCallCountBefore,
            long providerCallCountAfter,
            String graphOwnerId,
            long graphFencingToken,
            Instant abandonedAt,
            byte[] canonicalGraphReceipt,
            String abandonmentSha256) {

        public AbandonmentApplication {
            frameSetId = identifier(frameSetId, "frameSetId");
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            commandId = identifier(commandId, "commandId");
            commandRequestSha256 = sha256(
                    commandRequestSha256, "commandRequestSha256");
            threadId = bounded(threadId, "threadId", 64);
            admissionReceiptSha256 = sha256(
                    admissionReceiptSha256, "admissionReceiptSha256");
            authoritySha256 = sha256(authoritySha256, "authoritySha256");
            abandonmentId = identifier(abandonmentId, "abandonmentId");
            executionId = identifier(executionId, "executionId");
            nonNegative(providerCallCountBefore, "providerCallCountBefore");
            positive(providerCallCountAfter, "providerCallCountAfter");
            graphOwnerId = identifier(graphOwnerId, "graphOwnerId");
            positive(graphFencingToken, "graphFencingToken");
            abandonedAt = Objects.requireNonNull(abandonedAt, "abandonedAt");
            canonicalGraphReceipt = Objects.requireNonNull(
                    canonicalGraphReceipt, "canonicalGraphReceipt").clone();
            if (canonicalGraphReceipt.length < 2 || canonicalGraphReceipt.length > 65536) {
                throw new IllegalArgumentException(
                        "canonicalGraphReceipt must contain 2..65536 bytes");
            }
            abandonmentSha256 = sha256(abandonmentSha256, "abandonmentSha256");
            if (providerCallCountAfter <= providerCallCountBefore) {
                throw new IllegalArgumentException(
                        "abandonment must prove an advanced Provider intent count");
            }
        }

        @Override
        public byte[] canonicalGraphReceipt() {
            return canonicalGraphReceipt.clone();
        }
    }

    record AbandonmentReceipt(
            String frameSetId,
            String abandonmentId,
            String abandonmentSha256,
            Set<FrameType> ambiguousFrameTypes,
            boolean inserted) {

        public AbandonmentReceipt {
            frameSetId = identifier(frameSetId, "frameSetId");
            abandonmentId = identifier(abandonmentId, "abandonmentId");
            abandonmentSha256 = sha256(abandonmentSha256, "abandonmentSha256");
            ambiguousFrameTypes = Set.copyOf(Objects.requireNonNull(
                    ambiguousFrameTypes, "ambiguousFrameTypes"));
            if (ambiguousFrameTypes.isEmpty()
                    || !Set.of(FrameType.values()).containsAll(ambiguousFrameTypes)) {
                throw new IllegalArgumentException(
                        "abandonment must bind at least one known ambiguous Frame");
            }
        }
    }

    record IngressCommand(
            String frameSetId,
            String runId,
            String attemptId,
            String streamSessionId,
            long transportSequence,
            String ingressIdentity,
            FrameType frameType,
            long generation,
            IngressKind ingressKind,
            Long localIndex,
            Audience audience,
            AgentStreamEventV4.Payload publicPayload,
            String canonicalPayloadSha256,
            Instant occurredAt) {

        public IngressCommand {
            frameSetId = identifier(frameSetId, "frameSetId");
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            streamSessionId = identifier(streamSessionId, "streamSessionId");
            nonNegative(transportSequence, "transportSequence");
            ingressIdentity = bounded(ingressIdentity, "ingressIdentity", 256);
            frameType = Objects.requireNonNull(frameType, "frameType");
            positive(generation, "generation");
            wireInteger(generation, "generation");
            ingressKind = Objects.requireNonNull(ingressKind, "ingressKind");
            audience = Objects.requireNonNull(audience, "audience");
            publicPayload = Objects.requireNonNull(publicPayload, "publicPayload");
            canonicalPayloadSha256 =
                    sha256(canonicalPayloadSha256, "canonicalPayloadSha256");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            if (ingressKind.requiresLocalIndex() && localIndex == null) {
                throw new IllegalArgumentException(
                        ingressKind + " requires a localIndex");
            }
            if (!ingressKind.requiresLocalIndex() && localIndex != null) {
                throw new IllegalArgumentException(
                        ingressKind + " must not carry a localIndex");
            }
            if (localIndex != null) {
                nonNegative(localIndex, "localIndex");
            }
            if (publicPayload.frameType()
                    != AgentStreamEventV4.FrameType.valueOf(frameType.name())) {
                throw new IllegalArgumentException("public payload belongs to a foreign Frame");
            }
            if (ingressKind == IngressKind.FRAME_GENERATION_RESET) {
                if (publicPayload.newGeneration() == null
                        || publicPayload.newGeneration().longValue() != generation) {
                    throw new IllegalArgumentException(
                            "generation-reset payload newGeneration drifted");
                }
            } else if (publicPayload.generation() == null
                    || publicPayload.generation().longValue() != generation) {
                throw new IllegalArgumentException("public payload generation drifted");
            }
            if (localIndex != null
                    && (publicPayload.localIndex() == null
                            || publicPayload.localIndex().longValue() != localIndex)) {
                throw new IllegalArgumentException("public payload localIndex drifted");
            }
            if (ingressKind == IngressKind.PUBLIC_FRAME_SEALED) {
                throw new IllegalArgumentException(
                        "public Frame sealed must use the atomic seal boundary");
            }
        }
    }

    record IngressReceipt(
            String ingressId,
            String receiptId,
            boolean inserted,
            long globalSequence,
            long durableHighWatermark) {

        public IngressReceipt {
            ingressId = identifier(ingressId, "ingressId");
            receiptId = identifier(receiptId, "receiptId");
            nonNegative(globalSequence, "globalSequence");
            nonNegative(durableHighWatermark, "durableHighWatermark");
            if (durableHighWatermark < globalSequence) {
                throw new IllegalArgumentException(
                        "durableHighWatermark cannot precede the ingress event");
            }
        }
    }

    record ProviderUsage(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long latencyMs,
            int providerCallCount) {

        public ProviderUsage {
            nonNegative(inputTokens, "inputTokens");
            nonNegative(outputTokens, "outputTokens");
            nonNegative(totalTokens, "totalTokens");
            nonNegative(latencyMs, "latencyMs");
            if (totalTokens != inputTokens + outputTokens) {
                throw new IllegalArgumentException(
                        "totalTokens must equal inputTokens + outputTokens");
            }
            if (providerCallCount < 1 || providerCallCount > 2) {
                throw new IllegalArgumentException("providerCallCount must be 1 or 2");
            }
        }
    }

    record FrameSealCommand(
            String frameSetId,
            String runId,
            String attemptId,
            String streamSessionId,
            long transportSequence,
            String ingressIdentity,
            Audience audience,
            FrameType frameType,
            long generation,
            String frameId,
            String childCheckpointRef,
            String childCheckpointSha256,
            String contextEnvelopeSha256,
            String modelContextViewSha256,
            String canonicalResultJson,
            String resultSha256,
            String publicProjectionSha256,
            long nextLocalIndex,
            ProviderUsage usage,
            Instant completedAt) {

        public FrameSealCommand {
            frameSetId = identifier(frameSetId, "frameSetId");
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            streamSessionId = identifier(streamSessionId, "streamSessionId");
            nonNegative(transportSequence, "transportSequence");
            ingressIdentity = bounded(ingressIdentity, "ingressIdentity", 256);
            audience = Objects.requireNonNull(audience, "audience");
            frameType = Objects.requireNonNull(frameType, "frameType");
            positive(generation, "generation");
            wireInteger(generation, "generation");
            frameId = identifier(frameId, "frameId");
            childCheckpointRef = bounded(childCheckpointRef, "childCheckpointRef", 1024);
            childCheckpointSha256 = sha256(childCheckpointSha256, "childCheckpointSha256");
            contextEnvelopeSha256 = sha256(contextEnvelopeSha256, "contextEnvelopeSha256");
            modelContextViewSha256 = sha256(modelContextViewSha256, "modelContextViewSha256");
            canonicalResultJson = bounded(canonicalResultJson, "canonicalResultJson", 262144);
            resultSha256 = sha256(resultSha256, "resultSha256");
            publicProjectionSha256 =
                    sha256(publicProjectionSha256, "publicProjectionSha256");
            nonNegative(nextLocalIndex, "nextLocalIndex");
            wireInteger(nextLocalIndex, "nextLocalIndex");
            usage = Objects.requireNonNull(usage, "usage");
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    record FrameSealReceipt(
            String frameSetId,
            FrameType frameType,
            long generation,
            String resultId,
            String frameReceiptId,
            boolean inserted,
            boolean exactThreeSealed,
            AssemblyState assemblyState,
            long globalSequence,
            long durableHighWatermark) {

        public FrameSealReceipt {
            frameSetId = identifier(frameSetId, "frameSetId");
            frameType = Objects.requireNonNull(frameType, "frameType");
            positive(generation, "generation");
            resultId = identifier(resultId, "resultId");
            frameReceiptId = identifier(frameReceiptId, "frameReceiptId");
            assemblyState = Objects.requireNonNull(assemblyState, "assemblyState");
            nonNegative(globalSequence, "globalSequence");
            nonNegative(durableHighWatermark, "durableHighWatermark");
            if (durableHighWatermark < globalSequence) {
                throw new IllegalArgumentException(
                        "durableHighWatermark cannot precede the sealed event");
            }
            if (exactThreeSealed && assemblyState != AssemblyState.COLLECTING) {
                throw new IllegalArgumentException(
                        "sealing three Frames does not itself grant READY authority");
            }
        }
    }

    record FrameRetryAdmission(
            String frameSetId,
            FrameManifest replacement,
            long expectedCurrentGeneration,
            SlotState expectedCurrentState,
            String repairCode,
            String validationPath,
            Instant admittedAt) {

        public FrameRetryAdmission {
            frameSetId = identifier(frameSetId, "frameSetId");
            replacement = Objects.requireNonNull(replacement, "replacement");
            positive(expectedCurrentGeneration, "expectedCurrentGeneration");
            expectedCurrentState = Objects.requireNonNull(
                    expectedCurrentState, "expectedCurrentState");
            repairCode = identifier(repairCode, "repairCode");
            validationPath = bounded(validationPath, "validationPath", 1024);
            admittedAt = Objects.requireNonNull(admittedAt, "admittedAt")
                    .truncatedTo(ChronoUnit.MICROS);
            if (expectedCurrentState != SlotState.FAILED
                    && expectedCurrentState != SlotState.AMBIGUOUS) {
                throw new IllegalArgumentException(
                        "retry admission requires FAILED or AMBIGUOUS current slot");
            }
            if (replacement.generation() != expectedCurrentGeneration + 1) {
                throw new IllegalArgumentException(
                        "retry generation must advance exactly once");
            }
        }
    }

    record FrameRetryReceipt(
            String frameSetId,
            FrameType frameType,
            long generation,
            String frameId,
            String receiptId,
            boolean inserted) {

        public FrameRetryReceipt {
            frameSetId = identifier(frameSetId, "frameSetId");
            frameType = Objects.requireNonNull(frameType, "frameType");
            positive(generation, "generation");
            frameId = identifier(frameId, "frameId");
            receiptId = identifier(receiptId, "receiptId");
        }
    }

    record ExecutionLane(
            FrameType frameType,
            long generation,
            String frameId,
            SlotState slotState,
            ExecutionAction action,
            long nextLocalIndex,
            long slotVersion,
            String resultId,
            String resultSha256,
            String publicProjectionSha256,
            String predecessorFailureCode) {

        public ExecutionLane {
            frameType = Objects.requireNonNull(frameType, "frameType");
            positive(generation, "generation");
            wireInteger(generation, "generation");
            frameId = identifier(frameId, "frameId");
            slotState = Objects.requireNonNull(slotState, "slotState");
            action = Objects.requireNonNull(action, "action");
            nonNegative(nextLocalIndex, "nextLocalIndex");
            wireInteger(nextLocalIndex, "nextLocalIndex");
            nonNegative(slotVersion, "slotVersion");
            resultSha256 = optionalSha256(resultSha256, "resultSha256");
            publicProjectionSha256 =
                    optionalSha256(publicProjectionSha256, "publicProjectionSha256");
            if (resultId != null) {
                resultId = identifier(resultId, "resultId");
            }
            if (predecessorFailureCode != null) {
                predecessorFailureCode = identifier(
                        predecessorFailureCode, "predecessorFailureCode");
            }
            boolean skip = action == ExecutionAction.SKIP_SEALED;
            if (skip
                    != (slotState == SlotState.SEALED
                            && resultId != null
                            && resultSha256 != null
                            && publicProjectionSha256 != null
                            && predecessorFailureCode == null)) {
                throw new IllegalArgumentException(
                        "only a sealed immutable result may be skipped");
            }
            if (!skip
                    && (slotState != SlotState.ADMITTED
                            || nextLocalIndex != 0
                            || resultId != null
                            || resultSha256 != null
                            || publicProjectionSha256 != null)) {
                throw new IllegalArgumentException(
                        "a runnable Frame must be a fresh admitted generation");
            }
            if ((action == ExecutionAction.RUN_RETRY)
                    != (predecessorFailureCode != null)) {
                throw new IllegalArgumentException(
                        "RUN_RETRY must carry its persisted predecessor failure");
            }
        }
    }

    record ExecutionPlan(
            String frameSetId,
            String runId,
            String attemptId,
            Map<FrameType, ExecutionLane> lanes) {

        public ExecutionPlan {
            frameSetId = identifier(frameSetId, "frameSetId");
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            lanes = Map.copyOf(Objects.requireNonNull(lanes, "lanes"));
            if (!lanes.keySet().equals(Set.of(FrameType.values()))) {
                throw new IllegalArgumentException(
                        "execution plan must contain exactly three Frame lanes");
            }
            lanes.forEach((type, lane) -> {
                if (type != lane.frameType()) {
                    throw new IllegalArgumentException(
                            "execution plan lane key differs from its Frame type");
                }
            });
        }

        public boolean allSealed() {
            return lanes.values().stream()
                    .allMatch(lane -> lane.action() == ExecutionAction.SKIP_SEALED);
        }
    }

    record ExactThreeFrame(
            FrameType frameType,
            long generation,
            String frameId,
            long slotVersion,
            String resultId,
            String resultSha256,
            String publicProjectionSha256,
            long nextLocalIndex) {

        public ExactThreeFrame {
            frameType = Objects.requireNonNull(frameType, "frameType");
            positive(generation, "generation");
            wireInteger(generation, "generation");
            frameId = identifier(frameId, "frameId");
            nonNegative(slotVersion, "slotVersion");
            resultId = identifier(resultId, "resultId");
            resultSha256 = sha256(resultSha256, "resultSha256");
            publicProjectionSha256 =
                    sha256(publicProjectionSha256, "publicProjectionSha256");
            nonNegative(nextLocalIndex, "nextLocalIndex");
            wireInteger(nextLocalIndex, "nextLocalIndex");
        }
    }

    record ExactThreeCompletion(
            String frameSetId,
            String runId,
            String attemptId,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            Map<FrameType, ExactThreeFrame> frames) {

        public ExactThreeCompletion {
            frameSetId = identifier(frameSetId, "frameSetId");
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            nonNegative(lastSequenceNo, "lastSequenceNo");
            frames = Map.copyOf(Objects.requireNonNull(frames, "frames"));
            if (!publicOutputEmitted
                    || !frames.keySet().equals(Set.of(FrameType.values()))) {
                throw new IllegalArgumentException(
                        "exact-three completion requires three public sealed Frames");
            }
            frames.forEach((type, frame) -> {
                if (type != frame.frameType()) {
                    throw new IllegalArgumentException(
                            "completion Frame key differs from its Frame type");
                }
            });
        }
    }

    record FrameSlotView(
            FrameType frameType,
            long generation,
            String frameId,
            SlotState state,
            String resultId) {

        public FrameSlotView {
            frameType = Objects.requireNonNull(frameType, "frameType");
            positive(generation, "generation");
            frameId = identifier(frameId, "frameId");
            state = Objects.requireNonNull(state, "state");
            if ((state == SlotState.SEALED) != (resultId != null)) {
                throw new IllegalArgumentException(
                        "only a sealed Frame slot may carry a resultId");
            }
            if (resultId != null) {
                resultId = identifier(resultId, "resultId");
            }
        }
    }

    record AssemblyView(
            String frameSetId,
            String runId,
            String attemptId,
            EventAuthority eventAuthority,
            String contextEnvelopeSha256,
            String modelContextViewSha256,
            AssemblyState state,
            Map<FrameType, FrameSlotView> slots,
            String inputSetSha256,
            String proposalArtifactId,
            String proposalSha256,
            String graphResultSha256,
            String terminalReceiptId) {

        public AssemblyView {
            frameSetId = identifier(frameSetId, "frameSetId");
            runId = identifier(runId, "runId");
            attemptId = identifier(attemptId, "attemptId");
            eventAuthority = Objects.requireNonNull(eventAuthority, "eventAuthority");
            contextEnvelopeSha256 = sha256(contextEnvelopeSha256, "contextEnvelopeSha256");
            modelContextViewSha256 = sha256(modelContextViewSha256, "modelContextViewSha256");
            state = Objects.requireNonNull(state, "state");
            slots = Map.copyOf(Objects.requireNonNull(slots, "slots"));
            if (!slots.keySet().equals(Set.of(FrameType.values()))) {
                throw new IllegalArgumentException(
                        "assembly must expose exactly three Frame slots");
            }
            inputSetSha256 = optionalSha256(inputSetSha256, "inputSetSha256");
            proposalSha256 = optionalSha256(proposalSha256, "proposalSha256");
            graphResultSha256 = optionalSha256(graphResultSha256, "graphResultSha256");
            if (proposalArtifactId != null) {
                proposalArtifactId = identifier(proposalArtifactId, "proposalArtifactId");
            }
            if (terminalReceiptId != null) {
                terminalReceiptId = identifier(terminalReceiptId, "terminalReceiptId");
            }
            if (state == AssemblyState.COLLECTING
                    && (inputSetSha256 != null
                            || proposalArtifactId != null
                            || proposalSha256 != null
                            || graphResultSha256 != null
                            || terminalReceiptId != null)) {
                throw new IllegalArgumentException(
                        "COLLECTING assembly cannot carry proposal or terminal authority");
            }
            if ((state == AssemblyState.READY || state == AssemblyState.COMMITTED)
                    && (inputSetSha256 == null
                            || proposalArtifactId == null
                            || proposalSha256 == null
                            || graphResultSha256 == null)) {
                throw new IllegalArgumentException(
                        "READY/COMMITTED assembly requires immutable proposal authority");
            }
            if ((state == AssemblyState.COMMITTED) != (terminalReceiptId != null)) {
                throw new IllegalArgumentException(
                        "only COMMITTED assembly may carry terminalReceiptId");
            }
        }
    }

    final class StagingConflictException extends IllegalStateException {
        private final String code;

        public StagingConflictException(String code, String message) {
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
        Objects.requireNonNull(value, field);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static String optionalSha256(String value, String field) {
        return value == null ? null : sha256(value, field);
    }

    private static String bounded(String value, String field, int maximum) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain 1.." + maximum + " characters");
        }
        return value;
    }

    private static void nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static void positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void wireInteger(long value, String field) {
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " exceeds the agent-stream.v4 integer range");
        }
    }
}
