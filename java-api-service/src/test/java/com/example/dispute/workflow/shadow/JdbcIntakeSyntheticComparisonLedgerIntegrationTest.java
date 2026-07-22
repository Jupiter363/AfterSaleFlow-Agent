package com.example.dispute.workflow.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.dispute.config.ActorRole;
import com.example.dispute.workflow.application.authority.epoch.AgentSessionProfileRegistry;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison;
import com.example.dispute.workflow.shadow.intake.IntakeShadowParityService;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonLedger.CommitRequest;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonLedger.CommitResult;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonReceiptFactory;
import com.example.dispute.workflow.shadow.intake.JdbcIntakeSyntheticComparisonLedger;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL proof for the signed-synthetic comparison authority and replay boundary. */
@Testcontainers
class JdbcIntakeSyntheticComparisonLedgerIntegrationTest {

    private static final String DB = "intake_shadow_comparison";
    private static final String USER = "dispute_test";
    private static final String PASSWORD = "local_test_password";
    private static final Instant NOW = Instant.parse("2026-07-22T08:03:00Z");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
                    DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
            .withEnv("POSTGRES_DB", DB)
            .withEnv("POSTGRES_USER", USER)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcIntakeSyntheticComparisonLedger ledger;
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
        dataSource = new DriverManagerDataSource(url, USER, PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        var transactions = new DataSourceTransactionManager(dataSource);
        ledger = new JdbcIntakeSyntheticComparisonLedger(
                new NamedParameterJdbcTemplate(dataSource),
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactions);
    }

    @Test
    void exactConcurrentCommitCreatesOneRowAndReplaysTheStoredReceipt() throws Exception {
        Fixture fixture = insertFixture("CONCURRENT_" + SEQUENCE.incrementAndGet());
        CommitRequest request = commitRequest(fixture, IntakeDomainEventType.TURN_READY_TO_CONFIRM);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<CommitResult> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<CommitResult>> futures = List.of(
                    executor.submit(() -> commitAfterBarrier(request, ready, start)),
                    executor.submit(() -> commitAfterBarrier(request, ready, start)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            results = List.of(futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));
        }

        assertThat(results).extracting(CommitResult::created).containsExactlyInAnyOrder(true, false);
        assertThat(results.get(0).comparison()).isEqualTo(results.get(1).comparison());
        assertThat(results.get(0).receipt()).isEqualTo(results.get(1).receipt());
        CommitResult replay = ledger.commit(request);
        assertThat(replay.created()).isFalse();
        assertThat(replay.receipt()).isEqualTo(results.get(0).receipt());
        assertThat(countComparisons(fixture)).isOne();
        assertThat(jdbc.queryForMap(
                        "select party_authority_id, party, command_type, projected_event_type "
                                + "from case_intake_shadow_comparison where operation_key = ?",
                        fixture.finalization().operationKey()))
                .containsEntry("party_authority_id", fixture.initiatorAuthorityId())
                .containsEntry("party", "INITIATOR")
                .containsEntry("command_type", "INTAKE_MESSAGE")
                .containsEntry("projected_event_type", "TURN_READY_TO_CONFIRM");
        assertThat(jdbc.queryForObject(
                        "select count(*) from domain_operation where operation_key = ?",
                        Integer.class,
                        fixture.finalization().operationKey()))
                .isZero();
    }

    @Test
    void differentOperationCollidingOnComparisonKeyFailsClosedInsteadOfReplaying() {
        Fixture fixture = insertFixture("KEY_COLLISION_" + SEQUENCE.incrementAndGet());
        CommitRequest request = commitRequest(fixture, IntakeDomainEventType.TURN_READY_TO_CONFIRM);
        ledger.commit(request);
        String originalOperationKey = fixture.finalization().operationKey();
        String collidingOperationKey = IntakeOperationKeys.turnFinalize(
                fixture.caseId(),
                fixture.roomEpoch(),
                fixture.threadId(),
                fixture.commandId(),
                sha256("colliding-operation:" + fixture.caseId()));
        replaceStoredOperationKey(originalOperationKey, collidingOperationKey);

        Throwable failure = catchThrowable(() -> ledger.commit(request));

        assertThat(failure)
                .as("a comparison-key collision must never be returned as an exact replay")
                .isNotNull()
                .isInstanceOf(RuntimeException.class);
        assertThat(countComparisons(fixture)).isOne();
        assertThat(jdbc.queryForObject(
                        "select operation_key from case_intake_shadow_comparison "
                                + "where comparison_key_hash = ?",
                        String.class,
                        request.comparison().comparisonKeyHash()))
                .isEqualTo(collidingOperationKey);
    }

