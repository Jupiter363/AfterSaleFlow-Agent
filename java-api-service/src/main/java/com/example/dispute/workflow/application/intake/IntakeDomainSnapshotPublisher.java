package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.PublishRequest;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Builds and publishes the one actor-filtered Intake initialization snapshot. */
public final class IntakeDomainSnapshotPublisher {

    private static final String SCHEMA_VERSION = "intake-domain-snapshot.v2";

    private final IntakeImmutablePayloadPublisher payloadPublisher;
    private final IntakeGraphBindingStore bindingStore;

    public IntakeDomainSnapshotPublisher(
            IntakeImmutablePayloadPublisher payloadPublisher,
            IntakeGraphBindingStore bindingStore) {
        this.payloadPublisher = Objects.requireNonNull(payloadPublisher, "payloadPublisher");
        this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore");
    }

    public IntakeGraphBindingStore.WriteReceipt<IntakeSnapshotReference> publish(
            SnapshotRequest request) {
        Objects.requireNonNull(request, "request");
        IntakeGraphThreadBinding binding = request.threadBinding();
        IntakePrivateThreadRegistration registration = binding.registration();
        registration.requireCanonicalHash();
        if (request.createdAt().isBefore(registration.issuedAt())) {
            throw new IllegalArgumentException("snapshot cannot predate its thread registration");
        }
        validateMessages(request, registration.actorScope().audience());
        IntakePrivatePayloadValidator.requireSafeObject(
                request.initialCaseFacts(), "initialCaseFacts");
        IntakePrivatePayloadValidator.requireSafeObject(
                request.shareableProjection(), "shareableProjection");
        IntakePrivatePayloadValidator.requireSafeObject(
                request.currentDossier(), "currentDossier");

        ObjectNode payload = snapshotPayload(request, registration);
        String snapshotHash = ContractJson.sha256Hex(payload);
        payload.put("snapshot_hash", snapshotHash);
        byte[] bytes = ContractJson.canonicalize(payload);
        if (bytes.length > IntakeContractSupport.SNAPSHOT_MAX_BYTES) {
            throw new IllegalArgumentException("snapshot exceeds 256 KiB");
        }
        PublishRequest publishRequest =
                new PublishRequest(
                        request.snapshotId(),
                        SCHEMA_VERSION,
                        snapshotHash,
                        bytes,
                        IntakeContractSupport.SNAPSHOT_MAX_BYTES);
        var stored = payloadPublisher.publish(publishRequest);
        RoomGraphCommand.SnapshotRef payloadRef =
                IntakePayloadReferences.requireExact(publishRequest, stored);
        IntakeSnapshotReference reference =
                new IntakeSnapshotReference(
                        request.snapshotId(),
                        registration.registrationId(),
                        registration.tenantSurrogate(),
                        registration.caseId(),
                        registration.roomEpoch(),
                        binding.fencingToken(),
                        registration.threadId(),
                        registration.actorScopeHash(),
                        registration.agentSessionId(),
                        payloadRef,
                        stored.objectVersion(),
                        request.domainRevision(),
                        request.roomRevision(),
                        request.projectionRevision(),
                        request.createdAt());
        var receipt = Objects.requireNonNull(
                bindingStore.bindInitialSnapshot(reference), "snapshot binding receipt");
        if (!reference.equals(receipt.value())) {
            throw new IntakeGraphBindingConflictException(
                    "persisted initial snapshot differs from the published reference");
        }
        return receipt;
    }

    public static String operationKey(SnapshotRequest request) {
        IntakePrivateThreadRegistration registration = request.threadBinding().registration();
        return "intake.snapshot.publish:"
                + registration.caseId()
                + ":"
                + registration.roomEpoch()
                + ":"
                + registration.actorScopeHash()
                + ":"
                + request.domainRevision();
    }

