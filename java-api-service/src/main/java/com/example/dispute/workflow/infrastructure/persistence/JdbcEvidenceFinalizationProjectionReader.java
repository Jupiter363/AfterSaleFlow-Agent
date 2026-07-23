package com.example.dispute.workflow.infrastructure.persistence;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseAuthority;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseRequirement;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt.BatchMergeBinding;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceiptLookup.CommittedFinalization;
import com.example.dispute.evidence.application.graph.EvidenceTerminalSummary;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionQuery.StateEnricher;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.Recovery;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.TerminalProposal;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryReconciler;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore.JavaRecoveryAuthority;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** SELECT-only hydration of public Evidence terminal and recovery projection state. */
public class JdbcEvidenceFinalizationProjectionReader
    implements StateEnricher,
        EvidenceOperationalRecoveryReconciler.EvidenceOperationalRecoveryStateEnricher
            .DurableRecoveryStateReader {

  private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private static final String RECEIPT_AND_SUMMARY_COLUMNS =
      """
      select receipt.schema_version as receipt_schema_version,
             receipt.receipt_id as receipt_id,
             receipt.receipt_hash as receipt_hash,
             receipt.operation_type as receipt_operation_type,
             receipt.operation_key as receipt_operation_key,
             receipt.request_hash as receipt_request_hash,
             receipt.result_hash as receipt_result_hash,
             receipt.commit_scope as receipt_commit_scope,
             receipt.status as receipt_status,
             receipt.formal_domain_write as receipt_formal_domain_write,
             receipt.formal_sink_eligible as receipt_formal_sink_eligible,
             receipt.tenant_surrogate as receipt_tenant_surrogate,
             receipt.case_id as receipt_case_id,
             receipt.room_id as receipt_room_id,
             receipt.graph_binding_id as receipt_graph_binding_id,
             receipt.room_epoch as receipt_room_epoch,
             receipt.fencing_token as receipt_fencing_token,
             receipt.source_revision as receipt_source_revision,
             receipt.process_revision as receipt_process_revision,
             receipt.room_revision as receipt_room_revision,
             receipt.operation_binding_json as receipt_operation_binding_json,
             receipt.merge_count as receipt_merge_count,
             receipt.domain_event_ids_json as receipt_domain_event_ids_json,
             receipt.outbox_ids_json as receipt_outbox_ids_json,
             receipt.hearing_opened as receipt_hearing_opened,
             receipt.committed_at_epoch_second as receipt_committed_at_epoch_second,
             receipt.committed_at_nano as receipt_committed_at_nano,
             receipt.authority_snapshot_hash as receipt_authority_snapshot_hash,
             summary.schema_version as summary_schema_version,
             summary.summary_hash as summary_hash,
             summary.graph_lease_fencing_token as summary_graph_lease_fencing_token,
             summary.java_finalization_fencing_token as summary_java_finalization_fencing_token,
             summary.authority_snapshot_hash as summary_authority_snapshot_hash,
             summary.graph_thread_id as summary_graph_thread_id,
             summary.manifest_hash as summary_manifest_hash,
             summary.proposal_hash as summary_proposal_hash,
             summary.current_fact_ids_json as summary_current_fact_ids_json,
             summary.current_source_refs_json as summary_current_source_refs_json,
             summary.committed_at_epoch_second as summary_committed_at_epoch_second,
             summary.committed_at_nano as summary_committed_at_nano,
             authority.graph_binding_id as authority_graph_binding_id,
             authority.runtime_mode as authority_runtime_mode,
             authority.agent_profile_id as authority_agent_profile_id,
             authority.room_id as authority_room_id,
             authority.actor_id as authority_actor_id,
             authority.actor_role as authority_actor_role,
             authority.participant_id as authority_participant_id,
             authority.actor_scope_hash as authority_actor_scope_hash,
             authority.agent_session_id as authority_agent_session_id,
             authority.current_fact_ids_json as authority_current_fact_ids_json,
             authority.current_source_refs_json as authority_current_source_refs_json
      """;

  private static final String RECOVERY_COLUMNS =
      """
      , recovery.room_type as recovery_room_type,
        recovery.runtime_mode as recovery_runtime_mode,
        recovery.java_signed_synthetic as recovery_java_signed_synthetic,
        recovery.formal_sink_eligible as recovery_formal_sink_eligible,
        recovery.temporal_evidence_allocation as recovery_temporal_evidence_allocation
      """;

  private static final String DURABLE_JOINS =
      """
        from case_evidence_terminal_summary summary
        join case_evidence_finalization_receipt receipt
          on receipt.receipt_id = summary.receipt_id
         and receipt.receipt_hash = summary.receipt_hash
         and receipt.tenant_surrogate = summary.tenant_surrogate
         and receipt.case_id = summary.case_id
         and receipt.room_epoch = summary.room_epoch
         and receipt.fencing_token = summary.java_room_fencing_token
         and receipt.source_revision = summary.source_revision
         and receipt.process_revision = summary.process_revision
         and receipt.room_revision = summary.room_revision
         and receipt.result_hash = summary.result_hash
         and receipt.authority_snapshot_hash = summary.authority_snapshot_hash
        join case_evidence_current_authority_snapshot authority
          on authority.authority_snapshot_hash = summary.authority_snapshot_hash
         and authority.graph_binding_id = receipt.graph_binding_id
         and authority.tenant_surrogate = summary.tenant_surrogate
         and authority.case_id = summary.case_id
         and authority.room_id = receipt.room_id
         and authority.room_epoch = summary.room_epoch
         and authority.java_room_fencing_token = summary.java_room_fencing_token
         and authority.source_revision = summary.source_revision
         and authority.process_revision = summary.process_revision
         and authority.room_revision = summary.room_revision
         and authority.is_current = true
      """;

  private static final String RECOVERY_JOIN =
      """
        left join case_evidence_operational_recovery recovery
          on recovery.authority_snapshot_hash = authority.authority_snapshot_hash
         and recovery.graph_binding_id = authority.graph_binding_id
         and recovery.tenant_surrogate = authority.tenant_surrogate
         and recovery.case_id = authority.case_id
         and recovery.room_id = authority.room_id
         and recovery.room_epoch = authority.room_epoch
         and recovery.java_room_fencing_token = authority.java_room_fencing_token
         and recovery.source_revision = authority.source_revision
         and recovery.process_revision = authority.process_revision
         and recovery.room_revision = authority.room_revision
         and recovery.is_current = true
      """;

  private static final String DURABLE_SCOPE =
      """
       where summary.tenant_surrogate = :tenantSurrogate
         and summary.case_id = :caseId
         and summary.room_epoch = :roomEpoch
         and summary.java_room_fencing_token = :javaRoomFencingToken
         and summary.process_revision = :processRevision
         and summary.room_revision = :roomRevision
         and authority.room_id = :roomId
         and receipt.operation_type = 'BATCH_MERGE'
         and receipt.status = 'COMMITTED'
         and receipt.formal_domain_write = false
         and receipt.formal_sink_eligible = false
      """;

  static final String TERMINAL_SQL = RECEIPT_AND_SUMMARY_COLUMNS + DURABLE_JOINS + DURABLE_SCOPE;
  static final String RECOVERY_SQL =
      RECEIPT_AND_SUMMARY_COLUMNS + RECOVERY_COLUMNS + DURABLE_JOINS + RECOVERY_JOIN + DURABLE_SCOPE;

  private final NamedParameterJdbcOperations jdbc;
  private final TransactionTemplate readTransactions;
  private final ObjectProvider<GraphLeaseAuthority> graphLeaseAuthorities;

  public JdbcEvidenceFinalizationProjectionReader(
      NamedParameterJdbcOperations jdbc,
      PlatformTransactionManager transactionManager,
      ObjectProvider<GraphLeaseAuthority> graphLeaseAuthorities) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.graphLeaseAuthorities = Objects.requireNonNull(graphLeaseAuthorities, "graphLeaseAuthorities");
    this.readTransactions = new TransactionTemplate(
        Objects.requireNonNull(transactionManager, "transactionManager"));
    this.readTransactions.setReadOnly(true);
  }

  @Override
  public EvidenceProcessProjectionAdapter.ProjectionEvidenceState enrich(
      EvidenceProcessProjectionAdapter.ProjectionRow row,
      AuthenticatedActor actor,
      EvidenceProcessProjectionAdapter.ProjectionEvidenceState current) {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(current, "current");
    if (!supports(row, actor)) {
      return current;
    }
    return inReadTransaction(() -> load(row, RECOVERY_SQL, true)
        .map(material -> withDurableState(row, current, material))
        .orElse(current));
  }

  @Override
  public Optional<Recovery> findDurableRecovery(
      EvidenceProcessProjectionAdapter.ProjectionRow row, AuthenticatedActor actor) {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(actor, "actor");
    if (row.historyMode() || !supports(row, actor)) {
      return Optional.empty();
    }
    return inReadTransaction(() -> load(row, RECOVERY_SQL, true).flatMap(this::validatedRecovery));
  }

  private Optional<DurableMaterial> load(
      EvidenceProcessProjectionAdapter.ProjectionRow row, String sql, boolean includeRecovery) {
    List<DurableMaterial> rows = jdbc.query(sql, parameters(row),
        (resultSet, ignored) -> mapMaterial(resultSet, includeRecovery));
    if (rows.size() > 1) {
      throw new IllegalStateException("multiple durable Evidence projections match one room epoch");
    }
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private Optional<Recovery> validatedRecovery(DurableMaterial material) {
    if (material.recoveryAuthority() == null || !material.recoveryAuthorityMatches()) {
      return Optional.empty();
    }
    List<GraphLeaseAuthority> authorities;
    try {
      authorities = graphLeaseAuthorities.orderedStream().limit(2).toList();
    } catch (RuntimeException unavailable) {
      return Optional.empty();
    }
    if (authorities.size() != 1) {
      return Optional.empty();
    }
    try {
      authorities.getFirst().requireCurrent(new GraphLeaseRequirement(
          material.summary().authoritySnapshotHash(),
          material.summary().tenantSurrogate(),
          material.summary().caseId(),
          material.authority().roomId(),
          material.summary().roomEpoch(),
          material.summary().javaRoomFencingToken(),
          material.summary().graphThreadId(),
          material.summary().graphLeaseFencingToken()));
    } catch (RuntimeException rejected) {
      return Optional.empty();
    }
    return Optional.of(new Recovery(
        "RESUMABLE", true, material.receipt().receiptId(), material.receipt().receiptHash()));
  }

  private EvidenceProcessProjectionAdapter.ProjectionEvidenceState withDurableState(
      EvidenceProcessProjectionAdapter.ProjectionRow row,
      EvidenceProcessProjectionAdapter.ProjectionEvidenceState current,
      DurableMaterial material) {
    EvidenceProcessProjectionAdapter.ProjectionEvidenceState terminal =
        withTerminal(current, material);
    if (row.historyMode()) {
      return terminal;
    }
    return validatedRecovery(material)
        .map(recovery -> withRecovery(terminal, recovery))
        .orElse(terminal);
  }

  private static EvidenceProcessProjectionAdapter.ProjectionEvidenceState withRecovery(
      EvidenceProcessProjectionAdapter.ProjectionEvidenceState current, Recovery recovery) {
    return new EvidenceProcessProjectionAdapter.ProjectionEvidenceState(
        current.originalDeadlineAt(),
        current.warningSent(),
        current.warningSentAt(),
        current.partyCompletion(),
        current.assessmentCounts(),
        current.dossierVersion(),
        current.lastEventSequence(),
        current.terminalReason(),
        current.terminalProposal(),
        recovery);
  }

  private static EvidenceProcessProjectionAdapter.ProjectionEvidenceState withTerminal(
      EvidenceProcessProjectionAdapter.ProjectionEvidenceState current, DurableMaterial material) {
    BatchMergeBinding binding = (BatchMergeBinding) material.receipt().operationBinding();
    TerminalProposal proposal =
        new TerminalProposal(material.receipt().receiptId(), material.summary().proposalHash());
    if ((current.terminalProposal() != null && !current.terminalProposal().equals(proposal))
        || (current.dossierVersion() != null
            && current.dossierVersion() != binding.dossierTargetVersion())) {
      throw new IllegalStateException("durable Evidence terminal state conflicts with projection state");
    }
    return new EvidenceProcessProjectionAdapter.ProjectionEvidenceState(
        current.originalDeadlineAt(),
        current.warningSent(),
        current.warningSentAt(),
        current.partyCompletion(),
        current.assessmentCounts(),
        binding.dossierTargetVersion(),
        current.lastEventSequence(),
        current.terminalReason(),
        proposal,
        current.recovery());
  }

  private static boolean supports(
      EvidenceProcessProjectionAdapter.ProjectionRow row, AuthenticatedActor actor) {
    return "SHADOW".equals(row.writerMode())
        && row.tenantSurrogate() != null
        && row.tenantSurrogate().startsWith("TENANT_P5_SYNTHETIC_")
        && row.caseId() != null
        && row.caseId().startsWith("CASE_P5_SYNTHETIC_")
        && row.roomId() != null
        && row.epochRoomEpochValue() != null
        && row.epochProcessRevisionValue() != null
        && row.roomRevisionValue() != null
        && row.epochFencingTokenValue() != null
        && "SHADOW".equals(row.epochWriterMode())
        && "READY".equals(row.epochProvisioningStatus())
        && (("ACTIVE".equals(row.epochLifecycleStatus())
                && "READY".equals(row.writerActivationStatus()))
            || ("TERMINAL".equals(row.epochLifecycleStatus())
                && "TERMINAL".equals(row.writerActivationStatus())))
        && row.projectionRoomEpoch() == row.epochRoomEpochValue()
        && row.projectionProcessRevision() == row.epochProcessRevisionValue()
        && row.projectionFencingToken() == row.epochFencingTokenValue()
        && actor.actorId().equals(row.scopedActorId())
        && actor.role().name().equals(row.scopedActorRole());
  }

  private static MapSqlParameterSource parameters(
      EvidenceProcessProjectionAdapter.ProjectionRow row) {
    return new MapSqlParameterSource()
        .addValue("tenantSurrogate", row.tenantSurrogate())
        .addValue("caseId", row.caseId())
        .addValue("roomId", row.roomId())
        .addValue("roomEpoch", row.projectionRoomEpoch())
        .addValue("javaRoomFencingToken", row.projectionFencingToken())
        .addValue("processRevision", row.projectionProcessRevision())
        .addValue("roomRevision", row.roomRevisionValue());
  }

  private static DurableMaterial mapMaterial(ResultSet row, boolean includeRecovery)
      throws SQLException {
    EvidenceFinalizationReceipt receipt = mapReceipt(row);
    EvidenceTerminalSummary summary = mapSummary(row, receipt);
    EvidenceCurrentAuthoritySnapshot authority = mapAuthority(row, summary);
    JavaRecoveryAuthority recovery = includeRecovery && row.getString("recovery_room_type") != null
        ? mapRecovery(row, summary, authority)
        : null;
    return new DurableMaterial(
        receipt,
        summary,
        authority,
        row.getString("receipt_room_id"),
        row.getString("receipt_graph_binding_id"),
        row.getString("receipt_authority_snapshot_hash"),
        recovery);
  }

  private static EvidenceFinalizationReceipt mapReceipt(ResultSet row) throws SQLException {
    EvidenceFinalizationReceipt.OperationType operationType;
    try {
      operationType = EvidenceFinalizationReceipt.OperationType.valueOf(
          row.getString("receipt_operation_type"));
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("persisted Evidence receipt operation is invalid", invalid);
    }
    if (operationType != EvidenceFinalizationReceipt.OperationType.BATCH_MERGE) {
      throw new IllegalStateException("projection reader only accepts batch-merge receipts");
    }
    JsonNode bindingJson = readTree(row.getString("receipt_operation_binding_json"));
    BatchMergeBinding binding = new BatchMergeBinding(
        requiredText(bindingJson, "manifest_hash"),
        requiredLong(bindingJson, "dossier_target_version"),
        requiredText(bindingJson, "proposal_hash"),
        requiredText(bindingJson, "logical_run_id"),
        requiredText(bindingJson, "command_id"),
        requiredText(bindingJson, "attempt_id"),
        requiredText(bindingJson, "thread_id"));
    return new EvidenceFinalizationReceipt(
        row.getString("receipt_schema_version"),
        row.getString("receipt_id"),
        row.getString("receipt_hash"),
        operationType,
        row.getString("receipt_operation_key"),
        row.getString("receipt_request_hash"),
        row.getString("receipt_result_hash"),
        row.getString("receipt_commit_scope"),
        row.getString("receipt_status"),
        row.getBoolean("receipt_formal_domain_write"),
        row.getBoolean("receipt_formal_sink_eligible"),
        row.getString("receipt_tenant_surrogate"),
        row.getString("receipt_case_id"),
        row.getLong("receipt_room_epoch"),
        row.getLong("receipt_fencing_token"),
        row.getLong("receipt_source_revision"),
        row.getLong("receipt_process_revision"),
        row.getLong("receipt_room_revision"),
        binding,
        row.getInt("receipt_merge_count"),
        readStringList(row.getString("receipt_domain_event_ids_json")),
        readStringList(row.getString("receipt_outbox_ids_json")),
        row.getBoolean("receipt_hearing_opened"),
        exactInstant(row, "summary"));
  }

  private static EvidenceTerminalSummary mapSummary(
      ResultSet row, EvidenceFinalizationReceipt receipt) throws SQLException {
    return new EvidenceTerminalSummary(
        row.getString("summary_schema_version"),
        row.getString("summary_hash"),
        receipt.receiptId(),
        receipt.receiptHash(),
        receipt.tenantSurrogate(),
        receipt.caseId(),
        receipt.roomEpoch(),
        receipt.fencingToken(),
        row.getLong("summary_graph_lease_fencing_token"),
        row.getLong("summary_java_finalization_fencing_token"),
        receipt.sourceRevision(),
        receipt.processRevision(),
        receipt.roomRevision(),
        row.getString("summary_authority_snapshot_hash"),
        row.getString("summary_graph_thread_id"),
        row.getString("summary_manifest_hash"),
        row.getString("summary_proposal_hash"),
        receipt.resultHash(),
        readStringList(row.getString("summary_current_fact_ids_json")),
        readStringList(row.getString("summary_current_source_refs_json")),
        exactInstant(row, "receipt"));
  }

  private static EvidenceCurrentAuthoritySnapshot mapAuthority(
      ResultSet row, EvidenceTerminalSummary summary) throws SQLException {
    return new EvidenceCurrentAuthoritySnapshot(
        summary.authoritySnapshotHash(),
        row.getString("authority_graph_binding_id"),
        row.getString("authority_runtime_mode"),
        row.getString("authority_agent_profile_id"),
        summary.tenantSurrogate(),
        summary.caseId(),
        row.getString("authority_room_id"),
        summary.roomEpoch(),
        summary.javaRoomFencingToken(),
        row.getString("authority_actor_id"),
        row.getString("authority_actor_role"),
        row.getString("authority_participant_id"),
        row.getString("authority_actor_scope_hash"),
        row.getString("authority_agent_session_id"),
        summary.sourceRevision(),
        summary.processRevision(),
        summary.roomRevision(),
        readStringList(row.getString("authority_current_fact_ids_json")),
        readStringList(row.getString("authority_current_source_refs_json")));
  }

  private static JavaRecoveryAuthority mapRecovery(
      ResultSet row,
      EvidenceTerminalSummary summary,
      EvidenceCurrentAuthoritySnapshot authority) throws SQLException {
    return new JavaRecoveryAuthority(
        summary.tenantSurrogate(),
        summary.caseId(),
        authority.roomId(),
        row.getString("recovery_room_type"),
        authority.authoritySnapshotHash(),
        summary.roomEpoch(),
        summary.javaRoomFencingToken(),
        summary.sourceRevision(),
        summary.processRevision(),
        summary.roomRevision(),
        row.getString("recovery_runtime_mode"),
        row.getBoolean("recovery_java_signed_synthetic"),
        row.getBoolean("recovery_formal_sink_eligible"),
        row.getBoolean("recovery_temporal_evidence_allocation"));
  }

  private <T> T inReadTransaction(Supplier<T> work) {
    return Objects.requireNonNull(
        readTransactions.execute(ignored -> work.get()), "read transaction returned no result");
  }

  private static Instant exactInstant(ResultSet row, String prefix) throws SQLException {
    return Instant.ofEpochSecond(
        row.getLong(prefix + "_committed_at_epoch_second"),
        row.getInt(prefix + "_committed_at_nano"));
  }

  private static JsonNode readTree(String value) {
    try {
      return JSON.readTree(value);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("persisted Evidence receipt JSON is invalid", failure);
    }
  }

  private static List<String> readStringList(String value) {
    try {
      return JSON.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("persisted Evidence projection list is invalid", failure);
    }
  }

  private static String requiredText(JsonNode parent, String field) {
    JsonNode value = parent.required(field);
    if (!value.isTextual()) {
      throw new IllegalStateException("persisted Evidence receipt field is not text: " + field);
    }
    return value.textValue();
  }

  private static long requiredLong(JsonNode parent, String field) {
    JsonNode value = parent.required(field);
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalStateException("persisted Evidence receipt field is not an integer: " + field);
    }
    return value.longValue();
  }

  private record DurableMaterial(
      EvidenceFinalizationReceipt receipt,
      EvidenceTerminalSummary summary,
      EvidenceCurrentAuthoritySnapshot authority,
      String receiptRoomId,
      String receiptGraphBindingId,
      String receiptAuthoritySnapshotHash,
      JavaRecoveryAuthority recoveryAuthority) {

    private DurableMaterial {
      new CommittedFinalization(receipt, summary);
      if (!(receipt.operationBinding() instanceof BatchMergeBinding binding)
          || !binding.manifestHash().equals(summary.manifestHash())
          || !binding.proposalHash().equals(summary.proposalHash())
          || !binding.threadId().equals(summary.graphThreadId())
          || !receiptRoomId.equals(authority.roomId())
          || !receiptGraphBindingId.equals(authority.graphBindingId())
          || !receiptAuthoritySnapshotHash.equals(authority.authoritySnapshotHash())
          || !"SIGNED_SYNTHETIC_SHADOW".equals(authority.runtimeMode())
          || !receipt.committedAt().equals(summary.committedAt())
          || !summary.currentFactIds().equals(authority.currentFactIds())
          || !summary.currentSourceRefs().equals(authority.currentSourceRefs())) {
        throw new IllegalStateException("durable Evidence projection authority is inconsistent");
      }
    }

    boolean recoveryAuthorityMatches() {
      return recoveryAuthority != null
          && recoveryAuthority.tenantSurrogate().equals(summary.tenantSurrogate())
          && recoveryAuthority.caseId().equals(summary.caseId())
          && recoveryAuthority.roomId().equals(authority.roomId())
          && recoveryAuthority.authoritySnapshotHash().equals(summary.authoritySnapshotHash())
          && recoveryAuthority.roomEpoch() == summary.roomEpoch()
          && recoveryAuthority.javaRoomFencingToken() == summary.javaRoomFencingToken()
          && recoveryAuthority.sourceRevision() == summary.sourceRevision()
          && recoveryAuthority.processRevision() == summary.processRevision()
          && recoveryAuthority.roomRevision() == summary.roomRevision()
          && "SIGNED_SYNTHETIC_SHADOW".equals(recoveryAuthority.runtimeMode())
          && recoveryAuthority.javaSignedSynthetic()
          && !recoveryAuthority.formalSinkEligible()
          && !recoveryAuthority.temporalEvidenceAllocation();
    }
  }
}
