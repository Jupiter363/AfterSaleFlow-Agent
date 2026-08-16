package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingAuthorityLedger;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowSubmissionStatus;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.hearing.domain.HearingFormalRequestHash;
import com.example.dispute.hearing.domain.HearingFormalTransition;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.hearing.application.HearingPublicTranscriptPolicy;
import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.workflow.activity.hearing.HearingDomainReceiptAdapter;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.RoomEpochAllocation;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingCommittedReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Target CONTROL implementation of the Hearing formalization contract.
 *
 * <p>The class is intentionally not a component. Runtime registration is an explicit promotion
 * step; until then this is only a concrete, transactionally fenced Activity implementation.
 */
public final class JdbcTargetHearingFormalizationActivities
    implements TargetHearingFormalizationActivities {

  private static final String CONTROL_ACTOR = "hearing-control";
  private static final String TARGET_NO_EXTERNAL_EFFECT = "TARGET_NO_EXTERNAL_EFFECT";
  private static final String TARGET_ACTION_SCHEMA = "target-no-external-effect.v1";
  private static final String TARGET_REVIEW_PROMPT_VERSION = "hearing-flow.v2";
  private static final String TARGET_REVIEW_PROFILE_VERSION = "hearing-judge-v2";
  private static final String TARGET_REVIEW_RULESET_VERSION = "ruleset-current";
  private static final String TARGET_REVIEW_SKILL_VERSION = "dispute-default-v1";
  private static final HearingPublicTranscriptPolicy TRANSCRIPT_POLICY =
      new HearingPublicTranscriptPolicy();
  static final String REVIEW_TASK_INSERT_SQL = """
      insert into review_task (id, case_id, plan_id, packet_id, policy_decision_id, task_status,
        priority, assigned_reviewer_id, required_role, due_at, decision_json, created_by, updated_by)
      values (?, ?, ?, ?, ?, 'PENDING', 'HIGH', null, 'PLATFORM_REVIEWER', null, '{}'::jsonb, ?, ?)
      on conflict (id) do nothing
      """;
  static final String REVIEW_TASK_REPLAY_SQL = """
      select task.id from review_task task
      join approval_policy_decision policy
        on policy.id = task.policy_decision_id
       and policy.case_id = task.case_id
       and policy.plan_id = task.plan_id
      where task.id = ? and task.case_id = ? and task.plan_id = ? and task.packet_id = ?
        and task.task_status = 'PENDING' and policy.id = ? and policy.policy_version = ?
      for update of task, policy
      """;
  static final String REVIEW_EPOCH_TASK_SOURCE_SQL = """
      select task.id, task.plan_id, task.policy_decision_id
        from review_task task
        join approval_policy_decision policy
          on policy.id = task.policy_decision_id
         and policy.case_id = task.case_id
         and policy.plan_id = task.plan_id
       where task.id = ? and task.case_id = ?
       for update of task, policy
      """;
  static final String REVIEW_EPOCH_TASK_BINDING_INSERT_SQL = """
      insert into target_e2e_review_epoch_task_binding (
        epoch_id, tenant_surrogate, case_id, room_epoch, room_fencing_token,
        review_task_id, plan_id, policy_decision_id, source_handoff_id, created_by)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      on conflict (epoch_id) do nothing
      """;
  static final String REVIEW_EPOCH_TASK_BINDING_REPLAY_SQL = """
      select binding.epoch_id
        from target_e2e_review_epoch_task_binding binding
        join case_room_epoch epoch
          on epoch.id = binding.epoch_id
         and epoch.tenant_surrogate = binding.tenant_surrogate
         and epoch.case_id = binding.case_id
         and epoch.room_epoch = binding.room_epoch
         and epoch.fencing_token = binding.room_fencing_token
        join hearing_review_handoff_fact handoff
          on handoff.id = binding.source_handoff_id
         and handoff.case_id = binding.case_id
         and handoff.review_task_id = binding.review_task_id
        join review_task task
          on task.id = binding.review_task_id
         and task.case_id = binding.case_id
         and task.plan_id = binding.plan_id
         and task.policy_decision_id = binding.policy_decision_id
        join approval_policy_decision policy
          on policy.id = binding.policy_decision_id
         and policy.case_id = binding.case_id
         and policy.plan_id = binding.plan_id
       where binding.epoch_id = ? and binding.tenant_surrogate = ? and binding.case_id = ?
         and binding.room_epoch = ? and binding.room_fencing_token = ?
         and binding.review_task_id = ? and binding.plan_id = ?
         and binding.policy_decision_id = ? and binding.source_handoff_id = ?
       for update of binding, epoch, handoff, task, policy
      """;

  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final TargetHearingFormalCompletion completion;
  private final TargetHearingInternalStageMaterializer materializer;
  private final HearingAuthorityLedger ledger;
  private final ObjectMapper mapper;
  private final RoomEpochAllocator roomEpochAllocator;
  private final JdbcTargetHearingPreludeAuthority preludeAuthority;
  private final JdbcTargetHearingPublicTranscriptCommitter transcript;

  public JdbcTargetHearingFormalizationActivities(
      DataSource dataSource,
      TransactionTemplate transactions,
      TargetHearingFormalCompletion completion,
      TargetHearingInternalStageMaterializer materializer,
      HearingAuthorityLedger ledger,
      ObjectMapper mapper) {
    this(dataSource, transactions, completion, materializer, ledger, mapper, null, null);
  }

  /** The target registration must supply the allocator; the compatibility constructor cannot close. */
  public JdbcTargetHearingFormalizationActivities(
      DataSource dataSource,
      TransactionTemplate transactions,
      TargetHearingFormalCompletion completion,
      TargetHearingInternalStageMaterializer materializer,
      HearingAuthorityLedger ledger,
      ObjectMapper mapper,
      RoomEpochAllocator roomEpochAllocator) {
    this(
        dataSource,
        transactions,
        completion,
        materializer,
        ledger,
        mapper,
        roomEpochAllocator,
        null);
  }

  /** Runtime promotion supplies the strict transcript writer; compatibility tests remain isolated. */
  public JdbcTargetHearingFormalizationActivities(
      DataSource dataSource,
      TransactionTemplate transactions,
      TargetHearingFormalCompletion completion,
      TargetHearingInternalStageMaterializer materializer,
      HearingAuthorityLedger ledger,
      ObjectMapper mapper,
      RoomEpochAllocator roomEpochAllocator,
      JdbcTargetHearingPublicTranscriptCommitter transcript) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = new JdbcTemplate(this.dataSource);
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.completion = Objects.requireNonNull(completion, "completion");
    this.materializer = Objects.requireNonNull(materializer, "materializer");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.roomEpochAllocator = roomEpochAllocator;
    this.preludeAuthority = new JdbcTargetHearingPreludeAuthority(this.dataSource, this.mapper);
    this.transcript = transcript;
  }

  @Override
  public StageResult bootstrapNext(TransitionRequest request) {
    return required(() -> {
      HearingStageReceipt replay = replayStage(request, HearingAuthorityCommit.OperationType.STAGE);
      if (replay != null) {
        JdbcTargetHearingPreludeAuthority.Authority prelude =
            preludeAuthority.loadCommitted(request.start());
        commitTranscript(
            JdbcTargetHearingPublicTranscriptCommitter.CommitMode.STRICT_REPLAY,
            replay,
            request.start(),
            receiptCommittedAt(replay.committed()),
            TRANSCRIPT_POLICY.deterministicStage(
                HearingFlowStage.valueOf(request.expectedStage().name()),
                prelude.caseFactMatrix(),
                prelude.evidenceMatrix()));
        return new StageResult(replay);
      }
      Cursor cursor = lock(request);
      require(!request.expectedStage().requiresAgentRun() && !request.expectedStage().isPartyWait(),
          "bootstrap only admits deterministic stages");
      JdbcTargetHearingPreludeAuthority.Authority prelude =
          request.expectedStage() == HearingWorkflowStage.COURT_PREPARING
              ? preludeAuthority.freeze(request.start())
              : preludeAuthority.loadCommitted(request.start());
      String sourceOutput = request.expectedStage() == HearingWorkflowStage.COURT_PREPARING
          ? ContractJson.canonicalString(prelude.payload())
          : canonical(cursor.sourceOutputJson());
      HearingFormalTransition transition = advancing(request, cursor, sourceOutput);
      HearingAuthorityCommit commit = stageCommit(cursor.authority(), request.operationKey(), transition,
          sourceOutput);
      HearingStageReceipt receipt = completion.advanceStage(new HearingFormalFinalizer.StageCommand(
          commit, transition, sourceOutput,
          hashJson(sourceOutput), CONTROL_ACTOR));
      commitTranscript(
          JdbcTargetHearingPublicTranscriptCommitter.CommitMode.NEW_COMMIT,
          receipt,
          request.start(),
          commit.committedAt(),
          TRANSCRIPT_POLICY.deterministicStage(
              HearingFlowStage.valueOf(request.expectedStage().name()),
              prelude.caseFactMatrix(),
              prelude.evidenceMatrix()));
      return new StageResult(receipt);
    });
  }

  @Override
  public PartyResult formalizeParty(PartyRequest request) {
    return required(() -> {
      HearingPartyTerminalReceipt replay = replayParty(request);
      if (replay != null) {
        commitPartyAdvanceReplay(
            replay, request.transition().start(), request.transition().expectedStage());
        return new PartyResult(replay);
      }
      Cursor cursor = lock(request.transition());
      var command = request.command();
      require(command.commandType() == CommandType.HEARING_STATEMENT
              || command.commandType() == CommandType.HEARING_EVIDENCE_BATCH,
          "unsupported party command type");
      CaseCommandLock commandLock = lockCaseCommand(command);
      TimelinePartyEvent timeline = lockTimelinePartyEvent(cursor, command);
      PartyAction action = one(jdbc.query("""
          select id, action_type, schema_version, participant_id, participant_role,
                 submission_status, payload_json::text as payload_json, content_hash
            from hearing_flow_action
           where id = ? and flow_instance_id = ? and case_id = ? and stage_id = ?
             and participant_id = ? and content_hash = ? and agent_run_id is null
           for update
          """, (row, ignored) -> new PartyAction(row.getString(1), row.getString(2), row.getString(3),
          row.getString(4), row.getString(5), row.getString(6), row.getString(7), row.getString(8)),
          timeline.actionId(), cursor.flowId(), command.caseId(), cursor.sourceStageId(),
          command.actorRef().actorId(), timeline.actionContentHash()));
      HearingFlowActionType actionType = HearingFlowActionType.valueOf(action.actionType());
      requireExactPartySubmissionSchema(command.commandType(), actionType, action.schemaVersion(),
          timeline.actionSchemaVersion(), requiredText(parse(timeline.actionPayload()), "schema_version"));
      require(action.contentHash().equals(timeline.actionContentHash())
              && action.participantRole().equals(command.actorRef().actorRole().name())
              && canonical(action.payloadJson()).equals(timeline.actionPayload()),
          "timeline event and Hearing action disagree");
      require(action.payloadJson().equals(canonical(action.payloadJson())), "party payload is not canonical");
      HearingFormalTransition transition = partyTransition(request.transition(), cursor, action);
      HearingAuthorityCommit commit = partyCommit(
          cursor.authority(), request.transition().operationKey(), command.commandId(), action,
          transition, command.actorRef().actorId(), Instant.EPOCH);
      var receipt = completion.adoptParty(new HearingFormalFinalizer.AdoptPartyActionCommand(commit, transition,
          action.id(), actionType, action.schemaVersion(), action.participantId(), action.participantRole(),
          HearingFlowSubmissionStatus.valueOf(action.submissionStatus()), action.payloadJson(), action.contentHash(),
          command.commandId(), command.actorRef().actorId()));
      commitPartyAdvance(
          JdbcTargetHearingPublicTranscriptCommitter.CommitMode.NEW_COMMIT,
          receipt,
          request.transition().start(),
          request.transition().expectedStage(),
          commit.committedAt());
      int updated = jdbc.update("""
          update case_command set command_status = 'APPLIED', status_reason_code = null,
              result_uri = ?, result_sha256 = ?, applied_at = now(), updated_at = now(), version = version + 1
           where command_id = ? and tenant_surrogate = ? and case_id = ? and room_epoch = ?
             and request_hash = ? and payload_sha256 = ?
             and command_status in ('PENDING_ORCHESTRATION', 'ORCHESTRATION_ACCEPTED', 'APPLIED')
          """, "urn:hearing:receipt:" + receipt.committed().receiptId(), receipt.committed().receiptHash(),
          command.commandId(), command.tenantSurrogate(), command.caseId(), command.roomEpoch(),
          commandLock.requestHash(), commandLock.payloadSha256());
      require(updated == 1, "case command APPLIED CAS failed");
      return new PartyResult(receipt);
    });
  }

  @Override
  public TimeoutResult formalizeTimeout(TimeoutRequest request) {
    return required(() -> {
      HearingPartyTerminalReceipt replay = replayTimeout(request);
      if (replay != null) {
        commitPartyAdvanceReplay(
            replay,
            request.transition().start(),
            request.transition().expectedStage());
        return new TimeoutResult(replay, false);
      }
      Cursor cursor = lock(request.transition());
      require(cursor.authority().stage().hasSharedPartyDeadline(), "party timeout stage required");
      Instant deadline = expiredPartyDeadline(cursor);
      if (pendingSubmittedActionCount(cursor) > 0) {
        return new TimeoutResult(null, true);
      }
      String participantRole = timeoutParticipantRole(request);
      HearingFlowActionType actionType =
          cursor.authority().stage() == HearingFlowStage.PARTY_ANSWERS_OPEN
              ? HearingFlowActionType.ANSWER_BUNDLE
              : HearingFlowActionType.EVIDENCE_BATCH;
      PartyAction action = timeoutAction(
          request, cursor, actionType, participantRole, deadline);
      HearingFormalTransition transition = partyTransition(
          request.transition(), cursor, action, CONTROL_ACTOR);
      String timeoutRequestId = "AUTO_TIMEOUT";
      HearingAuthorityCommit commit = partyCommit(
          cursor.authority(), request.transition().operationKey(), timeoutRequestId, action,
          transition, CONTROL_ACTOR, deadline);
      var receipt = completion.adoptParty(new HearingFormalFinalizer.AdoptPartyActionCommand(
          commit, transition, action.id(), actionType, action.schemaVersion(),
          action.participantId(), action.participantRole(),
          HearingFlowSubmissionStatus.AUTO_TIMEOUT, action.payloadJson(), action.contentHash(),
          timeoutRequestId, CONTROL_ACTOR));
      commitPartyAdvance(
          JdbcTargetHearingPublicTranscriptCommitter.CommitMode.NEW_COMMIT,
          receipt,
          request.transition().start(),
          request.transition().expectedStage(),
          commit.committedAt());
      return new TimeoutResult(receipt, false);
    });
  }

  @Override
  public AgentStagePreparation prepareAgentStage(TransitionRequest request) {
    return required(() -> {
      Cursor cursor = lock(request);
      var prepared = materializer.materialize(request);
      bindAgentStage(request, cursor, prepared.logicalRunId());
      return new AgentStagePreparation(prepared);
    });
  }

  private void bindAgentStage(TransitionRequest request, Cursor cursor, String logicalRunId) {
    String exactLogicalRunId = requiredValue(logicalRunId, "logicalRunId");
    int bound = jdbc.update("""
        update hearing_flow_stage stage
           set agent_run_id = ?, updated_at = now(), updated_by = ?
          from hearing_temporal_projection projection
          join case_room_epoch epoch
            on epoch.id = projection.epoch_id
           and epoch.tenant_surrogate = projection.tenant_surrogate
           and epoch.case_id = projection.case_id
           and epoch.room_type = 'HEARING'
           and epoch.room_epoch = projection.hearing_epoch
           and epoch.fencing_token = projection.fencing_token
           and epoch.writer_mode = 'TEMPORAL'
         where stage.id = ?
           and stage.flow_instance_id = ?
           and stage.case_id = ?
           and stage.stage_code = ?
           and stage.stage_sequence = ?
           and stage.stage_status = 'RUNNING'
           and stage.agent_run_id is null
           and projection.flow_instance_id = stage.flow_instance_id
           and projection.case_id = stage.case_id
           and projection.tenant_surrogate = ?
           and projection.epoch_id = ?
           and projection.hearing_epoch = ?
           and projection.writer_mode = 'TEMPORAL'
           and projection.process_revision = ?
           and projection.room_revision = ?
           and projection.fencing_token = ?
        """, exactLogicalRunId, CONTROL_ACTOR,
        cursor.sourceStageId(), cursor.flowId(), cursor.authority().caseId(),
        request.expectedStage().name(), request.expectedStageSequence(),
        cursor.authority().tenantSurrogate(), cursor.authority().epochId(),
        cursor.authority().roomEpoch(), cursor.authority().processRevision(),
        cursor.authority().roomRevision(), cursor.authority().fencingToken());
    if (bound == 1) return;

    Integer replay = jdbc.queryForObject("""
        select count(*)
          from hearing_flow_stage stage
          join hearing_temporal_projection projection
            on projection.flow_instance_id = stage.flow_instance_id
           and projection.case_id = stage.case_id
          join case_room_epoch epoch
            on epoch.id = projection.epoch_id
           and epoch.tenant_surrogate = projection.tenant_surrogate
           and epoch.case_id = projection.case_id
           and epoch.room_type = 'HEARING'
           and epoch.room_epoch = projection.hearing_epoch
           and epoch.fencing_token = projection.fencing_token
           and epoch.writer_mode = 'TEMPORAL'
         where stage.id = ?
           and stage.flow_instance_id = ?
           and stage.case_id = ?
           and stage.stage_code = ?
           and stage.stage_sequence = ?
           and stage.stage_status = 'RUNNING'
           and stage.agent_run_id = ?
           and projection.tenant_surrogate = ?
           and projection.epoch_id = ?
           and projection.hearing_epoch = ?
           and projection.writer_mode = 'TEMPORAL'
           and projection.process_revision = ?
           and projection.room_revision = ?
           and projection.fencing_token = ?
        """, Integer.class,
        cursor.sourceStageId(), cursor.flowId(), cursor.authority().caseId(),
        request.expectedStage().name(), request.expectedStageSequence(), exactLogicalRunId,
        cursor.authority().tenantSurrogate(), cursor.authority().epochId(),
        cursor.authority().roomEpoch(), cursor.authority().processRevision(),
        cursor.authority().roomRevision(), cursor.authority().fencingToken());
    require(Integer.valueOf(1).equals(replay), "stage AgentRun binding drifted");
  }

  @Override
  public AgentStageResult finalizeAgentStage(AgentStageFinalizationRequest request) {
    return required(() -> {
      var graph = request.request().command();
      require(request.request().command().caseId().equals(request.transition().start().caseId())
              && graph.roomEpoch() == request.transition().start().roomEpoch()
              && graph.stageCode().equals(request.transition().expectedStage().name())
              && request.result().agentRunId().equals(request.request().agentRunId())
              && request.result().logicalRunId().equals(request.request().logicalRunId())
              && request.result().attemptNo() >= request.request().attemptNo()
              && request.result().attemptNo() <= request.request().attemptLimit(),
          "AgentRun finalization coordinates drifted");
      FinalizationFact outer = one(jdbc.query("""
          select receipt_id, receipt_hash, process_revision, stage_sequence, room_fencing_token
            from target_e2e_finalization_receipt
           where tenant_surrogate = ? and case_id = ? and room_type = 'HEARING' and room_epoch = ?
             and room_fencing_token = ? and process_revision = ? and stage_sequence = ?
             and logical_run_id = ? and attempt_id = ? and result_hash = ?
             and domain_commit_status = 'COMMITTED'
           for update
          """, (row, ignored) -> new FinalizationFact(row.getString(1), row.getString(2), row.getLong(3),
          row.getLong(4), row.getLong(5)), graph.tenantSurrogate(), graph.caseId(), graph.roomEpoch(),
          request.transition().expectedFencingToken(), request.transition().expectedProcessRevision(),
          request.transition().expectedStageSequence(), request.request().agentRunId(), request.result().attemptId(),
          request.result().resultHash()));
      String finalKey = one(jdbc.query("""
          select operation_key from hearing_domain_receipt
           where tenant_surrogate = ? and case_id = ? and flow_instance_id = ?
             and operation_type = 'FINALIZE' and source_stage = ? and source_stage_sequence = ?
             and source_process_revision = ? and source_room_revision = ?
           for update
          """, (row, ignored) -> row.getString(1), graph.tenantSurrogate(), graph.caseId(),
          request.transition().start().flowInstanceId(), request.transition().expectedStage().name(),
          request.transition().expectedStageSequence(), request.transition().expectedProcessRevision(),
          request.transition().expectedRoomRevision()));
      var finalized = ledger.findCommitted(graph.tenantSurrogate(), finalKey)
          .orElseThrow(() -> new IllegalStateException("durable target Hearing final receipt is absent"));
      require(outer.processRevision() == request.transition().expectedProcessRevision()
              && outer.stageSequence() == request.transition().expectedStageSequence()
              && outer.fencingToken() == request.transition().expectedFencingToken()
              && finalized.operationType() == HearingAuthorityCommit.OperationType.FINALIZE
              && finalized.stage().ordinal() == request.transition().expectedStage().ordinal() + 1,
          "outer finalizer and Hearing FINALIZE receipt are not exact");
      return new AgentStageResult(HearingDomainReceiptAdapter.stage(finalized));
    });
  }

  @Override
  public StageResult freezeDossier(TransitionRequest request) {
    return required(() -> {
      HearingStageReceipt replay = replayStage(request, HearingAuthorityCommit.OperationType.FINALIZE);
      if (replay != null) {
        commitTranscript(
            JdbcTargetHearingPublicTranscriptCommitter.CommitMode.STRICT_REPLAY,
            replay,
            request.start(),
            receiptCommittedAt(replay.committed()),
            TRANSCRIPT_POLICY.dossierFrozen());
        return new StageResult(replay);
      }
      Cursor cursor = lock(request);
      require(cursor.authority().stage() == HearingFlowStage.DOSSIER_FREEZING, "dossier stage required");
      JsonNode questions = actionPayload(cursor.flowId(), cursor.authority().caseId(), "QUESTION_SET");
      JsonNode requests = actionPayload(cursor.flowId(), cursor.authority().caseId(), "EVIDENCE_REQUEST_SET");
      JsonNode answers = actions(cursor.flowId(), cursor.authority().caseId(), "ANSWER_BUNDLE");
      JsonNode evidence = actions(cursor.flowId(), cursor.authority().caseId(), "EVIDENCE_BATCH");
      JsonNode caseMatrix = stageOutput(cursor.flowId(), cursor.authority().caseId(), "INTAKE_SYNTHESIZING");
      JsonNode evidenceMatrix = stageOutput(cursor.flowId(), cursor.authority().caseId(), "EVIDENCE_SYNTHESIZING");
      String dossierId = actionId("dossier", request.operationKey());
      Instant committedAt = request.start().openedAt();
      JsonNode policyRules = policyRules(cursor.authority().caseId(), committedAt);
      TargetHearingTrialDossier.Value dossier = TargetHearingTrialDossier.build(mapper, dossierId,
          cursor.authority().caseId(), committedAt, caseMatrix, evidenceMatrix, questions, requests,
          answers, evidence, policyRules);
      HearingFormalTransition transition = advancing(request, cursor, canonical(cursor.sourceOutputJson()));
      String requestHash = HearingFormalRequestHash.compute("DOSSIER", cursor.authority(), transition, dossierId,
          dossier.caseVersion(), dossier.caseHash(), dossier.evidenceVersion(), dossier.evidenceHash(),
          dossier.questionSetId(), dossier.requestSetId(), dossier.hash(), CONTROL_ACTOR);
      HearingAuthorityCommit commit = commit(cursor.authority(), HearingAuthorityCommit.OperationType.FINALIZE,
          request.operationKey(), requestHash, committedAt);
      HearingStageReceipt receipt = completion.freezeDossier(new HearingFormalFinalizer.DossierCommand(commit, transition,
          dossierId, dossier.caseVersion(), dossier.caseHash(), dossier.evidenceVersion(), dossier.evidenceHash(),
          dossier.questionSetId(), dossier.requestSetId(), dossier.json(), dossier.hash(), CONTROL_ACTOR));
      commitTranscript(
          JdbcTargetHearingPublicTranscriptCommitter.CommitMode.NEW_COMMIT,
          receipt,
          request.start(),
          commit.committedAt(),
          TRANSCRIPT_POLICY.dossierFrozen());
      return new StageResult(receipt);
    });
  }

  @Override
  public StageResult handoff(TransitionRequest request) {
    return required(() -> {
      HearingStageReceipt replay = replayStage(request, HearingAuthorityCommit.OperationType.HANDOFF);
      if (replay != null) return new StageResult(replay);
      Cursor cursor = lock(request);
      require(cursor.authority().stage() == HearingFlowStage.HUMAN_REVIEW_OPEN, "review handoff stage required");
      Parent dossier = parent("select id, content_hash from hearing_trial_dossier where case_id = ? and flow_instance_id = ? for update", cursor);
      Parent proposal = parent("select id, content_hash from hearing_flow_artifact where case_id = ? and flow_instance_id = ? and artifact_type = 'JUDGE_PROPOSAL' for update", cursor);
      Parent report = parent("select id, content_hash from hearing_flow_artifact where case_id = ? and flow_instance_id = ? and artifact_type = 'JURY_REVIEW_REPORT' for update", cursor);
      Parent draft = parent("select id, content_hash from hearing_flow_artifact where case_id = ? and flow_instance_id = ? and artifact_type = 'ADJUDICATION_DRAFT' for update", cursor);
      Parent review = ensureReviewProjection(cursor, dossier, proposal, report, draft, request.operationKey());
      Parent lockedReview = one(jdbc.query("""
          select task.id, task.packet_id from review_task task
          join review_packet packet on packet.id = task.packet_id and packet.case_id = task.case_id
          where task.id = ? and task.case_id = ? and task.task_status = 'PENDING'
            and packet.id = ? and packet.frozen = true and packet.packet_status = 'FROZEN'
          for update of task, packet""", (row, ignored) -> new Parent(row.getString(1), row.getString(2)),
          review.id(), cursor.authority().caseId(), review.hash()));
      HearingFormalTransition transition = sameStage(cursor);
      Instant committedAt = request.start().openedAt(); String handoffId = actionId("handoff", request.operationKey());
      String handoffHash = HearingFormalRequestHash.compute("HANDOFF_FACT", cursor.authority(), handoffId, dossier.id(), dossier.hash(),
          proposal.id(), proposal.hash(), report.id(), report.hash(), draft.id(), draft.hash(), lockedReview.id(), lockedReview.hash(), CONTROL_ACTOR, committedAt);
      String requestHash = HearingFormalRequestHash.compute("HANDOFF", cursor.authority(), transition, handoffId, dossier.id(), dossier.hash(),
          proposal.id(), proposal.hash(), report.id(), report.hash(), draft.id(), draft.hash(), lockedReview.id(), lockedReview.hash(), handoffHash, CONTROL_ACTOR);
      HearingAuthorityCommit commit = commit(cursor.authority(), HearingAuthorityCommit.OperationType.HANDOFF,
          request.operationKey(), requestHash, committedAt);
      return new StageResult(completion.commitHandoff(new HearingFormalFinalizer.HandoffCommand(commit, transition, handoffId,
          dossier.id(), dossier.hash(), proposal.id(), proposal.hash(), report.id(), report.hash(), draft.id(), draft.hash(),
          lockedReview.id(), lockedReview.hash(), handoffHash, CONTROL_ACTOR)));
    });
  }

  @Override
  public StageResult close(TransitionRequest request) {
    return required(() -> {
      HearingStageReceipt replay = replayStage(request, HearingAuthorityCommit.OperationType.CLOSE);
      if (replay != null) return new StageResult(replay);
      Cursor cursor = lock(request);
      require(cursor.authority().stage() == HearingFlowStage.HUMAN_REVIEW_OPEN, "review closure stage required");
      ClosureParent parent = one(jdbc.query("""
          select handoff.id, handoff.review_task_id, receipt.receipt_id, receipt.receipt_hash
          from hearing_review_handoff_fact handoff join hearing_domain_receipt receipt
            on receipt.result_ref = 'urn:hearing:handoff:' || handoff.id and receipt.operation_type = 'HANDOFF'
          where handoff.case_id = ? and handoff.flow_instance_id = ? for update of handoff, receipt""",
          (row, ignored) -> new ClosureParent(
              row.getString(1), row.getString(2), row.getString(3), row.getString(4)),
          cursor.authority().caseId(), cursor.flowId()));
      HearingFormalTransition transition = advancing(request, cursor, canonical(cursor.sourceOutputJson()));
      Instant committedAt = request.start().openedAt(); String closureId = actionId("close", request.operationKey());
      String closureHash = HearingFormalRequestHash.compute("CLOSURE_FACT", cursor.authority(), closureId, parent.handoffId(),
          parent.receiptId(), parent.receiptHash(), CONTROL_ACTOR, committedAt);
      String requestHash = HearingFormalRequestHash.compute("CLOSURE", cursor.authority(), transition, closureId,
          parent.handoffId(), parent.receiptId(), parent.receiptHash(), closureHash, CONTROL_ACTOR);
      HearingAuthorityCommit commit = commit(cursor.authority(), HearingAuthorityCommit.OperationType.CLOSE,
          "hearing.close:" + key(cursor.authority()) + parent.receiptHash(), requestHash, committedAt);
      HearingStageReceipt receipt = completion.commitClosure(new HearingFormalFinalizer.ClosureCommand(commit, transition, closureId,
          parent.handoffId(), parent.receiptId(), parent.receiptHash(), closureHash, CONTROL_ACTOR));
      transitionToReview(
          cursor, receipt, committedAt, parent.handoffId(), parent.reviewTaskId());
      return new StageResult(receipt);
    });
  }

  private void commitTranscript(
      JdbcTargetHearingPublicTranscriptCommitter.CommitMode mode,
      HearingStageReceipt receipt,
      com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart start,
      Instant committedAt,
      List<HearingPublicTranscriptPolicy.Draft> drafts) {
    if (transcript != null) {
      transcript.commit(mode, receipt, start, committedAt, drafts);
    }
  }

  private void commitPartyAdvanceReplay(
      HearingPartyTerminalReceipt receipt,
      com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart start,
      HearingWorkflowStage sourceStage) {
    commitPartyAdvance(
        JdbcTargetHearingPublicTranscriptCommitter.CommitMode.STRICT_REPLAY,
        receipt,
        start,
        sourceStage,
        receiptCommittedAt(receipt.committed()));
  }

  private void commitPartyAdvance(
      JdbcTargetHearingPublicTranscriptCommitter.CommitMode mode,
      HearingPartyTerminalReceipt receipt,
      com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart start,
      HearingWorkflowStage sourceStage,
      Instant committedAt) {
    HearingCommittedReceipt committed = receipt.committed();
    require(
        committed.sourceStage().name().equals(sourceStage.name()),
        "Hearing party transcript source stage drifted");
    if (committed.stage() == committed.sourceStage()) {
      return;
    }
    require(
        committed.sourceStage().next() == committed.stage(),
        "Hearing party transcript transition is not adjacent");
    if (transcript != null) {
      transcript.commit(
          mode,
          receipt,
          start,
          committedAt,
          TRANSCRIPT_POLICY.partyStageAdvanced(
              HearingFlowStage.valueOf(sourceStage.name())));
    }
  }

  private Instant receiptCommittedAt(HearingCommittedReceipt receipt) {
    return one(jdbc.query(
        """
        select committed_at
          from hearing_domain_receipt
         where receipt_id = ? and receipt_hash = ? and tenant_surrogate = ?
           and case_id = ? and flow_instance_id = ? and operation_key = ?
         for update
        """,
        (row, ignored) -> row.getObject(1, OffsetDateTime.class).toInstant(),
        receipt.receiptId(),
        receipt.receiptHash(),
        receipt.tenantSurrogate(),
        receipt.caseId(),
        receipt.flowInstanceId(),
        receipt.operationKey()));
  }

  private <T> T required(java.util.concurrent.Callable<T> action) {
    return Objects.requireNonNull(transactions.execute(ignored -> {
      try { return action.call(); } catch (RuntimeException failure) { throw failure; }
      catch (Exception failure) { throw new IllegalStateException("target Hearing activity failed", failure); }
    }));
  }

  /** Receipt-first retry path: no old source cursor is touched after a lost Activity response. */
  private HearingStageReceipt replayStage(TransitionRequest request, HearingAuthorityCommit.OperationType operation) {
    return ledger.findCommitted(request.start().tenantSurrogate(), request.operationKey()).map(receipt -> {
      require(receipt.operationType() == operation
          && receipt.caseId().equals(request.start().caseId())
          && receipt.flowInstanceId().equals(request.start().flowInstanceId())
          && receipt.roomEpoch() == request.start().roomEpoch()
          && receipt.fencingToken() == request.expectedFencingToken()
          && receipt.sourceStage().name().equals(request.expectedStage().name())
          && receipt.sourceStageSequence() == request.expectedStageSequence()
          && receipt.sourceProcessRevision() == request.expectedProcessRevision()
          && receipt.sourceRoomRevision() == request.expectedRoomRevision(), "Hearing replay receipt conflicts");
      return HearingDomainReceiptAdapter.stage(receipt);
    }).orElse(null);
  }
  private HearingPartyTerminalReceipt replayParty(PartyRequest request) {
    var command = request.command();
    return ledger.findCommitted(request.transition().start().tenantSurrogate(), request.transition().operationKey()).map(receipt -> {
      require(receipt.operationType() == HearingAuthorityCommit.OperationType.PARTY_TERMINAL
          && receipt.caseId().equals(command.caseId())
          && receipt.flowInstanceId().equals(request.transition().start().flowInstanceId())
          && receipt.roomEpoch() == command.roomEpoch()
          && receipt.fencingToken() == request.transition().expectedFencingToken()
          && receipt.sourceStage().name().equals(request.transition().expectedStage().name())
          && receipt.sourceStageSequence() == request.transition().expectedStageSequence()
          && receipt.sourceProcessRevision() == request.transition().expectedProcessRevision()
          && receipt.sourceRoomRevision() == request.transition().expectedRoomRevision(),
          "Hearing party replay receipt conflicts");
      return HearingDomainReceiptAdapter.party(receipt, command.commandId(), command.actorRef().actorId(),
          HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED);
    }).orElse(null);
  }

  private HearingPartyTerminalReceipt replayTimeout(TimeoutRequest request) {
    TransitionRequest transition = request.transition();
    return ledger.findCommitted(
        transition.start().tenantSurrogate(), transition.operationKey()).map(receipt -> {
      require(receipt.operationType() == HearingAuthorityCommit.OperationType.PARTY_TERMINAL
          && receipt.caseId().equals(transition.start().caseId())
          && receipt.flowInstanceId().equals(transition.start().flowInstanceId())
          && receipt.roomEpoch() == transition.start().roomEpoch()
          && receipt.fencingToken() == transition.expectedFencingToken()
          && receipt.sourceStage().name().equals(transition.expectedStage().name())
          && receipt.sourceStageSequence() == transition.expectedStageSequence()
          && receipt.sourceProcessRevision() == transition.expectedProcessRevision()
          && receipt.sourceRoomRevision() == transition.expectedRoomRevision(),
          "Hearing timeout replay receipt conflicts");
      return HearingDomainReceiptAdapter.party(
          receipt, "AUTO_TIMEOUT", request.participantId(),
          HearingPartyTerminalReceipt.TerminalStatus.AUTO_TIMEOUT);
    }).orElse(null);
  }

  private Cursor lock(TransitionRequest request) {
    Cursor cursor = one(jdbc.query("""
        select p.tenant_surrogate, p.epoch_id, p.hearing_epoch, p.process_revision, p.room_revision,
               p.fencing_token, f.id, f.current_stage, f.stage_sequence, s.id, s.output_json::text
          from hearing_temporal_projection p join case_room_epoch e on e.id = p.epoch_id
          join hearing_flow_instance f on f.id = p.flow_instance_id and f.case_id = p.case_id
          join hearing_flow_stage s on s.flow_instance_id = f.id and s.case_id = f.case_id
             and s.stage_code = f.current_stage and s.stage_sequence = f.stage_sequence
         where p.tenant_surrogate = ? and p.case_id = ? and p.hearing_epoch = ?
           and p.writer_mode = 'TEMPORAL' and e.writer_mode = 'TEMPORAL'
         for update of p, e, f, s
        """, (row, ignored) -> new Cursor(new HearingAuthorityExpectation(row.getString(1), request.start().caseId(),
        row.getString(7), row.getString(2), row.getLong(3), HearingWriterMode.TEMPORAL,
        HearingFlowStage.valueOf(row.getString(8)), row.getInt(9), row.getLong(4), row.getLong(5), row.getLong(6)),
        row.getString(7), row.getString(10), row.getString(11)), request.start().tenantSurrogate(), request.start().caseId(), request.start().roomEpoch()));
    require(cursor.authority().flowInstanceId().equals(request.start().flowInstanceId())
            && cursor.authority().epochId().equals(request.start().epochId())
            && cursor.authority().fencingToken() == request.expectedFencingToken()
            && cursor.authority().processRevision() == request.expectedProcessRevision()
            && cursor.authority().roomRevision() == request.expectedRoomRevision()
            && cursor.authority().stage().name().equals(request.expectedStage().name())
            && cursor.authority().stageSequence() == request.expectedStageSequence(), "fenced Hearing cursor drifted");
    return cursor;
  }

  private HearingFormalTransition advancing(TransitionRequest request, Cursor cursor, String sourceOutput) {
    return advancing(request, cursor, sourceOutput, CONTROL_ACTOR);
  }
  private HearingFormalTransition advancing(
      TransitionRequest request, Cursor cursor, String sourceOutput, String actorId) {
    HearingWorkflowStage result = Objects.requireNonNull(request.expectedStage().next(), "Hearing is already closed");
    return new HearingFormalTransition(cursor.sourceStageId(), HearingFlowStage.valueOf(result.name()), result.sequence(),
        result.isPartyWait() ? request.start().hearingDeadlineAt() : null, actionId("stage", request.operationKey() + ':' + result.name()),
        "{}", sourceOutput, actorId);
  }
  private HearingFormalTransition partyTransition(TransitionRequest request, Cursor cursor, PartyAction action) {
    return partyTransition(request, cursor, action, action.participantId());
  }
  private HearingFormalTransition partyTransition(
      TransitionRequest request, Cursor cursor, PartyAction action, String actorId) {
    int committed = committedTerminalCount(cursor);
    require(committed == 0 || committed == 1, "formal party terminal receipt count");
    if (committed == 0) return sameStage(cursor, actorId);
    return advancing(
        request, cursor, canonical(cursor.sourceOutputJson()), actorId);
  }
  private HearingFormalTransition sameStage(Cursor cursor) {
    return sameStage(cursor, CONTROL_ACTOR);
  }
  private HearingFormalTransition sameStage(Cursor cursor, String actorId) {
    return new HearingFormalTransition(cursor.sourceStageId(), cursor.authority().stage(), cursor.authority().stageSequence(),
        cursor.authority().stage().hasSharedPartyDeadline() ? requestDeadline(cursor) : null, null, null, null, actorId);
  }
  private Instant requestDeadline(Cursor cursor) { return one(jdbc.query("select shared_deadline_at from hearing_flow_instance where id = ? for update", (r, i) -> r.getObject(1, java.time.OffsetDateTime.class).toInstant(), cursor.flowId())); }
  private Instant expiredPartyDeadline(Cursor cursor) {
    return one(jdbc.query("""
        select shared_deadline_at from hearing_flow_instance
         where id = ? and case_id = ? and current_stage = ? and stage_sequence = ?
           and shared_deadline_at is not null and shared_deadline_at <= current_timestamp
         for update
        """, (row, ignored) -> row.getObject(1, OffsetDateTime.class).toInstant(),
        cursor.flowId(), cursor.authority().caseId(), cursor.authority().stage().name(),
        cursor.authority().stageSequence()));
  }
  private int pendingSubmittedActionCount(Cursor cursor) {
    Integer value = jdbc.queryForObject("""
        select count(*)
          from hearing_flow_action action
         where action.flow_instance_id = ? and action.stage_id = ? and action.case_id = ?
           and action.action_type = ? and action.submission_status = 'SUBMITTED'
           and not exists (
             select 1 from hearing_domain_receipt receipt
              where receipt.tenant_surrogate = ? and receipt.case_id = action.case_id
                and receipt.flow_instance_id = action.flow_instance_id
                and receipt.epoch_id = ? and receipt.room_epoch = ?
                and receipt.fencing_token = ? and receipt.writer_mode = 'TEMPORAL'
                and receipt.operation_type = 'PARTY_TERMINAL'
                and receipt.source_stage = ? and receipt.source_stage_sequence = ?
                and receipt.result_ref = 'urn:hearing:party-action:' || action.id)
        """, Integer.class, cursor.flowId(), cursor.sourceStageId(), cursor.authority().caseId(),
        cursor.authority().stage() == HearingFlowStage.PARTY_ANSWERS_OPEN
            ? HearingFlowActionType.ANSWER_BUNDLE.name()
            : HearingFlowActionType.EVIDENCE_BATCH.name(),
        cursor.authority().tenantSurrogate(), cursor.authority().epochId(),
        cursor.authority().roomEpoch(), cursor.authority().fencingToken(),
        cursor.authority().stage().name(), cursor.authority().stageSequence());
    int count = value == null ? 0 : value;
    require(count >= 0 && count <= 2, "pending submitted party action count");
    return count;
  }
  private String timeoutParticipantRole(TimeoutRequest request) {
    if (request.participantId().equals(request.transition().start().initiatorParticipantId())) {
      return "USER";
    }
    require(
        request.participantId().equals(request.transition().start().respondentParticipantId()),
        "timeout participant authority");
    return "MERCHANT";
  }
  private PartyAction timeoutAction(
      TimeoutRequest request,
      Cursor cursor,
      HearingFlowActionType actionType,
      String participantRole,
      Instant deadline) {
    List<PartyAction> existing = jdbc.query("""
        select action.id, action.action_type, action.schema_version, action.participant_id,
               action.participant_role, action.submission_status,
               action.payload_json::text, action.content_hash
          from hearing_flow_action action
         where action.flow_instance_id = ? and action.stage_id = ? and action.case_id = ?
           and action.action_type = ? and action.participant_id = ?
           and action.participant_role = ? and action.agent_run_id is null
         for update
        """, (row, ignored) -> new PartyAction(
            row.getString(1), row.getString(2), row.getString(3), row.getString(4),
            row.getString(5), row.getString(6), row.getString(7), row.getString(8)),
        cursor.flowId(), cursor.sourceStageId(), cursor.authority().caseId(), actionType.name(),
        request.participantId(), participantRole);
    require(existing.size() <= 1, "timeout party action cardinality");
    PartyAction action = existing.isEmpty()
        ? createTimeoutAction(
            request, cursor, actionType, participantRole, deadline)
        : existing.getFirst();
    require(action.actionType().equals(actionType.name())
        && action.participantId().equals(request.participantId())
        && action.participantRole().equals(participantRole)
        && "AUTO_TIMEOUT".equals(action.submissionStatus())
        && action.payloadJson().equals(canonical(action.payloadJson()))
        && action.contentHash().equals(hashJson(action.payloadJson()))
        && requiredText(parse(action.payloadJson()), "schema_version").equals(action.schemaVersion()),
        "timeout party action authority");
    return action;
  }
  private PartyAction createTimeoutAction(
      TimeoutRequest request,
      Cursor cursor,
      HearingFlowActionType actionType,
      String participantRole,
      Instant deadline) {
    String actionId = actionId("timeout", request.transition().operationKey());
    JsonNode parent = actionPayload(
        cursor.flowId(), cursor.authority().caseId(),
        actionType == HearingFlowActionType.ANSWER_BUNDLE
            ? HearingFlowActionType.QUESTION_SET.name()
            : HearingFlowActionType.EVIDENCE_REQUEST_SET.name());
    ObjectNode payload = timeoutPayload(
        mapper, cursor.authority().stage(), request.participantId(), participantRole,
        deadline, parent, actionId("batch", request.transition().operationKey()));
    String payloadJson = json(payload);
    String contentHash = hashJson(payloadJson);
    String schemaVersion = requiredText(payload, "schema_version");
    int inserted = jdbc.update("""
        insert into hearing_flow_action (
          id, flow_instance_id, stage_id, case_id, action_type, schema_version,
          participant_id, participant_role, submission_status, payload_json, content_hash,
          agent_run_id, created_at, created_by)
        values (?, ?, ?, ?, ?, ?, ?, ?, 'AUTO_TIMEOUT', cast(? as jsonb), ?, null, ?, ?)
        """, actionId, cursor.flowId(), cursor.sourceStageId(), cursor.authority().caseId(),
        actionType.name(), schemaVersion, request.participantId(), participantRole,
        payloadJson, contentHash, OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC), CONTROL_ACTOR);
    require(inserted == 1, "timeout party action insert");
    return new PartyAction(
        actionId, actionType.name(), schemaVersion, request.participantId(), participantRole,
        HearingFlowSubmissionStatus.AUTO_TIMEOUT.name(), payloadJson, contentHash);
  }
  static ObjectNode timeoutPayload(
      ObjectMapper mapper,
      HearingFlowStage stage,
      String participantId,
      String participantRole,
      Instant deadline,
      JsonNode parent,
      String batchId) {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("schema_version", stage == HearingFlowStage.PARTY_ANSWERS_OPEN
        ? "hearing_party_statement.v1" : "hearing_evidence_batch.v1");
    payload.put("participant_id", participantId);
    payload.put("participant_role", participantRole);
    payload.put("submission_status", HearingFlowSubmissionStatus.AUTO_TIMEOUT.name());
    payload.put("submitted_at", deadline.toString());
    if (stage == HearingFlowStage.PARTY_ANSWERS_OPEN) {
      String questionSetId = requiredText(parent, "question_set_id");
      payload.put("question_set_id", questionSetId);
      String issueSetId = parent.path("issue_set_id").asText(null);
      payload.put("issue_set_id",
          issueSetId == null || issueSetId.isBlank() ? questionSetId : issueSetId);
      payload.putNull("statement_text");
      payload.putArray("source_message_ids");
    } else if (stage == HearingFlowStage.PARTY_EVIDENCE_OPEN) {
      payload.put("request_set_id", requiredText(parent, "request_set_id"));
      payload.put("batch_id", requiredValue(batchId, "timeout batch id"));
      payload.putArray("request_ids");
      payload.putArray("evidence_ids");
      payload.put("batch_note", "");
    } else {
      throw new IllegalArgumentException("timeout payload requires a party-wait stage");
    }
    return payload;
  }
  private int committedTerminalCount(Cursor cursor) {
    Integer value = jdbc.queryForObject("""
        select count(*) from hearing_domain_receipt
         where tenant_surrogate = ? and case_id = ? and flow_instance_id = ? and epoch_id = ?
           and room_epoch = ? and writer_mode = 'TEMPORAL' and fencing_token = ?
           and operation_type = 'PARTY_TERMINAL' and source_stage = ?
           and source_stage_sequence = ?
        """, Integer.class, cursor.authority().tenantSurrogate(), cursor.authority().caseId(),
        cursor.flowId(), cursor.authority().epochId(), cursor.authority().roomEpoch(),
        cursor.authority().fencingToken(), cursor.authority().stage().name(),
        cursor.authority().stageSequence());
    return value == null ? 0 : value;
  }
  private HearingAuthorityCommit stageCommit(HearingAuthorityExpectation authority, String operationKey, HearingFormalTransition transition, String sourceOutput) {
    String hash = hashJson(sourceOutput); String requestHash = HearingFormalRequestHash.compute("STAGE", authority, transition, hash, CONTROL_ACTOR);
    return commit(authority, HearingAuthorityCommit.OperationType.STAGE, operationKey, requestHash, Instant.EPOCH);
  }
  private HearingAuthorityCommit partyCommit(
      HearingAuthorityExpectation authority,
      String operationKey,
      String requestId,
      PartyAction action,
      HearingFormalTransition transition,
      String actorId,
      Instant committedAt) {
    String requestHash = HearingFormalRequestHash.compute(
        "ADOPT_PARTY_ACTION", authority, transition, action.id(),
        HearingFlowActionType.valueOf(action.actionType()), action.schemaVersion(),
        action.participantId(), action.participantRole(),
        HearingFlowSubmissionStatus.valueOf(action.submissionStatus()), action.contentHash(),
        requestId, actorId);
    return commit(
        authority, HearingAuthorityCommit.OperationType.PARTY_TERMINAL,
        operationKey, requestHash, committedAt);
  }
  private static HearingAuthorityCommit commit(HearingAuthorityExpectation authority, HearingAuthorityCommit.OperationType type, String operationKey, String requestHash, Instant committedAt) { return new HearingAuthorityCommit(HearingAuthorityCommit.SCHEMA_VERSION, authority, type, operationKey, requestHash, null, committedAt); }
  private CaseCommandLock lockCaseCommand(com.example.dispute.workflow.contract.v1.CaseCommandRef command) { return one(jdbc.query("""
      select command_id, request_hash, payload_uri, payload_sha256, payload_size_bytes
        from case_command where command_id = ? and tenant_surrogate = ? and case_id = ? and room_epoch = ?
          and request_hash = ? and payload_uri = ? and payload_sha256 = ? and payload_size_bytes = ?
          and command_status in ('PENDING_ORCHESTRATION', 'ORCHESTRATION_ACCEPTED', 'APPLIED')
        for update
      """, (r, i) -> new CaseCommandLock(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5)),
      command.commandId(), command.tenantSurrogate(), command.caseId(), command.roomEpoch(), command.requestHash(),
      command.payloadRef().uri(), command.payloadRef().sha256(), command.payloadRef().sizeBytes())); }
  private TimelinePartyEvent lockTimelinePartyEvent(Cursor cursor, com.example.dispute.workflow.contract.v1.CaseCommandRef command) {
    String eventType = command.commandType() == CommandType.HEARING_STATEMENT ? "HEARING_ANSWER_BUNDLE_SUBMITTED" : "HEARING_EVIDENCE_BATCH_SUBMITTED";
    String prefix = "urn:case-timeline-event:";
    require(command.payloadRef().schemaVersion().equals("case-timeline-event.v1")
        && command.payloadRef().uri().startsWith(prefix), "party timeline payload reference");
    String eventId = command.payloadRef().uri().substring(prefix.length());
    require(!eventId.isBlank() && eventId.indexOf('/') < 0, "party timeline event id");
    return one(jdbc.query("""
        select event_key, event_json::text from case_timeline_event
         where id = ? and case_id = ? and event_type = ?
         for update
        """, (r, i) -> timelineEvent(r.getString(1), r.getString(2), command, cursor), eventId,
        command.caseId(), eventType));
  }
  private TimelinePartyEvent timelineEvent(String key, String json, com.example.dispute.workflow.contract.v1.CaseCommandRef command, Cursor cursor) {
    String canonical = canonical(json);
    require(command.payloadRef().sha256().equals(hashJson(canonical))
        && command.payloadRef().sizeBytes() == canonical.getBytes(StandardCharsets.UTF_8).length,
        "party timeline payload bytes");
    JsonNode value = parse(canonical); String actionId = requiredText(value, "action_id");
    require(key.equals("hearing-party-submission:" + actionId)
        && cursor.authority().stage().name().equals(requiredText(value, "stage_code"))
        && command.actorRef().actorId().equals(requiredText(value, "participant_id"))
        && command.actorRef().actorRole().name().equals(requiredText(value, "participant_role")),
        "timeline party event binding");
    JsonNode payload = requiredObject(value, "action_payload");
    return new TimelinePartyEvent(actionId, requiredText(value, "action_schema_version"),
        requiredText(value, "action_content_hash"), ContractJson.canonicalString(payload));
  }
  private JsonNode actionPayload(String flowId, String caseId, String type) { return parse(one(jdbc.query("select payload_json::text from hearing_flow_action where flow_instance_id = ? and case_id = ? and action_type = ? for update", (r,i)->r.getString(1), flowId, caseId, type))); }
  private JsonNode actions(String flowId, String caseId, String type) { var array = mapper.createArrayNode(); jdbc.query("select payload_json::text from hearing_flow_action where flow_instance_id = ? and case_id = ? and action_type = ? order by participant_id for update", (RowCallbackHandler) r -> array.add(parse(r.getString(1))), flowId, caseId, type); require(array.size() == 2, "two party bundles required"); return array; }
  private JsonNode stageOutput(String flowId, String caseId, String stage) { return parse(one(jdbc.query("select output_json::text from hearing_flow_stage where flow_instance_id = ? and case_id = ? and stage_code = ? and stage_status = 'COMPLETED' for update", (r,i)->r.getString(1), flowId, caseId, stage))); }
  /** Snapshots the applicable Java Domain policy rows; Graph/bootstrap input is never policy authority. */
  private JsonNode policyRules(String caseId, Instant frozenAt) {
    var values = mapper.createArrayNode();
    jdbc.query("""
        select id, rule_code, rule_version, rule_name, rule_scope, rule_status, effective_from,
               effective_to, priority, condition_json::text, outcome_json::text, source_document_json::text
          from policy_rule
         where rule_status = 'ACTIVE' and deleted_at is null and effective_from <= ?
           and (effective_to is null or effective_to > ?)
         order by rule_code, rule_version
         for update
        """, (RowCallbackHandler) row -> {
      ObjectNode value = values.addObject(); value.put("policy_id", row.getString(1));
      value.put("rule_code", row.getString(2)); value.put("rule_version", row.getInt(3));
      value.put("rule_name", row.getString(4)); value.put("rule_scope", row.getString(5));
      value.put("rule_status", row.getString(6)); value.put("effective_from", row.getObject(7, java.time.OffsetDateTime.class).toInstant().toString());
      var effectiveTo = row.getObject(8, java.time.OffsetDateTime.class);
      if (effectiveTo == null) value.putNull("effective_to"); else value.put("effective_to", effectiveTo.toInstant().toString());
      value.put("priority", row.getInt(9)); value.set("conditions", parse(row.getString(10)));
      value.set("outcome", parse(row.getString(11))); value.set("source_document", parse(row.getString(12)));
    }, java.sql.Timestamp.from(frozenAt), java.sql.Timestamp.from(frozenAt));
    require(!values.isEmpty(), "applicable Java Domain policy rules"); return values;
  }
  private Parent ensureReviewProjection(Cursor cursor, Parent dossier, Parent proposal, Parent report,
      Parent formalDraft, String operationKey) {
    String draftId = actionId("review-draft", operationKey);
    String remedyId = actionId("review-remedy", operationKey);
    String packetId = actionId("review-packet", operationKey);
    String taskId = actionId("review-task", operationKey);
    ReviewPacketAuthority authority = one(jdbc.query("""
        select dispute.title, dispute.description, dispute.hearing_route, dispute.risk_level,
               dispute.version, frozen.case_matrix_version, frozen.case_matrix_hash,
               frozen.evidence_matrix_hash, frozen.payload_json::text, draft.payload_json::text,
               proposal.agent_run_id, report.agent_run_id, draft.agent_run_id
          from hearing_trial_dossier frozen
          join fulfillment_dispute_case dispute
            on dispute.id = frozen.case_id
          join hearing_flow_artifact proposal
            on proposal.id = ?
           and proposal.case_id = frozen.case_id
           and proposal.flow_instance_id = frozen.flow_instance_id
           and proposal.trial_dossier_id = frozen.id
           and proposal.trial_dossier_hash = frozen.content_hash
           and proposal.artifact_type = 'JUDGE_PROPOSAL'
           and proposal.schema_version = 'judge_proposal.v1'
           and proposal.content_hash = ?
          join hearing_flow_artifact report
            on report.id = ?
           and report.case_id = frozen.case_id
           and report.flow_instance_id = frozen.flow_instance_id
           and report.trial_dossier_id = frozen.id
           and report.trial_dossier_hash = frozen.content_hash
           and report.artifact_type = 'JURY_REVIEW_REPORT'
           and report.schema_version = 'jury_review_report.v1'
           and report.proposal_id = proposal.id
           and report.proposal_content_hash = proposal.content_hash
           and report.content_hash = ?
          join hearing_flow_artifact draft
            on draft.id = ?
           and draft.case_id = frozen.case_id
           and draft.flow_instance_id = frozen.flow_instance_id
           and draft.trial_dossier_id = frozen.id
           and draft.trial_dossier_hash = frozen.content_hash
           and draft.artifact_type = 'ADJUDICATION_DRAFT'
           and draft.schema_version = 'adjudication_draft.v2'
           and draft.proposal_id = proposal.id
           and draft.proposal_content_hash = proposal.content_hash
           and draft.report_id = report.id
           and draft.report_content_hash = report.content_hash
           and draft.content_hash = ?
          join hearing_flow_stage proposal_stage
            on proposal_stage.flow_instance_id = frozen.flow_instance_id
           and proposal_stage.case_id = frozen.case_id
           and proposal_stage.stage_code = 'JUDGE_V1_GENERATING'
           and proposal_stage.processor_role = 'PRESIDING_JUDGE'
           and proposal_stage.stage_status = 'COMPLETED'
           and proposal_stage.output_json = proposal.payload_json
           and proposal_stage.agent_run_id = proposal.agent_run_id
          join hearing_flow_stage report_stage
            on report_stage.flow_instance_id = frozen.flow_instance_id
           and report_stage.case_id = frozen.case_id
           and report_stage.stage_code = 'JURY_REVIEWING'
           and report_stage.processor_role = 'JURY_PANEL'
           and report_stage.stage_status = 'COMPLETED'
           and report_stage.output_json = report.payload_json
           and report_stage.agent_run_id = report.agent_run_id
          join hearing_flow_stage draft_stage
            on draft_stage.flow_instance_id = frozen.flow_instance_id
           and draft_stage.case_id = frozen.case_id
           and draft_stage.stage_code = 'JUDGE_V2_GENERATING'
           and draft_stage.processor_role = 'PRESIDING_JUDGE'
           and draft_stage.stage_status = 'COMPLETED'
           and draft_stage.output_json = draft.payload_json
           and draft_stage.agent_run_id = draft.agent_run_id
          join agent_run proposal_run
            on proposal_run.id = proposal.agent_run_id
           and proposal_run.case_id = frozen.case_id
           and proposal_run.agent_role = 'PRESIDING_JUDGE'
           and proposal_run.run_status = 'COMPLETED'
           and proposal_run.completed_at is not null
          join agent_run report_run
            on report_run.id = report.agent_run_id
           and report_run.case_id = frozen.case_id
           and report_run.agent_role = 'JURY_PANEL'
           and report_run.run_status = 'COMPLETED'
           and report_run.completed_at is not null
          join agent_run draft_run
            on draft_run.id = draft.agent_run_id
           and draft_run.case_id = frozen.case_id
           and draft_run.agent_role = 'PRESIDING_JUDGE'
           and draft_run.run_status = 'COMPLETED'
           and draft_run.completed_at is not null
         where frozen.id = ?
           and frozen.case_id = ?
           and frozen.flow_instance_id = ?
           and frozen.schema_version = 'trial_dossier.v1'
           and frozen.content_hash = ?
         for update of dispute, frozen, proposal, report, draft, proposal_stage, report_stage,
                       draft_stage, proposal_run, report_run, draft_run
        """, (r, i) -> new ReviewPacketAuthority(
            r.getString(1), r.getString(2), r.getString(3), r.getString(4),
            r.getLong(5), r.getInt(6), r.getString(7), r.getString(8),
            r.getString(9), r.getString(10), r.getString(11), r.getString(12),
            r.getString(13)),
        proposal.id(), proposal.hash(), report.id(), report.hash(), formalDraft.id(),
        formalDraft.hash(), dossier.id(), cursor.authority().caseId(), cursor.flowId(), dossier.hash()));
    require(!authority.judgeV1RunId().equals(authority.juryRunId())
            && !authority.judgeV1RunId().equals(authority.judgeV2RunId())
            && !authority.juryRunId().equals(authority.judgeV2RunId()),
        "Target Hearing Review manifest requires three distinct completed AgentRuns");
    JsonNode frozenDossier = parse(authority.dossierJson());
    require(requiredText(frozenDossier, "trial_dossier_id").equals(dossier.id())
            && requiredText(frozenDossier, "content_hash").equals(dossier.hash()),
        "Target Hearing Review dossier binding");
    JsonNode claims = requiredNonEmptyObject(frozenDossier, "case_fact_matrix");
    require(requiredText(claims, "content_hash").equals(authority.caseMatrixHash())
            && requiredNonEmptyArray(claims, "fact_rows").size() > 0,
        "Target Hearing Review claims authority");
    JsonNode evidenceMatrix = requiredNonEmptyObject(frozenDossier, "fact_evidence_matrix");
    require(requiredText(evidenceMatrix, "content_hash").equals(authority.evidenceMatrixHash()),
        "Target Hearing Review evidence matrix authority");
    JsonNode source = parse(authority.draftJson());
    require("adjudication_draft.v2".equals(requiredText(source, "schema_version"))
            && requiredText(source, "draft_id").equals(formalDraft.id())
            && requiredText(source, "content_hash").equals(formalDraft.hash()),
        "Target Hearing Review draft binding");
    JsonNode decision = requiredObject(source, "draft");
    JsonNode issues = requiredNonEmptyArray(decision, "fact_findings");
    JsonNode riskFlags = requiredNonEmptyArray(decision, "reviewer_attention");
    ObjectNode caseSummary = mapper.createObjectNode();
    caseSummary.put("case_id", cursor.authority().caseId());
    caseSummary.put("title", requiredValue(authority.title(), "case title"));
    caseSummary.put("description", requiredValue(authority.description(), "case description"));
    caseSummary.put("route_type", requiredValue(authority.routeType(), "case route"));
    caseSummary.put("risk_level", requiredValue(authority.riskLevel(), "case risk"));
    caseSummary.put("trial_dossier_id", dossier.id());
    caseSummary.put("trial_dossier_hash", dossier.hash());
    String caseSummaryJson = json(caseSummary);
    String claimsJson = json(claims);
    String issuesJson = json(issues);
    String evidenceMatrixJson = json(evidenceMatrix);
    String riskFlagsJson = json(riskFlags);
    String agentRunRefsJson = json(mapper.createArrayNode()
        .add(authority.judgeV1RunId()).add(authority.juryRunId()).add(authority.judgeV2RunId()));
    jdbc.update("""
        insert into adjudication_draft (id, case_id, hearing_state_id, draft_version, fact_findings_json,
          evidence_assessment_json, policy_application_json, reviewer_attention_json, recommended_decision,
          confidence, draft_text, created_by_agent, draft_status, non_final, created_by_agent_run_id,
          created_by, updated_by)
        values (?, ?, null, 1, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
          ?, ?, ?, ?, 'PENDING_HUMAN_REVIEW', true, null, ?, ?)
        on conflict (case_id, draft_version) do nothing
        """, draftId, cursor.authority().caseId(), json(decision.path("fact_findings")),
        json(decision.path("evidence_assessment")), json(decision.path("policy_application")),
        json(decision.path("reviewer_attention")), requiredText(decision, "recommended_decision"),
        decision.path("confidence").decimalValue(), requiredText(decision, "draft_text"), CONTROL_ACTOR,
        CONTROL_ACTOR, CONTROL_ACTOR);
    Parent authoritativeDraft = one(jdbc.query("""
        select id, content_hash from (
          select d.id, ? as content_hash from adjudication_draft d where d.id = ? and d.case_id = ? and d.draft_version = 1
        ) value for update
        """, (r, i) -> new Parent(r.getString(1), r.getString(2)), formalDraft.hash(), draftId, cursor.authority().caseId()));
    String targetActionIdempotencyKey = "target-no-external-effect:" + ContractJson.sha256Hex(
        mapper.valueToTree(List.of(cursor.authority().caseId(), remedyId))).substring(0, 32);
    ObjectNode targetAction = mapper.createObjectNode();
    targetAction.put("action_type", TARGET_NO_EXTERNAL_EFFECT);
    targetAction.put("effect_class", "NO_EXTERNAL_EFFECT");
    targetAction.put("idempotency_key", targetActionIdempotencyKey);
    targetAction.put("schema_version", TARGET_ACTION_SCHEMA);
    String targetActionsJson = ContractJson.canonicalString(mapper.createArrayNode().add(targetAction));
    jdbc.update("""
        insert into remedy_plan (id, case_id, adjudication_draft_id, plan_version, source_route, plan_status,
          risk_level, total_amount, currency, actions_json, preconditions_json, notification_plan_json,
          requires_human_review, created_by, updated_by)
        values (?, ?, ?, 1, 'HEARING_V2', 'PENDING_HUMAN_REVIEW', 'MEDIUM', 0, 'CNY', cast(? as jsonb),
           '[]'::jsonb, '[]'::jsonb, true, ?, ?)
        on conflict (case_id, plan_version) do nothing
        """, remedyId, cursor.authority().caseId(), authoritativeDraft.id(), targetActionsJson,
        CONTROL_ACTOR, CONTROL_ACTOR);
    String planId = one(jdbc.query("select id from remedy_plan where id = ? and case_id = ? and adjudication_draft_id = ? and plan_version = 1 for update",
        (r, i) -> r.getString(1), remedyId, cursor.authority().caseId(), authoritativeDraft.id()));
    RemedyProjection plan = one(jdbc.query("""
        select id, plan_version, actions_json::text, preconditions_json::text, notification_plan_json::text
          from remedy_plan where id = ? for update
        """, (r, i) -> new RemedyProjection(r.getString(1), r.getInt(2), r.getString(3), r.getString(4), r.getString(5)), planId));
    JsonNode remedyActions = parse(plan.actionsJson());
    JsonNode remedyNotifications = parse(plan.notificationsJson());
    ObjectNode frozenRemedy = mapper.createObjectNode(); frozenRemedy.put("id", plan.id());
    frozenRemedy.put("version", plan.version()); frozenRemedy.set("actions", remedyActions);
    frozenRemedy.set("preconditions", parse(plan.preconditionsJson()));
    frozenRemedy.set("notifications", remedyNotifications);
    String frozenRemedyJson = ContractJson.canonicalString(frozenRemedy);
    String frozenActionHash = ActionSnapshotHasher.hash(mapper, frozenRemedy);
    String policyVersion = one(jdbc.query("""
        select rule_code || ':' || rule_version from policy_rule
         where rule_status = 'ACTIVE' and deleted_at is null
         order by priority desc, rule_code, rule_version limit 1 for update
        """, (r, i) -> r.getString(1)));
    String policyDecisionId = actionId("review-policy", operationKey);
    jdbc.update("""
        insert into approval_policy_decision (id, case_id, plan_id, policy_version, risk_level,
          required_reviewer_role, required_review_count, allowed_actions_json, forbidden_actions_json,
          escalation_reason, auto_approve, created_by)
        values (?, ?, ?, ?, 'MEDIUM', 'PLATFORM_REVIEWER', 1, cast(? as jsonb), '[]'::jsonb,
          'Hearing V2 formal draft requires human review', false, ?)
        on conflict (id) do nothing
        """, policyDecisionId, cursor.authority().caseId(), planId, policyVersion,
        json(mapper.createArrayNode().add(TARGET_NO_EXTERNAL_EFFECT)), CONTROL_ACTOR);
    require(one(jdbc.query("select policy_version from approval_policy_decision where id = ? and case_id = ? and plan_id = ? for update",
        (r, i) -> r.getString(1), policyDecisionId, cursor.authority().caseId(), planId)).equals(policyVersion),
        "review policy decision replay");
    jdbc.update("""
        insert into review_packet (id, case_id, plan_id, packet_version, case_summary_json, claims_json,
          issues_json, evidence_matrix_json, draft_json, remedy_json, risk_flags_json, packet_status,
          case_version, dossier_version, issue_version, adjudication_draft_version, deliberation_report_version,
          remedy_plan_version, ruleset_version, prompt_version, skill_version, profile_version, action_hash,
          frozen, frozen_at, expires_at, agent_run_refs_json, created_by, updated_by)
        values (?, ?, ?, 1, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
          cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), 'FROZEN', ?, ?, 1, 2, 1, 1,
          ?, ?, ?, ?, ?, true, now(), now() + interval '7 days', cast(? as jsonb), ?, ?)
        on conflict (case_id, plan_id, packet_version) do nothing
        """, packetId, cursor.authority().caseId(), planId, caseSummaryJson, claimsJson, issuesJson,
        evidenceMatrixJson, json(source), frozenRemedyJson, riskFlagsJson,
        Math.max(1L, authority.caseVersion()), authority.dossierVersion(),
        TARGET_REVIEW_RULESET_VERSION, TARGET_REVIEW_PROMPT_VERSION, TARGET_REVIEW_SKILL_VERSION,
        TARGET_REVIEW_PROFILE_VERSION, frozenActionHash, agentRunRefsJson, CONTROL_ACTOR, CONTROL_ACTOR);
    String persistedPacketId = one(jdbc.query("""
        select id from review_packet
         where id = ? and case_id = ? and plan_id = ? and packet_version = 1
           and case_summary_json = cast(? as jsonb)
           and claims_json = cast(? as jsonb)
           and issues_json = cast(? as jsonb)
           and evidence_matrix_json = cast(? as jsonb)
           and draft_json = cast(? as jsonb)
           and remedy_json = cast(? as jsonb)
           and risk_flags_json = cast(? as jsonb)
           and packet_status = 'FROZEN'
           and case_version = ? and dossier_version = ? and issue_version = 1
           and adjudication_draft_version = 2 and deliberation_report_version = 1
           and remedy_plan_version = 1 and ruleset_version = ? and prompt_version = ?
           and skill_version = ? and profile_version = ? and action_hash = ?
           and frozen = true and agent_run_refs_json = cast(? as jsonb)
         for update
        """, (r, i) -> r.getString(1), packetId, cursor.authority().caseId(), planId,
        caseSummaryJson, claimsJson, issuesJson, evidenceMatrixJson, json(source), frozenRemedyJson,
        riskFlagsJson, Math.max(1L, authority.caseVersion()), authority.dossierVersion(),
        TARGET_REVIEW_RULESET_VERSION, TARGET_REVIEW_PROMPT_VERSION, TARGET_REVIEW_SKILL_VERSION,
        TARGET_REVIEW_PROFILE_VERSION, frozenActionHash, agentRunRefsJson));
    jdbc.update(REVIEW_TASK_INSERT_SQL, taskId, cursor.authority().caseId(), planId,
        persistedPacketId, policyDecisionId, CONTROL_ACTOR, CONTROL_ACTOR);
    String persistedTaskId = one(jdbc.query(REVIEW_TASK_REPLAY_SQL,
        (r, i) -> r.getString(1), taskId, cursor.authority().caseId(), planId,
        persistedPacketId, policyDecisionId, policyVersion));
    int waiting = jdbc.update("""
        update fulfillment_dispute_case set case_status = 'WAITING_HUMAN_REVIEW', updated_at = now()
         where id = ? and case_status not in ('CLOSED', 'CANCELLED')
        """, cursor.authority().caseId());
    require(waiting == 1, "case human-review state");
    return new Parent(persistedTaskId, persistedPacketId);
  }
  private void transitionToReview(
      Cursor cursor,
      HearingStageReceipt receipt,
      Instant committedAt,
      String handoffId,
      String reviewTaskId) {
    if (roomEpochAllocator == null) throw new IllegalStateException("target Hearing close requires RoomEpochAllocator wiring");
    String reviewRoomId = actionId("review-room", handoffId);
    jdbc.update("""
        insert into case_room (id, case_id, room_type, room_status, opened_at, metadata_json, created_by, updated_by)
        values (?, ?, 'REVIEW', 'OPEN', ?, '{}'::jsonb, ?, ?) on conflict (case_id, room_type) do nothing
        """, reviewRoomId, cursor.authority().caseId(), java.sql.Timestamp.from(committedAt), CONTROL_ACTOR, CONTROL_ACTOR);
    String persistedRoomId = one(jdbc.query("select id from case_room where case_id = ? and room_type = 'REVIEW' for update",
        (r, i) -> r.getString(1), cursor.authority().caseId()));
    RoomEpochAllocation allocation = roomEpochAllocator.transition(new TransitionRoomEpoch(cursor.authority().caseId(),
        RoomType.HEARING, persistedRoomId, RoomType.REVIEW, "REVIEW_OPEN", "PROVISIONING", null,
        OffsetDateTime.ofInstant(committedAt, ZoneOffset.UTC)));
    require(allocation.roomType() == RoomType.REVIEW && allocation.writerMode() == WriterMode.TEMPORAL
        && allocation.caseId().equals(cursor.authority().caseId()) && allocation.fencingToken() > 0
        && allocation.processRevision() == receipt.committed().processRevision(), "Review epoch allocation");
    Integer bindings = jdbc.queryForObject("""
        select count(*) from target_e2e_room_epoch_binding
         where epoch_id = ? and case_id = ? and room_type = 'REVIEW' and room_epoch = ?
           and room_fencing_token = ? and execution_lane = 'TARGET_E2E_CANDIDATE'
        """, Integer.class, allocation.epochId(), allocation.caseId(), allocation.roomEpoch(), allocation.fencingToken());
    require(bindings != null && bindings == 1, "Review target binding");
    ReviewEpochTask task = one(jdbc.query(
        REVIEW_EPOCH_TASK_SOURCE_SQL,
        (row, ignored) -> new ReviewEpochTask(
            row.getString(1), row.getString(2), row.getString(3)),
        reviewTaskId, allocation.caseId()));
    jdbc.update(
        REVIEW_EPOCH_TASK_BINDING_INSERT_SQL,
        allocation.epochId(), allocation.tenantSurrogate(), allocation.caseId(),
        allocation.roomEpoch(), allocation.fencingToken(), task.taskId(), task.planId(),
        task.policyDecisionId(), handoffId, CONTROL_ACTOR);
    String boundEpochId = one(jdbc.query(
        REVIEW_EPOCH_TASK_BINDING_REPLAY_SQL,
        (row, ignored) -> row.getString(1),
        allocation.epochId(), allocation.tenantSurrogate(), allocation.caseId(),
        allocation.roomEpoch(), allocation.fencingToken(), task.taskId(), task.planId(),
        task.policyDecisionId(), handoffId));
    require(boundEpochId.equals(allocation.epochId()), "Review epoch task binding replay");
  }
  private String json(JsonNode value) { return ContractJson.canonicalString(value.isMissingNode() ? mapper.createArrayNode() : value); }
  private Parent parent(String sql, Cursor cursor) { return one(jdbc.query(sql, (r,i)->new Parent(r.getString(1), r.getString(2)), cursor.authority().caseId(), cursor.flowId())); }
  private JsonNode parse(String json) { try { return mapper.readTree(json); } catch (Exception failure) { throw new IllegalStateException("persisted Hearing JSON is invalid", failure); } }
  private String canonical(String json) { return ContractJson.canonicalString(parse(json)); }
  private String hashJson(String json) { return ContractJson.sha256Hex(parse(json)); }
  static void requireExactPartySubmissionSchema(CommandType commandType, HearingFlowActionType actionType,
      String persistedActionSchema, String eventActionSchema, String payloadSchema) {
    boolean commandAndActionMatch = (commandType == CommandType.HEARING_STATEMENT
        && actionType == HearingFlowActionType.ANSWER_BUNDLE)
        || (commandType == CommandType.HEARING_EVIDENCE_BATCH
            && actionType == HearingFlowActionType.EVIDENCE_BATCH);
    boolean allowedSchema = switch (commandType) {
      case HEARING_STATEMENT -> "hearing_answer_bundle.v1".equals(persistedActionSchema)
          || "hearing_party_statement.v1".equals(persistedActionSchema);
      case HEARING_EVIDENCE_BATCH -> "hearing_evidence_batch.v1".equals(persistedActionSchema);
      default -> false;
    };
    require(commandAndActionMatch && allowedSchema
            && Objects.equals(persistedActionSchema, eventActionSchema)
            && Objects.equals(persistedActionSchema, payloadSchema),
        "case command, Hearing action, timeline event, and party payload schema disagree");
  }
  private static String requiredText(JsonNode node, String field) { String value = node.path(field).asText(null); if (value == null || value.isBlank()) throw new IllegalStateException("formal Hearing payload omits " + field); return value; }
  private static JsonNode requiredObject(JsonNode node, String field) { JsonNode value = node.path(field); if (!value.isObject()) throw new IllegalStateException("formal Hearing payload omits object " + field); return value; }
  private static JsonNode requiredNonEmptyObject(JsonNode node, String field) { JsonNode value = requiredObject(node, field); if (value.isEmpty()) throw new IllegalStateException("formal Hearing payload has empty object " + field); return value; }
  private static JsonNode requiredNonEmptyArray(JsonNode node, String field) { JsonNode value = node.path(field); if (!value.isArray() || value.isEmpty()) throw new IllegalStateException("formal Hearing payload omits nonempty array " + field); return value; }
  private static String requiredValue(String value, String field) { if (value == null || value.isBlank()) throw new IllegalStateException("formal Hearing authority omits " + field); return value; }
  private static String key(HearingAuthorityExpectation authority) { return authority.tenantSurrogate() + ':' + authority.caseId() + ':' + authority.roomEpoch() + ':'; }
  private static String actionId(String kind, String seed) { return "hearing-" + kind + '-' + UUID.nameUUIDFromBytes((kind + ':' + seed).getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""); }
  private static <T> T one(List<T> rows) { if (rows.size() != 1) throw new IllegalStateException("target Hearing row is absent or ambiguous"); return rows.getFirst(); }
  private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
  private record Cursor(HearingAuthorityExpectation authority, String flowId, String sourceStageId, String sourceOutputJson) {}
  private record PartyAction(String id, String actionType, String schemaVersion, String participantId, String participantRole, String submissionStatus, String payloadJson, String contentHash) {}
  private record CaseCommandLock(String commandId, String requestHash, String payloadUri, String payloadSha256, long payloadSizeBytes) {}
  private record TimelinePartyEvent(String actionId, String actionSchemaVersion, String actionContentHash, String actionPayload) {}
  private record FinalizationFact(String receiptId, String receiptHash, long processRevision, long stageSequence, long fencingToken) {}
  private record RemedyProjection(String id, int version, String actionsJson, String preconditionsJson, String notificationsJson) {}
  private record ReviewPacketAuthority(
      String title,
      String description,
      String routeType,
      String riskLevel,
      long caseVersion,
      int dossierVersion,
      String caseMatrixHash,
      String evidenceMatrixHash,
      String dossierJson,
      String draftJson,
      String judgeV1RunId,
      String juryRunId,
      String judgeV2RunId) {}
  private record Parent(String id, String hash) {}
  private record ClosureParent(
      String handoffId, String reviewTaskId, String receiptId, String receiptHash) {}
  private record ReviewEpochTask(String taskId, String planId, String policyDecisionId) {}
}
