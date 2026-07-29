package com.example.dispute.room.infrastructure.persistence;

import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger;
import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.MatrixAuthority;
import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.MergeResult;
import com.example.dispute.workflow.application.intake.IntakeFinalizationPersistenceException;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt.CommitFacts;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceiptCodec;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceiptReader;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeFormalCommitPort;
import com.example.dispute.workflow.application.intake.IntakeGraphFinalizationRequest;
import com.example.dispute.workflow.application.intake.IntakeGraphResultFinalizer.AuthorityPreflight;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PostgreSQL-backed formal Intake writer.
 *
 * <p>The class intentionally has no Spring stereotype. Under the Phase 4 engineering gate it can
 * only be assembled explicitly by tests or by the future promoted TEMPORAL wiring. Its transaction
 * uses REQUIRED propagation, so an {@code AgentRunFormalResultCommitter} transaction also owns the
 * manifest and AgentRun terminal update that follow this domain commit.
 */
public final class JdbcIntakeFormalCommitPort
        implements IntakeFormalCommitPort, IntakeFinalizationReceiptReader, AuthorityPreflight {

    private static final String AGENT_ID = "dispute-intake-officer";
    private static final String AGENT_ROLE = "DISPUTE_INTAKE_OFFICER";
    private static final String MESSAGE_SENDER_ROLE = "CUSTOMER_SERVICE";
    private static final String REQUIRED_ACCESS_SCOPES =
            "[\"CASE_READ\",\"INTAKE_PRIVATE_READ\",\"INTAKE_PARTICIPATE\",\"AGENT_SESSION_WRITE\"]";
    private static final String ROLE_AUDIENCE =
            "[\"USER\",\"MERCHANT\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate preflightTransactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IntakeDossierProjectionMerger dossierMerger;

    public JdbcIntakeFormalCommitPort(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        PlatformTransactionManager requiredTransactionManager =
                Objects.requireNonNull(transactionManager, "transactionManager");
        this.transactions = new TransactionTemplate(requiredTransactionManager);
        this.preflightTransactions = new TransactionTemplate(requiredTransactionManager);
        this.preflightTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.preflightTransactions.setReadOnly(true);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dossierMerger = new IntakeDossierProjectionMerger();
    }

    @Override
    public IntakeFinalizationReceipt commit(CommitCommand command) {
        Objects.requireNonNull(command, "command");
        command.request().requireCanonicalRequestHash();
        try {
            IntakeFinalizationReceipt receipt =
                    transactions.execute(status -> commitInTransaction(command));
            return Objects.requireNonNull(receipt, "formal transaction returned no receipt");
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            if (failure instanceof TransientDataAccessException
                    || failure instanceof RecoverableDataAccessException
                    || failure instanceof DataAccessResourceFailureException) {
                throw new IntakeFinalizationPersistenceException(
                        "Intake formal transaction failed due to a retryable database condition",
                        failure);
            }
            throw rejected(
                    "INTAKE_FINALIZATION_PERSISTENCE_INVARIANT",
                    "Intake formal transaction violated a database invariant",
                    failure);
        } catch (TransactionException failure) {
            throw new IntakeFinalizationPersistenceException(
                    "Intake formal transaction failed before its commit outcome was known",
                    failure);
        }
    }

    @Override
    public void preflight(IntakeGraphFinalizationRequest request) {
        Objects.requireNonNull(request, "request");
        request.requireCanonicalRequestHash();
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                performPreflight(request);
            } else {
                preflightTransactions.executeWithoutResult(ignored -> performPreflight(request));
            }
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            if (failure instanceof TransientDataAccessException
                    || failure instanceof RecoverableDataAccessException
                    || failure instanceof DataAccessResourceFailureException) {
                throw new IntakeFinalizationPersistenceException(
                        "Intake authority preflight failed due to a retryable database condition",
                        failure);
            }
            throw rejected(
                    "INTAKE_FINALIZATION_PERSISTENCE_INVARIANT",
                    "Intake authority preflight violated a database invariant",
                    failure);
        } catch (TransactionException failure) {
            throw new IntakeFinalizationPersistenceException(
                    "Intake authority preflight transaction outcome was unknown", failure);
        }
    }

    private void performPreflight(IntakeGraphFinalizationRequest request) {
        CurrentRows current = requireCurrentAuthority(request, false);
        requirePersistedPrivateReferences(request, false);
        requireSoleResultReadyAttempt(request, current.roomId(), false);
    }

    @Override
    public Optional<IntakeFinalizationReceipt> findCommitted(
            String tenantSurrogate, String operationKey, String requestHash) {
        Objects.requireNonNull(tenantSurrogate, "tenantSurrogate");
        Objects.requireNonNull(operationKey, "operationKey");
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256");
        }
        try {
            List<OperationRow> rows = jdbc.query(
                    """
                    select id, request_hash, operation_status, result_uri, result_sha256,
                           completed_at, version
                      from domain_operation
                     where tenant_surrogate = :tenantSurrogate
                       and operation_key = :operationKey
                    """,
                    Map.of(
                            "tenantSurrogate", tenantSurrogate,
                            "operationKey", operationKey),
                    (row, ignored) -> new OperationRow(
                            row.getString("id"),
                            row.getString("request_hash"),
                            row.getString("operation_status"),
                            row.getString("result_uri"),
                            row.getString("result_sha256"),
                            row.getObject("completed_at", OffsetDateTime.class),
                            row.getLong("version")));
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            OperationRow operation = rows.getFirst();
            if (!operation.requestHash().equals(requestHash)) {
                throw rejected(
                        "INTAKE_FINALIZATION_OPERATION_CONFLICT",
                        "operation key is already bound to another canonical request");
            }
            if (!"COMPLETED".equals(operation.status())) {
                return Optional.empty();
            }
            IntakeFinalizationReceipt receipt =
                    readReceiptEvent(operation, tenantSurrogate, operationKey);
            return Optional.of(receipt);
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            if (failure instanceof TransientDataAccessException
                    || failure instanceof RecoverableDataAccessException
                    || failure instanceof DataAccessResourceFailureException) {
                throw new IntakeFinalizationPersistenceException(
                        "Intake receipt read failed due to a retryable database condition",
                        failure);
            }
            throw rejected(
                    "INTAKE_FINALIZATION_PERSISTENCE_INVARIANT",
                    "Intake receipt read violated a database invariant",
                    failure);
        }
    }

    private IntakeFinalizationReceipt commitInTransaction(CommitCommand command) {
        IntakeGraphFinalizationRequest request = command.request();
        OffsetDateTime now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS)
                .atOffset(ZoneOffset.UTC);
        lockOperationKey(request);
        int inserted = startOperation(request, now);
        OperationRow operation = lockOperation(request);
        if (!operation.requestHash().equals(request.requestHash())) {
            throw rejected(
                    "INTAKE_FINALIZATION_OPERATION_CONFLICT",
                    "operation key is already bound to another canonical request");
        }
        if ("COMPLETED".equals(operation.status())) {
            return readCompletedReceipt(operation, request);
        }
        if (inserted == 0 || !"STARTED".equals(operation.status())) {
            throw rejected(
                    "INTAKE_FINALIZATION_OPERATION_INCOMPLETE",
                    "operation ledger contains a non-replayable unfinished result");
        }

        CurrentRows current = requireCurrentAuthority(request, true);
        requirePersistedPrivateReferences(request, true);
        AgentRunRow run = requireSoleResultReadyAttempt(request, current.roomId(), true);

        IntakeTurnProposal proposal = command.loadedProposal().proposal();
        DossierWrite dossier = writeDossier(command, proposal, current, now);
        String messageId = deterministicId("MSGI_", request.operationKey(), "message");
        writeFormalMessage(command, current.roomId(), run, messageId, now);

        String eventId = deterministicId("EVTI_", request.operationKey(), "event");
        String outboxId = deterministicId("OBXI_", request.operationKey(), "outbox");
        String auditId = deterministicId("AUDI_", request.operationKey(), "audit");
        long eventSequence = nextEventSequence(request.authority().caseId());
        IntakeFinalizationReceipt receipt = IntakeFinalizationReceipt.committed(
                new CommitFacts(
                        request.operationKey(),
                        request.authority().tenantSurrogate(),
                        request.authority().caseId(),
                        request.authority().roomEpoch(),
                        request.authority().threadId(),
                        request.authority().actorScopeHash(),
                        request.authority().agentSessionId(),
                        request.authority().commandId(),
                        request.authority().logicalRunId(),
                        request.authority().attemptId(),
                        request.authority().resultHash(),
                        request.authority().proposalHash(),
                        request.authority().processRevision(),
                        request.authority().roomRevision(),
                        request.authority().fencingToken(),
                        messageId,
                        dossier.version(),
                        dossier.matrixVersion(),
                        List.of(eventId),
                        List.of(outboxId),
                        now.toInstant()));

        writeDomainEvent(command, current.roomId(), receipt, eventId, eventSequence, now);
        writeOutbox(command, receipt, eventId, eventSequence, outboxId, now);
        writeAudit(command, receipt, auditId, now);
        completeOperation(operation, receipt, eventId, now);
        return receipt;
    }

    private void lockOperationKey(IntakeGraphFinalizationRequest request) {
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                Map.of(
                        "lockKey",
                        request.authority().tenantSurrogate() + ':' + request.operationKey()),
                (RowCallbackHandler) ignored -> {});
    }

    private int startOperation(IntakeGraphFinalizationRequest request, OffsetDateTime now) {
        var authority = request.authority();
        return jdbc.update(
                """
                insert into domain_operation (
                    id, operation_key, tenant_surrogate, case_id, case_command_id,
                    operation_type, room_type, room_epoch, process_revision, fencing_token,
                    request_hash, operation_status, started_at, created_at, updated_at, version
                ) values (
                    :id, :operationKey, :tenantSurrogate, :caseId, null,
                    'INTAKE_TURN_FINALIZE', 'INTAKE', :roomEpoch, :processRevision, :fencingToken,
                    :requestHash, 'STARTED', :now, :now, :now, 0
                )
                on conflict (tenant_surrogate, operation_key) do nothing
                """,
                new MapSqlParameterSource()
                        .addValue("id", deterministicId("INOP_", request.operationKey(), "operation"))
                        .addValue("operationKey", request.operationKey())
                        .addValue("tenantSurrogate", authority.tenantSurrogate())
                        .addValue("caseId", authority.caseId())
                        .addValue("roomEpoch", authority.roomEpoch())
                        .addValue("processRevision", authority.processRevision())
                        .addValue("fencingToken", authority.fencingToken())
                        .addValue("requestHash", request.requestHash())
                        .addValue("now", now));
    }

    private OperationRow lockOperation(IntakeGraphFinalizationRequest request) {
        List<OperationRow> rows = jdbc.query(
                """
                select id, request_hash, operation_status, result_uri, result_sha256,
                       completed_at, version
                  from domain_operation
                 where tenant_surrogate = :tenantSurrogate
                   and operation_key = :operationKey
                 for update
                """,
                Map.of(
                        "tenantSurrogate", request.authority().tenantSurrogate(),
                        "operationKey", request.operationKey()),
                (row, ignored) -> new OperationRow(
                        row.getString("id"),
                        row.getString("request_hash"),
                        row.getString("operation_status"),
                        row.getString("result_uri"),
                        row.getString("result_sha256"),
                        row.getObject("completed_at", OffsetDateTime.class),
                        row.getLong("version")));
        if (rows.size() != 1) {
            throw rejected(
                    "INTAKE_FINALIZATION_LEDGER_MISSING",
                    "operation ledger row could not be locked");
        }
        return rows.getFirst();
    }

    private CurrentRows requireCurrentAuthority(
            IntakeGraphFinalizationRequest request, boolean lockRows) {
        MapSqlParameterSource parameters = authorityParameters(request);
        List<Map<String, Object>> cases = jdbc.queryForList(
                """
                select case_status, current_room, current_deadline_at,
                       initiator_id, initiator_role, respondent_id, respondent_role
                  from fulfillment_dispute_case
                 where id = :caseId
                """ + (lockRows ? " for update" : ""),
                parameters);
        Map<String, Object> caseRow = cases.size() == 1 ? cases.getFirst() : Map.of();
        var actor = request.threadBinding().registration().actorScope();
        boolean isInitiator = actor.actorId().equals(string(caseRow, "initiator_id"))
                && actor.actorRole().name().equals(string(caseRow, "initiator_role"));
        boolean isRespondent = actor.actorId().equals(string(caseRow, "respondent_id"))
                && actor.actorRole().name().equals(string(caseRow, "respondent_role"));
        if (cases.size() != 1
                || !"INTAKE".equals(string(caseRow, "current_room"))
                || caseRow.get("current_deadline_at") != null
                || (!isInitiator && !isRespondent)
                || !List.of(
                                "INTAKE_PENDING",
                                "INTAKE_IN_PROGRESS",
                                "WAITING_SLOT_COMPLETION",
                                "INTAKE_COMPLETED")
                        .contains(string(caseRow, "case_status"))) {
            throw rejected(
                    "INTAKE_CURRENT_STAGE_REJECTED",
                    "case is no longer in a formalizable Intake state");
        }
        List<CurrentRows> rows = jdbc.query(
                """
                select epoch.room_id
                  from case_room_epoch epoch
                  join case_room room
                    on room.id = epoch.room_id
                   and room.case_id = epoch.case_id
                   and room.room_type = epoch.room_type
                  join case_process_projection projection
                    on projection.case_id = epoch.case_id
                 where epoch.tenant_surrogate = :tenantSurrogate
                   and epoch.case_id = :caseId
                   and epoch.room_type = 'INTAKE'
                   and epoch.room_epoch = :roomEpoch
                   and epoch.fencing_token = :fencingToken
                   and epoch.process_revision = :processRevision
                   and epoch.room_revision = :roomRevision
                   and epoch.writer_mode = 'TEMPORAL'
                   and epoch.lifecycle_status = 'ACTIVE'
                   and epoch.selection_schema_version = 'room-epoch-selection.v2'
                   and room.room_status in ('OPEN', 'WAITING')
                   and projection.current_room = 'INTAKE'
                   and projection.writer_mode = 'TEMPORAL'
                   and projection.room_epoch = :roomEpoch
                   and projection.fencing_token = :fencingToken
                   and projection.process_revision = :processRevision
                   and projection.room_phase = :stageCode
                   and projection.last_command_sequence = :stageSequence
                 """ + (lockRows ? " for update of epoch, room, projection" : ""),
                parameters,
                (row, ignored) -> new CurrentRows(row.getString("room_id"), null, null));
        if (rows.size() != 1) {
            throw rejected(
                    "INTAKE_STALE_AUTHORITY",
                    "current Intake epoch, stage, revision, or fence no longer matches");
        }

        List<String> privateAuthority = jdbc.queryForList(
                """
                select binding.registration_id
                  from case_intake_graph_thread_binding binding
                  join agent_conversation_session session
                    on session.id = binding.agent_session_id
                  join case_access_session access
                    on access.id = session.access_session_id
                  join case_participant participant
                    on participant.case_id = binding.case_id
                   and participant.actor_id = binding.actor_id
                   and participant.participant_role = binding.actor_role
                  join fulfillment_dispute_case dispute
                    on dispute.id = binding.case_id
                 where binding.registration_id = :registrationId
                   and binding.registration_status = 'REGISTERED'
                   and binding.tenant_surrogate = :tenantSurrogate
                   and binding.case_id = :caseId
                   and binding.room_type = 'INTAKE'
                   and binding.room_epoch = :roomEpoch
                   and binding.fencing_token = :fencingToken
                   and binding.thread_id = :threadId
                   and binding.actor_id = :actorId
                   and binding.actor_role = :actorRole
                   and binding.audience = :audience
                   and binding.actor_scope_hash = :actorScopeHash
                   and binding.agent_session_id = :agentSessionId
                   and binding.graph_key = :graphKey
                   and binding.graph_version = :graphVersion
                   and binding.checkpoint_schema_version = :checkpointSchemaVersion
                   and binding.prompt_version = :promptVersion
                   and binding.model_profile_id = :modelProfileId
                   and binding.output_schema_version = :outputSchemaVersion
                   and binding.policy_version = :policyVersion
                   and binding.guardrail_version = :guardrailVersion
                   and binding.tool_policy_version = :toolPolicyVersion
                   and binding.writer_mode = 'TEMPORAL'
                   and binding.registration_hash = :registrationHash
                   and participant.participant_status = 'ACTIVE'
                   and session.tenant_id = :tenantSurrogate
                   and session.case_id = :caseId
                   and session.room_type = 'INTAKE'
                   and session.actor_id = :actorId
                   and session.actor_role = :actorRole
                   and session.agent_key = :agentRole
                   and session.prompt_profile_id = :promptVersion
                   and session.status = 'ACTIVE'
                   and access.tenant_id = :tenantSurrogate
                   and access.case_id = :caseId
                   and access.actor_id = :actorId
                   and access.actor_role = :actorRole
                   and access.status = 'ACTIVE'
                   and access.permission_level = case
                       when :actorRole = 'USER' then 'PARTY_USER'
                       when :actorRole = 'MERCHANT' then 'PARTY_MERCHANT'
                       else '__DENY__'
                   end
                    and access.permission_scopes_json @> cast(:requiredScopes as jsonb)
                    and (
                        (
                            binding.actor_id = dispute.initiator_id
                            and binding.actor_role = dispute.initiator_role
                        )
                        or (
                            binding.actor_id = dispute.respondent_id
                            and binding.actor_role = dispute.respondent_role
                            and dispute.case_status = 'INTAKE_COMPLETED'
                            and exists (
                                select 1
                                  from case_intake_party_completion initiator_completion
                                 where initiator_completion.case_id = dispute.id
                                   and initiator_completion.participant_id = dispute.initiator_id
                                   and initiator_completion.participant_role = dispute.initiator_role
                                   and initiator_completion.completion_status = 'COMPLETED'
                            )
                        )
                    )
                    and not exists (
                        select 1
                          from case_intake_party_completion actor_completion
                         where actor_completion.case_id = dispute.id
                           and actor_completion.participant_id = binding.actor_id
                           and actor_completion.participant_role = binding.actor_role
                    )
                 """ + (lockRows
                        ? " for update of binding, session, access, participant"
                        : ""),
                parameters,
                String.class);
        if (privateAuthority.size() != 1) {
            throw rejected(
                    "INTAKE_AUTHORIZATION_REVOKED",
                    "private thread, participation, access, or Agent Session is no longer active");
        }
        CurrentRows current = rows.getFirst();
        return new CurrentRows(
                current.roomId(),
                string(caseRow, "initiator_role"),
                string(caseRow, "respondent_role"));
    }

    private void requirePersistedPrivateReferences(
            IntakeGraphFinalizationRequest request, boolean lockRows) {
        var snapshot = request.initialSnapshot();
        MapSqlParameterSource parameters = authorityParameters(request)
                .addValue("snapshotBindingId", snapshot.bindingId())
                .addValue("snapshotRegistrationId", snapshot.threadRegistrationId())
                .addValue("snapshotArtifactId", snapshot.payloadRef().artifactId())
                .addValue("snapshotSchema", snapshot.payloadRef().schemaVersion())
                .addValue("snapshotUri", snapshot.payloadRef().uri())
                .addValue("snapshotHash", snapshot.payloadRef().sha256())
                .addValue("snapshotSize", snapshot.payloadRef().sizeBytes())
                .addValue("snapshotObjectVersion", snapshot.objectVersion())
                .addValue("snapshotDomainRevision", snapshot.domainRevision())
                .addValue("snapshotRoomRevision", snapshot.roomRevision())
                .addValue("snapshotProjectionRevision", snapshot.projectionRevision())
                .addValue("snapshotInitialLastSequence", snapshot.initialLastSequence())
                .addValue(
                        "snapshotCreatedAt",
                        snapshot.createdAt().atOffset(ZoneOffset.UTC));
        List<String> initial = jdbc.queryForList(
                """
                select binding_id
                  from case_intake_snapshot_binding
                 where binding_id = :snapshotBindingId
                    and thread_registration_id = :snapshotRegistrationId
                    and tenant_surrogate = :tenantSurrogate
                    and case_id = :caseId
                    and room_type = 'INTAKE'
                    and room_epoch = :roomEpoch
                    and fencing_token = :fencingToken
                    and thread_id = :threadId
                    and actor_scope_hash = :actorScopeHash
                    and agent_session_id = :agentSessionId
                    and actor_audience = :audience
                    and binding_type = 'INITIAL'
                    and initialization_marker
                    and schema_version = :snapshotSchema
                    and artifact_id = :snapshotArtifactId
                    and object_uri = :snapshotUri
                    and object_version = :snapshotObjectVersion
                    and content_sha256 = :snapshotHash
                    and size_bytes = :snapshotSize
                    and visibility = 'PRIVATE'
                    and domain_revision = :snapshotDomainRevision
                    and room_revision = :snapshotRoomRevision
                    and projection_revision = :snapshotProjectionRevision
                    and initial_last_sequence = :snapshotInitialLastSequence
                    and created_at = :snapshotCreatedAt
                """ + (lockRows ? " for share" : ""),
                parameters,
                String.class);
        if (initial.size() != 1) {
            throw rejected(
                    "INTAKE_SNAPSHOT_BINDING_STALE",
                    "initial snapshot is not the current private thread binding");
        }
        if (request.event() == null) {
            return;
        }
        var event = request.event();
        parameters.addValue("eventBindingId", event.bindingId())
                .addValue("eventRegistrationId", event.threadRegistrationId())
                .addValue("eventId", event.eventId())
                .addValue("eventMessageId", event.messageId())
                .addValue("eventArtifactId", event.payloadRef().artifactId())
                .addValue("eventSchema", event.payloadRef().schemaVersion())
                .addValue("eventUri", event.payloadRef().uri())
                .addValue("eventHash", event.payloadRef().sha256())
                .addValue("eventSize", event.payloadRef().sizeBytes())
                .addValue("eventObjectVersion", event.objectVersion())
                .addValue("eventSequence", event.sequenceNo())
                .addValue("eventDomainRevision", event.domainRevision())
                .addValue("eventAudience", event.audience().name())
                .addValue("eventOccurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))
                .addValue("eventCreatedAt", event.createdAt().atOffset(ZoneOffset.UTC));
        List<String> persistedEvent = jdbc.queryForList(
                """
                select binding_id
                  from case_intake_snapshot_binding
                 where binding_id = :eventBindingId
                    and thread_registration_id = :eventRegistrationId
                    and tenant_surrogate = :tenantSurrogate
                    and case_id = :caseId
                    and room_type = 'INTAKE'
                    and room_epoch = :roomEpoch
                    and fencing_token = :fencingToken
                    and thread_id = :threadId
                    and actor_scope_hash = :actorScopeHash
                    and agent_session_id = :agentSessionId
                    and actor_audience = :audience
                    and binding_type = 'EVENT'
                    and not initialization_marker
                    and event_id = :eventId
                    and message_id = :eventMessageId
                    and schema_version = :eventSchema
                    and artifact_id = :eventArtifactId
                    and object_uri = :eventUri
                    and object_version = :eventObjectVersion
                    and content_sha256 = :eventHash
                    and size_bytes = :eventSize
                    and visibility = 'PRIVATE'
                    and event_sequence = :eventSequence
                    and domain_revision = :eventDomainRevision
                    and audience = :eventAudience
                    and occurred_at = :eventOccurredAt
                    and created_at = :eventCreatedAt
                """ + (lockRows ? " for share" : ""),
                parameters,
                String.class);
        if (persistedEvent.size() != 1) {
            throw rejected(
                    "INTAKE_EVENT_BINDING_STALE",
                    "turn event is not the current ordered private reference");
        }
    }

    private AgentRunRow requireSoleResultReadyAttempt(
            IntakeGraphFinalizationRequest request, String roomId, boolean lockRows) {
        var authority = request.authority();
        MapSqlParameterSource parameters = authorityParameters(request).addValue("roomId", roomId);
        List<AgentRunRow> rows = jdbc.query(
                """
                select attempt.last_sequence_no
                  from agent_run run
                  join agent_run_attempt attempt
                    on attempt.agent_run_id = run.id
                 where run.id = :logicalRunId
                   and run.tenant_surrogate = :tenantSurrogate
                   and run.case_id = :caseId
                   and run.room_id = :roomId
                   and run.protocol = 'agent-stream.v2'
                   and run.executor_kind = 'TEMPORAL_ACTIVITY'
                   and run.room_type = 'INTAKE'
                   and run.room_epoch = :roomEpoch
                   and run.process_revision = :processRevision
                   and run.fencing_token = :fencingToken
                   and run.run_status = 'RESULT_READY'
                   and run.finalization_status = 'UNCOMMITTED'
                   and run.result_ready_attempt_id = :attemptId
                   and run.final_result_hash = :resultHash
                   and attempt.id = :attemptId
                   and attempt.attempt_status = 'RESULT_READY'
                   and attempt.executor_kind = 'TEMPORAL_ACTIVITY'
                   and attempt.command_id = :commandId
                   and attempt.command_request_hash = :commandRequestHash
                   and attempt.result_hash = :resultHash
                   and attempt.graph_key = :graphKey
                   and attempt.graph_version = :graphVersion
                   and attempt.checkpoint_schema_version = :checkpointSchemaVersion
                   and attempt.checkpoint_id = :checkpointId
                   and attempt.prompt_version = :promptVersion
                   and attempt.model_profile_id = :modelProfileId
                   and attempt.output_schema_version = :outputSchemaVersion
                   and attempt.policy_version = :policyVersion
                   and attempt.guardrail_version = :guardrailVersion
                   and attempt.final_frame_observed
                   and not exists (
                       select 1
                         from agent_execution_manifest manifest
                        where manifest.tenant_surrogate = :tenantSurrogate
                          and manifest.case_id = :caseId
                          and manifest.logical_agent_run_id = :logicalRunId
                   )
                 """ + (lockRows ? " for update of run, attempt" : ""),
                parameters,
                (row, ignored) -> new AgentRunRow(row.getLong("last_sequence_no")));
        if (rows.size() != 1) {
            throw rejected(
                    "INTAKE_AGENT_RUN_NOT_ELIGIBLE",
                    "AgentRun is not result-ready under the current formal authority");
        }
        Integer eligible = jdbc.queryForObject(
                """
                select count(*)
                  from agent_run_attempt
                 where agent_run_id = :logicalRunId
                   and attempt_status = 'RESULT_READY'
                """,
                parameters,
                Integer.class);
        if (eligible == null || eligible != 1) {
            throw rejected(
                    "INTAKE_AGENT_RUN_NOT_SOLE_ELIGIBLE_ATTEMPT",
                    "logical AgentRun has more than one formalizable attempt");
        }
        if (!authority.attemptId().equals(request.result().attemptId())) {
            throw rejected(
                    "INTAKE_AGENT_RUN_ATTEMPT_MISMATCH",
                    "result attempt does not match the locked eligible attempt");
        }
        return rows.getFirst();
    }

    private DossierWrite writeDossier(
            CommitCommand command,
            IntakeTurnProposal proposal,
            CurrentRows currentAuthority,
            OffsetDateTime now) {
        var authority = command.request().authority();
        List<DossierRow> rows = jdbc.query(
                """
                select id, dossier_version, dossier_json::text as dossier_json
                  from case_intake_dossier
                 where case_id = :caseId
                   and room_type = 'INTAKE'
                 for update
                """,
                Map.of("caseId", authority.caseId()),
                (row, ignored) -> new DossierRow(
                        row.getString("id"),
                        row.getLong("dossier_version"),
                        row.getString("dossier_json")));
        if (rows.size() > 1) {
            throw rejected(
                    "INTAKE_DOSSIER_CONFLICT", "more than one current Intake dossier exists");
        }
        DossierRow current = rows.isEmpty()
                ? new DossierRow(
                        deterministicId("INTD_", command.request().operationKey(), "dossier"),
                        0,
                        "{}")
                : rows.getFirst();
        JsonNode currentJson = readJson(current.json(), "persisted Intake dossier");
        IntakeGraphFinalizationRequest request = command.request();
        String sourceRef = request.event() == null
                ? request.initialSnapshot().payloadRef().artifactId()
                : request.event().messageId();
        String sourceContextHash = request.event() == null
                ? request.initialSnapshot().payloadRef().sha256()
                : request.event().payloadRef().sha256();
        MergeResult merged = dossierMerger.merge(
                currentJson,
                proposal,
                new MatrixAuthority(
                        authority.caseId(),
                        request.threadBinding().registration().actorScope().actorRole(),
                        ActorRole.valueOf(currentAuthority.initiatorRole()),
                        ActorRole.valueOf(currentAuthority.respondentRole()),
                        sourceRef,
                        sourceContextHash));
        long version = current.version() + 1;
        int sourceTurn = sourceTurn(command.request());
        MapSqlParameterSource parameters = authorityParameters(command.request())
                .addValue("dossierId", current.id())
                .addValue("dossierVersion", version)
                .addValue("dossierJson", merged.canonicalDossierJson())
                .addValue("qualityScore", merged.qualityScore())
                .addValue("ready", merged.readyForNextStep())
                .addValue("recommendation", merged.recommendation())
                .addValue("sourceTurn", sourceTurn)
                .addValue("now", now)
                .addValue("agentId", AGENT_ID);
        if (rows.isEmpty()) {
            jdbc.update(
                    """
                    insert into case_intake_dossier (
                        id, case_id, room_type, dossier_version, dossier_json,
                        quality_score, ready_for_next_step, admission_recommendation,
                        source_turn_no, source_agent_session_id, source_actor_id,
                        source_actor_role, created_at, updated_at, created_by, updated_by
                    ) values (
                        :dossierId, :caseId, 'INTAKE', :dossierVersion,
                        cast(:dossierJson as jsonb), :qualityScore, :ready, :recommendation,
                        :sourceTurn, :agentSessionId, :actorId, :actorRole,
                        :now, :now, :agentId, :agentId
                    )
                    """,
                    parameters);
        } else {
            int changed = jdbc.update(
                    """
                    update case_intake_dossier
                       set dossier_version = :dossierVersion,
                           dossier_json = cast(:dossierJson as jsonb),
                           quality_score = :qualityScore,
                           ready_for_next_step = :ready,
                           admission_recommendation = :recommendation,
                           source_turn_no = :sourceTurn,
                           source_agent_session_id = :agentSessionId,
                           source_actor_id = :actorId,
                           source_actor_role = :actorRole,
                           updated_at = :now,
                           updated_by = :agentId
                     where id = :dossierId
                       and dossier_version = :previousVersion
                    """,
                    parameters.addValue("previousVersion", current.version()));
            if (changed != 1) {
                throw rejected(
                        "INTAKE_DOSSIER_STALE", "Intake dossier changed while it was locked");
            }
        }
        return new DossierWrite(version, merged.matrixVersion());
    }

    private void writeFormalMessage(
            CommitCommand command,
            String roomId,
            AgentRunRow run,
            String messageId,
            OffsetDateTime now) {
        var request = command.request();
        long sequence = Objects.requireNonNull(
                jdbc.queryForObject(
                        """
                        select coalesce(max(sequence_no), 0) + 1
                          from room_message
                         where room_id = :roomId
                        """,
                        Map.of("roomId", roomId),
                        Long.class),
                "next message sequence");
        String actorIds = json(List.of(command.currentAuthority().actorId()));
        int inserted = jdbc.update(
                """
                insert into room_message (
                    id, case_id, room_id, sequence_no, sender_type, sender_role, sender_id,
                    audience_json, audience_actor_ids_json, message_type, message_source,
                    message_text, attachment_refs_json, agent_run_id, hearing_round,
                    idempotency_key, created_at, trace_id, created_by
                ) values (
                    :id, :caseId, :roomId, :sequenceNo, 'AGENT', :senderRole, :senderId,
                    cast(:audienceJson as jsonb), cast(:actorIds as jsonb), 'AGENT_MESSAGE',
                    'AGENT_LLM', :messageText, '[]'::jsonb, :agentRunId, null,
                    :idempotencyKey, :now, :traceId, :senderId
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", messageId)
                        .addValue("caseId", request.authority().caseId())
                        .addValue("roomId", roomId)
                        .addValue("sequenceNo", sequence)
                        .addValue("senderRole", MESSAGE_SENDER_ROLE)
                        .addValue("senderId", AGENT_ID)
                        .addValue("audienceJson", ROLE_AUDIENCE)
                        .addValue("actorIds", actorIds)
                        .addValue("messageText", command.loadedProposal().proposal().roomUtterance())
                        .addValue("agentRunId", request.authority().logicalRunId())
                        .addValue("idempotencyKey", correlationKey(request.operationKey()))
                        .addValue("now", now)
                        .addValue("traceId", request.command().traceparent()));
        if (inserted != 1 || run.lastSequenceNo() < 0) {
            throw rejected(
                    "INTAKE_FORMAL_MESSAGE_WRITE_FAILED", "formal Intake message was not written");
        }
    }

    private long nextEventSequence(String caseId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        """
                        select coalesce(max(sequence_no), 0) + 1
                          from case_timeline_event
                         where case_id = :caseId
                        """,
                        Map.of("caseId", caseId),
                        Long.class),
                "next event sequence");
    }

    private void writeDomainEvent(
            CommitCommand command,
            String roomId,
            IntakeFinalizationReceipt receipt,
            String eventId,
            long sequence,
            OffsetDateTime now) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("schema_version", "intake-turn-committed-event.v1");
        event.put("event_type", turnEventType(command.loadedProposal().proposal()));
        event.put("operation_key", receipt.operationKey());
        event.put("request_hash", command.request().requestHash());
        event.put("result_hash", receipt.resultHash());
        event.put("proposal_hash", receipt.proposalHash());
        event.put("message_id", receipt.formalMessageId());
        event.put("actor_scope_hash", receipt.actorScopeHash());
        event.set("receipt", IntakeFinalizationReceiptCodec.toTree(receipt));
        List<String> sourceRefIds = command.request().event() == null
                ? List.of(
                        command.request().initialSnapshot().payloadRef().artifactId(),
                        command.request().proposalReference().artifactId())
                : List.of(
                        command.request().initialSnapshot().payloadRef().artifactId(),
                        command.request().event().payloadRef().artifactId(),
                        command.request().proposalReference().artifactId());
        String sourceRefs = json(sourceRefIds);
        jdbc.update(
                """
                insert into case_timeline_event (
                    id, case_id, dossier_id, sequence_no, room_id, event_type, event_time,
                    source_refs_json, event_json, audience_json, audience_actor_ids_json,
                    event_key, created_at, created_by
                ) values (
                    :id, :caseId, null, :sequenceNo, :roomId, :eventType, :now,
                    cast(:sourceRefs as jsonb), cast(:eventJson as jsonb),
                    cast(:audienceJson as jsonb), cast(:actorIds as jsonb),
                    :eventKey, :now, :createdBy
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", eventId)
                        .addValue("caseId", receipt.caseId())
                        .addValue("sequenceNo", sequence)
                        .addValue("roomId", roomId)
                        .addValue("eventType", turnEventType(command.loadedProposal().proposal()))
                        .addValue("now", now)
                        .addValue("sourceRefs", sourceRefs)
                        .addValue("eventJson", ContractJson.canonicalString(event))
                        .addValue("audienceJson", ROLE_AUDIENCE)
                        .addValue("actorIds", json(List.of(command.currentAuthority().actorId())))
                        .addValue("eventKey", correlationKey(command.request().operationKey()))
                        .addValue("createdBy", AGENT_ID));
    }

    private void writeOutbox(
            CommitCommand command,
            IntakeFinalizationReceipt receipt,
            String eventId,
            long eventSequence,
            String outboxId,
            OffsetDateTime now) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", "intake-turn-outbox.v1");
        payload.put("event_id", eventId);
        payload.put("event_sequence", eventSequence);
        payload.put("tenant_surrogate", receipt.tenantSurrogate());
        payload.put("case_id", receipt.caseId());
        payload.put("room_epoch", receipt.roomEpoch());
        payload.put("fencing_token", receipt.fencingToken());
        payload.put("operation_key", receipt.operationKey());
        payload.put("request_hash", command.request().requestHash());
        payload.put("result_hash", receipt.resultHash());
        payload.put("receipt_hash", receipt.receiptHash());
        jdbc.update(
                """
                insert into notification_outbox (
                    id, case_id, business_event_key, event_type, event_payload_json,
                    outbox_status, attempt_count, available_at, created_at, updated_at
                ) values (
                    :id, :caseId, :eventKey, :eventType, cast(:payload as jsonb),
                    'PENDING', 0, :now, :now, :now
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", outboxId)
                        .addValue("caseId", receipt.caseId())
                        .addValue("eventKey", correlationKey(command.request().operationKey()))
                        .addValue("eventType", turnEventType(command.loadedProposal().proposal()))
                        .addValue("payload", ContractJson.canonicalString(payload))
                        .addValue("now", now));
    }

    private void writeAudit(
            CommitCommand command,
            IntakeFinalizationReceipt receipt,
            String auditId,
            OffsetDateTime now) {
        ObjectNode after = objectMapper.createObjectNode();
        after.put("case_id", receipt.caseId());
        after.put("operation_key", receipt.operationKey());
        after.put("request_hash", command.request().requestHash());
        after.put("result_hash", receipt.resultHash());
        after.put("proposal_hash", receipt.proposalHash());
        after.put("receipt_hash", receipt.receiptHash());
        after.put("formal_message_id", receipt.formalMessageId());
        after.put("dossier_version", receipt.dossierVersion());
        if (receipt.matrixVersion() != null) {
            after.put("matrix_version", receipt.matrixVersion());
        }
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("tenant_surrogate", receipt.tenantSurrogate());
        metadata.put("domain_case_id", receipt.caseId());
        metadata.put("room_epoch", receipt.roomEpoch());
        metadata.put("fencing_token", receipt.fencingToken());
        jdbc.update(
                """
                insert into audit_log (
                    id, case_id, trace_id, request_id, workflow_id, user_id, role,
                    service, action, resource_type, resource_id, outcome,
                    before_json, after_json, metadata_json, source_ip, created_at, created_by
                ) values (
                    :id, :caseId, :traceId, :requestId, :workflowId, :userId, 'SYSTEM',
                    'java-api-service', 'INTAKE_TURN_FINALIZED', 'FULFILLMENT_DISPUTE_CASE',
                    :resourceId, 'SUCCESS', '{}'::jsonb, cast(:afterJson as jsonb),
                    cast(:metadataJson as jsonb), null, :now, :userId
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", auditId)
                        .addValue("caseId", receipt.caseId())
                        .addValue("traceId", command.request().command().traceparent())
                        .addValue("requestId", command.request().authority().commandId())
                        .addValue("workflowId", "agent-run:" + sha256(receipt.logicalRunId()))
                        .addValue("userId", AGENT_ID)
                        .addValue("resourceId", receipt.caseId())
                        .addValue("afterJson", ContractJson.canonicalString(after))
                        .addValue("metadataJson", ContractJson.canonicalString(metadata))
                        .addValue("now", now));
    }

    private void completeOperation(
            OperationRow operation,
            IntakeFinalizationReceipt receipt,
            String eventId,
            OffsetDateTime now) {
        String resultUri = "urn:intake:finalization-receipt:" + eventId;
        int changed = jdbc.update(
                """
                update domain_operation
                   set operation_status = 'COMPLETED',
                       result_uri = :resultUri,
                       result_sha256 = :receiptHash,
                       failure_code = null,
                       failure_detail = null,
                       completed_at = :now,
                       updated_at = :now,
                       version = version + 1
                 where id = :id
                   and operation_status = 'STARTED'
                   and version = :version
                """,
                new MapSqlParameterSource()
                        .addValue("resultUri", resultUri)
                        .addValue("receiptHash", receipt.receiptHash())
                        .addValue("now", now)
                        .addValue("id", operation.id())
                        .addValue("version", operation.version()));
        if (changed != 1) {
            throw rejected(
                    "INTAKE_FINALIZATION_LEDGER_STALE",
                    "operation ledger changed before receipt completion");
        }
    }

    private IntakeFinalizationReceipt readCompletedReceipt(
            OperationRow operation, IntakeGraphFinalizationRequest request) {
        IntakeFinalizationReceipt receipt = readReceiptEvent(
                operation,
                request.authority().tenantSurrogate(),
                request.operationKey());
        if (!request.authority().caseId().equals(receipt.caseId())
                || !request.authority().resultHash().equals(receipt.resultHash())
                || !request.authority().proposalHash().equals(receipt.proposalHash())) {
            throw rejected(
                    "INTAKE_FINALIZATION_RECEIPT_CONFLICT",
                    "persisted receipt conflicts with the replayed request");
        }
        return receipt;
    }

    private IntakeFinalizationReceipt readReceiptEvent(
            OperationRow operation, String tenantSurrogate, String operationKey) {
        if (operation.resultUri() == null
                || operation.resultHash() == null
                || operation.completedAt() == null
                || !operation.resultUri().startsWith("urn:intake:finalization-receipt:")) {
            throw rejected(
                    "INTAKE_FINALIZATION_RECEIPT_MISSING",
                    "completed operation has no valid persisted receipt reference");
        }
        String eventId = operation.resultUri()
                .substring("urn:intake:finalization-receipt:".length());
        List<String> events = jdbc.queryForList(
                """
                select event_json::text
                  from case_timeline_event
                 where id = :eventId
                """,
                Map.of("eventId", eventId),
                String.class);
        if (events.size() != 1) {
            throw rejected(
                    "INTAKE_FINALIZATION_RECEIPT_MISSING",
                    "persisted finalization receipt event is missing");
        }
        try {
            JsonNode event = objectMapper.readTree(events.getFirst());
            IntakeFinalizationReceipt receipt =
                    objectMapper.treeToValue(event.required("receipt"), IntakeFinalizationReceipt.class);
            receipt.requireCanonicalHash();
            if (!operation.resultHash().equals(receipt.receiptHash())
                    || !operationKey.equals(receipt.operationKey())
                    || !tenantSurrogate.equals(receipt.tenantSurrogate())) {
                throw rejected(
                        "INTAKE_FINALIZATION_RECEIPT_CONFLICT",
                        "persisted receipt conflicts with the replayed request");
            }
            return receipt;
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw rejected(
                    "INTAKE_FINALIZATION_RECEIPT_INVALID",
                    "persisted finalization receipt cannot be decoded",
                    failure);
        }
    }

    private MapSqlParameterSource authorityParameters(IntakeGraphFinalizationRequest request) {
        var authority = request.authority();
        IntakePrivateThreadRegistration registration = request.threadBinding().registration();
        var actor = registration.actorScope();
        var profiles = authority.profileVersions();
        return new MapSqlParameterSource()
                .addValue("tenantSurrogate", authority.tenantSurrogate())
                .addValue("caseId", authority.caseId())
                .addValue("roomEpoch", authority.roomEpoch())
                .addValue("fencingToken", authority.fencingToken())
                .addValue("processRevision", authority.processRevision())
                .addValue("roomRevision", authority.roomRevision())
                .addValue("stageCode", authority.stageCode())
                .addValue("stageSequence", authority.stageSequence())
                .addValue("registrationId", registration.registrationId())
                .addValue("registrationHash", registration.registrationHash())
                .addValue("threadId", authority.threadId())
                .addValue("actorId", actor.actorId())
                .addValue("actorRole", actor.actorRole().name())
                .addValue("audience", actor.audience().name())
                .addValue("actorScopeHash", authority.actorScopeHash())
                .addValue("agentSessionId", authority.agentSessionId())
                .addValue("agentRole", AGENT_ROLE)
                .addValue("requiredScopes", REQUIRED_ACCESS_SCOPES)
                .addValue("logicalRunId", authority.logicalRunId())
                .addValue("attemptId", authority.attemptId())
                .addValue("commandId", authority.commandId())
                .addValue("commandRequestHash", request.command().requestHash())
                .addValue("resultHash", authority.resultHash())
                .addValue("checkpointId", authority.checkpointId())
                .addValue("graphKey", request.command().graphKey())
                .addValue("graphVersion", profiles.graphVersion())
                .addValue("checkpointSchemaVersion", profiles.checkpointSchemaVersion())
                .addValue("promptVersion", profiles.promptVersion())
                .addValue("modelProfileId", profiles.modelProfileId())
                .addValue("outputSchemaVersion", authority.executionOutputSchemaVersion())
                .addValue("policyVersion", profiles.policyVersion())
                .addValue("guardrailVersion", profiles.guardrailVersion())
                .addValue("toolPolicyVersion", profiles.toolPolicyVersion());
    }

    private JsonNode readJson(String value, String field) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException failure) {
            throw rejected("INTAKE_DOSSIER_CURRENT_INVALID", field + " is invalid JSON", failure);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Intake formal JSON encoding failed", failure);
        }
    }

    private static String string(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }

    private static int sourceTurn(IntakeGraphFinalizationRequest request) {
        long value = request.event() == null
                ? request.authority().cognitiveRevision()
                : request.event().sequenceNo();
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw rejected(
                    "INTAKE_SOURCE_TURN_INVALID", "source turn cannot be persisted as an integer");
        }
        return (int) value;
    }

    private static String turnEventType(IntakeTurnProposal proposal) {
        return proposal.readiness() == IntakeTurnProposal.Readiness.READY_TO_CONFIRM
                ? "TURN_READY_TO_CONFIRM"
                : "TURN_NEEDS_INPUT";
    }

    private static String correlationKey(String operationKey) {
        return "intake-final:" + sha256(operationKey);
    }

    private static String deterministicId(String prefix, String operationKey, String purpose) {
        return prefix + sha256(operationKey + ':' + purpose).substring(0, 64 - prefix.length());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    private static IntakeFinalizationRejectedException rejected(
            String code, String message, Throwable cause) {
        return new IntakeFinalizationRejectedException(code, message, cause);
    }

    private record OperationRow(
            String id,
            String requestHash,
            String status,
            String resultUri,
            String resultHash,
            OffsetDateTime completedAt,
            long version) {}

    private record CurrentRows(String roomId, String initiatorRole, String respondentRole) {}

    private record AgentRunRow(long lastSequenceNo) {}

    private record DossierRow(String id, long version, String json) {}

    private record DossierWrite(long version, Long matrixVersion) {}
}
