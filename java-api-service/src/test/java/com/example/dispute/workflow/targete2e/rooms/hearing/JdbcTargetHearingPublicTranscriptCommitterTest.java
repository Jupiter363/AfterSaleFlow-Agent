package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.hearing.application.HearingPublicTranscriptPolicy;
import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageSource;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.workflow.temporal.room.hearing.HearingCommittedReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingOperationKeys;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcTargetHearingPublicTranscriptCommitterTest {

  private static final String TENANT = "tenant-hearing-public";
  private static final String CASE_ID = "CASE_HEARING_PUBLIC_1";
  private static final String ROOM_ID = "ROOM_HEARING_PUBLIC_1";
  private static final String FLOW_ID = "FLOW_HEARING_PUBLIC_1";
  private static final String EPOCH_ID = "EPOCH_HEARING_PUBLIC_1";
  private static final String RECEIPT_ID = "hearing-public-receipt-1";
  private static final String RECEIPT_HASH = "a".repeat(64);
  private static final String REQUEST_HASH = "b".repeat(64);
  private static final String RESULT_HASH = "c".repeat(64);
  private static final String AGENT_RUN_ID = "target-hearing-run:public-1";
  private static final String AGENT_WORKFLOW_ID = "AGENT_RUN_" + AGENT_RUN_ID;
  private static final Instant COMMITTED_AT = Instant.parse("2030-01-02T03:04:05.123456Z");
  private static final Instant PARTY_COMMITTED_AT = COMMITTED_AT.plusSeconds(1);

  @Container
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>(DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
          .withEnv("POSTGRES_DB", "hearing_public_transcript")
          .withEnv("POSTGRES_USER", "target_test")
          .withEnv("POSTGRES_PASSWORD", "target_test")
          .withExposedPorts(5432)
          .waitingFor(Wait.forListeningPort());

  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void schema() {
    dataSource =
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ':'
                + POSTGRES.getMappedPort(5432)
                + "/hearing_public_transcript",
            "target_test",
            "target_test");
    jdbc = new JdbcTemplate(dataSource);
    createPreMigrationSchema();
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V067__hearing_public_transcript_binding.sql"))
        .execute(dataSource);
    seedAuthority();
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  @Test
  void commitsFormalReceiptAndPublicProjectionAtomicallyAndReplaysStrictly() {
    List<String> notifications = new ArrayList<>();
    var committer =
        new JdbcTargetHearingPublicTranscriptCommitter(
            dataSource, JsonMapper.builder().findAndAddModules().build(), notifications::add);
    HearingRoomStart start = start(3);
    assertThat(start.roomId()).isNotEqualTo(start.flowInstanceId());
    assertThat(AGENT_WORKFLOW_ID).isNotEqualTo(start.roomId());
    HearingStageReceipt receipt = receipt();
    List<HearingPublicTranscriptPolicy.Draft> drafts = drafts();

    AtomicReference<JdbcTargetHearingPublicTranscriptCommitter.CommitResult> rolledBack =
        new AtomicReference<>();
    transactions.executeWithoutResult(
        status -> {
          rolledBack.set(
              committer.commit(
                  JdbcTargetHearingPublicTranscriptCommitter.CommitMode.NEW_COMMIT,
                  receipt,
                  start,
                  COMMITTED_AT,
                  drafts));
          assertThat(count("room_message")).isEqualTo(2);
          assertThat(count("hearing_public_transcript_binding")).isEqualTo(2);
          assertThat(count("case_timeline_event")).isEqualTo(2);
          assertThat(notifications).isEmpty();
          status.setRollbackOnly();
        });
    assertThat(rolledBack.get().publications()).hasSize(2);
    assertThat(count("hearing_domain_receipt")).isEqualTo(1);
    assertThat(count("room_message")).isZero();
    assertThat(count("hearing_public_transcript_binding")).isZero();
    assertThat(count("case_timeline_event")).isZero();
    assertThat(notifications).isEmpty();

    JdbcTargetHearingPublicTranscriptCommitter.CommitResult committed =
        transactions.execute(
            status ->
                committer.commit(
                    JdbcTargetHearingPublicTranscriptCommitter.CommitMode.NEW_COMMIT,
                    receipt,
                    start,
                    COMMITTED_AT,
                    drafts));
    assertThat(notifications).containsExactly(CASE_ID);
    assertThat(committed.receiptId()).isEqualTo(RECEIPT_ID);
    assertThat(committed.receiptHash()).isEqualTo(RECEIPT_HASH);
    assertThat(committed.publications())
        .extracting(JdbcTargetHearingPublicTranscriptCommitter.Publication::ordinal)
        .containsExactly(0, 1);
    assertThat(committed.publications())
        .extracting(JdbcTargetHearingPublicTranscriptCommitter.Publication::publicationKey)
        .containsExactly("hearing-v2:11:judge-v1", "hearing-v2:12:jury-review-next");
    assertThat(committed.publications())
        .extracting(JdbcTargetHearingPublicTranscriptCommitter.Publication::messageSequence)
        .containsExactly(1L, 2L);
    assertThat(committed.publications())
        .extracting(JdbcTargetHearingPublicTranscriptCommitter.Publication::eventSequence)
        .containsExactly(1L, 2L);
    assertThat(committed.publications())
        .allSatisfy(
            publication -> {
              assertThat(publication.messageSha256()).matches("[0-9a-f]{64}");
              assertThat(publication.eventSha256()).matches("[0-9a-f]{64}");
              assertThat(publication.bindingSha256()).matches("[0-9a-f]{64}");
            });

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            select binding.ordinal, binding.publication_key, binding.receipt_id,
                   binding.receipt_hash, binding.flow_instance_id, binding.epoch_id,
                   binding.hearing_epoch, binding.fencing_token, binding.source_stage,
                   binding.result_stage, binding.message_stage, message.sender_type,
                   message.sender_role, message.sender_id, message.message_source,
                   message.message_type, message.message_text, message.agent_run_id,
                   message.audience_json::text, message.audience_actor_ids_json::text,
                   timeline.sequence_no as event_sequence, timeline.event_type,
                   timeline.event_json::text
              from hearing_public_transcript_binding binding
              join room_message message on message.id = binding.message_id
              join case_timeline_event timeline on timeline.id = binding.event_id
             where binding.receipt_id = ?
             order by binding.ordinal
            """,
            RECEIPT_ID);
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(row -> row.get("receipt_hash")).containsOnly(RECEIPT_HASH);
    assertThat(rows).extracting(row -> row.get("flow_instance_id")).containsOnly(FLOW_ID);
    assertThat(rows).extracting(row -> row.get("epoch_id")).containsOnly(EPOCH_ID);
    assertThat(rows).extracting(row -> row.get("hearing_epoch")).containsOnly(0L);
    assertThat(rows).extracting(row -> row.get("fencing_token")).containsOnly(3L);
    assertThat(rows).extracting(row -> row.get("source_stage"))
        .containsOnly("JUDGE_V1_GENERATING");
    assertThat(rows).extracting(row -> row.get("result_stage"))
        .containsOnly("JURY_REVIEWING");
    assertThat(rows).extracting(row -> row.get("message_stage"))
        .containsExactly("JUDGE_V1_GENERATING", "JURY_REVIEWING");
    assertThat(rows).extracting(row -> row.get("audience_json"))
        .containsOnly("[\"USER\", \"MERCHANT\", \"PLATFORM_REVIEWER\", \"ADMIN\"]");
    assertThat(rows).extracting(row -> row.get("audience_actor_ids_json")).containsOnly("[]");
    assertThat(rows.getFirst().get("agent_run_id")).isEqualTo(AGENT_RUN_ID);
    assertThat(rows.getLast().get("agent_run_id")).isNull();
    assertThat(rows).extracting(row -> row.get("event_type")).containsOnly("ROOM_MESSAGE_CREATED");
    assertThat(rows).extracting(row -> row.get("event_sequence")).containsExactly(1L, 2L);

    JdbcTargetHearingPublicTranscriptCommitter.CommitResult replay =
        transactions.execute(
            status ->
                committer.commit(
                    JdbcTargetHearingPublicTranscriptCommitter.CommitMode.STRICT_REPLAY,
                    receipt,
                    start,
                    COMMITTED_AT,
                    drafts));
    assertThat(replay).isEqualTo(committed);
    assertThat(notifications).containsExactly(CASE_ID);
    assertThat(count("room_message")).isEqualTo(2);
    assertThat(count("hearing_public_transcript_binding")).isEqualTo(2);
    assertThat(count("case_timeline_event")).isEqualTo(2);

    assertCorruptReplayRejected(
        committer,
        receipt,
        start,
        drafts,
        "hearing_public_transcript_binding",
        "delete from hearing_public_transcript_binding where receipt_id = '"
            + RECEIPT_ID
            + "' and ordinal = 0");
    assertCorruptReplayRejected(
        committer,
        receipt,
        start,
        drafts,
        "room_message",
        "update room_message set message_text = 'drifted' where id = '"
            + committed.publications().getFirst().messageId()
            + "'");
    assertCorruptReplayRejected(
        committer,
        receipt,
        start,
        drafts,
        "case_timeline_event",
        "update case_timeline_event set event_json = '{}'::jsonb where id = '"
            + committed.publications().getLast().eventId()
            + "'");

    var wrongStage =
        new HearingPublicTranscriptPolicy.Draft(
            HearingFlowStage.DOSSIER_FREEZING,
            "wrong-stage",
            MessageSenderType.SYSTEM,
            "SYSTEM",
            HearingPublicTranscriptPolicy.SYSTEM_ACTOR,
            MessageSource.SYSTEM_STAGE_EVENT,
            MessageType.SYSTEM_STAGE_EVENT,
            "wrong stage",
            null);
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status ->
                        committer.commit(
                            JdbcTargetHearingPublicTranscriptCommitter.CommitMode.STRICT_REPLAY,
                            receipt,
                            start,
                            COMMITTED_AT,
                            List.of(wrongStage))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stage");
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status ->
                        committer.commit(
                            JdbcTargetHearingPublicTranscriptCommitter.CommitMode.STRICT_REPLAY,
                            receipt,
                            start(4),
                            COMMITTED_AT,
                            drafts)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("authority");
    assertThat(count("room_message")).isEqualTo(2);
    assertThat(count("hearing_public_transcript_binding")).isEqualTo(2);
    assertThat(count("case_timeline_event")).isEqualTo(2);

    HearingPartyTerminalReceipt partyReceipt = partyReceipt();
    seedReceipt(partyReceipt.committed(), PARTY_COMMITTED_AT);
    List<HearingPublicTranscriptPolicy.Draft> partyDrafts =
        new HearingPublicTranscriptPolicy()
            .partyStageAdvanced(HearingFlowStage.PARTY_ANSWERS_OPEN);
    JdbcTargetHearingPublicTranscriptCommitter.CommitResult partyCommit =
        transactions.execute(
            status ->
                committer.commit(
                    JdbcTargetHearingPublicTranscriptCommitter.CommitMode.NEW_COMMIT,
                    partyReceipt,
                    start,
                    PARTY_COMMITTED_AT,
                    partyDrafts));
    assertThat(partyCommit.publications())
        .extracting(JdbcTargetHearingPublicTranscriptCommitter.Publication::publicationKey)
        .containsExactly("hearing-v2:6:intake-synthesis-next");
    assertThat(partyCommit.receiptId()).isEqualTo(partyReceipt.committed().receiptId());
    assertThat(notifications).containsExactly(CASE_ID, CASE_ID);
    assertThat(count("room_message")).isEqualTo(3);
    assertThat(count("hearing_public_transcript_binding")).isEqualTo(3);
    assertThat(count("case_timeline_event")).isEqualTo(3);
  }

  private static void assertCorruptReplayRejected(
      JdbcTargetHearingPublicTranscriptCommitter committer,
      HearingStageReceipt receipt,
      HearingRoomStart start,
      List<HearingPublicTranscriptPolicy.Draft> drafts,
      String table,
      String corruption) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.execute("alter table " + table + " disable trigger user");
          jdbc.execute(corruption);
          assertThatThrownBy(
                  () ->
                      committer.commit(
                          JdbcTargetHearingPublicTranscriptCommitter.CommitMode.STRICT_REPLAY,
                          receipt,
                          start,
                          COMMITTED_AT,
                          drafts))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("STRICT_REPLAY");
          status.setRollbackOnly();
        });
    assertThat(count("room_message")).isEqualTo(2);
    assertThat(count("hearing_public_transcript_binding")).isEqualTo(2);
    assertThat(count("case_timeline_event")).isEqualTo(2);
  }

  private static List<HearingPublicTranscriptPolicy.Draft> drafts() {
    return List.of(
        new HearingPublicTranscriptPolicy.Draft(
            HearingFlowStage.JUDGE_V1_GENERATING,
            "judge-v1",
            MessageSenderType.AGENT,
            "PRESIDING_JUDGE",
            "presiding_judge",
            MessageSource.AGENT_LLM,
            MessageType.AGENT_MESSAGE,
            "法官初步裁决意见已经形成。",
            AGENT_RUN_ID),
        new HearingPublicTranscriptPolicy.Draft(
            HearingFlowStage.JURY_REVIEWING,
            "jury-review-next",
            MessageSenderType.SYSTEM,
            "SYSTEM",
            HearingPublicTranscriptPolicy.SYSTEM_ACTOR,
            MessageSource.SYSTEM_STAGE_EVENT,
            MessageType.SYSTEM_STAGE_EVENT,
            "法官初步裁决意见已形成，现交评审团复核。",
            null));
  }

  private static HearingRoomStart start(long fence) {
    return new HearingRoomStart(
        "hearing-room-start.v1",
        TENANT,
        CASE_ID,
        ROOM_ID,
        FLOW_ID,
        EPOCH_ID,
        HearingWriterMode.TEMPORAL,
        0,
        fence,
        "user-public",
        "merchant-public",
        COMMITTED_AT.minusSeconds(3_600),
        COMMITTED_AT.plusSeconds(3_600),
        1_200,
        17,
        7,
        "hearing-build-public");
  }

  private static HearingStageReceipt receipt() {
    HearingWorkflowStage source = HearingWorkflowStage.JUDGE_V1_GENERATING;
    String operationKey =
        "hearing.finalize:"
            + TENANT
            + ':'
            + CASE_ID
            + ":0:"
            + source.sequence()
            + ":formal-command-1:"
            + REQUEST_HASH;
    return new HearingStageReceipt(
        HearingStageReceipt.SCHEMA_VERSION,
        new HearingCommittedReceipt(
            HearingCommittedReceipt.SCHEMA_VERSION,
            RECEIPT_ID,
            RECEIPT_HASH,
            HearingAuthorityCommit.OperationType.FINALIZE,
            operationKey,
            REQUEST_HASH,
            TENANT,
            CASE_ID,
            FLOW_ID,
            EPOCH_ID,
            0,
            HearingWriterMode.TEMPORAL,
            3,
            source,
            source.sequence(),
            17,
            7,
            HearingWorkflowStage.JURY_REVIEWING,
            HearingWorkflowStage.JURY_REVIEWING.sequence(),
            null,
            18,
            8,
            "urn:target-hearing:artifact:formal-proposal-1",
            RESULT_HASH,
            42,
            100L));
  }

  private static HearingPartyTerminalReceipt partyReceipt() {
    String requestId = "party-request-public-1";
    String participantId = "user-public";
    HearingWorkflowStage source = HearingWorkflowStage.PARTY_ANSWERS_OPEN;
    String requestHash = "d".repeat(64);
    HearingCommittedReceipt committed =
        new HearingCommittedReceipt(
            HearingCommittedReceipt.SCHEMA_VERSION,
            "hearing-public-party-receipt-1",
            "e".repeat(64),
            HearingAuthorityCommit.OperationType.PARTY_TERMINAL,
            HearingOperationKeys.partyTerminal(
                TENANT, CASE_ID, 0, source, source.sequence(), participantId, requestId),
            requestHash,
            TENANT,
            CASE_ID,
            FLOW_ID,
            EPOCH_ID,
            0,
            HearingWriterMode.TEMPORAL,
            3,
            source,
            source.sequence(),
            18,
            8,
            HearingWorkflowStage.INTAKE_SYNTHESIZING,
            HearingWorkflowStage.INTAKE_SYNTHESIZING.sequence(),
            null,
            19,
            9,
            "urn:target-hearing:party:answer-bundle-1",
            "f".repeat(64),
            43,
            101L);
    return new HearingPartyTerminalReceipt(
        HearingPartyTerminalReceipt.SCHEMA_VERSION,
        requestId,
        participantId,
        HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
        committed);
  }

  private static long count(String table) {
    Long value = jdbc.queryForObject("select count(*) from " + table, Long.class);
    return value == null ? 0 : value;
  }

  private static void seedAuthority() {
    jdbc.update("insert into fulfillment_dispute_case (id) values (?)", CASE_ID);
    jdbc.update(
        "insert into case_room (id, case_id, room_type, room_status) values (?, ?, 'HEARING', 'OPEN')",
        ROOM_ID,
        CASE_ID);
    jdbc.update(
        """
        insert into case_room_epoch (
          id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
          writer_mode, fencing_token)
        values (?, ?, ?, ?, 'HEARING', 0, 'TEMPORAL', 3)
        """,
        EPOCH_ID,
        TENANT,
        CASE_ID,
        ROOM_ID);
    jdbc.update(
        "insert into agent_run (id, case_id, workflow_id, room_id) values (?, ?, ?, ?)",
        AGENT_RUN_ID,
        CASE_ID,
        AGENT_WORKFLOW_ID,
        ROOM_ID);
    seedReceipt(receipt().committed(), COMMITTED_AT);
  }

  private static void seedReceipt(HearingCommittedReceipt committed, Instant committedAt) {
    jdbc.update(
        """
        insert into hearing_domain_receipt (
          schema_version, receipt_id, receipt_hash, operation_type, operation_key,
          request_hash, tenant_surrogate, case_id, flow_instance_id, epoch_id,
          room_type, hearing_epoch, writer_mode, fencing_token, source_stage,
          source_stage_sequence, source_process_revision, source_room_revision,
          stage_code, stage_sequence, process_revision, room_revision, result_ref,
          result_hash, committed_event_sequence, temporal_history_event_id, committed_at)
        values ('hearing-domain-receipt.v1', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HEARING',
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        committed.receiptId(),
        committed.receiptHash(),
        committed.operationType().name(),
        committed.operationKey(),
        committed.requestHash(),
        committed.tenantSurrogate(),
        committed.caseId(),
        committed.flowInstanceId(),
        committed.epochId(),
        committed.roomEpoch(),
        committed.writerMode().name(),
        committed.fencingToken(),
        committed.sourceStage().name(),
        committed.sourceStageSequence(),
        committed.sourceProcessRevision(),
        committed.sourceRoomRevision(),
        committed.stage().name(),
        committed.stageSequence(),
        committed.processRevision(),
        committed.roomRevision(),
        committed.resultRef(),
        committed.resultHash(),
        committed.committedEventSequence(),
        committed.temporalHistoryEventId(),
        java.sql.Timestamp.from(committedAt));
  }

  private static void createPreMigrationSchema() {
    jdbc.execute(
        """
        create function hearing_flow_stage_sequence_v2(stage_code text)
        returns integer language sql immutable strict as $$
          select case stage_code
            when 'COURT_PREPARING' then 1
            when 'CASE_INTRODUCTION' then 2
            when 'EVIDENCE_INTRODUCTION' then 3
            when 'INTAKE_QUESTIONS_GENERATING' then 4
            when 'PARTY_ANSWERS_OPEN' then 5
            when 'INTAKE_SYNTHESIZING' then 6
            when 'EVIDENCE_REQUESTS_GENERATING' then 7
            when 'PARTY_EVIDENCE_OPEN' then 8
            when 'EVIDENCE_SYNTHESIZING' then 9
            when 'DOSSIER_FREEZING' then 10
            when 'JUDGE_V1_GENERATING' then 11
            when 'JURY_REVIEWING' then 12
            when 'JUDGE_V2_GENERATING' then 13
            when 'HUMAN_REVIEW_OPEN' then 14
            when 'CLOSED' then 15
            else null
          end
        $$
        """);
    jdbc.execute("create table fulfillment_dispute_case (id varchar(64) primary key)");
    jdbc.execute(
        """
        create table case_room (
          id varchar(64) primary key,
          case_id varchar(64) not null references fulfillment_dispute_case(id),
          room_type varchar(32) not null,
          room_status varchar(32) not null)
        """);
    jdbc.execute(
        """
        create table case_room_epoch (
          id varchar(64) primary key,
          tenant_surrogate varchar(128) not null,
          case_id varchar(64) not null references fulfillment_dispute_case(id),
          room_id varchar(64) not null references case_room(id),
          room_type varchar(32) not null,
          room_epoch bigint not null,
          writer_mode varchar(16) not null,
          fencing_token bigint not null)
        """);
    jdbc.execute(
        """
        create table agent_run (
          id varchar(128) primary key,
          case_id varchar(64),
          workflow_id varchar(128),
          room_id varchar(64))
        """);
    jdbc.execute(
        """
        create table room_message (
          id varchar(64) primary key,
          case_id varchar(64) not null references fulfillment_dispute_case(id),
          room_id varchar(64) not null references case_room(id),
          sequence_no bigint not null,
          sender_type varchar(32) not null,
          sender_role varchar(64) not null,
          sender_id varchar(128) not null,
          audience_json jsonb not null,
          audience_actor_ids_json jsonb not null,
          message_source varchar(32) not null,
          message_type varchar(64) not null,
          message_text text,
          attachment_refs_json jsonb not null,
          agent_run_id varchar(128) references agent_run(id),
          hearing_round integer,
          idempotency_key varchar(128) not null,
          created_at timestamptz not null,
          trace_id varchar(128),
          created_by varchar(128) not null,
          unique (room_id, sequence_no),
          unique (case_id, idempotency_key))
        """);
    jdbc.execute(
        """
        create table case_timeline_event (
          id varchar(64) primary key,
          case_id varchar(64) not null references fulfillment_dispute_case(id),
          dossier_id varchar(64),
          event_type varchar(64) not null,
          event_time timestamptz not null,
          source_refs_json jsonb not null,
          event_json jsonb not null,
          sequence_no bigint not null,
          room_id varchar(64) references case_room(id),
          audience_json jsonb not null,
          audience_actor_ids_json jsonb not null,
          event_key varchar(128),
          created_at timestamptz not null,
          created_by varchar(128) not null,
          unique (case_id, sequence_no),
          unique (case_id, event_key))
        """);
    jdbc.execute(
        """
        create table hearing_domain_receipt (
          schema_version varchar(64) not null,
          receipt_id varchar(64) primary key,
          receipt_hash varchar(64) not null,
          operation_type varchar(32) not null,
          operation_key varchar(512) not null,
          request_hash varchar(64) not null,
          tenant_surrogate varchar(128) not null,
          case_id varchar(64) not null,
          flow_instance_id varchar(64) not null,
          epoch_id varchar(64) not null,
          room_type varchar(32) not null,
          hearing_epoch bigint not null,
          writer_mode varchar(16) not null,
          fencing_token bigint not null,
          source_stage varchar(64) not null,
          source_stage_sequence integer not null,
          source_process_revision bigint not null,
          source_room_revision bigint not null,
          stage_code varchar(64) not null,
          stage_sequence integer not null,
          process_revision bigint not null,
          room_revision bigint not null,
          result_ref varchar(1024) not null,
          result_hash varchar(64) not null,
          committed_event_sequence bigint not null,
          temporal_history_event_id bigint,
          committed_at timestamptz not null,
          unique (receipt_id, receipt_hash))
        """);
    jdbc.execute(
        """
        create function reject_append_only_mutation()
        returns trigger language plpgsql as $$
        begin
          raise exception '% is append-only', tg_table_name using errcode = '55000';
        end;
        $$
        """);
    jdbc.execute(
        "create trigger trg_room_message_append_only before update or delete or truncate on room_message for each statement execute function reject_append_only_mutation()");
    jdbc.execute(
        "create trigger trg_case_timeline_event_append_only before update or delete or truncate on case_timeline_event for each statement execute function reject_append_only_mutation()");
  }
}
