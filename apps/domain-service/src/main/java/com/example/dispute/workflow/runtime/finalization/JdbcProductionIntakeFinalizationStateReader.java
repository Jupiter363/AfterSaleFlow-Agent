package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher.SourceType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Repeatable-read PostgreSQL adapter for all Java-owned Intake finalization facts. */
public final class JdbcProductionIntakeFinalizationStateReader
        implements ProductionIntakeFinalizationStateReader {

    private static final String BASE_SQL = """
            select run.id as run_id,
                   run.tenant_surrogate as run_tenant, run.case_id as run_case_id,
                   run.room_id as run_room_id, run.room_epoch_id,
                   run.room_type as run_room_type,
                   run.logical_idempotency_key, run.protocol,
                   run.executor_kind as run_executor_kind,
                   run.run_status, run.finalization_status,
                   run.room_epoch as run_room_epoch,
                   run.process_revision as run_process_revision,
                   run.fencing_token as run_fencing_token,
                   run.request_hash as run_request_hash,
                   run.logical_input_hash as run_logical_input_hash,
                   run.result_ready_attempt_id, run.committed_attempt_id,
                   run.final_result_hash,
                   attempt.id as attempt_id, attempt.agent_run_id,
                   attempt.attempt_no, attempt.attempt_status,
                   attempt.executor_kind as attempt_executor_kind,
                   attempt.provider, attempt.model_profile_id, attempt.model_version,
                   attempt.graph_key as attempt_graph_key,
                   attempt.graph_version as attempt_graph_version,
                   attempt.checkpoint_schema_version as attempt_checkpoint_schema,
                   attempt.checkpoint_id, attempt.prompt_version,
                   attempt.output_schema_version, attempt.policy_version,
                   attempt.guardrail_version,
                   attempt.request_hash as attempt_request_hash,
                   attempt.command_id, attempt.command_request_hash,
                   attempt.logical_input_hash as attempt_logical_input_hash,
                   attempt.result_hash as attempt_result_hash,
                   attempt.final_frame_observed, attempt.last_sequence_no,
                   attempt.latency_ms, attempt.completed_at,
                   cast(attempt.command_json as text) as persisted_command_json,
                   cast(attempt.result_json as text) as persisted_result_json,
                   epoch.id as epoch_id, epoch.tenant_surrogate as epoch_tenant,
                   epoch.case_id as epoch_case_id, epoch.room_id as epoch_room_id,
                   epoch.room_type as epoch_room_type,
                   epoch.writer_mode as epoch_writer_mode,
                   epoch.lifecycle_status, epoch.provisioning_status,
                   epoch.room_epoch as epoch_room_epoch,
                   epoch.process_revision as epoch_process_revision,
                   epoch.room_revision as epoch_room_revision,
                   epoch.fencing_token as epoch_fencing_token,
                   epoch.graph_key as epoch_graph_key,
                   epoch.graph_version as epoch_graph_version,
                   epoch.checkpoint_schema_version as epoch_checkpoint_schema,
                   epoch.stream_protocol as epoch_stream_protocol,
                   projection.tenant_surrogate as projection_tenant,
                   projection.case_id as projection_case_id,
                   projection.current_room as projection_current_room,
                   projection.room_phase as projection_room_phase,
                   projection.writer_mode as projection_writer_mode,
                   projection.writer_activation_status,
                   projection.process_revision as projection_process_revision,
                   projection.room_epoch as projection_room_epoch,
                   projection.fencing_token as projection_fencing_token,
                   projection.last_command_sequence,
                   binding.registration_id, binding.schema_version,
                   binding.tenant_surrogate as binding_tenant,
                   binding.case_id as binding_case_id,
                   binding.room_type as binding_room_type,
                   binding.room_epoch as binding_room_epoch,
                   binding.fencing_token as binding_fencing_token,
                   binding.thread_id, binding.actor_id, binding.actor_role,
                   binding.audience, cast(binding.actor_capabilities_json as text) as capabilities,
                   binding.actor_scope_hash, binding.agent_session_id,
                   binding.graph_key as binding_graph_key,
                   binding.graph_version as binding_graph_version,
                   binding.checkpoint_schema_version as binding_checkpoint_schema,
                   binding.state_schema_version, binding.prompt_version as binding_prompt_version,
                   binding.model_profile_id as binding_model_profile_id,
                   binding.output_schema_version as binding_output_schema_version,
                   binding.policy_version as binding_policy_version,
                   binding.guardrail_version as binding_guardrail_version,
                   binding.tool_policy_version, binding.writer_mode as binding_writer_mode,
                   binding.registration_status, binding.issued_at, binding.registration_hash,
                   participant.participant_status,
                   access_session.status as access_session_status,
                   agent_session.status as agent_session_status
              from agent_run run
              join agent_run_attempt attempt
                on attempt.agent_run_id = run.id
               and attempt.id = :attemptId
              join case_room_epoch epoch on epoch.id = run.room_epoch_id
              join case_process_projection projection on projection.case_id = run.case_id
              join case_intake_graph_thread_binding binding
                on binding.case_id = run.case_id
               and binding.thread_id = :threadId
              join agent_conversation_session agent_session
                on agent_session.id = binding.agent_session_id
               and agent_session.tenant_id = binding.tenant_surrogate
               and agent_session.case_id = binding.case_id
               and agent_session.room_type = binding.room_type
               and agent_session.actor_id = binding.actor_id
               and agent_session.actor_role = binding.actor_role
              join case_access_session access_session
                on access_session.id = agent_session.access_session_id
               and access_session.tenant_id = binding.tenant_surrogate
               and access_session.case_id = binding.case_id
               and access_session.actor_id = binding.actor_id
               and access_session.actor_role = binding.actor_role
              join case_participant participant
                on participant.case_id = binding.case_id
               and participant.actor_id = binding.actor_id
               and participant.participant_role = binding.actor_role
             where run.id = :agentRunId
               and run.case_id = :caseId
            """;

    private static final String SNAPSHOT_SQL = """
            select binding_id, thread_registration_id, tenant_surrogate, case_id,
                   room_epoch, fencing_token, thread_id, actor_scope_hash,
                   agent_session_id, schema_version, artifact_id, object_uri,
                   object_version, content_sha256, size_bytes, domain_revision,
                   room_revision, projection_revision, initial_last_sequence, created_at
              from case_intake_snapshot_binding
             where thread_registration_id = :registrationId
               and binding_type = :snapshotBindingType
               and initialization_marker = :snapshotInitializationMarker
               and artifact_id = :artifactId
            """;

    private static final String EVENT_SQL = """
            select binding_id, thread_registration_id, event_id, message_id,
                   tenant_surrogate, case_id, room_epoch, fencing_token, thread_id,
                   actor_scope_hash, agent_session_id, schema_version, artifact_id,
                   object_uri, object_version, content_sha256, size_bytes,
                   event_sequence, domain_revision, audience, occurred_at, created_at,
                   event_source_type
              from case_intake_snapshot_binding
             where thread_registration_id = :registrationId
               and binding_type = 'EVENT'
               and artifact_id = :artifactId
            """;

    private static final String OUTPUT_SQL = """
            select id, schema_version, object_uri, content_sha256
              from immutable_payload_snapshot
             where tenant_surrogate = :tenantSurrogate
               and case_id = :caseId
               and room_type = 'INTAKE'
               and snapshot_type = 'AGENT_OUTPUT'
               and source_type = 'AGENT_RUN'
               and source_id = :agentRunId
               and content_sha256 = :resultHash
            """;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public JdbcProductionIntakeFinalizationStateReader(
            NamedParameterJdbcOperations jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setReadOnly(true);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public JdbcProductionIntakeFinalizationStateReader(
            javax.sql.DataSource dataSource,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this(new NamedParameterJdbcTemplate(dataSource), transactionManager, objectMapper);
    }

    @Override
    public Optional<ProductionIntakeFinalizationState> load(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Optional<ProductionIntakeFinalizationState> resolved = transactions.execute(
                ignored -> loadInTransaction(request, result));
        return resolved == null ? Optional.empty() : resolved;
    }

    private Optional<ProductionIntakeFinalizationState> loadInTransaction(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Map<String, Object> parameters = Map.of(
                "agentRunId", request.agentRunId(),
                "attemptId", request.attemptId(),
                "caseId", request.command().caseId(),
                "threadId", request.command().threadId());
        Optional<BaseFacts> base = unique(
                jdbc.query(BASE_SQL, parameters, (rs, row) -> base(rs)), "AgentRun authority");
        if (base.isEmpty()) {
            return Optional.empty();
        }
        BaseFacts facts = base.orElseThrow();
        var snapshotRef = request.command().domainSnapshotRef();
        Optional<IntakeSnapshotReference> snapshot = unique(
                jdbc.query(
                        SNAPSHOT_SQL,
                        Map.of(
                                "registrationId", facts.binding().registration().registrationId(),
                                "snapshotBindingType", snapshotBindingType(request),
                                "snapshotInitializationMarker",
                                        !ExecuteAgentRunRequest.isParallelIntakeCommand(
                                                request.command()),
                                "artifactId", snapshotRef.artifactId()),
                        (rs, row) -> snapshot(rs)),
                "command snapshot");
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }

        IntakeEventReference event = null;
        if (request.command().eventRef() != null) {
            Optional<IntakeEventReference> loadedEvent = unique(
                    jdbc.query(
                            EVENT_SQL,
                            Map.of(
                                    "registrationId",
                                            facts.binding().registration().registrationId(),
                                    "artifactId", request.command().eventRef().artifactId()),
                            (rs, row) -> event(rs)),
                    "Intake event");
            if (loadedEvent.isEmpty()) {
                return Optional.empty();
            }
            event = loadedEvent.orElseThrow();
        }

        Optional<ArtifactPointer> output = unique(
                jdbc.query(
                        OUTPUT_SQL,
                        Map.of(
                                "tenantSurrogate", facts.run().tenantSurrogate(),
                                "caseId", facts.run().caseId(),
                                "agentRunId", facts.run().agentRunId(),
                                "resultHash", result.resultHash()),
                        (rs, row) -> new ArtifactPointer(
                                rs.getString("id"),
                                rs.getString("schema_version"),
                                rs.getString("object_uri"),
                                rs.getString("content_sha256"))),
                "graph output snapshot");
        if (output.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProductionIntakeFinalizationState(
                facts.run(),
                facts.attempt(),
                facts.epoch(),
                facts.projection(),
                facts.registrationStatus(),
                facts.participantStatus(),
                facts.accessSessionStatus(),
                facts.agentSessionStatus(),
                facts.binding(),
                snapshot.orElseThrow(),
                event,
                output.orElseThrow()));
    }

    private static String snapshotBindingType(ExecuteAgentRunRequest request) {
        return ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                ? "TURN"
                : "INITIAL";
    }

    private BaseFacts base(ResultSet rs) throws SQLException {
        var run = new ProductionIntakeFinalizationState.LogicalRun(
                rs.getString("run_id"),
                rs.getString("run_tenant"),
                rs.getString("run_case_id"),
                rs.getString("run_room_id"),
                rs.getString("room_epoch_id"),
                rs.getString("run_room_type"),
                rs.getString("logical_idempotency_key"),
                rs.getString("protocol"),
                rs.getString("run_executor_kind"),
                rs.getString("run_status"),
                rs.getString("finalization_status"),
                rs.getLong("run_room_epoch"),
                rs.getLong("run_process_revision"),
                rs.getLong("run_fencing_token"),
                rs.getString("run_request_hash"),
                rs.getString("run_logical_input_hash"),
                rs.getString("result_ready_attempt_id"),
                rs.getString("committed_attempt_id"),
                rs.getString("final_result_hash"));
        Long latency = (Long) rs.getObject("latency_ms");
        var attempt = new ProductionIntakeFinalizationState.Attempt(
                rs.getString("attempt_id"),
                rs.getString("agent_run_id"),
                rs.getLong("attempt_no"),
                rs.getString("attempt_status"),
                rs.getString("attempt_executor_kind"),
                rs.getString("provider"),
                rs.getString("model_profile_id"),
                rs.getString("model_version"),
                rs.getString("attempt_graph_key"),
                rs.getString("attempt_graph_version"),
                rs.getString("attempt_checkpoint_schema"),
                rs.getString("checkpoint_id"),
                rs.getString("prompt_version"),
                rs.getString("output_schema_version"),
                rs.getString("policy_version"),
                rs.getString("guardrail_version"),
                rs.getString("attempt_request_hash"),
                rs.getString("command_id"),
                rs.getString("command_request_hash"),
                rs.getString("attempt_logical_input_hash"),
                rs.getString("attempt_result_hash"),
                rs.getBoolean("final_frame_observed"),
                rs.getLong("last_sequence_no"),
                latency == null ? -1 : latency,
                instant(rs, "completed_at"),
                decode(rs.getString("persisted_command_json"), RoomGraphCommand.class),
                decode(rs.getString("persisted_result_json"), ExecuteAgentRunResult.class));
        var epoch = new ProductionIntakeFinalizationState.Epoch(
                rs.getString("epoch_id"),
                rs.getString("epoch_tenant"),
                rs.getString("epoch_case_id"),
                rs.getString("epoch_room_id"),
                rs.getString("epoch_room_type"),
                rs.getString("epoch_writer_mode"),
                rs.getString("lifecycle_status"),
                rs.getString("provisioning_status"),
                rs.getLong("epoch_room_epoch"),
                rs.getLong("epoch_process_revision"),
                rs.getLong("epoch_room_revision"),
                rs.getLong("epoch_fencing_token"),
                rs.getString("epoch_graph_key"),
                rs.getString("epoch_graph_version"),
                rs.getString("epoch_checkpoint_schema"),
                rs.getString("epoch_stream_protocol"));
        var projection = new ProductionIntakeFinalizationState.Projection(
                rs.getString("projection_tenant"),
                rs.getString("projection_case_id"),
                rs.getString("projection_current_room"),
                rs.getString("projection_room_phase"),
                rs.getString("projection_writer_mode"),
                rs.getString("writer_activation_status"),
                rs.getLong("projection_process_revision"),
                rs.getLong("projection_room_epoch"),
                rs.getLong("projection_fencing_token"),
                rs.getLong("last_command_sequence"));
        return new BaseFacts(
                run,
                attempt,
                epoch,
                projection,
                rs.getString("registration_status"),
                rs.getString("participant_status"),
                rs.getString("access_session_status"),
                rs.getString("agent_session_status"),
                binding(rs));
    }

    private IntakeGraphThreadBinding binding(ResultSet rs) throws SQLException {
        var actor = new IntakePrivateThreadRegistration.ActorScope(
                rs.getString("actor_id"),
                ActorRole.valueOf(rs.getString("actor_role")),
                Audience.valueOf(rs.getString("audience")),
                decodeList(rs.getString("capabilities")));
        var registration = new IntakePrivateThreadRegistration(
                rs.getString("schema_version"),
                rs.getString("registration_id"),
                rs.getString("binding_tenant"),
                rs.getString("binding_case_id"),
                rs.getString("binding_room_type"),
                rs.getLong("binding_room_epoch"),
                rs.getString("thread_id"),
                actor,
                rs.getString("actor_scope_hash"),
                rs.getString("agent_session_id"),
                rs.getString("binding_graph_key"),
                rs.getString("binding_graph_version"),
                rs.getString("binding_checkpoint_schema"),
                rs.getString("state_schema_version"),
                rs.getString("binding_prompt_version"),
                rs.getString("binding_model_profile_id"),
                rs.getString("binding_output_schema_version"),
                rs.getString("binding_policy_version"),
                rs.getString("binding_guardrail_version"),
                rs.getString("tool_policy_version"),
                WriterMode.valueOf(rs.getString("binding_writer_mode")),
                instant(rs, "issued_at"),
                rs.getString("registration_hash"));
        return new IntakeGraphThreadBinding(registration, rs.getLong("binding_fencing_token"));
    }

    private IntakeSnapshotReference snapshot(ResultSet rs) throws SQLException {
        return new IntakeSnapshotReference(
                rs.getString("binding_id"),
                rs.getString("thread_registration_id"),
                rs.getString("tenant_surrogate"),
                rs.getString("case_id"),
                rs.getLong("room_epoch"),
                rs.getLong("fencing_token"),
                rs.getString("thread_id"),
                rs.getString("actor_scope_hash"),
                rs.getString("agent_session_id"),
                snapshotRef(rs),
                rs.getString("object_version"),
                rs.getLong("domain_revision"),
                rs.getLong("room_revision"),
                rs.getLong("projection_revision"),
                rs.getLong("initial_last_sequence"),
                instant(rs, "created_at"));
    }

    private IntakeEventReference event(ResultSet rs) throws SQLException {
        return new IntakeEventReference(
                rs.getString("binding_id"),
                rs.getString("thread_registration_id"),
                rs.getString("event_id"),
                rs.getString("message_id"),
                rs.getString("tenant_surrogate"),
                rs.getString("case_id"),
                rs.getLong("room_epoch"),
                rs.getLong("fencing_token"),
                rs.getString("thread_id"),
                rs.getString("actor_scope_hash"),
                rs.getString("agent_session_id"),
                snapshotRef(rs),
                rs.getString("object_version"),
                rs.getLong("event_sequence"),
                rs.getLong("domain_revision"),
                Audience.valueOf(rs.getString("audience")),
                instant(rs, "occurred_at"),
                instant(rs, "created_at"),
                eventSourceType(rs));
    }

    private static SourceType eventSourceType(ResultSet rs) throws SQLException {
        String value = rs.getString("event_source_type");
        return value == null ? null : SourceType.valueOf(value);
    }

    private static RoomGraphCommand.SnapshotRef snapshotRef(ResultSet rs) throws SQLException {
        return new RoomGraphCommand.SnapshotRef(
                rs.getString("artifact_id"),
                rs.getString("schema_version"),
                rs.getString("object_uri"),
                rs.getString("content_sha256"),
                rs.getLong("size_bytes"));
    }

    private <T> T decode(String value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new ProductionFinalizationRejectedException(
                    "PRODUCTION_RUNTIME_PERSISTED_JSON_INVALID",
                    "persisted AgentRun JSON cannot be decoded",
                    failure);
        }
    }

    private List<String> decodeList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException failure) {
            throw new ProductionFinalizationRejectedException(
                    "PRODUCTION_RUNTIME_PERSISTED_JSON_INVALID",
                    "persisted actor capabilities cannot be decoded",
                    failure);
        }
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static <T> Optional<T> unique(List<T> rows, String fact) {
        if (rows.size() > 1) {
            throw new ProductionFinalizationRejectedException(
                    "PRODUCTION_RUNTIME_FINALIZATION_FACTS_AMBIGUOUS",
                    fact + " is not unique");
        }
        return rows.stream().findFirst();
    }

    private record BaseFacts(
            ProductionIntakeFinalizationState.LogicalRun run,
            ProductionIntakeFinalizationState.Attempt attempt,
            ProductionIntakeFinalizationState.Epoch epoch,
            ProductionIntakeFinalizationState.Projection projection,
            String registrationStatus,
            String participantStatus,
            String accessSessionStatus,
            String agentSessionStatus,
            IntakeGraphThreadBinding binding) {}
}
