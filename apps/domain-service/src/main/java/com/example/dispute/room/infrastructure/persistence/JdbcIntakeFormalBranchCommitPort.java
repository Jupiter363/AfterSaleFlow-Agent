package com.example.dispute.room.infrastructure.persistence;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.IntakeBranchDomainService;
import com.example.dispute.room.application.IntakeBranchDomainService.BranchResult;
import com.example.dispute.room.application.IntakeBranchDomainService.RespondentSubmitLineage;
import com.example.dispute.room.application.IntakeBranchDomainService.TimelineEventMode;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.workflow.application.intake.IntakeFinalizationPersistenceException;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger;
import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommandResolver;
import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommandResolver.ResolvedBranchCommand;
import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommitPort;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ContractTypes;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.FrozenIntakeSubmissionAuthority;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exact TEMPORAL authority and atomic operation ledger for Intake terminal branches.
 *
 * <p>This adapter has no Spring stereotype and is not registered in the Phase 4 worker. Tests may
 * assemble it explicitly. Every domain mutation, committed event and operation receipt uses one
 * caller-supplied transaction manager.
 */
public final class JdbcIntakeFormalBranchCommitPort implements IntakeFormalBranchCommitPort {

    private static final String EVENT_REF_PREFIX = "urn:after-sale-flow:intake-event:";
    private static final String DEFAULT_GRAPH_KEY = "intake.v2";
    private static final String TARGET_GRAPH_KEY = "all-rooms.production-runtime.v2";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final IntakeBranchDomainService domainService;
    private final IntakeFormalBranchCommandResolver commandResolver;
    private final RoomEpochAllocator roomEpochAllocator;
    private final String expectedGraphKey;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcIntakeFormalBranchCommitPort(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            IntakeBranchDomainService domainService,
            IntakeFormalBranchCommandResolver commandResolver,
            ObjectMapper objectMapper,
            Clock clock) {
        this(
                jdbc,
                transactionManager,
                caseRepository,
                roomRepository,
                domainService,
                commandResolver,
                null,
                DEFAULT_GRAPH_KEY,
                objectMapper,
                clock);
    }

    public JdbcIntakeFormalBranchCommitPort(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            IntakeBranchDomainService domainService,
            IntakeFormalBranchCommandResolver commandResolver,
            RoomEpochAllocator roomEpochAllocator,
            ObjectMapper objectMapper,
            Clock clock) {
        this(
                jdbc,
                transactionManager,
                caseRepository,
                roomRepository,
                domainService,
                commandResolver,
                roomEpochAllocator,
                DEFAULT_GRAPH_KEY,
                objectMapper,
                clock);
    }

    public JdbcIntakeFormalBranchCommitPort(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            IntakeBranchDomainService domainService,
            IntakeFormalBranchCommandResolver commandResolver,
            RoomEpochAllocator roomEpochAllocator,
            String expectedGraphKey,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.caseRepository = Objects.requireNonNull(caseRepository, "caseRepository");
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
        this.domainService = Objects.requireNonNull(domainService, "domainService");
        this.commandResolver = Objects.requireNonNull(commandResolver, "commandResolver");
        this.roomEpochAllocator = roomEpochAllocator;
        if (expectedGraphKey == null
                || !expectedGraphKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("expectedGraphKey is invalid");
        }
        this.expectedGraphKey = expectedGraphKey;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public BranchCommitReceipt commit(BranchCommitRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            if (request.envelope().invocation().mode()
                    == ActivityInvocationMode.RECONCILE_ONLY) {
                return reconcileOnly(request);
            }
            BranchCommitReceipt receipt = transactions.execute(status -> commitInTransaction(request));
            return Objects.requireNonNull(receipt, "branch transaction returned null");
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (IntakeFinalizationPersistenceException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            if (failure instanceof TransientDataAccessException
                    || failure instanceof RecoverableDataAccessException
                    || failure instanceof DataAccessResourceFailureException) {
                throw new IntakeFinalizationPersistenceException(
                        "Intake branch transaction failed due to a retryable database condition",
                        failure);
            }
            throw rejected(
                    "INTAKE_BRANCH_PERSISTENCE_INVARIANT",
                    "Intake branch transaction violated a database invariant",
                    failure);
        } catch (TransactionException failure) {
            throw new IntakeFinalizationPersistenceException(
                    "Intake branch transaction failed before its commit outcome was known",
                    failure);
        } catch (RuntimeException failure) {
            throw rejected(
                    "INTAKE_BRANCH_DOMAIN_REJECTED",
                    failure.getMessage() == null
                            ? "Intake branch failed closed"
                            : failure.getMessage(),
                    failure);
        }
    }

    private BranchCommitReceipt reconcileOnly(BranchCommitRequest request) {
        List<OperationRow> rows = jdbc.query(
                """
                select id, case_id, case_command_id, operation_type, room_epoch,
                       process_revision, fencing_token, request_hash, operation_status,
                       result_uri, result_sha256, completed_at, version
                  from domain_operation
                 where tenant_surrogate = :tenantSurrogate
                   and operation_key = :operationKey
                """,
                parameters(request),
                (row, ignored) -> new OperationRow(
                        row.getString("id"),
                        row.getString("case_id"),
                        row.getString("case_command_id"),
                        row.getString("operation_type"),
                        row.getLong("room_epoch"),
                        row.getLong("process_revision"),
                        row.getLong("fencing_token"),
                        row.getString("request_hash"),
                        row.getString("operation_status"),
                        row.getString("result_uri"),
                        row.getString("result_sha256"),
                        row.getObject("completed_at", OffsetDateTime.class),
                        row.getLong("version")));
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() != 1) {
            throw rejected(
                    "INTAKE_BRANCH_PERSISTENCE_INVARIANT",
                    "branch receipt ledger contains duplicate operation keys");
        }
        OperationRow operation = rows.getFirst();
        requireOperationBinding(request, operation);
        if ("STARTED".equals(operation.status())) {
            throw new IntakeFinalizationPersistenceException(
                    "Intake branch reconciliation found an existing operation without a committed receipt",
                    new IllegalStateException(
                            "unresolved branch operation status: " + operation.status()));
        }
        if (!"COMPLETED".equals(operation.status())) {
            throw rejected(
                    "INTAKE_BRANCH_RECEIPT_UNAVAILABLE",
                    "branch operation ended without a committed receipt");
        }
        return readCompletedReceipt(request, operation);
    }

    private BranchCommitReceipt commitInTransaction(BranchCommitRequest request) {
        OffsetDateTime now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS)
                .atOffset(ZoneOffset.UTC);
        lockOperationKey(request);
        CommandRow command = requireCommand(request);
        int inserted = "ORCHESTRATION_ACCEPTED".equals(command.status())
                ? startOperation(request, command, now)
                : 0;
        OperationRow operation = lockOperation(request);
        requireOperationBinding(request, command, operation);
        if ("COMPLETED".equals(operation.status())) {
            requireCommittedCommandBinding(command, operation);
            return readCompletedReceipt(request, operation);
        }
        if (!"ORCHESTRATION_ACCEPTED".equals(command.status())
                || inserted == 0
                || !"STARTED".equals(operation.status())) {
            throw rejected(
                    "INTAKE_BRANCH_OPERATION_INCOMPLETE",
                    "branch operation ledger contains a non-replayable unfinished result");
        }

        AuthorityRows authority = requireCurrentAuthority(request, command);
        ResolvedBranchCommand resolved = Objects.requireNonNull(
                commandResolver.resolve(request), "resolved branch command");
        resolved.requireMatches(request);
        if (!command.payloadSchema().equals(resolved.payloadSchema())) {
            throw rejected(
                    "INTAKE_BRANCH_PAYLOAD_SCHEMA_MISMATCH",
                    "resolved branch command schema does not match the immutable command ledger");
        }
        FulfillmentCaseEntity dispute = caseRepository.findByIdForUpdate(request.envelope().caseId())
                .orElseThrow(() -> rejected(
                        "INTAKE_BRANCH_CASE_MISSING", "locked Intake case no longer exists"));
        CaseRoomEntity intakeRoom = roomRepository
                .findByCaseIdAndRoomType(request.envelope().caseId(), RoomType.INTAKE)
                .orElseThrow(() -> rejected(
                        "INTAKE_BRANCH_ROOM_MISSING", "locked Intake room no longer exists"));
        AuthenticatedActor actor =
                new AuthenticatedActor(authority.actorId(), ActorRole.valueOf(authority.actorRole()));
        reconcileConfirmationHandoff(request, resolved, authority, now);

