package com.example.dispute.workflow.targete2e.ingress.rooms;

import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** Publishes the Java-authoritative formal Evidence Clerk turn for one target graph command. */
public final class TargetE2eEvidenceTurnInvocationPublisher {
    public static final String SCHEMA_VERSION = "target-e2e-evidence-turn-invocation.v2";

    private final MinioTargetE2eRoomCommandPayloadPublisher publisher;
    private final TargetE2eRoomObjectIndex objectIndex;
    private final ObjectMapper mapper;

    public TargetE2eEvidenceTurnInvocationPublisher(
            MinioTargetE2eRoomCommandPayloadPublisher publisher,
            TargetE2eRoomObjectIndex objectIndex,
            ObjectMapper mapper) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.objectIndex = Objects.requireNonNull(objectIndex, "objectIndex");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    }

    public Published publish(
            RoomGraphCommand outerCommand,
            long fencingToken,
            EvidenceAgentTurnCommand evidenceTurnCommand) {
        requireAuthority(outerCommand, fencingToken, evidenceTurnCommand);
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
        invocation.set("evidence_turn_request", mapper.valueToTree(evidenceTurnCommand));
        String invocationHash = ContractJson.sha256Hex(invocation);
        invocation.put("invocation_hash", invocationHash);
        String artifactId = "target-evidence-turn-invocation:" + invocationHash.substring(0, 32);
        return new Published(
                publisher.publishCanonical(artifactId, "EVIDENCE", invocation),
                actorScopeHash,
                invocationHash);
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
            EvidenceAgentTurnCommand turn) {
        Objects.requireNonNull(outer, "outerCommand");
        Objects.requireNonNull(turn, "evidenceTurnCommand");
        var context = turn.agentContext();
        var envelope = turn.contextEnvelope();
        var actor = envelope.actorSnapshot();
        var event = envelope.currentEvent();
        boolean exact = outer.roomType().name().equals("EVIDENCE")
                && fencingToken > 0
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
                && "PARTY_MESSAGE".equals(event.eventType())
                && event.messageType().name().equals("PARTY_EVIDENCE_REFERENCE")
                && outer.actorScope().actorId().equals(event.actorId())
                && outer.actorScope().actorRole().name().equals(event.actorRole())
                && event.attachmentRefs() != null
                && !event.attachmentRefs().isEmpty();
        if (!exact) {
            throw new IllegalArgumentException(
                    "formal Evidence turn authority does not bind its outer command");
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
