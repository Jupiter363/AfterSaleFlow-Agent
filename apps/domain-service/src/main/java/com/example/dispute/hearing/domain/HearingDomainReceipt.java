package com.example.dispute.hearing.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Canonically hashed Java-committed receipt consumed by Hearing process orchestration. */
public record HearingDomainReceipt(
        String schemaVersion,
        String receiptId,
        String receiptHash,
        HearingAuthorityCommit.OperationType operationType,
        String operationKey,
        String requestHash,
        String tenantSurrogate,
        String caseId,
        String flowInstanceId,
        String epochId,
        long roomEpoch,
        HearingWriterMode writerMode,
        long fencingToken,
        HearingFlowStage sourceStage,
        int sourceStageSequence,
        long sourceProcessRevision,
        long sourceRoomRevision,
        HearingFlowStage stage,
        int stageSequence,
        Instant sharedDeadlineAt,
        long processRevision,
        long roomRevision,
        String resultRef,
        String resultHash,
        long committedEventSequence,
        String temporalNamespace,
        String temporalWorkflowId,
        String temporalRunId,
        String temporalBuildOrDeployment,
        Long temporalHistoryEventId,
        Instant committedAt) {

    public static final String SCHEMA_VERSION = "hearing-domain-receipt.v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RESULT_REF = Pattern.compile("(?:urn|s3|minio):[^\\s]{1,1019}");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public HearingDomainReceipt {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
        }
        HearingAuthorityExpectation.identifier(receiptId, "receiptId");
        hash(receiptHash, "receiptHash");
        Objects.requireNonNull(operationType, "operationType");
        if (operationKey == null || operationKey.length() > 512 || !operationKey.startsWith("hearing.")) {
            throw new IllegalArgumentException("operationKey must be a bounded Hearing operation key");
        }
        hash(requestHash, "requestHash");
        HearingAuthorityExpectation.identifier(tenantSurrogate, "tenantSurrogate");
        HearingAuthorityExpectation.identifier(caseId, "caseId");
        HearingAuthorityExpectation.identifier(flowInstanceId, "flowInstanceId");
        HearingAuthorityExpectation.identifier(epochId, "epochId");
        Objects.requireNonNull(writerMode, "writerMode");
        Objects.requireNonNull(sourceStage, "sourceStage");
        Objects.requireNonNull(stage, "stage");
        if (writerMode == HearingWriterMode.SHADOW) {
            throw new IllegalArgumentException("SHADOW cannot produce a formal Hearing domain receipt");
        }
        safeNonNegative(roomEpoch, "roomEpoch");
        safeNonNegative(fencingToken, "fencingToken");
        safeNonNegative(sourceProcessRevision, "sourceProcessRevision");
        safeNonNegative(sourceRoomRevision, "sourceRoomRevision");
        safeNonNegative(processRevision, "processRevision");
        safeNonNegative(roomRevision, "roomRevision");
        if (writerMode == HearingWriterMode.TEMPORAL && fencingToken < 1) {
            throw new IllegalArgumentException("TEMPORAL receipt requires a positive fence");
        }
        if (sourceStageSequence != sourceStage.ordinal() + 1
                || stageSequence != stage.ordinal() + 1) {
            throw new IllegalArgumentException("receipt stages must use their durable ordinal sequence");
        }
        boolean sameStage = stage == sourceStage && stageSequence == sourceStageSequence;
        boolean nextStage = stage.ordinal() == sourceStage.ordinal() + 1
                && stageSequence == sourceStageSequence + 1;
        if (!sameStage && !nextStage) {
            throw new IllegalArgumentException("receipt can acknowledge only the same or next Hearing stage");
        }
        if (sharedDeadlineAt != null) {
            sharedDeadlineAt = sharedDeadlineAt.truncatedTo(ChronoUnit.MICROS);
        }
        if (stage.hasSharedPartyDeadline() != (sharedDeadlineAt != null)) {
            throw new IllegalArgumentException("sharedDeadlineAt is required only for a party-wait stage");
        }
        if (processRevision != sourceProcessRevision + 1
                || roomRevision != sourceRoomRevision + 1) {
            throw new IllegalArgumentException("a committed receipt must advance both authority revisions once");
        }
        if (resultRef == null || !RESULT_REF.matcher(resultRef).matches()) {
            throw new IllegalArgumentException("resultRef must be a bounded immutable urn/s3/minio reference");
        }
        hash(resultHash, "resultHash");
        safePositive(committedEventSequence, "committedEventSequence");
        temporalBuildOrDeployment = boundedText(
                temporalBuildOrDeployment, 128, "temporalBuildOrDeployment");
        if (writerMode == HearingWriterMode.LEGACY) {
            if (temporalNamespace != null || temporalWorkflowId != null || temporalRunId != null) {
                throw new IllegalArgumentException("LEGACY receipt cannot carry Temporal execution identity");
            }
        } else {
            temporalNamespace = boundedText(temporalNamespace, 128, "temporalNamespace");
            temporalWorkflowId = boundedText(temporalWorkflowId, 128, "temporalWorkflowId");
            temporalRunId = boundedText(temporalRunId, 128, "temporalRunId");
        }
        if (temporalHistoryEventId != null) {
            safePositive(temporalHistoryEventId, "temporalHistoryEventId");
        }
        committedAt = Objects.requireNonNull(committedAt, "committedAt")
                .truncatedTo(ChronoUnit.MICROS);
        String expectedHash = canonicalHash(
                schemaVersion,
                receiptId,
                operationType,
                operationKey,
                requestHash,
                tenantSurrogate,
                caseId,
                flowInstanceId,
                epochId,
                roomEpoch,
                writerMode,
                fencingToken,
                sourceStage,
                sourceStageSequence,
                sourceProcessRevision,
                sourceRoomRevision,
                stage,
                stageSequence,
                sharedDeadlineAt,
                processRevision,
                roomRevision,
                resultRef,
                resultHash,
                committedEventSequence,
                temporalNamespace,
                temporalWorkflowId,
                temporalRunId,
                temporalBuildOrDeployment,
                temporalHistoryEventId,
                committedAt);
        if (!receiptHash.equals(expectedHash)) {
            throw new IllegalArgumentException("receiptHash is not canonical");
        }
    }

    public static HearingDomainReceipt committed(
            HearingAuthorityCommit command,
            HearingFormalCommitResult result,
            String temporalNamespace,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildOrDeployment) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(result, "result");
        HearingAuthorityExpectation authority = command.authority();
        String receiptId = "HDR_" + sha256(
                authority.tenantSurrogate() + ':' + command.operationKey()).substring(0, 60);
        long nextProcessRevision = authority.processRevision() + 1;
        long nextRoomRevision = authority.roomRevision() + 1;
        String receiptHash = canonicalHash(
                SCHEMA_VERSION,
                receiptId,
                command.operationType(),
                command.operationKey(),
                command.requestHash(),
                authority.tenantSurrogate(),
                authority.caseId(),
                authority.flowInstanceId(),
                authority.epochId(),
                authority.roomEpoch(),
                authority.writerMode(),
                authority.fencingToken(),
                authority.stage(),
                authority.stageSequence(),
                authority.processRevision(),
                authority.roomRevision(),
                result.stage(),
                result.stageSequence(),
                result.sharedDeadlineAt(),
                nextProcessRevision,
                nextRoomRevision,
                result.resultRef(),
                result.resultHash(),
                result.committedEventSequence(),
                temporalNamespace,
                temporalWorkflowId,
                temporalRunId,
                temporalBuildOrDeployment,
                command.temporalHistoryEventId(),
                command.committedAt());
        return new HearingDomainReceipt(
                SCHEMA_VERSION,
                receiptId,
                receiptHash,
                command.operationType(),
                command.operationKey(),
                command.requestHash(),
                authority.tenantSurrogate(),
                authority.caseId(),
                authority.flowInstanceId(),
                authority.epochId(),
                authority.roomEpoch(),
                authority.writerMode(),
                authority.fencingToken(),
                authority.stage(),
                authority.stageSequence(),
                authority.processRevision(),
                authority.roomRevision(),
                result.stage(),
                result.stageSequence(),
                result.sharedDeadlineAt(),
                nextProcessRevision,
                nextRoomRevision,
                result.resultRef(),
                result.resultHash(),
                result.committedEventSequence(),
                temporalNamespace,
                temporalWorkflowId,
                temporalRunId,
                temporalBuildOrDeployment,
                command.temporalHistoryEventId(),
                command.committedAt());
    }

    public void requireReplayOf(HearingAuthorityCommit command) {
        Objects.requireNonNull(command, "command");
        HearingAuthorityExpectation authority = command.authority();
        if (operationType != command.operationType()
                || !operationKey.equals(command.operationKey())
                || !requestHash.equals(command.requestHash())
                || !tenantSurrogate.equals(authority.tenantSurrogate())
                || !caseId.equals(authority.caseId())
                || !flowInstanceId.equals(authority.flowInstanceId())
                || !epochId.equals(authority.epochId())
                || roomEpoch != authority.roomEpoch()
                || writerMode != authority.writerMode()
                || fencingToken != authority.fencingToken()
                || sourceStage != authority.stage()
                || sourceStageSequence != authority.stageSequence()
                || sourceProcessRevision != authority.processRevision()
                || sourceRoomRevision != authority.roomRevision()
                || !Objects.equals(temporalHistoryEventId, command.temporalHistoryEventId())) {
            throw new HearingAuthorityRejectedException(
                    "HEARING_IDEMPOTENCY_CONFLICT",
                    "operation key is already committed with another request or authority tuple");
        }
    }

    private static String canonicalHash(
            String schemaVersion,
            String receiptId,
            HearingAuthorityCommit.OperationType operationType,
            String operationKey,
            String requestHash,
            String tenantSurrogate,
            String caseId,
            String flowInstanceId,
            String epochId,
            long roomEpoch,
            HearingWriterMode writerMode,
            long fencingToken,
            HearingFlowStage sourceStage,
            int sourceStageSequence,
            long sourceProcessRevision,
            long sourceRoomRevision,
            HearingFlowStage stage,
            int stageSequence,
            Instant sharedDeadlineAt,
            long processRevision,
            long roomRevision,
            String resultRef,
            String resultHash,
            long committedEventSequence,
            String temporalNamespace,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildOrDeployment,
            Long temporalHistoryEventId,
            Instant committedAt) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("case_id", caseId);
        value.put("committed_at", committedAt.toString());
        value.put("committed_event_sequence", committedEventSequence);
        value.put("epoch_id", epochId);
        value.put("fencing_token", fencingToken);
        value.put("flow_instance_id", flowInstanceId);
        value.put("operation_key", operationKey);
        value.put("operation_type", operationType.name());
        value.put("process_revision", processRevision);
        value.put("receipt_id", receiptId);
        value.put("request_hash", requestHash);
        value.put("result_hash", resultHash);
        value.put("result_ref", resultRef);
        value.put("room_epoch", roomEpoch);
        value.put("room_revision", roomRevision);
        value.put("schema_version", schemaVersion);
        if (sharedDeadlineAt != null) {
            value.put("shared_deadline_at", sharedDeadlineAt.toString());
        }
        value.put("source_process_revision", sourceProcessRevision);
        value.put("source_room_revision", sourceRoomRevision);
        value.put("source_stage", sourceStage.name());
        value.put("source_stage_sequence", sourceStageSequence);
        value.put("stage", stage.name());
        value.put("stage_sequence", stageSequence);
        value.put("temporal_build_or_deployment", temporalBuildOrDeployment);
        if (temporalHistoryEventId != null) {
            value.put("temporal_history_event_id", temporalHistoryEventId);
        }
        if (temporalNamespace != null) {
            value.put("temporal_namespace", temporalNamespace);
        }
        if (temporalRunId != null) {
            value.put("temporal_run_id", temporalRunId);
        }
        if (temporalWorkflowId != null) {
            value.put("temporal_workflow_id", temporalWorkflowId);
        }
        value.put("tenant_surrogate", tenantSurrogate);
        value.put("writer_mode", writerMode.name());
        return sha256(canonicalJson(value));
    }

    private static String canonicalJson(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(sorted(value));
        } catch (Exception failure) {
            throw new IllegalStateException("cannot canonicalize Hearing receipt", failure);
        }
    }

    private static JsonNode sorted(JsonNode value) {
        if (!value.isObject()) {
            return value;
        }
        TreeMap<String, JsonNode> fields = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> iterator = value.fields();
        iterator.forEachRemaining(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
        ObjectNode sorted = JsonNodeFactory.instance.objectNode();
        fields.forEach(sorted::set);
        return sorted;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hash(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return value;
    }

    private static String boundedText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " must be bounded non-blank text");
        }
        return value;
    }

    private static void safeNonNegative(long value, String field) {
        if (value < 0 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(field + " must be a safe non-negative integer");
        }
    }

    private static void safePositive(long value, String field) {
        if (value < 1 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(field + " must be a positive safe integer");
        }
    }
}
