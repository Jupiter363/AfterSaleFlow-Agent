package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.FailureTerminationReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFailureFinalizationPort;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFailureFinalizationPort.FailureCommitCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFailureFinalizationPort.FailureCommitConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFailureFinalizationPort.FailureCommitReceipt;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL half of the Graph-first, Java-local parallel failure saga. */
@Repository
public class JdbcIntakeParallelFailureFinalizationStore
        implements IntakeParallelFailureFinalizationPort {

    private static final Set<String> RECEIPT_FIELDS = Set.of(
            "schema_version",
            "receipt_id",
            "request_hash",
            "frame_set_id",
            "run_id",
            "attempt_id",
            "command_id",
            "admission_receipt_sha256",
            "requested_failure_code",
            "graph_command_status",
            "graph_attempt_status",
            "graph_error_code",
            "graph_error_classification",
            "provider_permit_statuses",
            "receipt_sha256");
    private static final Set<String> TERMINAL_PERMIT_STATUSES = Set.of(
            "RELEASED", "CANCELLED", "EXPIRED", "TIMED_OUT", "ORPHANED");
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,127}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private static final String LOCK_FRAME_SET_SQL =
            """
            select frame_set.frame_set_id, frame_set.agent_run_id,
                   frame_set.agent_run_attempt_id, frame_set.command_id,
                   frame_set.command_request_sha256, frame_set.assembly_state,
                   frame_set.failure_code, frame_set.version,
                   admission.current_receipt_sha256,
                   attempt.attempt_status, attempt.command_id as attempt_command_id,
                   attempt.command_request_hash as attempt_command_request_hash,
                   attempt.error_code as attempt_error_code,
                   attempt.result_json as attempt_result_json,
                   run.protocol, run.run_status, run.finalization_status,
                   run.error_code as run_error_code, run.error_retryable
              from intake_parallel_frame_set frame_set
              join agent_run_attempt attempt
                on attempt.id = frame_set.agent_run_attempt_id
               and attempt.agent_run_id = frame_set.agent_run_id
              join agent_run run on run.id = frame_set.agent_run_id
              join intake_parallel_admission_receipt_authority admission
                on admission.frame_set_id = frame_set.frame_set_id
              where frame_set.frame_set_id = :frameSetId
              for update of frame_set, admission
            """;

    private static final String INSERT_RECEIPT_SQL =
            """
            insert into intake_parallel_failure_termination_receipt (
                receipt_id, frame_set_id, agent_run_id, agent_run_attempt_id,
                command_id, command_request_sha256, admission_receipt_sha256,
                requested_failure_code, graph_command_status, graph_attempt_status,
                graph_error_code, graph_error_classification,
                provider_permit_statuses, receipt_sha256,
                canonical_receipt_bytes, receipt_size_bytes
            ) values (
                :receiptId, :frameSetId, :runId, :attemptId,
                :commandId, :requestHash, :admissionReceiptSha256,
                :failureCode, :graphCommandStatus, :graphAttemptStatus,
                :graphErrorCode, :graphErrorClassification,
                cast(:providerPermitStatuses as jsonb), :receiptSha256,
                :canonicalReceiptBytes, :receiptSizeBytes
            )
            on conflict do nothing
            """;

    private static final String LOAD_RECEIPT_SQL =
            """
            select receipt_id, frame_set_id, agent_run_id, agent_run_attempt_id,
                   command_id, command_request_sha256, admission_receipt_sha256,
                   requested_failure_code, graph_command_status, graph_attempt_status,
                   graph_error_code, graph_error_classification,
                   provider_permit_statuses::text as provider_permit_statuses,
                   receipt_sha256, canonical_receipt_bytes, receipt_size_bytes
              from intake_parallel_failure_termination_receipt
             where receipt_id = :receiptId
                or frame_set_id = :frameSetId
             order by receipt_id
            """;

    private static final String FAIL_FRAME_SET_SQL =
            """
            update intake_parallel_frame_set
               set assembly_state = 'FAILED_UNCOMMITTED',
                   failure_code = :failureCode,
                   failed_at = :failedAt,
                   updated_at = :failedAt,
                   version = version + 1
             where frame_set_id = :frameSetId
               and assembly_state in ('COLLECTING', 'READY')
               and version = :expectedVersion
            returning version
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcIntakeParallelFailureFinalizationStore(
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FailureCommitReceipt commit(FailureCommitCommand command) {
        Objects.requireNonNull(command, "command");
        DecodedReceipt receipt = decode(command.graphReceipt());
        requireCommandBinding(command, receipt);
        MapSqlParameterSource parameters = parameters(command, receipt);
        List<Map<String, Object>> frameSets = jdbc.queryForList(LOCK_FRAME_SET_SQL, parameters);
        if (frameSets.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_FRAME_SET_MISSING",
                    "parallel failure does not bind exactly one Frame set");
        }
        Map<String, Object> frameSet = frameSets.getFirst();
        requireDurableJavaFailure(command, receipt, frameSet);
        String state = text(frameSet, "assembly_state");
        long version = number(frameSet, "version");
        if ("FAILED_UNCOMMITTED".equals(state)) {
            requireStoredReceipt(receipt, parameters);
            if (!receipt.requestedFailureCode().equals(nullableText(frameSet, "failure_code"))) {
                throw conflict(
                        "INTAKE_PARALLEL_FAILURE_REPLAY_CONFLICT",
                        "parallel failure replay changed its terminal code");
            }
            return new FailureCommitReceipt(
                    receipt.frameSetId(),
                    receipt.receiptId(),
                    receipt.receiptSha256(),
                    receipt.requestedFailureCode(),
                    false,
                    version);
        }
        if (!Set.of("COLLECTING", "READY").contains(state)
                || nullableText(frameSet, "failure_code") != null) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_STATE_CONFLICT",
                    "only an uncommitted parallel assembly may enter failure");
        }

        int inserted = jdbc.update(INSERT_RECEIPT_SQL, parameters);
        if (inserted == 0) {
            requireStoredReceipt(receipt, parameters);
        } else if (inserted != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_RECEIPT_PARTIAL",
                    "parallel failure receipt insert was not atomic");
        }
        parameters.addValue("expectedVersion", version);
        List<Map<String, Object>> updated = jdbc.queryForList(FAIL_FRAME_SET_SQL, parameters);
        if (updated.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_CAS_REJECTED",
                    "parallel failure did not atomically terminalize its Frame set");
        }
        return new FailureCommitReceipt(
                receipt.frameSetId(),
                receipt.receiptId(),
                receipt.receiptSha256(),
                receipt.requestedFailureCode(),
                inserted == 1,
                number(updated.getFirst(), "version"));
    }

    private MapSqlParameterSource parameters(
            FailureCommitCommand command, DecodedReceipt receipt) {
        byte[] bytes = command.graphReceipt().canonicalReceiptBytes();
        return new MapSqlParameterSource()
                .addValue("receiptId", receipt.receiptId())
                .addValue("frameSetId", receipt.frameSetId())
                .addValue("runId", receipt.runId())
                .addValue("attemptId", receipt.attemptId())
                .addValue("commandId", receipt.commandId())
                .addValue("requestHash", receipt.requestHash())
                .addValue("admissionReceiptSha256", receipt.admissionReceiptSha256())
                .addValue("failureCode", receipt.requestedFailureCode())
                .addValue("graphCommandStatus", receipt.graphCommandStatus())
                .addValue("graphAttemptStatus", receipt.graphAttemptStatus())
                .addValue("graphErrorCode", receipt.graphErrorCode())
                .addValue("graphErrorClassification", receipt.graphErrorClassification())
                .addValue("providerPermitStatuses", receipt.providerPermitStatusesJson())
                .addValue("receiptSha256", receipt.receiptSha256())
                .addValue("canonicalReceiptBytes", bytes)
                .addValue("receiptSizeBytes", bytes.length)
                .addValue("failedAt", Timestamp.from(command.durableResult().completedAt()));
    }

    private void requireCommandBinding(FailureCommitCommand command, DecodedReceipt receipt) {
        var request = command.request();
        ExecuteAgentRunResult result = command.durableResult();
        if (!request.command().requestHash().equals(receipt.requestHash())
                || !request.agentRunId().equals(receipt.runId())
                || !request.attemptId().equals(receipt.attemptId())
                || !request.command().commandId().equals(receipt.commandId())
                || !result.errorCode().equals(receipt.requestedFailureCode())
                || !command.graphReceipt().receiptId().equals(receipt.receiptId())
                || !command.graphReceipt().receiptHash().equals(receipt.receiptSha256())) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_RECEIPT_AUTHORITY_DRIFT",
                    "Graph failure receipt differs from its Java failure authority");
        }
    }

    private void requireDurableJavaFailure(
            FailureCommitCommand command,
            DecodedReceipt receipt,
            Map<String, Object> row) {
        String expectedStatus = command.attemptStatus().name();
        boolean exact = receipt.frameSetId().equals(text(row, "frame_set_id"))
                && receipt.runId().equals(text(row, "agent_run_id"))
                && receipt.attemptId().equals(text(row, "agent_run_attempt_id"))
                && receipt.commandId().equals(text(row, "command_id"))
                && receipt.requestHash().equals(text(row, "command_request_sha256"))
                && receipt.commandId().equals(text(row, "attempt_command_id"))
                && receipt.requestHash().equals(text(row, "attempt_command_request_hash"))
                && receipt.admissionReceiptSha256()
                        .equals(text(row, "current_receipt_sha256"))
                && expectedStatus.equals(text(row, "attempt_status"))
                && command.durableResult().errorCode().equals(text(row, "attempt_error_code"))
                && "agent-stream.v4".equals(text(row, "protocol"))
                && expectedStatus.equals(text(row, "run_status"))
                && "UNCOMMITTED".equals(text(row, "finalization_status"))
                && command.durableResult().errorCode().equals(text(row, "run_error_code"))
                && Boolean.FALSE.equals(row.get("error_retryable"));
        if (!exact) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_JAVA_AUTHORITY_DRIFT",
                    "parallel Frame failure differs from the durable AgentRun failure");
        }
        try {
            ExecuteAgentRunResult stored = mapper.readValue(
                    text(row, "attempt_result_json"), ExecuteAgentRunResult.class);
            if (!command.durableResult().equals(stored)) {
                throw conflict(
                        "INTAKE_PARALLEL_FAILURE_RESULT_DRIFT",
                        "parallel failure result differs from the attempt projection");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_RESULT_INVALID",
                    "durable parallel failure result is invalid");
        }
    }

    private void requireStoredReceipt(
            DecodedReceipt receipt, MapSqlParameterSource parameters) {
        List<Map<String, Object>> rows = jdbc.queryForList(LOAD_RECEIPT_SQL, parameters);
        if (rows.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_RECEIPT_CONFLICT",
                    "parallel failure receipt identity is missing or ambiguous");
        }
        Map<String, Object> row = rows.getFirst();
        byte[] expectedBytes = parameters.getValue("canonicalReceiptBytes") instanceof byte[] bytes
                ? bytes
                : null;
        boolean exact = receipt.receiptId().equals(text(row, "receipt_id"))
                && receipt.frameSetId().equals(text(row, "frame_set_id"))
                && receipt.runId().equals(text(row, "agent_run_id"))
                && receipt.attemptId().equals(text(row, "agent_run_attempt_id"))
                && receipt.commandId().equals(text(row, "command_id"))
                && receipt.requestHash().equals(text(row, "command_request_sha256"))
                && receipt.admissionReceiptSha256()
                        .equals(text(row, "admission_receipt_sha256"))
                && receipt.requestedFailureCode()
                        .equals(text(row, "requested_failure_code"))
                && receipt.graphCommandStatus().equals(text(row, "graph_command_status"))
                && receipt.graphAttemptStatus().equals(text(row, "graph_attempt_status"))
                && receipt.graphErrorCode().equals(text(row, "graph_error_code"))
                && receipt.graphErrorClassification()
                        .equals(text(row, "graph_error_classification"))
                && sameJson(
                        receipt.providerPermitStatusesJson(),
                        text(row, "provider_permit_statuses"))
                && receipt.receiptSha256().equals(text(row, "receipt_sha256"))
                && expectedBytes != null
                && Arrays.equals(expectedBytes, (byte[]) row.get("canonical_receipt_bytes"))
                && expectedBytes.length == number(row, "receipt_size_bytes");
        if (!exact) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_RECEIPT_REPLAY_CONFLICT",
                    "parallel failure receipt replay changed immutable authority");
        }
    }

    private DecodedReceipt decode(FailureTerminationReceipt envelope) {
        byte[] bytes = envelope.canonicalReceiptBytes();
        try {
            JsonNode decoded = mapper.readTree(bytes);
            if (!(decoded instanceof ObjectNode root)
                    || !Arrays.equals(bytes, ContractJson.canonicalize(root))) {
                throw new IllegalArgumentException("receipt bytes are not canonical");
            }
            Set<String> fields = new HashSet<>();
            root.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(RECEIPT_FIELDS)) {
                throw new IllegalArgumentException("receipt fields drifted");
            }
            String schemaVersion = requiredText(root, "schema_version");
            String receiptId = requiredText(root, "receipt_id");
            String requestHash = requiredText(root, "request_hash");
            String frameSetId = requiredText(root, "frame_set_id");
            String runId = requiredText(root, "run_id");
            String attemptId = requiredText(root, "attempt_id");
            String commandId = requiredText(root, "command_id");
            String admissionHash = requiredText(root, "admission_receipt_sha256");
            String failureCode = requiredText(root, "requested_failure_code");
            String commandStatus = requiredText(root, "graph_command_status");
            String attemptStatus = requiredText(root, "graph_attempt_status");
            String errorCode = requiredText(root, "graph_error_code");
            String errorClassification =
                    requiredText(root, "graph_error_classification");
            String receiptHash = requiredText(root, "receipt_sha256");
            JsonNode permitNode = root.required("provider_permit_statuses");
            if (!permitNode.isArray()) {
                throw new IllegalArgumentException("permit statuses are invalid");
            }
            List<String> permits = new ArrayList<>();
            permitNode.forEach(value -> {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException("permit status is invalid");
                }
                permits.add(value.asText());
            });
            if (!"intake.parallel-failure-termination.v1".equals(schemaVersion)
                    || !SHA256.matcher(requestHash).matches()
                    || !SHA256.matcher(admissionHash).matches()
                    || !SHA256.matcher(receiptHash).matches()
                    || !SAFE_CODE.matcher(failureCode).matches()
                    || !SAFE_CODE.matcher(errorCode).matches()
                    || !SAFE_CODE.matcher(errorClassification).matches()
                    || !Set.of("ABORTED", "CANCELLED").contains(commandStatus)
                    || !Set.of("FAILED", "LEASE_LOST", "CANCELLED", "ABSENT")
                            .contains(attemptStatus)
                    || !receiptId.equals(
                            "parallel-failure-terminal." + admissionHash.substring(0, 24))
                    || !permits.equals(permits.stream().sorted().toList())
                    || permits.stream()
                            .anyMatch(status -> !TERMINAL_PERMIT_STATUSES.contains(status))) {
                throw new IllegalArgumentException("receipt authority is invalid");
            }
            ObjectNode unsigned = root.deepCopy();
            unsigned.remove("receipt_sha256");
            if (!receiptHash.equals(ContractJson.sha256Hex(unsigned))) {
                throw new IllegalArgumentException("receipt self-hash drifted");
            }
            String permitJson = new String(
                    ContractJson.canonicalize(permitNode), StandardCharsets.UTF_8);
            return new DecodedReceipt(
                    receiptId,
                    requestHash,
                    frameSetId,
                    runId,
                    attemptId,
                    commandId,
                    admissionHash,
                    failureCode,
                    commandStatus,
                    attemptStatus,
                    errorCode,
                    errorClassification,
                    permitJson,
                    receiptHash);
        } catch (RuntimeException | java.io.IOException failure) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_RECEIPT_INVALID",
                    "parallel Graph failure receipt is invalid");
        }
    }

    private boolean sameJson(String expected, String stored) {
        try {
            return mapper.readTree(expected).equals(mapper.readTree(stored));
        } catch (java.io.IOException failure) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_RECEIPT_REPLAY_CONFLICT",
                    "parallel failure receipt stored invalid JSON authority");
        }
    }

    private static String requiredText(ObjectNode root, String field) {
        JsonNode value = root.required(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("receipt field is invalid: " + field);
        }
        return value.asText();
    }

    private static String text(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null || value.toString().isBlank()) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_STORED_VALUE_INVALID",
                    "parallel failure stored authority is incomplete");
        }
        return value.toString();
    }

    private static String nullableText(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }

    private static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (!(value instanceof Number number)) {
            throw conflict(
                    "INTAKE_PARALLEL_FAILURE_STORED_VALUE_INVALID",
                    "parallel failure stored number is invalid");
        }
        return number.longValue();
    }

    private static FailureCommitConflictException conflict(String code, String message) {
        return new FailureCommitConflictException(code, message);
    }

    private record DecodedReceipt(
            String receiptId,
            String requestHash,
            String frameSetId,
            String runId,
            String attemptId,
            String commandId,
            String admissionReceiptSha256,
            String requestedFailureCode,
            String graphCommandStatus,
            String graphAttemptStatus,
            String graphErrorCode,
            String graphErrorClassification,
            String providerPermitStatusesJson,
            String receiptSha256) {}
}
