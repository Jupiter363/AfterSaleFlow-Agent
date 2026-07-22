package com.example.dispute.workflow.infrastructure.persistence.authority.intake;

import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.Authority;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ObjectReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Revalidates a Python Intake exchange request against current signed-synthetic authority. */
public final class JdbcSignedSyntheticIntakeExchangeAuthorityValidationPort
        implements IntakeExchangeAuthorityValidationPort {

    private static final String AUTHORITY_SQL = """
        select source.artifact_id, source.schema_version, source.object_uri,
               source.object_version, source.content_sha256, source.size_bytes
          from case_intake_synthetic_activity_admission admission
          join case_room_epoch epoch
            on epoch.id = admission.epoch_id
           and epoch.tenant_surrogate = admission.tenant_surrogate
           and epoch.case_id = admission.case_id
           and epoch.room_type = admission.room_type
           and epoch.room_epoch = admission.room_epoch
           and epoch.fencing_token = admission.fencing_token
          join case_intake_epoch_selection_binding selection
            on selection.epoch_id = admission.epoch_id
           and selection.tenant_surrogate = admission.tenant_surrogate
           and selection.case_id = admission.case_id
           and selection.room_type = admission.room_type
           and selection.room_epoch = admission.room_epoch
           and selection.fencing_token = admission.fencing_token
           and selection.selection_hash = admission.selection_hash
           and selection.writer_mode = admission.writer_mode
          join case_intake_epoch_party_authority party
            on party.authority_id = admission.party_authority_id
           and party.party = admission.party
           and party.epoch_id = admission.epoch_id
           and party.tenant_surrogate = admission.tenant_surrogate
           and party.case_id = admission.case_id
           and party.room_type = admission.room_type
           and party.room_epoch = admission.room_epoch
           and party.fencing_token = admission.fencing_token
           and party.access_session_id = admission.access_session_id
           and party.registration_id = admission.registration_id
           and party.registration_hash = admission.registration_hash
           and party.thread_id = admission.thread_id
           and party.actor_id = admission.actor_id
           and party.actor_role = admission.actor_role
           and party.actor_scope_hash = admission.actor_scope_hash
           and party.agent_session_id = admission.agent_session_id
          join case_intake_graph_thread_binding thread
            on thread.registration_id = party.registration_id
           and thread.tenant_surrogate = party.tenant_surrogate
           and thread.case_id = party.case_id
           and thread.room_type = party.room_type
           and thread.room_epoch = party.room_epoch
           and thread.fencing_token = party.fencing_token
           and thread.thread_id = party.thread_id
           and thread.actor_id = party.actor_id
           and thread.actor_role = party.actor_role
           and thread.audience = party.audience
           and thread.actor_scope_hash = party.actor_scope_hash
           and thread.agent_session_id = party.agent_session_id
           and thread.registration_hash = party.registration_hash
          join case_access_session access
            on access.id = party.access_session_id
           and access.tenant_id = party.session_tenant_id
           and access.case_id = party.session_case_id
           and access.actor_id = party.actor_id
           and access.actor_role = party.actor_role
           and access.permission_level = party.permission_level
          join agent_conversation_session agent
            on agent.id = party.agent_session_id
           and agent.tenant_id = party.session_tenant_id
           and agent.case_id = party.session_case_id
           and agent.room_type = party.room_type
           and agent.access_session_id = party.access_session_id
           and agent.actor_id = party.actor_id
           and agent.actor_role = party.actor_role
           and agent.agent_key = party.agent_key
           and agent.prompt_profile_id = party.prompt_profile_id
           and agent.memory_policy_id = party.memory_policy_id
          join case_intake_command_payload_authority payload
            on payload.payload_authority_id = admission.payload_authority_id
           and payload.epoch_id = admission.epoch_id
           and payload.party_authority_id = admission.party_authority_id
           and payload.access_session_id = admission.access_session_id
           and payload.registration_id = admission.registration_id
           and payload.tenant_surrogate = admission.tenant_surrogate
           and payload.case_id = admission.case_id
           and payload.room_type = admission.room_type
           and payload.room_epoch = admission.room_epoch
           and payload.fencing_token = admission.fencing_token
           and payload.thread_id = admission.thread_id
           and payload.actor_scope_hash = admission.actor_scope_hash
           and payload.agent_session_id = admission.agent_session_id
           and payload.command_id = admission.command_id
          join case_intake_snapshot_binding source
            on source.binding_id = payload.existing_event_binding_id
           and source.thread_registration_id = payload.registration_id
           and source.tenant_surrogate = payload.tenant_surrogate
           and source.case_id = payload.case_id
           and source.room_type = payload.room_type
           and source.room_epoch = payload.room_epoch
           and source.fencing_token = payload.fencing_token
           and source.thread_id = payload.thread_id
           and source.actor_scope_hash = payload.actor_scope_hash
           and source.agent_session_id = payload.agent_session_id
           and source.schema_version = payload.schema_version
           and source.artifact_id = payload.artifact_id
           and source.object_uri = payload.object_uri
           and source.object_version = payload.object_version
           and source.content_sha256 = payload.content_sha256
           and source.size_bytes = payload.size_bytes
         where admission.schema_version = 'intake-synthetic-activity-admission.v1'
           and admission.admission_status = 'VERIFIED'
           and admission.traffic_source = 'AUTHENTICATED_SIGNED_SYNTHETIC'
           and admission.room_type = 'INTAKE'
           and admission.writer_mode = 'SHADOW'
           and admission.command_type = 'INTAKE_MESSAGE'
           and payload.source_kind = 'EXISTING_PRIVATE_EVENT'
           and payload.schema_version = 'intake-turn-event.v2'
           and epoch.lifecycle_status = 'ACTIVE'
           and epoch.provisioning_status = 'READY'
           and epoch.selection_schema_version = 'room-epoch-selection.v2'
           and epoch.writer_mode = 'SHADOW'
           and epoch.process_revision = admission.process_revision
           and epoch.room_revision = admission.room_revision
           and admission.accepted_room_revision = admission.room_revision
           and thread.registration_status = 'REGISTERED'
           and thread.writer_mode = 'SHADOW'
           and access.status = 'ACTIVE'
           and agent.status = 'ACTIVE'
           and admission.deadline_epoch_millis > :nowEpochMillis
           and admission.tenant_surrogate = :tenantSurrogate
           and admission.case_id = :caseId
           and admission.room_epoch = :roomEpoch
           and admission.thread_id = :threadId
           and admission.actor_id = :actorId
           and admission.actor_role = :actorRole
           and thread.audience = :audience
           and thread.actor_capabilities_json = cast(:actorCapabilities as jsonb)
           and admission.actor_scope_hash = :actorScopeHash
           and admission.agent_session_id = :agentSessionId
           and admission.command_id = :commandId
           and admission.logical_run_id = :logicalRunId
           and admission.attempt_id = :attemptId
           and admission.request_hash = :requestHash
           and admission.graph_key = :graphKey
           and admission.graph_version = :graphVersion
           and admission.checkpoint_schema_version = :checkpointSchemaVersion
           and admission.process_revision = :processRevision
           and admission.command_type = :stageCode
           and admission.command_sequence = :stageSequence
        """;

    private final NamedParameterJdbcOperations jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcSignedSyntheticIntakeExchangeAuthorityValidationPort(
            DataSource dataSource, ObjectMapper objectMapper, Clock clock) {
        this(new NamedParameterJdbcTemplate(dataSource), objectMapper, clock);
    }

    public JdbcSignedSyntheticIntakeExchangeAuthorityValidationPort(
            NamedParameterJdbcOperations jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PayloadLoadGrant requirePayloadLoad(PayloadLoadClaim claim) {
        Objects.requireNonNull(claim, "claim");
        ArtifactGrant grant = requireAuthority(claim.request().authority());
        requireExactObject(claim.request().objectRef(), grant);
        return new PayloadLoadGrant(claim.request(), grant.objectVersion());
    }

    @Override
    public ProposalPutGrant requireProposalPut(ProposalPutClaim claim) {
        Objects.requireNonNull(claim, "claim");
        requireAuthority(claim.request().authority());
        return new ProposalPutGrant(claim.request());
    }

    private ArtifactGrant requireAuthority(Authority authority) {
        List<Map<String, Object>> rows = jdbc.queryForList(AUTHORITY_SQL, parameters(authority));
        if (rows.size() != 1) {
            throw rejected("signed synthetic Intake exchange authority is not current and exact");
        }
        Map<String, Object> row = rows.getFirst();
        return new ArtifactGrant(
                text(row, "artifact_id"),
                text(row, "schema_version"),
                text(row, "object_uri"),
                text(row, "object_version"),
                text(row, "content_sha256"),
                number(row, "size_bytes"));
    }

    private MapSqlParameterSource parameters(Authority authority) {
        try {
            return new MapSqlParameterSource()
                    .addValue("nowEpochMillis", clock.millis())
                    .addValue("tenantSurrogate", authority.tenantSurrogate())
                    .addValue("caseId", authority.caseId())
                    .addValue("roomEpoch", authority.roomEpoch())
                    .addValue("threadId", authority.threadId())
                    .addValue("actorId", authority.actorId())
                    .addValue("actorRole", authority.actorRole().name())
                    .addValue("audience", authority.audience().name())
                    .addValue(
                            "actorCapabilities",
                            objectMapper.writeValueAsString(authority.actorCapabilities()))
                    .addValue("actorScopeHash", authority.actorScopeHash())
                    .addValue("agentSessionId", authority.agentSessionId())
                    .addValue("commandId", authority.commandId())
                    .addValue("logicalRunId", authority.logicalRunId())
                    .addValue("attemptId", authority.attemptId())
                    .addValue("requestHash", authority.requestHash())
                    .addValue("graphKey", authority.graphKey())
                    .addValue("graphVersion", authority.graphVersion())
                    .addValue(
                            "checkpointSchemaVersion", authority.checkpointSchemaVersion())
                    .addValue("processRevision", authority.processRevision())
                    .addValue("stageCode", authority.stageCode())
                    .addValue("stageSequence", authority.stageSequence());
        } catch (JsonProcessingException failure) {
            throw rejected("Intake exchange capabilities cannot be canonicalized", failure);
        }
    }

    private static void requireExactObject(ObjectReference expected, ArtifactGrant actual) {
        if (!expected.artifactId().equals(actual.artifactId())
                || !expected.schemaVersion().equals(actual.schemaVersion())
                || !expected.uri().equals(actual.uri())
                || !expected.sha256().equals(actual.sha256())
                || expected.sizeBytes() != actual.sizeBytes()) {
            throw rejected("Intake exchange object is not the exact admitted private payload");
        }
    }

    private static String text(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw rejected("signed synthetic Intake exchange authority row is malformed");
        }
        return text;
    }

    private static long number(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Number number)) {
            throw rejected("signed synthetic Intake exchange authority row is malformed");
        }
        return number.longValue();
    }

    private static Rejected rejected(String message) {
        return new Rejected(message);
    }

    private static Rejected rejected(String message, Throwable cause) {
        return new Rejected(message, cause);
    }

    private record ArtifactGrant(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String sha256,
            long sizeBytes) {}
}
