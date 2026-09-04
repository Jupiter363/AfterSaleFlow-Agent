package com.example.dispute.workflow.runtime.rooms.outcome;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeClosureReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeEvaluationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.evaluation.application.ClosureView;
import com.example.dispute.evaluation.application.EvaluationReportView;
import com.example.dispute.executor.application.TargetTemporalOutcomeLedgerAdapter;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Append-only JDBC implementation for the formal target completion relay. The completion facts
 * are committed before they are returned, so a Temporal retry can only replay byte-identical
 * Java facts. The target manifest is intentionally represented by NO_EXTERNAL_EFFECT commands.
 */
public final class JdbcTargetOutcomeCompletionActivities implements TargetOutcomeCompletionActivities {
  private static final String WRITER = "production-runtime-outcome-completion";
  static final String FACTS_SQL = """
      select fact_kind, revision, committed_event_sequence, payload_json::text, payload_hash
        from production_runtime_outcome_completion_fact
       where workflow_id = ? and case_id = ? and outcome_epoch = ? and fencing_token = ?
         and human_receipt_id = ? and human_receipt_hash = ?
       order by revision
       for update
      """;
  static final String COMMAND_ADMISSION_SQL = """
      select command.id, command.command_id, command.command_status, command.result_uri,
             command.result_sha256, admission.admission_id, admission.activation_id,
             admission.command_hash, admission.command_envelope_hash,
             decision_event.event_json::text, material.material_canonical_json,
             material.material_sha256
        from case_command command
        join production_runtime_command_admission admission
          on admission.tenant_surrogate = command.tenant_surrogate
         and admission.case_id = command.case_id
         and admission.command_id = command.command_id
         and admission.room_epoch = command.room_epoch
        join production_runtime_review_command_material material
          on material.admission_id = admission.admission_id
         and material.activation_id = admission.activation_id
         and material.activation_manifest_hash = admission.activation_manifest_hash
         and material.isolated_domain_db_binding_hash = admission.isolated_domain_db_binding_hash
         and material.tenant_surrogate = admission.tenant_surrogate
         and material.case_id = admission.case_id
         and material.command_id = admission.command_id
         and material.command_hash = admission.command_hash
         and material.command_envelope_hash = admission.command_envelope_hash
         and material.room_epoch = admission.room_epoch
         and material.room_fencing_token = admission.room_fencing_token
        join case_timeline_event decision_event
          on decision_event.case_id = command.case_id
         and decision_event.event_json ->> 'command_id' = command.command_id
         and decision_event.event_key =
             ('target-review-decision:' || (decision_event.event_json ->> 'approval_record_id'))
        join human_review_record approval
          on approval.id = decision_event.event_json ->> 'approval_record_id'
         and approval.case_id = command.case_id
         and approval.review_task_id = decision_event.event_json ->> 'review_task_id'
         and approval.review_packet_id = decision_event.event_json ->> 'packet_id'
         and approval.action_snapshot_hash =
             decision_event.event_json ->> 'approved_action_snapshot_hash'
         and approval.decision_type::text = decision_event.event_json ->> 'decision'
         and approval.ai_decision_action =
             decision_event.event_json ->> 'ai_decision_action'
         and approval.reviewer_decision_action =
             decision_event.event_json ->> 'reviewer_decision_action'
       where command.case_id = ?
         and command.command_type = 'REVIEW_DECISION'
         and command.room_type = 'REVIEW'
         and command.room_epoch = ?
         and admission.room_fencing_token = ?
         and approval.id = ?
         and approval.decision_type in ('APPROVE', 'MODIFY_AND_APPROVE')
         and approval.reviewer_decision_action in (
             'CANCEL_ORDER', 'RETURN_AND_REFUND', 'REFUND_ONLY', 'RESHIP', 'REPLACE',
             'REPAIR', 'COMPENSATE', 'CONTINUE_FULFILLMENT', 'REJECT_CLAIM')
         and approval.approved_plan_json ->> 'decision_action' =
             approval.reviewer_decision_action
         and command.payload_schema_version = 'production-runtime-review-human-decision-event.v1'
         and command.payload_sha256 = ?
         and decision_event.event_json ->> 'schema_version' = command.payload_schema_version
         and decision_event.event_json ->> 'case_id' = command.case_id
         and decision_event.event_json ->> 'room_epoch' = command.room_epoch::text
         and decision_event.event_json ->> 'fencing_token' = admission.room_fencing_token::text
         and decision_event.event_json ->> 'case_process_revision' =
             command.expected_process_revision::text
         and material.material_schema_version = 'production-runtime-review-command-material.v1'
         and material.material_canonical_json::jsonb #>> '{expected_process_revision}' =
             command.expected_process_revision::text
         and material.material_canonical_json::jsonb #>> '{room_fencing_token}' =
             admission.room_fencing_token::text
         and material.material_canonical_json::jsonb #>> '{request,command,tenant_surrogate}' =
             command.tenant_surrogate
         and material.material_canonical_json::jsonb #>> '{request,command,case_id}' =
             command.case_id
         and material.material_canonical_json::jsonb #>> '{request,command,command_id}' =
             command.command_id
         and material.material_canonical_json::jsonb #>> '{request,command,room_type}' =
             command.room_type
         and material.material_canonical_json::jsonb #>> '{request,command,room_epoch}' =
             command.room_epoch::text
         and material.material_canonical_json::jsonb #>> '{request,command,event_ref,uri}' =
             command.payload_uri
          and material.material_canonical_json::jsonb #>> '{request,command,event_ref,sha256}' =
              command.payload_sha256
       """;
  static final String COMMAND_COMPLETION_SQL = """
      select 1 from production_runtime_command_completion
       where admission_id = ? and activation_id = ? and command_id = ?
         and command_hash = ? and command_envelope_hash = ? and completion_hash = ?
      for key share
      """;
  static final String BEGIN_EXECUTION_SQL = """
      update fulfillment_dispute_case
         set case_status = 'EXECUTING', current_room = 'OUTCOME', current_deadline_at = null,
             updated_at = ?, updated_by = ?, version = version + 1
       where id = ? and case_status = 'APPROVED_FOR_EXECUTION'
      """;
  static final String TERMINALIZE_EPOCH_SQL = """
      update case_room_epoch
         set lifecycle_status = 'TERMINAL', process_revision = ?, room_revision = ?,
             terminal_at = ?, updated_at = ?, version = version + 1
       where case_id = ? and room_type = 'REVIEW' and room_epoch = ? and fencing_token = ?
         and lifecycle_status = 'ACTIVE'
      """;
  private final DataSource dataSource;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final CaseClosureService closureService;
  private final TargetTemporalOutcomeLedgerAdapter ledger;
  private final JdbcTargetTemporalOutcomeBindingResolver bindingResolver;
  private final ProductionActivationLedger activationLedger;

