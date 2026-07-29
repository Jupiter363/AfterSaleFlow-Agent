package com.example.dispute.workflow.targete2e.rooms.outcome;

import com.example.dispute.executor.application.TargetTemporalOutcomeLedgerAdapter;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Locks the pre-existing Java review facts required by the shared Outcome ledger. */
public final class JdbcTargetTemporalOutcomeBindingResolver {
  static final String BINDING_SQL = """
      select epoch.id as epoch_id, epoch.tenant_surrogate, epoch.process_revision, epoch.room_revision,
             packet.id as packet_id, packet.packet_version, packet.action_hash as packet_action_hash,
             approval.id as approval_id,
             approval.action_snapshot_hash as approval_action_hash
        from case_room_epoch epoch
        join human_review_record approval on approval.id = ? and approval.case_id = epoch.case_id
        join review_packet packet on packet.id = approval.review_packet_id and packet.case_id = epoch.case_id
       where epoch.case_id = ? and epoch.room_type = 'REVIEW' and epoch.room_epoch = ?
         and epoch.fencing_token = ? and epoch.writer_mode = 'TEMPORAL'
         and epoch.lifecycle_status = 'ACTIVE' and approval.decision_type in ('APPROVE', 'MODIFY_AND_APPROVE')
       for update of epoch, approval, packet
      """;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public JdbcTargetTemporalOutcomeBindingResolver(
      DataSource dataSource, TransactionTemplate transactions, Clock clock) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public TargetTemporalOutcomeLedgerAdapter.Binding bind(
      OutcomeWorkflowStart start, OutcomeReviewDecisionReceipt decision) {
    return Objects.requireNonNull(transactions.execute(ignored -> bindLocked(start, decision)),
        "target Outcome binding transaction returned null");
  }

  private TargetTemporalOutcomeLedgerAdapter.Binding bindLocked(
      OutcomeWorkflowStart start, OutcomeReviewDecisionReceipt decision) {
    List<Row> rows = jdbc.query(BINDING_SQL, (row, ignored) -> new Row(row.getString("epoch_id"), row.getString("tenant_surrogate"),
        row.getLong("process_revision"), row.getLong("room_revision"), row.getString("packet_id"),
        row.getInt("packet_version"), row.getString("packet_action_hash"), row.getString("approval_id"),
        row.getString("approval_action_hash")), decision.decisionRecordRef(),
        start.caseId(), start.epoch(), start.fence());
    if (rows.isEmpty()) throw new IllegalStateException("target Outcome ledger authority is absent");
    Row first = rows.getFirst();
    if (!first.packetId().equals(start.frozenReviewPacketRef())
        || !first.approvalId().equals(decision.decisionRecordRef())
        || !first.approvalActionHash().equals(decision.approvedActionSnapshotHash())
        || !first.packetActionHash().equals(decision.actionSnapshotHash())) {
      throw new IllegalStateException("target Outcome review facts conflict with its frozen start");
    }
    List<String> actions = jdbc.query("""
        select id from action_record
         where case_id = ? and approval_record_id = ? and review_packet_id = ?
           and action_snapshot_hash = ? and execution_status in ('RUNNING', 'SUCCEEDED')
         order by id for update
        """, (row, ignored) -> row.getString(1), start.caseId(), first.approvalId(), first.packetId(),
        decision.approvedActionSnapshotHash());
    if (start.requiredOperationCount() != 1 || actions.size() != 1) {
      throw new IllegalStateException("target Outcome requires the exact pre-existing approved ActionRecord set");
    }
    Instant now = clock.instant();
    OutcomeProcessProjection projection = new OutcomeProcessProjection(
        "OUTPRJ_" + decision.receiptHash().substring(0, 32), first.tenant(), start.caseId(), first.epochId(),
        start.epoch(), OutcomeProcessProjection.WriterMode.TEMPORAL,
        OutcomeProcessProjection.RuntimeMode.TEMPORAL, start.fence(), first.processRevision(), first.roomRevision(),
        first.approvalId(), decision.requestHash(), decision.approvedActionSnapshotHash(), start.requiredOperationCount(),
        OutcomeProcessProjection.ProcessState.DECISION_RECORDED, now, now);
    return new TargetTemporalOutcomeLedgerAdapter.Binding(projection, first.packetId(), first.packetVersion(),
        start.frozenReviewPacketHash(), first.packetActionHash(), first.approvalId(), first.approvalActionHash(),
        decision.requestHash(), start.policyVersion(), actions);
  }

  private record Row(String epochId, String tenant, long processRevision, long roomRevision,
      String packetId, int packetVersion, String packetActionHash, String approvalId,
      String approvalActionHash) {}
}
