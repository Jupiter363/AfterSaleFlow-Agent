package com.example.dispute.workflow.shadow.intake.admission;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.AdmissionAttempt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/** Strict compact-JWS verifier for the engineering-only synthetic Intake admission token. */
public final class Es256IntakeSyntheticAdmissionVerifier {

    public static final String TOKEN_TYPE = "intake-synthetic-admission+jwt";
    private static final int MAXIMUM_COMPACT_JWS_CHARACTERS = 16_384;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Set<String> HEADER_FIELDS = Set.of("alg", "typ", "kid");
    private static final Set<String> CLAIM_FIELDS = Set.of(
            "schema_version", "iss", "aud", "sub", "jti", "iat", "nbf", "exp",
            "room_type", "writer_mode", "tenant_surrogate", "case_id", "room_epoch",
            "fencing_token", "command_id", "command_sequence", "command_type", "party",
            "payload_ref", "payload_hash", "command_operation_key", "process_revision", "room_revision",
            "actor_scope_hash", "request_hash", "thread_id",
            "agent_session_id", "deadline_epoch_millis", "retry_budget", "logical_run_id",
            "attempt_id", "selection_hash", "registration_hash", "pins",
            "parity_baseline_ref", "parity_baseline_hash");
    private static final Set<String> RETRY_FIELDS = Set.of(
            "schema_version", "provider_attempts_remaining", "activity_attempts_remaining",
            "repairs_remaining");
    private static final Set<String> PIN_FIELDS = Set.of(
            "case_workflow_type", "case_workflow_build_id", "room_workflow_type",
            "room_workflow_build_id", "process_contract_version", "graph_key", "graph_version",
            "checkpoint_schema_version", "state_schema_version", "stream_protocol",
            "prompt_version", "model_profile_id", "output_schema_version", "policy_version",
            "guardrail_version", "tool_policy_version", "cohort_policy_version", "agent_key",
            "agent_session_profile_version", "memory_policy_id");

    private final IntakeSyntheticAdmissionTrustSet trustSet;
    private final Clock clock;

    public Es256IntakeSyntheticAdmissionVerifier(
            IntakeSyntheticAdmissionTrustSet trustSet, Clock clock) {
        this.trustSet = Objects.requireNonNull(trustSet, "trustSet must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public VerifiedToken verify(AdmissionAttempt attempt, IntakeWorkflowCommand command) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (attempt.trafficSource() != TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC
                || !attempt.hasSignatureEvidence()) {
            throw rejected("ADMISSION_EVIDENCE_INVALID", "valid signed synthetic evidence is required");
        }
        String compactJws = attempt.compactJws();
        if (compactJws.length() > MAXIMUM_COMPACT_JWS_CHARACTERS) {
            throw rejected("ADMISSION_EVIDENCE_INVALID", "compact JWS exceeds its bound");
        }
        String[] segments = compactJws.split("\\.", -1);
        if (segments.length != 3) {
            throw rejected("ADMISSION_EVIDENCE_INVALID", "compact JWS must contain three segments");
        }

        JsonNode header = decodeObject(segments[0], "protected header");
        requireExactFields(header, HEADER_FIELDS, "protected header");
        requireText(header, "alg", "ES256");
        requireText(header, "typ", TOKEN_TYPE);
        String keyId = text(header, "kid");
        if (!keyId.equals(attempt.signingKeyId())) {
            throw rejected("ADMISSION_KEY_REJECTED", "attempt key ID does not match the protected header");
        }

        byte[] signature = decodeCanonicalSegment(segments[2], "signature");
        if (signature.length != 64) {
            throw rejected("ADMISSION_SIGNATURE_INVALID", "ES256 signature must be 64-byte R || S");
        }
        verifySignature(keyId, segments[0] + "." + segments[1], signature);

        JsonNode payload = decodeObject(segments[1], "claims");
        requireExactFields(payload, CLAIM_FIELDS, "claims");
        IntakeSyntheticAdmissionClaims claims = claims(payload);
        if (!claims.isValidAt(clock.instant())) {
            throw rejected("ADMISSION_TIME_INVALID", "admission token is not currently valid");
        }
        requireAttemptMatch(attempt, claims);
        requireCommandMatch(command, claims);

        String envelopeHash = sha256Hex(compactJws.getBytes(StandardCharsets.US_ASCII));
        if (!envelopeHash.equals(attempt.signedEnvelopeHash())) {
            throw rejected("ADMISSION_EVIDENCE_INVALID", "compact JWS hash does not match the attempt");
        }
        byte[] canonicalClaims = ContractJson.canonicalize(payload);
        byte[] authorizationInput = new byte[keyId.length() + 1 + canonicalClaims.length];
        System.arraycopy(keyId.getBytes(StandardCharsets.US_ASCII), 0, authorizationInput, 0, keyId.length());
        authorizationInput[keyId.length()] = '\n';
        System.arraycopy(canonicalClaims, 0, authorizationInput, keyId.length() + 1, canonicalClaims.length);
        return new VerifiedToken(keyId, envelopeHash, sha256Hex(authorizationInput), claims);
    }

