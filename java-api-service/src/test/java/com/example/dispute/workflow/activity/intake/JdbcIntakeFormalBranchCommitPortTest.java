package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.IntakeBranchDomainService;
import com.example.dispute.room.application.IntakeBranchDomainService.BranchResult;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.application.IntakeConfirmationView;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.JdbcIntakeFormalBranchCommitPort;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommandResolver.ResolvedBranchCommand;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL proof for exact-once formal Intake branch commits and replay. */
@Testcontainers
class JdbcIntakeFormalBranchCommitPortTest {

    private static final String DB = "intake_formal_branch";
    private static final String USER = "dispute_test";
    private static final String PASSWORD = "local_test_password";
    private static final Instant NOW = Instant.parse("2026-07-20T09:00:00Z");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
                    DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
            .withEnv("POSTGRES_DB", DB)
            .withEnv("POSTGRES_USER", USER)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());

    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate namedJdbc;
    private static PlatformTransactionManager transactions;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void startDatabase() {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ':'
                + POSTGRES.getMappedPort(5432) + '/' + DB;
        Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DataSource dataSource = new DriverManagerDataSource(url, USER, PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
    }

    @Test
    void acceptedBranchReplaysTheExactReceiptAfterCommandAndProjectionAdvance() {
        Fixture fixture = fixture("ACCEPT", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(fixture);
        Harness harness = harness(fixture);

        BranchCommitReceipt first = harness.port().commit(fixture.request());
        BranchCommitReceipt replay = harness.port().commit(fixture.request());

        assertThat(replay).isEqualTo(first);
        assertThat(scalar("select command_status from case_command where command_id = ?",
                        fixture.envelope().commandId()))
                .isEqualTo("APPLIED");
        assertThat(scalar("select operation_status from domain_operation where operation_key = ?",
                        fixture.request().operationKey()))
                .isEqualTo("COMPLETED");
        assertThat(scalar("select room_phase from case_process_projection where case_id = ?",
                        fixture.caseId()))
                .isEqualTo("WAITING_PARTY");
        assertThat(number("select last_case_event_sequence from case_process_projection where case_id = ?",
                        fixture.caseId()))
                .isEqualTo(1);
        assertThat(scalar("select lifecycle_status from case_room_epoch where case_id = ?",
                        fixture.caseId()))
                .isEqualTo("ACTIVE");
        assertThat(count("select count(*) from case_timeline_event where case_id = ?",
                        fixture.caseId()))
                .isEqualTo(1);
        assertThat(scalar("select title from fulfillment_dispute_case where id = ?", fixture.caseId()))
                .isEqualTo("DOMAIN_INITIATOR_ACCEPT");
        assertCommandAndOperationReceiptsMatch(fixture);
        verify(harness.domainService(), times(1))
                .acceptInitiator(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(
            value = BranchOperation.class,
            names = {"INITIATOR_REJECT", "CANCEL"})
    void terminalInitiatorBranchesCloseTheExactEpochWithoutOpeningAnotherRoom(
            BranchOperation operation) {
        Fixture fixture = fixture(operation.name(), operation);
        insertFixture(fixture);

        BranchCommitReceipt receipt = harness(fixture).port().commit(fixture.request());

        assertThat(receipt.branchOperation()).isEqualTo(operation);
        assertThat(scalar("select lifecycle_status from case_room_epoch where case_id = ?",
                        fixture.caseId()))
                .isEqualTo("TERMINAL");
        assertThat(scalar("select writer_activation_status from case_process_projection where case_id = ?",
                        fixture.caseId()))
                .isEqualTo("TERMINAL");
        assertThat(scalar("select room_phase from case_process_projection where case_id = ?",
                        fixture.caseId()))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                        "select current_room from case_process_projection where case_id = ?",
                        String.class,
                        fixture.caseId()))
                .isNull();
        assertThat(count("select count(*) from case_room where case_id = ? and room_type = 'EVIDENCE'",
                        fixture.caseId()))
                .isZero();
        assertThat(count("select count(*) from case_timeline_event where case_id = ?",
                        fixture.caseId()))
                .isEqualTo(1);
        assertCommandAndOperationReceiptsMatch(fixture);
    }

    @Test
    void sameOperationKeyWithAnotherRequestHashCannotReplayOrWrite() {
        Fixture fixture = fixture("CONFLICT", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(fixture);
        Harness harness = harness(fixture);
        BranchCommitReceipt committed = harness.port().commit(fixture.request());
        BranchCommitRequest conflict = new BranchCommitRequest(
                "intake-branch-commit-request.v1",
                fixture.envelope(),
                fixture.operation(),
                fixture.request().operationKey(),
                sha256("conflicting-request"));

        assertThatThrownBy(() -> harness.port().commit(conflict))
                .isInstanceOf(IntakeFinalizationRejectedException.class);
        assertThat(committed).isNotNull();
        assertThat(count("select count(*) from domain_operation where case_id = ?", fixture.caseId()))
                .isEqualTo(1);
        assertThat(count("select count(*) from case_timeline_event where case_id = ?",
                        fixture.caseId()))
                .isEqualTo(1);
    }

    @Test
    void staleFenceWrongActorAndNotReadyAuthorityWriteNothing() {
        Fixture stale = fixture("STALE", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(stale);
        ActivityEnvelope staleEnvelope = envelopeWithFence(stale.envelope(), 3);
        BranchCommitRequest staleRequest = new BranchCommitRequest(
                "intake-branch-commit-request.v1",
                staleEnvelope,
                stale.operation(),
                operationKey(stale.operation(), staleEnvelope),
                stale.request().requestHash());
        assertRejectedWithNoWrites(stale, harness(stale), staleRequest);

        Fixture wrongActor = fixture("WRONG_ACTOR", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(wrongActor);
        jdbc.update(
                "update case_command set actor_role = 'MERCHANT' where command_id = ?",
                wrongActor.envelope().commandId());
        assertRejectedWithNoWrites(wrongActor, harness(wrongActor), wrongActor.request());

        Fixture notReady = fixture("NOT_READY", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(notReady);
        jdbc.update(
                "update case_process_projection set room_phase = 'OPEN' where case_id = ?",
                notReady.caseId());
        assertRejectedWithNoWrites(notReady, harness(notReady), notReady.request());
    }

    @Test
    void failureAfterDomainMutationRollsBackDomainEpochEventCommandAndOperation() {
        Fixture fixture = fixture("ROLLBACK", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(fixture);
        Harness harness = harness(fixture);
        when(harness.dispute().getCaseStatus())
                .thenThrow(new IllegalStateException("failure after domain mutation"));

        assertThatThrownBy(() -> harness.port().commit(fixture.request()))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .hasMessageContaining("failure after domain mutation");

        assertThat(scalar("select title from fulfillment_dispute_case where id = ?", fixture.caseId()))
                .isEqualTo("Branch fixture");
        assertThat(scalar("select command_status from case_command where command_id = ?",
                        fixture.envelope().commandId()))
                .isEqualTo("ORCHESTRATION_ACCEPTED");
        assertThat(number("select process_revision from case_room_epoch where case_id = ?",
                        fixture.caseId()))
                .isEqualTo(5);
        assertThat(number("select process_revision from case_process_projection where case_id = ?",
                        fixture.caseId()))
                .isEqualTo(5);
        assertThat(count("select count(*) from domain_operation where case_id = ?", fixture.caseId()))
                .isZero();
        assertThat(count("select count(*) from case_timeline_event where case_id = ?",
                        fixture.caseId()))
                .isZero();
    }

    @Test
    void respondentConfirmationRemainsFailClosedBehindTheErratumGate() {
        Fixture fixture = fixture("RESPONDENT", BranchOperation.RESPONDENT_CONFIRM);
        insertFixture(fixture);
        insertInitiatorCompletion(fixture);

        assertThatThrownBy(() -> harness(fixture).port().commit(fixture.request()))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(
                                ((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo("INTAKE_RESPONDENT_DELTA_GATE_PENDING"));

        assertThat(count("select count(*) from domain_operation where case_id = ?", fixture.caseId()))
                .isZero();
        assertThat(count("select count(*) from case_timeline_event where case_id = ?",
                        fixture.caseId()))
                .isZero();
        assertThat(scalar("select command_status from case_command where command_id = ?",
                        fixture.envelope().commandId()))
                .isEqualTo("ORCHESTRATION_ACCEPTED");
    }

    private static Harness harness(Fixture fixture) {
        FulfillmentCaseRepository caseRepository = mock(FulfillmentCaseRepository.class);
        CaseRoomRepository roomRepository = mock(CaseRoomRepository.class);
        IntakeBranchDomainService domainService = mock(IntakeBranchDomainService.class);
        FulfillmentCaseEntity dispute = mock(FulfillmentCaseEntity.class);
        CaseRoomEntity room = mock(CaseRoomEntity.class);
        when(caseRepository.findByIdForUpdate(fixture.caseId())).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(fixture.caseId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        CaseStatus resultStatus = switch (fixture.operation()) {
            case INITIATOR_ACCEPT, RESPONDENT_CONFIRM -> CaseStatus.INTAKE_COMPLETED;
            case INITIATOR_REJECT -> CaseStatus.NOT_ADMISSIBLE;
            case CANCEL -> CaseStatus.CANCELLED;
        };
        String currentRoom = switch (fixture.operation()) {
            case INITIATOR_ACCEPT, RESPONDENT_CONFIRM -> RoomType.INTAKE.name();
            case INITIATOR_REJECT, CANCEL -> null;
        };
        when(dispute.getCaseStatus()).thenReturn(resultStatus);
        when(dispute.getCurrentRoom()).thenReturn(currentRoom);
        when(dispute.getCurrentDeadlineAt()).thenReturn(null);
        BranchResult result = new BranchResult(
                new IntakeConfirmationView(
                        fixture.caseId(), resultStatus, currentRoom == null ? null : RoomType.INTAKE, null),
                fixture.roomId(),
                null,
                fixture.operation() == BranchOperation.INITIATOR_ACCEPT
                        ? "INITIATOR_FROZEN"
                        : null,
                fixture.operation() == BranchOperation.INITIATOR_ACCEPT
                        ? "d".repeat(64)
                        : null);
        switch (fixture.operation()) {
            case INITIATOR_ACCEPT -> {
                doAnswer(ignored -> {
                            applyDomainMutation(fixture, resultStatus, currentRoom);
                            return result;
                        })
                        .when(domainService)
                        .acceptInitiator(any(), any(), any(), any(), any());
                when(domainService.requireFormalInitiatorMatrix(dispute))
                        .thenReturn(new IntakeBranchDomainService.ObjectNodeAuthority(
                                "INITIATOR_FROZEN", "d".repeat(64)));
            }
            case INITIATOR_REJECT -> doAnswer(ignored -> {
                        applyDomainMutation(fixture, resultStatus, currentRoom);
                        return result;
                    })
                    .when(domainService)
                    .rejectInitiator(any(), any(), any(), any(), any());
            case CANCEL -> doAnswer(ignored -> {
                        applyDomainMutation(fixture, resultStatus, currentRoom);
                        return result;
                    })
                    .when(domainService)
                    .cancel(any(), any(), any(), any(), any());
            case RESPONDENT_CONFIRM -> when(domainService.requireFormalBilateralMatrix(dispute))
                    .thenReturn(new IntakeBranchDomainService.ObjectNodeAuthority(
                            "BILATERAL_FROZEN", "e".repeat(64)));
        }
        JdbcIntakeFormalBranchCommitPort port = new JdbcIntakeFormalBranchCommitPort(
                namedJdbc,
                transactions,
                caseRepository,
                roomRepository,
                domainService,
                request -> fixture.resolved(),
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Harness(port, domainService, dispute);
    }

    private static void applyDomainMutation(
            Fixture fixture, CaseStatus status, String currentRoom) {
        jdbc.update(
                "update fulfillment_dispute_case set case_status = ?, current_room = ?, title = ? where id = ?",
                status.name(),
                currentRoom,
                "DOMAIN_" + fixture.operation().name(),
                fixture.caseId());
        if (currentRoom == null) {
            jdbc.update(
                    "update case_room set room_status = 'CLOSED', closed_at = ?, updated_at = ? where id = ?",
                    NOW.atOffset(ZoneOffset.UTC),
                    NOW.atOffset(ZoneOffset.UTC),
                    fixture.roomId());
        }
    }

    private static void assertRejectedWithNoWrites(
            Fixture fixture, Harness harness, BranchCommitRequest request) {
        assertThatThrownBy(() -> harness.port().commit(request))
                .isInstanceOf(IntakeFinalizationRejectedException.class);
        assertThat(count("select count(*) from domain_operation where case_id = ?", fixture.caseId()))
                .isZero();
        assertThat(count("select count(*) from case_timeline_event where case_id = ?",
                        fixture.caseId()))
                .isZero();
        assertThat(scalar("select command_status from case_command where command_id = ?",
                        fixture.envelope().commandId()))
                .isEqualTo("ORCHESTRATION_ACCEPTED");
    }

    private static void assertCommandAndOperationReceiptsMatch(Fixture fixture) {
        String commandUri = scalar(
                "select result_uri from case_command where command_id = ?",
                fixture.envelope().commandId());
        String operationUri = scalar(
                "select result_uri from domain_operation where operation_key = ?",
                fixture.request().operationKey());
        String commandHash = scalar(
                "select result_sha256 from case_command where command_id = ?",
                fixture.envelope().commandId());
        String operationHash = scalar(
                "select result_sha256 from domain_operation where operation_key = ?",
                fixture.request().operationKey());
        assertThat(commandUri).isEqualTo(operationUri);
        assertThat(commandHash).isEqualTo(operationHash);
    }

    private static Fixture fixture(String label, BranchOperation operation) {
        int sequence = SEQUENCE.incrementAndGet();
        String suffix = label + '_' + sequence;
        String caseId = "CASE_BRANCH_" + suffix;
        String tenant = "tenant-branch";
        String roomId = "ROOM_BRANCH_" + suffix;
        String commandId = "COMMAND_BRANCH_" + suffix;
        String actorId = operation == BranchOperation.RESPONDENT_CONFIRM
                ? "merchant-branch-" + sequence
                : "user-branch-" + sequence;
        String actorRole = operation == BranchOperation.RESPONDENT_CONFIRM ? "MERCHANT" : "USER";
        IntakeParty party = operation == BranchOperation.RESPONDENT_CONFIRM
                ? IntakeParty.RESPONDENT
                : IntakeParty.INITIATOR;
        IntakeCommandType commandType = operation == BranchOperation.CANCEL
                ? IntakeCommandType.INTAKE_CANCEL
                : IntakeCommandType.INTAKE_CONFIRM;
        String payloadRef = "urn:intake:branch-command:" + suffix;
        String payloadHash = sha256("payload:" + suffix);
        String requestHash = sha256("request:" + suffix);
        ActivityEnvelope envelope = new ActivityEnvelope(
                "intake-activity-envelope.v1",
                tenant,
                caseId,
                1,
                2,
                commandId,
                1,
                commandType,
                party,
                sha256("scope:" + suffix),
                payloadRef,
                payloadHash,
                5,
                3,
                NOW.plusSeconds(300).toEpochMilli(),
                new RetryBudget("intake-retry-budget.v1", 2, 1, 1),
                new PinnedVersions(
                        "intake-pinned-versions.v1",
                        "intake-room-build.v1",
                        "2.0.0",
                        "intake-checkpoint.v2",
                        "intake-prompt.v2",
                        "intake-model.synthetic.v1",
                        "intake-turn-proposal.v2",
                        "intake-policy.v2",
                        "intake-guardrail.v2",
                        "no-tools.v1"));
        String operationKey = operationKey(operation, envelope);
        BranchCommitRequest request = new BranchCommitRequest(
                "intake-branch-commit-request.v1",
                envelope,
                operation,
                operationKey,
                requestHash);
        IntakeConfirmationCommand confirmation = operation == BranchOperation.CANCEL
                ? null
                : new IntakeConfirmationCommand(
                        operation != BranchOperation.INITIATOR_REJECT,
                        "REFUND_DISPUTE",
                        RiskLevel.MEDIUM,
                        null);
        ResolvedBranchCommand resolved = new ResolvedBranchCommand(
                operation,
                "intake-branch-command.v1",
                payloadRef,
                payloadHash,
                confirmation,
                operation == BranchOperation.CANCEL ? "resolved before admission" : null);
        return new Fixture(
                caseId,
                tenant,
                roomId,
                actorId,
                actorRole,
                operation,
                envelope,
                request,
                resolved);
    }

    private static ActivityEnvelope envelopeWithFence(ActivityEnvelope source, long fence) {
        return new ActivityEnvelope(
                source.schemaVersion(),
                source.tenantSurrogate(),
                source.caseId(),
                source.roomEpoch(),
                fence,
                source.commandId(),
                source.commandSequence(),
                source.commandType(),
                source.party(),
                source.actorScopeHash(),
                source.commandPayloadRef(),
                source.commandPayloadHash(),
                source.processRevision(),
                source.roomRevision(),
                source.deadlineEpochMillis(),
                source.retryBudget(),
                source.pinnedVersions(),
                source.invocation());
    }

    private static String operationKey(BranchOperation operation, ActivityEnvelope envelope) {
        return switch (operation) {
            case INITIATOR_ACCEPT -> IntakeOperationKeys.initiatorAccept(
                    envelope.caseId(), envelope.roomEpoch(), envelope.commandId());
            case INITIATOR_REJECT -> IntakeOperationKeys.initiatorReject(
                    envelope.caseId(), envelope.roomEpoch(), envelope.commandId());
            case CANCEL -> IntakeOperationKeys.cancel(
                    envelope.caseId(), envelope.roomEpoch(), envelope.commandId());
            case RESPONDENT_CONFIRM -> IntakeOperationKeys.respondentConfirm(
                    envelope.caseId(), envelope.roomEpoch(), envelope.commandId());
        };
    }

    private static void insertFixture(Fixture fixture) {
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        String epochId = "EPOCH_" + fixture.caseId();
        String userId = fixture.operation() == BranchOperation.RESPONDENT_CONFIRM
                ? "initiator-" + fixture.caseId()
                : fixture.actorId();
        String merchantId = fixture.operation() == BranchOperation.RESPONDENT_CONFIRM
                ? fixture.actorId()
                : "respondent-" + fixture.caseId();
        String caseStatus = fixture.operation() == BranchOperation.RESPONDENT_CONFIRM
                ? "INTAKE_COMPLETED"
                : "INTAKE_IN_PROGRESS";
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key, case_type,
                    case_status, initiator_role, initiator_id, respondent_role, respondent_id,
                    risk_level, title, description, current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', ?, 'USER', ?, 'MERCHANT', ?,
                    'MEDIUM', 'Branch fixture', 'formal branch fixture', 'INTAKE', 'test', 'test')
                """,
                fixture.caseId(),
                userId,
                merchantId,
                "create-" + fixture.caseId(),
                caseStatus,
                userId,
                merchantId);
        jdbc.update(
                "insert into case_room (id, case_id, room_type, room_status, opened_at, created_by, updated_by) values (?, ?, 'INTAKE', 'OPEN', ?, 'test', 'test')",
                fixture.roomId(),
                fixture.caseId(),
                now);
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase, writer_mode,
                    writer_activation_status, process_revision, room_epoch, fencing_token,
                    last_command_sequence, last_case_event_sequence, temporal_workflow_id,
                    temporal_run_id, temporal_build_id, projected_at, updated_at
                ) values (?, ?, ?, 'INTAKE', 'READY_TO_CONFIRM', 'TEMPORAL', 'READY',
                    5, 1, 2, 0, 0, ?, ?, 'branch-build', ?, ?)
                """,
                fixture.caseId(),
                fixture.tenant(),
                caseStatus,
                "CASE_WORKFLOW_" + fixture.caseId(),
                "CASE_RUN_" + fixture.caseId(),
                now,
                now);
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch, writer_mode,
                    lifecycle_status, provisioning_status, process_revision, room_revision,
                    fencing_token, temporal_workflow_id, temporal_run_id, room_temporal_workflow_id,
                    room_temporal_run_id, temporal_build_id, graph_key, graph_version,
                    checkpoint_schema_version, stream_protocol, selection_schema_version,
                    process_contract_version, workflow_type, room_workflow_type, room_workflow_build_id,
                    activated_at, provisioned_at, created_at, updated_at
                ) values (?, ?, ?, ?, 'INTAKE', 1, 'TEMPORAL', 'ACTIVE', 'READY', 5, 3, 2,
                    ?, ?, ?, ?, 'branch-build', 'intake.v2', '2.0.0', 'intake-checkpoint.v2',
                    'agent-stream.v2', 'room-epoch-selection.v2', 'case-process-contract.v1',
                    'CaseProcessWorkflow', 'IntakeRoomWorkflow', 'intake-room-build.v1', ?, ?, ?, ?)
                """,
                epochId,
                fixture.tenant(),
                fixture.caseId(),
                fixture.roomId(),
                "CASE_WORKFLOW_" + fixture.caseId(),
                "CASE_RUN_" + fixture.caseId(),
                "ROOM_WORKFLOW_" + fixture.caseId(),
                "ROOM_RUN_" + fixture.caseId(),
                now,
                now,
                now,
                now);
        jdbc.update(
                """
                insert into case_intake_graph_thread_binding (
                    registration_id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, fencing_token, thread_id, actor_id, actor_role, audience,
                    actor_capabilities_json, actor_scope_hash, agent_session_id, graph_key,
                    graph_version, checkpoint_schema_version, state_schema_version, prompt_version,
                    model_profile_id, output_schema_version, policy_version, guardrail_version,
                    tool_policy_version, writer_mode, registration_hash, registration_status,
                    issued_at, registered_at, created_at
                ) values (?, 'graph-private-thread-registration.v1', ?, ?, 'INTAKE', 1, 2,
                    ?, ?, ?, ?, '["graph.command.execute"]'::jsonb, ?, ?, 'intake.v2',
                    '2.0.0', 'intake-checkpoint.v2', 'intake-graph-state.v2', 'intake-prompt.v2',
                    'intake-model.synthetic.v1', 'intake-turn-proposal.v2', 'intake-policy.v2',
                    'intake-guardrail.v2', 'no-tools.v1', 'TEMPORAL', ?, 'REGISTERED', ?, ?, ?)
                """,
                "REG_" + fixture.caseId(),
                fixture.tenant(),
                fixture.caseId(),
                "grt.v1." + sha256(fixture.caseId()).substring(0, 32),
                fixture.actorId(),
                fixture.actorRole(),
                fixture.actorRole(),
                fixture.envelope().actorScopeHash(),
                "SESSION_" + fixture.caseId(),
                sha256("registration:" + fixture.caseId()),
                now,
                now,
                now);
        jdbc.update(
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id, case_command_sequence,
                    command_type, room_type, room_epoch, actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256, payload_size_bytes,
                    expected_process_revision, occurred_at, deadline_at, traceparent, request_hash,
                    command_status, accepted_at, orchestrated_at, created_at, updated_at
                ) values (?, ?, ?, ?, 1, ?, 'INTAKE', 1, ?, ?, '["INTAKE_PARTICIPATE"]'::jsonb,
                    'intake-branch-command.v1', ?, ?, 512, 5, ?, ?,
                    '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01', ?,
                    'ORCHESTRATION_ACCEPTED', ?, ?, ?, ?)
                """,
                "CMD_" + fixture.caseId(),
                fixture.envelope().commandId(),
                fixture.tenant(),
                fixture.caseId(),
                fixture.envelope().commandType().name(),
                fixture.actorId(),
                fixture.actorRole(),
                fixture.envelope().commandPayloadRef(),
                fixture.envelope().commandPayloadHash(),
                now.minusMinutes(1),
                now.plusMinutes(5),
                fixture.request().requestHash(),
                now,
                now,
                now,
                now);
    }

    private static void insertInitiatorCompletion(Fixture fixture) {
        String initiator = scalar(
                "select initiator_id from fulfillment_dispute_case where id = ?",
                fixture.caseId());
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into case_intake_party_completion (
                    id, case_id, participant_role, participant_id, completion_status,
                    completed_at, created_at, created_by
                ) values (?, ?, 'USER', ?, 'COMPLETED', ?, ?, 'test')
                """,
                "COMP_" + fixture.caseId(),
                fixture.caseId(),
                initiator,
                now,
                now);
    }

    private static long count(String sql, Object... values) {
        return jdbc.queryForObject(sql, Long.class, values);
    }

    private static long number(String sql, Object... values) {
        return jdbc.queryForObject(sql, Long.class, values);
    }

    private static String scalar(String sql, Object... values) {
        return jdbc.queryForObject(sql, String.class, values);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 must be available", impossible);
        }
    }

    private record Fixture(
            String caseId,
            String tenant,
            String roomId,
            String actorId,
            String actorRole,
            BranchOperation operation,
            ActivityEnvelope envelope,
            BranchCommitRequest request,
            ResolvedBranchCommand resolved) {}

    private record Harness(
            JdbcIntakeFormalBranchCommitPort port,
            IntakeBranchDomainService domainService,
            FulfillmentCaseEntity dispute) {}
}
