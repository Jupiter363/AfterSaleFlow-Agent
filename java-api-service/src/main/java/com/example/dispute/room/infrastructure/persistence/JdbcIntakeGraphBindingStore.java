package com.example.dispute.room.infrastructure.persistence;

import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingConflictException;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingStore;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher.SourceType;
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
    private static final String REQUIRED_ACCESS_SCOPES_JSON =
            "[\"CASE_READ\",\"INTAKE_PRIVATE_READ\",\"INTAKE_PARTICIPATE\",\"AGENT_SESSION_WRITE\"]";

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
            domain_revision, room_revision, projection_revision, initial_last_sequence,
            created_at
            """;

    private static final String EVENT_COLUMNS =
            """
            binding_id, thread_registration_id, event_id, message_id, tenant_surrogate,
            case_id, room_epoch, fencing_token, thread_id, actor_scope_hash,
            agent_session_id, artifact_id, schema_version, object_uri, object_version,
            content_sha256, size_bytes, event_sequence, domain_revision, audience,
            occurred_at, created_at, event_source_type
            """;

    private static final String CURRENT_EVENT_SLOTS_SQL =
            """
            select slot.logical_sequence,
                   slot.current_binding_id,
                   slot.current_generation,
                   coalesce(proof.matched_run_count, 0) as matched_run_count,
                   coalesce(
                       proof.matched_run_count = 1
                       and proof.latest_attempt_matches_binding
                       and proof.run_status in ('FAILED', 'ABORTED')
                       and proof.finalization_status = 'UNCOMMITTED'
                       and proof.result_ready_attempt_id is null
                       and proof.committed_attempt_id is null
                       and proof.final_result_hash is null
                       and proof.committed_manifest_id is null
                       and proof.committed_manifest_hash is null
                       and proof.finalized_at is null
                       and proof.attempt_status in ('FAILED', 'ABORTED')
                       and proof.termination_code = 'FAIL_LOGICAL_RUN'
                       and proof.error_retryable is false
                       and proof.completed_at is not null,
                       false
                   ) as recovery_eligible
              from case_intake_event_slot_authority slot
              join case_intake_snapshot_binding binding
                on binding.binding_id = slot.current_binding_id
               and binding.thread_registration_id = slot.thread_registration_id
               and binding.event_sequence = slot.logical_sequence
               and binding.binding_generation = slot.current_generation
               and binding.binding_type = 'EVENT'
              left join lateral (
                  select candidate.*,
                         count(*) over () as matched_run_count
                    from (
                        select run.run_status,
                               run.finalization_status,
                               run.result_ready_attempt_id,
                               run.committed_attempt_id,
                               run.final_result_hash,
                               run.committed_manifest_id,
                               run.committed_manifest_hash,
                               run.finalized_at,
                               attempt.attempt_status,
                               attempt.termination_code,
                               attempt.error_retryable,
                               attempt.completed_at,
                               (
                                   attempt.command_json #>> '{case_id}' = binding.case_id
                                   and attempt.command_json #>> '{thread_id}' = binding.thread_id
                                   and attempt.command_json #>> '{room_epoch}' = binding.room_epoch::text
                                   and attempt.command_json #>> '{actor_scope,audience}' = binding.audience
                                   and attempt.command_json #>> '{event_ref,artifact_id}' = binding.artifact_id
                                   and attempt.command_json #>> '{event_ref,schema_version}' = binding.schema_version
                                   and attempt.command_json #>> '{event_ref,uri}' = binding.object_uri
                                   and attempt.command_json #>> '{event_ref,sha256}' = binding.content_sha256
                                   and attempt.command_json #>> '{event_ref,size_bytes}' = binding.size_bytes::text
                               ) as latest_attempt_matches_binding,
                               run.id as run_id
                          from agent_run run
                          join lateral (
                              select candidate_attempt.*
                                from agent_run_attempt candidate_attempt
                               where candidate_attempt.agent_run_id = run.id
                               order by candidate_attempt.attempt_no desc
                               limit 1
                          ) attempt on true
                         where run.case_id = binding.case_id
                           and run.room_type = 'INTAKE'
                           and exists (
                               select 1
                                 from agent_run_attempt bound_attempt
                                where bound_attempt.agent_run_id = run.id
                                  and bound_attempt.command_json #>> '{case_id}' = binding.case_id
                                  and bound_attempt.command_json #>> '{thread_id}' = binding.thread_id
                                  and bound_attempt.command_json #>> '{room_epoch}' = binding.room_epoch::text
                                  and bound_attempt.command_json #>> '{actor_scope,audience}' = binding.audience
                                  and bound_attempt.command_json #>> '{event_ref,artifact_id}' = binding.artifact_id
                                  and bound_attempt.command_json #>> '{event_ref,schema_version}' = binding.schema_version
                                  and bound_attempt.command_json #>> '{event_ref,uri}' = binding.object_uri
                                  and bound_attempt.command_json #>> '{event_ref,sha256}' = binding.content_sha256
                                  and bound_attempt.command_json #>> '{event_ref,size_bytes}' = binding.size_bytes::text
                           )
                    ) candidate
                   order by candidate.run_id
                   limit 1
              ) proof on true
             where slot.thread_registration_id = :registrationId
             order by slot.logical_sequence
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
    public ThreadSnapshotState lockThreadSnapshotState(String registrationId) {
        IntakeGraphThreadBinding thread = lockThread(registrationId);
        return new ThreadSnapshotState(thread, findInitialSnapshot(registrationId));
    }

    @Override
    @Transactional
    public EventAllocation allocateEvent(String registrationId, String eventId, String messageId) {
        IntakeGraphThreadBinding thread = lockThread(registrationId);
        requireInitialSnapshot(registrationId);
        List<IntakeEventReference> existing = jdbc.query(
                """
                select %s
                  from case_intake_snapshot_binding
                 where binding_type = 'EVENT'
                   and thread_registration_id = :registrationId
                   and (event_id = :eventId or message_id = :messageId)
                """.formatted(EVENT_COLUMNS),
                Map.of("registrationId", registrationId, "eventId", eventId, "messageId", messageId),
                JdbcIntakeGraphBindingStore::mapEvent);
        if (existing.size() > 1) {
            throw conflict("event id or message id resolves to multiple private events");
        }
        if (existing.size() == 1) {
            IntakeEventReference event = existing.getFirst();
            if (!event.eventId().equals(eventId) || !event.messageId().equals(messageId)) {
                throw conflict("event id and message id are bound to different private events");
            }
            requireReferenceScope(thread, event);
            return new EventAllocation(event.sequenceNo(), Optional.of(event));
        }
        long initialLastSequence = requireInitialSnapshot(registrationId);
        EventSequenceState sequenceState = eventSequenceState(registrationId, initialLastSequence);
        long sequence = sequenceState.recoverySlot()
                .map(EventSlotState::sequenceNo)
                .orElseGet(() -> Math.addExact(sequenceState.maximumSequence(), 1L));
        return new EventAllocation(sequence, Optional.empty());
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
        if (!sameThreadIdentity(existing, binding)) {
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
                    room_revision, projection_revision, initial_last_sequence,
                    initialization_marker, created_at, event_source_type
                ) values (
                    :bindingId, :threadRegistrationId, :tenantSurrogate, :caseId, 'INTAKE',
                    :roomEpoch, :fencingToken, :threadId, :actorScopeHash, :agentSessionId,
                    :actorAudience, 'INITIAL', :schemaVersion, :artifactId, :objectUri,
                    :objectVersion, :contentSha256, :sizeBytes, 'PRIVATE', :domainRevision,
                    :roomRevision, :projectionRevision, :initialLastSequence, true, :createdAt, null
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
        long initialLastSequence = requireInitialSnapshot(reference.threadRegistrationId());
        Optional<IntakeEventReference> replay = findExactEvent(reference);
        if (replay.isPresent()) {
            if (!replay.get().equals(reference)) {
                throw conflict("event id or sequence is bound to another hash");
            }
            return WriteReceipt.replayed(replay.get());
        }
        EventSequenceState sequenceState =
                eventSequenceState(reference.threadRegistrationId(), initialLastSequence);
        Optional<EventSlotState> recoverySlot = sequenceState.recoverySlot();
        boolean recovery = recoverySlot
                .map(slot -> slot.sequenceNo() == reference.sequenceNo())
                .orElse(false);
        if (recoverySlot.isPresent() && !recovery) {
            throw conflict("a terminal uncommitted event sequence must be recovered first");
        }
        if (!recovery
                && reference.sequenceNo()
                        != Math.addExact(sequenceState.maximumSequence(), 1L)) {
            throw conflict("event sequence is not the next ordered reference");
        }
        long generation = recovery
                ? Math.addExact(recoverySlot.orElseThrow().generation(), 1L)
                : 1L;
        String supersedesBindingId =
                recovery ? recoverySlot.orElseThrow().bindingId() : null;
        MapSqlParameterSource parameters = eventParameters(reference)
                .addValue("bindingGeneration", generation)
                .addValue("supersedesBindingId", supersedesBindingId);
        int inserted = jdbc.update(
                """
                insert into case_intake_snapshot_binding (
                    binding_id, thread_registration_id, tenant_surrogate, case_id, room_type,
                    room_epoch, fencing_token, thread_id, actor_scope_hash, agent_session_id,
                    actor_audience, binding_type, schema_version, artifact_id, object_uri,
                    object_version, content_sha256, size_bytes, visibility, domain_revision,
                    event_id, message_id, event_sequence, audience, occurred_at,
                    initialization_marker, created_at, event_source_type,
                    binding_generation, supersedes_binding_id
                ) values (
                    :bindingId, :threadRegistrationId, :tenantSurrogate, :caseId, 'INTAKE',
                    :roomEpoch, :fencingToken, :threadId, :actorScopeHash, :agentSessionId,
                    :audience, 'EVENT', :schemaVersion, :artifactId, :objectUri,
                    :objectVersion, :contentSha256, :sizeBytes, 'PRIVATE', :domainRevision,
                    :eventId, :messageId, :eventSequence, :audience, :occurredAt, false, :createdAt,
                    :eventSourceType, :bindingGeneration, :supersedesBindingId
                )
                on conflict do nothing
                """,
                parameters);
        if (inserted != 1) {
            throw conflict("event id, sequence, or immutable artifact");
        }
        int authorityWritten = recovery
                ? advanceEventSlotAuthority(reference, generation, supersedesBindingId)
                : createEventSlotAuthority(reference);
        if (authorityWritten != 1) {
            throw conflict("event slot authority compare-and-set failed");
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
                  from agent_conversation_session session
                  join case_access_session access
                    on access.id = session.access_session_id
                 where session.id = :agentSessionId
                   and session.tenant_id = access.tenant_id
                   and session.case_id = :caseId
                   and session.room_type = 'INTAKE'
                   and session.actor_id = :actorId
                   and session.actor_role = :actorRole
                   and session.prompt_profile_id = :promptVersion
                   and session.status = 'ACTIVE'
                   and access.case_id = :caseId
                   and access.actor_id = :actorId
                   and access.actor_role = :actorRole
                   and access.status = 'ACTIVE'
                   and access.permission_level = case
                       when :actorRole = 'USER' then 'PARTY_USER'
                       when :actorRole = 'MERCHANT' then 'PARTY_MERCHANT'
                       else '__DENY__'
                   end
                   and access.permission_scopes_json @> cast(:requiredAccessScopes as jsonb)
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

    private Optional<IntakeSnapshotReference> findInitialSnapshot(String registrationId) {
        List<IntakeSnapshotReference> rows = jdbc.query(
                """
                select %s
                  from case_intake_snapshot_binding
                 where binding_type = 'INITIAL'
                   and thread_registration_id = :registrationId
                """.formatted(SNAPSHOT_COLUMNS),
                Map.of("registrationId", registrationId),
                JdbcIntakeGraphBindingStore::mapSnapshot);
        if (rows.size() > 1) {
            throw conflict("multiple initial snapshots exist for one private thread");
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private Optional<IntakeEventReference> findExactEvent(IntakeEventReference candidate) {
        List<IntakeEventReference> rows = jdbc.query(
                """
                select %s
                  from case_intake_snapshot_binding
                 where binding_type = 'EVENT'
                   and (binding_id = :bindingId
                         or (tenant_surrogate = :tenantSurrogate
                             and (event_id = :eventId
                                 or message_id = :messageId
                                or artifact_id = :artifactId)))
                """.formatted(EVENT_COLUMNS),
                eventParameters(candidate),
                JdbcIntakeGraphBindingStore::mapEvent);
        if (rows.size() > 1) {
            throw conflict("event identity resolves to multiple immutable bindings");
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
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

    /** The generated thread id and issue timestamp are intentionally excluded for first-write races. */
    private static boolean sameThreadIdentity(
            IntakeGraphThreadBinding left, IntakeGraphThreadBinding right) {
        IntakePrivateThreadRegistration a = left.registration();
        IntakePrivateThreadRegistration b = right.registration();
        return a.registrationId().equals(b.registrationId())
                && a.tenantSurrogate().equals(b.tenantSurrogate())
                && a.caseId().equals(b.caseId())
                && a.roomType().equals(b.roomType())
                && a.roomEpoch() == b.roomEpoch()
                && left.fencingToken() == right.fencingToken()
                && a.actorScope().equals(b.actorScope())
                && a.actorScopeHash().equals(b.actorScopeHash())
                && a.agentSessionId().equals(b.agentSessionId())
                && a.graphKey().equals(b.graphKey())
                && a.graphVersion().equals(b.graphVersion())
                && a.checkpointSchemaVersion().equals(b.checkpointSchemaVersion())
                && a.stateSchemaVersion().equals(b.stateSchemaVersion())
                && a.promptVersion().equals(b.promptVersion())
                && a.modelProfileId().equals(b.modelProfileId())
                && a.outputSchemaVersion().equals(b.outputSchemaVersion())
                && a.policyVersion().equals(b.policyVersion())
                && a.guardrailVersion().equals(b.guardrailVersion())
                && a.toolPolicyVersion().equals(b.toolPolicyVersion())
                && a.writerMode() == b.writerMode();
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

    private long requireInitialSnapshot(String registrationId) {
        List<Long> rows = jdbc.queryForList(
                """
                select initial_last_sequence
                  from case_intake_snapshot_binding
                 where thread_registration_id = :registrationId
                   and initialization_marker
                """,
                Map.of("registrationId", registrationId),
                Long.class);
        if (rows.size() != 1 || rows.getFirst() == null) {
            throw conflict("event cannot precede the initial snapshot");
        }
        return rows.getFirst();
    }

    private EventSequenceState eventSequenceState(
            String registrationId, long initialLastSequence) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                CURRENT_EVENT_SLOTS_SQL,
                Map.of("registrationId", registrationId));
        long expectedSequence = Math.addExact(initialLastSequence, 1L);
        java.util.ArrayList<EventSlotState> slots = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            long sequence = number(row, "logical_sequence");
            if (sequence <= initialLastSequence) {
                continue;
            }
            if (sequence != expectedSequence) {
                throw conflict("event slot authority is not contiguous with the initial snapshot");
            }
            slots.add(new EventSlotState(
                    sequence,
                    text(row, "current_binding_id"),
                    number(row, "current_generation"),
                    Boolean.TRUE.equals(row.get("recovery_eligible"))));
            expectedSequence = Math.addExact(expectedSequence, 1L);
        }
        long maximumSequence = slots.isEmpty()
                ? initialLastSequence
                : slots.getLast().sequenceNo();
        int recoveryStart = slots.size();
        while (recoveryStart > 0 && slots.get(recoveryStart - 1).recoveryEligible()) {
            recoveryStart--;
        }
        for (int index = 0; index < recoveryStart; index++) {
            if (slots.get(index).recoveryEligible()) {
                throw conflict("a recoverable event is followed by a non-recoverable event");
            }
        }
        Optional<EventSlotState> recoverySlot = recoveryStart < slots.size()
                ? Optional.of(slots.get(recoveryStart))
                : Optional.empty();
        return new EventSequenceState(maximumSequence, recoverySlot);
    }

    private int createEventSlotAuthority(IntakeEventReference reference) {
        return jdbc.update(
                """
                insert into case_intake_event_slot_authority (
                    thread_registration_id, logical_sequence, current_binding_id,
                    current_generation, authority_version, created_at, updated_at
                ) values (
                    :threadRegistrationId, :eventSequence, :bindingId,
                    1, 0, :createdAt, :createdAt
                )
                on conflict do nothing
                """,
                eventParameters(reference));
    }

    private int advanceEventSlotAuthority(
            IntakeEventReference reference,
            long generation,
            String supersedesBindingId) {
        return jdbc.update(
                """
                update case_intake_event_slot_authority
                   set current_binding_id = :bindingId,
                       current_generation = :bindingGeneration,
                       authority_version = authority_version + 1,
                       updated_at = :createdAt
                 where thread_registration_id = :threadRegistrationId
                   and logical_sequence = :eventSequence
                   and current_binding_id = :supersedesBindingId
                   and current_generation = :previousGeneration
                """,
                eventParameters(reference)
                        .addValue("bindingGeneration", generation)
                        .addValue("previousGeneration", generation - 1L)
                        .addValue("supersedesBindingId", supersedesBindingId));
    }

    private static long number(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (!(value instanceof Number number)) {
            throw conflict("event slot authority field is not numeric: " + name);
        }
        return number.longValue();
    }

    private static String text(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw conflict("event slot authority field is not text: " + name);
        }
        return text;
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
                .addValue("requiredAccessScopes", REQUIRED_ACCESS_SCOPES_JSON)
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
                .addValue("projectionRevision", reference.projectionRevision())
                .addValue("initialLastSequence", reference.initialLastSequence());
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
                .addValue("occurredAt", atOffset(reference.occurredAt()))
                .addValue(
                        "eventSourceType",
                        reference.sourceType() == null ? null : reference.sourceType().name());
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
                row.getLong("initial_last_sequence"),
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
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                eventSourceType(row));
    }

    private static SourceType eventSourceType(ResultSet row) throws SQLException {
        String value = row.getString("event_source_type");
        return value == null ? null : SourceType.valueOf(value);
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

    private record EventSlotState(
            long sequenceNo,
            String bindingId,
            long generation,
            boolean recoveryEligible) {}

    private record EventSequenceState(
            long maximumSequence,
            Optional<EventSlotState> recoverySlot) {}

    private static IntakeGraphBindingConflictException conflict(String detail) {
        return new IntakeGraphBindingConflictException("Intake Graph binding conflict: " + detail);
    }
}