    private static IntakeSyntheticAdmissionClaims claims(JsonNode payload) {
        JsonNode retry = object(payload, "retry_budget");
        requireExactFields(retry, RETRY_FIELDS, "retry_budget");
        RetryBudget retryBudget = new RetryBudget(
                text(retry, "schema_version"),
                integer(retry, "provider_attempts_remaining"),
                integer(retry, "activity_attempts_remaining"),
                integer(retry, "repairs_remaining"));

        JsonNode pinsNode = object(payload, "pins");
        requireExactFields(pinsNode, PIN_FIELDS, "pins");
        IntakeSyntheticAdmissionClaims.Pins pins = new IntakeSyntheticAdmissionClaims.Pins(
                text(pinsNode, "case_workflow_type"),
                text(pinsNode, "case_workflow_build_id"),
                text(pinsNode, "room_workflow_type"),
                text(pinsNode, "room_workflow_build_id"),
                text(pinsNode, "process_contract_version"),
                text(pinsNode, "graph_key"),
                text(pinsNode, "graph_version"),
                text(pinsNode, "checkpoint_schema_version"),
                text(pinsNode, "state_schema_version"),
                text(pinsNode, "stream_protocol"),
                text(pinsNode, "prompt_version"),
                text(pinsNode, "model_profile_id"),
                text(pinsNode, "output_schema_version"),
                text(pinsNode, "policy_version"),
                text(pinsNode, "guardrail_version"),
                text(pinsNode, "tool_policy_version"),
                text(pinsNode, "cohort_policy_version"),
                text(pinsNode, "agent_key"),
                text(pinsNode, "agent_session_profile_version"),
                text(pinsNode, "memory_policy_id"));

        try {
            return new IntakeSyntheticAdmissionClaims(
                    text(payload, "schema_version"),
                    text(payload, "iss"),
                    text(payload, "aud"),
                    text(payload, "sub"),
                    text(payload, "jti"),
                    longInteger(payload, "iat"),
                    longInteger(payload, "nbf"),
                    longInteger(payload, "exp"),
                    text(payload, "room_type"),
                    text(payload, "writer_mode"),
                    text(payload, "tenant_surrogate"),
                    text(payload, "case_id"),
                    longInteger(payload, "room_epoch"),
                    longInteger(payload, "fencing_token"),
                    text(payload, "command_id"),
                    longInteger(payload, "command_sequence"),
                    IntakeCommandType.valueOf(text(payload, "command_type")),
                    IntakeParty.valueOf(text(payload, "party")),
                    text(payload, "payload_ref"),
                    text(payload, "payload_hash"),
                    text(payload, "command_operation_key"),
                    longInteger(payload, "process_revision"),
                    longInteger(payload, "room_revision"),
                    text(payload, "actor_scope_hash"),
                    text(payload, "request_hash"),
                    text(payload, "thread_id"),
                    text(payload, "agent_session_id"),
                    longInteger(payload, "deadline_epoch_millis"),
                    retryBudget,
                    text(payload, "logical_run_id"),
                    text(payload, "attempt_id"),
                    text(payload, "selection_hash"),
                    text(payload, "registration_hash"),
                    pins,
                    text(payload, "parity_baseline_ref"),
                    text(payload, "parity_baseline_hash"));
        } catch (IllegalArgumentException exception) {
            throw rejected("ADMISSION_CLAIMS_INVALID", "admission claims contain an invalid enum", exception);
        }
    }

