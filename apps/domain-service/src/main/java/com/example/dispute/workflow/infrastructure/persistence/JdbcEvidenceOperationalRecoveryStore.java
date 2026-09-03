package com.example.dispute.workflow.infrastructure.persistence;

import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceiptLookup;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceiptLookup.CommittedFinalization;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ActivityRequest;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore.DurableReceipt;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore.JavaRecoveryAuthority;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore.RecoveryScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production-shaped, read-only B3 adapter. Durable receipt material remains behind the C3
 * reader; this adapter only reads the live Java authority row and has no Spring registration.
 */
public final class JdbcEvidenceOperationalRecoveryStore implements EvidenceOperationalRecoveryStore {

  private final NamedParameterJdbcTemplate jdbc;
  private final DurableReceiptReader receiptReader;

  public JdbcEvidenceOperationalRecoveryStore(
      NamedParameterJdbcTemplate jdbc, DurableReceiptReader receiptReader) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.receiptReader = Objects.requireNonNull(receiptReader, "receiptReader");
  }

  /** C3-owned receipt lookup performs all semantic/request-hash and sidecar validation. */
  public static DurableReceiptReader c3ReceiptReader(EvidenceFinalizationReceiptLookup lookup) {
    Objects.requireNonNull(lookup, "lookup");
    return request -> lookup.findForActivity(request).map(JdbcEvidenceOperationalRecoveryStore::toDurableReceipt);
  }

  @Override
  public Optional<DurableReceipt> findCommitted(ActivityRequest request) {
    return receiptReader.findCommitted(Objects.requireNonNull(request, "request"));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<JavaRecoveryAuthority> findCurrentJavaAuthority(RecoveryScope scope) {
    Objects.requireNonNull(scope, "scope");
    List<JavaRecoveryAuthority> rows = jdbc.query(
        """
        select recovery.tenant_surrogate, recovery.case_id, recovery.room_id,
               recovery.room_type, recovery.authority_snapshot_hash,
               recovery.room_epoch, recovery.java_room_fencing_token,
               recovery.source_revision, recovery.process_revision, recovery.room_revision,
               recovery.runtime_mode, recovery.java_signed_synthetic,
               recovery.formal_sink_eligible, recovery.temporal_evidence_allocation
          from case_evidence_operational_recovery recovery
          join case_evidence_current_authority_snapshot authority
            on authority.authority_snapshot_hash = recovery.authority_snapshot_hash
           and authority.tenant_surrogate = recovery.tenant_surrogate
           and authority.case_id = recovery.case_id
           and authority.room_id = recovery.room_id
           and authority.room_epoch = recovery.room_epoch
           and authority.java_room_fencing_token = recovery.java_room_fencing_token
           and authority.source_revision = recovery.source_revision
           and authority.process_revision = recovery.process_revision
           and authority.room_revision = recovery.room_revision
         where recovery.tenant_surrogate = :tenantSurrogate
           and recovery.case_id = :caseId
           and recovery.room_type = 'EVIDENCE'
           and recovery.room_epoch = :roomEpoch
           and recovery.java_room_fencing_token = :javaRoomFencingToken
           and recovery.source_revision = :sourceRevision
           and recovery.process_revision = :processRevision
           and recovery.room_revision = :roomRevision
           and recovery.authority_snapshot_hash = :authoritySnapshotHash
           and recovery.is_current = true
           and authority.is_current = true
        """,
        new MapSqlParameterSource()
            .addValue("tenantSurrogate", scope.tenantSurrogate())
            .addValue("caseId", scope.caseId())
            .addValue("roomEpoch", scope.roomEpoch())
            .addValue("javaRoomFencingToken", scope.javaRoomFencingToken())
            .addValue("sourceRevision", scope.sourceRevision())
            .addValue("processRevision", scope.processRevision())
            .addValue("roomRevision", scope.roomRevision())
            .addValue("authoritySnapshotHash", scope.authoritySnapshotHash()),
        JdbcEvidenceOperationalRecoveryStore::mapCurrentAuthority);
    if (rows.size() > 1) {
      throw new IllegalStateException("multiple current Evidence recovery authority rows");
    }
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private static JavaRecoveryAuthority mapCurrentAuthority(ResultSet row, int ignored)
      throws SQLException {
    String runtimeMode = row.getString("runtime_mode");
    return new JavaRecoveryAuthority(
        row.getString("tenant_surrogate"),
        row.getString("case_id"),
        row.getString("room_id"),
        row.getString("room_type"),
        row.getString("authority_snapshot_hash"),
        row.getLong("room_epoch"),
        row.getLong("java_room_fencing_token"),
        row.getLong("source_revision"),
        row.getLong("process_revision"),
        row.getLong("room_revision"),
        runtimeMode,
        row.getBoolean("java_signed_synthetic"),
        row.getBoolean("formal_sink_eligible"),
        row.getBoolean("temporal_evidence_allocation"));
  }

  private static DurableReceipt toDurableReceipt(CommittedFinalization value) {
    var receipt = value.receipt().toSyntheticReceiptRef();
    var summary = value.terminalSummary();
    return new DurableReceipt(receipt, new EvidenceOperationalRecoveryStore.TerminalSummary(
        summary.receiptId(), summary.summaryHash(), summary.tenantSurrogate(), summary.caseId(),
        summary.roomEpoch(), summary.javaRoomFencingToken(), summary.graphThreadId(),
        summary.graphLeaseFencingToken(), summary.javaFinalizationFencingToken(),
        summary.sourceRevision(), summary.processRevision(), summary.roomRevision(),
        summary.authoritySnapshotHash(), summary.manifestHash(), receipt.operationKey(),
        receipt.requestHash(), summary.resultHash()));
  }

  /** Bridge implemented by the C3 durable ledger read port after Wave-B integration. */
  @FunctionalInterface
  public interface DurableReceiptReader {
    Optional<DurableReceipt> findCommitted(ActivityRequest request);
  }
}
