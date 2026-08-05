package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.PublishRequest;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Publishes one accepted formal event while retaining only its ordered immutable reference. */
public final class IntakeTurnEventPublisher {

    private static final String SCHEMA_VERSION = "intake-turn-event.v2";

    private final IntakeImmutablePayloadPublisher payloadPublisher;
    private final IntakeGraphBindingStore bindingStore;

    public IntakeTurnEventPublisher(
            IntakeImmutablePayloadPublisher payloadPublisher,
            IntakeGraphBindingStore bindingStore) {
        this.payloadPublisher = Objects.requireNonNull(payloadPublisher, "payloadPublisher");
        this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore");
    }

    public IntakeGraphBindingStore.WriteReceipt<IntakeEventReference> publish(
            EventRequest request) {
        Objects.requireNonNull(request, "request");
        IntakeGraphThreadBinding binding = request.threadBinding();
        IntakePrivateThreadRegistration registration = binding.registration();
        registration.requireCanonicalHash();
        if (request.occurredAt().isBefore(registration.issuedAt())) {
            throw new IllegalArgumentException("event cannot predate its thread registration");
        }
        if (request.audience() != registration.actorScope().audience()) {
            throw new IllegalArgumentException("event audience crosses the private actor scope");
        }
        if (!request.sourceRefs().contains(request.messageId())) {
            throw new IllegalArgumentException("event sourceRefs must contain messageId");
        }

        ObjectNode payload = eventPayload(request, registration);
        String eventHash = ContractJson.sha256Hex(payload);
        payload.put("event_hash", eventHash);
        byte[] bytes = ContractJson.canonicalize(payload);
        if (bytes.length > IntakeContractSupport.EVENT_MAX_BYTES) {
            throw new IllegalArgumentException("event exceeds 32 KiB");
        }
        PublishRequest publishRequest =
                new PublishRequest(
                        request.eventId(),
                        SCHEMA_VERSION,
                        eventHash,
                        bytes,
                        IntakeContractSupport.EVENT_MAX_BYTES);
        var stored = payloadPublisher.publish(publishRequest);
        RoomGraphCommand.SnapshotRef payloadRef =
                IntakePayloadReferences.requireExact(publishRequest, stored);
        IntakeEventReference reference =
                new IntakeEventReference(
                        request.eventId(),
                        registration.registrationId(),
                        request.eventId(),
                        request.messageId(),
                        registration.tenantSurrogate(),
                        registration.caseId(),
                        registration.roomEpoch(),
                        binding.fencingToken(),
                        registration.threadId(),
                        registration.actorScopeHash(),
                        registration.agentSessionId(),
                        payloadRef,
                        stored.objectVersion(),
                        request.sequenceNo(),
                        request.domainRevision(),
                        request.audience(),
                        request.occurredAt(),
                        request.publishedAt());
        var receipt =
                Objects.requireNonNull(bindingStore.bindEvent(reference), "event binding receipt");
        if (!reference.equals(receipt.value())) {
            throw new IntakeGraphBindingConflictException(
                    "persisted event differs from the published reference");
        }
        return receipt;
    }

    /** Allocates a durable per-thread event slot or returns the exact replayed event. */
    public IntakeGraphBindingStore.EventAllocation allocate(
            IntakeGraphThreadBinding threadBinding, String eventId, String messageId) {
        Objects.requireNonNull(threadBinding, "threadBinding");
        return bindingStore.allocateEvent(
                threadBinding.registration().registrationId(),
                IntakeContractSupport.identifier(eventId, "eventId"),
                IntakeContractSupport.identifier(messageId, "messageId"));
    }

    private static ObjectNode eventPayload(
            EventRequest request, IntakePrivateThreadRegistration registration) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("event_id", request.eventId());
        root.put("message_id", request.messageId());
        root.put("tenant_surrogate", registration.tenantSurrogate());
        root.put("case_id", registration.caseId());
        root.put("room_type", "INTAKE");
        root.put("room_epoch", registration.roomEpoch());
        root.put("thread_id", registration.threadId());
        root.put("actor_scope_hash", registration.actorScopeHash());
        root.put("agent_session_id", registration.agentSessionId());
        root.put("sequence_no", request.sequenceNo());
        root.put("domain_revision", request.domainRevision());
        root.put("audience", request.audience().name());
        root.put("source_type", request.sourceType().name());
        root.put("text", request.text());
        var refs = root.putArray("source_refs");
        request.sourceRefs().forEach(refs::add);
        root.put("occurred_at", request.occurredAt().toString());
        return root;
    }

    public record EventRequest(
            String eventId,
            String messageId,
            IntakeGraphThreadBinding threadBinding,
            long sequenceNo,
            long domainRevision,
            Audience audience,
            SourceType sourceType,
            String text,
            List<String> sourceRefs,
            Instant occurredAt,
            Instant publishedAt) {

        public EventRequest {
            eventId = IntakeContractSupport.identifier(eventId, "eventId");
            messageId = IntakeContractSupport.identifier(messageId, "messageId");
            threadBinding = Objects.requireNonNull(threadBinding, "threadBinding");
            IntakeContractSupport.positive(sequenceNo, "sequenceNo");
            IntakeContractSupport.nonNegative(domainRevision, "domainRevision");
            if (audience != Audience.USER && audience != Audience.MERCHANT) {
                throw new IllegalArgumentException("event audience must be USER or MERCHANT");
            }
            sourceType = Objects.requireNonNull(sourceType, "sourceType");
            text = IntakeContractSupport.boundedText(text, 8192, "text");
            sourceRefs = IntakeContractSupport.identifiers(sourceRefs, 1, 32, "sourceRefs");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
            if (publishedAt.isBefore(occurredAt)) {
                throw new IllegalArgumentException("publishedAt cannot precede occurredAt");
            }
        }
    }

    public enum SourceType {
        INITIAL_FORM,
        ROOM_MESSAGE,
        FORMAL_EVENT,
        RESPONDENT_OPENING
    }
}
