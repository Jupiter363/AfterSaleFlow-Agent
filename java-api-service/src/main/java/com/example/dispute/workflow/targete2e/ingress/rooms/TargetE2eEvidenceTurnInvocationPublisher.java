package com.example.dispute.workflow.targete2e.ingress.rooms;

import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.room.application.EvidenceContextEnvelopeV1;
import com.example.dispute.evidence.application.EvidenceContentAuthorityLookup;
import com.example.dispute.evidence.application.EvidenceContentAuthorityUnavailableException;
import com.example.dispute.evidence.application.EvidenceContentAuthorityV1;
import com.example.dispute.evidence.application.EvidenceParseOutboxService;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Publishes the Java-authoritative formal Evidence Clerk turn for one target graph command. */
public final class TargetE2eEvidenceTurnInvocationPublisher {
    public static final String SCHEMA_VERSION = "target-e2e-evidence-turn-invocation.v2";
    private static final EvidenceContentAuthorityLookup MISSING_AUTHORITY_LOOKUP =
            (caseId, evidenceId, fileSha256, contentType, fileSize, parserVersion) -> Optional.empty();

    private final MinioTargetE2eRoomCommandPayloadPublisher publisher;
    private final TargetE2eRoomObjectIndex objectIndex;
    private final ObjectMapper mapper;
    private final EvidenceContentAuthorityLookup contentAuthorityLookup;

    public TargetE2eEvidenceTurnInvocationPublisher(
            MinioTargetE2eRoomCommandPayloadPublisher publisher,
            TargetE2eRoomObjectIndex objectIndex,
            ObjectMapper mapper) {
        this(publisher, objectIndex, mapper, MISSING_AUTHORITY_LOOKUP);
    }

