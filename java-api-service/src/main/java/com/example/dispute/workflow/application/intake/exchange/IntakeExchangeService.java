package com.example.dispute.workflow.application.intake.exchange;

import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.PayloadLoadClaim;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.PayloadLoadGrant;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.ProposalPutClaim;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.ProposalPutGrant;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.PayloadLoadReceipt;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.PayloadLoadRequest;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.PayloadLoadResponse;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ProposalPutReceipt;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ProposalPutRequest;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ProposalPutResponse;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeObjectStore.LoadedPayload;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeObjectStore.StoredProposal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Base64;
import java.util.Objects;

/** Application boundary for Java-authorized immutable Intake payload exchange. */
public final class IntakeExchangeService {

    private final IntakeExchangeAuthorityValidationPort authority;
    private final IntakeExchangeObjectStore objectStore;
    private final IntakeExchangeCanonicalPayloadValidator validator;

    public IntakeExchangeService(
            IntakeExchangeAuthorityValidationPort authority,
            IntakeExchangeObjectStore objectStore,
            IntakeExchangeCanonicalPayloadValidator validator) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public PayloadLoadResponse load(PayloadLoadRequest request) {
        Objects.requireNonNull(request, "request");
        PayloadLoadGrant grant = Objects.requireNonNull(
                authority.requirePayloadLoad(new PayloadLoadClaim(request)),
                "payload authority grant");
        if (!request.equals(grant.request())) {
            throw rejected("payload authority grant differs from the exact request");
        }
        LoadedPayload stored = Objects.requireNonNull(objectStore.load(grant), "loaded payload");
        requireExactLoadReceipt(request, grant, stored);
        byte[] payload = stored.canonicalPayload();
        validator.requireValid(
                stored.schemaVersion(), stored.sha256(), stored.sizeBytes(), payload);
        PayloadLoadReceipt receipt = new PayloadLoadReceipt(
                "intake-payload-load-receipt.v1",
                stored.artifactId(),
                stored.schemaVersion(),
                stored.uri(),
                stored.objectVersion(),
                stored.sha256(),
                stored.sizeBytes());
        return new PayloadLoadResponse(
                "intake-payload-load-response.v1",
                request.authority(),
                receipt,
                Base64.getEncoder().encodeToString(payload));
    }

    public ProposalPutResponse put(ProposalPutRequest request) {
        Objects.requireNonNull(request, "request");
        requireExactIdempotencyKey(request);
        byte[] payload = decodeCanonicalBase64(request.proposal().canonicalPayloadBase64());
        JsonNode document = validator.requireValid(
                request.proposal().schemaVersion(),
                request.proposal().sha256(),
                request.proposal().sizeBytes(),
                payload);
        requireProposalExecutionBinding(request, document);
        ProposalPutGrant grant = Objects.requireNonNull(
                authority.requireProposalPut(new ProposalPutClaim(request, document)),
                "proposal authority grant");
        if (!request.equals(grant.request())) {
            throw rejected("proposal authority grant differs from the exact request");
        }
        StoredProposal stored = Objects.requireNonNull(
                objectStore.put(grant, payload), "stored proposal");
        requireExactPutReceipt(request, stored);
        ProposalPutReceipt receipt = new ProposalPutReceipt(
                "intake-proposal-put-receipt.v1",
                stored.artifactId(),
                stored.schemaVersion(),
                stored.uri(),
                stored.objectVersion(),
                stored.sha256(),
                stored.sizeBytes());
        return new ProposalPutResponse(
                "intake-proposal-put-response.v1",
                request.authority(),
                request.checkpointNs(),
                request.checkpointId(),
                request.cognitiveRevision(),
                receipt);
    }

