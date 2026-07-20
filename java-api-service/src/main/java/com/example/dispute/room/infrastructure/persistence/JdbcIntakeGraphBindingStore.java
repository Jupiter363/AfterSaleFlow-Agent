package com.example.dispute.room.infrastructure.persistence;

import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingConflictException;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingStore;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for the two V043 Domain-side binding tables. */
@Repository
public class JdbcIntakeGraphBindingStore implements IntakeGraphBindingStore {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private static final String REGISTRATION_COLUMNS =
            """
            registration_id, schema_version, tenant_surrogate, case_id, room_type,
            room_epoch, fencing_token, thread_id, actor_id, actor_role, audience,
            actor_capabilities_json, actor_scope_hash, agent_session_id, graph_key,
            graph_version, checkpoint_schema_version, state_schema_version,
            prompt_version, model_profile_id, output_schema_version, policy_version,
            guardrail_version, tool_policy_version, writer_mode, registration_hash,
            issued_at
            """;

    private static final String SNAPSHOT_COLUMNS =
            """
            binding_id, thread_registration_id, tenant_surrogate, case_id, room_epoch,
            fencing_token, thread_id, actor_scope_hash, agent_session_id, artifact_id,
            schema_version, object_uri, object_version, content_sha256, size_bytes,
            domain_revision, room_revision, projection_revision, created_at
            """;

