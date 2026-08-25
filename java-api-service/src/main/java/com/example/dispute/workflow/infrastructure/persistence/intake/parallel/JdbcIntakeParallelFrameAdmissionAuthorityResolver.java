package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAdmissionAuthorityResolver;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.EventAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.StagingConflictException;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Read-side resolver for the exact current event binding used by V4 Frame-set admission. */
@Repository
public class JdbcIntakeParallelFrameAdmissionAuthorityResolver
        implements IntakeParallelFrameAdmissionAuthorityResolver {

    private static final String LOAD_SQL =
            """
            select attempt.command_id, attempt.command_request_hash,
                   attempt.attempt_status, attempt.model_profile_id,
                   run.protocol, run.run_status, run.finalization_status,
                   run.tenant_surrogate as run_tenant_surrogate,
                   run.case_id as run_case_id, run.room_id as run_room_id,
                   run.room_epoch as run_room_epoch, run.fencing_token as run_fencing_token,
                   binding.binding_id, binding.thread_registration_id,
                   binding.event_sequence, binding.binding_generation,
                   binding.tenant_surrogate, binding.case_id,
                   binding.room_epoch, binding.fencing_token,
                   binding.thread_id, binding.actor_scope_hash,
                   binding.agent_session_id, binding.binding_type,
                   binding.schema_version, binding.artifact_id,
                   binding.object_uri, binding.content_sha256, binding.size_bytes,
                   authority.current_binding_id,
                   authority.current_generation,
                   authority.authority_version
              from agent_run_attempt attempt
              join agent_run run on run.id = attempt.agent_run_id
              join case_intake_snapshot_binding binding
                on binding.binding_type = 'EVENT'
               and binding.artifact_id = :artifactId
               and binding.schema_version = :schemaVersion
               and binding.object_uri = :objectUri
               and binding.content_sha256 = :contentSha256
               and binding.size_bytes = :sizeBytes
              join case_intake_event_slot_authority authority
                on authority.thread_registration_id = binding.thread_registration_id
               and authority.logical_sequence = binding.event_sequence
             where attempt.agent_run_id = :runId
               and attempt.id = :attemptId
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcIntakeParallelFrameAdmissionAuthorityResolver(
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public AdmissionAuthority resolve(ExecuteAgentRunRequest request) {
        Objects.requireNonNull(request, "request");
        RoomGraphCommand command = request.command();
        if (!ExecuteAgentRunRequest.isParallelIntakeCommand(command)) {
            throw conflict(
                    "INTAKE_PARALLEL_ADMISSION_PROFILE_INVALID",
                    "event authority resolution requires the explicit parallel Intake profile");
        }
        RoomGraphCommand.SnapshotRef eventRef =
                Objects.requireNonNull(command.eventRef(), "parallel Intake eventRef");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("runId", request.agentRunId())
                .addValue("attemptId", request.attemptId())
                .addValue("artifactId", eventRef.artifactId())
                .addValue("schemaVersion", eventRef.schemaVersion())
                .addValue("objectUri", eventRef.uri())
                .addValue("contentSha256", eventRef.sha256())
                .addValue("sizeBytes", eventRef.sizeBytes());
        List<Map<String, Object>> rows = jdbc.queryForList(LOAD_SQL, parameters);
        if (rows.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_ADMISSION_AUTHORITY_MISSING",
                    "current event authority is absent or ambiguous");
        }
        Map<String, Object> row = rows.getFirst();
        String actorScopeSha256 = ContractJson.sha256Hex(mapper.valueToTree(command.actorScope()));
        boolean exact = "RUNNING".equals(text(row, "attempt_status"))
                && "RUNNING".equals(text(row, "run_status"))
                && "UNCOMMITTED".equals(text(row, "finalization_status"))
                && "agent-stream.v4".equals(text(row, "protocol"))
                && command.commandId().equals(text(row, "command_id"))
                && command.requestHash().equals(text(row, "command_request_hash"))
                && command.invocationContext().modelProfileId().equals(text(row, "model_profile_id"))
                && command.tenantSurrogate().equals(text(row, "run_tenant_surrogate"))
                && command.tenantSurrogate().equals(text(row, "tenant_surrogate"))
                && command.caseId().equals(text(row, "run_case_id"))
                && command.caseId().equals(text(row, "case_id"))
                && command.roomId().equals(text(row, "run_room_id"))
                && command.roomEpoch() == number(row, "run_room_epoch")
                && command.roomEpoch() == number(row, "room_epoch")
                && number(row, "run_fencing_token") == number(row, "fencing_token")
                && command.threadId().equals(text(row, "thread_id"))
                && actorScopeSha256.equals(text(row, "actor_scope_hash"))
                && text(row, "binding_id").equals(text(row, "current_binding_id"))
                && number(row, "binding_generation") == number(row, "current_generation");
        if (!exact) {
            throw conflict(
                    "INTAKE_PARALLEL_ADMISSION_AUTHORITY_DRIFT",
                    "event authority differs from the immutable execution request");
        }
        return new AdmissionAuthority(
                number(row, "run_fencing_token"),
                actorScopeSha256,
                text(row, "agent_session_id"),
                new EventAuthority(
                        text(row, "binding_id"),
                        text(row, "thread_registration_id"),
                        number(row, "event_sequence"),
                        number(row, "binding_generation"),
                        number(row, "authority_version"),
                        command.requestHash()));
    }

    private static String text(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw conflict(
                    "INTAKE_PARALLEL_ADMISSION_AUTHORITY_CORRUPT",
                    field + " is absent from event authority");
        }
        return text;
    }

    private static long number(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Number number)) {
            throw conflict(
                    "INTAKE_PARALLEL_ADMISSION_AUTHORITY_CORRUPT",
                    field + " is absent from event authority");
        }
        return number.longValue();
    }

    private static StagingConflictException conflict(String code, String message) {
        return new StagingConflictException(code, message);
    }
}
