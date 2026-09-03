package com.example.dispute.workflow.infrastructure.persistence.authority.payload;

import com.example.dispute.workflow.application.authority.payload.IntakeAuthorityRoute;
import com.example.dispute.workflow.application.authority.payload.IntakeCommandAuthority;
import com.example.dispute.workflow.application.authority.payload.IntakeCommandOutboxBinding;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadAuthority;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadAuthorityStore;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadAuthorityStore.Acceptance;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadAuthorityStore.AcceptanceReceipt;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadPutReceipt;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic P4-R1.5 writer for a server-bound payload, its case-command authority, and outbox row.
 * The caller must already have persisted the immutable case_command row in the same transaction.
 */
@Repository
public class JdbcIntakePayloadAuthorityStore implements IntakePayloadAuthorityStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final EpochAuthorityLockCoordinator lockCoordinator;

    public JdbcIntakePayloadAuthorityStore(
            NamedParameterJdbcTemplate jdbc, EpochAuthorityLockCoordinator lockCoordinator) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.lockCoordinator = Objects.requireNonNull(lockCoordinator, "lockCoordinator must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AcceptanceReceipt accept(Acceptance request) {
        Objects.requireNonNull(request, "request must not be null");
        IntakePayloadAuthority payload = request.payload();
        IntakeCommandAuthority command = request.command();
        IntakeAuthorityRoute route = payload.route();
        var locked = lockCoordinator.lockForShare(new LockRequest(
                List.of(route.accessSessionId()),
                List.of(route.agentSessionId()),
                List.of(route.registrationId())));
        lockCoordinator.requireActive(locked);

        requireCaseCommandShape(command, payload);
        boolean payloadCreated = persistPayload(payload);
        boolean commandCreated = persistCommand(command);
        boolean outboxCreated = persistOutbox(request.outbox(), command);
        return new AcceptanceReceipt(
                payload.payloadAuthorityId(), command.caseCommandId(), request.outbox().outboxId(),
                !(payloadCreated || commandCreated || outboxCreated));
    }

    private void requireCaseCommandShape(
            IntakeCommandAuthority command, IntakePayloadAuthority payload) {
        Integer exactRows = jdbc.queryForObject(
                """
                select count(*)
                  from case_command
                 where id = :caseCommandId
                   and tenant_surrogate = :tenant
                   and case_id = :caseId
                   and command_id = :commandId
                   and case_command_sequence = :caseCommandSequence
                   and command_type = :commandType
                   and room_type = 'INTAKE'
                   and room_epoch = :roomEpoch
                   and actor_id = :actorId
                   and actor_role = :actorRole
                   and payload_schema_version = :payloadSchemaVersion
                   and payload_uri = :payloadUri
                   and payload_sha256 = :payloadSha256
                   and payload_size_bytes = :payloadSizeBytes
                   and request_hash = :requestHash
                """,
                commandParameters(command, payload),
                Integer.class);
        if (exactRows == null || exactRows != 1) {
            throw new IntakePayloadAuthorityConflictException(
                    "case_command is not the exact immutable authority payload reference");
        }
    }

    private boolean persistPayload(IntakePayloadAuthority payload) {
        MapSqlParameterSource params = payloadParameters(payload);
        int inserted = jdbc.update(
                """
                insert into case_intake_command_payload_authority (
                    payload_authority_id, command_id, epoch_id, party_authority_id,
                    access_session_id, registration_id, tenant_surrogate, case_id, room_type,
                    room_epoch, fencing_token, thread_id, actor_id, actor_role, actor_scope_hash,
                    agent_session_id, source_kind, existing_event_binding_id, artifact_id,
                    schema_version, object_uri, object_version, content_sha256, size_bytes,
                    put_receipt_schema_version, put_receipt_id, put_idempotency_key,
                    put_receipt_stored_at_epoch_micros, put_receipt_hash, created_at
                ) values (
                    :payloadAuthorityId, :commandId, :epochId, :partyAuthorityId,
                    :accessSessionId, :registrationId, :tenant, :caseId, 'INTAKE',
                    :roomEpoch, :fencingToken, :threadId, :actorId, :actorRole, :actorScopeHash,
                    :agentSessionId, :sourceKind, :existingEventBindingId, :artifactId,
                    :schemaVersion, :objectUri, :objectVersion, :contentSha256, :sizeBytes,
                    :putReceiptSchemaVersion, :putReceiptId, :putIdempotencyKey,
                    :putReceiptStoredAtEpochMicros, :putReceiptHash, :createdAt
                ) on conflict do nothing
                """,
                params);
        requireExactPayloadReplay(params);
        return inserted == 1;
    }

    private boolean persistCommand(IntakeCommandAuthority command) {
        MapSqlParameterSource params = commandParameters(command, null);
        int inserted = jdbc.update(
                """
                insert into case_intake_command_authority (
                    case_command_id, command_id, case_command_sequence, command_type, epoch_id,
                    party_authority_id, access_session_id, registration_id, tenant_surrogate,
                    case_id, room_type, room_epoch, fencing_token, thread_id, actor_id,
                    actor_role, actor_scope_hash, agent_session_id, payload_authority_id,
                    request_hash, accepted_room_revision, execution_disposition, created_at
                ) values (
                    :caseCommandId, :commandId, :caseCommandSequence, :commandType, :epochId,
                    :partyAuthorityId, :accessSessionId, :registrationId, :tenant, :caseId,
                    'INTAKE', :roomEpoch, :fencingToken, :threadId, :actorId, :actorRole,
                    :actorScopeHash, :agentSessionId, :payloadAuthorityId, :requestHash,
                    :acceptedRoomRevision, :executionDisposition, :createdAt
                ) on conflict do nothing
                """,
                params);
        requireExactCommandReplay(params);
        return inserted == 1;
    }

    private boolean persistOutbox(IntakeCommandOutboxBinding outbox, IntakeCommandAuthority command) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("outboxId", outbox.outboxId())
                .addValue("caseCommandId", command.caseCommandId())
                .addValue("tenant", command.route().tenantSurrogate())
                .addValue("caseId", command.route().caseId())
                .addValue("workflowId", outbox.caseWorkflowId())
                .addValue("workflowType", outbox.workflowType())
                .addValue("taskQueue", outbox.taskQueue())
                .addValue("updateId", outbox.updateId())
                .addValue("availableAt", outbox.availableAt());
        int inserted = jdbc.update(
                """
                insert into case_command_outbox (
                    id, case_command_id, tenant_surrogate, case_id, workflow_id, workflow_type,
                    task_queue, delivery_kind, update_id, outbox_status, available_at, attempt_count,
                    created_at, updated_at
                ) values (
                    :outboxId, :caseCommandId, :tenant, :caseId, :workflowId, :workflowType,
                    :taskQueue, 'UPDATE_WITH_START', :updateId, 'PENDING', :availableAt, 0,
                    :availableAt, :availableAt
                ) on conflict do nothing
                """,
                params);
        Integer exactRows = jdbc.queryForObject(
                """
                select count(*)
                  from case_command_outbox
                 where id = :outboxId
                   and case_command_id = :caseCommandId
                   and tenant_surrogate = :tenant
                   and case_id = :caseId
                   and workflow_id = :workflowId
                   and workflow_type = :workflowType
                   and task_queue = :taskQueue
                   and delivery_kind = 'UPDATE_WITH_START'
                   and update_id = :updateId
                """,
                params,
                Integer.class);
        if (exactRows == null || exactRows != 1) {
            throw new IntakePayloadAuthorityConflictException(
                    "command outbox retry does not match the immutable command authority tuple");
        }
        return inserted == 1;
    }

    private void requireExactPayloadReplay(MapSqlParameterSource params) {
        Integer exactRows = jdbc.queryForObject(
                """
                select count(*)
                  from case_intake_command_payload_authority
                 where payload_authority_id = :payloadAuthorityId
                   and command_id = :commandId
                   and epoch_id = :epochId
                   and party_authority_id = :partyAuthorityId
                   and access_session_id = :accessSessionId
                   and registration_id = :registrationId
                   and tenant_surrogate = :tenant
                   and case_id = :caseId
                   and room_type = 'INTAKE'
                   and room_epoch = :roomEpoch
                   and fencing_token = :fencingToken
                   and thread_id = :threadId
                   and actor_id = :actorId
                   and actor_role = :actorRole
                   and actor_scope_hash = :actorScopeHash
                   and agent_session_id = :agentSessionId
                   and source_kind = :sourceKind
                   and existing_event_binding_id is not distinct from :existingEventBindingId
                   and artifact_id = :artifactId
                   and schema_version = :schemaVersion
                   and object_uri = :objectUri
                   and object_version = :objectVersion
                   and content_sha256 = :contentSha256
                   and size_bytes = :sizeBytes
                   and put_receipt_schema_version is not distinct from :putReceiptSchemaVersion
                   and put_receipt_id is not distinct from :putReceiptId
                   and put_idempotency_key is not distinct from :putIdempotencyKey
                   and put_receipt_stored_at_epoch_micros is not distinct from :putReceiptStoredAtEpochMicros
                   and put_receipt_hash is not distinct from :putReceiptHash
                   and created_at = :createdAt
                """,
                params,
                Integer.class);
        if (exactRows == null || exactRows != 1) {
            throw new IntakePayloadAuthorityConflictException(
                    "payload authority retry does not match the immutable server-bound payload tuple");
        }
    }

    private void requireExactCommandReplay(MapSqlParameterSource params) {
        Integer exactRows = jdbc.queryForObject(
                """
                select count(*)
                  from case_intake_command_authority
                 where case_command_id = :caseCommandId
                   and command_id = :commandId
                   and case_command_sequence = :caseCommandSequence
                   and command_type = :commandType
                   and epoch_id = :epochId
                   and party_authority_id = :partyAuthorityId
                   and access_session_id = :accessSessionId
                   and registration_id = :registrationId
                   and tenant_surrogate = :tenant
                   and case_id = :caseId
                   and room_type = 'INTAKE'
                   and room_epoch = :roomEpoch
                   and fencing_token = :fencingToken
                   and thread_id = :threadId
                   and actor_id = :actorId
                   and actor_role = :actorRole
                   and actor_scope_hash = :actorScopeHash
                   and agent_session_id = :agentSessionId
                   and payload_authority_id = :payloadAuthorityId
                   and request_hash = :requestHash
                   and accepted_room_revision = :acceptedRoomRevision
                   and execution_disposition = :executionDisposition
                   and created_at = :createdAt
                """,
                params,
                Integer.class);
        if (exactRows == null || exactRows != 1) {
            throw new IntakePayloadAuthorityConflictException(
                    "command authority retry does not match the immutable command tuple");
        }
    }

    private static MapSqlParameterSource payloadParameters(IntakePayloadAuthority payload) {
        IntakeAuthorityRoute route = payload.route();
        IntakePayloadPutReceipt receipt = payload.putReceipt();
        return routeParameters(route)
                .addValue("payloadAuthorityId", payload.payloadAuthorityId())
                .addValue("commandId", payload.commandId())
                .addValue("sourceKind", payload.sourceKind().name())
                .addValue("existingEventBindingId", payload.existingEventBindingId())
                .addValue("artifactId", payload.artifactId())
                .addValue("schemaVersion", payload.schemaVersion())
                .addValue("objectUri", payload.objectUri())
                .addValue("objectVersion", payload.objectVersion())
                .addValue("contentSha256", payload.contentSha256())
                .addValue("sizeBytes", payload.sizeBytes())
                .addValue("putReceiptSchemaVersion", receipt == null ? null : receipt.schemaVersion())
                .addValue("putReceiptId", receipt == null ? null : receipt.receiptId())
                .addValue("putIdempotencyKey", receipt == null ? null : receipt.putIdempotencyKey())
                .addValue("putReceiptStoredAtEpochMicros", receipt == null ? null : receipt.storedAtEpochMicros())
                .addValue("putReceiptHash", receipt == null ? null : receipt.receiptHash())
                .addValue("createdAt", payload.createdAt());
    }

    private static MapSqlParameterSource commandParameters(
            IntakeCommandAuthority command, IntakePayloadAuthority payload) {
        IntakeAuthorityRoute route = command.route();
        MapSqlParameterSource parameters = routeParameters(route)
                .addValue("caseCommandId", command.caseCommandId())
                .addValue("commandId", command.commandId())
                .addValue("caseCommandSequence", command.caseCommandSequence())
                .addValue("commandType", command.commandType().name())
                .addValue("payloadAuthorityId", command.payloadAuthorityId())
                .addValue("requestHash", command.requestHash())
                .addValue("acceptedRoomRevision", command.acceptedRoomRevision())
                .addValue("executionDisposition", command.executionDisposition().name())
                .addValue("createdAt", command.createdAt());
        if (payload != null) {
            parameters.addValue("payloadSchemaVersion", payload.schemaVersion())
                    .addValue("payloadUri", payload.objectUri())
                    .addValue("payloadSha256", payload.contentSha256())
                    .addValue("payloadSizeBytes", payload.sizeBytes());
        }
        return parameters;
    }

    private static MapSqlParameterSource routeParameters(IntakeAuthorityRoute route) {
        return new MapSqlParameterSource()
                .addValue("partyAuthorityId", route.partyAuthorityId())
                .addValue("epochId", route.epochId())
                .addValue("accessSessionId", route.accessSessionId())
                .addValue("registrationId", route.registrationId())
                .addValue("tenant", route.tenantSurrogate())
                .addValue("caseId", route.caseId())
                .addValue("roomEpoch", route.roomEpoch())
                .addValue("fencingToken", route.fencingToken())
                .addValue("threadId", route.threadId())
                .addValue("actorId", route.actorId())
                .addValue("actorRole", route.actorRole().name())
                .addValue("actorScopeHash", route.actorScopeHash())
                .addValue("agentSessionId", route.agentSessionId());
    }
}
