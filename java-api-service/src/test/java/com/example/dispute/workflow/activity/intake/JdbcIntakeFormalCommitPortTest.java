package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeFinalizationOperationKey;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeFormalCommitPort;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.IntakeGraphFinalizationRequest;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakeImmutableProposalReader;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.example.dispute.workflow.application.intake.IntakeTurnProposalLoader;
import com.example.dispute.room.infrastructure.persistence.JdbcIntakeFormalCommitPort;
import com.example.dispute.room.infrastructure.persistence.JdbcIntakeGraphBindingStore;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult.ExecutionMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL proof for the real Intake operation ledger and formal write transaction. */
@Testcontainers
class JdbcIntakeFormalCommitPortTest {

    private static final String DB = "intake_formal_commit";
    private static final String USER = "dispute_test";
    private static final String PASSWORD = "local_test_password";
    private static final Instant NOW = Instant.parse("2026-07-20T08:03:00Z");
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
    private static JdbcIntakeFormalCommitPort port;
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
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        transactions = new DataSourceTransactionManager(dataSource);
        port = new JdbcIntakeFormalCommitPort(
                new NamedParameterJdbcTemplate(dataSource),
                transactions,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void commitFollowedByLostActivityCompletionReplaysThePersistedReceiptExactly() {
        Fixture fixture = fixture("REPLAY_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture);

        IntakeFinalizationReceipt first = port.commit(fixture.commitCommand());
        IntakeFinalizationReceipt replay = port.commit(fixture.commitCommand());

        assertThat(replay).isEqualTo(first);
        assertCounts(fixture.caseId(), 1, 1, 1, 1, 1, 1);
        assertThat(scalar("select operation_status from domain_operation where operation_key = ?",
                        fixture.request().operationKey()))
                .isEqualTo("COMPLETED");
        assertThat(port.findCommitted(
                        fixture.tenant(),
                        fixture.request().operationKey(),
                        fixture.request().requestHash()))
                .contains(first);
    }

    @Test
    void sameOperationKeyWithAnotherCanonicalRequestHashIsAConflict() {
        Fixture fixture = fixture("CONFLICT_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture);
        IntakeFinalizationReceipt committed = port.commit(fixture.commitCommand());

        IntakeGraphFinalizationRequest.Authority changedAuthority =
                new IntakeGraphFinalizationRequest.Authority(
                        fixture.authority().tenantSurrogate(),
                        fixture.authority().caseId(),
                        fixture.authority().roomEpoch(),
                        fixture.authority().fencingToken(),
                        fixture.authority().threadId(),
                        fixture.authority().actorScopeHash(),
                        fixture.authority().agentSessionId(),
                        fixture.authority().commandId(),
                        fixture.authority().logicalRunId(),
                        fixture.authority().attemptId(),
                        fixture.authority().resultHash(),
                        fixture.authority().proposalHash(),
                        fixture.authority().checkpointId(),
                        fixture.authority().cognitiveRevision(),
                        fixture.authority().processRevision() + 1,
                        fixture.authority().roomRevision(),
                        fixture.authority().stageCode(),
                        fixture.authority().stageSequence(),
                        fixture.authority().profileVersions());
        IntakeGraphFinalizationRequest unsigned = new IntakeGraphFinalizationRequest(
                fixture.request().operationKey(),
                "0".repeat(64),
                changedAuthority,
                fixture.command(),
                fixture.result(),
                fixture.binding(),
                fixture.snapshot(),
                fixture.event(),
                fixture.proposalReference());
        IntakeGraphFinalizationRequest changed = new IntakeGraphFinalizationRequest(
                unsigned.operationKey(),
                unsigned.canonicalRequestHash(),
                unsigned.authority(),
                unsigned.command(),
                unsigned.result(),
                unsigned.threadBinding(),
                unsigned.initialSnapshot(),
                unsigned.event(),
                unsigned.proposalReference());
        IntakeFormalCommitPort.CommitCommand conflict = commitCommand(changed, fixture);

        assertThatThrownBy(() -> port.commit(conflict))
                .isInstanceOf(com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException.class)
                .hasMessageContaining("another canonical request");
        assertThat(committed).isNotNull();
        assertCounts(fixture.caseId(), 1, 1, 1, 1, 1, 1);
    }

    @Test
    void staleProjectionAuthorityRollsBackTheInsertedOperationAndWritesNothing() {
        Fixture fixture = fixture("STALE_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture);
        jdbc.update(
                "update case_process_projection set room_phase = 'WAITING_PARTY' where case_id = ?",
                fixture.caseId());

        assertThatThrownBy(() -> port.commit(fixture.commitCommand()))
                .isInstanceOf(com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException.class)
                .hasMessageContaining("stage");
        assertCounts(fixture.caseId(), 0, 0, 0, 0, 0, 0);
    }

    @Test
    void revokedAgentSessionRollsBackTheLedgerAndEveryFormalEffect() {
        Fixture fixture = fixture("REVOKED_SESSION_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture);
        jdbc.update(
                "update agent_conversation_session set status = 'REVOKED' where id = ?",
                fixture.binding().registration().agentSessionId());

        assertThatThrownBy(() -> port.commit(fixture.commitCommand()))
                .isInstanceOf(com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException.class)
                .hasMessageContaining("Agent Session");

        assertCounts(fixture.caseId(), 0, 0, 0, 0, 0, 0);
    }

    @Test
    void nonResultReadyAttemptRollsBackTheLedgerAndEveryFormalEffect() {
        Fixture fixture = fixture("NOT_READY_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture);
        jdbc.update(
                "update agent_run_attempt set attempt_status = 'RUNNING' where id = ?",
                fixture.authority().attemptId());

        assertThatThrownBy(() -> port.commit(fixture.commitCommand()))
                .isInstanceOf(com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException.class)
                .hasMessageContaining("result-ready");

        assertCounts(fixture.caseId(), 0, 0, 0, 0, 0, 0);
    }

    @Test
    void failureAfterDomainEffectsRollsBackWhenTheOuterManifestTransactionFails() {
        Fixture fixture = fixture("OUTER_ROLLBACK_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture);
        TransactionTemplate outer = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> outer.execute(status -> {
                    port.commit(fixture.commitCommand());
                    throw new IllegalStateException("simulated manifest failure");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated manifest failure");
        assertCounts(fixture.caseId(), 0, 0, 0, 0, 0, 0);
    }

    @Test
    void preflightSeesRegisteredBindingCreatedByTheCurrentWritableTransaction() {
        Fixture fixture = fixture("PREFLIGHT_CURRENT_TX_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture, false);
        TransactionTemplate outer = new TransactionTemplate(transactions);

        outer.executeWithoutResult(status -> {
            jdbc.update(
                    "update case_intake_graph_thread_binding"
                            + " set registration_status = 'REGISTERED', registered_at = current_timestamp"
                            + " where registration_id = ?",
                    fixture.binding().registration().registrationId());
            port.preflight(fixture.request());
        });
        assertThat(scalar(
                        "select registration_status from case_intake_graph_thread_binding"
                                + " where registration_id = ?",
                        fixture.binding().registration().registrationId()))
                .isEqualTo("REGISTERED");
    }

    @Test
    void independentPreflightStillRejectsAnUnregisteredBinding() {
        Fixture fixture = fixture("PREFLIGHT_INDEPENDENT_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture, false);

        assertThatThrownBy(() -> port.preflight(fixture.request()))
                .isInstanceOf(
                        com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException.class)
                .hasMessageContaining("no longer active");
    }

    @Test
    void authorityParametersUseExecutionOutputSchemaWhenProposalAndExecutionSchemasDiffer() {
        Fixture fixture = fixture("PREFLIGHT_EXECUTION_SCHEMA_" + SEQUENCE.incrementAndGet());
        String executionSchema = "target-e2e-room-proposal-source.v1";
        IntakeGraphFinalizationRequest request = requestWithExecutionOutputSchema(fixture, executionSchema);

        MapSqlParameterSource parameters = authorityParameters(request);

        assertThat(request.authority().profileVersions().outputSchemaVersion())
                .isEqualTo("intake-turn-proposal.v2");
        assertThat(parameters.getValue("outputSchemaVersion")).isEqualTo(executionSchema);
    }

    @Test
    void preflightRejectsAnActorNoLongerAssignedAsTheExplicitCaseParty() {
        Fixture fixture = fixture("PARTY_REBOUND_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture);
        String replacement = "replacement-" + fixture.caseId();
        jdbc.update(
                "update fulfillment_dispute_case set user_id = ?, initiator_id = ? where id = ?",
                replacement,
                replacement,
                fixture.caseId());

        assertThatThrownBy(() -> port.preflight(fixture.request()))
                .isInstanceOf(
                        com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException.class)
                .hasMessageContaining("formalizable Intake state");
        assertCounts(fixture.caseId(), 0, 0, 0, 0, 0, 0);
    }

    @Test
    void transactionRejectsACompletedPartyWithoutWritingAnyFormalEffect() {
        Fixture fixture = fixture("PARTY_COMPLETED_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture);
        insertPartyCompletion(
                fixture,
                ActorRole.USER,
                fixture.binding().registration().actorScope().actorId());

        assertThatThrownBy(() -> port.commit(fixture.commitCommand()))
                .isInstanceOf(
                        com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException.class)
                .hasMessageContaining("no longer active");
        assertCounts(fixture.caseId(), 0, 0, 0, 0, 0, 0);
    }

    @Test
    void respondentRequiresExplicitInitiatorCompletionBeforePreflightAndCommit() {
        Fixture fixture = fixture(
                "RESPONDENT_LOCK_" + SEQUENCE.incrementAndGet(), ActorRole.MERCHANT);
        insertFixture(fixture);

        assertThatThrownBy(() -> port.preflight(fixture.request()))
                .isInstanceOf(
                        com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException.class)
                .hasMessageContaining("no longer active");

        String initiatorId = scalar(
                "select initiator_id from fulfillment_dispute_case where id = ?",
                fixture.caseId());
        insertPartyCompletion(fixture, ActorRole.USER, initiatorId);
        jdbc.update(
                "update fulfillment_dispute_case set case_status = 'INTAKE_COMPLETED' where id = ?",
                fixture.caseId());

        port.preflight(fixture.request());
        assertThat(port.commit(fixture.commitCommand()).caseId()).isEqualTo(fixture.caseId());
        assertCounts(fixture.caseId(), 1, 1, 1, 1, 1, 1);
    }

    @Test
    void rehashedRequestCannotChangePersistedSnapshotMetadata() {
        Fixture fixture = fixture("SNAPSHOT_MUTATION_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture);
        IntakeSnapshotReference source = fixture.snapshot();
        IntakeSnapshotReference changed = new IntakeSnapshotReference(
                source.bindingId(),
                source.threadRegistrationId(),
                source.tenantSurrogate(),
                source.caseId(),
                source.roomEpoch(),
                source.fencingToken(),
                source.threadId(),
                source.actorScopeHash(),
                source.agentSessionId(),
                source.payloadRef(),
                "version-mutated",
                source.domainRevision(),
                source.roomRevision(),
                source.projectionRevision(),
                source.initialLastSequence(),
                source.createdAt());
        IntakeGraphFinalizationRequest request = requestWith(fixture, changed, fixture.event());

        assertThatThrownBy(() -> port.commit(commitCommand(request, fixture)))
                .isInstanceOf(
                        com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException.class)
                .hasMessageContaining("initial snapshot");
        assertCounts(fixture.caseId(), 0, 0, 0, 0, 0, 0);
    }

    @Test
    void rehashedRequestCannotChangePersistedEventMetadata() {
        Fixture fixture = fixture("EVENT_MUTATION_" + SEQUENCE.incrementAndGet());
        insertFixture(fixture);
        IntakeEventReference source = fixture.event();
        IntakeEventReference changed = new IntakeEventReference(
                source.bindingId(),
                source.threadRegistrationId(),
                source.eventId(),
                source.messageId(),
                source.tenantSurrogate(),
                source.caseId(),
                source.roomEpoch(),
                source.fencingToken(),
                source.threadId(),
                source.actorScopeHash(),
                source.agentSessionId(),
                source.payloadRef(),
                source.objectVersion(),
                source.sequenceNo(),
                source.domainRevision(),
                source.audience(),
                source.occurredAt(),
                source.createdAt().plusSeconds(1));
        IntakeGraphFinalizationRequest request = requestWith(fixture, fixture.snapshot(), changed);

        assertThatThrownBy(() -> port.commit(commitCommand(request, fixture)))
                .isInstanceOf(
                        com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException.class)
                .hasMessageContaining("turn event");
        assertCounts(fixture.caseId(), 0, 0, 0, 0, 0, 0);
    }

    private static void assertCounts(
            String caseId,
            int messages,
            int dossiers,
            int events,
            int outbox,
            int audits,
            int operations) {
        assertThat(count("select count(*) from room_message where case_id = ?", caseId))
                .isEqualTo(messages);
        assertThat(count("select count(*) from case_intake_dossier where case_id = ?", caseId))
                .isEqualTo(dossiers);
        assertThat(count("select count(*) from case_timeline_event where case_id = ?", caseId))
                .isEqualTo(events);
        assertThat(count("select count(*) from notification_outbox where case_id = ?", caseId))
                .isEqualTo(outbox);
        assertThat(count(
                        "select count(*) from audit_log where case_id = ? and resource_id = ?",
                        caseId,
                        caseId))
                .isEqualTo(audits);
        assertThat(count("select count(*) from domain_operation where case_id = ?", caseId))
                .isEqualTo(operations);
    }

    private static long count(String sql, Object... values) {
        return jdbc.queryForObject(sql, Long.class, values);
    }

    private static String scalar(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private static IntakeFormalCommitPort.CommitCommand commitCommand(
            IntakeGraphFinalizationRequest request, Fixture fixture) {
        var actor = request.threadBinding().registration().actorScope();
        return new IntakeFormalCommitPort.CommitCommand(
                request,
                fixture.loadedProposal(),
                new IntakeFormalCommitPort.CurrentAuthorityRequirement(
                        request.authority().tenantSurrogate(),
                        request.authority().caseId(),
                        request.authority().roomEpoch(),
                        request.authority().fencingToken(),
                        request.authority().processRevision(),
                        request.authority().roomRevision(),
                        request.authority().stageCode(),
                        request.authority().stageSequence(),
                        actor.actorId(),
                        actor.actorRole(),
                        actor.audience(),
                        request.authority().actorScopeHash(),
                        request.authority().agentSessionId()),
                new IntakeFormalCommitPort.AgentRunFinalEligibilityRequirement(
                        request.authority().caseId(),
                        request.authority().commandId(),
                        request.authority().logicalRunId(),
                        request.authority().attemptId(),
                        request.authority().resultHash(),
                        request.authority().proposalHash(),
                        request.authority().checkpointId(),
                        request.authority().cognitiveRevision(),
                        request.authority().fencingToken()));
    }

    private static Fixture fixture(String suffix) {
        return fixture(suffix, ActorRole.USER);
    }

    private static Fixture fixture(String suffix, ActorRole actorRole) {
        String caseId = "CASE_JDBC_" + suffix;
        String tenant = "tenant-jdbc";
        String threadId = "grt.v1." + sha256(suffix).substring(0, 32);
        String registrationId = "REG_JDBC_" + suffix;
        String sessionId = "AGENT_SESSION_JDBC_" + suffix;
        String commandId = "COMMAND_JDBC_" + suffix;
        String runId = "RUN_JDBC_" + suffix;
        String attemptId = "ATTEMPT_JDBC_" + suffix;
        Instant issued = NOW.minus(5, ChronoUnit.MINUTES);
        Audience audience = actorRole == ActorRole.USER ? Audience.USER : Audience.MERCHANT;
        String actorId = (actorRole == ActorRole.USER ? "user-jdbc-" : "merchant-jdbc-")
                + suffix;
        var actor = new IntakePrivateThreadRegistration.ActorScope(
                actorId,
                actorRole,
                audience,
                List.of("graph.command.execute"));
        IntakeGraphThreadBinding binding =
                new IntakePrivateThreadRegistrationFactory(() -> threadId)
                        .issue(new IntakePrivateThreadRegistrationFactory.IssueRequest(
                                registrationId,
                                tenant,
                                caseId,
                                1,
                                2,
                                actor,
                                sessionId,
                                new IntakePrivateThreadRegistrationFactory.VersionPins(
                                        "2.0.0",
                                        "intake-checkpoint.v2",
                                        "intake-prompt.v2",
                                        "intake-model.synthetic.v1",
                                        "intake-policy.v2",
                                        "intake-guardrail.v2",
                                        "no-tools.v1"),
                                WriterMode.TEMPORAL,
                                issued));
        IntakeSnapshotReference snapshot = new IntakeSnapshotReference(
                "SNAPSHOT_JDBC_" + suffix,
                registrationId,
                tenant,
                caseId,
                1,
                2,
                threadId,
                binding.registration().actorScopeHash(),
                sessionId,
                new RoomGraphCommand.SnapshotRef(
                        "SNAPSHOT_JDBC_" + suffix,
                        "intake-domain-snapshot.v2",
                        "urn:intake:snapshot:" + suffix,
                        sha256("snapshot:" + suffix),
                        1024),
                "version-1",
                4,
                3,
                4,
                1,
                issued.plusSeconds(1));
        IntakeEventReference event = new IntakeEventReference(
                "EVENT_JDBC_" + suffix,
                registrationId,
                "EVENT_JDBC_" + suffix,
                "MESSAGE_PARTY_JDBC_" + suffix,
                tenant,
                caseId,
                1,
                2,
                threadId,
                binding.registration().actorScopeHash(),
                sessionId,
                new RoomGraphCommand.SnapshotRef(
                        "EVENT_JDBC_" + suffix,
                        "intake-turn-event.v2",
                        "urn:intake:event:" + suffix,
                        sha256("event:" + suffix),
                        512),
                "version-1",
                2,
                5,
                audience,
                issued.plusSeconds(2),
                issued.plusSeconds(3));
        RoomGraphCommand command = new IntakeGraphCommandFactory().create(
                new IntakeGraphCommandFactory.CommandRequest(
                        commandId,
                        runId,
                        attemptId,
                        binding,
                        snapshot,
                        event,
                        5,
                        "INTAKE_ACTIVE",
                        2,
                        "intake-agent.v2",
                        2,
                        3,
                        1,
                        NOW.plus(5, ChronoUnit.MINUTES),
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                        "graph-envelope.synthetic.v1",
                        "nonce-" + suffix));
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("schema_version", "intake-dossier.v2");
        patch.putObject("case_story").put("summary", "JDBC integration turn");
        patch.putObject("requested_resolution").put("kind", "REFUND");
        IntakeTurnProposal.ProfileVersions profiles = new IntakeTurnProposal.ProfileVersions(
                command.graphVersion(),
                command.checkpointSchemaVersion(),
                command.invocationContext().promptProfileId(),
                command.invocationContext().modelProfileId(),
                command.invocationContext().outputSchemaVersion(),
                command.invocationContext().policyVersion(),
                command.invocationContext().guardrailVersion(),
                binding.registration().toolPolicyVersion());
        IntakeTurnProposal unsignedProposal = new IntakeTurnProposal(
                "intake-turn-proposal.v2",
                commandId,
                runId,
                attemptId,
                caseId,
                1,
                threadId,
                binding.registration().actorScopeHash(),
                sessionId,
                2,
                snapshot.payloadRef().sha256(),
                event.payloadRef().sha256(),
                "The requested resolution is refund.",
                patch,
                null,
                IntakeTurnProposal.Readiness.INCOMPLETE,
                List.of("requested_resolution_detail"),
                IntakeTurnProposal.Recommendation.NEED_MORE_INFO,
                IntakeTurnProposal.KnowledgeAnswerMode.NONE,
                new java.math.BigDecimal("0.82"),
                profiles,
                "0".repeat(64));
        JsonNode unsignedTree = objectMapper.valueToTree(unsignedProposal);
        String proposalHash = IntakeContractHashes.canonicalHashExcluding(
                unsignedTree, "proposal_hash");
        IntakeTurnProposal proposal = new IntakeTurnProposal(
                unsignedProposal.schemaVersion(),
                unsignedProposal.commandId(),
                unsignedProposal.logicalRunId(),
                unsignedProposal.attemptId(),
                unsignedProposal.caseId(),
                unsignedProposal.roomEpoch(),
                unsignedProposal.threadId(),
                unsignedProposal.actorScopeHash(),
                unsignedProposal.agentSessionId(),
                unsignedProposal.cognitiveRevision(),
                unsignedProposal.sourceSnapshotHash(),
                unsignedProposal.sourceEventHash(),
                unsignedProposal.roomUtterance(),
                unsignedProposal.dossierPatch(),
                unsignedProposal.matrixPatch(),
                unsignedProposal.readiness(),
                unsignedProposal.missingFields(),
                unsignedProposal.recommendation(),
                unsignedProposal.knowledgeAnswerMode(),
                unsignedProposal.confidence(),
                unsignedProposal.profileVersions(),
                proposalHash);
        IntakeImmutableProposalReader.StoredProposal stored = new IntakeImmutableProposalReader.StoredProposal(
                "PROPOSAL_JDBC_" + suffix,
                "intake-turn-proposal.v2",
                "urn:intake:proposal:" + suffix,
                "version-1",
                proposalHash,
                4096,
                ContractJson.canonicalize(objectMapper.valueToTree(proposal)));
        IntakeGraphFinalizationRequest.Authority authority =
                new IntakeGraphFinalizationRequest.Authority(
                        tenant,
                        caseId,
                        1,
                        2,
                        threadId,
                        binding.registration().actorScopeHash(),
                        sessionId,
                        commandId,
                        runId,
                        attemptId,
                        resultHash(command, proposalHash),
                        proposalHash,
                        "CHECKPOINT_JDBC_" + suffix,
                        2,
                        5,
                        3,
                        "INTAKE_ACTIVE",
                        2,
                        profiles);
        RoomGraphResult result = result(command, stored, authority.checkpointId());
        authority = new IntakeGraphFinalizationRequest.Authority(
                tenant, caseId, 1, 2, threadId, binding.registration().actorScopeHash(), sessionId,
                commandId, runId, attemptId, result.outputHash(), proposalHash, result.checkpointId(),
                result.cognitiveRevision(), 5, 3, "INTAKE_ACTIVE", 2, profiles);
        String operationKey = IntakeFinalizationOperationKey.create(
                caseId, 1, threadId, commandId, result.outputHash());
        IntakeGraphFinalizationRequest unsignedRequest = new IntakeGraphFinalizationRequest(
                operationKey,
                "0".repeat(64),
                authority,
                command,
                result,
                binding,
                snapshot,
                event,
                new com.example.dispute.workflow.application.intake.IntakeProposalReference(
                        stored.artifactId(), stored.schemaVersion(), stored.uri(),
                        stored.objectVersion(), stored.contentSha256(), stored.sizeBytes()));
        IntakeGraphFinalizationRequest request = new IntakeGraphFinalizationRequest(
                unsignedRequest.operationKey(),
                unsignedRequest.canonicalRequestHash(),
                authority,
                command,
                result,
                binding,
                snapshot,
                event,
                unsignedRequest.proposalReference());
        return new Fixture(
                caseId,
                tenant,
                binding,
                snapshot,
                event,
                command,
                result,
                request,
                authority,
                new IntakeTurnProposalLoader.LoadedProposal(request.proposalReference(), proposal),
                stored);
    }

    private static IntakeGraphFinalizationRequest requestWith(
            Fixture fixture,
            IntakeSnapshotReference snapshot,
            IntakeEventReference event) {
        IntakeGraphFinalizationRequest unsigned = new IntakeGraphFinalizationRequest(
                fixture.request().operationKey(),
                "0".repeat(64),
                fixture.authority(),
                fixture.command(),
                fixture.result(),
                fixture.binding(),
                snapshot,
                event,
                fixture.proposalReference());
        return new IntakeGraphFinalizationRequest(
                unsigned.operationKey(),
                unsigned.canonicalRequestHash(),
                unsigned.authority(),
                unsigned.command(),
                unsigned.result(),
                unsigned.threadBinding(),
                unsigned.initialSnapshot(),
                unsigned.event(),
                unsigned.proposalReference());
    }

    private static IntakeGraphFinalizationRequest requestWithExecutionOutputSchema(
            Fixture fixture, String executionOutputSchemaVersion) {
        IntakeGraphFinalizationRequest.Authority source = fixture.authority();
        IntakeGraphFinalizationRequest.Authority authority =
                new IntakeGraphFinalizationRequest.Authority(
                        source.tenantSurrogate(),
                        source.caseId(),
                        source.roomEpoch(),
                        source.fencingToken(),
                        source.threadId(),
                        source.actorScopeHash(),
                        source.agentSessionId(),
                        source.commandId(),
                        source.logicalRunId(),
                        source.attemptId(),
                        source.resultHash(),
                        source.proposalHash(),
                        source.checkpointId(),
                        source.cognitiveRevision(),
                        source.processRevision(),
                        source.roomRevision(),
                        source.stageCode(),
                        source.stageSequence(),
                        source.profileVersions(),
                        executionOutputSchemaVersion);
        IntakeGraphFinalizationRequest unsigned = new IntakeGraphFinalizationRequest(
                fixture.request().operationKey(),
                "0".repeat(64),
                authority,
                fixture.command(),
                fixture.result(),
                fixture.binding(),
                fixture.snapshot(),
                fixture.event(),
                fixture.proposalReference());
        return new IntakeGraphFinalizationRequest(
                unsigned.operationKey(),
                unsigned.canonicalRequestHash(),
                unsigned.authority(),
                unsigned.command(),
                unsigned.result(),
                unsigned.threadBinding(),
                unsigned.initialSnapshot(),
                unsigned.event(),
                unsigned.proposalReference());
    }

    private static MapSqlParameterSource authorityParameters(IntakeGraphFinalizationRequest request) {
        try {
            var method = JdbcIntakeFormalCommitPort.class.getDeclaredMethod(
                    "authorityParameters", IntakeGraphFinalizationRequest.class);
            method.setAccessible(true);
            return (MapSqlParameterSource) method.invoke(port, request);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("unable to inspect formal-commit authority parameters", failure);
        }
    }

    private static void insertPartyCompletion(
            Fixture fixture, ActorRole role, String participantId) {
        jdbc.update(
                """
                insert into case_intake_party_completion (
                    id, case_id, participant_role, participant_id, completion_status,
                    completed_at, created_at, created_by
                ) values (?, ?, ?, ?, 'COMPLETED', ?, ?, 'test')
                """,
                "COMPLETION_" + fixture.caseId() + '_' + role.name(),
                fixture.caseId(),
                role.name(),
                participantId,
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC));
    }

    private static String resultHash(RoomGraphCommand command, String proposalHash) {
        return "a".repeat(64);
    }

    private static RoomGraphResult result(
            RoomGraphCommand command,
            IntakeImmutableProposalReader.StoredProposal proposal,
            String checkpointId) {
        RoomGraphResult unsigned = new RoomGraphResult(
                "room-graph-result.v1",
                command.commandId(),
                command.logicalRunId(),
                command.attemptId(),
                command.graphKey(),
                command.graphVersion(),
                checkpointId,
                2,
                GraphStatus.COMPLETED,
                List.of(),
                List.of(new RoomGraphResult.ArtifactOperation(
                        ArtifactOperationType.PROPOSE_PATCH,
                        new com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer(
                                proposal.artifactId(), proposal.schemaVersion(), proposal.uri(),
                                proposal.contentSha256()))),
                null,
                null,
                null,
                "0".repeat(64),
                new Usage(10, 5, 15),
                new ExecutionMetadata(
                        command.invocationContext().promptProfileId(),
                        command.invocationContext().modelProfileId(),
                        command.invocationContext().outputSchemaVersion(),
                        command.invocationContext().policyVersion(),
                        command.invocationContext().guardrailVersion()));
        return new RoomGraphResult(
                unsigned.schemaVersion(), unsigned.commandId(), unsigned.logicalRunId(),
                unsigned.attemptId(), unsigned.graphKey(), unsigned.graphVersion(),
                unsigned.checkpointId(), unsigned.cognitiveRevision(), unsigned.status(),
                unsigned.publicEventProposals(), unsigned.artifactOperations(), unsigned.needsInput(),
                unsigned.needsReview(), unsigned.error(), IntakeContractHashes.graphResultHash(unsigned),
                unsigned.usage(), unsigned.executionMetadata());
    }

    private static void insertFixture(Fixture fixture) {
        insertFixture(fixture, true);
    }

    private static void insertFixture(Fixture fixture, boolean registerBinding) {
        String c = fixture.caseId();
        String tenant = fixture.tenant();
        String roomId = "ROOM_" + c;
        String epochId = "EPOCH_" + c;
        String actorId = fixture.binding().registration().actorScope().actorId();
        ActorRole actorRole = fixture.binding().registration().actorScope().actorRole();
        String userId = actorRole == ActorRole.USER ? actorId : "user-" + c;
        String merchantId = actorRole == ActorRole.MERCHANT ? actorId : "merchant-" + c;
        String sessionId = fixture.binding().registration().agentSessionId();
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key, case_type,
                    case_status, initiator_role, initiator_id, respondent_role, respondent_id,
                    risk_level, title, description, current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'INTAKE_IN_PROGRESS', 'USER', ?,
                    'MERCHANT', ?, 'MEDIUM', 'JDBC Intake', 'formal ledger fixture', 'INTAKE', 'test', 'test')
                """, c, userId, merchantId, "create-" + c, userId, merchantId);
        jdbc.update("insert into case_participant (id, case_id, actor_id, participant_role, participant_status, joined_at, created_at, updated_at, created_by, updated_by) values (?, ?, ?, 'USER', 'ACTIVE', ?, ?, ?, 'test', 'test')",
                "PART_USER_" + c, c, userId, now, now, now);
        jdbc.update("insert into case_participant (id, case_id, actor_id, participant_role, participant_status, joined_at, created_at, updated_at, created_by, updated_by) values (?, ?, ?, 'MERCHANT', 'ACTIVE', ?, ?, ?, 'test', 'test')",
                "PART_MERCHANT_" + c, c, merchantId, now, now, now);
        jdbc.update("insert into case_room (id, case_id, room_type, room_status, opened_at, created_by, updated_by) values (?, ?, 'INTAKE', 'OPEN', ?, 'test', 'test')",
                roomId, c, now);
        jdbc.update("""
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase, writer_mode,
                    writer_activation_status, process_revision, room_epoch, fencing_token,
                    last_command_sequence, last_case_event_sequence, temporal_workflow_id,
                    temporal_run_id, temporal_build_id, projected_at, updated_at
                ) values (?, ?, 'INTAKE', 'INTAKE', 'INTAKE_ACTIVE', 'TEMPORAL', 'READY',
                    5, 1, 2, 2, 0, ?, ?, 'jdbc-build', ?, ?)
                """, c, tenant, "CASE_WORKFLOW_" + c, "CASE_RUN_" + c, now, now);
        jdbc.update("""
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch, writer_mode,
                    lifecycle_status, provisioning_status, process_revision, room_revision,
                    fencing_token, temporal_workflow_id, temporal_run_id, room_temporal_workflow_id,
                    room_temporal_run_id, temporal_build_id, graph_key, graph_version,
                    checkpoint_schema_version, stream_protocol, selection_schema_version,
                    process_contract_version, workflow_type, room_workflow_type, room_workflow_build_id,
                    activated_at, provisioned_at, created_at, updated_at
                ) values (?, ?, ?, ?, 'INTAKE', 1, 'TEMPORAL', 'ACTIVE', 'READY', 5, 3, 2,
                    ?, ?, ?, ?, 'jdbc-build', 'intake.v2', '2.0.0', 'intake-checkpoint.v2',
                    'agent-stream.v2', 'room-epoch-selection.v2', 'case-process-contract.v1',
                    'CaseProcessWorkflow', 'IntakeRoomWorkflow', 'jdbc-room-build', ?, ?, ?, ?)
                """, epochId, tenant, c, roomId, "CASE_WORKFLOW_" + c, "CASE_RUN_" + c,
                "ROOM_WORKFLOW_" + c, "ROOM_RUN_" + c, now, now, now, now);
        jdbc.update("insert into case_access_session (id, tenant_id, case_id, actor_id, actor_role, permission_level, permission_scopes_json, status, created_at, updated_at, created_by) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), 'ACTIVE', ?, ?, 'test')",
                "ACCESS_" + c, tenant, c, actorId, actorRole.name(),
                actorRole == ActorRole.USER ? "PARTY_USER" : "PARTY_MERCHANT",
                "[\"CASE_READ\",\"INTAKE_PRIVATE_READ\",\"INTAKE_PARTICIPATE\",\"AGENT_SESSION_WRITE\"]", now, now);
        jdbc.update("insert into agent_conversation_session (id, tenant_id, case_id, room_type, actor_id, actor_role, agent_key, access_session_id, prompt_profile_id, memory_policy_id, conversation_scope, status, created_at, updated_at, created_by) values (?, ?, ?, 'INTAKE', ?, ?, 'DISPUTE_INTAKE_OFFICER', ?, 'intake-prompt.v2', 'MEMORY_DEFAULT', ?, 'ACTIVE', ?, ?, 'test')",
                sessionId, tenant, c, actorId, actorRole.name(), "ACCESS_" + c, "scope:" + c, now, now);

        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc.getDataSource());
        JdbcIntakeGraphBindingStore bindings = new JdbcIntakeGraphBindingStore(named);
        bindings.register(fixture.binding());
        if (registerBinding) {
            jdbc.update(
                    "update case_intake_graph_thread_binding"
                            + " set registration_status = 'REGISTERED', registered_at = created_at"
                            + " where registration_id = ?",
                    fixture.binding().registration().registrationId());
        }
        bindings.bindInitialSnapshot(fixture.snapshot());
        bindings.bindEvent(fixture.event());

        String run = fixture.command().logicalRunId();
        String attempt = fixture.command().attemptId();
        String requestHash = fixture.command().requestHash();
        String resultHash = fixture.result().outputHash();
        jdbc.update("""
                insert into agent_run (
                    id, case_id, room_id, agent_id, agent_role, profile_version, prompt_version,
                    skill_version, ruleset_version, model, run_status, input_refs_json,
                    validation_json, risk_flags_json, started_at, trace_id, created_by,
                    stream_operation, stream_endpoint, stream_request_json, stream_request_hash,
                    stream_audience_json, stream_audience_actor_ids_json, stream_idempotency_key,
                    stream_request_id, updated_at, tenant_surrogate, protocol, logical_idempotency_key,
                    executor_kind, finalization_status, room_epoch_id, room_type, room_epoch,
                    process_revision, fencing_token, request_hash, attempt_limit, deadline_at,
                    result_ready_attempt_id, final_result_hash, lineage_schema_version, logical_input_hash
                ) values (?, ?, ?, 'agent-stream:intake', 'SYSTEM', 'runtime', 'intake-prompt.v2',
                    'intake-skill.v2', 'agent-stream.v2', 'synthetic', 'RESULT_READY', '{}'::jsonb,
                    '{}'::jsonb, '[]'::jsonb, ?, 'trace-jdbc', 'test', 'INTAKE_TURN',
                    'internal://graph', '{}'::jsonb, ?, '[]'::jsonb, '[]'::jsonb, ?, ?, ?, ?,
                    'agent-stream.v2', ?, 'TEMPORAL_ACTIVITY', 'UNCOMMITTED', ?, 'INTAKE', 1, 5, 2, ?, 1, ?, null, ?,
                     'agent-run-lineage.v1', ?)
                """, run, c, roomId, now, requestHash, "key:" + run, run, now, tenant, requestHash,
                epochId, requestHash, now.plusMinutes(5), resultHash, "e".repeat(64));
        jdbc.update("""
                insert into agent_run_attempt (
                    id, agent_run_id, attempt_no, attempt_status, executor_kind, provider,
                    model_profile_id, model_version, graph_key, graph_version, checkpoint_schema_version,
                    checkpoint_id, prompt_version, output_schema_version, policy_version, guardrail_version,
                    request_hash, lineage_schema_version, command_id, command_request_hash,
                    logical_input_hash, command_json, reset_required, public_sequence_offset,
                    result_hash, result_json, input_tokens, output_tokens, total_tokens, latency_ms,
                    public_output_emitted, final_frame_observed, last_sequence_no, started_at,
                    completed_at, created_at, updated_at, created_by
                ) values (?, ?, 1, 'RESULT_READY', 'TEMPORAL_ACTIVITY', 'synthetic',
                    'intake-model.synthetic.v1', 'synthetic-1', 'intake.v2', '2.0.0',
                    'intake-checkpoint.v2', ?, 'intake-prompt.v2', 'intake-turn-proposal.v2',
                    'intake-policy.v2', 'intake-guardrail.v2', ?, 'agent-run-attempt-lineage.v1',
                    ?, ?, ?, cast(? as jsonb), false, 0, ?, '{}'::jsonb, 10, 5, 15, 1,
                    true, true, 7, ?, ?, ?, ?, 'test')
                """, attempt, run, fixture.authority().checkpointId(), requestHash,
                fixture.command().commandId(), requestHash, "e".repeat(64),
                ContractJson.canonicalString(objectMapper.valueToTree(fixture.command())), resultHash,
                now, now, now, now);
        jdbc.update(
                "update agent_run set result_ready_attempt_id = ?, final_result_hash = ? where id = ?",
                attempt,
                resultHash,
                run);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 must be available", impossible);
        }
    }

    private record Fixture(
            String caseId,
            String tenant,
            IntakeGraphThreadBinding binding,
            IntakeSnapshotReference snapshot,
            IntakeEventReference event,
            RoomGraphCommand command,
            RoomGraphResult result,
            IntakeGraphFinalizationRequest request,
            IntakeGraphFinalizationRequest.Authority authority,
            IntakeTurnProposalLoader.LoadedProposal loadedProposal,
            IntakeImmutableProposalReader.StoredProposal storedProposal) {

        IntakeFormalCommitPort.CommitCommand commitCommand() {
            return JdbcIntakeFormalCommitPortTest.commitCommand(request, this);
        }

        IntakeProposalReference proposalReference() {
            return request.proposalReference();
        }
    }
}