    @Test
    void sameOperationRejectsRequestAndProposalDriftAndResultCannotReuseTheKey() {
        Fixture fixture = insertFixture("VALUE_CONFLICT_" + SEQUENCE.incrementAndGet());
        CommitRequest committed = commitRequest(
                fixture, IntakeDomainEventType.TURN_READY_TO_CONFIRM);
        CommitResult original = ledger.commit(committed);

        TurnFinalizationRequest requestDrift = withRequestHash(
                fixture.finalization(), sha256("request-drift:" + fixture.caseId()));
        assertReplayConflict(requestDrift);

        TurnFinalizationRequest proposalDrift = withProposalHash(
                fixture.finalization(), sha256("proposal-drift:" + fixture.caseId()));
        assertReplayConflict(proposalDrift);

        assertThatThrownBy(() -> withResultHashKeepingOperation(
                        fixture.finalization(), sha256("result-drift:" + fixture.caseId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operationKey does not match");

        CommitResult replay = ledger.commit(committed);
        assertThat(replay.created()).isFalse();
        assertThat(replay.receipt()).isEqualTo(original.receipt());
        assertThat(countComparisons(fixture)).isOne();
    }

    @Test
    void staleFenceTerminalEpochRevokedAuthorityWrongPartyAndWrongRequestAreRejected() {
        Fixture stale = insertFixture("STALE_" + SEQUENCE.incrementAndGet());
        var staleRequest = IntakeSyntheticTestFixtures.finalizationRequest(
                stale.tenant(),
                stale.caseId(),
                stale.roomEpoch(),
                stale.fencingToken() + 1,
                stale.commandId(),
                IntakeParty.INITIATOR,
                stale.actorScopeHash(),
                stale.threadId(),
                stale.agentSessionId(),
                stale.requestHash());
        assertRejected(staleRequest, IntakeDomainEventType.TURN_READY_TO_CONFIRM);

        Fixture terminal = insertFixture("TERMINAL_" + SEQUENCE.incrementAndGet());
        terminalize(terminal);
        assertRejected(terminal.finalization(), IntakeDomainEventType.TURN_READY_TO_CONFIRM);

        Fixture revoked = insertFixture("REVOKED_" + SEQUENCE.incrementAndGet());
        assertThat(jdbc.update(
                        "update case_access_session set status = 'REVOKED', updated_at = ? "
                                + "where id = (select access_session_id "
                                + "from case_intake_epoch_party_authority where authority_id = ?)",
                        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                        revoked.initiatorAuthorityId()))
                .isOne();
        assertRejected(revoked.finalization(), IntakeDomainEventType.TURN_READY_TO_CONFIRM);

        Fixture wrongParty = insertFixture("PARTY_" + SEQUENCE.incrementAndGet());
        var wrongPartyRequest = IntakeSyntheticTestFixtures.finalizationRequest(
                wrongParty.tenant(),
                wrongParty.caseId(),
                wrongParty.roomEpoch(),
                wrongParty.fencingToken(),
                wrongParty.commandId(),
                IntakeParty.RESPONDENT,
                wrongParty.actorScopeHash(),
                wrongParty.threadId(),
                wrongParty.agentSessionId(),
                wrongParty.requestHash());
        assertRejected(wrongPartyRequest, IntakeDomainEventType.TURN_READY_TO_CONFIRM);

        Fixture wrongHash = insertFixture("HASH_" + SEQUENCE.incrementAndGet());
        var wrongHashRequest = IntakeSyntheticTestFixtures.finalizationRequest(
                wrongHash.tenant(),
                wrongHash.caseId(),
                wrongHash.roomEpoch(),
                wrongHash.fencingToken(),
                wrongHash.commandId(),
                IntakeParty.INITIATOR,
                wrongHash.actorScopeHash(),
                wrongHash.threadId(),
                wrongHash.agentSessionId(),
                sha256("wrong-request:" + wrongHash.caseId()));
        assertRejected(wrongHashRequest, IntakeDomainEventType.TURN_READY_TO_CONFIRM);

        assertThat(countComparisons(stale)
                        + countComparisons(terminal)
                        + countComparisons(revoked)
                        + countComparisons(wrongParty)
                        + countComparisons(wrongHash))
                .isZero();
    }

    @Test
    void sameOperationWithAnotherProjectedEventTypeIsAConflict() {
        Fixture fixture = insertFixture("EVENT_" + SEQUENCE.incrementAndGet());
        ledger.commit(commitRequest(fixture, IntakeDomainEventType.TURN_READY_TO_CONFIRM));

        assertThatThrownBy(() -> ledger.commit(
                        commitRequest(fixture, IntakeDomainEventType.TURN_NEEDS_INPUT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event type");
        assertThat(countComparisons(fixture)).isOne();
    }

    @Test
    void exactCommittedReceiptReplaysAfterTerminalOrRevokedAuthority() {
        Fixture terminal = insertFixture("REPLAY_TERMINAL_" + SEQUENCE.incrementAndGet());
        CommitRequest terminalRequest = commitRequest(
                terminal, IntakeDomainEventType.TURN_READY_TO_CONFIRM);
        CommitResult terminalCommitted = ledger.commit(terminalRequest);
        terminalize(terminal);
        CommitResult terminalReplay = ledger.commit(terminalRequest);
        assertThat(terminalReplay.created()).isFalse();
        assertThat(terminalReplay.receipt()).isEqualTo(terminalCommitted.receipt());

        Fixture revoked = insertFixture("REPLAY_REVOKED_" + SEQUENCE.incrementAndGet());
        CommitRequest revokedRequest = commitRequest(
                revoked, IntakeDomainEventType.TURN_READY_TO_CONFIRM);
        CommitResult revokedCommitted = ledger.commit(revokedRequest);
        assertThat(jdbc.update(
                        "update case_access_session set status = 'REVOKED', updated_at = ? "
                                + "where id = (select access_session_id "
                                + "from case_intake_shadow_comparison where operation_key = ?)",
                        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                        revoked.finalization().operationKey()))
                .isOne();
        CommitResult revokedReplay = ledger.commit(revokedRequest);
        assertThat(revokedReplay.created()).isFalse();
        assertThat(revokedReplay.receipt()).isEqualTo(revokedCommitted.receipt());
    }

    @Test
    void terminalTransitionRacingComparisonCommitWinsOrLosesAtTheEpochLock() throws Exception {
        Fixture fixture = insertFixture("RACE_" + SEQUENCE.incrementAndGet());
        CommitRequest request = commitRequest(fixture, IntakeDomainEventType.TURN_READY_TO_CONFIRM);

        try (Connection terminal = dataSource.getConnection();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            terminal.setAutoCommit(false);
            try (var statement = terminal.prepareStatement(
                    "update case_room_epoch set lifecycle_status = 'TERMINAL', "
                            + "process_revision = process_revision + 1, "
                            + "terminal_at = ?, updated_at = ? where id = ?")) {
                statement.setObject(1, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
                statement.setObject(2, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
                statement.setString(3, fixture.epochId());
                assertThat(statement.executeUpdate()).isOne();
            }

            Future<CommitResult> blocked = executor.submit(() -> ledger.commit(request));
            Thread.sleep(250);
            assertThat(blocked).isNotDone();
            terminal.commit();

            assertThatThrownBy(() -> blocked.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SecurityException.class);
        }
        assertThat(countComparisons(fixture)).isZero();
    }

    @Test
    void comparisonLedgerIsAppendOnlyIncludingTruncate() {
        Fixture fixture = insertFixture("IMMUTABLE_" + SEQUENCE.incrementAndGet());
        ledger.commit(commitRequest(fixture, IntakeDomainEventType.TURN_READY_TO_CONFIRM));

        assertThatThrownBy(() -> jdbc.execute("truncate table case_intake_shadow_comparison"))
                .isInstanceOf(DataAccessException.class);
        assertThat(countComparisons(fixture)).isOne();
    }

    private static void assertRejected(
            com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest finalization,
            IntakeDomainEventType eventType) {
        String key = IntakeSyntheticComparisonReceiptFactory.comparisonKey(finalization);
        IntakeShadowComparison comparison = new IntakeShadowParityService(ignored -> {}).evaluate(
                key,
                IntakeSyntheticTestFixtures.paritySnapshot(),
                IntakeSyntheticTestFixtures.paritySnapshot());
        assertThatThrownBy(() -> ledger.commit(new CommitRequest(finalization, comparison, eventType)))
                .isInstanceOf(SecurityException.class);
    }

    private static CommitRequest commitRequest(
            Fixture fixture, IntakeDomainEventType eventType) {
        return commitRequest(fixture.finalization(), eventType);
    }

    private static CommitRequest commitRequest(
            TurnFinalizationRequest finalization, IntakeDomainEventType eventType) {
        String key = IntakeSyntheticComparisonReceiptFactory.comparisonKey(finalization);
        IntakeShadowComparison comparison = new IntakeShadowParityService(ignored -> {}).evaluate(
                key,
                IntakeSyntheticTestFixtures.paritySnapshot(),
                IntakeSyntheticTestFixtures.paritySnapshot());
        return new CommitRequest(finalization, comparison, eventType);
    }

    private static CommitResult commitAfterBarrier(
            CommitRequest request, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent commit start barrier timed out");
        }
        return ledger.commit(request);
    }

    private static void assertReplayConflict(TurnFinalizationRequest finalization) {
        assertThatThrownBy(() -> ledger.commit(commitRequest(
                        finalization, IntakeDomainEventType.TURN_READY_TO_CONFIRM)))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
    }

    private static void replaceStoredOperationKey(String expected, String replacement) {
        jdbc.execute(
                "alter table case_intake_shadow_comparison "
                        + "disable trigger trg_intake_shadow_comparison_immutable");
        try {
            assertThat(jdbc.update(
                            "update case_intake_shadow_comparison set operation_key = ? "
                                    + "where operation_key = ?",
                            replacement,
                            expected))
                    .isOne();
        } finally {
            jdbc.execute(
                    "alter table case_intake_shadow_comparison "
                            + "enable trigger trg_intake_shadow_comparison_immutable");
        }
    }

    private static TurnFinalizationRequest withRequestHash(
            TurnFinalizationRequest source, String requestHash) {
        GraphExecutionReceipt graph = source.graphExecution();
        OperationReceipt operation = graph.operation();
        GraphExecutionReceipt reboundGraph = new GraphExecutionReceipt(
                graph.schemaVersion(),
                new OperationReceipt(
                        operation.schemaVersion(),
                        operation.operationKey(),
                        requestHash,
                        operation.resultHash(),
                        operation.processRevision(),
                        operation.roomRevision()),
                graph.agentRunRef(),
                graph.graphExecutionRef(),
                graph.resultPointer(),
                graph.proposalPointer());
        return new TurnFinalizationRequest(
                source.schemaVersion(),
                source.envelope(),
                source.threadId(),
                source.agentSessionId(),
                reboundGraph,
                source.operationKey(),
                requestHash);
    }

    private static TurnFinalizationRequest withProposalHash(
            TurnFinalizationRequest source, String proposalHash) {
        GraphExecutionReceipt graph = source.graphExecution();
        IntakeGraphExecutionRef graphRef = graph.graphExecutionRef();
        String proposalRef = graphRef.proposalRef() + ":conflict";
        IntakeGraphExecutionRef reboundRef = new IntakeGraphExecutionRef(
                graphRef.schemaVersion(),
                graphRef.threadId(),
                graphRef.graphCommandId(),
                graphRef.graphKey(),
                graphRef.graphVersion(),
                graphRef.checkpointId(),
                graphRef.resultRef(),
                graphRef.resultHash(),
                proposalRef,
                proposalHash);
        ImmutablePayloadRef proposal = graph.proposalPointer();
        ImmutablePayloadRef reboundProposal = new ImmutablePayloadRef(
                proposal.schemaVersion(),
                proposal.artifactId(),
                proposal.artifactType(),
                proposal.artifactSchemaVersion(),
                proposalRef,
                "conflicting-proposal.v1",
                proposalHash,
                proposal.sizeBytes());
        return new TurnFinalizationRequest(
                source.schemaVersion(),
                source.envelope(),
                source.threadId(),
                source.agentSessionId(),
                new GraphExecutionReceipt(
                        graph.schemaVersion(),
                        graph.operation(),
                        graph.agentRunRef(),
                        reboundRef,
                        graph.resultPointer(),
                        reboundProposal),
                source.operationKey(),
                source.requestHash());
    }

    private static TurnFinalizationRequest withResultHashKeepingOperation(
            TurnFinalizationRequest source, String resultHash) {
        GraphExecutionReceipt graph = source.graphExecution();
        OperationReceipt operation = graph.operation();
        IntakeAgentRunRef run = graph.agentRunRef();
        IntakeGraphExecutionRef graphRef = graph.graphExecutionRef();
        String resultRef = graphRef.resultRef() + ":conflict";
        ImmutablePayloadRef result = graph.resultPointer();
        GraphExecutionReceipt reboundGraph = new GraphExecutionReceipt(
                graph.schemaVersion(),
                new OperationReceipt(
                        operation.schemaVersion(),
                        operation.operationKey(),
                        operation.requestHash(),
                        resultHash,
                        operation.processRevision(),
                        operation.roomRevision()),
                new IntakeAgentRunRef(
                        run.schemaVersion(), run.logicalRunId(), run.attemptId(), resultHash),
                new IntakeGraphExecutionRef(
                        graphRef.schemaVersion(),
                        graphRef.threadId(),
                        graphRef.graphCommandId(),
                        graphRef.graphKey(),
                        graphRef.graphVersion(),
                        graphRef.checkpointId(),
                        resultRef,
                        resultHash,
                        graphRef.proposalRef(),
                        graphRef.proposalHash()),
                new ImmutablePayloadRef(
                        result.schemaVersion(),
                        result.artifactId(),
                        result.artifactType(),
                        result.artifactSchemaVersion(),
                        resultRef,
                        "conflicting-result.v1",
                        resultHash,
                        result.sizeBytes()),
                graph.proposalPointer());
        return new TurnFinalizationRequest(
                source.schemaVersion(),
                source.envelope(),
                source.threadId(),
                source.agentSessionId(),
                reboundGraph,
                source.operationKey(),
                source.requestHash());
    }

    private static Fixture insertFixture(String suffix) {
        String caseId = "CASE_SHADOW_" + suffix;
        String tenant = "tenant-shadow-" + suffix.toLowerCase();
        String roomId = "ROOM_" + suffix;
        String epochId = "EPOCH_" + suffix;
        String initiatorActor = "user-" + suffix;
        String respondentActor = "merchant-" + suffix;
        String initiatorAccess = "ACCESS_I_" + suffix;
        String respondentAccess = "ACCESS_R_" + suffix;
        String initiatorSession = "AGENT_I_" + suffix;
        String respondentSession = "AGENT_R_" + suffix;
        String initiatorRegistration = "REG_I_" + suffix;
        String respondentRegistration = "REG_R_" + suffix;
        String initiatorThread = "grt.v1." + sha256("thread-i:" + suffix).substring(0, 32);
        String respondentThread = "grt.v1." + sha256("thread-r:" + suffix).substring(0, 32);
        String initiatorScope = sha256("scope-i:" + suffix);
        String respondentScope = sha256("scope-r:" + suffix);
        String initiatorAuthority = "AUTH_I_" + suffix;
        String respondentAuthority = "AUTH_R_" + suffix;
        String commandId = "COMMAND_" + suffix;
        String caseCommandId = "CCMD_" + suffix;
        String payloadAuthorityId = "PAYLOAD_" + suffix;
        String requestHash = sha256("request:" + suffix);
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        String profileUser = AgentSessionProfileRegistry.profileId(ActorRole.USER, "intake-prompt.v2");
        String profileMerchant = AgentSessionProfileRegistry.profileId(
                ActorRole.MERCHANT, "intake-prompt.v2");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key, case_type,
                        case_status, initiator_role, initiator_id, respondent_role, respondent_id,
                        risk_level, title, description, current_room, created_by, updated_by
                    ) values (?, ?, ?, ?, 'DISPUTE', 'INTAKE_IN_PROGRESS', 'USER', ?,
                        'MERCHANT', ?, 'MEDIUM', 'Synthetic Intake', 'shadow fixture',
                        'INTAKE', 'test', 'test')
                    """,
                    caseId, initiatorActor, respondentActor, "create-" + suffix,
                    initiatorActor, respondentActor);
            jdbc.update(
                    """
                    insert into case_room (
                        id, case_id, room_type, room_status, opened_at, created_by, updated_by
                    ) values (?, ?, 'INTAKE', 'OPEN', ?, 'test', 'test')
                    """,
                    roomId, caseId, now);
            jdbc.update(
                    """
                    insert into case_room_epoch (
                        id, tenant_surrogate, case_id, room_id, room_type, room_epoch, writer_mode,
                        lifecycle_status, provisioning_status, process_revision, room_revision,
                        fencing_token, temporal_workflow_id, temporal_run_id,
                        room_temporal_workflow_id, room_temporal_run_id, temporal_build_id,
                        graph_key, graph_version, checkpoint_schema_version, stream_protocol,
                        selection_schema_version, process_contract_version, workflow_type,
                        room_workflow_type, room_workflow_build_id, activated_at, provisioned_at,
                        created_at, updated_at
                    ) values (?, ?, ?, ?, 'INTAKE', 9, 'SHADOW', 'ACTIVE', 'READY', 0, 0, 41,
                        ?, ?, ?, ?, 'synthetic-build', 'intake.v2', '2.0.0',
                        'intake-checkpoint.v2', 'agent-stream.v2', 'room-epoch-selection.v2',
                        'case-process-contract.v1', 'CaseProcessWorkflow', 'IntakeRoomWorkflow',
                        'synthetic-room-build', ?, ?, ?, ?)
                    """,
                    epochId, tenant, caseId, roomId,
                    "CASE_WORKFLOW_" + suffix, "CASE_RUN_" + suffix,
                    "ROOM_WORKFLOW_" + suffix, "ROOM_RUN_" + suffix,
                    now, now, now, now);
            insertAccessAndAgent(
                    tenant, caseId, initiatorActor, "USER", "PARTY_USER",
                    initiatorAccess, initiatorSession, profileUser, now);
            insertAccessAndAgent(
                    tenant, caseId, respondentActor, "MERCHANT", "PARTY_MERCHANT",
                    respondentAccess, respondentSession, profileMerchant, now);
            insertGraphRegistration(
                    tenant, caseId, epochId, initiatorActor, "USER", initiatorAccess,
                    initiatorSession, initiatorRegistration, initiatorThread, initiatorScope, now);
            insertGraphRegistration(
                    tenant, caseId, epochId, respondentActor, "MERCHANT", respondentAccess,
                    respondentSession, respondentRegistration, respondentThread, respondentScope, now);
            jdbc.update(
                    """
                    insert into case_intake_epoch_selection_binding (
                        epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
                        selection_hash, writer_mode, case_workflow_type, case_workflow_build_id,
                        room_workflow_type, room_workflow_build_id, process_contract_version,
                        graph_key, graph_version, checkpoint_schema_version, state_schema_version,
                        stream_protocol, prompt_version, model_profile_id, output_schema_version,
                        policy_version, guardrail_version, tool_policy_version,
                        cohort_policy_version, agent_key, agent_session_profile_version,
                        memory_policy_id, created_at
                    ) values (?, ?, ?, 'INTAKE', 9, 41, ?, 'SHADOW', 'CaseProcessWorkflow',
                        'synthetic-case-build', 'IntakeRoomWorkflow', 'synthetic-room-build',
                        'case-process-contract.v1', 'intake.v2', '2.0.0', 'intake-checkpoint.v2',
                        'intake-graph-state.v2', 'agent-stream.v2', 'intake-prompt.v2',
                        'intake-model.synthetic.v1', 'intake-turn-proposal.v2', 'intake-policy.v2',
                        'intake-guardrail.v2', 'no-tools.v1', 'synthetic-cohort.v1',
                        'DISPUTE_INTAKE_OFFICER', 'agent-session-profile.v1',
                        'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1', ?)
                    """,
                    epochId, tenant, caseId, sha256("selection:" + suffix), now);
            insertPartyAuthority(
                    initiatorAuthority, epochId, "INITIATOR", tenant, caseId,
                    initiatorRegistration, initiatorThread, initiatorActor, "USER",
                    initiatorScope, initiatorAccess, "PARTY_USER", initiatorSession,
                    profileUser, now);
            insertPartyAuthority(
                    respondentAuthority, epochId, "RESPONDENT", tenant, caseId,
                    respondentRegistration, respondentThread, respondentActor, "MERCHANT",
                    respondentScope, respondentAccess, "PARTY_MERCHANT", respondentSession,
                    profileMerchant, now);
            String payloadHash = sha256("payload:" + suffix);
            String payloadUri = "urn:after-sale-flow:intake-command:" + commandId;
            jdbc.update(
                    """
                    insert into case_command (
                        id, command_id, tenant_surrogate, case_id, case_command_sequence,
                        command_type, room_type, room_epoch, actor_id, actor_role,
                        actor_scopes_json, payload_schema_version, payload_uri, payload_sha256,
                        payload_size_bytes, expected_process_revision, occurred_at, deadline_at,
                        traceparent, request_hash, command_status
                    ) values (?, ?, ?, ?, 1, 'INTAKE_MESSAGE', 'INTAKE', 9, ?, 'USER',
                        '["INTAKE_PARTICIPATE"]'::jsonb, 'intake-human-input-command.v1',
                        ?, ?, 512, 0, ?, ?,
                        '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01', ?,
                        'ORCHESTRATION_ACCEPTED')
                    """,
                    caseCommandId, commandId, tenant, caseId, initiatorActor,
                    payloadUri, payloadHash, now, now.plusMinutes(5), requestHash);
            jdbc.update(
                    """
                    insert into case_intake_command_payload_authority (
                        payload_authority_id, command_id, epoch_id, party_authority_id,
                        access_session_id, registration_id, tenant_surrogate, case_id, room_type,
                        room_epoch, fencing_token, thread_id, actor_id, actor_role,
                        actor_scope_hash, agent_session_id, source_kind, artifact_id,
                        schema_version, object_uri, object_version, content_sha256, size_bytes,
                        put_receipt_schema_version, put_receipt_id, put_idempotency_key,
                        put_receipt_stored_at_epoch_micros, put_receipt_hash, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, 'INTAKE', 9, 41, ?, ?, 'USER', ?, ?,
                        'SERVER_MINTED_HUMAN_INPUT', ?, 'intake-human-input-command.v1', ?,
                        'version-1', ?, 512, 'intake-command-payload-put-receipt.v1', ?, ?,
                        1, ?, ?)
                    """,
                    payloadAuthorityId, commandId, epochId, initiatorAuthority,
                    initiatorAccess, initiatorRegistration, tenant, caseId, initiatorThread,
                    initiatorActor, initiatorScope, initiatorSession, "ARTIFACT_" + suffix,
                    payloadUri, payloadHash, "PUT_" + suffix,
                    "iput.v1." + sha256("put-key:" + suffix), sha256("put-receipt:" + suffix), now);
            jdbc.update(
                    """
                    insert into case_intake_command_authority (
                        case_command_id, command_id, case_command_sequence, command_type,
                        epoch_id, party_authority_id, access_session_id, registration_id,
                        tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
                        thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id,
                        payload_authority_id, request_hash, accepted_room_revision,
                        execution_disposition, created_at
                    ) values (?, ?, 1, 'INTAKE_MESSAGE', ?, ?, ?, ?, ?, ?, 'INTAKE', 9, 41,
                        ?, ?, 'USER', ?, ?, ?, ?, 0, 'ACTIVITY_ORCHESTRATED', ?)
                    """,
                    caseCommandId, commandId, epochId, initiatorAuthority, initiatorAccess,
                    initiatorRegistration, tenant, caseId, initiatorThread, initiatorActor,
                    initiatorScope, initiatorSession, payloadAuthorityId, requestHash, now);
        });

        var finalization = IntakeSyntheticTestFixtures.finalizationRequest(
                tenant, caseId, 9, 41, commandId, IntakeParty.INITIATOR,
                initiatorScope, initiatorThread, initiatorSession, requestHash);
        return new Fixture(
                tenant, caseId, epochId, 9, 41, initiatorAuthority,
                initiatorThread, initiatorScope, initiatorSession,
                commandId, requestHash, finalization);
    }

    private static void insertAccessAndAgent(
            String tenant,
            String caseId,
            String actorId,
            String role,
            String permission,
            String accessId,
            String agentSessionId,
            String promptProfileId,
            OffsetDateTime now) {
        jdbc.update(
                """
                insert into case_access_session (
                    id, tenant_id, case_id, actor_id, actor_role, permission_level,
                    permission_scopes_json, status, created_at, updated_at, created_by
                ) values (?, ?, ?, ?, ?, ?,
                    '["CASE_READ","INTAKE_PRIVATE_READ","INTAKE_PARTICIPATE","AGENT_SESSION_WRITE"]'::jsonb,
                    'ACTIVE', ?, ?, 'test')
                """,
                accessId, tenant, caseId, actorId, role, permission, now, now);
        jdbc.update(
                """
                insert into agent_conversation_session (
                    id, tenant_id, case_id, room_type, actor_id, actor_role, agent_key,
                    access_session_id, prompt_profile_id, memory_policy_id, conversation_scope,
                    status, created_at, updated_at, created_by
                ) values (?, ?, ?, 'INTAKE', ?, ?, 'DISPUTE_INTAKE_OFFICER', ?, ?,
                    'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1', ?, 'ACTIVE', ?, ?, 'test')
                """,
                agentSessionId, tenant, caseId, actorId, role, accessId,
                promptProfileId, "scope:" + actorId, now, now);
    }

    private static void insertGraphRegistration(
            String tenant,
            String caseId,
            String epochId,
            String actorId,
            String role,
            String accessId,
            String agentSessionId,
            String registrationId,
            String threadId,
            String actorScopeHash,
            OffsetDateTime now) {
        jdbc.update(
                """
                insert into case_intake_graph_thread_binding (
                    registration_id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, fencing_token, thread_id, actor_id, actor_role, audience,
                    actor_capabilities_json, actor_scope_hash, agent_session_id, graph_key,
                    graph_version, checkpoint_schema_version, state_schema_version,
                    prompt_version, model_profile_id, output_schema_version, policy_version,
                    guardrail_version, tool_policy_version, writer_mode, registration_hash,
                    registration_status, issued_at, registered_at, created_at
                ) values (?, 'graph-private-thread-registration.v1', ?, ?, 'INTAKE', 9, 41,
                    ?, ?, ?, ?, '["graph.command.execute"]'::jsonb, ?, ?, 'intake.v2',
                    '2.0.0', 'intake-checkpoint.v2', 'intake-graph-state.v2',
                    'intake-prompt.v2', 'intake-model.synthetic.v1', 'intake-turn-proposal.v2',
                    'intake-policy.v2', 'intake-guardrail.v2', 'no-tools.v1', 'SHADOW', ?,
                    'REGISTERED', ?, ?, ?)
                """,
                registrationId, tenant, caseId, threadId, actorId, role, role,
                actorScopeHash, agentSessionId, sha256("registration:" + registrationId),
                now, now, now);
    }

    private static void insertPartyAuthority(
            String authorityId,
            String epochId,
            String party,
            String tenant,
            String caseId,
            String registrationId,
            String threadId,
            String actorId,
            String actorRole,
            String actorScopeHash,
            String accessId,
            String permission,
            String agentSessionId,
            String promptProfileId,
            OffsetDateTime now) {
        jdbc.update(
                """
                insert into case_intake_epoch_party_authority (
                    authority_id, epoch_id, party, tenant_surrogate, case_id,
                    session_tenant_id, session_case_id, room_type, room_epoch, fencing_token,
                    registration_id, registration_hash, thread_id, actor_id, actor_role,
                    audience, actor_scope_hash, access_session_id, permission_level,
                    agent_session_id, agent_key, prompt_version, agent_session_profile_version,
                    prompt_profile_id, memory_policy_id, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, 'INTAKE', 9, 41, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'DISPUTE_INTAKE_OFFICER', 'intake-prompt.v2', 'agent-session-profile.v1', ?,
                    'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1', ?)
                """,
                authorityId, epochId, party, tenant, caseId, tenant, caseId,
                registrationId, sha256("registration:" + registrationId), threadId,
                actorId, actorRole, actorRole, actorScopeHash, accessId, permission,
                agentSessionId, promptProfileId, now);
    }

    private static void terminalize(Fixture fixture) {
        assertThat(jdbc.update(
                        "update case_room_epoch set lifecycle_status = 'TERMINAL', terminal_at = ?, "
                                + "process_revision = process_revision + 1, updated_at = ? "
                                + "where id = ?",
                        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                        fixture.epochId()))
                .isOne();
    }

    private static int countComparisons(Fixture fixture) {
        return jdbc.queryForObject(
                "select count(*) from case_intake_shadow_comparison where case_id = ?",
                Integer.class,
                fixture.caseId());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(
            String tenant,
            String caseId,
            String epochId,
            long roomEpoch,
            long fencingToken,
            String initiatorAuthorityId,
            String threadId,
            String actorScopeHash,
            String agentSessionId,
            String commandId,
            String requestHash,
            com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest finalization) {}
}