  public JdbcTargetOutcomeCompletionActivities(
      DataSource dataSource, TransactionTemplate transactions, ObjectMapper mapper, Clock clock) {
    this(dataSource, transactions, mapper, clock, null);
  }

  public JdbcTargetOutcomeCompletionActivities(DataSource dataSource, TransactionTemplate transactions,
      ObjectMapper mapper, Clock clock, CaseClosureService closureService) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.clock = Objects.requireNonNull(clock, "clock");
    this.closureService = closureService;
    this.ledger = null;
    this.bindingResolver = null;
    this.activationLedger = null;
  }

  public JdbcTargetOutcomeCompletionActivities(DataSource dataSource, TransactionTemplate transactions,
      ObjectMapper mapper, Clock clock, CaseClosureService closureService, OutcomeOperationLedger ledger,
      JdbcTargetTemporalOutcomeBindingResolver bindingResolver) {
    this(dataSource, transactions, mapper, clock, closureService, ledger, bindingResolver, null);
  }

  public JdbcTargetOutcomeCompletionActivities(DataSource dataSource, TransactionTemplate transactions,
      ObjectMapper mapper, Clock clock, CaseClosureService closureService, OutcomeOperationLedger ledger,
      JdbcTargetTemporalOutcomeBindingResolver bindingResolver, ProductionActivationLedger activationLedger) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy(); this.clock = Objects.requireNonNull(clock, "clock");
    this.closureService = Objects.requireNonNull(closureService, "closureService");
    this.ledger = new TargetTemporalOutcomeLedgerAdapter(Objects.requireNonNull(ledger, "ledger"));
    this.bindingResolver = Objects.requireNonNull(bindingResolver, "bindingResolver");
    this.activationLedger = activationLedger;
  }

  @Override
  public CompletionResult complete(CompletionRequest request) {
    CompletionRequest completionRequest = Objects.requireNonNull(request, "request");
    CompletionResult terminal = transactions.execute(ignored -> replayTerminalIfPresent(completionRequest));
    if (terminal != null) return terminal;
    transactions.executeWithoutResult(ignored -> prepareOperations(completionRequest));
    transactions.executeWithoutResult(ignored -> beginExecution(completionRequest));
    // CaseClosureService deliberately runs outside the durable operation transaction.
    ClosureView closure = closeNoEffectCase(completionRequest);
    EvaluationReportView evaluation = closureService.evaluation(completionRequest.start().caseId(),
        new AuthenticatedActor("production-runtime-outcome", ActorRole.SYSTEM));
    return Objects.requireNonNull(transactions.execute(ignored -> finishCompletion(completionRequest, closure, evaluation)),
        "target Outcome completion transaction returned null");
  }

  private CompletionResult replayTerminalIfPresent(CompletionRequest request) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (PreparedStatement statement = connection.prepareStatement("""
        select lifecycle_status from case_room_epoch where case_id = ? and room_type = 'REVIEW'
          and room_epoch = ? and fencing_token = ? for key share
        """)) {
      statement.setString(1, request.start().caseId()); statement.setLong(2, request.start().epoch()); statement.setLong(3, request.start().fence());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw new IllegalStateException("target Outcome epoch is absent");
        if (!"TERMINAL".equals(rows.getString(1))) return null;
      }
      List<Fact> facts = facts(connection, request);
      TargetRoomProgressReceipt progress = loadTerminalProgressLocked(new TerminalProgressRequest(
          request.start().workflowId(), request.start().caseId(), request.start().epoch(), request.start().fence(),
          request.humanDecision().receiptId(), request.humanDecision().receiptHash(), request.humanDecision().revision()));
      return replay(facts, request.expectedRevision(), progress);
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("target Outcome terminal replay failed", failure);
    } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
  }

  private void beginExecution(CompletionRequest request) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (PreparedStatement update = connection.prepareStatement(BEGIN_EXECUTION_SQL)) {
      update.setTimestamp(1, sqlTimestamp(clock.instant())); update.setString(2, WRITER);
      update.setString(3, request.start().caseId());
      if (update.executeUpdate() == 1) return;
      try (PreparedStatement current = connection.prepareStatement(
          "select case_status from fulfillment_dispute_case where id = ? for update")) {
        current.setString(1, request.start().caseId());
        try (ResultSet rows = current.executeQuery()) {
          if (!rows.next()) {
            throw new IllegalStateException("target Outcome case is not execution-authorized");
          }
          String status = rows.getString(1);
          if (rows.next() || !("EXECUTING".equals(status) || "CLOSED".equals(status))) {
            throw new IllegalStateException("target Outcome case is not execution-authorized");
          }
        }
      }
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("cannot begin target Outcome execution", failure);
    } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
  }

  @Override
  public TargetRoomProgressReceipt loadTerminalProgress(TerminalProgressRequest request) {
    return Objects.requireNonNull(transactions.execute(ignored -> loadTerminalProgressLocked(request)),
        "target Outcome terminal lookup returned null");
  }

  private TargetRoomProgressReceipt loadTerminalProgressLocked(TerminalProgressRequest request) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      List<Fact> facts = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement("""
          select fact_kind, revision, committed_event_sequence, payload_json::text, payload_hash
            from production_runtime_outcome_completion_fact
           where workflow_id = ? and case_id = ? and outcome_epoch = ? and fencing_token = ?
             and human_receipt_id = ? and human_receipt_hash = ?
           order by revision for key share
          """)) {
        statement.setString(1, request.workflowId()); statement.setString(2, request.caseId());
        statement.setLong(3, request.outcomeEpoch()); statement.setLong(4, request.fencingToken());
        statement.setString(5, request.humanReceiptId()); statement.setString(6, request.humanReceiptHash());
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            Fact fact = new Fact(rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getString(4), rows.getString(5));
            if (!canonicalPayloadHash(fact.payload()).equals(fact.hash())) throw new IllegalStateException("target Outcome terminal fact hash conflicts");
            facts.add(fact);
          }
        }
      }
      if (facts.size() != 4 || facts.getFirst().revision() != request.humanReceiptRevision() + 1
          || !"OPERATION_COMMAND".equals(facts.get(0).kind()) || !"OPERATION_RECEIPT".equals(facts.get(1).kind())
          || !"CLOSURE_RECEIPT".equals(facts.get(2).kind()) || !"EVALUATION_RECEIPT".equals(facts.get(3).kind())
          || facts.get(1).revision() != facts.get(0).revision() + 1
          || facts.get(2).revision() != facts.get(1).revision() + 1
          || facts.get(3).revision() != facts.get(2).revision() + 1
          || facts.get(1).sequence() != facts.get(0).sequence() + 1
          || facts.get(2).sequence() != facts.get(1).sequence() + 1
          || facts.get(3).sequence() != facts.get(2).sequence() + 1) {
        throw new IllegalStateException("target Outcome terminal facts are absent or incomplete");
      }
      long processRevision; long roomRevision;
      try (PreparedStatement statement = connection.prepareStatement("""
          select epoch.process_revision, epoch.room_revision
            from case_room_epoch epoch join outcome_process_projection projection
              on projection.case_id = epoch.case_id and projection.outcome_epoch = epoch.room_epoch
             and projection.fencing_token = epoch.fencing_token
           where epoch.case_id = ? and epoch.room_type = 'REVIEW' and epoch.room_epoch = ?
             and epoch.fencing_token = ? and epoch.lifecycle_status = 'TERMINAL'
             and projection.process_state = 'EVALUATED'
           for key share of epoch, projection
          """)) {
        statement.setString(1, request.caseId()); statement.setLong(2, request.outcomeEpoch()); statement.setLong(3, request.fencingToken());
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) throw new IllegalStateException("target Outcome terminal state is absent");
          processRevision = rows.getLong(1); roomRevision = rows.getLong(2);
          if (rows.next()) throw new IllegalStateException("target Outcome terminal state is absent");
        }
      }
      CommandAdmissionBinding command = commandAdmission(connection, request.caseId(),
          request.outcomeEpoch(), request.fencingToken(), request.humanReceiptId(),
          request.humanReceiptHash(), false);
      requireAppliedCommand(command, facts.getLast().hash());
      try (PreparedStatement statement = connection.prepareStatement("""
          select 1 from case_process_projection where case_id = ? and writer_mode = 'TEMPORAL'
             and macro_phase = 'TERMINAL' and current_room is null and room_phase = 'TERMINAL'
             and writer_activation_status = 'TERMINAL' and process_revision = ?
          for key share
          """)) {
        statement.setString(1, request.caseId()); statement.setLong(2, processRevision);
        try (ResultSet rows = statement.executeQuery()) { if (!rows.next() || rows.next()) throw new IllegalStateException("target Outcome case process is not terminal"); }
      }
      requireActivationCompletion(connection, command, facts.getLast().hash());
      String identity = hash(List.of(request.workflowId(), request.caseId(), request.outcomeEpoch(), request.fencingToken(),
          processRevision, roomRevision, facts.stream().map(Fact::hash).toList()));
      return new TargetRoomProgressReceipt(RoomType.REVIEW, request.outcomeEpoch(), request.fencingToken(),
          processRevision, roomRevision, id("OTRM", identity), identity);
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("target Outcome terminal lookup failed", failure);
    } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
  }

  private void prepareOperations(CompletionRequest request) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (connection.getAutoCommit()) throw new IllegalStateException("target Outcome completion requires a transaction");
      List<Fact> facts = facts(connection, request);
      verifyAuthority(connection, request, facts.isEmpty());
      if (!request.humanDecision().executionAuthorized()) {
        throw new IllegalStateException("rejected target Outcome must not invoke completion");
      }
      if (request.start().requiredOperationCount() != 1) {
        throw new IllegalStateException("target manifest requires exactly one formal no-effect operation");
      }
      if (facts.isEmpty()) {
        facts = reserveAndCompleteNoEffect(request);
        for (Fact fact : facts) append(connection, request.start(), request.humanDecision(), fact);
      }
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("target Outcome operation preparation failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private CompletionResult finishCompletion(CompletionRequest request, ClosureView closure, EvaluationReportView evaluation) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (connection.getAutoCommit()) throw new IllegalStateException("target Outcome completion requires a transaction");
      List<Fact> facts = facts(connection, request);
      verifyAuthority(connection, request, false);
      if (facts.size() < 2) throw new IllegalStateException("target Outcome operation facts are incomplete");
      if (facts.size() == 2) {
        facts = appendTerminalFacts(connection, facts, request, closure, evaluation);
        for (Fact fact : facts.subList(2, facts.size())) append(connection, request.start(), request.humanDecision(), fact);
      }
      advanceTerminalProjection(connection, request);
      completeAdmission(connection, request, facts.getLast());
      return replay(facts, request.expectedRevision(), terminalize(connection, request, facts));
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("target Outcome completion persistence failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private void verifyAuthority(Connection connection, CompletionRequest request,
      boolean initialBinding) throws java.sql.SQLException {
    OutcomeWorkflowStart start = request.start();
    OutcomeReviewDecisionReceipt decision = request.humanDecision();
    com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionRequest completion =
        request.completionRequest();
    if (!start.workflowId().equals(decision.workflowId()) || !start.caseId().equals(decision.caseId())
        || start.epoch() != decision.epoch() || start.fence() != decision.fence()
        || start.revision() != decision.sourceRevision() || decision.revision() != start.revision() + 1
        || !decision.receiptId().equals(decision.decisionRecordRef())
        || !decision.receiptHash().equals(decision.decisionRecordHash())
        || !start.reviewTaskId().equals(decision.reviewTaskId())
        || !start.frozenReviewPacketRef().equals(decision.frozenReviewPacketRef())
        || !start.frozenReviewPacketHash().equals(decision.frozenReviewPacketHash())
        || !start.requiredOperationSetHash().equals(decision.requiredOperationSetHash())
        || start.requiredOperationCount() != decision.requiredOperationCount()
        || !start.policyVersion().equals(decision.policyVersion()) || decision.syntheticOnly()
        || completion.roomType() != RoomType.REVIEW
        || completion.roomEpoch() != start.epoch()
        || completion.fencingToken() != start.fence()
        || completion.expectedRoomRevision() != decision.revision()
        || !completion.reviewReceiptId().equals(decision.receiptId())
        || !completion.reviewReceiptHash().equals(decision.receiptHash())
        || completion.reviewReceiptRevision() != decision.revision()) {
      throw new IllegalStateException("target Outcome start or human receipt is stale");
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        select epoch.id, epoch.process_revision, epoch.room_revision
          from case_room_epoch epoch
         where epoch.case_id = ? and epoch.room_type = 'REVIEW' and epoch.room_epoch = ?
           and epoch.fencing_token = ? and epoch.writer_mode = 'TEMPORAL'
           and epoch.lifecycle_status = 'ACTIVE'
         for update
        """)) {
      statement.setString(1, start.caseId()); statement.setLong(2, start.epoch());
      statement.setLong(3, start.fence());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw new IllegalStateException("target Outcome epoch authority is absent or ambiguous");
        long processRevision = rows.getLong(2); long roomRevision = rows.getLong(3);
        if (rows.next()) {
          throw new IllegalStateException("target Outcome epoch revision is stale");
        }
        if (initialBinding) {
          requireInitialEpochCoordinates(
              completion, start.revision(), decision.revision(), processRevision, roomRevision);
        }
      }
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        select id from human_review_record
         where id = ? and case_id = ? and decision_type in ('APPROVE', 'MODIFY_AND_APPROVE')
           and reviewer_decision_action in (
               'CANCEL_ORDER', 'RETURN_AND_REFUND', 'REFUND_ONLY', 'RESHIP', 'REPLACE',
               'REPAIR', 'COMPENSATE', 'CONTINUE_FULFILLMENT', 'REJECT_CLAIM')
           and approved_plan_json ->> 'decision_action' = reviewer_decision_action
         for key share
        """)) {
      statement.setString(1, decision.decisionRecordRef()); statement.setString(2, start.caseId());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.next()) throw new IllegalStateException("target Outcome human decision is not durable");
      }
    }
  }

  private static long requestRevision(OutcomeWorkflowStart start, OutcomeReviewDecisionReceipt decision) {
    return decision.revision();
  }

  private List<Fact> facts(Connection connection, CompletionRequest request) throws java.sql.SQLException {
    List<Fact> result = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(FACTS_SQL)) {
      OutcomeWorkflowStart start = request.start();
      statement.setString(1, start.workflowId()); statement.setString(2, start.caseId());
      statement.setLong(3, start.epoch()); statement.setLong(4, start.fence());
      statement.setString(5, request.humanDecision().receiptId());
      statement.setString(6, request.humanDecision().receiptHash());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          Fact fact = new Fact(rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getString(4), rows.getString(5));
          if (!canonicalPayloadHash(fact.payload()).equals(fact.hash())) {
            throw new IllegalStateException("target Outcome fact payload hash is not canonical");
          }
          result.add(fact);
        }
      }
    }
    return result;
  }

  static void requireInitialEpochCoordinates(
      com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionRequest completion,
      long startRevision,
      long decisionRevision,
      long persistedProcessRevision,
      long persistedRoomRevision) {
    if (completion.expectedProcessRevision() != Math.incrementExact(persistedProcessRevision)
        || startRevision != persistedRoomRevision
        || decisionRevision != Math.incrementExact(persistedRoomRevision)
        || completion.expectedRoomRevision() != decisionRevision) {
      throw new IllegalStateException("target Outcome epoch revision is stale");
    }
  }

  private ClosureView closeNoEffectCase(CompletionRequest request) {
    if (closureService == null) throw new IllegalStateException("target Outcome requires CaseClosureService");
    String key = "target-outcome:" + request.humanDecision().receiptHash();
    return closureService.close(request.start().caseId(), key,
        new AuthenticatedActor("production-runtime-outcome", ActorRole.SYSTEM),
        "EVAL_" + request.humanDecision().receiptHash().substring(0, 32), request.humanDecision().receiptId());
  }

  private List<Fact> reserveAndCompleteNoEffect(CompletionRequest request) {
    if (ledger == null || bindingResolver == null) throw new IllegalStateException("target Outcome requires its ledger binding");
    var binding = bindingResolver.bind(request.start(), request.humanDecision());
    OutcomeProcessProjection projection = ledger.createProjection(binding.projection());
    OutcomeOperationCommand command = operationCommand(request);
    ledger.reserve(command, binding, clock.instant());
    projection = advance(projection, OutcomeProcessProjection.ProcessState.OPERATIONS_RESERVED);
    OutcomeOperationReceipt receipt = operationReceipt(command, request.start(), projection.updatedAt());
    ledger.recordNoEffectSuccess(command, receipt.receiptId(), receipt.receiptHash(), receipt.resultRef(),
        receipt.resultHash(), receipt.observedAt(), binding);
    markActionSucceeded(binding.actionRecordId(1), receipt.observedAt());
    projection = advance(projection, OutcomeProcessProjection.ProcessState.READY_TO_CLOSE);
    return List.of(fact("OPERATION_COMMAND", command, command.revision(), command.committedEventSequence()),
        fact("OPERATION_RECEIPT", receipt, receipt.revision(), receipt.committedEventSequence()));
  }

  private void markActionSucceeded(String actionId, Instant completedAt) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (PreparedStatement statement = connection.prepareStatement("""
        update action_record set execution_status = 'SUCCEEDED', execution_time = coalesce(execution_time, ?),
            result_json = jsonb_build_object('effect_class', 'NO_EXTERNAL_EFFECT', 'receipt_at', ?),
            external_result_ref = coalesce(external_result_ref, ?)
         where id = ? and execution_status = 'RUNNING'
        """)) {
      statement.setTimestamp(1, sqlTimestamp(completedAt)); statement.setString(2, completedAt.toString());
      statement.setString(3, "urn:target-outcome:no-effect:" + actionId); statement.setString(4, actionId);
      int changed = statement.executeUpdate();
      if (changed == 0 && !actionSucceeded(connection, actionId)) {
        throw new IllegalStateException("target Outcome action is not a governed RUNNING record");
      }
    } catch (java.sql.SQLException failure) { throw new IllegalStateException("cannot complete target Outcome action", failure); }
    finally { DataSourceUtils.releaseConnection(connection, dataSource); }
  }

  private static boolean actionSucceeded(Connection connection, String actionId) throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "select 1 from action_record where id = ? and execution_status = 'SUCCEEDED' for update")) {
      statement.setString(1, actionId);
      try (ResultSet rows = statement.executeQuery()) { return rows.next() && !rows.next(); }
    }
  }

  private OutcomeProcessProjection advance(OutcomeProcessProjection projection,
      OutcomeProcessProjection.ProcessState next) {
    return ledgerAdvance(new OutcomeOperationLedger.ProjectionExpectation(projection.projectionId(),
        projection.tenantSurrogate(), projection.caseId(), projection.outcomeEpoch(), projection.fencingToken(),
        projection.processRevision(), projection.outcomeRevision()), next);
  }

  private OutcomeProcessProjection ledgerAdvance(OutcomeOperationLedger.ProjectionExpectation expectation,
      OutcomeProcessProjection.ProcessState next) {
    return ledger.advance(expectation, next, clock.instant());
  }

  private List<Fact> appendTerminalFacts(Connection connection, List<Fact> operationFacts, CompletionRequest request, ClosureView closed, EvaluationReportView evaluation) throws java.sql.SQLException {
    OutcomeWorkflowStart start = request.start(); OutcomeReviewDecisionReceipt decision = request.humanDecision();
    long revision = operationFacts.getLast().revision(); long sequence = operationFacts.getLast().sequence();
    List<Fact> facts = new ArrayList<>(operationFacts);
    long closureRevision = ++revision; long closureSequence = ++sequence;
    String closureSeed = hash(List.of(start.workflowId(), decision.receiptHash(), "closure"));
    SnapshotFacts snapshots = snapshotFacts(connection, closed.evaluationTraceId(), start.caseId());
    String snapshotRef = "urn:evaluation-trace:" + closed.evaluationTraceId();
    String snapshotHash = snapshots.inputSnapshotHash();
    OutcomeClosureReceipt closure = new OutcomeClosureReceipt(OutcomeClosureReceipt.SCHEMA_VERSION,
        start.workflowId(), start.caseId(), id("OCLS", closureSeed), hash(List.of(closureSeed, "hash")),
        decision.receiptId(), decision.receiptHash(), decision.approvedActionSnapshotRef(),
        decision.approvedActionSnapshotHash(), start.requiredOperationSetRef(), start.requiredOperationSetHash(),
        start.requiredOperationCount(), "urn:target-outcome:terminal:" + closureSeed.substring(0, 24),
        hash(List.of(closureSeed, "terminal")), snapshotRef, snapshotHash,
        snapshots.caseVersion(), 0, 0, 0, 0, 0, closed.closedAt().toInstant(), start.epoch(), closureRevision - 1, closureRevision,
        start.fence(), closureSequence, false);
    facts.add(fact("CLOSURE_RECEIPT", closure, closureRevision, closureSequence));
    long evaluationRevision = ++revision; long evaluationSequence = ++sequence; String evaluationSeed = hash(List.of(closure.receiptHash(), "evaluation"));
    OutcomeEvaluationReceipt evaluationReceipt = new OutcomeEvaluationReceipt(OutcomeEvaluationReceipt.SCHEMA_VERSION,
        start.workflowId(), start.caseId(), id("OEVA", evaluationSeed), hash(List.of(evaluationSeed, "hash")),
        closure.closedSnapshotRef(), closure.closedSnapshotHash(), "urn:evaluation-trace:" + evaluation.evaluationTraceId(),
        snapshots.reportHash(), OutcomeWireTypes.EvaluationStatus.SUCCEEDED, evaluation.completedAt().toInstant(),
        start.epoch(), evaluationRevision - 1, evaluationRevision, start.fence(), evaluationSequence, false);
    facts.add(fact("EVALUATION_RECEIPT", evaluationReceipt, evaluationRevision, evaluationSequence));
    return facts;
  }

  private SnapshotFacts snapshotFacts(Connection connection, String traceId, String caseId) throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select trace.input_snapshot_json::text, trace.report_json::text, dispute.version
          from evaluation_record trace join fulfillment_dispute_case dispute on dispute.id = trace.case_id
         where trace.id = ? and trace.case_id = ? and trace.evaluation_status = 'COMPLETED'
         for key share of trace, dispute
        """)) {
      statement.setString(1, traceId); statement.setString(2, caseId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw new IllegalStateException("target Outcome completed evaluation facts are absent");
        SnapshotFacts facts = new SnapshotFacts(
            canonicalPayloadHash(rows.getString(1)), canonicalPayloadHash(rows.getString(2)), rows.getLong(3));
        if (rows.next()) throw new IllegalStateException("target Outcome completed evaluation facts are absent");
        return facts;
      }
    }
  }

  private OutcomeOperationCommand operationCommand(CompletionRequest request) {
    OutcomeWorkflowStart start = request.start(); OutcomeReviewDecisionReceipt decision = request.humanDecision();
    String seed = hash(List.of(start.workflowId(), decision.receiptHash(), "operation", 1));
    return new OutcomeOperationCommand(OutcomeOperationCommand.SCHEMA_VERSION, start.workflowId(), start.caseId(),
        id("OCMD", seed), id("OOP", seed), hash(List.of(seed, "key")), decision.receiptId(), decision.receiptHash(),
        decision.approvedActionSnapshotRef(), decision.approvedActionSnapshotHash(), "urn:target-outcome:request:" + seed.substring(0, 24),
        hash(List.of(seed, "request")), hash(List.of(seed, "idempotency")), OutcomeWireTypes.EffectClass.NO_EXTERNAL_EFFECT,
        true, false, 1, start.epoch(), decision.revision(), decision.revision() + 1, start.fence(),
        decision.committedEventSequence() + 1, 1, start.reviewDeadlineAt(), start.workflowBuild(),
        OutcomeWireTypes.RuntimeMode.TEMPORAL, null, false);
  }

  private OutcomeOperationReceipt operationReceipt(OutcomeOperationCommand command, OutcomeWorkflowStart start, Instant completedAt) {
    String seed = hash(List.of(command.commandId(), "receipt"));
    return new OutcomeOperationReceipt(OutcomeOperationReceipt.SCHEMA_VERSION, start.workflowId(), start.caseId(),
        id("ORCT", seed), hash(List.of(seed, "hash")), command.operationId(), command.operationKeyHash(), command.requestHash(),
        command.externalIdempotencyKeyHash(), "urn:target-outcome:result:" + seed.substring(0, 24), hash(List.of(seed, "result")),
        OutcomeWireTypes.TerminalStatus.SUCCEEDED, 1, true, false, completedAt, start.epoch(), command.revision(),
        command.revision() + 1, start.fence(), command.committedEventSequence() + 1, OutcomeWireTypes.RuntimeMode.TEMPORAL, false);
  }

  private CompletionResult replay(List<Fact> facts, long expectedRevision, TargetRoomProgressReceipt terminalProgress) {
    List<OutcomeOperationCommand> commands = new ArrayList<>(); List<OutcomeOperationReceipt> receipts = new ArrayList<>();
    OutcomeClosureReceipt closure = null; OutcomeEvaluationReceipt evaluation = null;
    for (Fact fact : facts) {
      if (fact.revision() <= expectedRevision) continue;
      try {
        switch (fact.kind()) {
          case "OPERATION_COMMAND" -> commands.add(mapper.readValue(fact.payload(), OutcomeOperationCommand.class));
          case "OPERATION_RECEIPT" -> receipts.add(mapper.readValue(fact.payload(), OutcomeOperationReceipt.class));
          case "CLOSURE_RECEIPT" -> closure = mapper.readValue(fact.payload(), OutcomeClosureReceipt.class);
          case "EVALUATION_RECEIPT" -> evaluation = mapper.readValue(fact.payload(), OutcomeEvaluationReceipt.class);
          default -> throw new IllegalStateException("unknown target Outcome fact kind");
        }
      } catch (Exception failure) { throw new IllegalStateException("target Outcome fact is malformed", failure); }
    }
    return new CompletionResult(commands, receipts, closure, evaluation, terminalProgress);
  }

  private void completeAdmission(Connection connection, CompletionRequest request, Fact terminalFact)
      throws java.sql.SQLException {
    if (activationLedger == null) throw new IllegalStateException("target Outcome requires activation command ledger");
    CommandAdmissionBinding command = commandAdmission(connection, request.start().caseId(),
        request.start().epoch(), request.start().fence(), request.humanDecision().decisionRecordRef(),
        request.humanDecision().decisionRecordHash(), true);
    String resultUri = terminalResultUri(terminalFact.hash());
    if ("ORCHESTRATION_ACCEPTED".equals(command.status())) {
      if (command.resultUri() != null || command.resultHash() != null) {
        throw new IllegalStateException("target Outcome accepted command already carries a result");
      }
      try (PreparedStatement update = connection.prepareStatement("""
          update case_command set command_status = 'APPLIED', result_uri = ?, result_sha256 = ?,
              applied_at = ?, updated_at = ?, version = version + 1
           where id = ? and command_status = 'ORCHESTRATION_ACCEPTED'
             and result_uri is null and result_sha256 is null
          """)) {
        Instant now = clock.instant(); update.setString(1, resultUri); update.setString(2, terminalFact.hash());
        update.setTimestamp(3, sqlTimestamp(now)); update.setTimestamp(4, sqlTimestamp(now)); update.setString(5, command.id());
        if (update.executeUpdate() != 1) throw new IllegalStateException("target Outcome command CAS was rejected");
      }
    } else {
      requireAppliedCommand(command, terminalFact.hash());
    }
    activationLedger.completeCommand(connection, new ProductionActivationLedger.CommandCompletion(
        command.admissionId(), command.activationId(), command.commandId(), command.commandHash(),
        command.commandEnvelopeHash(), terminalFact.hash()));
  }

  private CommandAdmissionBinding commandAdmission(Connection connection, String caseId, long epoch,
      long fence, String decisionRecordId, String decisionRecordHash, boolean updateLock)
      throws java.sql.SQLException {
    String lock = updateLock
        ? "\nfor update of command"
        : "\nfor key share of command, admission, material, decision_event, approval";
    try (PreparedStatement statement = connection.prepareStatement(COMMAND_ADMISSION_SQL + lock)) {
      statement.setString(1, caseId); statement.setLong(2, epoch); statement.setLong(3, fence);
      statement.setString(4, decisionRecordId); statement.setString(5, decisionRecordHash);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new IllegalStateException("target Outcome command admission is absent or ambiguous");
        }
        CommandAdmissionBinding binding = new CommandAdmissionBinding(
            rows.getString("id"), rows.getString("command_id"), rows.getString("command_status"),
            rows.getString("result_uri"), rows.getString("result_sha256"),
            rows.getString("admission_id"), rows.getString("activation_id"),
            rows.getString("command_hash"), rows.getString("command_envelope_hash"));
        String eventJson = rows.getString("event_json");
        String materialJson = rows.getString("material_canonical_json");
        String materialHash = rows.getString("material_sha256");
        if (rows.next()
            || !decisionRecordHash.equals(canonicalPayloadHash(eventJson))
            || !materialJson.equals(canonicalPayload(materialJson))
            || !materialHash.equals(canonicalPayloadHash(materialJson))) {
          throw new IllegalStateException("target Outcome command admission has conflicting durable material");
        }
        return binding;
      }
    }
  }

  private void requireActivationCompletion(Connection connection, CommandAdmissionBinding command,
      String completionHash) throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(COMMAND_COMPLETION_SQL)) {
      statement.setString(1, command.admissionId()); statement.setString(2, command.activationId());
      statement.setString(3, command.commandId()); statement.setString(4, command.commandHash());
      statement.setString(5, command.commandEnvelopeHash()); statement.setString(6, completionHash);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.next()) {
          throw new IllegalStateException("target Outcome activation completion is absent or conflicting");
        }
      }
    }
  }

  private static void requireAppliedCommand(CommandAdmissionBinding command, String terminalHash) {
    if (!"APPLIED".equals(command.status())
        || !terminalResultUri(terminalHash).equals(command.resultUri())
        || !terminalHash.equals(command.resultHash())) {
      throw new IllegalStateException("target Outcome command replay conflicts with its terminal fact");
    }
  }

  static String terminalResultUri(String terminalHash) {
    if (terminalHash == null || !terminalHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("target Outcome terminal hash is invalid");
    }
    return "urn:production-runtime:outcome-terminal:" + terminalHash;
  }

  private void advanceTerminalProjection(Connection connection, CompletionRequest request) throws java.sql.SQLException {
    if (ledger == null || bindingResolver == null) throw new IllegalStateException("target Outcome requires its ledger binding");
    OutcomeProcessProjection projection;
    try (PreparedStatement statement = connection.prepareStatement("""
        select projection_id, tenant_surrogate, case_id, epoch_id, outcome_epoch, writer_mode, runtime_mode,
               fencing_token, process_revision, outcome_revision, decision_authority_receipt_id,
               decision_request_hash, approved_operation_set_hash, expected_required_operation_count,
               process_state, projected_at, updated_at
          from outcome_process_projection where case_id = ? and outcome_epoch = ?
          and fencing_token = ? for update
        """)) {
      statement.setString(1, request.start().caseId()); statement.setLong(2, request.start().epoch()); statement.setLong(3, request.start().fence());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw new IllegalStateException("target Outcome projection is absent or ambiguous");
        projection = new OutcomeProcessProjection(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
            rows.getLong(5), OutcomeProcessProjection.WriterMode.valueOf(rows.getString(6)),
            OutcomeProcessProjection.RuntimeMode.valueOf(rows.getString(7)), rows.getLong(8), rows.getLong(9), rows.getLong(10),
            rows.getString(11), rows.getString(12), rows.getString(13), rows.getLong(14),
            OutcomeProcessProjection.ProcessState.valueOf(rows.getString(15)), rows.getObject(16, java.time.OffsetDateTime.class).toInstant(),
            rows.getObject(17, java.time.OffsetDateTime.class).toInstant());
        if (rows.next()) throw new IllegalStateException("target Outcome projection is absent or ambiguous");
      }
    }
    String state = projection.processState().name();
    if (OutcomeProcessProjection.ProcessState.READY_TO_CLOSE.name().equals(state)) {
      projection = advance(projection, OutcomeProcessProjection.ProcessState.CLOSED);
      state = projection.processState().name();
    }
    if (OutcomeProcessProjection.ProcessState.CLOSED.name().equals(state)) {
      projection = advance(projection, OutcomeProcessProjection.ProcessState.EVALUATION_PENDING);
      advance(projection, OutcomeProcessProjection.ProcessState.EVALUATED);
    } else if (!OutcomeProcessProjection.ProcessState.EVALUATED.name().equals(state)) {
      throw new IllegalStateException("target Outcome projection is not terminal-ready");
    }
  }

  private TargetRoomProgressReceipt terminalize(Connection connection, CompletionRequest request, List<Fact> facts)
      throws java.sql.SQLException {
    long processRevision;
    long roomRevision;
    String lifecycle;
    try (PreparedStatement statement = connection.prepareStatement("""
        select process_revision, room_revision, lifecycle_status
          from case_room_epoch where case_id = ? and room_type = 'REVIEW'
           and room_epoch = ? and fencing_token = ? for update
        """)) {
      statement.setString(1, request.start().caseId()); statement.setLong(2, request.start().epoch());
      statement.setLong(3, request.start().fence());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw new IllegalStateException("target Outcome terminal epoch is absent");
        processRevision = rows.getLong(1); roomRevision = rows.getLong(2); lifecycle = rows.getString(3);
        if (rows.next()) throw new IllegalStateException("target Outcome terminal epoch is absent");
      }
    }
    if (!"TERMINAL".equals(lifecycle)) {
      try (PreparedStatement statement = connection.prepareStatement("""
          select process_revision, outcome_revision from outcome_process_projection
           where case_id = ? and outcome_epoch = ? and fencing_token = ? and process_state = 'EVALUATED'
           for update
          """)) {
        statement.setString(1, request.start().caseId()); statement.setLong(2, request.start().epoch()); statement.setLong(3, request.start().fence());
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) throw new IllegalStateException("target Outcome evaluated projection is absent");
          processRevision = rows.getLong(1) + 1; roomRevision = rows.getLong(2) + 1;
          if (rows.next()) throw new IllegalStateException("target Outcome evaluated projection is absent");
        }
      }
      try (PreparedStatement statement = connection.prepareStatement(TERMINALIZE_EPOCH_SQL)) {
        Instant terminalAt = clock.instant();
        statement.setLong(1, processRevision); statement.setLong(2, roomRevision);
        statement.setTimestamp(3, sqlTimestamp(terminalAt)); statement.setTimestamp(4, sqlTimestamp(terminalAt));
        statement.setString(5, request.start().caseId()); statement.setLong(6, request.start().epoch()); statement.setLong(7, request.start().fence());
        if (statement.executeUpdate() != 1) throw new IllegalStateException("target Outcome terminal epoch transition was rejected");
      }
      try (PreparedStatement statement = connection.prepareStatement("""
          update case_process_projection
             set macro_phase = 'TERMINAL', current_room = null, room_phase = 'TERMINAL',
                 writer_activation_status = 'TERMINAL', projected_deadline_at = null,
                 process_revision = ?, room_epoch = ?, fencing_token = ?, updated_at = ?, version = version + 1
           where case_id = ? and writer_mode = 'TEMPORAL' and process_revision <= ?
          """)) {
        statement.setLong(1, processRevision); statement.setLong(2, request.start().epoch()); statement.setLong(3, request.start().fence());
        statement.setTimestamp(4, sqlTimestamp(clock.instant())); statement.setString(5, request.start().caseId()); statement.setLong(6, processRevision);
        if (statement.executeUpdate() != 1) throw new IllegalStateException("target Outcome process projection terminal transition was rejected");
      }
    }
    String identity = hash(List.of(request.start().workflowId(), request.start().caseId(), request.start().epoch(),
        request.start().fence(), processRevision, roomRevision, facts.stream().map(Fact::hash).toList()));
    return new TargetRoomProgressReceipt(RoomType.REVIEW, request.start().epoch(), request.start().fence(),
        processRevision, roomRevision, id("OTRM", identity), identity);
  }

  private void append(Connection connection, OutcomeWorkflowStart start, OutcomeReviewDecisionReceipt decision,
      Fact fact) throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        insert into production_runtime_outcome_completion_fact (
          workflow_id, case_id, outcome_epoch, fencing_token, human_receipt_id, human_receipt_hash,
          fact_kind, revision, committed_event_sequence, payload_json, payload_hash, committed_at, committed_by)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
        """)) {
      statement.setString(1, start.workflowId()); statement.setString(2, start.caseId()); statement.setLong(3, start.epoch());
      statement.setLong(4, start.fence()); statement.setString(5, decision.receiptId()); statement.setString(6, decision.receiptHash());
      statement.setString(7, fact.kind()); statement.setLong(8, fact.revision()); statement.setLong(9, fact.sequence());
      statement.setString(10, fact.payload()); statement.setString(11, fact.hash());
      statement.setTimestamp(12, sqlTimestamp(clock.instant()));
      statement.setString(13, WRITER); statement.executeUpdate();
    }
  }

  private Fact fact(String kind, Object value, long revision, long sequence) {
    String payload = ContractJson.canonicalString(mapper.valueToTree(value));
    return new Fact(kind, revision, sequence, payload, canonicalPayloadHash(payload));
  }

  static java.sql.Timestamp sqlTimestamp(Instant value) {
    return java.sql.Timestamp.from(Objects.requireNonNull(value, "timestamp"));
  }
  private String canonicalPayloadHash(String payload) {
    try { return ContractJson.sha256Hex(mapper.readTree(payload)); }
    catch (Exception failure) { throw new IllegalStateException("target Outcome fact payload is malformed", failure); }
  }
  private String canonicalPayload(String payload) {
    try { return ContractJson.canonicalString(mapper.readTree(payload)); }
    catch (Exception failure) { throw new IllegalStateException("target Outcome durable material is malformed", failure); }
  }
  private String hash(Object value) { return ContractJson.sha256Hex(mapper.valueToTree(value)); }
  private static String id(String prefix, String hash) { return prefix + "_" + hash.substring(0, 32); }
  private record Fact(String kind, long revision, long sequence, String payload, String hash) {}
  private record CommandAdmissionBinding(String id, String commandId, String status, String resultUri,
      String resultHash, String admissionId, String activationId, String commandHash,
      String commandEnvelopeHash) {}
  private record SnapshotFacts(String inputSnapshotHash, String reportHash, long caseVersion) {}
}
