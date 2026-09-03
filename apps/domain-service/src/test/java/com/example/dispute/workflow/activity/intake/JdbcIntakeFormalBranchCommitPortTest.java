package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.IntakeBranchDomainService;
import com.example.dispute.room.application.IntakeBranchDomainService.BranchResult;
import com.example.dispute.room.application.IntakeBranchDomainService.TimelineEventMode;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.application.IntakeConfirmationView;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.JdbcIntakeFormalBranchCommitPort;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.workflow.application.intake.IntakeFinalizationPersistenceException;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommandResolver.ResolvedBranchCommand;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes;
import com.example.dispute.workflow.contract.v1.FrozenIntakeSubmissionAuthority;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.CannotCreateTransactionException;
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
    void emptyRemarkConfirmationFinalizesHandoffAndReplaysWithoutMessages() throws Exception {
        Fixture fixture = fixture("ACCEPT", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(fixture);
        Harness harness = harness(fixture);

        BranchCommitReceipt first = harness.port().commit(fixture.request());
        String finalizedDossier = scalar(
                "select dossier_json::text from case_intake_dossier where case_id = ?",
                fixture.caseId());
        BranchCommitReceipt replay = harness.port().commit(fixture.request());
        BranchCommitReceipt reconciled =
                harness.port().commit(reconciliationRequest(fixture.request()));
        BranchCommitRequest conflict = new BranchCommitRequest(
                "intake-branch-commit-request.v1",
                fixture.envelope(),
                fixture.operation(),
                fixture.request().operationKey(),
                sha256("changed-confirmation-authority"));

        assertThat(replay).isEqualTo(first);
        assertThat(reconciled).isEqualTo(first);
        assertThatThrownBy(() -> harness.port().commit(conflict))
                .isInstanceOf(IntakeFinalizationRejectedException.class);
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
        assertThat(count("select count(*) from case_intake_party_completion where case_id = ?",
                        fixture.caseId()))
                .isEqualTo(1);
        assertThat(count("select count(*) from room_message where case_id = ?", fixture.caseId()))
                .isZero();
        assertThat(number("select dossier_version from case_intake_dossier where case_id = ?",
                        fixture.caseId()))
                .isEqualTo(4);
        assertThat(scalar("select dossier_json::text from case_intake_dossier where case_id = ?",
                        fixture.caseId()))
                .isEqualTo(finalizedDossier);
        assertThat(objectMapper.readTree(finalizedDossier)
                        .at("/party_intake_state/USER/handoff_notes/remark_status")
                        .asText())
                .isEqualTo("NO_EXTRA_REMARKS");
        assertThat(objectMapper.readTree(finalizedDossier)
                        .at("/party_intake_state/USER/handoff_notes/latest_remark")
                        .asText())
                .isEqualTo("无额外备注。");
        assertThat(objectMapper.readTree(finalizedDossier)
                        .at("/party_intake_state/USER/handoff_notes/remarks")
                        .size())
                .isZero();
        assertThat(objectMapper.readTree(finalizedDossier).path("handoff_notes"))
                .isEqualTo(objectMapper.readTree(finalizedDossier)
                        .at("/party_intake_state/USER/handoff_notes"));
        assertThat(objectMapper.readTree(finalizedDossier)
                        .at("/handoff_remark_partition/parties/USER/source/source_kind")
                        .asText())
                .isEqualTo("FORMAL_CONFIRMATION");
        assertThat(objectMapper.readTree(finalizedDossier)
                        .at("/handoff_remark_partition/parties/USER/source/command_id")
                        .asText())
                .isEqualTo(fixture.envelope().commandId());
        assertThat(objectMapper.readTree(finalizedDossier)
                        .at("/handoff_remark_partition/parties/USER/source/request_hash")
                        .asText())
                .isEqualTo(fixture.request().requestHash());
        assertThat(objectMapper.readTree(finalizedDossier)
                        .at("/handoff_remark_partition/parties/USER/latest_remark")
                        .asText())
                .isEmpty();
        assertThat(scalar("select title from fulfillment_dispute_case where id = ?", fixture.caseId()))
                .isEqualTo("DOMAIN_INITIATOR_ACCEPT");
        assertCommandAndOperationReceiptsMatch(fixture);
        verify(harness.domainService(), times(1))
                .acceptInitiator(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(TimelineEventMode.FORMAL_TYPED_ONLY));

        Fixture withRemark = fixture("ACCEPT_WITH_REMARK", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(withRemark);
        insertConfirmationDossier(withRemark, "HAS_REMARKS", "请书记官核对物流节点。");
        Harness remarkHarness = harness(withRemark);
        String existingDossier = scalar(
                "select dossier_json::text from case_intake_dossier where case_id = ?",
                withRemark.caseId());

        remarkHarness.port().commit(withRemark.request());

        assertThat(number("select dossier_version from case_intake_dossier where case_id = ?",
                        withRemark.caseId()))
                .isEqualTo(3);
        assertThat(scalar("select dossier_json::text from case_intake_dossier where case_id = ?",
                        withRemark.caseId()))
                .isEqualTo(existingDossier);
        assertThat(count("select count(*) from room_message where case_id = ?", withRemark.caseId()))
                .isZero();

        Fixture withoutRemark = fixture("ACCEPT_WITHOUT_REMARK", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(withoutRemark);
        insertConfirmationDossier(withoutRemark, "NO_EXTRA_REMARKS", null);
        Harness noRemarkHarness = harness(withoutRemark);
        String existingNoRemarkDossier = scalar(
                "select dossier_json::text from case_intake_dossier where case_id = ?",
                withoutRemark.caseId());

        noRemarkHarness.port().commit(withoutRemark.request());

        assertThat(number("select dossier_version from case_intake_dossier where case_id = ?",
                        withoutRemark.caseId()))
                .isEqualTo(3);
        assertThat(scalar("select dossier_json::text from case_intake_dossier where case_id = ?",
                        withoutRemark.caseId()))
                .isEqualTo(existingNoRemarkDossier);
        assertThat(count("select count(*) from room_message where case_id = ?", withoutRemark.caseId()))
                .isZero();

        Fixture legacyWaiting = fixture("ACCEPT_LEGACY_WAITING", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(legacyWaiting);
        jdbc.update(
                "update case_intake_dossier set dossier_json = dossier_json - 'handoff_remark_partition' where case_id = ?",
                legacyWaiting.caseId());
        Harness legacyWaitingHarness = harness(legacyWaiting);

        legacyWaitingHarness.port().commit(legacyWaiting.request());

        String createdPartitionDossier = scalar(
                "select dossier_json::text from case_intake_dossier where case_id = ?",
                legacyWaiting.caseId());
        assertThat(objectMapper.readTree(createdPartitionDossier)
                        .at("/handoff_remark_partition/parties/USER/source/source_kind")
                        .asText())
                .isEqualTo("FORMAL_CONFIRMATION");
        assertThat(objectMapper.readTree(createdPartitionDossier)
                        .at("/handoff_remark_partition/parties/MERCHANT/remark_status")
                        .asText())
                .isEqualTo("NOT_READY");
        assertThat(count("select count(*) from room_message where case_id = ?", legacyWaiting.caseId()))
                .isZero();

        Fixture mismatchedNoRemark = fixture(
                "ACCEPT_MISMATCHED_NO_REMARK",
                BranchOperation.INITIATOR_ACCEPT,
                "另一条确认备注");
        insertFixture(mismatchedNoRemark);
        insertConfirmationDossier(mismatchedNoRemark, "NO_EXTRA_REMARKS", null);
        Harness mismatchedNoRemarkHarness = harness(mismatchedNoRemark);

        assertRejectedWithNoWrites(
                mismatchedNoRemark, mismatchedNoRemarkHarness, mismatchedNoRemark.request());
        assertThat(number("select dossier_version from case_intake_dossier where case_id = ?",
                        mismatchedNoRemark.caseId()))
                .isEqualTo(3);
        assertThat(count("select count(*) from case_intake_party_completion where case_id = ?",
                        mismatchedNoRemark.caseId()))
                .isZero();

        Fixture mismatchedRemark = fixture(
                "ACCEPT_MISMATCHED_REMARK",
                BranchOperation.INITIATOR_ACCEPT,
                "另一条确认备注");
        insertFixture(mismatchedRemark);
        insertConfirmationDossier(
                mismatchedRemark, "HAS_REMARKS", "请书记官核对物流节点。");
        Harness mismatchedHarness = harness(mismatchedRemark);

        assertRejectedWithNoWrites(
                mismatchedRemark, mismatchedHarness, mismatchedRemark.request());
        assertThat(number("select dossier_version from case_intake_dossier where case_id = ?",
                        mismatchedRemark.caseId()))
                .isEqualTo(3);
        assertThat(count("select count(*) from case_intake_party_completion where case_id = ?",
                        mismatchedRemark.caseId()))
                .isZero();

        Fixture unsupported = fixture("ACCEPT_UNSUPPORTED", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(unsupported);
        insertConfirmationDossier(unsupported, "READY_PENDING_REMARK_INVITE", null);
        Harness unsupportedHarness = harness(unsupported);

        assertRejectedWithNoWrites(unsupported, unsupportedHarness, unsupported.request());
        assertThat(number("select dossier_version from case_intake_dossier where case_id = ?",
                        unsupported.caseId()))
                .isEqualTo(3);
        assertThat(count("select count(*) from case_intake_party_completion where case_id = ?",
                        unsupported.caseId()))
                .isZero();
        assertThat(count("select count(*) from room_message where case_id = ?", unsupported.caseId()))
                .isZero();

        Fixture missing = fixture("ACCEPT_MISSING_HANDOFF", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(missing);
        jdbc.update("delete from case_intake_dossier where case_id = ?", missing.caseId());
        Harness missingHarness = harness(missing);

        assertRejectedWithNoWrites(missing, missingHarness, missing.request());
        assertThat(count("select count(*) from case_intake_party_completion where case_id = ?",
                        missing.caseId()))
                .isZero();

        Fixture foreign = fixture("ACCEPT_FOREIGN_REMARK", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(foreign);
        insertConfirmationDossier(foreign, "HAS_REMARKS", "请书记官核对物流节点。");
        jdbc.update(
                """
                update case_intake_dossier
                   set dossier_json = jsonb_set(
                       jsonb_set(
                           dossier_json,
                           '{party_intake_state,USER,handoff_notes,remarks,0,role}',
                           '"MERCHANT"'::jsonb),
                       '{handoff_notes,remarks,0,role}',
                       '"MERCHANT"'::jsonb)
                 where case_id = ?
                """,
                foreign.caseId());
        Harness foreignHarness = harness(foreign);

        assertRejectedWithNoWrites(foreign, foreignHarness, foreign.request());
        assertThat(number("select dossier_version from case_intake_dossier where case_id = ?",
                        foreign.caseId()))
                .isEqualTo(3);
        assertThat(count("select count(*) from case_intake_party_completion where case_id = ?",
                        foreign.caseId()))
                .isZero();
        assertThat(count("select count(*) from room_message where case_id = ?", foreign.caseId()))
                .isZero();
    }

    @Test
    void reconciliationReturnsNullOnlyWhenTheReceiptLedgerIsAbsentAndWritesNothing() {
        Fixture fixture = fixture("RECONCILE_ABSENT", BranchOperation.INITIATOR_ACCEPT);
        Harness harness = harness(fixture);

        BranchCommitReceipt receipt =
                harness.port().commit(reconciliationRequest(fixture.request()));

        assertThat(receipt).isNull();
        assertThat(count("select count(*) from domain_operation where operation_key = ?",
                        fixture.request().operationKey()))
                .isZero();
        assertThat(count("select count(*) from case_timeline_event where case_id = ?",
                        fixture.caseId()))
                .isZero();
        verifyNoInteractions(harness.domainService());
    }

    @Test
    void reconciliationTreatsAnExistingStartedOperationAsRetryableUnresolved() {
        Fixture fixture = fixture("RECONCILE_STARTED", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(fixture);
        insertStartedOperation(fixture);
        Harness harness = harness(fixture);

        assertThatThrownBy(
                        () -> harness.port().commit(reconciliationRequest(fixture.request())))
                .isInstanceOf(IntakeFinalizationPersistenceException.class)
                .hasMessageContaining("without a committed receipt");

        assertThat(scalar("select operation_status from domain_operation where operation_key = ?",
                        fixture.request().operationKey()))
                .isEqualTo("STARTED");
        assertThat(scalar("select command_status from case_command where command_id = ?",
                        fixture.envelope().commandId()))
                .isEqualTo("ORCHESTRATION_ACCEPTED");
        assertThat(count("select count(*) from case_timeline_event where case_id = ?",
                        fixture.caseId()))
                .isZero();
        verifyNoInteractions(harness.domainService());
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
    void cancellationAllowsEitherInitiatorCompletionStateButNoRespondentCompletion() {
        Fixture completedInitiator = fixture("CANCEL_AFTER_INITIATOR", BranchOperation.CANCEL);
        insertFixture(completedInitiator);
        insertInitiatorCompletion(completedInitiator);

        BranchCommitReceipt receipt =
                harness(completedInitiator).port().commit(completedInitiator.request());

        assertThat(receipt.branchOperation()).isEqualTo(BranchOperation.CANCEL);
        assertThat(count("select count(*) from case_timeline_event where case_id = ?",
                        completedInitiator.caseId()))
                .isEqualTo(1);

        Fixture completedRespondent = fixture("CANCEL_AFTER_RESPONDENT", BranchOperation.CANCEL);
        insertFixture(completedRespondent);
        insertRespondentCompletion(completedRespondent);

        assertRejectedWithNoWrites(
                completedRespondent, harness(completedRespondent), completedRespondent.request());
    }

    @ParameterizedTest
    @EnumSource(
            value = BranchOperation.class,
            names = {"INITIATOR_ACCEPT", "INITIATOR_REJECT"})
    void initiatorDecisionsRequireBothPartiesToRemainIncomplete(BranchOperation operation) {
        Fixture completedInitiator = fixture("DECISION_INITIATOR_" + operation, operation);
        insertFixture(completedInitiator);
        insertInitiatorCompletion(completedInitiator);
        assertRejectedWithNoWrites(
                completedInitiator, harness(completedInitiator), completedInitiator.request());

        Fixture completedRespondent = fixture("DECISION_RESPONDENT_" + operation, operation);
        insertFixture(completedRespondent);
        insertRespondentCompletion(completedRespondent);
        assertRejectedWithNoWrites(
                completedRespondent, harness(completedRespondent), completedRespondent.request());
    }

    @Test
    void respondentConfirmationRequiresAnInitiatorCompletionBeforeTheDeltaGate() {
        Fixture fixture = fixture("RESPONDENT_WITHOUT_INITIATOR", BranchOperation.RESPONDENT_CONFIRM);
        insertFixture(fixture);

        assertThatThrownBy(() -> harness(fixture).port().commit(fixture.request()))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(
                                ((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo("INTAKE_RESPONDENT_AUTHORITY_REJECTED"));

        assertThat(count("select count(*) from domain_operation where case_id = ?", fixture.caseId()))
                .isZero();
    }

    @Test
    void retryableAndDeterministicDatabaseFailuresKeepDistinctTemporalSemantics() {
        Fixture retryable = fixture("RETRYABLE_DATABASE", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(retryable);
        Harness retryableHarness = harness(retryable);
        doThrow(new TransientDataAccessResourceException("database connection lost"))
                .when(retryableHarness.domainService())
                .acceptInitiator(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> retryableHarness.port().commit(retryable.request()))
                .isInstanceOf(IntakeFinalizationPersistenceException.class);
        assertThat(count("select count(*) from domain_operation where case_id = ?", retryable.caseId()))
                .isZero();

        Fixture invariant = fixture("DATABASE_INVARIANT", BranchOperation.INITIATOR_ACCEPT);
        insertFixture(invariant);
        Harness invariantHarness = harness(invariant);
        doThrow(new DataIntegrityViolationException("deterministic constraint violation"))
                .when(invariantHarness.domainService())
                .acceptInitiator(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> invariantHarness.port().commit(invariant.request()))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(
                                ((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo("INTAKE_BRANCH_PERSISTENCE_INVARIANT"));
        assertThat(count("select count(*) from domain_operation where case_id = ?", invariant.caseId()))
                .isZero();
    }

    @Test
    void unknownTransactionOutcomeRemainsRetryable() {
        Fixture fixture = fixture("TRANSACTION_UNKNOWN", BranchOperation.INITIATOR_ACCEPT);
        PlatformTransactionManager unavailable = mock(PlatformTransactionManager.class);
        when(unavailable.getTransaction(any()))
                .thenThrow(new CannotCreateTransactionException("transaction manager unavailable"));
        JdbcIntakeFormalBranchCommitPort port = new JdbcIntakeFormalBranchCommitPort(
                namedJdbc,
                unavailable,
                mock(FulfillmentCaseRepository.class),
                mock(CaseRoomRepository.class),
                mock(IntakeBranchDomainService.class),
                request -> fixture.resolved(),
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> port.commit(fixture.request()))
                .isInstanceOf(IntakeFinalizationPersistenceException.class)
                .hasMessageContaining("commit outcome was known");
    }

    @Test
    void respondentConfirmationFreezesTheExactMatrixAndReplaysWithoutAnotherEpoch() throws Exception {
        Fixture fixture = fixture("RESPONDENT", BranchOperation.RESPONDENT_CONFIRM);
        insertFixture(fixture);
        insertInitiatorCompletion(fixture);
        Harness harness = harness(fixture);
        RoomEpochAllocator.RoomEpochAllocation allocation = mock(RoomEpochAllocator.RoomEpochAllocation.class);
        when(allocation.caseId()).thenReturn(fixture.caseId());
        when(allocation.roomType()).thenReturn(ContractTypes.RoomType.EVIDENCE);
        when(allocation.processRevision()).thenReturn(fixture.envelope().processRevision() + 1);
        doAnswer(ignored -> {
                    assertThat(number(
                                    "select last_command_sequence from case_process_projection where case_id = ?",
                                    fixture.caseId()))
                            .isEqualTo(fixture.envelope().commandSequence());
                    assertThat(number(
                                    "select last_case_event_sequence from case_process_projection where case_id = ?",
                                    fixture.caseId()))
                            .isEqualTo(1);
                    return allocation;
                })
                .when(harness.roomEpochAllocator())
                .transition(any());

        BranchCommitReceipt receipt = harness.port().commit(fixture.request());
        BranchCommitReceipt replay = harness.port().commit(fixture.request());

        assertThat(replay).isEqualTo(receipt);
        assertThat(receipt.operation().processRevision())
                .isEqualTo(fixture.envelope().processRevision() + 1);
        assertThat(receipt.operation().roomRevision())
                .isEqualTo(fixture.envelope().roomRevision() + 1);
        verify(harness.domainService()).confirmRespondent(
                any(),
                any(),
                any(),
                any(),
                any(),
                eq(TimelineEventMode.FORMAL_TYPED_ONLY),
                any());
        ArgumentCaptor<TransitionRoomEpoch> transition =
                ArgumentCaptor.forClass(TransitionRoomEpoch.class);
        verify(harness.roomEpochAllocator(), times(1)).transition(transition.capture());
        FrozenIntakeSubmissionAuthority expected = Objects.requireNonNull(
                harness.branchResult().frozenSubmissionAuthority());
        assertThat(transition.getValue().projectionRef()).isEqualTo(expected.projectionRef());
        assertThat(transition.getValue().projectionSha256())
                .isEqualTo(expected.matrixContentHash());
        assertThat(transition.getValue().projectionSha256())
                .isNotEqualTo(expected.authorityHash());
        assertThat(count("select count(*) from domain_operation where case_id = ?", fixture.caseId()))
                .isEqualTo(1);
        assertThat(count("select count(*) from case_timeline_event where case_id = ?",
                        fixture.caseId()))
                .isEqualTo(1);
        assertThat(scalar("select command_status from case_command where command_id = ?",
                        fixture.envelope().commandId()))
                .isEqualTo("APPLIED");
        JsonNode storedEvent = objectMapper.readTree(scalar(
                "select event_json::text from case_timeline_event where case_id = ?",
                fixture.caseId()));
        FrozenIntakeSubmissionAuthority stored = objectMapper.treeToValue(
                storedEvent.at("/result/frozen_submission/authority"),
                FrozenIntakeSubmissionAuthority.class);
        JsonNode storedMatrix = storedEvent.at("/result/frozen_submission/matrix");
        assertThat(storedEvent.at("/result/schema_version").asText())
                .isEqualTo("intake-branch-result.v2");
        assertThat(stored).isEqualTo(expected);
        assertThat(ContractJson.canonicalString(storedMatrix))
                .isEqualTo(harness.branchResult().frozenMatrixCanonicalJson());
        stored.requireMatchesMatrix(storedMatrix);
        stored.requireProjectionPair(
                stored.projectionRef(), stored.matrixContentHash());
    }

    @Test
    void frozenSubmissionAuthorityRejectsHashDriftAndReboundMatrixIdentity() {
        Fixture fixture = fixture("RESPONDENT_MATRIX_REJECT", BranchOperation.RESPONDENT_CONFIRM);
        ObjectNode matrix = frozenMatrix(fixture.caseId());
        FrozenIntakeSubmissionAuthority authority = frozenAuthority(fixture, matrix);

        ObjectNode drifted = matrix.deepCopy();
        drifted.put("unexpected_mutation", true);
        assertThatThrownBy(() -> authority.requireMatchesMatrix(drifted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content_hash");

        ObjectNode rebound = matrix.deepCopy();
        String original = rebound.path("matrix_id").asText();
        char replacement = original.charAt("CASE_MATRIX_".length()) == 'A' ? 'B' : 'A';
        rebound.put(
                "matrix_id",
                "CASE_MATRIX_"
                        + replacement
                        + original.substring("CASE_MATRIX_".length() + 1));
        rebound.remove("content_hash");
        rebound.put("content_hash", ContractJson.sha256Hex(rebound));
        assertThatThrownBy(() -> frozenAuthority(fixture, rebound))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matrix_id");
    }

    private static Harness harness(Fixture fixture) {
        FulfillmentCaseRepository caseRepository = mock(FulfillmentCaseRepository.class);
        CaseRoomRepository roomRepository = mock(CaseRoomRepository.class);
        IntakeBranchDomainService domainService = mock(IntakeBranchDomainService.class);
        RoomEpochAllocator roomEpochAllocator = mock(RoomEpochAllocator.class);
        FulfillmentCaseEntity dispute = mock(FulfillmentCaseEntity.class);
        CaseRoomEntity room = mock(CaseRoomEntity.class);
        when(caseRepository.findByIdForUpdate(fixture.caseId())).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(fixture.caseId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        CaseStatus resultStatus = switch (fixture.operation()) {
            case INITIATOR_ACCEPT -> CaseStatus.INTAKE_COMPLETED;
            case RESPONDENT_CONFIRM -> CaseStatus.EVIDENCE_OPEN;
            case INITIATOR_REJECT -> CaseStatus.NOT_ADMISSIBLE;
            case CANCEL -> CaseStatus.CANCELLED;
        };
        String currentRoom = switch (fixture.operation()) {
            case INITIATOR_ACCEPT -> RoomType.INTAKE.name();
            case RESPONDENT_CONFIRM -> RoomType.EVIDENCE.name();
            case INITIATOR_REJECT, CANCEL -> null;
        };
        when(dispute.getCaseStatus()).thenReturn(resultStatus);
        when(dispute.getCurrentRoom()).thenReturn(currentRoom);
        when(dispute.getCurrentDeadlineAt()).thenReturn(null);
        ObjectNode frozenMatrix = fixture.operation() == BranchOperation.RESPONDENT_CONFIRM
                ? frozenMatrix(fixture.caseId())
                : null;
        FrozenIntakeSubmissionAuthority frozenAuthority = frozenMatrix == null
                ? null
                : frozenAuthority(fixture, frozenMatrix);
        BranchResult result = new BranchResult(
                new IntakeConfirmationView(
                        fixture.caseId(),
                        resultStatus,
                        currentRoom == null ? null : RoomType.valueOf(currentRoom),
                        fixture.operation() == BranchOperation.RESPONDENT_CONFIRM
                                ? NOW.plusSeconds(7_200).atOffset(ZoneOffset.UTC)
                                : null),
                fixture.roomId(),
                fixture.operation() == BranchOperation.RESPONDENT_CONFIRM
                        ? "ROOM_EVIDENCE_" + fixture.caseId()
                        : null,
                switch (fixture.operation()) {
                    case INITIATOR_ACCEPT -> "INITIATOR_FROZEN";
                    case RESPONDENT_CONFIRM -> FrozenIntakeSubmissionAuthority.MATRIX_KIND;
                    default -> null;
                },
                switch (fixture.operation()) {
                    case INITIATOR_ACCEPT -> "d".repeat(64);
                    case RESPONDENT_CONFIRM -> frozenAuthority.matrixContentHash();
                    default -> null;
                },
                frozenAuthority,
                frozenMatrix == null ? null : ContractJson.canonicalString(frozenMatrix));
        switch (fixture.operation()) {
            case INITIATOR_ACCEPT -> {
                doAnswer(ignored -> {
                            applyDomainMutation(fixture, resultStatus, currentRoom);
                            return result;
                        })
                        .when(domainService)
                        .acceptInitiator(any(), any(), any(), any(), any(), any());
                when(domainService.requireFormalInitiatorMatrix(dispute))
                        .thenReturn(new IntakeBranchDomainService.ObjectNodeAuthority(
                                "INITIATOR_FROZEN", "d".repeat(64)));
            }
            case INITIATOR_REJECT -> doAnswer(ignored -> {
                        applyDomainMutation(fixture, resultStatus, currentRoom);
                        return result;
                    })
                    .when(domainService)
                    .rejectInitiator(any(), any(), any(), any(), any(), any());
            case CANCEL -> doAnswer(ignored -> {
                        applyDomainMutation(fixture, resultStatus, currentRoom);
                        return result;
                    })
                    .when(domainService)
                    .cancel(any(), any(), any(), any(), any(), any());
            case RESPONDENT_CONFIRM -> {
                doAnswer(ignored -> {
                            applyDomainMutation(fixture, resultStatus, currentRoom);
                            return result;
                        })
                        .when(domainService)
                        .confirmRespondent(any(), any(), any(), any(), any(), any(), any());
                when(domainService.requireFormalBilateralMatrix(dispute))
                        .thenReturn(new IntakeBranchDomainService.ObjectNodeAuthority(
                                "BILATERAL_FROZEN", frozenAuthority.matrixContentHash()));
            }
        }
        JdbcIntakeFormalBranchCommitPort port = new JdbcIntakeFormalBranchCommitPort(
                namedJdbc,
                transactions,
                caseRepository,
                roomRepository,
                domainService,
                request -> fixture.resolved(),
                roomEpochAllocator,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Harness(port, domainService, dispute, roomEpochAllocator, result);
    }

    private static void applyDomainMutation(
            Fixture fixture, CaseStatus status, String currentRoom) {
        jdbc.update(
                "update fulfillment_dispute_case set case_status = ?, current_room = ?, title = ? where id = ?",
                status.name(),
                currentRoom,
                "DOMAIN_" + fixture.operation().name(),
                fixture.caseId());
        if (fixture.operation() == BranchOperation.INITIATOR_ACCEPT) {
            insertCompletion(fixture, "initiator");
        } else if (fixture.operation() == BranchOperation.RESPONDENT_CONFIRM) {
            insertCompletion(fixture, "respondent");
        }
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
        return fixture(label, operation, null);
    }

    private static Fixture fixture(
            String label, BranchOperation operation, String confirmationNote) {
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
                        confirmationNote);
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

    private static BranchCommitRequest reconciliationRequest(BranchCommitRequest source) {
        ActivityEnvelope envelope = source.envelope();
        ActivityEnvelope reconciliationEnvelope = new ActivityEnvelope(
                envelope.schemaVersion(),
                envelope.tenantSurrogate(),
                envelope.caseId(),
                envelope.roomEpoch(),
                envelope.fencingToken(),
                envelope.commandId(),
                envelope.commandSequence(),
                envelope.commandType(),
                envelope.party(),
                envelope.actorScopeHash(),
                envelope.commandPayloadRef(),
                envelope.commandPayloadHash(),
                envelope.processRevision(),
                envelope.roomRevision(),
                envelope.deadlineEpochMillis(),
                new RetryBudget(
                        envelope.retryBudget().schemaVersion(),
                        envelope.retryBudget().providerAttemptsRemaining(),
                        0,
                        envelope.retryBudget().repairsRemaining()),
                envelope.pinnedVersions(),
                new ActivityInvocation(
                        "intake-activity-invocation.v1",
                        ActivityInvocationMode.RECONCILE_ONLY,
                        0));
        return new BranchCommitRequest(
                source.schemaVersion(),
                reconciliationEnvelope,
                source.operation(),
                source.operationKey(),
                source.requestHash());
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
        if (fixture.operation() != BranchOperation.CANCEL) {
            insertConfirmationDossier(fixture, "WAITING_FOR_REMARK", null);
        }
    }

    private static void insertConfirmationDossier(
            Fixture fixture, String remarkStatus, String remark) {
        ObjectNode dossier = objectMapper.createObjectNode();
        dossier.put("schema_version", "intake-dossier.v2");
        ObjectNode partyState = dossier.putObject("party_intake_state");
        partyState.put("schema_version", "party-intake-state.v1");
        putPartyIntakeEntry(partyState.putObject("USER"), "USER", "NOT_READY", null);
        putPartyIntakeEntry(partyState.putObject("MERCHANT"), "MERCHANT", "NOT_READY", null);
        ObjectNode actorEntry = (ObjectNode) partyState.path(fixture.actorRole());
        putPartyIntakeEntry(actorEntry, fixture.actorRole(), remarkStatus, remark);
        dossier.set("handoff_notes", actorEntry.path("handoff_notes").deepCopy());
        ObjectNode matrix = dossier.putObject("case_fact_matrix");
        matrix.put("matrix_id", "CASE_MATRIX_" + sha256(fixture.caseId()).substring(0, 20).toUpperCase());
        matrix.put("matrix_version", 2);
        matrix.put("content_hash", sha256("matrix:" + fixture.caseId()));
        ObjectNode partition = dossier.putObject("handoff_remark_partition");
        partition.put("schema_version", "handoff_remark_partition.v1");
        partition.set("case_fact_matrix_id", matrix.path("matrix_id").deepCopy());
        partition.set("case_fact_matrix_version", matrix.path("matrix_version").deepCopy());
        partition.set("case_fact_matrix_hash", matrix.path("content_hash").deepCopy());
        ObjectNode parties = partition.putObject("parties");
        putRemarkPartitionParty(parties.putObject("USER"), "USER", "NOT_READY", null);
        putRemarkPartitionParty(parties.putObject("MERCHANT"), "MERCHANT", "NOT_READY", null);
        putRemarkPartitionParty(
                (ObjectNode) parties.path(fixture.actorRole()),
                fixture.actorRole(),
                remarkStatus,
                remark);
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into case_intake_dossier (
                    id, case_id, room_type, dossier_version, dossier_json,
                    quality_score, ready_for_next_step, admission_recommendation,
                    source_turn_no, created_at, updated_at, created_by, updated_by
                ) values (?, ?, 'INTAKE', 3, cast(? as jsonb),
                    100, true, 'ACCEPTED', 3, ?, ?, 'test', 'test')
                on conflict (case_id, room_type) do update
                   set dossier_version = excluded.dossier_version,
                       dossier_json = excluded.dossier_json,
                       quality_score = excluded.quality_score,
                       ready_for_next_step = excluded.ready_for_next_step,
                       admission_recommendation = excluded.admission_recommendation,
                       source_turn_no = excluded.source_turn_no,
                       updated_at = excluded.updated_at,
                       updated_by = excluded.updated_by
                """,
                "DOS_" + sha256(fixture.caseId()).substring(0, 60),
                fixture.caseId(),
                ContractJson.canonicalString(dossier),
                now,
                now);
    }

    private static void putPartyIntakeEntry(
            ObjectNode entry, String role, String remarkStatus, String remark) {
        boolean ready = !"NOT_READY".equals(remarkStatus);
        ObjectNode quality = entry.putObject("intake_quality");
        quality.put("score", ready ? 100 : 0);
        quality.put("threshold", 85);
        quality.put("ready_for_next_step", ready);
        ObjectNode breakdown = quality.putObject("score_breakdown");
        breakdown.put("references", ready ? 15 : 0);
        breakdown.put("event_story", ready ? 20 : 0);
        breakdown.put("party_positions", ready ? 20 : 0);
        breakdown.put("requested_resolution", ready ? 15 : 0);
        breakdown.put("risk_and_conflicts", ready ? 15 : 0);
        breakdown.put("next_action_clarity", ready ? 15 : 0);
        quality.put(
                "improvement_reason",
                ready ? "信息完整度已达到提交阈值。" : "等待当前参与方补充案情。");
        ObjectNode missing = entry.putObject("missing_information");
        missing.putArray("blocking_gaps");
        missing.putArray("nice_to_have_gaps");
        missing.putArray("next_questions");
        ObjectNode handoff = entry.putObject("handoff_notes");
        handoff.put("remark_status", remarkStatus);
        handoff.put(
                "phase_source_message_id",
                "NOT_READY".equals(remarkStatus)
                        ? ""
                        : remark == null ? "MESSAGE_PHASE_" + role : "MESSAGE_REMARK_" + role);
        handoff.put(
                "latest_remark",
                "NO_EXTRA_REMARKS".equals(remarkStatus)
                        ? "无额外备注。"
                        : remark == null ? "" : remark);
        var remarks = handoff.putArray("remarks");
        if (remark != null) {
            ObjectNode item = remarks.addObject();
            item.put("role", role);
            item.put("text", remark);
            item.put("source_message_id", "MESSAGE_REMARK_" + role);
            item.put("turn_source", "CURRENT_MESSAGE");
        }
        handoff.put("instruction", "可补充交接备注。");
        ObjectNode admission = entry.putObject("admission");
        admission.put("recommendation", ready ? "ACCEPTED" : "NEED_MORE_INFO");
        admission.put("reasoning", "");
        admission.put("confidence", 1);
    }

    private static void putRemarkPartitionParty(
            ObjectNode party, String role, String remarkStatus, String remark) {
        party.removeAll();
        party.put("party_role", role);
        party.put("remark_status", remarkStatus);
        if (!"NOT_READY".equals(remarkStatus)) {
            ObjectNode source = party.putObject("source");
            source.put("source_kind", "ROOM_MESSAGE");
            source.put(
                    "message_id",
                    remark == null ? "MESSAGE_PHASE_" + role : "MESSAGE_REMARK_" + role);
            source.put(
                    "message_hash",
                    remarkSourceHash(
                            source.path("message_id").asText(),
                            role,
                            remark == null ? "无备注" : remark));
        }
        party.put("latest_remark", remark == null ? "" : remark);
        var remarks = party.putArray("remarks");
        if (remark != null) {
            ObjectNode item = remarks.addObject();
            item.put("party_role", role);
            item.put("text", remark);
            item.put("source_message_id", "MESSAGE_REMARK_" + role);
            item.put(
                    "source_message_hash",
                    remarkSourceHash("MESSAGE_REMARK_" + role, role, remark));
            item.put("turn_source", "ROOM_MESSAGE");
        }
    }

    private static String remarkSourceHash(String messageId, String role, String text) {
        ObjectNode material = objectMapper.createObjectNode();
        material.put("message_id", messageId);
        material.put("role", role);
        material.put("source", "ROOM_MESSAGE");
        material.put("text", text);
        return ContractJson.sha256Hex(material);
    }

    private static void insertInitiatorCompletion(Fixture fixture) {
        insertCompletion(fixture, "initiator");
    }

    private static void insertRespondentCompletion(Fixture fixture) {
        insertCompletion(fixture, "respondent");
    }

    private static void insertCompletion(Fixture fixture, String party) {
        String participantId = scalar(
                "select " + party + "_id from fulfillment_dispute_case where id = ?",
                fixture.caseId());
        String participantRole = scalar(
                "select " + party + "_role from fulfillment_dispute_case where id = ?",
                fixture.caseId());
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into case_intake_party_completion (
                    id, case_id, participant_role, participant_id, completion_status,
                    completed_at, created_at, created_by
                ) values (?, ?, ?, ?, 'COMPLETED', ?, ?, 'test')
                """,
                "COMP_" + sha256(party + ':' + fixture.caseId()).substring(0, 59),
                fixture.caseId(),
                participantRole,
                participantId,
                now,
                now);
    }

    private static void insertStartedOperation(Fixture fixture) {
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into domain_operation (
                    id, operation_key, tenant_surrogate, case_id, case_command_id,
                    operation_type, room_type, room_epoch, process_revision, fencing_token,
                    request_hash, operation_status, started_at, created_at, updated_at, version
                ) values (?, ?, ?, ?, ?, ?, 'INTAKE', ?, ?, ?, ?, 'STARTED', ?, ?, ?, 0)
                """,
                "INBR_STARTED_" + fixture.caseId(),
                fixture.request().operationKey(),
                fixture.tenant(),
                fixture.caseId(),
                "CMD_" + fixture.caseId(),
                branchOperationType(fixture.operation()),
                fixture.envelope().roomEpoch(),
                fixture.envelope().processRevision(),
                fixture.envelope().fencingToken(),
                fixture.request().requestHash(),
                now,
                now,
                now);
    }

    private static String branchOperationType(BranchOperation operation) {
        return switch (operation) {
            case INITIATOR_ACCEPT -> "INTAKE_INITIATOR_ACCEPT";
            case INITIATOR_REJECT -> "INTAKE_INITIATOR_REJECT";
            case CANCEL -> "INTAKE_CANCEL";
            case RESPONDENT_CONFIRM -> "INTAKE_RESPONDENT_CONFIRM";
        };
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

    private static ObjectNode frozenMatrix(String caseId) {
        ObjectNode matrix = objectMapper.createObjectNode();
        matrix.put("schema_version", FrozenIntakeSubmissionAuthority.MATRIX_SCHEMA_VERSION);
        matrix.put("case_id", caseId);
        matrix.put("matrix_version", 3);
        matrix.put("matrix_kind", FrozenIntakeSubmissionAuthority.MATRIX_KIND);
        ObjectNode partyMap = matrix.putObject("party_map");
        partyMap.put("initiator_role", "USER");
        partyMap.put("respondent_role", "MERCHANT");
        ObjectNode row = matrix.putArray("fact_rows").addObject();
        row.put("fact_id", "FACT_" + sha256(caseId).substring(0, 24).toUpperCase(Locale.ROOT));
        ObjectNode respondent = row.putObject("positions").putObject("MERCHANT");
        respondent.put("stance", "CONFIRM");
        respondent.put("source_type", "DIRECT_PARTY_STATEMENT");
        respondent.putArray("source_refs").add("SUBMIT_SOURCE_" + caseId);
        row.putObject("party_alignment").put("status", "AGREED");
        row.put("requires_resolution", false);
        matrix.put(
                "matrix_id",
                "CASE_MATRIX_"
                        + ContractJson.sha256Hex(matrix)
                                .substring(0, 20)
                                .toUpperCase(Locale.ROOT));
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        return matrix;
    }

    private static FrozenIntakeSubmissionAuthority frozenAuthority(
            Fixture fixture, ObjectNode matrix) {
        String eventId = "EVIB_"
                + sha256(fixture.request().operationKey() + ":event").substring(0, 59);
        String eventRef = "urn:after-sale-flow:intake-event:" + eventId;
        return FrozenIntakeSubmissionAuthority.capture(
                fixture.tenant(),
                fixture.caseId(),
                fixture.actorId(),
                ContractTypes.ActorRole.valueOf(fixture.actorRole()),
                "INTAKE_COMPLETE_" + sha256(fixture.caseId()).substring(0, 48),
                FrozenIntakeSubmissionAuthority.COMPLETION_STATUS,
                NOW,
                fixture.request().operationKey(),
                fixture.envelope().commandId(),
                fixture.envelope().commandSequence(),
                fixture.request().requestHash(),
                eventId,
                eventRef,
                1,
                fixture.envelope().roomEpoch(),
                fixture.envelope().fencingToken(),
                fixture.envelope().processRevision() + 1,
                fixture.envelope().roomRevision() + 1,
                "INTAKE_DOSSIER_" + sha256(fixture.caseId()).substring(0, 48),
                4,
                matrix);
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
            FulfillmentCaseEntity dispute,
            RoomEpochAllocator roomEpochAllocator,
            BranchResult branchResult) {}
}
