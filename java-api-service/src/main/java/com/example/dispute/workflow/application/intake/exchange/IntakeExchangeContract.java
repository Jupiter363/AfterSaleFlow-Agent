package com.example.dispute.workflow.application.intake.exchange;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact Java mirror of the frozen Python Intake exchange wire DTOs. */
public final class IntakeExchangeContract {

    public static final int SNAPSHOT_MAX_BYTES = 256 * 1024;
    public static final int EVENT_MAX_BYTES = 32 * 1024;
    public static final int PROPOSAL_MAX_BYTES = 64 * 1024;

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern THREAD_ID = Pattern.compile("grt\\.v1\\.[0-9a-f]{32}");
    private static final Set<String> EXCHANGE_GRAPH_KEYS =
            Set.of("intake.v2", "all-rooms.target-e2e.v1");

    private IntakeExchangeContract() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Authority(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            String roomType,
            long roomEpoch,
            String threadId,
            String actorId,
            ActorRole actorRole,
            Audience audience,
            List<String> actorCapabilities,
            String actorScopeHash,
            String agentSessionId,
            String commandId,
            String logicalRunId,
            String attemptId,
            String requestHash,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            long processRevision,
            String stageCode,
            long stageSequence) {

        public Authority {
            exact(schemaVersion, "intake-exchange-authority.v1", "authority schema");
            identifier(tenantSurrogate, "tenantSurrogate");
            bounded(caseId, 1, 64, "caseId");
            exact(roomType, "INTAKE", "roomType");
            nonNegative(roomEpoch, "roomEpoch");
            requireThreadId(threadId);
            identifier(actorId, "actorId");
            if (actorRole != ActorRole.USER && actorRole != ActorRole.MERCHANT) {
                throw invalid("actorRole must be USER or MERCHANT");
            }
            Audience expected = actorRole == ActorRole.USER ? Audience.USER : Audience.MERCHANT;
            if (audience != expected) {
                throw invalid("private Intake actor and audience must match");
            }
            actorCapabilities = List.copyOf(
                    Objects.requireNonNull(actorCapabilities, "actorCapabilities"));
            if (actorCapabilities.size() > 32
                    || new HashSet<>(actorCapabilities).size() != actorCapabilities.size()) {
                throw invalid("actorCapabilities must contain at most 32 unique identifiers");
            }
            actorCapabilities.forEach(value -> identifier(value, "actorCapabilities"));
            requireSha256(actorScopeHash, "actorScopeHash");
            identifier(agentSessionId, "agentSessionId");
            identifier(commandId, "commandId");
            identifier(logicalRunId, "logicalRunId");
            identifier(attemptId, "attemptId");
            requireSha256(requestHash, "requestHash");
            if (!EXCHANGE_GRAPH_KEYS.contains(graphKey)) {
                throw invalid("graphKey is not an allowed Intake exchange graph");
            }
            identifier(graphVersion, "graphVersion");
            identifier(checkpointSchemaVersion, "checkpointSchemaVersion");
            nonNegative(processRevision, "processRevision");
            identifier(stageCode, "stageCode");
            nonNegative(stageSequence, "stageSequence");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ObjectReference(
            String artifactId,
            String schemaVersion,
            String uri,
            String sha256,
            long sizeBytes) {

        public ObjectReference {
            identifier(artifactId, "artifactId");
            int maximum = payloadMaximum(schemaVersion);
            IntakeExchangeUris.requireCanonical(uri);
            requireSha256(sha256, "sha256");
            boundedSize(sizeBytes, maximum, "payload");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PayloadLoadRequest(
            String schemaVersion, Authority authority, ObjectReference objectRef) {

        public PayloadLoadRequest {
            exact(schemaVersion, "intake-payload-load-request.v1", "load request schema");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(objectRef, "objectRef");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PayloadLoadReceipt(
            String schemaVersion,
            String artifactId,
            String contentSchemaVersion,
            String uri,
            String objectVersion,
            String sha256,
            long sizeBytes) {

        public PayloadLoadReceipt {
            exact(schemaVersion, "intake-payload-load-receipt.v1", "load receipt schema");
            identifier(artifactId, "artifactId");
            int maximum = payloadMaximum(contentSchemaVersion);
            IntakeExchangeUris.requireCanonical(uri);
            identifier(objectVersion, "objectVersion");
            requireSha256(sha256, "sha256");
            boundedSize(sizeBytes, maximum, "payload receipt");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PayloadLoadResponse(
            String schemaVersion,
            Authority authority,
            PayloadLoadReceipt receipt,
            String canonicalPayloadBase64) {

        public PayloadLoadResponse {
            exact(schemaVersion, "intake-payload-load-response.v1", "load response schema");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(receipt, "receipt");
            bounded(canonicalPayloadBase64, 4, 350_000, "canonicalPayloadBase64");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProposalDocument(
            String artifactId,
            String schemaVersion,
            String sha256,
            long sizeBytes,
            String canonicalPayloadBase64) {

        public ProposalDocument {
            identifier(artifactId, "artifactId");
            exact(schemaVersion, "intake-turn-proposal.v2", "proposal schema");
            requireSha256(sha256, "sha256");
            boundedSize(sizeBytes, PROPOSAL_MAX_BYTES, "proposal");
            bounded(canonicalPayloadBase64, 4, 90_000, "canonicalPayloadBase64");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProposalPutRequest(
            String schemaVersion,
            Authority authority,
            String idempotencyKey,
            String checkpointNs,
            String checkpointId,
            long cognitiveRevision,
            ProposalDocument proposal) {

        public ProposalPutRequest {
            exact(schemaVersion, "intake-proposal-put-request.v1", "put request schema");
            Objects.requireNonNull(authority, "authority");
            bounded(idempotencyKey, 1, 512, "idempotencyKey");
            bounded(checkpointNs, 0, 128, "checkpointNs");
            bounded(checkpointId, 1, 128, "checkpointId");
            positive(cognitiveRevision, "cognitiveRevision");
            Objects.requireNonNull(proposal, "proposal");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProposalPutReceipt(
            String schemaVersion,
            String artifactId,
            String contentSchemaVersion,
            String uri,
            String objectVersion,
            String sha256,
            long sizeBytes) {

        public ProposalPutReceipt {
            exact(schemaVersion, "intake-proposal-put-receipt.v1", "put receipt schema");
            identifier(artifactId, "artifactId");
            exact(contentSchemaVersion, "intake-turn-proposal.v2", "proposal content schema");
            IntakeExchangeUris.requireCanonical(uri);
            identifier(objectVersion, "objectVersion");
            requireSha256(sha256, "sha256");
            boundedSize(sizeBytes, PROPOSAL_MAX_BYTES, "proposal receipt");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProposalPutResponse(
            String schemaVersion,
            Authority authority,
            String checkpointNs,
            String checkpointId,
            long cognitiveRevision,
            ProposalPutReceipt receipt) {

        public ProposalPutResponse {
            exact(schemaVersion, "intake-proposal-put-response.v1", "put response schema");
            Objects.requireNonNull(authority, "authority");
            bounded(checkpointNs, 0, 128, "checkpointNs");
            bounded(checkpointId, 1, 128, "checkpointId");
            positive(cognitiveRevision, "cognitiveRevision");
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    static int payloadMaximum(String schemaVersion) {
        return switch (schemaVersion) {
            case "intake-domain-snapshot.v2" -> SNAPSHOT_MAX_BYTES;
            case "intake-turn-event.v2" -> EVENT_MAX_BYTES;
            default -> throw invalid("payload schema is not loadable");
        };
    }

    public static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw invalid(field + " must be a bounded identifier");
        }
        return value;
    }

    public static String requireSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw invalid(field + " must be lowercase SHA-256");
        }
        return value;
    }

    private static void requireThreadId(String value) {
        if (value == null || !THREAD_ID.matcher(value).matches()) {
            throw invalid("threadId is invalid");
        }
    }

    private static String bounded(String value, int minimum, int maximum, String field) {
        if (value == null
                || value.length() < minimum
                || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + " exceeds its text bound");
        }
        return value;
    }

    private static void boundedSize(long value, int maximum, String field) {
        if (value <= 0 || value > maximum) {
            throw invalid(field + " exceeds its byte bound");
        }
    }

    private static void nonNegative(long value, String field) {
        if (value < 0) {
            throw invalid(field + " must be non-negative");
        }
    }

    private static void positive(long value, String field) {
        if (value <= 0) {
            throw invalid(field + " must be positive");
        }
    }

    private static void exact(String value, String expected, String field) {
        if (!expected.equals(value)) {
            throw invalid(field + " must be " + expected);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
