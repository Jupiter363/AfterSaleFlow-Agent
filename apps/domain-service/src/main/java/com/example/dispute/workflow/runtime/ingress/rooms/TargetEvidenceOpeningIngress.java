package com.example.dispute.workflow.runtime.ingress.rooms;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.EvidenceAgentTurnService;
import com.example.dispute.room.application.EvidenceAgentTurnService.TargetOpeningPreparation;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandReferenceMapper;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandMaterial;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated target-v2 ingress for one actor-scoped Evidence opening per room epoch. */
@Service
public class TargetEvidenceOpeningIngress {
    private static final String PAYLOAD_SCHEMA = "production-runtime-evidence-opening.v1";
    private static final String RETRY_GENERATION_SCHEMA =
            "target-evidence-opening-retry-generation.v1";
    private static final String TERMINAL_NO_COMMIT_RECEIPT_PREFIX =
            "urn:target-room-agent-run-terminal-no-commit:";

    private final FulfillmentCaseRepository cases;
    private final CaseRoomEpochRepository epochs;
    private final CaseProcessProjectionRepository projections;
    private final EvidenceAgentTurnService evidenceTurns;
    private final CaseCommandService caseCommands;
    private final CaseCommandRepository commandRepository;
    private final ObjectProvider<TargetRoomCommandIngress> targetIngress;
    private final ObjectProvider<TargetEvidenceCommandMaterialStore> targetMaterials;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TargetEvidenceOpeningIngress(
            FulfillmentCaseRepository cases,
            CaseRoomEpochRepository epochs,
            CaseProcessProjectionRepository projections,
            EvidenceAgentTurnService evidenceTurns,
            CaseCommandService caseCommands,
            CaseCommandRepository commandRepository,
            ObjectProvider<TargetRoomCommandIngress> targetIngress,
            ObjectProvider<TargetEvidenceCommandMaterialStore> targetMaterials,
            ObjectMapper objectMapper,
            Clock clock) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.evidenceTurns = Objects.requireNonNull(evidenceTurns, "evidenceTurns");
        this.caseCommands = Objects.requireNonNull(caseCommands, "caseCommands");
        this.commandRepository = Objects.requireNonNull(commandRepository, "commandRepository");
        this.targetIngress = Objects.requireNonNull(targetIngress, "targetIngress");
        this.targetMaterials = Objects.requireNonNull(targetMaterials, "targetMaterials");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public Object open(
            String caseId,
            AuthenticatedActor actor,
            String traceId,
            String requestId) {
        Objects.requireNonNull(actor, "actor");
        requireText(caseId, "caseId");
        requireText(traceId, "traceId");
        requireText(requestId, "requestId");
        FulfillmentCaseEntity dispute = cases.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseRoomEpochEntity selected = epochs
                .findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                        caseId, RoomType.EVIDENCE, EpochLifecycleStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException(
                        "target Evidence opening requires an active Evidence epoch"));
        String baseCommandId = generationZeroCommandId(selected, caseId, actor);
        OpeningGeneration generation =
                lockOpeningGeneration(
                        requireText(selected.getTenantSurrogate(), "tenantSurrogate"),
                        caseId,
                        selected.getRoomEpoch(),
                        actor,
                        baseCommandId);
        CaseRoomEpochEntity epoch = epochs.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        caseId, RoomType.EVIDENCE, selected.getRoomEpoch())
                .orElseThrow(() -> new IllegalStateException(
                        "target Evidence opening epoch disappeared before admission"));
        requireTargetEpoch(epoch);
        CaseProcessProjectionEntity projection = projections.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalStateException(
                        "target Evidence opening requires the current process projection"));
        requireProjection(epoch, projection);
        String projectionRef = requireText(projection.getProjectionRef(), "projectionRef");
        String projectionSha256 = requireHash(projection.getProjectionSha256(), "projectionSha256");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant deadline = requireFutureDeadline(dispute, now);
        TargetOpeningPreparation prepared = evidenceTurns.prepareTargetOpening(
                caseId,
                actor,
                epoch.getRoomEpoch(),
                epoch.getFencingToken(),
                projectionRef,
                projectionSha256,
                now);
        if (prepared.existingMessage() != null) {
            requireCommittedOpeningReplay(
                    generation,
                    epoch,
                    projection,
                    projectionRef,
                    projectionSha256,
                    actor,
                    deadline,
                    prepared);
            return prepared.existingMessage();
        }
        requireOpeningGenerationLineage(
                generation,
                epoch,
                projectionRef,
                projectionSha256,
                actor,
                deadline,
                epoch.getProcessRevision(),
                epoch.getRoomRevision());
        if (generation.existingCommand() != null
                && generation.existingCommand().getCommandStatus() == CommandStatus.APPLIED) {
            throw new IllegalStateException(
                    "applied target Evidence opening is missing its committed clerk message");
        }
        String commandId = generation.commandId();
        PayloadRef payload = openingPayload(
                commandId, epoch, projectionRef, projectionSha256, actor);
        AcceptCaseCommand command = new AcceptCaseCommand(
                CommandType.EVIDENCE_OPENING,
                RoomType.EVIDENCE,
                epoch.getRoomEpoch(),
                payload,
                epoch.getProcessRevision(),
                deadline);
        TargetRoomCommandIngress ingress = requireUnique(targetIngress, "target Evidence command ingress");
        TargetEvidenceCommandMaterialStore materials =
                requireUnique(targetMaterials, "target Evidence material store");
        TargetRoomCommandIngress.EvidenceOpeningRunReceipt run;
        var existing = materials.readByRoute(
                new TargetEvidenceCommandMaterialStore.CommandLookup(
                        epoch.getTenantSurrogate(),
                        caseId,
                        commandId,
                        epoch.getRoomEpoch(),
                        epoch.getFencingToken()));
        if (generation.existingCommand() != null && existing.isEmpty()) {
            throw new IllegalStateException(
                    "target Evidence opening command is missing its immutable material");
        }
        if (existing.isPresent()) {
            TargetEvidenceCommandMaterial material = existing.orElseThrow().material();
            requireExistingMaterial(
                    material,
                    command,
                    actor,
                    prepared.idempotencyKey(),
                    projectionRef,
                    projectionSha256);
            String logicalRunId = material.request().agentRunId();
            run = new TargetRoomCommandIngress.EvidenceOpeningRunReceipt(
                    logicalRunId, logicalRunId + ":1");
        } else {
            run = ingress.materializeEvidenceOpening(
                    caseId,
                    commandId,
                    command,
                    actor,
                    traceId,
                    prepared.command());
        }
        CaseCommandAcceptance acceptance = caseCommands.accept(
                caseId, commandId, command, actor, traceId, requestId, null);
        OffsetDateTime acceptedAt = OffsetDateTime.ofInstant(
                acceptance.acceptedAt(), ZoneOffset.UTC);
        return new AgentRunAcceptedView(
                run.logicalRunId(),
                "PENDING",
                "/api/agent-runs/" + run.logicalRunId() + "/events",
                acceptedAt);
    }

    private OpeningGeneration lockOpeningGeneration(
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            AuthenticatedActor actor,
            String baseCommandId) {
        List<CaseCommandEntity> lineage = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String candidateId = baseCommandId;
        while (visited.add(candidateId)) {
            var candidate =
                    commandRepository.findByTenantSurrogateAndCommandIdForUpdate(
                            tenantSurrogate, candidateId);
            if (candidate.isEmpty()) {
                return new OpeningGeneration(
                        baseCommandId, candidateId, lineage, null);
            }
            CaseCommandEntity stored = candidate.orElseThrow();
            requireOpeningGenerationIdentity(
                    stored, tenantSurrogate, caseId, roomEpoch, actor, candidateId);
            lineage.add(stored);
            if (stored.getCommandStatus() == CommandStatus.FAILED) {
                requireTerminalNoCommitFailure(stored);
                candidateId = successorCommandId(baseCommandId, stored);
                continue;
            }
            if (stored.getCommandStatus() == CommandStatus.PENDING_ORCHESTRATION
                    || stored.getCommandStatus() == CommandStatus.ORCHESTRATION_ACCEPTED
                    || stored.getCommandStatus() == CommandStatus.APPLIED) {
                return new OpeningGeneration(
                        baseCommandId, candidateId, lineage, stored);
            }
            throw new IllegalStateException(
                    "target Evidence opening reached an unsupported terminal command state");
        }
        throw new IllegalStateException(
                "target Evidence opening retry generation contains an identity cycle");
    }

    private static void requireOpeningGenerationIdentity(
            CaseCommandEntity stored,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            AuthenticatedActor actor,
            String commandId) {
        if (!tenantSurrogate.equals(stored.getTenantSurrogate())
                || !caseId.equals(stored.getCaseId())
                || !commandId.equals(stored.getCommandId())
                || stored.getCaseCommandSequence() < 1
                || stored.getCommandType() != CommandType.EVIDENCE_OPENING
                || stored.getRoomType() != RoomType.EVIDENCE
                || stored.getRoomEpoch() != roomEpoch
                || !actor.actorId().equals(stored.getActorId())
                || !actor.role().name().equals(stored.getActorRole().name())) {
            throw new IllegalStateException(
                    "target Evidence opening retry lineage has foreign command authority");
        }
    }

    private void requireOpeningGenerationLineage(
            OpeningGeneration generation,
            CaseRoomEpochEntity epoch,
            String projectionRef,
            String projectionSha256,
            AuthenticatedActor actor,
            Instant deadline,
            long expectedProcessRevision,
            long expectedRoomRevision) {
        String expectedBase = generationZeroCommandId(epoch, epoch.getCaseId(), actor);
        if (!expectedBase.equals(generation.baseCommandId())) {
            throw new IllegalStateException(
                    "target Evidence opening generation-zero authority drifted");
        }
        List<CaseCommandEntity> lineage = generation.lineage();
        for (int index = 0; index < lineage.size(); index++) {
            CaseCommandEntity stored = lineage.get(index);
            CaseCommandRef reference =
                    CaseCommandReferenceMapper.fromEntity(stored, objectMapper);
            PayloadRef expectedPayload =
                    openingPayload(
                            stored.getCommandId(),
                            epoch,
                            expectedProcessRevision,
                            expectedRoomRevision,
                            projectionRef,
                            projectionSha256,
                            actor);
            boolean exact =
                    epoch.getTenantSurrogate().equals(reference.tenantSurrogate())
                            && epoch.getCaseId().equals(reference.caseId())
                            && stored.getCommandId().equals(reference.commandId())
                            && reference.caseCommandSequence() == stored.getCaseCommandSequence()
                            && reference.commandType() == CommandType.EVIDENCE_OPENING
                            && reference.roomType() == RoomType.EVIDENCE
                            && reference.roomEpoch() == epoch.getRoomEpoch()
                            && reference.expectedProcessRevision() == expectedProcessRevision
                            && reference.actorRef().actorId().equals(actor.actorId())
                            && reference.actorRef().actorRole().name().equals(actor.role().name())
                            && reference.actorRef().actorScopes().contains(
                                    "case:"
                                            + epoch.getCaseId()
                                            + ":command:EVIDENCE_OPENING")
                            && expectedPayload.equals(reference.payloadRef())
                            && deadline.equals(reference.deadlineAt())
                            && reference.requestHash().matches("[0-9a-f]{64}");
            if (!exact) {
                throw new IllegalStateException(
                        "target Evidence opening retry lineage drifted from current authority");
            }

            boolean last = index == lineage.size() - 1;
            if (!last || stored.getCommandStatus() == CommandStatus.FAILED) {
                requireTerminalNoCommitFailure(stored);
                continue;
            }
            requireSelectedOpeningReplayState(stored);
        }
        if (generation.existingCommand() == null) {
            if (!lineage.isEmpty()
                    && lineage.get(lineage.size() - 1).getCommandStatus()
                            != CommandStatus.FAILED) {
                throw new IllegalStateException(
                        "target Evidence opening retry candidate is not after a terminal failure");
            }
            return;
        }
        if (lineage.isEmpty()
                || !generation.existingCommand().getCommandId()
                        .equals(lineage.get(lineage.size() - 1).getCommandId())
                || !generation.commandId().equals(generation.existingCommand().getCommandId())) {
            throw new IllegalStateException(
                    "target Evidence opening replay generation is ambiguous");
        }
    }

    private void requireCommittedOpeningReplay(
            OpeningGeneration generation,
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            String projectionRef,
            String projectionSha256,
            AuthenticatedActor actor,
            Instant deadline,
            TargetOpeningPreparation prepared) {
        CaseCommandEntity applied = generation.existingCommand();
        if (applied == null || applied.getCommandStatus() != CommandStatus.APPLIED) {
            throw new IllegalStateException(
                    "target Evidence opening message lacks an applied command authority");
        }
        requireSelectedOpeningReplayState(applied);
        CaseCommandRef reference = CaseCommandReferenceMapper.fromEntity(applied, objectMapper);
        TargetEvidenceCommandMaterialStore materials =
                requireUnique(targetMaterials, "target Evidence material store");
        TargetEvidenceCommandMaterialStore.MaterialSnapshot snapshot = materials.readByRoute(
                        new TargetEvidenceCommandMaterialStore.CommandLookup(
                                epoch.getTenantSurrogate(),
                                epoch.getCaseId(),
                                applied.getCommandId(),
                                epoch.getRoomEpoch(),
                                epoch.getFencingToken()))
                .orElseThrow(() -> new IllegalStateException(
                        "applied target Evidence opening is missing its immutable material"));
        if (snapshot.material() == null || snapshot.admission() == null) {
            throw new IllegalStateException(
                    "applied target Evidence opening material authority is incomplete");
        }
        TargetEvidenceCommandMaterial material = snapshot.material();
        AcceptCaseCommand historicalCommand = new AcceptCaseCommand(
                reference.commandType(),
                reference.roomType(),
                reference.roomEpoch(),
                reference.payloadRef(),
                reference.expectedProcessRevision(),
                reference.deadlineAt());
        requireExistingMaterial(
                material,
                historicalCommand,
                actor,
                prepared.idempotencyKey(),
                projectionRef,
                projectionSha256);
        var admission = snapshot.admission();
        boolean exactHistoricalMaterial =
                TargetEvidenceCommandMaterial.SCHEMA_VERSION.equals(material.schemaVersion())
                        && TargetEvidenceCommandMaterial.TARGET_LANE.equals(material.executionLane())
                        && snapshot.admissionId() != null
                        && !snapshot.admissionId().isBlank()
                        && snapshot.materialSha256() != null
                        && snapshot.materialSha256().matches("[0-9a-f]{64}")
                        && snapshot.storedAt() != null
                        && material.activationId().equals(admission.activationId())
                        && material.activationManifestHash().equals(admission.manifestHash())
                        && epoch.getTenantSurrogate().equals(admission.tenantSurrogate())
                        && epoch.getCaseId().equals(admission.caseId())
                        && applied.getCommandId().equals(admission.commandId())
                        && material.commandHash().equals(admission.commandHash())
                        && material.commandEnvelopeHash().equals(admission.commandEnvelopeHash())
                        && admission.roomEpoch() == epoch.getRoomEpoch()
                        && admission.roomFencingToken() == epoch.getFencingToken()
                        && material.roomFencingToken() == epoch.getFencingToken()
                        && material.expectedProcessRevision() == reference.expectedProcessRevision()
                        && material.expectedRoomRevision() >= 0
                        && material.caseCommandRequestHash().equals(reference.requestHash())
                        && material.request().command().commandId().equals(applied.getCommandId())
                        && material.request().command().tenantSurrogate()
                                .equals(epoch.getTenantSurrogate())
                        && material.request().command().caseId().equals(epoch.getCaseId());
        if (!exactHistoricalMaterial) {
            throw new IllegalStateException(
                    "applied target Evidence opening material authority drifted");
        }
        requireOpeningGenerationLineage(
                generation,
                epoch,
                projectionRef,
                projectionSha256,
                actor,
                deadline,
                material.expectedProcessRevision(),
                material.expectedRoomRevision());
        boolean exactSingleCommitAdvance =
                epoch.getProcessRevision()
                                == Math.incrementExact(material.expectedProcessRevision())
                        && projection.getProcessRevision()
                                == Math.incrementExact(material.expectedProcessRevision())
                        && epoch.getRoomRevision()
                                == Math.incrementExact(material.expectedRoomRevision());
        if (!exactSingleCommitAdvance) {
            throw new IllegalStateException(
                    "applied target Evidence opening revisions are not the exact formal-commit successor");
        }
        requireCommittedOpeningMessage(applied, material, epoch, prepared);
    }

    private static void requireCommittedOpeningMessage(
            CaseCommandEntity applied,
            TargetEvidenceCommandMaterial material,
            CaseRoomEpochEntity epoch,
            TargetOpeningPreparation prepared) {
        var message = prepared.existingMessage();
        boolean exact = message != null
                && message.id() != null
                && !message.id().isBlank()
                && epoch.getCaseId().equals(message.caseId())
                && epoch.getRoomId().equals(message.roomId())
                && message.sequenceNo() > 0
                && "EVIDENCE_CLERK".equals(message.senderRole())
                && "evidence-clerk".equals(message.senderId())
                && message.messageType() != null
                && "AGENT_MESSAGE".equals(message.messageType().name())
                && message.messageSource() != null
                && "AGENT_LLM".equals(message.messageSource().name())
                && message.messageText() != null
                && !message.messageText().isBlank()
                && message.attachmentRefs() != null
                && message.attachmentRefs().isEmpty()
                && material.request().agentRunId().equals(message.agentRunId())
                && message.createdAt() != null
                && ("urn:production-runtime:evidence-formal-message:" + message.id())
                        .equals(applied.getResultUri())
                && applied.getResultSha256() != null
                && applied.getResultSha256().matches("[0-9a-f]{64}");
        if (!exact) {
            throw new IllegalStateException(
                    "applied target Evidence opening committed message authority drifted");
        }
    }

    private static void requireSelectedOpeningReplayState(CaseCommandEntity stored) {
        if (stored.getCommandStatus() == CommandStatus.PENDING_ORCHESTRATION) {
            if (stored.getOrchestratedAt() != null
                    || stored.getResultUri() != null
                    || stored.getResultSha256() != null
                    || stored.getAppliedAt() != null) {
                throw new IllegalStateException(
                        "pending target Evidence opening carries terminal authority");
            }
            return;
        }
        if (stored.getCommandStatus() == CommandStatus.ORCHESTRATION_ACCEPTED) {
            if (stored.getOrchestratedAt() == null
                    || stored.getResultUri() != null
                    || stored.getResultSha256() != null
                    || stored.getAppliedAt() != null) {
                throw new IllegalStateException(
                        "accepted target Evidence opening carries terminal authority");
            }
            return;
        }
        if (stored.getCommandStatus() == CommandStatus.APPLIED) {
            if (stored.getOrchestratedAt() == null
                    || stored.getAppliedAt() == null
                    || stored.getStatusReasonCode() != null
                    || stored.getResultUri() == null
                    || stored.getResultUri().isBlank()
                    || stored.getResultSha256() == null
                    || !stored.getResultSha256().matches("[0-9a-f]{64}")) {
                throw new IllegalStateException(
                        "applied target Evidence opening is missing result authority");
            }
            return;
        }
        throw new IllegalStateException(
                "target Evidence opening replay state is unsupported");
    }

    private static void requireTerminalNoCommitFailure(CaseCommandEntity stored) {
        String receiptSha256 = stored.getResultSha256();
        if (stored.getCommandStatus() != CommandStatus.FAILED
                || stored.getAppliedAt() != null
                || stored.getOrchestratedAt() == null
                || stored.getStatusReasonCode() == null
                || stored.getStatusReasonCode().isBlank()
                || receiptSha256 == null
                || !receiptSha256.matches("[0-9a-f]{64}")
                || !(TERMINAL_NO_COMMIT_RECEIPT_PREFIX + receiptSha256)
                        .equals(stored.getResultUri())) {
            throw new IllegalStateException(
                    "failed target Evidence opening lacks exact terminal-no-commit authority");
        }
    }

    private static String generationZeroCommandId(
            CaseRoomEpochEntity epoch, String caseId, AuthenticatedActor actor) {
        return "evidence-opening:"
                + stableToken(
                        requireText(epoch.getTenantSurrogate(), "tenantSurrogate")
                                + "\n"
                                + caseId
                                + "\n"
                                + epoch.getRoomEpoch()
                                + "\n"
                                + actor.actorId()
                                + "\n"
                                + actor.role().name());
    }

    private static String successorCommandId(
            String baseCommandId, CaseCommandEntity prior) {
        return "evidence-opening:"
                + stableToken(
                        RETRY_GENERATION_SCHEMA
                                + "\n"
                                + baseCommandId
                                + "\n"
                                + prior.getCommandId()
                                + "\n"
                                + prior.getCaseCommandSequence()
                                + "\n"
                                + prior.getResultSha256());
    }

    private record OpeningGeneration(
            String baseCommandId,
            String commandId,
            List<CaseCommandEntity> lineage,
            CaseCommandEntity existingCommand) {

        private OpeningGeneration {
            requireText(baseCommandId, "baseCommandId");
            requireText(commandId, "commandId");
            lineage = List.copyOf(lineage);
        }
    }

    private PayloadRef openingPayload(
            String commandId,
            CaseRoomEpochEntity epoch,
            String projectionRef,
            String projectionSha256,
            AuthenticatedActor actor) {
        return openingPayload(
                commandId,
                epoch,
                epoch.getProcessRevision(),
                epoch.getRoomRevision(),
                projectionRef,
                projectionSha256,
                actor);
    }

    private PayloadRef openingPayload(
            String commandId,
            CaseRoomEpochEntity epoch,
            long expectedProcessRevision,
            long expectedRoomRevision,
            String projectionRef,
            String projectionSha256,
            AuthenticatedActor actor) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("schema_version", PAYLOAD_SCHEMA);
        value.put("case_id", epoch.getCaseId());
        value.put("room_id", requireText(epoch.getRoomId(), "roomId"));
        value.put("room_epoch", epoch.getRoomEpoch());
        value.put("fencing_token", epoch.getFencingToken());
        value.put("expected_process_revision", expectedProcessRevision);
        value.put("expected_room_revision", expectedRoomRevision);
        value.put("actor_id", actor.actorId());
        value.put("actor_role", actor.role().name());
        value.put("projection_ref", projectionRef);
        value.put("projection_sha256", projectionSha256);
        byte[] canonical = ContractJson.canonicalize(value);
        return new PayloadRef(
                PAYLOAD_SCHEMA,
                "urn:production-runtime:evidence-opening:" + stableToken(commandId),
                ContractJson.sha256Hex(value),
                canonical.length);
    }

    private static void requireTargetEpoch(CaseRoomEpochEntity epoch) {
        boolean exact = epoch.getLifecycleStatus() == EpochLifecycleStatus.ACTIVE
                && epoch.getProvisioningStatus() == EpochProvisioningStatus.READY
                && epoch.getWriterMode() == WriterMode.TEMPORAL
                && TargetTypedRoomProtocol.GRAPH_KEY.equals(epoch.getGraphKey())
                && epoch.getRoomType() == RoomType.EVIDENCE
                && epoch.getRoomEpoch() >= 0
                && epoch.getFencingToken() > 0
                && epoch.getProcessRevision() >= 0
                && epoch.getRoomRevision() >= 0;
        if (!exact) {
            throw new IllegalStateException("target Evidence opening epoch authority is invalid");
        }
    }

    private static void requireProjection(
            CaseRoomEpochEntity epoch, CaseProcessProjectionEntity projection) {
        boolean exact = epoch.getTenantSurrogate().equals(projection.getTenantSurrogate())
                && epoch.getCaseId().equals(projection.getCaseId())
                && "EVIDENCE_OPEN".equals(projection.getMacroPhase())
                && "EVIDENCE".equals(projection.getCurrentRoom())
                && "OPEN".equals(projection.getRoomPhase())
                && projection.getWriterMode() == WriterMode.TEMPORAL
                && projection.getRoomEpoch() == epoch.getRoomEpoch()
                && projection.getFencingToken() == epoch.getFencingToken()
                && projection.getProcessRevision() == epoch.getProcessRevision();
        if (!exact) {
            throw new IllegalStateException(
                    "target Evidence opening projection authority drifted");
        }
    }

    private static void requireExistingMaterial(
            TargetEvidenceCommandMaterial material,
            AcceptCaseCommand command,
            AuthenticatedActor actor,
            String openingIdempotencyKey,
            String projectionRef,
            String projectionSha256) {
        var graph = material.request().command();
        var turn = material.evidenceAgentTurnCommand();
        var envelope = turn.contextEnvelope();
        var frozen = envelope.frozenSubmission();
        boolean exact = graph.caseId().equals(envelope.caseSnapshot().caseId())
                && graph.roomType() == RoomType.EVIDENCE
                && graph.roomEpoch() == command.roomEpoch()
                && graph.processRevision() == command.expectedProcessRevision()
                && graph.eventRef().schemaVersion().equals(command.payloadRef().schemaVersion())
                && graph.eventRef().uri().equals(command.payloadRef().uri())
                && graph.eventRef().sha256().equals(command.payloadRef().sha256())
                && graph.eventRef().sizeBytes() == command.payloadRef().sizeBytes()
                && graph.actorScope().actorId().equals(actor.actorId())
                && graph.actorScope().actorRole().name().equals(actor.role().name())
                && graph.actorScope().capabilities().contains(
                        "case:" + graph.caseId() + ":command:EVIDENCE_OPENING")
                && "ROOM_OPENING".equals(envelope.currentEvent().eventType())
                && envelope.currentEvent().messageType().name().equals("AGENT_MESSAGE")
                && envelope.currentEvent().eventId().equals(openingIdempotencyKey)
                && envelope.currentEvent().attachmentRefs().isEmpty()
                && frozen != null
                && frozen.evidenceRoomEpoch() == command.roomEpoch()
                && frozen.evidenceFencingToken() == material.roomFencingToken()
                && projectionRef.equals(frozen.projectionRef())
                && projectionSha256.equals(frozen.projectionSha256());
        if (!exact) {
            throw new IllegalStateException(
                    "target Evidence opening replay material drifted");
        }
    }

    private static Instant requireFutureDeadline(
            FulfillmentCaseEntity dispute, Instant now) {
        if (dispute.getCurrentDeadlineAt() == null) {
            throw new IllegalStateException(
                    "target Evidence opening requires an authoritative deadline");
        }
        Instant deadline = dispute.getCurrentDeadlineAt().toInstant();
        if (!deadline.isAfter(now)) {
            throw new IllegalStateException(
                    "target Evidence opening deadline is not in the future");
        }
        return deadline;
    }

    private static <T> T requireUnique(ObjectProvider<T> provider, String field) {
        T value = provider.getIfUnique();
        if (value == null) {
            throw new IllegalStateException(field + " is unavailable or ambiguous");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value;
    }

    private static String requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(field + " must be lowercase SHA-256");
        }
        return value;
    }

    private static String stableToken(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }
}