    private static final String EVENT_COLUMNS =
            """
            binding_id, thread_registration_id, event_id, message_id, tenant_surrogate,
            case_id, room_epoch, fencing_token, thread_id, actor_scope_hash,
            agent_session_id, artifact_id, schema_version, object_uri, object_version,
            content_sha256, size_bytes, event_sequence, domain_revision, audience,
            occurred_at, created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIntakeGraphBindingStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IntakeGraphThreadBinding> findRegistration(String registrationId) {
        List<IntakeGraphThreadBinding> rows =
                jdbc.query(
                        "select %s from case_intake_graph_thread_binding "
                                .formatted(REGISTRATION_COLUMNS)
                                + "where registration_id = :registrationId",
                        Map.of("registrationId", registrationId),
                        JdbcIntakeGraphBindingStore::mapRegistration);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    @Override
    @Transactional
    public WriteReceipt<IntakeGraphThreadBinding> register(IntakeGraphThreadBinding binding) {
        binding.registration().requireCanonicalHash();
        requireRegistrationAuthority(binding);
        int inserted = jdbc.update(
                """
                insert into case_intake_graph_thread_binding (
                    registration_id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, fencing_token, thread_id, actor_id, actor_role, audience,
                    actor_capabilities_json, actor_scope_hash, agent_session_id, graph_key,
                    graph_version, checkpoint_schema_version, state_schema_version,
                    prompt_version, model_profile_id, output_schema_version, policy_version,
                    guardrail_version, tool_policy_version, writer_mode, registration_hash,
                    issued_at
                ) values (
                    :registrationId, :schemaVersion, :tenantSurrogate, :caseId, 'INTAKE',
                    :roomEpoch, :fencingToken, :threadId, :actorId, :actorRole, :audience,
                    cast(:actorCapabilities as jsonb), :actorScopeHash, :agentSessionId,
                    :graphKey, :graphVersion, :checkpointSchemaVersion, :stateSchemaVersion,
                    :promptVersion, :modelProfileId, :outputSchemaVersion, :policyVersion,
                    :guardrailVersion, :toolPolicyVersion, :writerMode, :registrationHash,
                    :issuedAt
                )
                on conflict do nothing
                """,
                registrationParameters(binding));
        if (inserted == 1) {
            return WriteReceipt.created(binding);
        }
        IntakeGraphThreadBinding existing =
                findConflictingRegistration(binding)
                        .orElseThrow(() -> conflict("registration uniqueness"));
        if (!existing.equals(binding)) {
            throw conflict("registration hash, private tuple, or fencing token");
        }
        return WriteReceipt.replayed(existing);
    }

    @Override
    @Transactional
    public WriteReceipt<IntakeSnapshotReference> bindInitialSnapshot(
            IntakeSnapshotReference reference) {
        IntakeGraphThreadBinding thread = lockThread(reference.threadRegistrationId());
        requireReferenceScope(thread, reference);
        MapSqlParameterSource parameters = snapshotParameters(reference)
                .addValue("actorAudience", thread.registration().actorScope().audience().name());
        int inserted = jdbc.update(
                """
                insert into case_intake_snapshot_binding (
                    binding_id, thread_registration_id, tenant_surrogate, case_id, room_type,
                    room_epoch, fencing_token, thread_id, actor_scope_hash, agent_session_id,
                    actor_audience, binding_type, schema_version, artifact_id, object_uri,
                    object_version, content_sha256, size_bytes, visibility, domain_revision,
                    room_revision, projection_revision, initialization_marker, created_at
                ) values (
                    :bindingId, :threadRegistrationId, :tenantSurrogate, :caseId, 'INTAKE',
                    :roomEpoch, :fencingToken, :threadId, :actorScopeHash, :agentSessionId,
                    :actorAudience, 'INITIAL', :schemaVersion, :artifactId, :objectUri,
                    :objectVersion, :contentSha256, :sizeBytes, 'PRIVATE', :domainRevision,
                    :roomRevision, :projectionRevision, true, :createdAt
                )
                on conflict do nothing
                """,
                parameters);
        if (inserted == 1) {
            return WriteReceipt.created(reference);
        }
        IntakeSnapshotReference existing = findInitialSnapshot(reference)
                .orElseThrow(() -> conflict("initial snapshot uniqueness"));
        if (!existing.equals(reference)) {
            throw conflict("a private thread can import only one exact initial snapshot");
        }
        return WriteReceipt.replayed(existing);
    }

    @Override
    @Transactional
    public WriteReceipt<IntakeEventReference> bindEvent(IntakeEventReference reference) {
        IntakeGraphThreadBinding thread = lockThread(reference.threadRegistrationId());
        requireReferenceScope(thread, reference);
        requireInitialSnapshot(reference.threadRegistrationId());
        Optional<IntakeEventReference> replay = findEvent(reference);
        if (replay.isPresent()) {
            if (!replay.get().equals(reference)) {
                throw conflict("event id or sequence is bound to another hash");
            }
            return WriteReceipt.replayed(replay.get());
        }
        Optional<Long> maximumSequence = maximumEventSequence(reference.threadRegistrationId());
        if (maximumSequence.isPresent()
                && reference.sequenceNo() != Math.addExact(maximumSequence.get(), 1L)) {
            throw conflict("event sequence is not the next ordered reference");
        }
        int inserted = jdbc.update(
                """
                insert into case_intake_snapshot_binding (
                    binding_id, thread_registration_id, tenant_surrogate, case_id, room_type,
                    room_epoch, fencing_token, thread_id, actor_scope_hash, agent_session_id,
                    actor_audience, binding_type, schema_version, artifact_id, object_uri,
                    object_version, content_sha256, size_bytes, visibility, domain_revision,
                    event_id, message_id, event_sequence, audience, occurred_at,
                    initialization_marker, created_at
                ) values (
                    :bindingId, :threadRegistrationId, :tenantSurrogate, :caseId, 'INTAKE',
                    :roomEpoch, :fencingToken, :threadId, :actorScopeHash, :agentSessionId,
                    :audience, 'EVENT', :schemaVersion, :artifactId, :objectUri,
                    :objectVersion, :contentSha256, :sizeBytes, 'PRIVATE', :domainRevision,
                    :eventId, :messageId, :eventSequence, :audience, :occurredAt, false, :createdAt
                )
                on conflict do nothing
                """,
                eventParameters(reference));
        if (inserted != 1) {
            throw conflict("event id, sequence, or immutable artifact");
        }
        return WriteReceipt.created(reference);
    }

    private Optional<IntakeGraphThreadBinding> findConflictingRegistration(
            IntakeGraphThreadBinding candidate) {
        IntakePrivateThreadRegistration registration = candidate.registration();
        MapSqlParameterSource parameters = registrationParameters(candidate);
        List<IntakeGraphThreadBinding> rows = jdbc.query(
                """
                select %s
                  from case_intake_graph_thread_binding
                 where registration_id = :registrationId
                    or thread_id = :threadId
                    or (
                        tenant_surrogate = :tenantSurrogate
                        and case_id = :caseId
                        and room_epoch = :roomEpoch
                        and actor_scope_hash = :actorScopeHash
                        and agent_session_id = :agentSessionId
                        and graph_key = :graphKey
                        and graph_version = :graphVersion
                        and checkpoint_schema_version = :checkpointSchemaVersion
                    )
                """.formatted(REGISTRATION_COLUMNS),
                parameters,
                JdbcIntakeGraphBindingStore::mapRegistration);
        if (rows.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
    }

    private void requireRegistrationAuthority(IntakeGraphThreadBinding binding) {
        IntakePrivateThreadRegistration registration = binding.registration();
        Integer epochCount = jdbc.queryForObject(
                """
                select count(*)
                  from case_room_epoch
                 where tenant_surrogate = :tenantSurrogate
                   and case_id = :caseId
                   and room_type = 'INTAKE'
                   and room_epoch = :roomEpoch
                   and fencing_token = :fencingToken
                   and writer_mode = :writerMode
                   and graph_key = :graphKey
                   and graph_version = :graphVersion
                   and checkpoint_schema_version = :checkpointSchemaVersion
                   and selection_schema_version = 'room-epoch-selection.v2'
                   and lifecycle_status = 'ACTIVE'
                """,
                registrationParameters(binding),
                Integer.class);
        if (epochCount == null || epochCount != 1) {
            throw conflict("tenant, case, epoch, fence, mode, or Graph pins are unauthorized");
        }

        Integer sessionCount = jdbc.queryForObject(
                """
                select count(*)
                 from agent_conversation_session
                 where id = :agentSessionId
                   and tenant_id = :tenantSurrogate
                   and case_id = :caseId
                   and room_type = 'INTAKE'
                   and actor_id = :actorId
                   and actor_role = :actorRole
                   and prompt_profile_id = :promptVersion
                   and status = 'ACTIVE'
                """,
                registrationParameters(binding),
                Integer.class);
        if (sessionCount == null || sessionCount != 1) {
            throw conflict("agent session or private actor scope is unauthorized");
        }
    }

    private Optional<IntakeSnapshotReference> findInitialSnapshot(
            IntakeSnapshotReference candidate) {
        List<IntakeSnapshotReference> rows = jdbc.query(
                """
                select %s
                  from case_intake_snapshot_binding
                 where binding_type = 'INITIAL'
                   and (binding_id = :bindingId
                        or thread_registration_id = :threadRegistrationId
                        or (tenant_surrogate = :tenantSurrogate
                            and artifact_id = :artifactId))
                """.formatted(SNAPSHOT_COLUMNS),
                snapshotParameters(candidate),
                JdbcIntakeGraphBindingStore::mapSnapshot);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    private Optional<IntakeEventReference> findEvent(IntakeEventReference candidate) {
        List<IntakeEventReference> rows = jdbc.query(
                """
                select %s
                  from case_intake_snapshot_binding
                 where binding_type = 'EVENT'
                   and (binding_id = :bindingId
                        or (thread_registration_id = :threadRegistrationId
                            and event_sequence = :eventSequence)
                        or (tenant_surrogate = :tenantSurrogate
                            and (event_id = :eventId
                                or message_id = :messageId
                                or artifact_id = :artifactId)))
                """.formatted(EVENT_COLUMNS),
                eventParameters(candidate),
                JdbcIntakeGraphBindingStore::mapEvent);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    private IntakeGraphThreadBinding lockThread(String registrationId) {
        List<IntakeGraphThreadBinding> rows = jdbc.query(
                "select %s from case_intake_graph_thread_binding "
                                .formatted(REGISTRATION_COLUMNS)
                        + "where registration_id = :registrationId for update",
                Map.of("registrationId", registrationId),
                JdbcIntakeGraphBindingStore::mapRegistration);
        if (rows.size() != 1) {
            throw conflict("private thread registration is missing");
        }
        return rows.getFirst();
    }

    private static void requireReferenceScope(
            IntakeGraphThreadBinding thread, IntakeSnapshotReference reference) {
        requireReferenceScope(
                thread,
                reference.threadRegistrationId(),
                reference.tenantSurrogate(),
                reference.caseId(),
                reference.roomEpoch(),
                reference.fencingToken(),
                reference.threadId(),
                reference.actorScopeHash(),
                reference.agentSessionId());
    }

    private static void requireReferenceScope(
            IntakeGraphThreadBinding thread, IntakeEventReference reference) {
        requireReferenceScope(
                thread,
                reference.threadRegistrationId(),
                reference.tenantSurrogate(),
                reference.caseId(),
                reference.roomEpoch(),
                reference.fencingToken(),
                reference.threadId(),
                reference.actorScopeHash(),
                reference.agentSessionId());
        if (thread.registration().actorScope().audience() != reference.audience()) {
            throw conflict("event audience is outside the private thread scope");
        }
    }

    private static void requireReferenceScope(
            IntakeGraphThreadBinding thread,
            String registrationId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String threadId,
            String actorScopeHash,
            String agentSessionId) {
        IntakePrivateThreadRegistration registration = thread.registration();
        if (!registration.registrationId().equals(registrationId)
                || !registration.tenantSurrogate().equals(tenantSurrogate)
                || !registration.caseId().equals(caseId)
                || registration.roomEpoch() != roomEpoch
                || thread.fencingToken() != fencingToken
                || !registration.threadId().equals(threadId)
                || !registration.actorScopeHash().equals(actorScopeHash)
                || !registration.agentSessionId().equals(agentSessionId)) {
            throw conflict("reference is outside the private thread scope");
        }
    }

    private void requireInitialSnapshot(String registrationId) {
        Integer count = jdbc.queryForObject(
                """
                select count(*)
                  from case_intake_snapshot_binding
                 where thread_registration_id = :registrationId
                   and initialization_marker
                """,
                Map.of("registrationId", registrationId),
                Integer.class);
        if (count == null || count != 1) {
            throw conflict("event cannot precede the initial snapshot");
        }
    }

    private Optional<Long> maximumEventSequence(String registrationId) {
        Long maximum = jdbc.queryForObject(
                """
                select max(event_sequence)
                  from case_intake_snapshot_binding
                 where thread_registration_id = :registrationId
                   and binding_type = 'EVENT'
                """,
                Map.of("registrationId", registrationId),
                Long.class);
        return Optional.ofNullable(maximum);
    }

    private static MapSqlParameterSource registrationParameters(
            IntakeGraphThreadBinding binding) {
        IntakePrivateThreadRegistration registration = binding.registration();
        return new MapSqlParameterSource()
                .addValue("registrationId", registration.registrationId())
                .addValue("schemaVersion", registration.schemaVersion())
                .addValue("tenantSurrogate", registration.tenantSurrogate())
                .addValue("caseId", registration.caseId())
                .addValue("roomEpoch", registration.roomEpoch())
                .addValue("fencingToken", binding.fencingToken())
                .addValue("threadId", registration.threadId())
                .addValue("actorId", registration.actorScope().actorId())
                .addValue("actorRole", registration.actorScope().actorRole().name())
                .addValue("audience", registration.actorScope().audience().name())
                .addValue("actorCapabilities", writeCapabilities(registration.actorScope().capabilities()))
                .addValue("actorScopeHash", registration.actorScopeHash())
                .addValue("agentSessionId", registration.agentSessionId())
                .addValue("graphKey", registration.graphKey())
                .addValue("graphVersion", registration.graphVersion())
                .addValue("checkpointSchemaVersion", registration.checkpointSchemaVersion())
                .addValue("stateSchemaVersion", registration.stateSchemaVersion())
                .addValue("promptVersion", registration.promptVersion())
                .addValue("modelProfileId", registration.modelProfileId())
                .addValue("outputSchemaVersion", registration.outputSchemaVersion())
                .addValue("policyVersion", registration.policyVersion())
                .addValue("guardrailVersion", registration.guardrailVersion())
                .addValue("toolPolicyVersion", registration.toolPolicyVersion())
                .addValue("writerMode", registration.writerMode().name())
                .addValue("registrationHash", registration.registrationHash())
                .addValue("issuedAt", atOffset(registration.issuedAt()));
    }

    private static MapSqlParameterSource snapshotParameters(IntakeSnapshotReference reference) {
        return baseReferenceParameters(
                        reference.bindingId(),
                        reference.threadRegistrationId(),
                        reference.tenantSurrogate(),
                        reference.caseId(),
                        reference.roomEpoch(),
                        reference.fencingToken(),
                        reference.threadId(),
                        reference.actorScopeHash(),
                        reference.agentSessionId(),
                        reference.payloadRef(),
                        reference.objectVersion(),
                        reference.domainRevision(),
                        reference.createdAt())
                .addValue("roomRevision", reference.roomRevision())
                .addValue("projectionRevision", reference.projectionRevision());
    }

    private static MapSqlParameterSource eventParameters(IntakeEventReference reference) {
        return baseReferenceParameters(
                        reference.bindingId(),
                        reference.threadRegistrationId(),
                        reference.tenantSurrogate(),
                        reference.caseId(),
                        reference.roomEpoch(),
                        reference.fencingToken(),
                        reference.threadId(),
                        reference.actorScopeHash(),
                        reference.agentSessionId(),
                        reference.payloadRef(),
                        reference.objectVersion(),
                        reference.domainRevision(),
                        reference.createdAt())
                .addValue("eventId", reference.eventId())
                .addValue("messageId", reference.messageId())
                .addValue("eventSequence", reference.sequenceNo())
                .addValue("audience", reference.audience().name())
                .addValue("occurredAt", atOffset(reference.occurredAt()));
    }

    private static MapSqlParameterSource baseReferenceParameters(
            String bindingId,
            String registrationId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String threadId,
            String actorScopeHash,
            String agentSessionId,
            RoomGraphCommand.SnapshotRef payloadRef,
            String objectVersion,
            long domainRevision,
            java.time.Instant createdAt) {
        return new MapSqlParameterSource()
                .addValue("bindingId", bindingId)
                .addValue("threadRegistrationId", registrationId)
                .addValue("tenantSurrogate", tenantSurrogate)
                .addValue("caseId", caseId)
                .addValue("roomEpoch", roomEpoch)
                .addValue("fencingToken", fencingToken)
                .addValue("threadId", threadId)
                .addValue("actorScopeHash", actorScopeHash)
                .addValue("agentSessionId", agentSessionId)
                .addValue("artifactId", payloadRef.artifactId())
                .addValue("schemaVersion", payloadRef.schemaVersion())
                .addValue("objectUri", payloadRef.uri())
                .addValue("objectVersion", objectVersion)
                .addValue("contentSha256", payloadRef.sha256())
                .addValue("sizeBytes", payloadRef.sizeBytes())
                .addValue("domainRevision", domainRevision)
                .addValue("createdAt", atOffset(createdAt));
    }

    private static IntakeGraphThreadBinding mapRegistration(ResultSet row, int ignored)
            throws SQLException {
        IntakePrivateThreadRegistration.ActorScope scope =
                new IntakePrivateThreadRegistration.ActorScope(
                        row.getString("actor_id"),
                        ActorRole.valueOf(row.getString("actor_role")),
                        Audience.valueOf(row.getString("audience")),
                        readCapabilities(row.getString("actor_capabilities_json")));
        IntakePrivateThreadRegistration registration =
                new IntakePrivateThreadRegistration(
                        row.getString("schema_version"),
                        row.getString("registration_id"),
                        row.getString("tenant_surrogate"),
                        row.getString("case_id"),
                        "INTAKE",
                        row.getLong("room_epoch"),
                        row.getString("thread_id"),
                        scope,
                        row.getString("actor_scope_hash"),
                        row.getString("agent_session_id"),
                        row.getString("graph_key"),
                        row.getString("graph_version"),
                        row.getString("checkpoint_schema_version"),
                        row.getString("state_schema_version"),
                        row.getString("prompt_version"),
                        row.getString("model_profile_id"),
                        row.getString("output_schema_version"),
                        row.getString("policy_version"),
                        row.getString("guardrail_version"),
                        row.getString("tool_policy_version"),
                        WriterMode.valueOf(row.getString("writer_mode")),
                        row.getObject("issued_at", OffsetDateTime.class).toInstant(),
                        row.getString("registration_hash"));
        return new IntakeGraphThreadBinding(registration, row.getLong("fencing_token"));
    }

    private static IntakeSnapshotReference mapSnapshot(ResultSet row, int ignored)
            throws SQLException {
        return new IntakeSnapshotReference(
                row.getString("binding_id"),
                row.getString("thread_registration_id"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getLong("room_epoch"),
                row.getLong("fencing_token"),
                row.getString("thread_id"),
                row.getString("actor_scope_hash"),
                row.getString("agent_session_id"),
                snapshotRef(row),
                row.getString("object_version"),
                row.getLong("domain_revision"),
                row.getLong("room_revision"),
                row.getLong("projection_revision"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static IntakeEventReference mapEvent(ResultSet row, int ignored) throws SQLException {
        return new IntakeEventReference(
                row.getString("binding_id"),
                row.getString("thread_registration_id"),
                row.getString("event_id"),
                row.getString("message_id"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getLong("room_epoch"),
                row.getLong("fencing_token"),
                row.getString("thread_id"),
                row.getString("actor_scope_hash"),
                row.getString("agent_session_id"),
                snapshotRef(row),
                row.getString("object_version"),
                row.getLong("event_sequence"),
                row.getLong("domain_revision"),
                Audience.valueOf(row.getString("audience")),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static RoomGraphCommand.SnapshotRef snapshotRef(ResultSet row) throws SQLException {
        return new RoomGraphCommand.SnapshotRef(
                row.getString("artifact_id"),
                row.getString("schema_version"),
                row.getString("object_uri"),
                row.getString("content_sha256"),
                row.getLong("size_bytes"));
    }

    private static String writeCapabilities(List<String> capabilities) {
        try {
            return MAPPER.writeValueAsString(capabilities);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("actor capabilities are not serializable", failure);
        }
    }

    private static List<String> readCapabilities(String value) {
        try {
            return MAPPER.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("persisted actor capabilities are invalid", failure);
        }
    }

    private static OffsetDateTime atOffset(java.time.Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static IntakeGraphBindingConflictException conflict(String detail) {
        return new IntakeGraphBindingConflictException("Intake Graph binding conflict: " + detail);
    }
}