    /**
     * Production construction supplies the persisted lookup. The three-argument constructor is
     * retained only for existing non-text fixtures; supported text still fails closed when no
     * lookup is present.
     */
    public TargetE2eEvidenceTurnInvocationPublisher(
            MinioTargetE2eRoomCommandPayloadPublisher publisher,
            TargetE2eRoomObjectIndex objectIndex,
            ObjectMapper mapper,
            EvidenceContentAuthorityLookup contentAuthorityLookup) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.objectIndex = Objects.requireNonNull(objectIndex, "objectIndex");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.contentAuthorityLookup = Objects.requireNonNull(contentAuthorityLookup, "contentAuthorityLookup");
    }

    public Published publish(
            RoomGraphCommand outerCommand,
            long fencingToken,
            CommandType commandType,
            EvidenceAgentTurnCommand evidenceTurnCommand) {
        requireAuthority(outerCommand, fencingToken, commandType, evidenceTurnCommand);
        requireCurrentSupportedTextAuthorities(outerCommand, commandType, evidenceTurnCommand);
        JsonNode actorScope = mapper.valueToTree(outerCommand.actorScope());
        String actorScopeHash = ContractJson.sha256Hex(actorScope);
        ObjectNode invocation = mapper.createObjectNode();
        invocation.put("schema_version", SCHEMA_VERSION);
        invocation.put("logical_run_id", outerCommand.logicalRunId());
        invocation.put("tenant_surrogate", outerCommand.tenantSurrogate());
        invocation.put("case_id", outerCommand.caseId());
        invocation.put("room_epoch", outerCommand.roomEpoch());
        invocation.put("fencing_token", fencingToken);
        invocation.put("thread_id", outerCommand.threadId());
        invocation.put("actor_id", outerCommand.actorScope().actorId());
        invocation.put("actor_role", outerCommand.actorScope().actorRole().name());
        invocation.put("actor_scope_hash", actorScopeHash);
        invocation.set(
                "evidence_turn_request",
                bindPromptAuthority(outerCommand, evidenceTurnCommand));
        String invocationHash = ContractJson.sha256Hex(invocation);
        invocation.put("invocation_hash", invocationHash);
        String artifactId = "target-evidence-turn-invocation:" + invocationHash.substring(0, 32);
        return new Published(
                publisher.publishCanonical(artifactId, "EVIDENCE", invocation),
                actorScopeHash,
                invocationHash);
    }

    private ObjectNode bindPromptAuthority(
            RoomGraphCommand outerCommand,
            EvidenceAgentTurnCommand evidenceTurnCommand) {
        ObjectNode request = mapper.valueToTree(evidenceTurnCommand);
        JsonNode rawAgentContext = request.get("agent_context");
        if (!(rawAgentContext instanceof ObjectNode agentContext)) {
            throw new IllegalArgumentException(
                    "formal Evidence turn does not contain an agent context");
        }
        JsonNode rawContextEnvelope = request.get("context_envelope");
        if (!(rawContextEnvelope instanceof ObjectNode contextEnvelope)
                || !(contextEnvelope.get("actor_snapshot") instanceof ObjectNode actorSnapshot)) {
            throw new IllegalArgumentException(
                    "formal Evidence turn does not contain an actor snapshot");
        }
        String promptProfileId = outerCommand.invocationContext().promptProfileId();
        agentContext.put("prompt_profile_id", promptProfileId);
        actorSnapshot.put("prompt_profile_id", promptProfileId);
        return request;
    }

    public void bind(Authority authority, RoomGraphCommand command, Published published) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(published, "published");
        if (command.roomType().name().equals("EVIDENCE") == false
                || !command.domainSnapshotRef().equals(published.invocation().reference())
                || !ContractJson.sha256Hex(mapper.valueToTree(command.actorScope()))
                        .equals(published.actorScopeHash())) {
            throw new IllegalArgumentException(
                    "formal Evidence invocation does not bind its outer command");
        }
        publisher.bind(
                authority,
                command,
                published.invocation(),
                TargetE2eRoomObjectIndex.Kind.COMMAND_INPUT);
    }

    private static void requireAuthority(
            RoomGraphCommand outer,
            long fencingToken,
            CommandType commandType,
            EvidenceAgentTurnCommand turn) {
        Objects.requireNonNull(outer, "outerCommand");
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(turn, "evidenceTurnCommand");
        var context = turn.agentContext();
        var envelope = turn.contextEnvelope();
        var actor = envelope.actorSnapshot();
        var event = envelope.currentEvent();
        boolean opening = commandType == CommandType.EVIDENCE_OPENING;
        boolean submission = commandType == CommandType.EVIDENCE_SUBMIT;
        boolean exactEvent = opening
                ? "ROOM_OPENING".equals(event.eventType())
                        && event.messageType().name().equals("AGENT_MESSAGE")
                        && event.attachmentRefs() != null
                        && event.attachmentRefs().isEmpty()
                        && exactOpeningFrozenAuthority(
                                outer, fencingToken, envelope.frozenSubmission())
                : submission
                        && "PARTY_MESSAGE".equals(event.eventType())
                        && event.messageType().name().equals("PARTY_EVIDENCE_REFERENCE")
                        && event.attachmentRefs() != null
                        && !event.attachmentRefs().isEmpty();
        boolean exact = outer.roomType().name().equals("EVIDENCE")
                && fencingToken > 0
                && outer.actorScope().capabilities().contains(
                        "case:" + outer.caseId() + ":command:" + commandType.name())
                && outer.caseId().equals(context.caseId())
                && context.roomType().name().equals("EVIDENCE")
                && outer.actorScope().actorId().equals(context.actorId())
                && outer.actorScope().actorRole().name().equals(context.actorRole())
                && outer.caseId().equals(envelope.caseSnapshot().caseId())
                && envelope.roomPolicy().roomType().name().equals("EVIDENCE")
                && outer.actorScope().actorId().equals(actor.actorId())
                && outer.actorScope().actorRole().name().equals(actor.actorRole())
                && Objects.equals(context.accessSessionId(), actor.accessSessionId())
                && Objects.equals(context.agentSessionId(), actor.agentSessionId())
                && outer.actorScope().actorId().equals(event.actorId())
                && outer.actorScope().actorRole().name().equals(event.actorRole())
                && exactEvent;
        if (!exact) {
            throw new IllegalArgumentException(
                    "formal Evidence turn authority does not bind its outer command");
        }
    }

    /**
     * The only source for supported text content is the exact immutable parser result. Visible
     * metadata and the item projection are independently rechecked, but neither can stand in for
     * a missing authority row.
     */
    private void requireCurrentSupportedTextAuthorities(
            RoomGraphCommand outer, CommandType commandType, EvidenceAgentTurnCommand turn) {
        if (commandType != CommandType.EVIDENCE_SUBMIT) {
            return;
        }
        EvidenceContextEnvelopeV1 envelope = turn.contextEnvelope();
        Map<String, List<EvidenceContextEnvelopeV1.VisibleEvidence>> visibleById = new HashMap<>();
        for (EvidenceContextEnvelopeV1.VisibleEvidence evidence : envelope.visibleEvidence()) {
            visibleById
                    .computeIfAbsent(evidence.evidenceId(), ignored -> new java.util.ArrayList<>())
                    .add(evidence);
        }
        Map<String, List<EvidenceContentAuthorityV1>> authoritiesByEvidenceId = new HashMap<>();
        for (EvidenceContentAuthorityV1 authority : envelope.evidenceContentAuthorities()) {
            authoritiesByEvidenceId
                    .computeIfAbsent(authority.evidenceId(), ignored -> new java.util.ArrayList<>())
                    .add(authority);
        }
        Set<String> seenSupportedReferences = new HashSet<>();
        for (String evidenceId : envelope.currentEvent().attachmentRefs()) {
            List<EvidenceContextEnvelopeV1.VisibleEvidence> candidates =
                    visibleById.getOrDefault(evidenceId, List.of());
            if (candidates.size() != 1) {
                throw unavailable();
            }
            EvidenceContextEnvelopeV1.VisibleEvidence visible = candidates.getFirst();
            if (!EvidenceContentAuthorityV1.isSupportedTextContentType(visible.contentType())) {
                continue;
            }
            if (!seenSupportedReferences.add(evidenceId)
                    || !exactVisibleCoordinate(outer, envelope, visible)) {
                throw unavailable();
            }
            List<EvidenceContentAuthorityV1> frozen =
                    authoritiesByEvidenceId.getOrDefault(evidenceId, List.of());
            if (frozen.size() != 1) {
                throw unavailable();
            }
            EvidenceContentAuthorityV1 envelopeAuthority = frozen.getFirst();
            EvidenceContentAuthorityLookup.StoredAuthority persisted =
                    contentAuthorityLookup
                            .findExact(
                                    outer.caseId(),
                                    evidenceId,
                                    visible.fileHash(),
                                    visible.contentType(),
                                    visible.fileSize(),
                                    EvidenceParseOutboxService.PARSER_VERSION)
                            .orElseThrow(this::unavailable);
            if (!envelopeAuthority.equals(persisted.authority())
                    || persisted.fileSize() != visible.fileSize()
                    || !envelopeAuthority.parsedText().equals(visible.parsedText())) {
                throw unavailable();
            }
        }
    }

    private static boolean exactVisibleCoordinate(
            RoomGraphCommand outer,
            EvidenceContextEnvelopeV1 envelope,
            EvidenceContextEnvelopeV1.VisibleEvidence visible) {
        return outer.caseId().equals(envelope.caseSnapshot().caseId())
                && visible.evidenceId() != null
                && visible.fileHash() != null
                && visible.fileHash().matches("[0-9a-f]{64}")
                && visible.fileSize() != null
                && visible.fileSize() >= 1
                && visible.fileSize() <= 25L * 1024 * 1024
                && visible.contentType() != null
                && EvidenceContentAuthorityV1.isSupportedTextContentType(visible.contentType())
                && "SUCCEEDED".equals(visible.parseStatus())
                && visible.parsedText() != null
                && !visible.parsedText().isBlank()
                && envelope.currentEvent().actorId().equals(visible.submittedById())
                && envelope.currentEvent().actorRole().equals(visible.submittedByRole());
    }

    private EvidenceContentAuthorityUnavailableException unavailable() {
        return new EvidenceContentAuthorityUnavailableException();
    }

    private static boolean exactOpeningFrozenAuthority(
            RoomGraphCommand outer,
            long fencingToken,
            EvidenceContextEnvelopeV1.FrozenSubmission frozen) {
        if (frozen == null
                || frozen.evidenceRoomEpoch() != outer.roomEpoch()
                || frozen.evidenceFencingToken() != fencingToken
                || frozen.projectionRef() == null
                || frozen.projectionRef().isBlank()
                || frozen.projectionSha256() == null
                || !frozen.projectionSha256().matches("[0-9a-f]{64}")
                || frozen.authority() == null
                || frozen.matrix() == null
                || !outer.caseId().equals(frozen.authority().caseId())) {
            return false;
        }
        try {
            frozen.authority().requireProjectionPair(
                    frozen.projectionRef(), frozen.projectionSha256());
            frozen.authority().requireMatchesMatrix(frozen.matrix());
            return true;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return false;
        }
    }

    public record Published(
            MinioTargetE2eRoomCommandPayloadPublisher.PublishedObject invocation,
            String actorScopeHash,
            String invocationHash) {
        public Published {
            Objects.requireNonNull(invocation, "invocation");
            requireHash(actorScopeHash, "actorScopeHash");
            requireHash(invocationHash, "invocationHash");
        }

        private static void requireHash(String value, String field) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(field + " must be lowercase SHA-256");
            }
        }
    }
}