        long eventSequence = nextEventSequence(request.envelope().caseId());
        EventCoordinates eventCoordinates = eventCoordinates(request, eventSequence);
        RevisionRows expectedRevisions = expectedRevisions(request);
        BranchResult result = applyBranch(
                request,
                resolved,
                dispute,
                intakeRoom,
                actor,
                eventCoordinates,
                expectedRevisions,
                now);
        RevisionRows revisions = advanceProjection(request, dispute, result, eventSequence, now);
        if (!expectedRevisions.equals(revisions)) {
            throw rejected(
                    "INTAKE_BRANCH_REVISION_INVARIANT",
                    "branch projection did not commit the pre-authorized event revisions");
        }
        EventReceipt event = writeCommittedEvent(
                request,
                result,
                authority,
                revisions,
                dispute,
                eventCoordinates,
                now);
        markCommandApplied(command, event, now);
        completeOperation(operation, event, now);
        BranchCommitReceipt receipt = new BranchCommitReceipt(
                "intake-branch-commit-receipt.v1",
                request.operation(),
                new OperationReceipt(
                        "intake-operation-receipt.v1",
                        request.operationKey(),
                        request.requestHash(),
                        event.resultHash(),
                        revisions.processRevision(),
                        revisions.roomRevision()),
                event.event());
        receipt.requireMatches(request);
        return receipt;
    }

    private BranchResult applyBranch(
            BranchCommitRequest request,
            ResolvedBranchCommand resolved,
            FulfillmentCaseEntity dispute,
            CaseRoomEntity intakeRoom,
            AuthenticatedActor actor,
            EventCoordinates eventCoordinates,
            RevisionRows expectedRevisions,
            OffsetDateTime now) {
        return switch (request.operation()) {
            case INITIATOR_ACCEPT -> {
                BranchResult result = domainService.acceptInitiator(
                        dispute,
                        intakeRoom,
                        actor,
                        requiredConfirmation(resolved),
                        now,
                        TimelineEventMode.FORMAL_TYPED_ONLY);
                domainService.requireFormalInitiatorMatrix(dispute);
                yield result;
            }
            case INITIATOR_REJECT -> domainService.rejectInitiator(
                    dispute,
                    intakeRoom,
                    actor,
                    requiredConfirmation(resolved),
                    now,
                    TimelineEventMode.FORMAL_TYPED_ONLY);
            case CANCEL -> domainService.cancel(
                    dispute,
                    intakeRoom,
                    actor,
                    resolved.cancellationReason(),
                    now,
                    TimelineEventMode.FORMAL_TYPED_ONLY);
            case RESPONDENT_CONFIRM -> {
                BranchResult result = domainService.confirmRespondent(
                        dispute,
                        intakeRoom,
                        actor,
                        requiredConfirmation(resolved),
                        now,
                        TimelineEventMode.FORMAL_TYPED_ONLY,
                        new RespondentSubmitLineage(
                                request.envelope().tenantSurrogate(),
                                request.operationKey(),
                                request.envelope().commandId(),
                                request.envelope().commandSequence(),
                                request.requestHash(),
                                eventCoordinates.eventId(),
                                eventCoordinates.eventRef(),
                                eventCoordinates.sequence(),
                                request.envelope().roomEpoch(),
                                request.envelope().fencingToken(),
                                expectedRevisions.processRevision(),
                                expectedRevisions.roomRevision()));
                IntakeBranchDomainService.ObjectNodeAuthority matrix =
                        domainService.requireFormalBilateralMatrix(dispute);
                FrozenIntakeSubmissionAuthority frozen = Objects.requireNonNull(
                        result.frozenSubmissionAuthority(),
                        "respondent branch frozen submission authority");
                if (!FrozenIntakeSubmissionAuthority.MATRIX_KIND.equals(matrix.matrixKind())
                        || !frozen.matrixContentHash().equals(matrix.contentHash())) {
                    throw rejected(
                            "INTAKE_RESPONDENT_FROZEN_MATRIX_MISMATCH",
                            "respondent Submit authority differs from the formal bilateral matrix");
                }
                requireFrozenSubmitBinding(
                        request, actor, eventCoordinates, expectedRevisions, frozen);
                yield result;
            }
        };
    }

    private RevisionRows advanceProjection(
            BranchCommitRequest request,
            FulfillmentCaseEntity dispute,
            BranchResult result,
            long eventSequence,
            OffsetDateTime now) {
        ActivityEnvelope envelope = request.envelope();
        long processRevision = Math.addExact(envelope.processRevision(), 1);
        long roomRevision = Math.addExact(envelope.roomRevision(), 1);
        if (request.operation() == BranchOperation.RESPONDENT_CONFIRM) {
            return transitionRespondentToEvidence(
                    request, dispute, result, eventSequence, processRevision, roomRevision, now);
        }
        boolean terminal = request.operation() == BranchOperation.INITIATOR_REJECT
                || request.operation() == BranchOperation.CANCEL;
        int epochChanged = jdbc.update(
                """
                update case_room_epoch
                   set lifecycle_status = case when :terminal then 'TERMINAL' else lifecycle_status end,
                       process_revision = :processRevision,
                       room_revision = :roomRevision,
                       terminal_at = case when :terminal then :now else terminal_at end,
                       updated_at = :now,
                       version = version + 1
                 where tenant_surrogate = :tenantSurrogate
                   and case_id = :caseId
                   and room_type = 'INTAKE'
                   and room_epoch = :roomEpoch
                   and fencing_token = :fencingToken
                   and writer_mode = 'TEMPORAL'
                   and lifecycle_status = 'ACTIVE'
                   and process_revision = :expectedProcessRevision
                   and room_revision = :expectedRoomRevision
                """,
                parameters(request)
                        .addValue("terminal", terminal)
                        .addValue("processRevision", processRevision)
                        .addValue("roomRevision", roomRevision)
                        .addValue("now", now));
        if (epochChanged != 1) {
            throw rejected(
                    "INTAKE_BRANCH_STALE_EPOCH",
                    "Intake epoch changed before the branch transition committed");
        }
        String phase = terminal ? "CLOSED" : "WAITING_PARTY";
        int projectionChanged = jdbc.update(
                """
                update case_process_projection
                   set macro_phase = :macroPhase,
                       current_room = case when :terminal then null else 'INTAKE' end,
                       room_phase = :roomPhase,
                       writer_activation_status = case when :terminal then 'TERMINAL' else writer_activation_status end,
                       process_revision = :processRevision,
                       last_command_sequence = :commandSequence,
                       last_case_event_sequence = :eventSequence,
                       projected_deadline_at = null,
                       projected_at = :now,
                       updated_at = :now,
                       version = version + 1
                 where tenant_surrogate = :tenantSurrogate
                   and case_id = :caseId
                   and current_room = 'INTAKE'
                   and writer_mode = 'TEMPORAL'
                   and writer_activation_status = 'READY'
                   and room_epoch = :roomEpoch
                   and fencing_token = :fencingToken
                   and process_revision = :expectedProcessRevision
                """,
                parameters(request)
                        .addValue("terminal", terminal)
                        .addValue("macroPhase", dispute.getCaseStatus().name())
                        .addValue("roomPhase", phase)
                        .addValue("processRevision", processRevision)
                        .addValue("eventSequence", eventSequence)
                        .addValue("now", now));
        if (projectionChanged != 1) {
            throw rejected(
                    "INTAKE_BRANCH_STALE_PROJECTION",
                    "Intake projection changed before the branch transition committed");
        }
        return new RevisionRows(processRevision, roomRevision);
    }

    private RevisionRows transitionRespondentToEvidence(
            BranchCommitRequest request,
            FulfillmentCaseEntity dispute,
            BranchResult result,
            long eventSequence,
            long expectedProcessRevision,
            long expectedSourceRoomRevision,
            OffsetDateTime now) {
        if (roomEpochAllocator == null) {
            throw rejected(
                    "INTAKE_RESPONDENT_EPOCH_ALLOCATOR_MISSING",
                    "respondent formal commit requires the target room epoch allocator");
        }
        FrozenIntakeSubmissionAuthority frozen = Objects.requireNonNull(
                result.frozenSubmissionAuthority(),
                "respondent branch frozen submission authority");
        advanceRespondentProjectionSequence(request, eventSequence, now);
        RoomEpochAllocator.RoomEpochAllocation allocation = Objects.requireNonNull(
                roomEpochAllocator.transition(
                        new TransitionRoomEpoch(
                                request.envelope().caseId(),
                                ContractTypes.RoomType.INTAKE,
                                Objects.requireNonNull(result.evidenceRoomId(), "evidenceRoomId"),
                                ContractTypes.RoomType.EVIDENCE,
                                dispute.getCaseStatus().name(),
                                "OPEN",
                                result.view().deadlineAt(),
                                now,
                                frozen.projectionRef(),
                                frozen.projectionSha256())),
                "respondent evidence epoch allocation");
        if (!request.envelope().caseId().equals(allocation.caseId())
                || allocation.roomType() != ContractTypes.RoomType.EVIDENCE
                || allocation.processRevision() != expectedProcessRevision) {
            throw rejected(
                    "INTAKE_RESPONDENT_EPOCH_TRANSITION_INVALID",
                    "respondent formal commit did not create the expected Evidence epoch");
        }
        // The allocator terminalizes the source epoch and increments its room revision. The typed
        // event remains bound to that source Intake epoch, so retain the exact source revisions.
        return new RevisionRows(expectedProcessRevision, expectedSourceRoomRevision);
    }

    private void advanceRespondentProjectionSequence(
            BranchCommitRequest request, long eventSequence, OffsetDateTime now) {
        int changed = jdbc.update(
                """
                update case_process_projection
                   set last_command_sequence = :commandSequence,
                       last_case_event_sequence = :eventSequence,
                       projected_at = :now,
                       updated_at = :now,
                       version = version + 1
                 where tenant_surrogate = :tenantSurrogate
                   and case_id = :caseId
                   and current_room = 'INTAKE'
                   and writer_mode = 'TEMPORAL'
                   and writer_activation_status = 'READY'
                   and room_epoch = :roomEpoch
                   and fencing_token = :fencingToken
                   and process_revision = :expectedProcessRevision
                   and last_command_sequence < :commandSequence
                   and last_case_event_sequence < :eventSequence
                """,
                parameters(request)
                        .addValue("eventSequence", eventSequence)
                        .addValue("now", now));
        if (changed != 1) {
            throw rejected(
                    "INTAKE_RESPONDENT_PROJECTION_SEQUENCE_STALE",
                    "respondent branch could not advance the source projection sequence");
        }
    }

    private CommandRow requireCommand(BranchCommitRequest request) {
        List<CommandRow> rows = jdbc.query(
                """
                select id, case_command_sequence, command_type, room_type, room_epoch,
                       actor_id, actor_role,
                       payload_schema_version, payload_uri, payload_sha256,
                       expected_process_revision, request_hash, command_status,
                       result_uri, result_sha256, applied_at
                  from case_command
                 where tenant_surrogate = :tenantSurrogate
                   and case_id = :caseId
                   and command_id = :commandId
                 for update
                """,
                parameters(request),
                (row, ignored) -> new CommandRow(
                        row.getString("id"),
                        row.getLong("case_command_sequence"),
                        row.getString("command_type"),
                        row.getString("room_type"),
                        row.getLong("room_epoch"),
                        row.getString("actor_id"),
                        row.getString("actor_role"),
                        row.getString("payload_schema_version"),
                        row.getString("payload_uri"),
                        row.getString("payload_sha256"),
                        row.getLong("expected_process_revision"),
                        row.getString("request_hash"),
                        row.getString("command_status"),
                        row.getString("result_uri"),
                        row.getString("result_sha256"),
                        row.getObject("applied_at", OffsetDateTime.class)));
        if (rows.size() != 1) {
            throw rejected(
                    "INTAKE_BRANCH_COMMAND_MISSING",
                    "branch command is not present in the Java command ledger");
        }
        CommandRow command = rows.getFirst();
        ActivityEnvelope envelope = request.envelope();
        if (command.sequence() != envelope.commandSequence()
                || !command.commandType().equals(envelope.commandType().name())
                || !"INTAKE".equals(command.roomType())
                || command.roomEpoch() != envelope.roomEpoch()
                || !command.payloadUri().equals(envelope.commandPayloadRef())
                || !command.payloadHash().equals(envelope.commandPayloadHash())
                || command.expectedProcessRevision() != envelope.processRevision()
                || !command.requestHash().equals(request.requestHash())
                || !List.of("ORCHESTRATION_ACCEPTED", "APPLIED").contains(command.status())) {
            throw rejected(
                    "INTAKE_BRANCH_COMMAND_CONFLICT",
                    "branch Activity does not match the exact accepted or applied Java command");
        }
        return command;
    }

    private static void requireCommittedCommandBinding(
            CommandRow command, OperationRow operation) {
        if (!"APPLIED".equals(command.status())
                || command.appliedAt() == null
                || !Objects.equals(command.resultUri(), operation.resultUri())
                || !Objects.equals(command.resultHash(), operation.resultHash())) {
            throw rejected(
                    "INTAKE_BRANCH_RECEIPT_CONFLICT",
                    "completed operation is not bound to the exact applied command receipt");
        }
    }

    private AuthorityRows requireCurrentAuthority(
            BranchCommitRequest request, CommandRow command) {
        ActivityEnvelope envelope = request.envelope();
        List<AuthorityRows> rows = jdbc.query(
                """
                select binding.actor_id, binding.actor_role, epoch.room_id,
                       projection.room_phase, dispute.initiator_id, dispute.initiator_role,
                       dispute.respondent_id, dispute.respondent_role
                  from case_intake_graph_thread_binding binding
                  join case_room_epoch epoch
                    on epoch.tenant_surrogate = binding.tenant_surrogate
                   and epoch.case_id = binding.case_id
                   and epoch.room_type = binding.room_type
                   and epoch.room_epoch = binding.room_epoch
                   and epoch.fencing_token = binding.fencing_token
                  join case_process_projection projection on projection.case_id = epoch.case_id
                  join case_room room
                    on room.id = epoch.room_id
                   and room.case_id = epoch.case_id
                   and room.room_type = epoch.room_type
                  join fulfillment_dispute_case dispute on dispute.id = epoch.case_id
                 where binding.tenant_surrogate = :tenantSurrogate
                   and binding.case_id = :caseId
                   and binding.room_type = 'INTAKE'
                   and binding.room_epoch = :roomEpoch
                   and binding.fencing_token = :fencingToken
                   and binding.actor_scope_hash = :actorScopeHash
                   and binding.registration_status = 'REGISTERED'
                   and binding.writer_mode = 'TEMPORAL'
                   and binding.graph_key = :expectedGraphKey
                   and binding.graph_version = :graphVersion
                   and binding.checkpoint_schema_version = :checkpointSchemaVersion
                   and binding.prompt_version = :promptVersion
                   and binding.model_profile_id = :modelProfileId
                   and binding.output_schema_version = :outputSchemaVersion
                   and binding.policy_version = :policyVersion
                   and binding.guardrail_version = :guardrailVersion
                   and binding.tool_policy_version = :toolPolicyVersion
                   and epoch.writer_mode = 'TEMPORAL'
                   and epoch.lifecycle_status = 'ACTIVE'
                   and epoch.provisioning_status = 'READY'
                   and epoch.selection_schema_version = 'room-epoch-selection.v2'
                   and epoch.graph_key = binding.graph_key
                   and epoch.graph_version = binding.graph_version
                   and epoch.checkpoint_schema_version = binding.checkpoint_schema_version
                   and epoch.process_revision = :expectedProcessRevision
                   and epoch.room_revision = :expectedRoomRevision
                   and projection.tenant_surrogate = :tenantSurrogate
                   and projection.current_room = 'INTAKE'
                   and projection.writer_mode = 'TEMPORAL'
                   and projection.writer_activation_status = 'READY'
                   and projection.room_epoch = :roomEpoch
                   and projection.fencing_token = :fencingToken
                   and projection.process_revision = :expectedProcessRevision
                   and projection.last_command_sequence <= :commandSequence
                   and room.room_status = 'OPEN'
                   and dispute.current_room = 'INTAKE'
                   and dispute.current_deadline_at is null
                   and dispute.case_status in (
                       'INTAKE_PENDING', 'INTAKE_IN_PROGRESS',
                       'WAITING_SLOT_COMPLETION', 'INTAKE_COMPLETED'
                   )
                   and not exists (
                       select 1
                         from case_command earlier
                        where earlier.tenant_surrogate = :tenantSurrogate
                          and earlier.case_id = :caseId
                          and earlier.case_command_sequence < :commandSequence
                          and earlier.command_status in (
                              'PENDING_ORCHESTRATION', 'ORCHESTRATION_ACCEPTED'
                          )
                   )
                 for update of binding, epoch, projection, room, dispute
                """,
                parameters(request),
                (row, ignored) -> new AuthorityRows(
                        row.getString("actor_id"),
                        row.getString("actor_role"),
                        row.getString("room_id"),
                        row.getString("room_phase"),
                        row.getString("initiator_id"),
                        row.getString("initiator_role"),
                        row.getString("respondent_id"),
                        row.getString("respondent_role")));
        if (rows.isEmpty()) {
            throw rejected(
                    "INTAKE_BRANCH_STALE_AUTHORITY",
                    "active TEMPORAL Intake authority, epoch, fence, or revisions are stale");
        }
        AuthorityRows authority = rows.getFirst();
        requireTargetEpochGraphBinding(request);
        if (rows.stream().anyMatch(candidate -> !candidate.equals(authority))) {
            throw rejected(
                    "INTAKE_BRANCH_AMBIGUOUS_AUTHORITY",
                    "matching Intake thread bindings disagree on Java actor authority");
        }
        boolean initiator = authority.actorId().equals(authority.initiatorId())
                && authority.actorRole().equals(authority.initiatorRole());
        boolean respondent = authority.actorId().equals(authority.respondentId())
                && authority.actorRole().equals(authority.respondentRole());
        IntakeParty expectedParty = respondent ? IntakeParty.RESPONDENT : IntakeParty.INITIATOR;
        if ((!initiator && !respondent)
                || (request.operation() == BranchOperation.CANCEL && !initiator)
                || expectedParty != envelope.party()
                || !authority.actorId().equals(command.actorId())
                || !authority.actorRole().equals(command.actorRole())) {
            throw rejected(
                    "INTAKE_BRANCH_ACTOR_REJECTED",
                    "branch command actor does not match the exact Java party authority");
        }
        if (request.operation() != BranchOperation.CANCEL
                && !"READY_TO_CONFIRM".equals(authority.roomPhase())) {
            throw rejected(
                    "INTAKE_BRANCH_NOT_READY",
                    "formal Intake confirmation requires READY_TO_CONFIRM Java authority");
        }
        if (request.operation() == BranchOperation.CANCEL
                && !List.of("OPEN", "WAITING_PARTY", "AGENT_RUNNING", "READY_TO_CONFIRM")
                        .contains(authority.roomPhase())) {
            throw rejected(
                    "INTAKE_BRANCH_NOT_CANCELLABLE",
                    "formal Intake cancellation is not allowed from the current room phase");
        }
        requirePartyCompletionState(request, authority);
        return authority;
    }

    private void reconcileConfirmationHandoff(
            BranchCommitRequest request,
            ResolvedBranchCommand resolved,
            AuthorityRows authority,
            OffsetDateTime now) {
        if (request.operation() == BranchOperation.CANCEL) {
            return;
        }
        IntakeConfirmationCommand confirmation = requiredConfirmation(resolved);
        List<ConfirmationDossierRow> rows = jdbc.query(
                """
                select id, dossier_version, dossier_json::text as dossier_json
                  from case_intake_dossier
                 where case_id = :caseId
                   and room_type = 'INTAKE'
                 for update
                """,
                Map.of("caseId", request.envelope().caseId()),
                (row, ignored) -> new ConfirmationDossierRow(
                        row.getString("id"),
                        row.getLong("dossier_version"),
                        row.getString("dossier_json")));
        if (rows.size() != 1) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISSING",
                    "formal Intake confirmation requires one exact current dossier");
        }

        ConfirmationDossierRow row = rows.getFirst();
        ObjectNode dossier;
        try {
            JsonNode decoded = objectMapper.readTree(row.dossierJson());
            if (!(decoded instanceof ObjectNode object)) {
                throw new IllegalArgumentException("dossier is not an object");
            }
            dossier = object;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_INVALID",
                    "formal Intake confirmation dossier cannot be decoded",
                    failure);
        }
        IntakeDossierProjectionMerger.requireCanonicalHandoffRemarkPartition(dossier);

        JsonNode partyState = dossier.get("party_intake_state");
        String actorRole = authority.actorRole();
        if (!"intake-dossier.v2".equals(dossier.path("schema_version").asText(null))
                || !hasExactFields(
                        partyState, Set.of("schema_version", "USER", "MERCHANT"))
                || !"party-intake-state.v1".equals(
                        partyState.path("schema_version").asText(null))
                || !Set.of("USER", "MERCHANT").contains(actorRole)) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_INVALID",
                    "formal Intake confirmation has unsupported party handoff authority");
        }
        JsonNode actorEntry = partyState.get(actorRole);
        if (!hasExactFields(
                actorEntry,
                Set.of(
                        "intake_quality",
                        "missing_information",
                        "handoff_notes",
                        "admission"))) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_INVALID",
                    "formal Intake confirmation current-party authority is malformed");
        }
        JsonNode handoff = actorEntry.get("handoff_notes");
        if (!hasExactFields(
                        handoff,
                        Set.of(
                                "remark_status",
                                "phase_source_message_id",
                                "latest_remark",
                                "remarks",
                                "instruction"))
                || !handoff.equals(dossier.get("handoff_notes"))) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISMATCH",
                    "formal Intake confirmation current-party handoff is not the exact dossier mirror");
        }

        String status = handoff.path("remark_status").asText(null);
        String confirmationNote = confirmation.confirmationNote();
        if ("HAS_REMARKS".equals(status)) {
            requireCanonicalExistingRemarks(handoff, actorRole, confirmationNote);
            requirePartitionMatchesExistingHandoff(dossier, actorRole, handoff, status);
            return;
        }
        if ("NO_EXTRA_REMARKS".equals(status)) {
            requireCanonicalNoExtraRemarks(handoff, confirmationNote);
            requirePartitionMatchesExistingHandoff(dossier, actorRole, handoff, status);
            return;
        }
        if (!"WAITING_FOR_REMARK".equals(status)
                || confirmationNote != null
                || !handoff.path("phase_source_message_id").isTextual()
                || handoff.path("phase_source_message_id").textValue().isBlank()
                || !handoff.path("latest_remark").isTextual()
                || !handoff.path("latest_remark").textValue().isEmpty()
                || !handoff.path("remarks").isArray()
                || !handoff.path("remarks").isEmpty()
                || !handoff.path("instruction").isTextual()) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISMATCH",
                    "blank formal Intake confirmation requires WAITING_FOR_REMARK authority");
        }
        requirePartitionMatchesExistingHandoff(dossier, actorRole, handoff, status);

        ObjectNode finalizedHandoff = (ObjectNode) handoff;
        finalizedHandoff.put("remark_status", "NO_EXTRA_REMARKS");
        finalizedHandoff.put("latest_remark", "无额外备注。");
        finalizedHandoff.putArray("remarks");
        dossier.set("handoff_notes", finalizedHandoff.deepCopy());
        finalizeFormalConfirmationPartition(dossier, actorRole, request);
        IntakeDossierProjectionMerger.requireCanonicalHandoffRemarkPartition(dossier);
        int changed = jdbc.update(
                """
                update case_intake_dossier
                   set dossier_version = dossier_version + 1,
                       dossier_json = cast(:dossierJson as jsonb),
                       updated_at = :now,
                       updated_by = :actorId
                 where id = :id
                   and dossier_version = :dossierVersion
                """,
                new MapSqlParameterSource()
                        .addValue("id", row.id())
                        .addValue("dossierVersion", row.version())
                        .addValue("dossierJson", ContractJson.canonicalString(dossier))
                        .addValue("now", now)
                        .addValue("actorId", authority.actorId()));
        if (changed != 1) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_STALE",
                    "formal Intake confirmation handoff changed while locked");
        }
    }

    private static void requirePartitionMatchesExistingHandoff(
            ObjectNode dossier, String actorRole, JsonNode handoff, String expectedStatus) {
        JsonNode partition = dossier.get("handoff_remark_partition");
        if (partition == null) {
            return;
        }
        JsonNode party = partition.path("parties").path(actorRole);
        if (!expectedStatus.equals(party.path("remark_status").asText(null))) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISMATCH",
                    "formal Intake confirmation partition status differs from party authority");
        }
        JsonNode source = party.path("source");
        if ("ROOM_MESSAGE".equals(source.path("source_kind").asText(null))
                && !source.path("message_id")
                        .equals(handoff.path("phase_source_message_id"))) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISMATCH",
                    "formal Intake confirmation partition source differs from party phase authority");
        }
        if ("HAS_REMARKS".equals(expectedStatus)) {
            JsonNode partitionRemarks = party.path("remarks");
            JsonNode handoffRemarks = handoff.path("remarks");
            if (!party.path("latest_remark").equals(handoff.path("latest_remark"))
                    || partitionRemarks.size() != handoffRemarks.size()) {
                throw rejected(
                        "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISMATCH",
                        "formal Intake confirmation partition remarks differ from party authority");
            }
            for (int index = 0; index < partitionRemarks.size(); index++) {
                JsonNode partitionRemark = partitionRemarks.get(index);
                JsonNode handoffRemark = handoffRemarks.get(index);
                if (!partitionRemark.path("party_role").equals(handoffRemark.path("role"))
                        || !partitionRemark.path("text").equals(handoffRemark.path("text"))
                        || !partitionRemark
                                .path("source_message_id")
                                .equals(handoffRemark.path("source_message_id"))) {
                    throw rejected(
                            "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISMATCH",
                            "formal Intake confirmation partition remark source differs from party authority");
                }
            }
        } else if (!party.path("latest_remark").asText("").isEmpty()
                || !party.path("remarks").isEmpty()) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISMATCH",
                    "formal Intake confirmation partition has unexpected remark content");
        }
    }

    private static void finalizeFormalConfirmationPartition(
            ObjectNode dossier, String actorRole, BranchCommitRequest request) {
        JsonNode partition = dossier.get("handoff_remark_partition");
        if (partition == null) {
            JsonNode matrix = dossier.path("case_fact_matrix");
            if (!matrix.isObject()) {
                throw rejected(
                        "INTAKE_CONFIRM_HANDOFF_AUTHORITY_INVALID",
                        "formal Intake confirmation cannot create a partition without a formal matrix");
            }
            ObjectNode created = dossier.putObject("handoff_remark_partition");
            created.put("schema_version", "handoff_remark_partition.v1");
            created.set("case_fact_matrix_id", matrix.path("matrix_id").deepCopy());
            created.set("case_fact_matrix_version", matrix.path("matrix_version").deepCopy());
            created.set("case_fact_matrix_hash", matrix.path("content_hash").deepCopy());
            ObjectNode parties = created.putObject("parties");
            for (String role : List.of("USER", "MERCHANT")) {
                if (role.equals(actorRole)) {
                    parties.set(role, formalConfirmationNoRemark(role, request));
                } else {
                    JsonNode handoff = dossier.path("party_intake_state")
                            .path(role)
                            .path("handoff_notes");
                    if (!hasExactFields(
                                    handoff,
                                    Set.of(
                                            "remark_status",
                                            "phase_source_message_id",
                                            "latest_remark",
                                            "remarks",
                                            "instruction"))
                            || !"NOT_READY".equals(
                                    handoff.path("remark_status").asText(null))
                            || !handoff.path("phase_source_message_id").isTextual()
                            || !handoff.path("phase_source_message_id").textValue().isEmpty()
                            || !handoff.path("latest_remark").isTextual()
                            || !handoff.path("latest_remark").textValue().isEmpty()
                            || !handoff.path("remarks").isArray()
                            || !handoff.path("remarks").isEmpty()) {
                        throw rejected(
                                "INTAKE_CONFIRM_HANDOFF_AUTHORITY_INVALID",
                                "formal Intake confirmation cannot reconstruct foreign remark authority");
                    }
                    ObjectNode notReady = parties.putObject(role);
                    notReady.put("party_role", role);
                    notReady.put("remark_status", "NOT_READY");
                    notReady.put("latest_remark", "");
                    notReady.putArray("remarks");
                }
            }
            return;
        }
        ((ObjectNode) partition.path("parties"))
                .set(actorRole, formalConfirmationNoRemark(actorRole, request));
    }

    private static ObjectNode formalConfirmationNoRemark(
            String actorRole, BranchCommitRequest request) {
        ObjectNode finalized = JsonNodeFactory.instance.objectNode();
        finalized.put("party_role", actorRole);
        finalized.put("remark_status", "NO_EXTRA_REMARKS");
        ObjectNode source = finalized.putObject("source");
        source.put("source_kind", "FORMAL_CONFIRMATION");
        source.put("command_id", request.envelope().commandId());
        source.put("request_hash", request.requestHash());
        finalized.put("latest_remark", "");
        finalized.putArray("remarks");
        return finalized;
    }

    private static void requireCanonicalExistingRemarks(
            JsonNode handoff, String actorRole, String confirmationNote) {
        JsonNode latest = handoff.path("latest_remark");
        JsonNode remarks = handoff.path("remarks");
        if (!handoff.path("phase_source_message_id").isTextual()
                || handoff.path("phase_source_message_id").textValue().isBlank()
                || !latest.isTextual()
                || latest.textValue().isBlank()
                || !remarks.isArray()
                || remarks.isEmpty()
                || !handoff.path("instruction").isTextual()
                || (confirmationNote != null && !confirmationNote.equals(latest.textValue()))) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISMATCH",
                    "formal Intake confirmation note differs from existing remark authority");
        }
        Set<String> sourceMessageIds = new HashSet<>();
        for (JsonNode remark : remarks) {
            if (!hasExactFields(
                            remark,
                            Set.of("role", "text", "source_message_id", "turn_source"))
                    || !actorRole.equals(remark.path("role").asText(null))
                    || !remark.path("text").isTextual()
                    || remark.path("text").textValue().isBlank()
                    || !remark.path("source_message_id").isTextual()
                    || remark.path("source_message_id").textValue().isBlank()
                    || !remark.path("turn_source").isTextual()
                    || remark.path("turn_source").textValue().isBlank()
                    || !sourceMessageIds.add(remark.path("source_message_id").textValue())) {
                throw rejected(
                        "INTAKE_CONFIRM_HANDOFF_AUTHORITY_INVALID",
                        "formal Intake confirmation existing remarks are not canonical");
            }
        }
        if (!latest.textValue().equals(remarks.get(remarks.size() - 1).path("text").asText())) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISMATCH",
                    "formal Intake confirmation latest remark differs from its source authority");
        }
    }

    private static void requireCanonicalNoExtraRemarks(
            JsonNode handoff, String confirmationNote) {
        JsonNode latest = handoff.path("latest_remark");
        JsonNode remarks = handoff.path("remarks");
        if (!handoff.path("phase_source_message_id").isTextual()
                || handoff.path("phase_source_message_id").textValue().isBlank()
                || !latest.isTextual()
                || !"无额外备注。".equals(latest.textValue())
                || !remarks.isArray()
                || !remarks.isEmpty()
                || !handoff.path("instruction").isTextual()
                || (confirmationNote != null && !confirmationNote.equals(latest.textValue()))) {
            throw rejected(
                    "INTAKE_CONFIRM_HANDOFF_AUTHORITY_MISMATCH",
                    "formal Intake confirmation differs from existing no-remark authority");
        }
    }

    private static boolean hasExactFields(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject() || value.size() != expected.size()) {
            return false;
        }
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private void requirePartyCompletionState(
            BranchCommitRequest request, AuthorityRows authority) {
        int initiatorComplete = completionCount(
                request.envelope().caseId(), authority.initiatorId(), authority.initiatorRole());
        int respondentComplete = completionCount(
                request.envelope().caseId(), authority.respondentId(), authority.respondentRole());
        switch (request.operation()) {
            case RESPONDENT_CONFIRM -> {
                if (initiatorComplete != 1 || respondentComplete != 0) {
                    throw rejected(
                            "INTAKE_RESPONDENT_AUTHORITY_REJECTED",
                            "respondent must be independently incomplete after initiator unlock");
                }
            }
            case CANCEL -> {
                if ((initiatorComplete != 0 && initiatorComplete != 1)
                        || respondentComplete != 0) {
                    throw rejected(
                            "INTAKE_INITIATOR_AUTHORITY_REJECTED",
                            "initiator cancellation requires no respondent completion");
                }
            }
            case INITIATOR_ACCEPT, INITIATOR_REJECT -> {
                if (initiatorComplete != 0 || respondentComplete != 0) {
                    throw rejected(
                            "INTAKE_INITIATOR_AUTHORITY_REJECTED",
                            "initiator decision requires both parties to be incomplete");
                }
            }
        }
    }

    private void requireTargetEpochGraphBinding(BranchCommitRequest request) {
        if (!TARGET_GRAPH_KEY.equals(expectedGraphKey)) {
            return;
        }
        Integer matches = jdbc.queryForObject(
                """
                select count(*)
                  from production_runtime_room_epoch_binding target_binding
                  join production_runtime_activation activation
                    on activation.activation_id = target_binding.activation_id
                   and activation.manifest_hash = target_binding.activation_manifest_hash
                   and activation.execution_lane = target_binding.execution_lane
                   and activation.isolated_domain_db_binding_hash =
                       target_binding.isolated_domain_db_binding_hash
                 where target_binding.tenant_surrogate = :tenantSurrogate
                   and target_binding.case_id = :caseId
                   and target_binding.room_type = 'INTAKE'
                   and target_binding.room_epoch = :roomEpoch
                   and target_binding.room_fencing_token = :fencingToken
                   and activation.execution_lane = 'PRODUCTION'
                   and activation.graph_key = :expectedGraphKey
                   and activation.lifecycle_status in ('ACTIVE', 'DRAIN_ONLY')
                """,
                parameters(request),
                Integer.class);
        if (matches == null || matches != 1) {
            throw rejected(
                    "INTAKE_BRANCH_TARGET_GRAPH_BINDING_STALE",
                    "target Intake epoch does not bind the exact registered graph key");
        }
    }

    private int completionCount(String caseId, String actorId, String actorRole) {
        Integer count = jdbc.queryForObject(
                """
                select count(*)
                  from case_intake_party_completion
                 where case_id = :caseId
                   and participant_id = :actorId
                   and participant_role = :actorRole
                   and completion_status = 'COMPLETED'
                """,
                Map.of("caseId", caseId, "actorId", actorId, "actorRole", actorRole),
                Integer.class);
        return count == null ? 0 : count;
    }

    private void lockOperationKey(BranchCommitRequest request) {
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                Map.of(
                        "lockKey",
                        request.envelope().tenantSurrogate() + ':' + request.operationKey()),
                (RowCallbackHandler) ignored -> {});
    }

    private int startOperation(
            BranchCommitRequest request, CommandRow command, OffsetDateTime now) {
        return jdbc.update(
                """
                insert into domain_operation (
                    id, operation_key, tenant_surrogate, case_id, case_command_id,
                    operation_type, room_type, room_epoch, process_revision, fencing_token,
                    request_hash, operation_status, started_at, created_at, updated_at, version
                ) values (
                    :id, :operationKey, :tenantSurrogate, :caseId, :caseCommandId,
                    :operationType, 'INTAKE', :roomEpoch, :expectedProcessRevision, :fencingToken,
                    :requestHash, 'STARTED', :now, :now, :now, 0
                ) on conflict (tenant_surrogate, operation_key) do nothing
                """,
                parameters(request)
                        .addValue("id", deterministicId("INBR_", request.operationKey(), "operation"))
                        .addValue("caseCommandId", command.id())
                        .addValue("operationType", operationType(request.operation()))
                        .addValue("now", now));
    }

    private OperationRow lockOperation(BranchCommitRequest request) {
        List<OperationRow> rows = jdbc.query(
                """
                select id, case_id, case_command_id, operation_type, room_epoch,
                       process_revision, fencing_token, request_hash, operation_status,
                       result_uri, result_sha256, completed_at, version
                  from domain_operation
                 where tenant_surrogate = :tenantSurrogate
                   and operation_key = :operationKey
                 for update
                """,
                parameters(request),
                (row, ignored) -> new OperationRow(
                        row.getString("id"),
                        row.getString("case_id"),
                        row.getString("case_command_id"),
                        row.getString("operation_type"),
                        row.getLong("room_epoch"),
                        row.getLong("process_revision"),
                        row.getLong("fencing_token"),
                        row.getString("request_hash"),
                        row.getString("operation_status"),
                        row.getString("result_uri"),
                        row.getString("result_sha256"),
                        row.getObject("completed_at", OffsetDateTime.class),
                        row.getLong("version")));
        if (rows.size() != 1) {
            throw rejected(
                    "INTAKE_BRANCH_OPERATION_MISSING",
                    "branch operation row could not be locked");
        }
        return rows.getFirst();
    }

    private static void requireOperationBinding(
            BranchCommitRequest request, CommandRow command, OperationRow operation) {
        requireOperationBinding(request, operation);
        if (!operation.caseCommandId().equals(command.id())) {
            throw rejected(
                    "INTAKE_BRANCH_OPERATION_CONFLICT",
                    "operation key is already bound to another exact branch request");
        }
    }

    private static void requireOperationBinding(
            BranchCommitRequest request, OperationRow operation) {
        ActivityEnvelope envelope = request.envelope();
        if (!operation.caseId().equals(envelope.caseId())
                || !operation.operationType().equals(operationType(request.operation()))
                || operation.roomEpoch() != envelope.roomEpoch()
                || operation.processRevision() != envelope.processRevision()
                || operation.fencingToken() != envelope.fencingToken()
                || !operation.requestHash().equals(request.requestHash())) {
            throw rejected(
                    "INTAKE_BRANCH_OPERATION_CONFLICT",
                    "operation key is already bound to another exact branch request");
        }
    }

    private void appendFrozenSubmission(
            BranchCommitRequest request,
            BranchResult result,
            AuthorityRows actorAuthority,
            RevisionRows revisions,
            EventCoordinates eventCoordinates,
            ObjectNode resultFacts) {
        if (request.operation() != BranchOperation.RESPONDENT_CONFIRM) {
            if (result.frozenSubmissionAuthority() != null
                    || result.frozenMatrixCanonicalJson() != null) {
                throw rejected(
                        "INTAKE_FROZEN_SUBMISSION_UNEXPECTED",
                        "only respondent Submit can persist frozen matrix authority");
            }
            return;
        }
        FrozenIntakeSubmissionAuthority frozen = Objects.requireNonNull(
                result.frozenSubmissionAuthority(),
                "respondent branch frozen submission authority");
        requireFrozenSubmitBinding(
                request,
                new AuthenticatedActor(
                        actorAuthority.actorId(), ActorRole.valueOf(actorAuthority.actorRole())),
                eventCoordinates,
                revisions,
                frozen);
        try {
            JsonNode matrix = objectMapper.readTree(Objects.requireNonNull(
                    result.frozenMatrixCanonicalJson(),
                    "respondent branch canonical frozen matrix"));
            if (!result.frozenMatrixCanonicalJson()
                            .equals(ContractJson.canonicalString(matrix))
                    || !FrozenIntakeSubmissionAuthority.MATRIX_KIND.equals(result.matrixKind())
                    || !frozen.matrixContentHash().equals(result.matrixHash())) {
                throw new IllegalArgumentException(
                        "respondent branch matrix material is not canonical");
            }
            frozen.requireMatchesMatrix(matrix);
            frozen.requireProjectionPair(frozen.projectionRef(), frozen.projectionSha256());
            ObjectNode frozenSubmission = resultFacts.putObject("frozen_submission");
            frozenSubmission.set("authority", objectMapper.valueToTree(frozen));
            frozenSubmission.set("matrix", matrix);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw rejected(
                    "INTAKE_RESPONDENT_FROZEN_SUBMISSION_INVALID",
                    "respondent Submit frozen matrix authority is invalid",
                    failure);
        }
    }

    private EventReceipt writeCommittedEvent(
            BranchCommitRequest request,
            BranchResult result,
            AuthorityRows authority,
            RevisionRows revisions,
            FulfillmentCaseEntity dispute,
            EventCoordinates eventCoordinates,
            OffsetDateTime now) {
        String eventId = eventCoordinates.eventId();
        String eventRef = eventCoordinates.eventRef();
        long sequence = eventCoordinates.sequence();
        ObjectNode resultFacts = objectMapper.createObjectNode();
        resultFacts.put(
                "schema_version",
                result.frozenSubmissionAuthority() == null
                        ? "intake-branch-result.v1"
                        : "intake-branch-result.v2");
        resultFacts.put("operation", request.operation().name());
        resultFacts.put("case_id", request.envelope().caseId());
        resultFacts.put("case_status", dispute.getCaseStatus().name());
        if (dispute.getCurrentRoom() == null) {
            resultFacts.putNull("current_room");
        } else {
            resultFacts.put("current_room", dispute.getCurrentRoom());
        }
        if (dispute.getCurrentDeadlineAt() == null) {
            resultFacts.putNull("deadline_at");
        } else {
            resultFacts.put("deadline_at", dispute.getCurrentDeadlineAt().toString());
        }
        resultFacts.put("process_revision", revisions.processRevision());
        resultFacts.put("room_revision", revisions.roomRevision());
        if (result.matrixKind() != null) {
            resultFacts.put("matrix_kind", result.matrixKind());
        }
        if (result.matrixHash() != null) {
            resultFacts.put("matrix_hash", result.matrixHash());
        }
        appendFrozenSubmission(
                request, result, authority, revisions, eventCoordinates, resultFacts);
        String resultHash = ContractJson.sha256Hex(resultFacts);

        ObjectNode eventJson = objectMapper.createObjectNode();
        eventJson.put("schema_version", "intake-branch-committed-event.v1");
        eventJson.put("event_id", eventId);
        eventJson.put("event_ref", eventRef);
        eventJson.put("event_sequence", sequence);
        eventJson.put("event_type", eventCoordinates.eventType().name());
        eventJson.put("party", request.envelope().party().name());
        eventJson.put("command_id", request.envelope().commandId());
        eventJson.put("tenant_surrogate", request.envelope().tenantSurrogate());
        eventJson.put("case_id", request.envelope().caseId());
        eventJson.put("room_epoch", request.envelope().roomEpoch());
        eventJson.put("fencing_token", request.envelope().fencingToken());
        eventJson.put("actor_scope_hash", request.envelope().actorScopeHash());
        eventJson.put("operation_key", request.operationKey());
        eventJson.put("request_hash", request.requestHash());
        eventJson.put("result_hash", resultHash);
        eventJson.put("process_revision", revisions.processRevision());
        eventJson.put("room_revision", revisions.roomRevision());
        eventJson.set("result", resultFacts);
        String eventHash = ContractJson.sha256Hex(eventJson);
        eventJson.put("event_hash", eventHash);
        int inserted = jdbc.update(
                """
                insert into case_timeline_event (
                    id, case_id, dossier_id, sequence_no, room_id, event_type, event_time,
                    source_refs_json, event_json, audience_json, audience_actor_ids_json,
                    event_key, created_at, created_by
                ) values (
                    :id, :caseId, null, :sequence, :roomId, :eventType, :now,
                    cast(:sourceRefs as jsonb), cast(:eventJson as jsonb),
                    cast(:audience as jsonb), cast(:actorIds as jsonb),
                    :eventKey, :now, :actorId
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", eventId)
                        .addValue("caseId", request.envelope().caseId())
                        .addValue("sequence", sequence)
                        .addValue("roomId", authority.roomId())
                        .addValue("eventType", eventType(request.operation()).name())
                        .addValue("now", now)
                        .addValue("sourceRefs", json(List.of(request.envelope().commandPayloadRef())))
                        .addValue("eventJson", ContractJson.canonicalString(eventJson))
                        .addValue("audience", json(List.of(authority.actorRole())))
                        .addValue("actorIds", json(List.of(authority.actorId())))
                        .addValue("eventKey", "intake-branch:" + sha256(request.operationKey()))
                        .addValue("actorId", authority.actorId()));
        if (inserted != 1) {
            throw rejected(
                    "INTAKE_BRANCH_EVENT_WRITE_FAILED",
                    "typed branch event was not committed");
        }
        IntakeDomainEventRef event = new IntakeDomainEventRef(
                "intake-domain-event-ref.v1",
                eventId,
                eventRef,
                eventHash,
                sequence,
                eventType(request.operation()),
                request.envelope().party(),
                request.envelope().commandId(),
                request.envelope().tenantSurrogate(),
                request.envelope().caseId(),
                request.envelope().roomEpoch(),
                request.envelope().fencingToken(),
                request.envelope().actorScopeHash(),
                request.operationKey(),
                request.requestHash(),
                resultHash,
                revisions.processRevision(),
                revisions.roomRevision(),
                null,
                null);
        return new EventReceipt(event, resultHash);
    }

    private void markCommandApplied(
            CommandRow command, EventReceipt event, OffsetDateTime now) {
        int changed = jdbc.update(
                """
                update case_command
                   set command_status = 'APPLIED',
                       status_reason_code = null,
                       result_uri = :resultUri,
                       result_sha256 = :resultHash,
                       applied_at = :now,
                       updated_at = :now,
                       version = version + 1
                 where id = :id
                   and command_status = 'ORCHESTRATION_ACCEPTED'
                   and request_hash = :requestHash
                """,
                Map.of(
                        "resultUri", event.event().eventRef(),
                        "resultHash", event.resultHash(),
                        "now", now,
                        "id", command.id(),
                        "requestHash", command.requestHash()));
        if (changed != 1) {
            throw rejected(
                    "INTAKE_BRANCH_COMMAND_STALE",
                    "command changed before its branch result committed");
        }
    }

    private void completeOperation(
            OperationRow operation, EventReceipt event, OffsetDateTime now) {
        int changed = jdbc.update(
                """
                update domain_operation
                   set operation_status = 'COMPLETED',
                       result_uri = :resultUri,
                       result_sha256 = :resultHash,
                       failure_code = null,
                       failure_detail = null,
                       completed_at = :now,
                       updated_at = :now,
                       version = version + 1
                 where id = :id
                   and operation_status = 'STARTED'
                   and version = :version
                """,
                Map.of(
                        "resultUri", event.event().eventRef(),
                        "resultHash", event.resultHash(),
                        "now", now,
                        "id", operation.id(),
                        "version", operation.version()));
        if (changed != 1) {
            throw rejected(
                    "INTAKE_BRANCH_OPERATION_STALE",
                    "operation ledger changed before receipt completion");
        }
    }

    private BranchCommitReceipt readCompletedReceipt(
            BranchCommitRequest request, OperationRow operation) {
        if (operation.resultUri() == null
                || operation.resultHash() == null
                || operation.completedAt() == null
                || !operation.resultUri().startsWith(EVENT_REF_PREFIX)) {
            throw rejected(
                    "INTAKE_BRANCH_RECEIPT_MISSING",
                    "completed branch operation has no persisted event receipt");
        }
        String eventId = operation.resultUri().substring(EVENT_REF_PREFIX.length());
        List<StoredEventRow> values = jdbc.query(
                """
                select case_id, sequence_no, event_type, event_json::text as event_json
                  from case_timeline_event
                 where id = :eventId
                """,
                Map.of("eventId", eventId),
                (row, ignored) -> new StoredEventRow(
                        row.getString("case_id"),
                        row.getLong("sequence_no"),
                        row.getString("event_type"),
                        row.getString("event_json")));
        if (values.size() != 1) {
            throw rejected(
                    "INTAKE_BRANCH_RECEIPT_MISSING",
                    "persisted branch event is missing");
        }
        try {
            StoredEventRow stored = values.getFirst();
            JsonNode json = objectMapper.readTree(stored.eventJson());
            if (!json.isObject()) {
                throw rejected(
                        "INTAKE_BRANCH_RECEIPT_INVALID",
                        "persisted branch receipt must be an object");
            }
            ObjectNode hashInput = ((ObjectNode) json).deepCopy();
            JsonNode storedEventHash = hashInput.remove("event_hash");
            if (storedEventHash == null || !storedEventHash.isTextual()) {
                throw rejected(
                        "INTAKE_BRANCH_RECEIPT_HASH_INVALID",
                        "persisted branch receipt has no event hash");
            }
            String eventHash = storedEventHash.asText();
            JsonNode result = json.required("result");
            if (!eventHash.equals(ContractJson.sha256Hex(hashInput))
                    || !operation.resultHash().equals(json.required("result_hash").asText())
                    || !result.isObject()
                    || !operation.resultHash().equals(ContractJson.sha256Hex(result))
                    || !eventId.equals(json.required("event_id").asText())
                    || !operation.resultUri().equals(json.required("event_ref").asText())
                    || !request.envelope().caseId().equals(stored.caseId())
                    || stored.sequence() != json.required("event_sequence").asLong()
                    || !stored.eventType().equals(json.required("event_type").asText())
                    || !request.operation().name().equals(result.required("operation").asText())
                    || !request.envelope().caseId().equals(result.required("case_id").asText())
                    || json.required("process_revision").asLong()
                            != result.required("process_revision").asLong()
                    || json.required("room_revision").asLong()
                            != result.required("room_revision").asLong()) {
                throw rejected(
                    "INTAKE_BRANCH_RECEIPT_HASH_INVALID",
                    "persisted branch receipt hash is invalid");
            }
            validateStoredFrozenSubmission(request, json, result);
            IntakeDomainEventRef event = new IntakeDomainEventRef(
                    "intake-domain-event-ref.v1",
                    json.required("event_id").asText(),
                    json.required("event_ref").asText(),
                    eventHash,
                    json.required("event_sequence").asLong(),
                    IntakeDomainEventType.valueOf(json.required("event_type").asText()),
                    IntakeParty.valueOf(json.required("party").asText()),
                    json.required("command_id").asText(),
                    json.required("tenant_surrogate").asText(),
                    json.required("case_id").asText(),
                    json.required("room_epoch").asLong(),
                    json.required("fencing_token").asLong(),
                    json.required("actor_scope_hash").asText(),
                    json.required("operation_key").asText(),
                    json.required("request_hash").asText(),
                    json.required("result_hash").asText(),
                    json.required("process_revision").asLong(),
                    json.required("room_revision").asLong(),
                    null,
                    null);
            BranchCommitReceipt receipt = new BranchCommitReceipt(
                    "intake-branch-commit-receipt.v1",
                    request.operation(),
                    new OperationReceipt(
                            "intake-operation-receipt.v1",
                            request.operationKey(),
                            request.requestHash(),
                            event.resultHash(),
                            event.processRevision(),
                            event.roomRevision()),
                    event);
            receipt.requireMatches(request);
            return receipt;
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw rejected(
                    "INTAKE_BRANCH_RECEIPT_INVALID",
                    "persisted branch receipt cannot be decoded",
                    failure);
        }
    }

    private void validateStoredFrozenSubmission(
            BranchCommitRequest request, JsonNode event, JsonNode result)
            throws JsonProcessingException {
        JsonNode frozenSubmission = result.get("frozen_submission");
        if (request.operation() != BranchOperation.RESPONDENT_CONFIRM) {
            if (frozenSubmission != null
                    || !"intake-branch-result.v1".equals(
                            result.path("schema_version").asText())) {
                throw rejected(
                        "INTAKE_BRANCH_RECEIPT_INVALID",
                        "non-respondent branch receipt contains frozen Submit authority");
            }
            return;
        }
        String resultSchema = result.path("schema_version").asText();
        if ("intake-branch-result.v1".equals(resultSchema) && frozenSubmission == null) {
            // Historical respondent receipts remain replayable, but cannot authorize hydration.
            return;
        }
        if (!"intake-branch-result.v2".equals(resultSchema)) {
            throw rejected(
                    "INTAKE_BRANCH_RECEIPT_INVALID",
                    "respondent branch receipt has an unsupported frozen Submit schema");
        }
        if (!(frozenSubmission instanceof ObjectNode frozenObject)
                || frozenObject.size() != 2
                || !frozenObject.path("authority").isObject()
                || !frozenObject.path("matrix").isObject()) {
            throw rejected(
                    "INTAKE_BRANCH_RECEIPT_INVALID",
                    "respondent branch receipt has no exact frozen Submit material");
        }
        JsonNode authorityDocument = frozenObject.required("authority");
        JsonNode matrix = frozenObject.required("matrix");
        FrozenIntakeSubmissionAuthority frozen =
                objectMapper.treeToValue(authorityDocument, FrozenIntakeSubmissionAuthority.class);
        if (!ContractJson.canonicalString(authorityDocument)
                        .equals(ContractJson.canonicalString(objectMapper.valueToTree(frozen)))
                || !FrozenIntakeSubmissionAuthority.MATRIX_KIND.equals(
                        result.required("matrix_kind").asText())
                || !frozen.matrixContentHash().equals(
                        result.required("matrix_hash").asText())) {
            throw rejected(
                    "INTAKE_BRANCH_RECEIPT_INVALID",
                    "persisted frozen Submit authority is not canonical");
        }
        frozen.requireMatchesMatrix(matrix);
        frozen.requireProjectionPair(frozen.projectionRef(), frozen.projectionSha256());
        requireFrozenSubmitEventBinding(
                request,
                new EventCoordinates(
                        event.required("event_id").asText(),
                        event.required("event_ref").asText(),
                        event.required("event_sequence").asLong(),
                        IntakeDomainEventType.valueOf(event.required("event_type").asText())),
                new RevisionRows(
                        event.required("process_revision").asLong(),
                        event.required("room_revision").asLong()),
                frozen);
    }

    private long nextEventSequence(String caseId) {
        Long value = jdbc.queryForObject(
                "select coalesce(max(sequence_no), 0) + 1 from case_timeline_event where case_id = :caseId",
                Map.of("caseId", caseId),
                Long.class);
        return Objects.requireNonNull(value, "next event sequence");
    }

    private static RevisionRows expectedRevisions(BranchCommitRequest request) {
        return new RevisionRows(
                Math.addExact(request.envelope().processRevision(), 1),
                Math.addExact(request.envelope().roomRevision(), 1));
    }

    private static EventCoordinates eventCoordinates(
            BranchCommitRequest request, long sequence) {
        String eventId = deterministicId("EVIB_", request.operationKey(), "event");
        return new EventCoordinates(
                eventId,
                EVENT_REF_PREFIX + eventId,
                sequence,
                eventType(request.operation()));
    }

    private static void requireFrozenSubmitBinding(
            BranchCommitRequest request,
            AuthenticatedActor actor,
            EventCoordinates eventCoordinates,
            RevisionRows revisions,
            FrozenIntakeSubmissionAuthority frozen) {
        requireFrozenSubmitEventBinding(request, eventCoordinates, revisions, frozen);
        if (!actor.actorId().equals(frozen.respondentActorId())
                || !actor.role().name().equals(frozen.respondentActorRole().name())) {
            throw rejected(
                    "INTAKE_RESPONDENT_FROZEN_SUBMISSION_INVALID",
                    "respondent Submit actor differs from frozen authority");
        }
    }

    private static void requireFrozenSubmitEventBinding(
            BranchCommitRequest request,
            EventCoordinates eventCoordinates,
            RevisionRows revisions,
            FrozenIntakeSubmissionAuthority frozen) {
        ActivityEnvelope envelope = request.envelope();
        if (request.operation() != BranchOperation.RESPONDENT_CONFIRM
                || envelope.party() != IntakeParty.RESPONDENT
                || !envelope.tenantSurrogate().equals(frozen.tenantSurrogate())
                || !envelope.caseId().equals(frozen.caseId())
                || !request.operationKey().equals(frozen.submitOperationKey())
                || !envelope.commandId().equals(frozen.submitCommandId())
                || envelope.commandSequence() != frozen.submitCommandSequence()
                || !request.requestHash().equals(frozen.submitRequestHash())
                || !eventCoordinates.eventId().equals(frozen.submitEventId())
                || !eventCoordinates.eventRef().equals(frozen.submitEventRef())
                || eventCoordinates.sequence() != frozen.submitEventSequence()
                || !eventCoordinates.eventType().name().equals(frozen.submitEventType())
                || envelope.roomEpoch() != frozen.sourceRoomEpoch()
                || envelope.fencingToken() != frozen.sourceFencingToken()
                || revisions.processRevision() != frozen.sourceProcessRevision()
                || revisions.roomRevision() != frozen.sourceRoomRevision()) {
            throw rejected(
                    "INTAKE_RESPONDENT_FROZEN_SUBMISSION_INVALID",
                    "respondent Submit event differs from frozen matrix authority");
        }
    }

    private MapSqlParameterSource parameters(BranchCommitRequest request) {
        ActivityEnvelope envelope = request.envelope();
        var versions = envelope.pinnedVersions();
        return new MapSqlParameterSource()
                .addValue("tenantSurrogate", envelope.tenantSurrogate())
                .addValue("caseId", envelope.caseId())
                .addValue("roomEpoch", envelope.roomEpoch())
                .addValue("fencingToken", envelope.fencingToken())
                .addValue("commandId", envelope.commandId())
                .addValue("commandSequence", envelope.commandSequence())
                .addValue("actorScopeHash", envelope.actorScopeHash())
                .addValue("expectedProcessRevision", envelope.processRevision())
                .addValue("expectedRoomRevision", envelope.roomRevision())
                .addValue("requestHash", request.requestHash())
                .addValue("operationKey", request.operationKey())
                .addValue("expectedGraphKey", expectedGraphKey)
                .addValue("graphVersion", versions.graphVersion())
                .addValue("checkpointSchemaVersion", versions.checkpointSchemaVersion())
                .addValue("promptVersion", versions.promptVersion())
                .addValue("modelProfileId", versions.modelProfileId())
                .addValue("outputSchemaVersion", versions.outputSchemaVersion())
                .addValue("policyVersion", versions.policyVersion())
                .addValue("guardrailVersion", versions.guardrailVersion())
                .addValue("toolPolicyVersion", versions.toolPolicyVersion());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot encode Intake branch JSON", failure);
        }
    }

    private static IntakeConfirmationCommand requiredConfirmation(
            ResolvedBranchCommand resolved) {
        return Objects.requireNonNull(resolved.confirmation(), "resolved confirmation");
    }

    private static String operationType(BranchOperation operation) {
        return switch (operation) {
            case INITIATOR_ACCEPT -> "INTAKE_INITIATOR_ACCEPT";
            case INITIATOR_REJECT -> "INTAKE_INITIATOR_REJECT";
            case CANCEL -> "INTAKE_CANCEL";
            case RESPONDENT_CONFIRM -> "INTAKE_RESPONDENT_CONFIRM";
        };
    }

    private static IntakeDomainEventType eventType(BranchOperation operation) {
        return switch (operation) {
            case INITIATOR_ACCEPT -> IntakeDomainEventType.INITIATOR_ACCEPTED;
            case INITIATOR_REJECT -> IntakeDomainEventType.NOT_ADMISSIBLE;
            case CANCEL -> IntakeDomainEventType.CANCELLED;
            case RESPONDENT_CONFIRM -> IntakeDomainEventType.RESPONDENT_CONFIRMED;
        };
    }

    private static String deterministicId(
            String prefix, String operationKey, String purpose) {
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

    private record CommandRow(
            String id,
            long sequence,
            String commandType,
            String roomType,
            long roomEpoch,
            String actorId,
            String actorRole,
            String payloadSchema,
            String payloadUri,
            String payloadHash,
            long expectedProcessRevision,
            String requestHash,
            String status,
            String resultUri,
            String resultHash,
            OffsetDateTime appliedAt) {}

    private record AuthorityRows(
            String actorId,
            String actorRole,
            String roomId,
            String roomPhase,
            String initiatorId,
            String initiatorRole,
            String respondentId,
            String respondentRole) {}

    private record ConfirmationDossierRow(String id, long version, String dossierJson) {}

    private record OperationRow(
            String id,
            String caseId,
            String caseCommandId,
            String operationType,
            long roomEpoch,
            long processRevision,
            long fencingToken,
            String requestHash,
            String status,
            String resultUri,
            String resultHash,
            OffsetDateTime completedAt,
            long version) {}

    private record RevisionRows(long processRevision, long roomRevision) {}

    private record EventCoordinates(
            String eventId,
            String eventRef,
            long sequence,
            IntakeDomainEventType eventType) {}

    private record EventReceipt(IntakeDomainEventRef event, String resultHash) {}

    private record StoredEventRow(
            String caseId, long sequence, String eventType, String eventJson) {}
}