    private void verifySignature(String keyId, String signingInput, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(trustSet.resolve(keyId));
            verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signature)) {
                throw rejected("ADMISSION_SIGNATURE_INVALID", "ES256 admission signature is invalid");
            }
        } catch (GeneralSecurityException exception) {
            throw rejected("ADMISSION_SIGNATURE_INVALID", "ES256 admission verification failed", exception);
        }
    }

    private static void requireAttemptMatch(
            AdmissionAttempt attempt, IntakeSyntheticAdmissionClaims claims) {
        if (!claims.threadId().equals(attempt.threadId())
                || !claims.agentSessionId().equals(attempt.agentSessionId())
                || claims.deadlineEpochMillis() != attempt.deadlineEpochMillis()
                || !claims.retryBudget().equals(attempt.retryBudget())) {
            throw rejected("ADMISSION_ATTEMPT_MISMATCH", "signed claims do not match the admission attempt");
        }
    }

    private static void requireCommandMatch(
            IntakeWorkflowCommand command, IntakeSyntheticAdmissionClaims claims) {
        boolean exact = claims.tenantSurrogate().equals(command.tenantSurrogate())
                && claims.caseId().equals(command.caseId())
                && claims.roomEpoch() == command.roomEpoch()
                && claims.fencingToken() == command.fencingToken()
                && claims.commandId().equals(command.commandId())
                && claims.commandSequence() == command.sequence()
                && claims.commandType() == command.commandType()
                && claims.party() == command.party()
                && claims.payloadRef().equals(command.payloadRef())
                && claims.payloadHash().equals(command.payloadHash())
                && claims.commandOperationKey().equals(command.operationKey())
                && claims.actorScopeHash().equals(command.actorScopeHash())
                && claims.requestHash().equals(command.requestHash());
        if (!exact || command.executionContext() != null || command.commandType() != IntakeCommandType.INTAKE_MESSAGE) {
            throw rejected("ADMISSION_COMMAND_MISMATCH", "signed claims do not match the inert message command");
        }
    }

    private static JsonNode decodeObject(String segment, String field) {
        byte[] decoded = decodeCanonicalSegment(segment, field);
        try {
            JsonNode value = JSON.readTree(decoded);
            if (value == null || !value.isObject()) {
                throw rejected("ADMISSION_JSON_INVALID", field + " must be a JSON object");
            }
            return value;
        } catch (IOException exception) {
            throw rejected("ADMISSION_JSON_INVALID", field + " is not strict JSON", exception);
        }
    }

    private static byte[] decodeCanonicalSegment(String segment, String field) {
        if (segment == null || segment.isEmpty() || segment.indexOf('=') >= 0) {
            throw rejected("ADMISSION_EVIDENCE_INVALID", field + " is not unpadded base64url");
        }
        try {
            byte[] decoded = BASE64_URL_DECODER.decode(segment);
            if (!BASE64_URL_ENCODER.encodeToString(decoded).equals(segment)) {
                throw rejected("ADMISSION_EVIDENCE_INVALID", field + " is not canonical base64url");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw rejected("ADMISSION_EVIDENCE_INVALID", field + " is not valid base64url", exception);
        }
    }

    private static void requireExactFields(JsonNode object, Set<String> expected, String field) {
        Set<String> actual = fieldNames(object);
        if (!actual.equals(expected)) {
            throw rejected("ADMISSION_FIELDS_INVALID", field + " has missing or unknown fields");
        }
    }

    private static Set<String> fieldNames(JsonNode object) {
        java.util.HashSet<String> fields = new java.util.HashSet<>();
        Iterator<String> names = object.fieldNames();
        names.forEachRemaining(fields::add);
        return Set.copyOf(fields);
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw rejected("ADMISSION_CLAIMS_INVALID", field + " must be an object");
        }
        return value;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw rejected("ADMISSION_CLAIMS_INVALID", field + " must be a string");
        }
        return value.textValue();
    }

    private static void requireText(JsonNode parent, String field, String expected) {
        if (!expected.equals(text(parent, field))) {
            throw rejected("ADMISSION_HEADER_INVALID", field + " must be " + expected);
        }
    }

    private static int integer(JsonNode parent, String field) {
        long value = longInteger(parent, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw rejected("ADMISSION_CLAIMS_INVALID", field + " exceeds integer bounds");
        }
        return (int) value;
    }

    private static long longInteger(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw rejected("ADMISSION_CLAIMS_INVALID", field + " must be an exact integer");
        }
        return value.longValue();
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static IntakeSyntheticAdmissionException rejected(String code, String message) {
        return new IntakeSyntheticAdmissionException(code, message);
    }

    private static IntakeSyntheticAdmissionException rejected(
            String code, String message, Throwable cause) {
        return new IntakeSyntheticAdmissionException(code, message, cause);
    }

    public record VerifiedToken(
            String keyId,
            String envelopeHash,
            String authorizationHash,
            IntakeSyntheticAdmissionClaims claims) {

        public VerifiedToken {
            IntakeSyntheticAdmissionTrustSet.requireKeyId(keyId);
            if (envelopeHash == null || !envelopeHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("envelopeHash must be lowercase SHA-256");
            }
            if (authorizationHash == null || !authorizationHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("authorizationHash must be lowercase SHA-256");
            }
            Objects.requireNonNull(claims, "claims must not be null");
        }
    }
}
