package com.example.dispute.workflow.targete2e.temporal.room.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingAuthorityRejectedException;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.hearing.domain.HearingFormalRequestHash;
import com.example.dispute.hearing.domain.HearingFormalTransition;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.hearing.application.finalization.HearingFormalReceiptService;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingAuthorityLedger;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingFormalFinalizer;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.targete2e.rooms.hearing.JdbcTargetHearingAgentRunStartedPublisher;
import com.example.dispute.workflow.targete2e.rooms.hearing.JdbcTargetHearingFormalizationActivities;
import com.example.dispute.workflow.targete2e.rooms.hearing.JdbcTargetHearingPublicTranscriptCommitter;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingAgentRunStartedPublisher;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingFormalCompletion;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingFormalizationActivities;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingInternalStageMaterializer;
import com.example.dispute.workflow.temporal.room.hearing.HearingOperationKeys;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import io.temporal.failure.ApplicationFailure;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import io.temporal.common.converter.DefaultDataConverter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcTargetHearingBootstrapActivitiesTest {

  private static final String TENANT = "tenant-target-hearing-bootstrap";
  private static final String CASE_ID = "CASE_TARGET_HEARING_BOOTSTRAP";
  private static final String ROOM_ID = "ROOM_TARGET_HEARING_BOOTSTRAP";
  private static final String EPOCH_ID = "EPOCH_TARGET_HEARING_BOOTSTRAP";
  private static final String CASE_BUILD = "case-build-target-hearing";
  private static final String ROOM_BUILD = "hearing-build-target-hearing";
  private static final String CONTROL_ACTOR = "hearing-control";
  private static final String EMPTY_JSON_HASH =
      "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

  @Container
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>(
              DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
          .withEnv("POSTGRES_DB", "target_hearing_bootstrap")
          .withEnv("POSTGRES_USER", "target_test")
          .withEnv("POSTGRES_PASSWORD", "target_test")
          .withExposedPorts(5432)
          .waitingFor(Wait.forListeningPort());

  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void migrate() {
    dataSource =
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ':'
                + POSTGRES.getMappedPort(5432)
                + "/target_hearing_bootstrap",
            "target_test",
            "target_test");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbc = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  private static final TargetHearingBootstrapActivities.ActivationRequest ACTIVATION =
      new TargetHearingBootstrapActivities.ActivationRequest(
          "tenant-1",
          "case-1",
          "hearing-room-1",
          "epoch-1",
          0,
          17,
          12,
          7,
          "room-workflow:case-1:HEARING:0",
          "room-run-1",
          "hearing-build.v1");

  @Test
  void acceptsOnlyCoherentBootstrapEpochPairs() {
    assertThat(
            JdbcTargetHearingBootstrapActivities.allowedEpochState(
                "PROVISIONING", "PROVISIONING", null))
        .isTrue();
    assertThat(
            JdbcTargetHearingBootstrapActivities.allowedEpochState(
                "ACTIVE", "READY", "room-run-1"))
        .isTrue();
    assertThat(
            JdbcTargetHearingBootstrapActivities.allowedEpochState(
                "ACTIVE", "PROVISIONING", null))
        .isFalse();
    assertThat(
            JdbcTargetHearingBootstrapActivities.allowedEpochState(
                "PROVISIONING", "PROVISIONING", "premature-run"))
        .isFalse();
  }

  @Test
  void preservesMerchantInitiatorOrdering() {
    var parties =
        JdbcTargetHearingBootstrapActivities.exactCaseParties(
            new JdbcTargetHearingBootstrapActivities.CaseRow(
                "case-1",
                "user-1",
                "merchant-1",
                "merchant-1",
                "MERCHANT",
                "user-1",
                "USER",
                "HEARING"));

    assertThat(parties.initiatorId()).isEqualTo("merchant-1");
    assertThat(parties.respondentId()).isEqualTo("user-1");
  }

  @Test
  void rejectsRoleSwappedCaseFacts() {
    assertThatThrownBy(
            () ->
                JdbcTargetHearingBootstrapActivities.exactCaseParties(
                    new JdbcTargetHearingBootstrapActivities.CaseRow(
                        "case-1",
                        "user-1",
                        "merchant-1",
                        "user-1",
                        "MERCHANT",
                        "merchant-1",
                        "USER",
                        "HEARING")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("participants drifted");
  }

  @Test
  void activationGateWaitsForTheAtomicFinalizeAndAcceptsOnlyTheRealChildRun() {
    String provisional = TargetHearingProvisioningRunIds.provisional("epoch-1");
    assertThat(
            JdbcTargetHearingBootstrapActivities.activationPhase(
                ACTIVATION,
                new JdbcTargetHearingBootstrapActivities.ActivationRow(
                    "PROVISIONING",
                    "PROVISIONING",
                    null,
                    null,
                    "PROVISIONING",
                    null,
                    provisional)))
        .isEqualTo(JdbcTargetHearingBootstrapActivities.ActivationPhase.PENDING);

    assertThat(
            JdbcTargetHearingBootstrapActivities.activationPhase(
                ACTIVATION,
                new JdbcTargetHearingBootstrapActivities.ActivationRow(
                    "ACTIVE",
                    "READY",
                    "case-run-1",
                    "room-run-1",
                    "READY",
                    "case-run-1",
                    "room-run-1")))
        .isEqualTo(JdbcTargetHearingBootstrapActivities.ActivationPhase.READY);

    assertThat(
            JdbcTargetHearingBootstrapActivities.activationPhase(
                ACTIVATION,
                new JdbcTargetHearingBootstrapActivities.ActivationRow(
                    "ACTIVE",
                    "READY",
                    "case-run-1",
                    "other-room-run",
                    "READY",
                    "case-run-1",
                    "other-room-run")))
        .isEqualTo(JdbcTargetHearingBootstrapActivities.ActivationPhase.INVALID);
  }

  @Test
  void missingHearingStateIsCreatedFromExactEpochAuthorityAndBootstrapReplaysExactlyOnce()
      throws SQLException {
    ProvisionRoomEpoch provision = provision(CASE_ID, ROOM_ID, EPOCH_ID, 3);
    seedAuthority(provision);
    JdbcTargetHearingBootstrapActivities activities =
        new JdbcTargetHearingBootstrapActivities(
            dataSource, transactions, "default", java.time.Duration.ofMinutes(20));

    TargetHearingBootstrapActivities.Binding first = activities.bootstrap(provision);
    TargetHearingBootstrapActivities.Binding replay = activities.bootstrap(provision);

    assertThat(first).isEqualTo(replay);
    assertThat(DefaultDataConverter.STANDARD_INSTANCE.toPayload(first).orElseThrow())
        .isEqualTo(DefaultDataConverter.STANDARD_INSTANCE.toPayload(replay).orElseThrow());
    assertThat(first.flowInstanceId()).isEqualTo(ROOM_ID);
    assertThat(first.epochId()).isEqualTo(EPOCH_ID);
    assertThat(first.partyStageWindowSeconds()).isEqualTo(1_200);
    assertThat(first.processRevision()).isEqualTo(14);
    assertThat(first.roomRevision()).isZero();
    assertThat(first.fencingToken()).isEqualTo(3);
    assertThat(first.stageCode()).isEqualTo("COURT_PREPARING");
    assertThat(first.stageSequence()).isEqualTo(1);
    assertThat(count("hearing_state", CASE_ID)).isEqualTo(1);
    assertThat(count("hearing_flow_instance", CASE_ID)).isEqualTo(1);
    assertThat(count("hearing_flow_stage", CASE_ID)).isEqualTo(1);
    assertThat(count("hearing_temporal_projection", CASE_ID)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select id from hearing_state where case_id = ?", String.class, CASE_ID))
        .isEqualTo(stableStateId(EPOCH_ID, ROOM_ID));
    assertThat(
            jdbc.queryForObject(
                "select workflow_id from hearing_state where case_id = ?",
                String.class,
                CASE_ID))
        .isEqualTo(ROOM_ID);

    activate(provision);
    HearingFormalFinalizer finalizer = formalFinalizer();
    HearingFormalFinalizer.StageCommand opening = openingCommand(provision);
    String openingStatus =
        jdbc.queryForObject(
            "select stage_status from hearing_flow_stage where case_id = ? and stage_code = 'COURT_PREPARING'",
            String.class,
            CASE_ID);
    HearingDomainReceipt committed = finalizer.advanceStage(opening);

    assertThat(openingStatus).isEqualTo("RUNNING");
    assertThat(
            jdbc.queryForObject(
                "select stage_status from hearing_flow_stage where case_id = ? and stage_code = 'COURT_PREPARING'",
                String.class,
                CASE_ID))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "select stage_status from hearing_flow_stage where case_id = ? and stage_code = 'CASE_INTRODUCTION'",
                String.class,
                CASE_ID))
        .isEqualTo("RUNNING");
    assertThat(committed.sourceStage()).isEqualTo(HearingFlowStage.COURT_PREPARING);
    assertThat(committed.stage()).isEqualTo(HearingFlowStage.CASE_INTRODUCTION);
    assertThat(committed.sourceProcessRevision()).isEqualTo(14);
    assertThat(committed.sourceRoomRevision()).isZero();
    assertThat(committed.processRevision()).isEqualTo(15);
    assertThat(committed.roomRevision()).isEqualTo(1);
    assertThat(committed.fencingToken()).isEqualTo(3);
    assertThat(finalizer.advanceStage(opening)).isEqualTo(committed);
    assertThat(count("hearing_state", CASE_ID)).isEqualTo(1);
    assertThat(count("hearing_flow_instance", CASE_ID)).isEqualTo(1);
    assertThat(countStage(CASE_ID, "COURT_PREPARING")).isEqualTo(1);
    assertThat(countStage(CASE_ID, "CASE_INTRODUCTION")).isEqualTo(1);
    assertThat(count("hearing_temporal_projection", CASE_ID)).isEqualTo(1);
    assertThat(count("hearing_domain_receipt", CASE_ID)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select process_revision from case_process_projection where case_id = ?",
                Long.class,
                CASE_ID))
        .isEqualTo(15L);
    assertThat(
            jdbc.queryForObject(
                "select process_revision from case_room_epoch where id = ?", Long.class, EPOCH_ID))
        .isEqualTo(15L);
    assertThat(
            jdbc.queryForObject(
                "select room_revision from case_room_epoch where id = ?", Long.class, EPOCH_ID))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "select current_stage from hearing_temporal_projection where case_id = ?",
                String.class,
                CASE_ID))
        .isEqualTo("CASE_INTRODUCTION");

    assertThatThrownBy(() -> activities.bootstrap(provision))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("target Hearing epoch authority drifted")
        .satisfies(
            failure ->
                assertThat(((ApplicationFailure) failure).getType())
                    .isEqualTo(JdbcTargetHearingBootstrapActivities.BOOTSTRAP_INVALID));
    assertThat(count("hearing_flow_instance", CASE_ID)).isEqualTo(1);
    assertThat(count("hearing_flow_stage", CASE_ID)).isEqualTo(2);
    assertThat(count("hearing_domain_receipt", CASE_ID)).isEqualTo(1);

    assertThatThrownBy(
            () -> activities.bootstrap(provision(CASE_ID, ROOM_ID, EPOCH_ID, 4)))
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(
            failure ->
                assertThat(((ApplicationFailure) failure).getType())
                    .isEqualTo(JdbcTargetHearingBootstrapActivities.BOOTSTRAP_INVALID));
    assertThat(count("hearing_flow_instance", CASE_ID)).isEqualTo(1);

    for (String drift : List.of("id", "workflow", "status", "node", "json")) {
      String suffix = drift.toUpperCase();
      ProvisionRoomEpoch drifted =
          provision(
              "CASE_HEARING_DRIFT_" + suffix,
              "ROOM_HEARING_DRIFT_" + suffix,
              "EPOCH_HEARING_DRIFT_" + suffix,
              3);
      seedAuthority(drifted);
      seedDriftedState(drifted, drift);

      assertThatThrownBy(() -> activities.bootstrap(drifted))
          .isInstanceOf(ApplicationFailure.class)
          .hasMessageContaining("target Hearing state authority drifted")
          .satisfies(
              failure ->
                  assertThat(((ApplicationFailure) failure).getType())
                      .isEqualTo(JdbcTargetHearingBootstrapActivities.BOOTSTRAP_INVALID));
      assertThat(count("hearing_state", drifted.caseId())).isEqualTo(1);
      assertThat(count("hearing_flow_instance", drifted.caseId())).isZero();
      assertThat(count("hearing_flow_stage", drifted.caseId())).isZero();
      assertThat(count("hearing_temporal_projection", drifted.caseId())).isZero();
    }

    for (String status : List.of("PENDING", "FAILED")) {
      ProvisionRoomEpoch drifted =
          provision(
              "CASE_HEARING_STAGE_" + status,
              "ROOM_HEARING_STAGE_" + status,
              "EPOCH_HEARING_STAGE_" + status,
              3);
      seedAuthority(drifted);
      activities.bootstrap(drifted);
      activate(drifted);
      jdbc.update(
          """
          update hearing_flow_stage
             set stage_status = ?, completed_at = case when ? = 'FAILED' then now() else null end
           where case_id = ? and stage_code = 'COURT_PREPARING'
          """,
          status,
          status,
          drifted.caseId());

      assertThatThrownBy(() -> finalizer.advanceStage(openingCommand(drifted)))
          .isInstanceOf(HearingAuthorityRejectedException.class)
          .extracting(failure -> ((HearingAuthorityRejectedException) failure).code())
          .isEqualTo("HEARING_SOURCE_STAGE_NOT_EXACT");
      assertThat(count("hearing_flow_stage", drifted.caseId())).isEqualTo(1);
      assertThat(count("hearing_domain_receipt", drifted.caseId())).isZero();
      assertThat(
              jdbc.queryForObject(
                  "select current_stage from hearing_temporal_projection where case_id = ?",
                  String.class,
                  drifted.caseId()))
          .isEqualTo("COURT_PREPARING");
    }
  }

  @Test
  void targetOpeningProjectsBaselineChatMessagesInOrderAndReplayDoesNotDuplicate()
      throws SQLException {
    ProvisionRoomEpoch provision =
        provision(
            "CASE_TARGET_HEARING_CHAT",
            "ROOM_TARGET_HEARING_CHAT",
            "EPOCH_TARGET_HEARING_CHAT",
            3);
    seedAuthority(provision);
    seedPreludeAuthority(provision.caseId());
    JdbcTargetHearingBootstrapActivities bootstrap =
        new JdbcTargetHearingBootstrapActivities(
            dataSource, transactions, "default", java.time.Duration.ofMinutes(20));
    TargetHearingBootstrapActivities.Binding binding = bootstrap.bootstrap(provision);
    activate(provision);

    NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
    JdbcHearingAuthorityLedger ledger = new JdbcHearingAuthorityLedger(named, transactions);
    TargetHearingFormalCompletion completion =
        new TargetHearingFormalCompletion(
            new HearingFormalReceiptService(new JdbcHearingFormalFinalizer(named, ledger)));
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    JdbcTargetHearingFormalizationActivities formalization =
        new JdbcTargetHearingFormalizationActivities(
            dataSource,
            transactions,
            completion,
            mock(TargetHearingInternalStageMaterializer.class),
            ledger,
            mapper,
            null,
            new JdbcTargetHearingPublicTranscriptCommitter(dataSource, mapper, ignored -> {}));
    HearingRoomStart start =
        new HearingRoomStart(
            "hearing-room-start.v1",
            provision.tenantSurrogate(),
            provision.caseId(),
            provision.roomId(),
            binding.flowInstanceId(),
            binding.epochId(),
            HearingWriterMode.TEMPORAL,
            binding.roomEpoch(),
            binding.fencingToken(),
            binding.initiatorParticipantId(),
            binding.respondentParticipantId(),
            provision.requestedAt(),
            provision.projectedDeadlineAt(),
            binding.partyStageWindowSeconds(),
            binding.processRevision(),
            binding.roomRevision(),
            ROOM_BUILD);
    String operationKey =
        HearingOperationKeys.stageCompletion(
            provision.tenantSurrogate(),
            provision.caseId(),
            provision.roomEpoch(),
            HearingWorkflowStage.COURT_PREPARING,
            HearingWorkflowStage.COURT_PREPARING.sequence());
    TargetHearingFormalizationActivities.TransitionRequest transition =
        new TargetHearingFormalizationActivities.TransitionRequest(
            start,
            HearingWorkflowStage.COURT_PREPARING,
            HearingWorkflowStage.COURT_PREPARING.sequence(),
            binding.processRevision(),
            binding.roomRevision(),
            binding.fencingToken(),
            operationKey);

    var first = formalization.bootstrapNext(transition);
    var replay = formalization.bootstrapNext(transition);

    assertThat(replay).isEqualTo(first);
    List<Map<String, Object>> messages =
        jdbc.queryForList(
            """
            select sender_type, sender_role, message_source, message_type,
                   message_text, idempotency_key
              from room_message
             where case_id = ? and room_id = ?
             order by sequence_no
            """,
            provision.caseId(),
            provision.roomId());
    assertThat(messages).hasSize(4);
    assertThat(messages)
        .extracting(row -> row.get("sender_role"))
        .containsExactly("SYSTEM", "PRESIDING_JUDGE", "SYSTEM", "SYSTEM");
    assertThat(messages)
        .extracting(row -> row.get("message_source"))
        .containsExactly(
            "SYSTEM_STAGE_EVENT", "ROLE_TEMPLATE", "SYSTEM_STAGE_EVENT", "SYSTEM_STAGE_EVENT");
    assertThat(messages)
        .extracting(row -> row.get("message_text"))
        .containsExactly(
            "法庭正在装载冻结前案情矩阵和证据矩阵。",
            "现在开庭。庭前案情与证据材料将依次宣读；本席在庭审卷宗冻结后进入裁决审理。",
            "前序案情矩阵和证据矩阵已装载。",
            "下面请案情接待官介绍庭前案情。");
    assertThat(messages)
        .extracting(row -> row.get("idempotency_key"))
        .containsExactly(
            "hearing-v2:1:prepare",
            "hearing-v2:1:judge-opening",
            "hearing-v2:1:prepare-completed",
            "hearing-v2:2:case-introduction-next");
  }

  @Test
  void automaticAgentRunStartPublishesOneDurableDiscoveryEventAndRejectsReplayDrift()
      throws SQLException {
    ProvisionRoomEpoch provision =
        provision(
            "CASE_TARGET_HEARING_RUN_DISCOVERY",
            "ROOM_TARGET_HEARING_RUN_DISCOVERY",
            "EPOCH_TARGET_HEARING_RUN_DISCOVERY",
            7);
    seedAuthority(provision);
    AtomicInteger notifications = new AtomicInteger();
    JdbcTargetHearingAgentRunStartedPublisher publisher =
        new JdbcTargetHearingAgentRunStartedPublisher(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            ignored -> notifications.incrementAndGet());
    Instant startedAt = Instant.parse("2026-08-17T02:45:00.123456Z");
    TargetHearingAgentRunStartedPublisher.Event event =
        new TargetHearingAgentRunStartedPublisher.Event(
            provision.tenantSurrogate(),
            provision.caseId(),
            provision.roomId(),
            provision.roomEpoch(),
            provision.fencingToken(),
            "FLOW_TARGET_HEARING_RUN_DISCOVERY",
            "INTAKE_QUESTIONS_GENERATING",
            4,
            "HEARING_INTAKE_QUESTIONS",
            "hearing-stage:4:run-discovery",
            "target-hearing-run:run-discovery",
            "target-hearing-run:run-discovery:1",
            "PENDING",
            startedAt);

    transactions.executeWithoutResult(ignored -> publisher.publish(event));
    transactions.executeWithoutResult(ignored -> publisher.publish(event));

    assertThat(notifications).hasValue(1);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from case_timeline_event
                 where case_id = ? and event_type = 'AGENT_RUN_STARTED'
                """,
                Long.class,
                provision.caseId()))
        .isEqualTo(1L);
    Map<String, Object> stored =
        jdbc.queryForMap(
            """
            select event_json ->> 'agent_run_id' as agent_run_id,
                   event_json ->> 'attempt_id' as attempt_id,
                   event_json ->> 'stream_url' as stream_url,
                   event_json ->> 'stream_access' as stream_access,
                   event_json ->> 'schema_version' as schema_version,
                   event_json ->> 'stage_code' as stage_code,
                   (event_json ->> 'fencing_token')::bigint as fencing_token
              from case_timeline_event
             where case_id = ? and event_type = 'AGENT_RUN_STARTED'
            """,
            provision.caseId());
    assertThat(stored)
        .containsEntry("agent_run_id", event.agentRunId())
        .containsEntry("attempt_id", event.attemptId())
        .containsEntry(
            "stream_url", "/api/agent-runs/" + event.agentRunId() + "/events")
        .containsEntry("stream_access", "ACTOR_VISIBLE")
        .containsEntry("schema_version", "target-hearing-agent-run-started.v3")
        .containsEntry("stage_code", event.stageCode())
        .containsEntry("fencing_token", event.fencingToken());

    TargetHearingAgentRunStartedPublisher.Event drifted =
        new TargetHearingAgentRunStartedPublisher.Event(
            event.tenantSurrogate(),
            event.caseId(),
            event.roomId(),
            event.roomEpoch(),
            event.fencingToken(),
            event.flowInstanceId(),
            "EVIDENCE_REQUESTS_GENERATING",
            7,
            "HEARING_EVIDENCE_REQUESTS",
            event.commandId(),
            event.agentRunId(),
            event.attemptId(),
            event.status(),
            event.startedAt());
    assertThatThrownBy(
            () -> transactions.executeWithoutResult(ignored -> publisher.publish(drifted)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("replay drifted");
    assertThat(notifications).hasValue(1);
  }

  private static HearingFormalFinalizer formalFinalizer() {
    NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
    return new JdbcHearingFormalFinalizer(
        named, new JdbcHearingAuthorityLedger(named, transactions));
  }

  private static HearingFormalFinalizer.StageCommand openingCommand(ProvisionRoomEpoch provision) {
    HearingAuthorityExpectation authority =
        new HearingAuthorityExpectation(
            provision.tenantSurrogate(),
            provision.caseId(),
            provision.roomId(),
            provision.epochId(),
            provision.roomEpoch(),
            HearingWriterMode.TEMPORAL,
            HearingFlowStage.COURT_PREPARING,
            1,
            provision.initialProcessRevision(),
            provision.initialRoomRevision(),
            provision.fencingToken());
    HearingFormalTransition transition =
        new HearingFormalTransition(
            stableStageId(provision),
            HearingFlowStage.CASE_INTRODUCTION,
            2,
            null,
            "hearing-stage-case-introduction-" + provision.epochId(),
            "{}",
            "{}",
            CONTROL_ACTOR);
    String requestHash =
        HearingFormalRequestHash.compute(
            "STAGE", authority, transition, EMPTY_JSON_HASH, CONTROL_ACTOR);
    HearingAuthorityCommit commit =
        new HearingAuthorityCommit(
            HearingAuthorityCommit.SCHEMA_VERSION,
            authority,
            HearingAuthorityCommit.OperationType.STAGE,
            "hearing.stage:"
                + provision.tenantSurrogate()
                + ':'
                + provision.caseId()
                + ':'
                + provision.roomEpoch()
                + ":1:COURT_PREPARING",
            requestHash,
            null,
            Instant.EPOCH);
    return new HearingFormalFinalizer.StageCommand(
        commit, transition, "{}", EMPTY_JSON_HASH, CONTROL_ACTOR);
  }

  private static void activate(ProvisionRoomEpoch provision) {
    String caseRunId = "case-run-" + provision.caseId().toLowerCase();
    String roomRunId = "room-run-" + provision.caseId().toLowerCase();
    transactions.executeWithoutResult(
        ignored -> {
          assertThat(
                  jdbc.update(
                      """
                      update case_room_epoch
                         set lifecycle_status = 'ACTIVE', provisioning_status = 'READY',
                             temporal_run_id = ?, room_temporal_run_id = ?,
                             provisioned_at = now(), updated_at = now(), version = version + 1
                       where id = ? and lifecycle_status = 'PROVISIONING'
                         and provisioning_status = 'PROVISIONING'
                      """,
                      caseRunId,
                      roomRunId,
                      provision.epochId()))
              .isEqualTo(1);
          assertThat(
                  jdbc.update(
                      """
                      update case_process_projection
                         set writer_activation_status = 'READY', temporal_run_id = ?,
                             updated_at = now(), version = version + 1
                       where case_id = ? and writer_activation_status = 'PROVISIONING'
                      """,
                      caseRunId,
                      provision.caseId()))
              .isEqualTo(1);
          jdbc.queryForObject(
              "select set_config('app.hearing_activation_commit', 'on', true)", String.class);
          assertThat(
                  jdbc.update(
                      """
                      update hearing_temporal_projection
                         set temporal_run_id = ?, updated_at = now()
                       where case_id = ? and epoch_id = ?
                         and temporal_run_id = ?
                      """,
                      roomRunId,
                      provision.caseId(),
                      provision.epochId(),
                      TargetHearingProvisioningRunIds.provisional(provision.epochId())))
              .isEqualTo(1);
        });
  }

  private static void seedAuthority(ProvisionRoomEpoch provision) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          insert into fulfillment_dispute_case (
              id, user_id, merchant_id, creation_idempotency_key,
              case_type, case_status, initiator_role, initiator_id,
              respondent_role, respondent_id, risk_level, title, description,
              current_room, created_by, updated_by
          ) values (
              '%s', 'user-hearing-bootstrap', 'merchant-hearing-bootstrap',
              'create-%s', 'DISPUTE', 'HEARING_OPEN',
              'USER', 'user-hearing-bootstrap', 'MERCHANT', 'merchant-hearing-bootstrap',
              'HIGH', 'Target Hearing bootstrap', 'Exact Target Hearing authority.',
              'HEARING', 'target-hearing-bootstrap', 'target-hearing-bootstrap'
          )
          """
              .formatted(provision.caseId(), provision.caseId().toLowerCase()));
      statement.executeUpdate(
          """
          insert into case_participant (
              id, case_id, actor_id, participant_role, participant_status,
              joined_at, created_by, updated_by
          ) values
              ('PARTICIPANT_USER_%s', '%s', 'user-hearing-bootstrap', 'USER',
               'ACTIVE', now(), 'target-hearing-bootstrap', 'target-hearing-bootstrap'),
              ('PARTICIPANT_MERCHANT_%s', '%s', 'merchant-hearing-bootstrap', 'MERCHANT',
               'ACTIVE', now(), 'target-hearing-bootstrap', 'target-hearing-bootstrap')
          """
              .formatted(
                  provision.caseId(),
                  provision.caseId(),
                  provision.caseId(),
                  provision.caseId()));
      statement.executeUpdate(
          """
          insert into case_room (
              id, case_id, room_type, room_status, opened_at, created_by, updated_by
          ) values ('%s', '%s', 'HEARING', 'OPEN', now(),
                    'target-hearing-bootstrap', 'target-hearing-bootstrap')
          """
              .formatted(provision.roomId(), provision.caseId()));
      statement.executeUpdate(
          """
          insert into case_process_projection (
              case_id, tenant_surrogate, macro_phase, current_room, room_phase,
              writer_mode, writer_activation_status, process_revision, room_epoch,
              fencing_token, last_command_sequence, last_case_event_sequence,
              temporal_workflow_id, temporal_run_id, temporal_build_id,
              projected_at, updated_at
          ) values (
              '%s', '%s', 'HEARING_OPEN', 'HEARING', 'PROVISIONING',
              'TEMPORAL', 'PROVISIONING', 14, 0, 3, 13, 26,
              '%s', null, '%s', now(), now()
          )
          """
              .formatted(
                  provision.caseId(),
                  provision.tenantSurrogate(),
                  provision.caseWorkflowId(),
                  provision.temporalBuildId()));
      statement.executeUpdate(
          """
          insert into case_room_epoch (
              id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
              writer_mode, lifecycle_status, provisioning_status,
              process_revision, room_revision, fencing_token,
              temporal_workflow_id, temporal_run_id,
              room_temporal_workflow_id, room_temporal_run_id,
              temporal_build_id, graph_key, graph_version,
              checkpoint_schema_version, stream_protocol,
              selection_schema_version, process_contract_version, workflow_type,
              room_workflow_type, room_workflow_build_id,
              activated_at, created_at, updated_at
          ) values (
              '%s', '%s', '%s', '%s', 'HEARING', 0,
              'TEMPORAL', 'PROVISIONING', 'PROVISIONING', 14, 0, 3,
              '%s', null, '%s', null,
              '%s', 'all-rooms.target-e2e.v1', 'target-e2e-graph.2026-07-27.1',
              'target-e2e-checkpoint.v1', 'agent-stream.v2',
              'room-epoch-selection.v2', 'case-process-contract.v1', 'CaseProcessWorkflow',
              'HearingRoomWorkflow', '%s', now(), now(), now()
          )
          """
              .formatted(
                  provision.epochId(),
                  provision.tenantSurrogate(),
                  provision.caseId(),
                  provision.roomId(),
                  provision.caseWorkflowId(),
                  provision.roomWorkflowId(),
                  provision.temporalBuildId(),
                  provision.roomWorkflowBuildId()));
    }
  }

  private static void seedPreludeAuthority(String caseId) {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ObjectNode caseMatrix = mapper.createObjectNode();
    caseMatrix.put("schema_version", "case_fact_matrix.v2");
    caseMatrix.put("case_id", caseId);
    caseMatrix.put("matrix_id", "MATRIX_" + caseId);
    caseMatrix.put("matrix_version", 1);
    caseMatrix.putArray("fact_rows");
    caseMatrix.put("content_hash", ContractJson.sha256Hex(caseMatrix));
    ObjectNode intakeDossier = mapper.createObjectNode();
    intakeDossier.set("case_fact_matrix", caseMatrix);
    jdbc.update(
        """
        insert into case_intake_dossier (
            id, case_id, room_type, dossier_version, dossier_json,
            quality_score, ready_for_next_step, admission_recommendation,
            source_turn_no, created_by, updated_by)
        values (?, ?, 'INTAKE', 1, cast(? as jsonb), 100, true, 'ACCEPTED',
                1, 'target-hearing-bootstrap', 'target-hearing-bootstrap')
        """,
        "INTAKE_DOSSIER_" + caseId,
        caseId,
        ContractJson.canonicalString(intakeDossier));

    ObjectNode evidenceMatrix = mapper.createObjectNode();
    evidenceMatrix.put("schema_version", "fact_evidence_matrix.v3");
    evidenceMatrix.put("case_id", caseId);
    evidenceMatrix.put("matrix_id", "EVIDENCE_MATRIX_" + caseId);
    evidenceMatrix.put("matrix_version", 1);
    evidenceMatrix.put("matrix_status", "FROZEN");
    evidenceMatrix.put("case_fact_matrix_id", caseMatrix.path("matrix_id").asText());
    evidenceMatrix.put("case_fact_matrix_version", caseMatrix.path("matrix_version").asInt());
    evidenceMatrix.put("case_fact_matrix_hash", caseMatrix.path("content_hash").asText());
    evidenceMatrix.putArray("fact_coverage");
    evidenceMatrix.putArray("links");
    evidenceMatrix.putArray("source_refs");
    evidenceMatrix.put("content_hash", ContractJson.sha256Hex(evidenceMatrix));
    ObjectNode matrixSummary = mapper.createObjectNode();
    matrixSummary.put("schema_version", "evidence-dossier-matrix-summary.v3");
    matrixSummary.set("fact_evidence_matrix", evidenceMatrix);
    jdbc.update(
        """
        insert into evidence_dossier (
            id, case_id, dossier_status, dossier_version, summary_json,
            timeline_json, matrix_summary_json, built_at, created_by, updated_by)
        values (?, ?, 'FROZEN', 1, '{}'::jsonb, '[]'::jsonb, cast(? as jsonb),
                now(), 'target-hearing-bootstrap', 'target-hearing-bootstrap')
        """,
        "EVIDENCE_DOSSIER_" + caseId,
        caseId,
        ContractJson.canonicalString(matrixSummary));
  }

  private static void seedDriftedState(ProvisionRoomEpoch provision, String drift) {
    jdbc.update(
        """
        insert into hearing_state (
            id, case_id, workflow_id, hearing_status, current_node, round_no,
            confidence, manual_required, graph_state_json, pending_requests_json,
            manual_flags_json, waiting_until, completed_at, created_at, updated_at,
            created_by, updated_by
        ) values (?, ?, ?, 'RUNNING', 'COURT_PREPARING', 0,
            null, false, '{}'::jsonb, '[]'::jsonb, '[]'::jsonb,
            null, null, now(), now(),
            'target-e2e-hearing-bootstrap', 'target-e2e-hearing-bootstrap')
        """,
        stableStateId(provision.epochId(), provision.roomId()),
        provision.caseId(),
        provision.roomId());
    switch (drift) {
      case "id" ->
          jdbc.update(
              "update hearing_state set id = ? where case_id = ?",
              "hearing-state-drift-" + provision.fencingToken() + '-' + provision.caseId(),
              provision.caseId());
      case "workflow" ->
          jdbc.update(
              "update hearing_state set workflow_id = ? where case_id = ?",
              "wrong-flow-" + provision.caseId(),
              provision.caseId());
      case "status" ->
          jdbc.update(
              "update hearing_state set hearing_status = 'WAITING_EVIDENCE' where case_id = ?",
              provision.caseId());
      case "node" ->
          jdbc.update(
              "update hearing_state set current_node = 'CASE_INTRODUCTION' where case_id = ?",
              provision.caseId());
      case "json" ->
          jdbc.update(
              "update hearing_state set graph_state_json = '{\"drift\":true}'::jsonb where case_id = ?",
              provision.caseId());
      default -> throw new IllegalArgumentException("unsupported drift fixture");
    }
  }

  private static ProvisionRoomEpoch provision(
      String caseId, String roomId, String epochId, long fencingToken) {
    String tenant = TENANT + '-' + caseId.toLowerCase();
    return new ProvisionRoomEpoch(
        ProvisionRoomEpoch.SCHEMA_VERSION,
        epochId,
        tenant,
        caseId,
        roomId,
        RoomType.HEARING,
        0,
        14,
        0,
        fencingToken,
        "HEARING_OPEN",
        "HEARING",
        "PROVISIONING",
        WriterMode.TEMPORAL,
        CaseProcessWorkflowProtocol.caseWorkflowId(tenant, caseId),
        CaseProcessWorkflowProtocol.roomWorkflowId(caseId, RoomType.HEARING, 0),
        "room-epoch-selection.v2",
        "case-process-contract.v1",
        "CaseProcessWorkflow",
        CASE_BUILD,
        "HearingRoomWorkflow",
        ROOM_BUILD,
        "all-rooms.target-e2e.v1",
        "target-e2e-graph.2026-07-27.1",
        "target-e2e-checkpoint.v1",
        "agent-stream.v2",
        13,
        26,
        14,
        27,
        Instant.parse("2026-08-16T00:00:00Z").plusSeconds(3600),
        null,
        null,
        Instant.parse("2026-08-16T00:00:00Z"));
  }

  private static int count(String table, String caseId) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where case_id = ?", Integer.class, caseId);
  }

  private static int countStage(String caseId, String stageCode) {
    return jdbc.queryForObject(
        "select count(*) from hearing_flow_stage where case_id = ? and stage_code = ?",
        Integer.class,
        caseId,
        stageCode);
  }

  private static String stableStageId(ProvisionRoomEpoch provision) {
    return "hearing-stage-"
        + UUID.nameUUIDFromBytes(
                (provision.epochId() + ':' + provision.roomId())
                    .getBytes(StandardCharsets.UTF_8))
            .toString()
            .replace("-", "");
  }

  private static String stableStateId(String epochId, String roomId) {
    return "hearing-state-"
        + UUID.nameUUIDFromBytes((epochId + ':' + roomId).getBytes(StandardCharsets.UTF_8))
            .toString()
            .replace("-", "");
  }
}