    private static ObjectNode snapshotPayload(
            SnapshotRequest request, IntakePrivateThreadRegistration registration) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("snapshot_id", request.snapshotId());
        root.put("tenant_surrogate", registration.tenantSurrogate());
        root.put("case_id", registration.caseId());
        root.put("room_type", "INTAKE");
        root.put("room_epoch", registration.roomEpoch());
        root.put("thread_id", registration.threadId());
        root.put("actor_scope_hash", registration.actorScopeHash());
        root.put("agent_session_id", registration.agentSessionId());
        root.put("domain_revision", request.domainRevision());
        root.put("room_revision", request.roomRevision());
        root.put("projection_revision", request.projectionRevision());
        root.put("visibility", "PRIVATE");
        var sourceRefs = root.putArray("source_refs");
        request.sourceRefs().forEach(sourceRefs::add);
        root.set("initial_case_facts", request.initialCaseFacts().deepCopy());
        root.set("shareable_projection", request.shareableProjection().deepCopy());
        var messages = root.putArray("own_messages");
        for (OwnMessage message : request.ownMessages()) {
            ObjectNode item = messages.addObject();
            item.put("message_id", message.messageId());
            item.put("role", message.role().name());
            item.put("audience", message.audience().name());
            item.put("sequence", message.sequence());
            item.put("text", message.text());
            item.put("source_hash", message.sourceHash());
        }
        root.set("current_dossier", request.currentDossier().deepCopy());
        root.put("created_at", request.createdAt().toString());
        return root;
    }

    private static void validateMessages(SnapshotRequest request, Audience expectedAudience) {
        if (request.ownMessages().size() > 6) {
            throw new IllegalArgumentException("ownMessages exceeds the six-message window");
        }
        HashSet<String> messageIds = new HashSet<>();
        long previousSequence = -1;
        for (OwnMessage message : request.ownMessages()) {
            if (message.audience() != expectedAudience) {
                throw new IllegalArgumentException("snapshot message crosses the actor audience");
            }
            if (!request.sourceRefs().contains(message.messageId())) {
                throw new IllegalArgumentException("snapshot message is missing its source reference");
            }
            if (!messageIds.add(message.messageId()) || message.sequence() <= previousSequence) {
                throw new IllegalArgumentException(
                        "snapshot messages require unique ids and increasing sequence");
            }
            previousSequence = message.sequence();
        }
    }

    public record SnapshotRequest(
            String snapshotId,
            IntakeGraphThreadBinding threadBinding,
            long domainRevision,
            long roomRevision,
            long projectionRevision,
            List<String> sourceRefs,
            JsonNode initialCaseFacts,
            JsonNode shareableProjection,
            List<OwnMessage> ownMessages,
            JsonNode currentDossier,
            Instant createdAt) {

        public SnapshotRequest {
            snapshotId = IntakeContractSupport.identifier(snapshotId, "snapshotId");
            threadBinding = Objects.requireNonNull(threadBinding, "threadBinding");
            IntakeContractSupport.nonNegative(domainRevision, "domainRevision");
            IntakeContractSupport.nonNegative(roomRevision, "roomRevision");
            IntakeContractSupport.nonNegative(projectionRevision, "projectionRevision");
            sourceRefs = IntakeContractSupport.identifiers(sourceRefs, 1, 128, "sourceRefs");
            initialCaseFacts = IntakeContractSupport.immutableJson(
                    initialCaseFacts, "initialCaseFacts");
            shareableProjection = IntakeContractSupport.immutableJson(
                    shareableProjection, "shareableProjection");
            ownMessages = List.copyOf(Objects.requireNonNull(ownMessages, "ownMessages"));
            currentDossier = IntakeContractSupport.immutableJson(
                    currentDossier, "currentDossier");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }

        @Override
        public JsonNode initialCaseFacts() {
            return initialCaseFacts.deepCopy();
        }

        @Override
        public JsonNode shareableProjection() {
            return shareableProjection.deepCopy();
        }

        @Override
        public JsonNode currentDossier() {
            return currentDossier.deepCopy();
        }
    }

    public record OwnMessage(
            String messageId,
            MessageRole role,
            Audience audience,
            long sequence,
            String text,
            String sourceHash) {

        public OwnMessage {
            messageId = IntakeContractSupport.identifier(messageId, "messageId");
            role = Objects.requireNonNull(role, "role");
            if (audience != Audience.USER && audience != Audience.MERCHANT) {
                throw new IllegalArgumentException("message audience must be USER or MERCHANT");
            }
            IntakeContractSupport.nonNegative(sequence, "sequence");
            if (text == null || text.length() > 8192) {
                throw new IllegalArgumentException("message text exceeds 8192 characters");
            }
            sourceHash = IntakeContractSupport.sha256(sourceHash, "sourceHash");
        }
    }

    public enum MessageRole {
        HUMAN,
        AI
    }
}