    private static void requireExactLoadReceipt(
            PayloadLoadRequest request, PayloadLoadGrant grant, LoadedPayload stored) {
        var expected = request.objectRef();
        if (!expected.artifactId().equals(stored.artifactId())
                || !expected.schemaVersion().equals(stored.schemaVersion())
                || !expected.uri().equals(stored.uri())
                || !grant.objectVersion().equals(stored.objectVersion())
                || !expected.sha256().equals(stored.sha256())
                || expected.sizeBytes() != stored.sizeBytes()) {
            throw rejected("immutable payload receipt differs from the exact authority grant");
        }
        IntakeExchangeUris.requireCanonical(stored.uri());
        IntakeExchangeContract.identifier(stored.objectVersion(), "objectVersion");
    }

    private static void requireExactPutReceipt(ProposalPutRequest request, StoredProposal stored) {
        var expected = request.proposal();
        if (!expected.artifactId().equals(stored.artifactId())
                || !expected.schemaVersion().equals(stored.schemaVersion())
                || !expected.sha256().equals(stored.sha256())
                || expected.sizeBytes() != stored.sizeBytes()) {
            throw rejected("immutable proposal receipt differs from canonical bytes");
        }
        IntakeExchangeUris.requireCanonical(stored.uri());
        IntakeExchangeContract.identifier(stored.objectVersion(), "objectVersion");
    }

    private static void requireExactIdempotencyKey(ProposalPutRequest request) {
        String expected = "intake.proposal:"
                + request.authority().threadId()
                + ":"
                + request.authority().commandId()
                + ":"
                + request.proposal().sha256();
        if (!expected.equals(request.idempotencyKey())) {
            throw rejected("proposal idempotency key differs from its content address");
        }
    }

    private static byte[] decodeCanonicalBase64(String value) {
        try {
            byte[] payload = Base64.getDecoder().decode(value);
            if (!Base64.getEncoder().encodeToString(payload).equals(value)) {
                throw rejected("proposal payload is not canonical base64");
            }
            return payload;
        } catch (IllegalArgumentException failure) {
            throw rejected("proposal payload is not canonical base64", failure);
        }
    }

    private static void requireProposalExecutionBinding(
            ProposalPutRequest request, JsonNode proposal) {
        var expected = request.authority();
        requireText(proposal, "command_id", expected.commandId());
        requireText(proposal, "logical_run_id", expected.logicalRunId());
        requireText(proposal, "attempt_id", expected.attemptId());
        requireText(proposal, "case_id", expected.caseId());
        requireLong(proposal, "room_epoch", expected.roomEpoch());
        requireText(proposal, "thread_id", expected.threadId());
        requireText(proposal, "actor_scope_hash", expected.actorScopeHash());
        requireText(proposal, "agent_session_id", expected.agentSessionId());
        requireLong(proposal, "cognitive_revision", request.cognitiveRevision());
        JsonNode profiles = proposal.path("profile_versions");
        if (!profiles.isObject()
                || !expected.graphVersion().equals(profiles.path("graph_version").asText(null))
                || !expected.checkpointSchemaVersion()
                        .equals(profiles.path("checkpoint_schema_version").asText(null))) {
            throw rejected("proposal profile binding differs from the exact authority");
        }
    }

    private static void requireText(JsonNode document, String field, String expected) {
        if (!expected.equals(document.path(field).asText(null))) {
            throw rejected("proposal " + field + " differs from the exact authority");
        }
    }

    private static void requireLong(JsonNode document, String field, long expected) {
        JsonNode value = document.path(field);
        if (!value.isIntegralNumber() || value.longValue() != expected) {
            throw rejected("proposal " + field + " differs from the exact authority");
        }
    }

    private static IntakeExchangeAuthorityValidationPort.Rejected rejected(String message) {
        return new IntakeExchangeAuthorityValidationPort.Rejected(message);
    }

    private static IntakeExchangeAuthorityValidationPort.Rejected rejected(
            String message, Throwable cause) {
        return new IntakeExchangeAuthorityValidationPort.Rejected(message, cause);
    }
}
