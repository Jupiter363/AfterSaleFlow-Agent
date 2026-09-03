package com.example.dispute.workflow.targete2e.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;
import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.room.application.AgentInvocationContext;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.room.application.EvidenceContextEnvelopeV1;
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.domain.MessageSource;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.ActorScope;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.SnapshotRef;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterialStore.MaterialSnapshot;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceTurnProposalLoader.LoadedProposal;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceTurnProposalLoader.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class JdbcTargetEvidenceFormalCommitPortTest {

  private static final String TENANT = "tenant-evidence";
  private static final String CASE_ID = "CASE_EVIDENCE_1";
  private static final String ROOM_ID = "ROOM_EVIDENCE_1";
  private static final String EPOCH_ID = "EPOCH_EVIDENCE_1";
  private static final String COMMAND_ID = "evidence-submit:EVIDENCE_BATCH_1";
  private static final String OPENING_COMMAND_ID = "evidence-opening:OPENING_1";
  private static final String OPENING_IDEMPOTENCY_KEY =
      "agent-evidence-opening:freeze-v1:CASE_EVIDENCE_1:USER_1:2";
  private static final String COMMAND_ROW_ID = "CASE_COMMAND_EVIDENCE_1";
  private static final String ADMISSION_ID = "p9cmd.v1." + "1".repeat(32);
  private static final String ACTIVATION_ID = "p9act.v1." + "2".repeat(32);
  private static final String MANIFEST_HASH = "3".repeat(64);
  private static final String DATABASE_HASH = "4".repeat(64);
  private static final String COMMAND_HASH = "5".repeat(64);
  private static final String ENVELOPE_HASH = "6".repeat(64);
  private static final String PAYLOAD_HASH = "7".repeat(64);
  private static final String REQUEST_HASH = "8".repeat(64);
  private static final String RESULT_HASH = "9".repeat(64);
  private static final String CLERK_MESSAGE_ID = "MESSAGE_EVIDENCE_CLERK_1";
  private static final String GUARDED_ROOM_UTTERANCE = "请补充该证据的形成时间与原始来源。";
  private static final String FORMAL_OPERATION_ID = "target-e2e-evidence:operation-1";
  private static final String AGENT_SESSION_ID = "AGENT_SESSION_EVIDENCE_1";
  private static final int TURN_NO = 1;
  private static final String ACTOR_SCOPE = "case:" + CASE_ID + ":command:EVIDENCE_SUBMIT";
  private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");
  private static final long COMMAND_SEQUENCE = 3;
  private static final long ROOM_EPOCH = 2;
  private static final long FENCE = 5;
  private static final long PROCESS_REVISION = 11;
  private static final long ROOM_REVISION = 7;

  @Container
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>(
              DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
          .withEnv("POSTGRES_DB", "target_evidence_formal")
          .withEnv("POSTGRES_USER", "target_test")
          .withEnv("POSTGRES_PASSWORD", "target_test")
          .withExposedPorts(5432)
          .waitingFor(Wait.forListeningPort());

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbc;
  private JdbcTargetEvidenceFormalCommitPort port;

  @BeforeEach
  void resetSchema() {
    dataSource =
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ':'
                + POSTGRES.getMappedPort(5432)
                + "/target_evidence_formal",
            "target_test",
            "target_test");
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop table if exists evidence_fact_edge_v2");
    jdbc.execute("drop table if exists evidence_turn_projection_v2");
    jdbc.execute("drop table if exists agent_run_frame_authority");
    jdbc.execute("drop table if exists agent_run_public_frame");
    jdbc.execute("drop table if exists room_message");
    jdbc.execute("drop table if exists case_room");
    jdbc.execute("drop table if exists case_process_projection");
    jdbc.execute("drop table if exists target_e2e_room_epoch_binding");
    jdbc.execute("drop table if exists case_room_epoch");
    jdbc.execute("drop table if exists case_command");
    jdbc.execute("drop table if exists target_e2e_command_admission");
    jdbc.execute("drop table if exists agent_run_attempt");
    jdbc.execute("drop table if exists agent_run");
    jdbc.execute("drop table if exists evidence_party_completion");
    jdbc.execute("drop table if exists evidence_submission_batch");
    jdbc.execute("drop table if exists evidence_item");
    createSchema();
    seedAuthority();
    port = new JdbcTargetEvidenceFormalCommitPort(JsonMapper.builder().build());
  }

  @Test
  void commitsExactGuardedClerkMessageAndReplaysWithoutPlaceholder() throws Exception {
    TargetEvidenceFinalizationRequest request = request(ACTIVATION_ID);
    RoomMessageView message = clerkMessage();
    seedClerkMessage(message);

    TargetEvidenceFormalCommitPort.CommitResult first = commit(request, message);
    TargetEvidenceFormalCommitPort.CommitResult replay = commit(request, message);

    assertThat(replay).isEqualTo(first);
    assertThat(first.formalObjectId()).isEqualTo(CLERK_MESSAGE_ID);
    assertThat(text("select message_text from room_message where id = ?", CLERK_MESSAGE_ID))
        .isEqualTo(GUARDED_ROOM_UTTERANCE)
        .doesNotContain("Evidence analysis completed");
    assertThat(number("select count(*) from room_message where case_id = ?", CASE_ID)).isEqualTo(1);
  }

  @Test
  void openingFormalCommitCreatesOnlyClerkMessageAndResultWithoutSubmissionOrCompletion()
      throws Exception {
    jdbc.update(
        "update agent_run_attempt set command_id = ? where agent_run_id = ?",
        OPENING_COMMAND_ID,
        "target-evidence-run:RUN_1");
    jdbc.update(
        "update target_e2e_command_admission set command_id = ? where admission_id = ?",
        OPENING_COMMAND_ID,
        ADMISSION_ID);
    jdbc.update(
        """
        update case_command
           set command_id = ?,
               command_type = 'EVIDENCE_OPENING',
               actor_scopes_json = ?::jsonb,
               payload_schema_version = 'target-e2e-evidence-opening.v1',
               payload_uri = 'urn:target-e2e:evidence-opening:OPENING_1'
         where id = ?
        """,
        OPENING_COMMAND_ID,
        "[\"case:" + CASE_ID + ":command:EVIDENCE_OPENING\"]",
        COMMAND_ROW_ID);
    TargetEvidenceFinalizationRequest request = openingRequest();
    RoomMessageView message = clerkMessage();
    seedClerkMessage(message, OPENING_IDEMPOTENCY_KEY);

    TargetEvidenceFormalCommitPort.CommitResult first = commit(request, message);
    TargetEvidenceFormalCommitPort.CommitResult replay = commit(request, message);

    assertThat(replay).isEqualTo(first);
    assertThat(first.formalObjectId()).isEqualTo(CLERK_MESSAGE_ID);
    assertThat(text("select command_type from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo("EVIDENCE_OPENING");
    assertThat(text("select command_status from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo("APPLIED");
    assertThat(text("select result_sha256 from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo(first.formalCommitHash());
    assertThat(number("select count(*) from room_message where case_id = ?", CASE_ID)).isEqualTo(1);
    assertThat(number("select count(*) from evidence_item where case_id = ?", CASE_ID)).isZero();
    assertThat(number("select count(*) from evidence_submission_batch where case_id = ?", CASE_ID))
        .isZero();
    assertThat(number("select count(*) from evidence_party_completion where case_id = ?", CASE_ID))
        .isZero();
  }

  @Test
  void atomicallyAdvancesTheFencedCoordinatesAndReplaysWithoutAdvancingAgain()
      throws Exception {
    TargetEvidenceFinalizationRequest request = request(ACTIVATION_ID);

    TargetEvidenceFormalCommitPort.CommitResult first = commit(request);
    TargetEvidenceFormalCommitPort.CommitResult replay = commit(request);

    assertThat(replay).isEqualTo(first);
    assertThat(number("select process_revision from case_room_epoch where id = ?", EPOCH_ID))
        .isEqualTo(PROCESS_REVISION + 1);
    assertThat(number("select room_revision from case_room_epoch where id = ?", EPOCH_ID))
        .isEqualTo(ROOM_REVISION + 1);
    assertThat(number("select process_revision from case_process_projection where case_id = ?", CASE_ID))
        .isEqualTo(PROCESS_REVISION + 1);
    assertThat(number("select last_command_sequence from case_process_projection where case_id = ?", CASE_ID))
        .isEqualTo(COMMAND_SEQUENCE);
    assertThat(text("select command_status from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo("APPLIED");
    assertThat(text("select result_sha256 from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo(first.formalCommitHash());
    assertThat(number("select count(*) from room_message where case_id = ?", CASE_ID)).isEqualTo(1);
  }

  @Test
  void rejectsProjectionCommandCursorDriftWithoutLeavingAPartialFact() throws Exception {
    jdbc.update(
        "update case_process_projection set last_command_sequence = ? where case_id = ?",
        COMMAND_SEQUENCE - 2,
        CASE_ID);
    TargetEvidenceFinalizationRequest request = request(ACTIVATION_ID);

    assertThatThrownBy(() -> commit(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Evidence projection command cursor drifted before formalization");

    assertThat(number("select process_revision from case_room_epoch where id = ?", EPOCH_ID))
        .isEqualTo(PROCESS_REVISION);
    assertThat(text("select command_status from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo("ORCHESTRATION_ACCEPTED");
    assertThat(number("select count(*) from evidence_turn_projection_v2 where case_id = ?", CASE_ID))
        .isZero();
  }

  @Test
  void appliesAWinningRetryAdmissionToTheRootCaseCommand() throws Exception {
    String retryCommandId = "agent-command:" + "a".repeat(32);
    String retryAdmissionId = "p9cmd.v1." + "b".repeat(32);
    String retryCommandHash = "c".repeat(64);
    String retryEnvelopeHash = "d".repeat(64);
    jdbc.update(
        "insert into agent_run_attempt values (?, ?, 2, ?)",
        "target-evidence-attempt:RUN_1:2",
        "target-evidence-run:RUN_1",
        retryCommandId);
    jdbc.update(
        "insert into target_e2e_command_admission values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        retryAdmissionId,
        ACTIVATION_ID,
        MANIFEST_HASH,
        TargetEvidenceCommandMaterial.TARGET_LANE,
        DATABASE_HASH,
        TENANT,
        CASE_ID,
        retryCommandId,
        retryCommandHash,
        retryEnvelopeHash,
        ROOM_EPOCH,
        FENCE);

    TargetEvidenceFormalCommitPort.CommitResult committed = commit(request(
        ACTIVATION_ID,
        retryAdmissionId,
        retryCommandId,
        retryCommandHash,
        retryEnvelopeHash,
        2));

    assertThat(text("select command_status from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo("APPLIED");
    assertThat(text("select result_sha256 from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo(committed.formalCommitHash());
  }

  @Test
  void rejectsActivationDriftBeforeWritingOrAdvancing() throws Exception {
    TargetEvidenceFinalizationRequest request = request("p9act.v1." + "a".repeat(32));

    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      assertThatThrownBy(() -> port.commit(connection, request, clerkMessage()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("activation id drifted");
      connection.rollback();
    }

    assertThat(number("select process_revision from case_room_epoch where id = ?", EPOCH_ID))
        .isEqualTo(PROCESS_REVISION);
    assertThat(text("select command_status from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo("ORCHESTRATION_ACCEPTED");
    assertThat(number("select count(*) from room_message where case_id = ?", CASE_ID)).isZero();
  }

  @Test
  void rejectsProjectionRevisionDriftWithoutLeavingAPartialFact() throws Exception {
    jdbc.update(
        "update case_process_projection set process_revision = ? where case_id = ?",
        PROCESS_REVISION + 4,
        CASE_ID);
    TargetEvidenceFinalizationRequest request = request(ACTIVATION_ID);
    RoomMessageView message = clerkMessage();
    seedClerkMessage(message);

    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      assertThatThrownBy(() -> port.commit(connection, request, message))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Evidence projection revision drifted before formalization");
      connection.rollback();
    }

    assertThat(number("select process_revision from case_room_epoch where id = ?", EPOCH_ID))
        .isEqualTo(PROCESS_REVISION);
    assertThat(text("select command_status from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo("ORCHESTRATION_ACCEPTED");
    assertThat(number("select count(*) from room_message where case_id = ?", CASE_ID)).isEqualTo(1);
  }

  @Test
  void rejectsDifferentWellFormedCaseCommandRequestHashWithoutWriting() throws Exception {
    jdbc.update(
        "update case_command set request_hash = ? where id = ?",
        "a".repeat(64),
        COMMAND_ROW_ID);

    assertRejected(request(ACTIVATION_ID), "case command request hash drifted");

    assertThat(number("select process_revision from case_room_epoch where id = ?", EPOCH_ID))
        .isEqualTo(PROCESS_REVISION);
    assertThat(text("select command_status from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo("ORCHESTRATION_ACCEPTED");
    assertThat(number("select count(*) from room_message where case_id = ?", CASE_ID)).isZero();
  }

  @Test
  void rejectsAppliedReplayAfterCaseCommandRequestHashTamper() throws Exception {
    commit(request(ACTIVATION_ID));
    jdbc.update(
        "update case_command set request_hash = ? where id = ?",
        "a".repeat(64),
        COMMAND_ROW_ID);

    assertRejected(request(ACTIVATION_ID), "case command request hash drifted");

    assertThat(number("select process_revision from case_room_epoch where id = ?", EPOCH_ID))
        .isEqualTo(PROCESS_REVISION + 1);
    assertThat(number("select count(*) from room_message where case_id = ?", CASE_ID)).isEqualTo(1);
  }

  @Test
  void rejectsSealedEvidenceRoomWithoutWritingOrAdvancing() throws Exception {
    jdbc.update("update case_room set room_status = 'SEALED' where id = ?", ROOM_ID);

    assertRejected(request(ACTIVATION_ID), "target Evidence room is not open");

    assertThat(number("select process_revision from case_room_epoch where id = ?", EPOCH_ID))
        .isEqualTo(PROCESS_REVISION);
    assertThat(text("select command_status from case_command where id = ?", COMMAND_ROW_ID))
        .isEqualTo("ORCHESTRATION_ACCEPTED");
    assertThat(number("select count(*) from room_message where case_id = ?", CASE_ID)).isZero();
  }

  @Test
  void rejectsActorScopeTamperWithoutWriting() throws Exception {
    jdbc.update(
        "update case_command set actor_scopes_json = ?::jsonb where id = ?",
        "[\"case:other:command:EVIDENCE_SUBMIT\"]",
        COMMAND_ROW_ID);

    assertRejected(request(ACTIVATION_ID), "case command actor scopes drifted");
    assertThat(number("select count(*) from room_message where case_id = ?", CASE_ID)).isZero();
  }

  @Test
  void rejectsDeadlineTamperWithoutWriting() throws Exception {
    jdbc.update(
        "update case_command set deadline_at = ? where id = ?",
        java.sql.Timestamp.from(DEADLINE.plusSeconds(60)),
        COMMAND_ROW_ID);

    assertRejected(request(ACTIVATION_ID), "case command deadline drifted");
    assertThat(number("select count(*) from room_message where case_id = ?", CASE_ID)).isZero();
  }

  private TargetEvidenceFormalCommitPort.CommitResult commit(
      TargetEvidenceFinalizationRequest request) throws Exception {
    RoomMessageView message = clerkMessage();
    seedClerkMessage(message);
    return commit(request, message);
  }

  private TargetEvidenceFormalCommitPort.CommitResult commit(
      TargetEvidenceFinalizationRequest request, RoomMessageView message) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        TargetEvidenceFormalCommitPort.CommitResult result =
            port.commit(connection, request, message);
        connection.commit();
        return result;
      } catch (RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private RoomMessageView clerkMessage() {
    return new RoomMessageView(
        CLERK_MESSAGE_ID,
        CASE_ID,
        ROOM_ID,
        1,
        "EVIDENCE_CLERK",
        "evidence-clerk",
        MessageType.AGENT_MESSAGE,
        MessageSource.AGENT_LLM,
        GUARDED_ROOM_UTTERANCE,
        java.util.List.of(),
        "target-evidence-run:RUN_1",
        Instant.parse("2030-01-01T00:00:01Z"));
  }

  private void seedClerkMessage(RoomMessageView message) {
    seedClerkMessage(
        message,
        "agent-evidence-turn:"
            + CASE_ID
            + ":"
            + AGENT_SESSION_ID
            + ":USER:"
            + TURN_NO);
  }

  private void seedClerkMessage(RoomMessageView message, String idempotencyKey) {
    jdbc.update(
        """
        insert into room_message (
          id, case_id, room_id, sequence_no, sender_type, sender_role, sender_id,
          audience_json, audience_actor_ids_json, message_source, message_type, message_text,
          attachment_refs_json, agent_run_id, idempotency_key, created_at, trace_id, created_by)
        values (?, ?, ?, ?, 'AGENT', ?, ?, '[]'::jsonb, '[]'::jsonb, ?, ?, ?, '[]'::jsonb,
          ?, ?, ?, null, ?)
        on conflict (id) do nothing
        """,
        message.id(),
        message.caseId(),
        message.roomId(),
        message.sequenceNo(),
        message.senderRole(),
        message.senderId(),
        message.messageSource().name(),
        message.messageType().name(),
        message.messageText(),
        message.agentRunId(),
        idempotencyKey,
        java.sql.Timestamp.from(message.createdAt()),
        message.senderId());
  }

  private void assertRejected(TargetEvidenceFinalizationRequest request, String message)
      throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      assertThatThrownBy(() -> port.commit(connection, request, clerkMessage()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(message);
      connection.rollback();
    }
  }

  private TargetEvidenceFinalizationRequest request(String activationId) {
    return request(
        activationId,
        ADMISSION_ID,
        COMMAND_ID,
        COMMAND_HASH,
        ENVELOPE_HASH,
        1);
  }

  private TargetEvidenceFinalizationRequest openingRequest() {
    return request(
        ACTIVATION_ID,
        ADMISSION_ID,
        OPENING_COMMAND_ID,
        COMMAND_HASH,
        ENVELOPE_HASH,
        1,
        true);
  }

  private TargetEvidenceFinalizationRequest request(
      String activationId,
      String admissionId,
      String graphCommandId,
      String commandHash,
      String envelopeHash,
      long attemptNo) {
    return request(
        activationId,
        admissionId,
        graphCommandId,
        commandHash,
        envelopeHash,
        attemptNo,
        false);
  }

  private TargetEvidenceFinalizationRequest request(
      String activationId,
      String admissionId,
      String graphCommandId,
      String commandHash,
      String envelopeHash,
      long attemptNo,
      boolean opening) {
    String commandType = opening ? "EVIDENCE_OPENING" : "EVIDENCE_SUBMIT";
    RoomGraphCommand graph = mock(RoomGraphCommand.class);
    when(graph.tenantSurrogate()).thenReturn(TENANT);
    when(graph.caseId()).thenReturn(CASE_ID);
    when(graph.commandId()).thenReturn(graphCommandId);
    when(graph.logicalRunId()).thenReturn("target-evidence-run:RUN_1");
    when(graph.attemptId()).thenReturn("target-evidence-attempt:RUN_1:" + attemptNo);
    when(graph.threadId()).thenReturn("target-evidence-thread:USER_1");
    when(graph.roomType()).thenReturn(RoomType.EVIDENCE);
    when(graph.roomEpoch()).thenReturn(ROOM_EPOCH);
    when(graph.actorScope())
        .thenReturn(
            new ActorScope(
                "USER_1",
                ActorRole.USER,
                Audience.USER,
                java.util.List.of("case:" + CASE_ID + ":command:" + commandType)));
    when(graph.deadlineAt()).thenReturn(DEADLINE);
    when(graph.eventRef())
        .thenReturn(
            new SnapshotRef(
                "EVENT_EVIDENCE_1",
                opening
                    ? "target-e2e-evidence-opening.v1"
                    : "target-e2e-evidence-submission.v1",
                opening
                    ? "urn:target-e2e:evidence-opening:OPENING_1"
                    : "urn:target-e2e:timeline-event:EVENT_EVIDENCE_1",
                PAYLOAD_HASH,
                321));
    when(graph.domainSnapshotRef())
        .thenReturn(
            new SnapshotRef(
                "EVIDENCE_INVOCATION_1",
                "target-e2e-evidence-turn-invocation.v2",
                "urn:target-e2e:object:EVIDENCE_INVOCATION_1",
                "a".repeat(64),
                512));
    ExecuteAgentRunRequest execution = mock(ExecuteAgentRunRequest.class);
    when(execution.command()).thenReturn(graph);
    when(execution.agentRunId()).thenReturn("target-evidence-run:RUN_1");
    when(execution.attemptNo()).thenReturn(attemptNo);
    ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
    when(result.resultHash()).thenReturn(RESULT_HASH);
    CommitCommand command =
        new CommitCommand(execution, result, mock(AgentExecutionManifest.class));
    TargetEvidenceCommandMaterial material = mock(TargetEvidenceCommandMaterial.class);
    when(material.schemaVersion()).thenReturn(TargetEvidenceCommandMaterial.SCHEMA_VERSION);
    AgentInvocationContext agentContext = mock(AgentInvocationContext.class);
    when(agentContext.agentSessionId()).thenReturn(AGENT_SESSION_ID);
    EvidenceContextEnvelopeV1.CurrentEvent currentEvent =
        new EvidenceContextEnvelopeV1.CurrentEvent(
            opening ? OPENING_IDEMPOTENCY_KEY : "MESSAGE_EVIDENCE_PARTY_1",
            opening ? "ROOM_OPENING" : "PARTY_MESSAGE",
            opening ? MessageType.AGENT_MESSAGE : MessageType.PARTY_EVIDENCE_REFERENCE,
            "USER_1",
            "USER",
            null,
            opening ? java.util.List.of() : java.util.List.of("EVIDENCE_ITEM_1"),
            TURN_NO,
            Instant.parse("2030-01-01T00:00:00Z").toString());
    EvidenceContextEnvelopeV1 envelope =
        new EvidenceContextEnvelopeV1(
            EvidenceContextEnvelopeV1.SCHEMA_VERSION,
            Instant.parse("2030-01-01T00:00:00Z").toString(),
            new EvidenceContextEnvelopeV1.CaseSnapshot(
                CASE_ID,
                1,
                "EVIDENCE_OPEN",
                "FULFILLMENT_DISPUTE",
                "SIGNED_NOT_RECEIVED",
                "USER",
                "Evidence dispute",
                "Evidence dispute",
                "HIGH",
                "FORMAL",
                "ORDER_1",
                "AFTER_1",
                "LOGISTICS_1",
                "OMS",
                "OMS",
                "EXT_1",
                "EVIDENCE",
                DEADLINE.toString()),
            null,
            new EvidenceContextEnvelopeV1.ActorSnapshot(
                "USER_1",
                "USER",
                "USER",
                "ACCESS_SESSION_1",
                AGENT_SESSION_ID,
                "PARTY_PRIVATE",
                "evidence-clerk",
                "memory-v1"),
            currentEvent,
            java.util.List.of(),
            new EvidenceContextEnvelopeV1.PrivateConversation(
                AGENT_SESSION_ID, "PARTY_PRIVATE", 0, false, java.util.List.of()),
            new EvidenceContextEnvelopeV1.RoomPolicy(
                ROOM_ID,
                com.example.dispute.room.domain.RoomType.EVIDENCE,
                "OPEN",
                DEADLINE.toString(),
                "USER",
                true));
    EvidenceAgentTurnCommand turnCommand =
        new EvidenceAgentTurnCommand(envelope, agentContext);
    when(material.evidenceAgentTurnCommand()).thenReturn(turnCommand);
    when(material.request()).thenReturn(execution);
    MaterialSnapshot snapshot =
        new MaterialSnapshot(admissionId, null, material, "b".repeat(64), Instant.EPOCH);
    var mapper = JsonMapper.builder().build();
    TargetEvidenceTurnResultV2 turnResult =
        singleReadinessResult(mapper, graphCommandId, graph.attemptId());
    var turnResultJson = turnResult.document();
    LoadedProposal proposal =
        new LoadedProposal(
            "urn:target-e2e:proposal:PROPOSAL_1",
            "c".repeat(64),
            "d".repeat(64),
            graphCommandId,
            graph.logicalRunId(),
            graph.attemptId(),
            TENANT,
            CASE_ID,
            ROOM_EPOCH,
            FENCE,
            graph.threadId(),
            "USER_1",
            ActorRole.USER.name(),
            "e".repeat(64),
            graph.domainSnapshotRef().sha256(),
            turnResultJson,
            turnResult,
            GUARDED_ROOM_UTTERANCE,
            "f".repeat(64),
            new Usage(7, 5, 12),
            Instant.parse("2030-01-01T00:00:00Z"));
    seedPublicFrames(graph, turnResult);
    return new TargetEvidenceFinalizationRequest(
        TargetEvidenceCommandMaterial.TARGET_LANE,
        activationId,
        MANIFEST_HASH,
        admissionId,
        DATABASE_HASH,
        commandHash,
        envelopeHash,
        REQUEST_HASH,
        FENCE,
        PROCESS_REVISION,
        ROOM_REVISION,
        FORMAL_OPERATION_ID,
        "EVIDENCE_SEAL",
        PROCESS_REVISION,
        "USER_1",
        ActorRole.USER,
        Audience.USER,
        command,
        snapshot,
        proposal);
  }

  private static TargetEvidenceTurnResultV2 singleReadinessResult(
      ObjectMapper mapper, String commandId, String attemptId) {
    var header = mapper.createObjectNode();
    header.put("frame_sequence", 1);
    header.put("frame_type", "ROOM_READINESS");
    header.put("core_fact_coverage", "UNKNOWN");
    header.put("source_chain_coverage", "UNKNOWN");
    header.put("time_integrity_coverage", "UNKNOWN");
    header.put("overall_readiness", "UNKNOWN");

    var frame = mapper.createObjectNode();
    frame.put(
        "frame_id",
        TargetEvidenceTurnResultV2.frameId(commandId, attemptId, 1, "ROOM_READINESS"));
    frame.put("frame_sequence", 1);
    frame.put("frame_type", "ROOM_READINESS");
    frame.set("header", header);
    frame.put("header_sha256", ContractJson.sha256Hex(header));
    frame.put("public_text", GUARDED_ROOM_UTTERANCE);
    frame.put("public_text_sha256", sha256(GUARDED_ROOM_UTTERANCE));
    frame.put(
        "public_text_length",
        GUARDED_ROOM_UTTERANCE.codePointCount(0, GUARDED_ROOM_UTTERANCE.length()));
    var framePreimage = frame.deepCopy();
    frame.put("frame_sha256", ContractJson.sha256Hex(framePreimage));

    var manifest = mapper.createArrayNode().add(frame);
    var result = mapper.createObjectNode();
    result.put("schema_version", TargetEvidenceTurnResultV2.SCHEMA_VERSION);
    result.put("frame_authority_schema", TargetEvidenceTurnResultV2.FRAME_SCHEMA_VERSION);
    result.set("frame_manifest", manifest);
    result.put("frame_manifest_sha256", ContractJson.sha256Hex(manifest));
    result.put("room_utterance", GUARDED_ROOM_UTTERANCE);
    result.set("referenced_evidence_ids", mapper.createArrayNode());
    result.set("observation_graph", mapper.createArrayNode());
    result.set("evidence_assessments", mapper.createArrayNode());
    result.set("evidence_requests", mapper.createArrayNode());
    result.set("room_readiness", header);
    return TargetEvidenceTurnResultV2.parse(mapper, result);
  }

  private void seedPublicFrames(
      RoomGraphCommand graph, TargetEvidenceTurnResultV2 turnResult) {
    for (TargetEvidenceTurnResultV2.Frame frame : turnResult.frames()) {
      if (frame.publicText() == null) {
        continue;
      }
      jdbc.update(
          """
          insert into agent_run_public_frame (
            id, agent_run_id, agent_run_attempt_id, frame_id, frame_sequence, frame_type,
            public_header, public_text, header_sha256, public_text_sha256, frame_sha256,
            public_text_chars, durable_cursor, commit_status)
          values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, 'COMMITTED')
          on conflict (agent_run_id, agent_run_attempt_id, frame_id) do nothing
          """,
          "ARPF_" + frame.frameId(),
          graph.logicalRunId(),
          graph.attemptId(),
          frame.frameId(),
          frame.frameSequence(),
          frame.frameType(),
          ContractJson.canonicalString(frame.header()),
          frame.publicText(),
          frame.headerSha256(),
          frame.publicTextSha256(),
          frame.frameSha256(),
          frame.publicTextLength(),
          "v3:" + graph.attemptId() + ":FRAME:" + frame.frameSequence());
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private void createSchema() {
    jdbc.execute("create table evidence_item (id varchar(64) primary key, case_id varchar(64) not null)");
    jdbc.execute(
        "create table evidence_submission_batch (id varchar(64) primary key, case_id varchar(64) not null)");
    jdbc.execute(
        "create table evidence_party_completion (id varchar(64) primary key, case_id varchar(64) not null)");
    jdbc.execute("""
        create table agent_run (
          id varchar(128) primary key,
          protocol varchar(32) not null,
          executor_kind varchar(32) not null)
        """);
    jdbc.execute("""
        create table agent_run_attempt (
          id varchar(128) primary key,
          agent_run_id varchar(128) not null references agent_run(id),
          attempt_no bigint not null,
          command_id varchar(128) not null,
          unique (agent_run_id, attempt_no))
        """);
    jdbc.execute("""
        create table target_e2e_command_admission (
          admission_id varchar(64) primary key,
          activation_id varchar(64) not null,
          activation_manifest_hash varchar(64) not null,
          execution_lane varchar(32) not null,
          isolated_domain_db_binding_hash varchar(64) not null,
          tenant_surrogate varchar(128) not null,
          case_id varchar(64) not null,
          command_id varchar(128) not null,
          command_hash varchar(64) not null,
          command_envelope_hash varchar(64) not null,
          room_epoch bigint not null,
          room_fencing_token bigint not null)
        """);
    jdbc.execute("""
        create table case_command (
          id varchar(64) primary key,
          tenant_surrogate varchar(128) not null,
          case_id varchar(64) not null,
          command_id varchar(128) not null,
          case_command_sequence bigint not null,
          command_type varchar(64) not null,
          room_type varchar(32) not null,
          room_epoch bigint not null,
          actor_id varchar(128) not null,
          actor_role varchar(32) not null,
          actor_scopes_json jsonb not null,
          payload_schema_version varchar(128) not null,
          payload_uri varchar(1024) not null,
          payload_sha256 varchar(64) not null,
          payload_size_bytes bigint not null,
          expected_process_revision bigint not null,
          request_hash varchar(64) not null,
          deadline_at timestamptz not null,
          command_status varchar(32) not null,
          status_reason_code varchar(64),
          result_uri varchar(1024),
          result_sha256 varchar(64),
          applied_at timestamptz,
          updated_at timestamptz not null default now(),
          version bigint not null default 0)
        """);
    jdbc.execute("""
        create table case_room_epoch (
          id varchar(64) primary key,
          room_id varchar(64) not null,
          tenant_surrogate varchar(128) not null,
          case_id varchar(64) not null,
          room_type varchar(32) not null,
          room_epoch bigint not null,
          lifecycle_status varchar(16) not null,
          writer_mode varchar(16) not null,
          process_revision bigint not null,
          room_revision bigint not null,
          fencing_token bigint not null,
          updated_at timestamptz not null default now(),
          version bigint not null default 0)
        """);
    jdbc.execute("""
        create table target_e2e_room_epoch_binding (
          epoch_id varchar(64) primary key,
          activation_id varchar(64) not null,
          activation_manifest_hash varchar(64) not null,
          execution_lane varchar(32) not null,
          isolated_domain_db_binding_hash varchar(64) not null)
        """);
    jdbc.execute("""
        create table case_process_projection (
          case_id varchar(64) primary key,
          tenant_surrogate varchar(128) not null,
          macro_phase varchar(64) not null,
          current_room varchar(32) not null,
          room_phase varchar(64) not null,
          writer_mode varchar(16) not null,
          process_revision bigint not null,
          room_epoch bigint not null,
          fencing_token bigint not null,
          last_command_sequence bigint not null,
          updated_at timestamptz not null default now(),
          version bigint not null default 0)
        """);
    jdbc.execute("""
        create table case_room (
          id varchar(64) primary key,
          case_id varchar(64) not null,
          room_type varchar(32) not null,
          room_status varchar(32) not null)
        """);
    jdbc.execute("""
        create table room_message (
          id varchar(64) primary key,
          case_id varchar(64) not null,
          room_id varchar(64) not null,
          sequence_no bigint not null,
          sender_type varchar(32) not null,
          sender_role varchar(32) not null,
          sender_id varchar(128) not null,
          audience_json jsonb not null,
          audience_actor_ids_json jsonb not null,
          message_source varchar(32) not null,
          message_type varchar(32) not null,
          message_text text not null,
          attachment_refs_json jsonb not null,
          agent_run_id varchar(128),
          idempotency_key varchar(128) not null,
          created_at timestamptz not null,
          trace_id varchar(128),
          created_by varchar(128) not null,
          unique (case_id, idempotency_key))
        """);
    jdbc.execute("""
        create table agent_run_public_frame (
          id varchar(64) primary key,
          agent_run_id varchar(128) not null,
          agent_run_attempt_id varchar(128) not null,
          frame_id varchar(128) not null,
          frame_sequence integer not null,
          frame_type varchar(64) not null,
          public_header jsonb not null,
          public_text text not null,
          header_sha256 varchar(64) not null,
          public_text_sha256 varchar(64) not null,
          frame_sha256 varchar(64) not null,
          public_text_chars integer not null,
          durable_cursor varchar(256) not null,
          commit_status varchar(32) not null,
          unique (agent_run_id, agent_run_attempt_id, frame_id))
        """);
    jdbc.execute("""
        create table agent_run_frame_authority (
          id varchar(64) primary key,
          agent_run_id varchar(128) not null,
          agent_run_attempt_id varchar(128) not null,
          command_id varchar(128) not null,
          frame_id varchar(128) not null,
          frame_sequence integer not null,
          frame_type varchar(64) not null,
          private_header jsonb not null,
          public_header jsonb not null,
          public_text text,
          header_sha256 varchar(64) not null,
          public_text_sha256 varchar(64) not null,
          frame_sha256 varchar(64) not null,
          unique (agent_run_id, agent_run_attempt_id, frame_id))
        """);
    jdbc.execute("""
        create table evidence_turn_projection_v2 (
          id varchar(64) primary key,
          case_id varchar(64) not null,
          room_epoch bigint not null,
          command_id varchar(128) not null,
          agent_run_id varchar(128) not null,
          agent_run_attempt_id varchar(128) not null,
          actor_id varchar(128) not null,
          actor_role varchar(32) not null,
          room_message_id varchar(64) not null,
          frame_manifest_sha256 varchar(64) not null,
          result_sha256 varchar(64) not null,
          observation_graph jsonb not null,
          evidence_assessments jsonb not null,
          evidence_requests jsonb not null,
          human_review_tasks jsonb not null,
          room_readiness jsonb not null,
          unique (case_id, command_id))
        """);
    jdbc.execute("""
        create table evidence_fact_edge_v2 (
          id varchar(64) primary key,
          projection_id varchar(64) not null,
          case_id varchar(64) not null,
          evidence_id varchar(64) not null,
          source_unit_id varchar(128) not null,
          observation_slot varchar(128) not null,
          fact_id varchar(128) not null,
          relation varchar(64) not null,
          reason text not null,
          unique (projection_id, observation_slot, fact_id))
        """);
  }

  private void seedAuthority() {
    jdbc.update(
        "insert into agent_run values (?, 'agent-stream.v3', 'TEMPORAL_ACTIVITY')",
        "target-evidence-run:RUN_1");
    jdbc.update(
        "insert into agent_run_attempt values (?, ?, 1, ?)",
        "target-evidence-attempt:RUN_1:1",
        "target-evidence-run:RUN_1",
        COMMAND_ID);
    jdbc.update(
        "insert into target_e2e_command_admission values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ADMISSION_ID,
        ACTIVATION_ID,
        MANIFEST_HASH,
        TargetEvidenceCommandMaterial.TARGET_LANE,
        DATABASE_HASH,
        TENANT,
        CASE_ID,
        COMMAND_ID,
        COMMAND_HASH,
        ENVELOPE_HASH,
        ROOM_EPOCH,
        FENCE);
    jdbc.update(
        """
        insert into case_command (
          id, tenant_surrogate, case_id, command_id, case_command_sequence, command_type,
          room_type, room_epoch, actor_id, actor_role, actor_scopes_json,
          payload_schema_version, payload_uri, payload_sha256, payload_size_bytes,
          expected_process_revision, request_hash, deadline_at, command_status)
        values (?, ?, ?, ?, ?, 'EVIDENCE_SUBMIT', 'EVIDENCE', ?, 'USER_1', 'USER', ?::jsonb,
          'target-e2e-evidence-submission.v1', 'urn:target-e2e:timeline-event:EVENT_EVIDENCE_1',
          ?, 321, ?, ?, ?, 'ORCHESTRATION_ACCEPTED')
        """,
        COMMAND_ROW_ID,
        TENANT,
        CASE_ID,
        COMMAND_ID,
        COMMAND_SEQUENCE,
        ROOM_EPOCH,
        "[\"" + ACTOR_SCOPE + "\"]",
        PAYLOAD_HASH,
        PROCESS_REVISION,
        REQUEST_HASH,
        java.sql.Timestamp.from(DEADLINE));
    jdbc.update(
        "insert into case_room values (?, ?, 'EVIDENCE', 'OPEN')", ROOM_ID, CASE_ID);
    jdbc.update(
        """
        insert into case_room_epoch (
          id, room_id, tenant_surrogate, case_id, room_type, room_epoch, lifecycle_status,
          writer_mode, process_revision, room_revision, fencing_token)
        values (?, ?, ?, ?, 'EVIDENCE', ?, 'ACTIVE', 'TEMPORAL', ?, ?, ?)
        """,
        EPOCH_ID,
        ROOM_ID,
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        PROCESS_REVISION,
        ROOM_REVISION,
        FENCE);
    jdbc.update(
        "insert into target_e2e_room_epoch_binding values (?, ?, ?, ?, ?)",
        EPOCH_ID,
        ACTIVATION_ID,
        MANIFEST_HASH,
        TargetEvidenceCommandMaterial.TARGET_LANE,
        DATABASE_HASH);
    jdbc.update(
        """
        insert into case_process_projection (
          case_id, tenant_surrogate, macro_phase, current_room, room_phase, writer_mode,
          process_revision, room_epoch, fencing_token, last_command_sequence)
        values (?, ?, 'EVIDENCE_OPEN', 'EVIDENCE', 'OPEN', 'TEMPORAL', ?, ?, ?, ?)
        """,
        CASE_ID,
        TENANT,
        PROCESS_REVISION,
        ROOM_EPOCH,
        FENCE,
        COMMAND_SEQUENCE - 1);
  }

  private long number(String sql, Object... parameters) {
    return jdbc.queryForObject(sql, Long.class, parameters);
  }

  private String text(String sql, Object... parameters) {
    return jdbc.queryForObject(sql, String.class, parameters);
  }
}

class TargetEvidenceFinalizationPersistenceExceptionTest {

  @Test
  void serializationFailureIsTypedRetryableAndSameCommitReplaysExactlyOnce() throws Exception {
    RuntimeException serialization = commitFailure("40001");
    RuntimeException deadlock = commitFailure("40P01");
    RuntimeException identicalRetry = commitFailure("40001");

    for (RuntimeException failure : java.util.List.of(serialization, deadlock, identicalRetry)) {
      assertThat(failure)
          .isInstanceOf(AgentRunFinalizationFailure.class)
          .hasMessage("target Evidence formal commit was interrupted by a transient transaction conflict");
      AgentRunFinalizationFailure typed = (AgentRunFinalizationFailure) failure;
      assertThat(typed.retryable()).isTrue();
      assertThat(typed.code())
          .isEqualTo("TargetEvidenceFinalizationPersistenceRetryable");
    }
    assertThat(serialization.getCause()).isInstanceOf(SQLException.class);
    assertThat(((SQLException) serialization.getCause()).getSQLState()).isEqualTo("40001");
    assertThat(identicalRetry.getCause()).isInstanceOf(SQLException.class);
    assertThat(((SQLException) identicalRetry.getCause()).getSQLState()).isEqualTo("40001");

    RuntimeException deterministic = commitFailure("23505");
    assertThat(deterministic)
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(AgentRunFinalizationFailure.class)
        .hasMessage("target Evidence formal commit failed")
        .hasCauseInstanceOf(SQLException.class);
  }

  private static RuntimeException commitFailure(String sqlState) throws Exception {
    Connection transaction = mock(Connection.class);
    when(transaction.getAutoCommit()).thenReturn(false);
    when(transaction.getTransactionIsolation())
        .thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
    when(transaction.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
        .thenThrow(new SQLException("synthetic transaction failure", sqlState));

    RoomGraphCommand graph = mock(RoomGraphCommand.class);
    when(graph.roomType()).thenReturn(RoomType.EVIDENCE);
    ExecuteAgentRunRequest execution = mock(ExecuteAgentRunRequest.class);
    when(execution.command()).thenReturn(graph);
    CommitCommand command = mock(CommitCommand.class);
    when(command.request()).thenReturn(execution);
    TargetEvidenceFinalizationRequest request = mock(TargetEvidenceFinalizationRequest.class);
    when(request.command()).thenReturn(command);

    try {
      new JdbcTargetEvidenceFormalCommitPort(JsonMapper.builder().build())
          .commit(transaction, request, mock(RoomMessageView.class));
      throw new AssertionError("synthetic SQL failure was not propagated");
    } catch (RuntimeException failure) {
      org.mockito.Mockito.verify(transaction, org.mockito.Mockito.times(1))
          .prepareStatement(org.mockito.ArgumentMatchers.anyString());
      return failure;
    }
  }
}
