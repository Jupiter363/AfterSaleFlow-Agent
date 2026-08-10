package com.example.dispute.workflow.contract.v1;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable respondent-Submit lineage for reusing one exact bilateral matrix. */
public record FrozenIntakeSubmissionAuthority(
        String schemaVersion,
        String tenantSurrogate,
        String caseId,
        String respondentActorId,
        ActorRole respondentActorRole,
        String respondentCompletionId,
        String respondentCompletionStatus,
        Instant respondentCompletedAt,
        String submitOperation,
        String submitOperationKey,
        String submitCommandId,
        long submitCommandSequence,
        String submitRequestHash,
        String submitEventId,
        String submitEventRef,
        long submitEventSequence,
        String submitEventType,
        long sourceRoomEpoch,
        long sourceFencingToken,
        long sourceProcessRevision,
        long sourceRoomRevision,
        String dossierId,
        long dossierVersion,
        String matrixId,
        long matrixVersion,
        String matrixContentHash,
        String projectionRef,
        String authorityHash) {

    public static final String SCHEMA_VERSION = "frozen-intake-submission-authority.v1";
    public static final String MATRIX_SCHEMA_VERSION = "case_fact_matrix.v2";
    public static final String MATRIX_KIND = "BILATERAL_FROZEN";
    public static final String COMPLETION_STATUS = "COMPLETED";
    public static final String SUBMIT_OPERATION = "RESPONDENT_CONFIRM";
    public static final String SUBMIT_EVENT_TYPE = "RESPONDENT_CONFIRMED";
    public static final String FROZEN_MATRIX_RESULT_POINTER =
            "/result/frozen_submission/matrix";

    private static final String INTAKE_EVENT_REF_PREFIX =
            "urn:after-sale-flow:intake-event:";
    private static final Pattern KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");
    private static final Pattern MATRIX_ID = Pattern.compile("CASE_MATRIX_[A-F0-9]{20}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public FrozenIntakeSubmissionAuthority {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "schemaVersion must be " + SCHEMA_VERSION);
        }
        tenantSurrogate = requireText(tenantSurrogate, 128, "tenantSurrogate");
        caseId = requireText(caseId, 64, "caseId");
        respondentActorId = requireText(respondentActorId, 128, "respondentActorId");
        respondentActorRole =
                Objects.requireNonNull(respondentActorRole, "respondentActorRole");
        if (respondentActorRole != ActorRole.USER
                && respondentActorRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("respondentActorRole must be a party role");
        }
        respondentCompletionId = requireKey(respondentCompletionId, "respondentCompletionId");
        if (!COMPLETION_STATUS.equals(respondentCompletionStatus)) {
            throw new IllegalArgumentException(
                    "respondentCompletionStatus must be " + COMPLETION_STATUS);
        }
        respondentCompletedAt = Objects.requireNonNull(
                        respondentCompletedAt, "respondentCompletedAt")
                .truncatedTo(ChronoUnit.MICROS);
        if (!SUBMIT_OPERATION.equals(submitOperation)) {
            throw new IllegalArgumentException("submitOperation must be " + SUBMIT_OPERATION);
        }
        submitOperationKey = requireKey(submitOperationKey, "submitOperationKey");
        submitCommandId = requireKey(submitCommandId, "submitCommandId");
        if (submitCommandSequence < 1) {
            throw new IllegalArgumentException("submitCommandSequence must be positive");
        }
        submitRequestHash = requireHash(submitRequestHash, "submitRequestHash");
        submitEventId = requireKey(submitEventId, "submitEventId");
        submitEventRef = requireText(submitEventRef, 1024, "submitEventRef");
        if (!(INTAKE_EVENT_REF_PREFIX + submitEventId).equals(submitEventRef)) {
            throw new IllegalArgumentException("submitEventRef does not identify submitEventId");
        }
        if (submitEventSequence < 1) {
            throw new IllegalArgumentException("submitEventSequence must be positive");
        }
        if (!SUBMIT_EVENT_TYPE.equals(submitEventType)) {
            throw new IllegalArgumentException("submitEventType must be " + SUBMIT_EVENT_TYPE);
        }
        if (sourceRoomEpoch < 0
                || sourceFencingToken < 1
                || sourceProcessRevision < 1
                || sourceRoomRevision < 1) {
            throw new IllegalArgumentException("source Intake revision authority is invalid");
        }
        dossierId = requireKey(dossierId, "dossierId");
        if (dossierVersion < 1) {
            throw new IllegalArgumentException("dossierVersion must be positive");
        }
        if (matrixId == null || !MATRIX_ID.matcher(matrixId).matches()) {
            throw new IllegalArgumentException("matrixId is invalid");
        }
        if (matrixVersion < 2) {
            throw new IllegalArgumentException("matrixVersion must identify a bilateral matrix");
        }
        matrixContentHash = requireHash(matrixContentHash, "matrixContentHash");
        projectionRef = requireText(projectionRef, 1024, "projectionRef");
        if (!projectionRefFor(submitEventRef).equals(projectionRef)) {
            throw new IllegalArgumentException(
                    "projectionRef must locate the immutable Submit event matrix");
        }
        authorityHash = requireHash(authorityHash, "authorityHash");
        String expectedAuthorityHash = authorityHash(
                schemaVersion,
                tenantSurrogate,
                caseId,
                respondentActorId,
                respondentActorRole,
                respondentCompletionId,
                respondentCompletionStatus,
                respondentCompletedAt,
                submitOperation,
                submitOperationKey,
                submitCommandId,
                submitCommandSequence,
                submitRequestHash,
                submitEventId,
                submitEventRef,
                submitEventSequence,
                submitEventType,
                sourceRoomEpoch,
                sourceFencingToken,
                sourceProcessRevision,
                sourceRoomRevision,
                dossierId,
                dossierVersion,
                matrixId,
                matrixVersion,
                matrixContentHash,
                projectionRef);
        if (!authorityHash.equals(expectedAuthorityHash)) {
            throw new IllegalArgumentException("authorityHash is not canonical");
        }
    }

    public static FrozenIntakeSubmissionAuthority capture(
            String tenantSurrogate,
            String caseId,
            String respondentActorId,
            ActorRole respondentActorRole,
            String respondentCompletionId,
            String respondentCompletionStatus,
            Instant respondentCompletedAt,
            String submitOperationKey,
            String submitCommandId,
            long submitCommandSequence,
            String submitRequestHash,
            String submitEventId,
            String submitEventRef,
            long submitEventSequence,
            long sourceRoomEpoch,
            long sourceFencingToken,
            long sourceProcessRevision,
            long sourceRoomRevision,
            String dossierId,
            long dossierVersion,
            JsonNode matrix) {
        MatrixIdentity identity = matrixIdentity(matrix);
        if (!Objects.equals(caseId, identity.caseId())) {
            throw new IllegalArgumentException("matrix case does not match Submit authority");
        }
        String projectionRef = projectionRefFor(submitEventRef);
        Instant completedAt = Objects.requireNonNull(
                        respondentCompletedAt, "respondentCompletedAt")
                .truncatedTo(ChronoUnit.MICROS);
        String authorityHash = authorityHash(
                SCHEMA_VERSION,
                tenantSurrogate,
                caseId,
                respondentActorId,
                respondentActorRole,
                respondentCompletionId,
                respondentCompletionStatus,
                completedAt,
                SUBMIT_OPERATION,
                submitOperationKey,
                submitCommandId,
                submitCommandSequence,
                submitRequestHash,
                submitEventId,
                submitEventRef,
                submitEventSequence,
                SUBMIT_EVENT_TYPE,
                sourceRoomEpoch,
                sourceFencingToken,
                sourceProcessRevision,
                sourceRoomRevision,
                dossierId,
                dossierVersion,
                identity.matrixId(),
                identity.matrixVersion(),
                identity.contentHash(),
                projectionRef);
        return new FrozenIntakeSubmissionAuthority(
                SCHEMA_VERSION,
                tenantSurrogate,
                caseId,
                respondentActorId,
                respondentActorRole,
                respondentCompletionId,
                respondentCompletionStatus,
                completedAt,
                SUBMIT_OPERATION,
                submitOperationKey,
                submitCommandId,
                submitCommandSequence,
                submitRequestHash,
                submitEventId,
                submitEventRef,
                submitEventSequence,
                SUBMIT_EVENT_TYPE,
                sourceRoomEpoch,
                sourceFencingToken,
                sourceProcessRevision,
                sourceRoomRevision,
                dossierId,
                dossierVersion,
                identity.matrixId(),
                identity.matrixVersion(),
                identity.contentHash(),
                projectionRef,
                authorityHash);
    }

    /** The epoch projection hash is the matrix self-hash, never the authority or dossier hash. */
    public String projectionSha256() {
        return matrixContentHash;
    }

    public void requireProjectionPair(String candidateRef, String candidateSha256) {
        if (!projectionRef.equals(candidateRef) || !matrixContentHash.equals(candidateSha256)) {
            throw new IllegalArgumentException(
                    "projection pair does not identify the frozen Submit matrix");
        }
    }

    /** Verifies only the selected matrix node; surrounding dossier fields are intentionally ignored. */
    public void requireMatchesMatrix(JsonNode candidate) {
        MatrixIdentity identity = matrixIdentity(candidate);
        if (!caseId.equals(identity.caseId())
                || !matrixId.equals(identity.matrixId())
                || matrixVersion != identity.matrixVersion()
                || !matrixContentHash.equals(identity.contentHash())) {
            throw new IllegalArgumentException(
                    "matrix does not match the frozen Submit authority");
        }
    }

    public static String projectionRefFor(String submitEventRef) {
        return requireText(submitEventRef, 1024, "submitEventRef")
                + '#'
                + FROZEN_MATRIX_RESULT_POINTER;
    }

    private static MatrixIdentity matrixIdentity(JsonNode candidate) {
        if (!(candidate instanceof ObjectNode matrix)) {
            throw new IllegalArgumentException("frozen matrix must be an object");
        }
        String schemaVersion = matrix.path("schema_version").asText(null);
        String matrixKind = matrix.path("matrix_kind").asText(null);
        String caseId = matrix.path("case_id").asText(null);
        String matrixId = matrix.path("matrix_id").asText(null);
        JsonNode versionNode = matrix.path("matrix_version");
        JsonNode hashNode = matrix.path("content_hash");
        if (!MATRIX_SCHEMA_VERSION.equals(schemaVersion)
                || !MATRIX_KIND.equals(matrixKind)
                || caseId == null
                || caseId.isBlank()
                || matrixId == null
                || !MATRIX_ID.matcher(matrixId).matches()
                || !versionNode.isIntegralNumber()
                || !versionNode.canConvertToLong()
                || versionNode.longValue() < 2
                || !hashNode.isTextual()
                || !SHA256.matcher(hashNode.textValue()).matches()) {
            throw new IllegalArgumentException(
                    "matrix is not canonical case_fact_matrix.v2/BILATERAL_FROZEN authority");
        }
        ObjectNode hashInput = matrix.deepCopy();
        String contentHash = hashInput.remove("content_hash").textValue();
        if (!contentHash.equals(ContractJson.sha256Hex(hashInput))) {
            throw new IllegalArgumentException("matrix content_hash is not canonical");
        }
        ObjectNode matrixIdInput = hashInput.deepCopy();
        matrixIdInput.remove("matrix_id");
        String expectedMatrixId = "CASE_MATRIX_"
                + ContractJson.sha256Hex(matrixIdInput)
                        .substring(0, 20)
                        .toUpperCase(Locale.ROOT);
        if (!matrixId.equals(expectedMatrixId)) {
            throw new IllegalArgumentException("matrix_id is not canonical");
        }
        return new MatrixIdentity(caseId, matrixId, versionNode.longValue(), contentHash);
    }

    private static String authorityHash(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            String respondentActorId,
            ActorRole respondentActorRole,
            String respondentCompletionId,
            String respondentCompletionStatus,
            Instant respondentCompletedAt,
            String submitOperation,
            String submitOperationKey,
            String submitCommandId,
            long submitCommandSequence,
            String submitRequestHash,
            String submitEventId,
            String submitEventRef,
            long submitEventSequence,
            String submitEventType,
            long sourceRoomEpoch,
            long sourceFencingToken,
            long sourceProcessRevision,
            long sourceRoomRevision,
            String dossierId,
            long dossierVersion,
            String matrixId,
            long matrixVersion,
            String matrixContentHash,
            String projectionRef) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("schema_version", schemaVersion);
        value.put("tenant_surrogate", tenantSurrogate);
        value.put("case_id", caseId);
        value.put("respondent_actor_id", respondentActorId);
        value.put("respondent_actor_role", respondentActorRole.name());
        value.put("respondent_completion_id", respondentCompletionId);
        value.put("respondent_completion_status", respondentCompletionStatus);
        value.put("respondent_completed_at", respondentCompletedAt.toString());
        value.put("submit_operation", submitOperation);
        value.put("submit_operation_key", submitOperationKey);
        value.put("submit_command_id", submitCommandId);
        value.put("submit_command_sequence", submitCommandSequence);
        value.put("submit_request_hash", submitRequestHash);
        value.put("submit_event_id", submitEventId);
        value.put("submit_event_ref", submitEventRef);
        value.put("submit_event_sequence", submitEventSequence);
        value.put("submit_event_type", submitEventType);
        value.put("source_room_epoch", sourceRoomEpoch);
        value.put("source_fencing_token", sourceFencingToken);
        value.put("source_process_revision", sourceProcessRevision);
        value.put("source_room_revision", sourceRoomRevision);
        value.put("dossier_id", dossierId);
        value.put("dossier_version", dossierVersion);
        value.put("matrix_id", matrixId);
        value.put("matrix_version", matrixVersion);
        value.put("matrix_content_hash", matrixContentHash);
        value.put("projection_ref", projectionRef);
        return ContractJson.sha256Hex(value);
    }

    private static String requireKey(String value, String field) {
        String required = requireText(value, 512, field);
        if (!KEY.matcher(required).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return required;
    }

    private static String requireHash(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return value;
    }

    private static String requireText(String value, int maximumLength, String field) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private record MatrixIdentity(
            String caseId, String matrixId, long matrixVersion, String contentHash) {}
}
