package com.example.dispute.workflow.runtime.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingFormalTransition;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingAuthorityLedger;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingFormalFinalizer;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingFormalizationActivities.TransitionRequest;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingOperationKeys;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcTargetHearingFormalizationActivitiesTest {

  @Test
  void reviewHandoffPersistsTheAuthoritativeFullHearingRoute() {
    assertThat(JdbcTargetHearingFormalizationActivities.TARGET_REVIEW_SOURCE_ROUTE)
        .isEqualTo("FULL_HEARING")
        .isNotEqualTo("HEARING_V2");
  }

  @Test
  void dossierFreezeDerivesAnExactFinalizeKeyAndReplaysByFrozenSourceCoordinates() {
    String requestHash = "a".repeat(64);
    HearingAuthorityExpectation authority = new HearingAuthorityExpectation(
        "tenant-dossier", "CASE_DOSSIER", "FLOW_DOSSIER", "EPOCH_DOSSIER", 3,
        HearingWriterMode.TEMPORAL,
        com.example.dispute.hearing.domain.HearingFlowStage.DOSSIER_FREEZING,
        HearingWorkflowStage.DOSSIER_FREEZING.sequence(), 17, 9, 4);

    String key = JdbcTargetHearingFormalizationActivities.dossierFinalizeOperationKey(
        authority, requestHash);

    assertThat(key).isEqualTo(
        "hearing.finalize:tenant-dossier:CASE_DOSSIER:3:10:trial_dossier.v2:"
            + requestHash);
    assertThat(key).isNotEqualTo(HearingOperationKeys.stageCompletion(
        "tenant-dossier", "CASE_DOSSIER", 3,
        HearingWorkflowStage.DOSSIER_FREEZING,
        HearingWorkflowStage.DOSSIER_FREEZING.sequence()));
    assertThat(JdbcTargetHearingFormalizationActivities.DOSSIER_FINALIZE_REPLAY_SQL)
        .contains(
            "operation_type = 'FINALIZE'",
            "epoch_id = ? and hearing_epoch = ? and fencing_token = ?",
            "source_stage = ? and source_stage_sequence = ?",
            "source_process_revision = ? and source_room_revision = ?",
            "for update");

    HearingAuthorityExpectation wrongStage = new HearingAuthorityExpectation(
        "tenant-dossier", "CASE_DOSSIER", "FLOW_DOSSIER", "EPOCH_DOSSIER", 3,
        HearingWriterMode.TEMPORAL,
        com.example.dispute.hearing.domain.HearingFlowStage.EVIDENCE_SYNTHESIZING,
        HearingWorkflowStage.EVIDENCE_SYNTHESIZING.sequence(), 16, 8, 4);
    assertThatThrownBy(() ->
        JdbcTargetHearingFormalizationActivities.dossierFinalizeOperationKey(
            wrongStage, requestHash))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DOSSIER_FREEZING");
  }

  @Test
  void partyFormalizationAcquiresCaseCommandBeforeHearingCursor() {
    List<String> acquired = new ArrayList<>();

    var locks = JdbcTargetHearingFormalizationActivities.acquirePartyLocksInGlobalOrder(
        () -> {
          acquired.add("case_command");
          return "COMMAND_LOCK";
        },
        () -> {
          acquired.add("hearing_cursor");
          return "HEARING_LOCK";
        });

    assertThat(acquired).containsExactly("case_command", "hearing_cursor");
    assertThat(locks.caseCommand()).isEqualTo("COMMAND_LOCK");
    assertThat(locks.hearingCursor()).isEqualTo("HEARING_LOCK");
  }

  @Test
  void answerTimeoutIsForbiddenWhileEvidenceTimeoutKeepsItsFormalContract() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Instant deadline = Instant.parse("2026-08-16T07:20:00Z");
    ObjectNode questionSet = mapper.createObjectNode();
    questionSet.put("question_set_id", "QUESTION_SET_TIMEOUT");
    ObjectNode requestSet = mapper.createObjectNode();
    requestSet.put("request_set_id", "REQUEST_SET_TIMEOUT");

    assertThatThrownBy(() -> JdbcTargetHearingFormalizationActivities.timeoutPayload(
        mapper, com.example.dispute.hearing.domain.HearingFlowStage.PARTY_ANSWERS_OPEN,
        "user-timeout", "USER", deadline, questionSet, "BATCH_UNUSED"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("answer timeout payloads are forbidden");

    ObjectNode evidence = JdbcTargetHearingFormalizationActivities.timeoutPayload(
        mapper, com.example.dispute.hearing.domain.HearingFlowStage.PARTY_EVIDENCE_OPEN,
        "merchant-timeout", "MERCHANT", deadline, requestSet, "BATCH_TIMEOUT_EXACT");
    assertThat(evidence.path("schema_version").asText())
        .isEqualTo("hearing_evidence_batch.v1");
    assertThat(evidence.path("participant_id").asText()).isEqualTo("merchant-timeout");
    assertThat(evidence.path("request_set_id").asText()).isEqualTo("REQUEST_SET_TIMEOUT");
    assertThat(evidence.path("batch_id").asText()).isEqualTo("BATCH_TIMEOUT_EXACT");
    assertThat(evidence.path("request_ids")).isEmpty();
    assertThat(evidence.path("evidence_ids")).isEmpty();

    HearingRoomStart start = new HearingRoomStart(
        "hearing-room-start.v1", "tenant-timeout", "CASE_TIMEOUT", "ROOM_TIMEOUT",
        "FLOW_TIMEOUT", "EPOCH_TIMEOUT", HearingWriterMode.TEMPORAL, 2, 9,
        "user-timeout", "merchant-timeout", deadline.minusSeconds(300),
        deadline.plusSeconds(3_600), 300, 30, 12, "hearing-build-v1");
    String operationKey = HearingOperationKeys.partyTerminal(
        start.tenantSurrogate(), start.caseId(), start.roomEpoch(),
        HearingWorkflowStage.PARTY_ANSWERS_OPEN,
        HearingWorkflowStage.PARTY_ANSWERS_OPEN.sequence(), "user-timeout", "AUTO_TIMEOUT");
    TransitionRequest transition = new TransitionRequest(
        start, HearingWorkflowStage.PARTY_ANSWERS_OPEN,
        HearingWorkflowStage.PARTY_ANSWERS_OPEN.sequence(), 30, 12, 9, operationKey);
    assertDoesNotThrow(() -> new TargetHearingFormalizationActivities.TimeoutRequest(
        transition, "user-timeout"));
    assertThatThrownBy(() -> new TargetHearingFormalizationActivities.TimeoutRequest(
        transition, "foreign-timeout"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TargetHearingFormalizationActivities.class.getMethod(
        "formalizeTimeout", TargetHearingFormalizationActivities.TimeoutRequest.class))
        .isNotNull();
    assertThat(HearingRoomWorkflowImpl.class.getDeclaredMethod("formalizeRequiredTimeouts"))
        .isNotNull();
  }

  @Test
  void partyTransitionUsesCommittedReceiptOrderAndExactParticipantActor() throws Exception {
    String tenant = "tenant-party-order";
    String caseId = "CASE_PARTY_ORDER";
    String flowId = "FLOW_PARTY_ORDER";
    String epochId = "EPOCH_PARTY_ORDER";
    String stageId = "STAGE_PARTY_ORDER_5";
    String participantId = "user-party-order";
    Instant opened = Instant.parse("2026-08-16T05:00:00Z");
    HearingWorkflowStage stage = HearingWorkflowStage.PARTY_ANSWERS_OPEN;
    HearingRoomStart start = new HearingRoomStart(
        "hearing-room-start.v1", tenant, caseId, "ROOM_PARTY_ORDER", flowId, epochId,
        HearingWriterMode.TEMPORAL, 0, 7, participantId, "merchant-party-order",
        opened, opened.plusSeconds(3_600), 300, 20, 8, "hearing-build-v1");
    TransitionRequest request = new TransitionRequest(
        start, stage, stage.sequence(), 20, 8, 7,
        "hearing.party:" + tenant + ':' + caseId + ":0:5:" + participantId + ":CMD_PARTY_ORDER");
    HearingAuthorityExpectation authority = new HearingAuthorityExpectation(
        tenant, caseId, flowId, epochId, 0, HearingWriterMode.TEMPORAL,
        com.example.dispute.hearing.domain.HearingFlowStage.PARTY_ANSWERS_OPEN,
        stage.sequence(), 20, 8, 7);

    Class<?> cursorType = Class.forName(
        JdbcTargetHearingFormalizationActivities.class.getName() + "$Cursor");
    var cursorConstructor = cursorType.getDeclaredConstructors()[0];
    cursorConstructor.setAccessible(true);
    Object cursor = cursorConstructor.newInstance(authority, flowId, stageId, "{}");
    Class<?> actionType = Class.forName(
        JdbcTargetHearingFormalizationActivities.class.getName() + "$PartyAction");
    var actionConstructor = actionType.getDeclaredConstructors()[0];
    actionConstructor.setAccessible(true);
    Object action = actionConstructor.newInstance(
        "ACTION_PARTY_ORDER", "ANSWER_BUNDLE", "hearing_answer_bundle.v4",
        participantId, "USER", "SUBMITTED",
        "{\"participant_id\":\"user-party-order\",\"participant_role\":\"USER\","
            + "\"schema_version\":\"hearing_answer_bundle.v4\","
            + "\"submission_status\":\"SUBMITTED\"}",
        "a".repeat(64));

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AtomicReference<Integer> committedTerminalCount = new AtomicReference<>(0);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
        .thenAnswer(invocation -> {
          String sql = invocation.getArgument(0);
          return sql.contains("hearing_domain_receipt")
              ? committedTerminalCount.get()
              : 2;
        });
    ResultSet deadlineRow = mock(ResultSet.class);
    when(deadlineRow.getObject(1, java.time.OffsetDateTime.class))
        .thenReturn(java.time.OffsetDateTime.ofInstant(opened.plusSeconds(300), java.time.ZoneOffset.UTC));
    when(jdbc.query(
        contains("select shared_deadline_at"), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          RowMapper<Object> mapper = invocation.getArgument(1);
          return List.of(mapper.mapRow(deadlineRow, 0));
        });
    JdbcTargetHearingFormalizationActivities subject =
        new JdbcTargetHearingFormalizationActivities(
            mock(DataSource.class), mock(TransactionTemplate.class),
            mock(TargetHearingFormalCompletion.class),
            mock(TargetHearingInternalStageMaterializer.class),
            mock(HearingAuthorityLedger.class), new ObjectMapper().findAndRegisterModules());
    ReflectionTestUtils.setField(subject, "jdbc", jdbc);
    Method transition = JdbcTargetHearingFormalizationActivities.class.getDeclaredMethod(
        "partyTransition", TransitionRequest.class, cursorType, actionType);
    transition.setAccessible(true);

    HearingFormalTransition first =
        (HearingFormalTransition) transition.invoke(subject, request, cursor, action);
    assertThat(first.resultStage())
        .isEqualTo(com.example.dispute.hearing.domain.HearingFlowStage.PARTY_ANSWERS_OPEN);
    assertThat(first.advances()).isFalse();
    assertThat(first.actorId()).isEqualTo(participantId);

    committedTerminalCount.set(1);
    HearingFormalTransition second =
        (HearingFormalTransition) transition.invoke(subject, request, cursor, action);
    assertThat(second.resultStage())
        .isEqualTo(com.example.dispute.hearing.domain.HearingFlowStage.INTAKE_SYNTHESIZING);
    assertThat(second.advances()).isTrue();
    assertThat(second.actorId()).isEqualTo(participantId);
  }

  @Test
  void rejectsLegacyAnswerSchemasAndStatementCommand() {
    assertThrows(IllegalStateException.class,
        () -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
            CommandType.HEARING_STATEMENT, HearingFlowActionType.ANSWER_BUNDLE,
            "hearing_answer_bundle.v1", "hearing_answer_bundle.v1", "hearing_answer_bundle.v1"));
    assertThrows(IllegalStateException.class,
        () -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
            CommandType.HEARING_ANSWER_BUNDLE, HearingFlowActionType.ANSWER_BUNDLE,
            "hearing_party_statement.v1", "hearing_party_statement.v1",
            "hearing_party_statement.v1"));
  }

  @Test
  void acceptsV4AnswerBundleSchemaForAnswerBundleCommand() {
    assertDoesNotThrow(() -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
        CommandType.HEARING_ANSWER_BUNDLE, HearingFlowActionType.ANSWER_BUNDLE,
        "hearing_answer_bundle.v4", "hearing_answer_bundle.v4", "hearing_answer_bundle.v4"));
  }

  @Test
  void rejectsSchemaConfusionAcrossActionEventAndPayload() {
    assertThrows(IllegalStateException.class,
        () -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
            CommandType.HEARING_ANSWER_BUNDLE, HearingFlowActionType.ANSWER_BUNDLE,
            "hearing_answer_bundle.v4", "hearing_answer_bundle.v1", "hearing_answer_bundle.v4"));
  }

  @Test
  void evidenceBatchStillAcceptsOnlyItsOwnSchema() {
    assertDoesNotThrow(() -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
        CommandType.HEARING_EVIDENCE_BATCH, HearingFlowActionType.EVIDENCE_BATCH,
        "hearing_evidence_batch.v1", "hearing_evidence_batch.v1", "hearing_evidence_batch.v1"));
    assertThrows(IllegalStateException.class,
        () -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
            CommandType.HEARING_EVIDENCE_BATCH, HearingFlowActionType.EVIDENCE_BATCH,
            "hearing_answer_bundle.v4", "hearing_answer_bundle.v4", "hearing_answer_bundle.v4"));
  }

  @Test
  void targetHearingPinsTheExactPolicyDecisionOnItsReviewTaskProjection() {
    assertThat(JdbcTargetHearingFormalizationActivities.TARGET_REVIEW_AGENT_RUN_ROLE)
        .isEqualTo("SYSTEM");
    assertThat(JdbcTargetHearingFormalizationActivities.TARGET_REVIEW_DRAFT_VERSION)
        .isEqualTo(2);
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_TASK_INSERT_SQL)
        .contains("packet_id, policy_decision_id, task_status", "values (?, ?, ?, ?, ?");
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_TASK_REPLAY_SQL)
        .contains(
            "policy.id = task.policy_decision_id",
            "policy.case_id = task.case_id",
            "policy.plan_id = task.plan_id",
            "policy.id = ?",
            "policy.policy_version = ?");
  }

  @Test
  void targetHearingRouteReconciliationIsBoundToTheExactTemporalTargetEpoch() {
    assertThat(JdbcTargetHearingFormalizationActivities.RECONCILE_TARGET_FULL_HEARING_ROUTE_SQL)
        .contains(
            "dispute.hearing_route is null",
            "dispute.current_room = 'HEARING'",
            "projection.flow_instance_id = ?",
            "projection.epoch_id = ?",
            "projection.hearing_epoch = ?",
            "projection.process_revision = ?",
            "projection.room_revision = ?",
            "projection.fencing_token = ?",
            "projection.writer_mode = 'TEMPORAL'",
            "epoch.lifecycle_status = 'ACTIVE'",
            "epoch.provisioning_status = 'READY'",
            "binding.execution_lane = 'PRODUCTION'")
        .doesNotContain("coalesce(dispute.hearing_route");

    assertDoesNotThrow(
        () -> JdbcTargetHearingFormalizationActivities.requireFullHearingRoute("FULL_HEARING"));
    assertThatThrownBy(
            () -> JdbcTargetHearingFormalizationActivities.requireFullHearingRoute(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FULL_HEARING");
    assertThatThrownBy(
            () -> JdbcTargetHearingFormalizationActivities.requireFullHearingRoute("QUICK_CHECK"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FULL_HEARING");
  }

  @Test
  void targetHearingBindsTheExactHandoffTaskToTheAllocatedReviewEpoch() {
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_EPOCH_TASK_BINDING_INSERT_SQL)
        .contains(
            "production_runtime_review_epoch_task_binding",
            "review_task_id, plan_id, policy_decision_id, source_handoff_id",
            "on conflict (epoch_id) do nothing");
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_EPOCH_TASK_BINDING_REPLAY_SQL)
        .contains(
            "epoch.id = binding.epoch_id",
            "epoch.fencing_token = binding.room_fencing_token",
            "handoff.review_task_id = binding.review_task_id",
            "task.id = binding.review_task_id",
            "task.policy_decision_id = binding.policy_decision_id",
            "policy.id = binding.policy_decision_id");
  }

  @Test
  void reviewTransitionCarriesTheExactPersistedCaseSequenceBoundary() {
    Instant committedAt = Instant.parse("2026-08-21T08:00:00Z");
    var sequenceAuthority =
        new JdbcTargetHearingFormalizationActivities.ReviewSequenceAuthority(15, 68);

    var transition = JdbcTargetHearingFormalizationActivities.reviewTransitionCommand(
        "CASE_REVIEW_SEQUENCE", "ROOM_REVIEW_SEQUENCE", committedAt, sequenceAuthority);

    assertThat(transition.caseId()).isEqualTo("CASE_REVIEW_SEQUENCE");
    assertThat(transition.expectedRoomType()).isEqualTo(RoomType.HEARING);
    assertThat(transition.nextRoomId()).isEqualTo("ROOM_REVIEW_SEQUENCE");
    assertThat(transition.nextRoomType()).isEqualTo(RoomType.REVIEW);
    assertThat(transition.macroPhase()).isEqualTo("REVIEW_OPEN");
    assertThat(transition.roomPhase()).isEqualTo("PROVISIONING");
    assertThat(transition.occurredAt().toInstant()).isEqualTo(committedAt);
    assertThat(transition.hasProjectionAuthority()).isFalse();
    assertThat(transition.hasSequenceAuthority()).isTrue();
    assertThat(transition.lastCommandSequence()).isEqualTo(15L);
    assertThat(transition.lastCaseEventSequence()).isEqualTo(68L);
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_TRANSITION_CASE_LOCK_SQL)
        .contains("from fulfillment_dispute_case", "where id = ?", "for update");
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_LAST_COMMAND_SEQUENCE_SQL)
        .contains(
            "tenant_surrogate = ? and case_id = ?",
            "order by case_command_sequence desc",
            "limit 1",
            "for update");
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_LAST_CASE_EVENT_SEQUENCE_SQL)
        .contains(
            "where case_id = ?", "order by sequence_no desc", "limit 1", "for update");
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_SEQUENCE_AUTHORITY_REPLAY_SQL)
        .contains(
            "current_room = 'REVIEW'",
            "process_revision = ?",
            "room_epoch = ?",
            "fencing_token = ?",
            "for update");
    assertThatThrownBy(() ->
        new JdbcTargetHearingFormalizationActivities.ReviewSequenceAuthority(0, 68))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
    assertThatThrownBy(() -> JdbcTargetHearingFormalizationActivities.reviewTransitionCommand(
        "CASE_REVIEW_SEQUENCE", "ROOM_REVIEW_SEQUENCE", committedAt, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("sequenceAuthority");
  }

  @Test
  void hearingTerminalTimeUsesTheExactPersistedMonotonicEpochBoundary() {
    assertThat(JdbcTargetHearingFormalizationActivities.HEARING_EPOCH_TIME_BOUNDARY_SQL)
        .contains(
            "epoch.id = ?",
            "epoch.tenant_surrogate = ?",
            "epoch.case_id = ?",
            "epoch.room_type = 'HEARING'",
            "epoch.room_epoch = ?",
            "epoch.process_revision = ?",
            "epoch.room_revision = ?",
            "epoch.fencing_token = ?",
            "epoch.writer_mode = 'TEMPORAL'",
            "epoch.lifecycle_status = 'ACTIVE'",
            "epoch.provisioning_status = 'READY'",
            "for update");

    Instant older = Instant.parse("2026-08-21T00:00:00Z");
    Instant newer = Instant.parse("2026-08-21T00:05:00Z");
    assertThat(JdbcTargetHearingFormalizationActivities.laterBoundary(newer, older))
        .isEqualTo(newer);
    assertThat(JdbcTargetHearingFormalizationActivities.laterBoundary(older, newer))
        .isEqualTo(newer);
    assertThat(JdbcTargetHearingFormalizationActivities.laterBoundary(newer, newer))
        .isEqualTo(newer);
    assertThatThrownBy(() ->
        JdbcTargetHearingFormalizationActivities.laterBoundary(null, newer))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("epochBoundary");
    assertThatThrownBy(() ->
        JdbcTargetHearingFormalizationActivities.laterBoundary(newer, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("handoffBoundary");
  }

  @Test
  void prepareAgentStageBindsExactRunningStageAndRollbackReplayStayFailClosed() throws Exception {
    String tenant = "tenant-agent-stage-binding";
    String caseId = "CASE_AGENT_STAGE_BINDING";
    String roomId = "ROOM_AGENT_STAGE_BINDING";
    String flowId = "FLOW_AGENT_STAGE_BINDING";
    String epochId = "EPOCH_AGENT_STAGE_BINDING";
    String stageId = "STAGE_AGENT_STAGE_BINDING_4";
    String logicalRunId = "target-hearing-run:agent-stage-binding";
    Instant now = Instant.parse("2026-08-16T04:00:00Z");
    HearingWorkflowStage stage = HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING;
    HearingRoomStart start = new HearingRoomStart(
        "hearing-room-start.v1", tenant, caseId, roomId, flowId, epochId,
        HearingWriterMode.TEMPORAL, 0, 3, "user-agent-stage", "merchant-agent-stage",
        now, now.plusSeconds(3_600), 300, 14, 6, "hearing-build-v1");
    TransitionRequest transition = new TransitionRequest(
        start, stage, stage.sequence(), 14, 6, 3,
        "hearing.agent:" + tenant + ':' + caseId + ":0:4:intake_questions");

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    TransactionStatus transactionStatus = mock(TransactionStatus.class);
    when(transactions.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(transactionStatus);
    });
    TargetHearingInternalStageMaterializer materializer =
        mock(TargetHearingInternalStageMaterializer.class);
    ExecuteAgentRunRequest execution = mock(ExecuteAgentRunRequest.class);
    when(execution.logicalRunId()).thenReturn(logicalRunId);
    when(materializer.materialize(transition)).thenReturn(execution);

    ResultSet row = mock(ResultSet.class);
    when(row.getString(1)).thenReturn(tenant);
    when(row.getString(2)).thenReturn(epochId);
    when(row.getLong(3)).thenReturn(0L);
    when(row.getLong(4)).thenReturn(14L);
    when(row.getLong(5)).thenReturn(6L);
    when(row.getLong(6)).thenReturn(3L);
    when(row.getString(7)).thenReturn(flowId);
    when(row.getString(8)).thenReturn(stage.name());
    when(row.getInt(9)).thenReturn(stage.sequence());
    when(row.getString(10)).thenReturn(stageId);
    when(row.getString(11)).thenReturn("{}");
    when(jdbc.query(
        contains("from hearing_temporal_projection"), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          RowMapper<Object> mapper = invocation.getArgument(1);
          return List.of(mapper.mapRow(row, 0));
        });

    AtomicReference<String> stageBinding = new AtomicReference<>();
    AtomicBoolean exactBindingTuple = new AtomicBoolean(true);
    AtomicReference<String> bindingSql = new AtomicReference<>();
    when(jdbc.update(contains("update hearing_flow_stage"), any(Object[].class)))
        .thenAnswer(invocation -> {
          bindingSql.set(invocation.getArgument(0));
          Object[] arguments = (Object[]) invocation.getRawArguments()[1];
          assertThat(arguments)
              .contains(stageId, flowId, caseId, stage.name(), stage.sequence(), tenant,
                  epochId, 0L, 14L, 6L, 3L);
          if (exactBindingTuple.get() && stageBinding.compareAndSet(null, (String) arguments[0])) {
            return 1;
          }
          return 0;
        });
    when(jdbc.queryForObject(
        contains("from hearing_flow_stage stage"), eq(Integer.class), any(Object[].class)))
        .thenAnswer(invocation -> {
          Object[] arguments = (Object[]) invocation.getRawArguments()[2];
          return exactBindingTuple.get()
                  && logicalRunId.equals(stageBinding.get())
                  && Arrays.asList(arguments).contains(logicalRunId)
              ? 1 : 0;
        });

    JdbcTargetHearingFormalizationActivities subject =
        new JdbcTargetHearingFormalizationActivities(
            mock(DataSource.class), transactions, mock(TargetHearingFormalCompletion.class),
            materializer, mock(HearingAuthorityLedger.class),
            new ObjectMapper().findAndRegisterModules());
    ReflectionTestUtils.setField(subject, "jdbc", jdbc);

    var first = subject.prepareAgentStage(transition);
    var replay = subject.prepareAgentStage(transition);

    assertThat(first.request()).isSameAs(execution);
    assertThat(replay).isEqualTo(first);
    assertThat(stageBinding).hasValue(logicalRunId);
    assertThat(bindingSql.get())
        .contains(
            "stage.agent_run_id is null",
            "stage.stage_status = 'RUNNING'",
            "stage.id = ?",
            "stage.flow_instance_id = ?",
            "stage.case_id = ?",
            "stage.stage_code = ?",
            "stage.stage_sequence = ?",
            "projection.tenant_surrogate = ?",
            "projection.epoch_id = ?",
            "projection.hearing_epoch = ?",
            "projection.process_revision = ?",
            "projection.room_revision = ?",
            "projection.fencing_token = ?",
            "epoch.writer_mode = 'TEMPORAL'")
        .doesNotContain(stage.name());
    assertThat(Arrays.stream(HearingWorkflowStage.values()).filter(HearingWorkflowStage::requiresAgentRun))
        .hasSize(7);
    verify(materializer, times(2)).materialize(transition);

    NamedParameterJdbcTemplate strictJdbc = mock(NamedParameterJdbcTemplate.class);
    when(strictJdbc.queryForObject(
        contains("from hearing_flow_stage"), any(MapSqlParameterSource.class), eq(Integer.class)))
        .thenAnswer(invocation -> {
          MapSqlParameterSource parameters = invocation.getArgument(1);
          return logicalRunId.equals(stageBinding.get())
                  && logicalRunId.equals(parameters.getValue("agentRunId"))
                  && stageId.equals(parameters.getValue("sourceStageId"))
                  && "RUNNING".equals(parameters.getValue("expectedSourceStatus"))
              ? 1 : 0;
        });
    JdbcHearingFormalFinalizer strictFinalizer = new JdbcHearingFormalFinalizer(
        strictJdbc, mock(HearingAuthorityLedger.class));
    HearingAuthorityExpectation authority = new HearingAuthorityExpectation(
        tenant, caseId, flowId, epochId, 0, HearingWriterMode.TEMPORAL,
        com.example.dispute.hearing.domain.HearingFlowStage.valueOf(stage.name()),
        stage.sequence(), 14, 6, 3);
    HearingFormalTransition sameStage = new HearingFormalTransition(
        stageId, authority.stage(), authority.stageSequence(), null,
        null, null, null, "hearing-control");
    Method strictSource = JdbcHearingFormalFinalizer.class.getDeclaredMethod(
        "requireSourceStage", HearingAuthorityExpectation.class,
        HearingFormalTransition.class, String.class, Instant.class);
    strictSource.setAccessible(true);
    assertDoesNotThrow(() -> strictSource.invoke(
        strictFinalizer, authority, sameStage, logicalRunId, now));

    stageBinding.set("target-hearing-run:foreign");
    assertThatThrownBy(() -> subject.prepareAgentStage(transition))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stage AgentRun binding");
    assertThat(stageBinding).hasValue("target-hearing-run:foreign");

    stageBinding.set(null);
    exactBindingTuple.set(false);
    assertThatThrownBy(() -> subject.prepareAgentStage(transition))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stage AgentRun binding");
    assertThat(stageBinding).hasValue(null);

    exactBindingTuple.set(true);
    TransitionRequest driftedAuthority = new TransitionRequest(
        start, stage, stage.sequence(), 15, 6, 3,
        "hearing.agent:" + tenant + ':' + caseId + ":0:4:drifted");
    assertThatThrownBy(() -> subject.prepareAgentStage(driftedAuthority))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fenced Hearing cursor drifted");
    assertThat(stageBinding).hasValue(null);

    when(materializer.materialize(transition))
        .thenThrow(new IllegalStateException("materialization failed"));
    assertThatThrownBy(() -> subject.prepareAgentStage(transition))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("materialization failed");
    assertThat(stageBinding).hasValue(null);
  }
}
