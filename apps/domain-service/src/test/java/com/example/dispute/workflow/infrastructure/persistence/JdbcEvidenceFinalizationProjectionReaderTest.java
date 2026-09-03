package com.example.dispute.workflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseAuthority;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseRequirement;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt.BatchMergeBinding;
import com.example.dispute.evidence.application.graph.EvidenceTerminalSummary;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter.ProjectionEvidenceState;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter.ProjectionRow;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.AssessmentCounts;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.PartyCompletion;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.Recovery;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.TerminalProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class JdbcEvidenceFinalizationProjectionReaderTest {

  private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
  private static final Instant COMMITTED_AT = Instant.parse("2026-07-23T10:15:30.123456789Z");
  private static final String TENANT = "TENANT_P5_SYNTHETIC_PROJECTION";
  private static final String CASE_ID = "CASE_P5_SYNTHETIC_PROJECTION";
  private static final String ROOM_ID = "ROOM_P5_SYNTHETIC_PROJECTION";
  private static final String AUTHORITY_HASH = "d".repeat(64);
  private static final String MANIFEST_HASH = "a".repeat(64);
  private static final String PROPOSAL_HASH = "f".repeat(64);

  private NamedParameterJdbcOperations jdbc;
  private PlatformTransactionManager transactions;
  private TransactionStatus transactionStatus;
  private ObjectProvider<GraphLeaseAuthority> graphLeaseAuthorities;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jdbc = mock(NamedParameterJdbcOperations.class);
    transactions = mock(PlatformTransactionManager.class);
    transactionStatus = mock(TransactionStatus.class);
    graphLeaseAuthorities = mock(ObjectProvider.class);
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(graphLeaseAuthorities.orderedStream()).thenReturn(Stream.empty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void hydratesTerminalFactsFromOneSelectOnlyCurrentAuthoritySnapshot() throws Exception {
    ResultSet persisted = persistedMaterial();
    when(jdbc.query(
            anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> {
          RowMapper<Object> mapper = invocation.getArgument(2);
          return List.of(mapper.mapRow(persisted, 0));
        });
    JdbcEvidenceFinalizationProjectionReader reader = reader();
    ProjectionRow row = projectionRow(false);
    ProjectionEvidenceState pending = pendingState();

    ProjectionEvidenceState enriched = reader.enrich(row, actor(), pending);

    assertThat(enriched.dossierVersion()).isEqualTo(3L);
    assertThat(enriched.terminalProposal())
        .isEqualTo(new TerminalProposal(receipt().receiptId(), PROPOSAL_HASH));
    assertThat(enriched.recovery()).isEqualTo(Recovery.none());
    assertThat(enriched.partyCompletion()).isEqualTo(PartyCompletion.pending());
    assertThat(enriched.assessmentCounts()).isEqualTo(AssessmentCounts.empty());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<MapSqlParameterSource> parameters =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
    String normalized = sql.getValue().trim().toLowerCase();
    assertThat(normalized).startsWith("select ");
    assertThat(normalized)
        .contains(
            "join case_evidence_finalization_receipt receipt",
            "join case_evidence_current_authority_snapshot authority",
            "left join case_evidence_operational_recovery recovery",
            "authority.is_current = true",
            "recovery.is_current = true")
        .doesNotContain(" insert ", " update ", " delete ", " for update");
    assertThat(parameters.getValue().getValues())
        .containsEntry("tenantSurrogate", TENANT)
        .containsEntry("caseId", CASE_ID)
        .containsEntry("roomId", ROOM_ID)
        .containsEntry("roomEpoch", 4L)
        .containsEntry("javaRoomFencingToken", 9L)
        .containsEntry("processRevision", 12L)
        .containsEntry("roomRevision", 7L);
    verify(transactions).getTransaction(
        org.mockito.ArgumentMatchers.argThat(TransactionDefinition::isReadOnly));
  }

  @Test
  @SuppressWarnings("unchecked")
  void recoveryRequiresTheSameDurableSnapshotAndOneLiveGraphLease() throws Exception {
    ResultSet persisted = persistedMaterial();
    when(jdbc.query(
            anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> {
          RowMapper<Object> mapper = invocation.getArgument(2);
          return List.of(mapper.mapRow(persisted, 0));
        });
    GraphLeaseAuthority liveLease = mock(GraphLeaseAuthority.class);
    when(graphLeaseAuthorities.orderedStream()).thenReturn(Stream.of(liveLease));
    JdbcEvidenceFinalizationProjectionReader reader = reader();

    ProjectionEvidenceState enriched = reader.enrich(projectionRow(false), actor(), pendingState());

    assertThat(enriched.recovery())
        .isEqualTo(new Recovery(
            "RESUMABLE", true, receipt().receiptId(), receipt().receiptHash()));
    ArgumentCaptor<GraphLeaseRequirement> requirement =
        ArgumentCaptor.forClass(GraphLeaseRequirement.class);
    verify(liveLease).requireCurrent(requirement.capture());
    assertThat(requirement.getValue().authoritySnapshotHash()).isEqualTo(AUTHORITY_HASH);
    assertThat(requirement.getValue().roomId()).isEqualTo(ROOM_ID);
    assertThat(requirement.getValue().graphLeaseFencingToken()).isEqualTo(41);
  }

  @Test
  @SuppressWarnings("unchecked")
  void rejectedOrUnavailableLiveLeaseNeverFabricatesRecoveryOrBlocksTerminalHydration()
      throws Exception {
    ResultSet persisted = persistedMaterial();
    when(jdbc.query(
            anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> {
          RowMapper<Object> mapper = invocation.getArgument(2);
          return List.of(mapper.mapRow(persisted, 0));
        });
    GraphLeaseAuthority staleLease = mock(GraphLeaseAuthority.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("stale lease"))
        .when(staleLease)
        .requireCurrent(any());
    when(graphLeaseAuthorities.orderedStream()).thenReturn(Stream.of(staleLease));
    JdbcEvidenceFinalizationProjectionReader reader = reader();

    ProjectionEvidenceState enriched = reader.enrich(projectionRow(false), actor(), pendingState());

    assertThat(enriched.terminalProposal()).isNotNull();
    assertThat(enriched.dossierVersion()).isEqualTo(3L);
    assertThat(enriched.recovery()).isEqualTo(Recovery.none());
  }

  @Test
  @SuppressWarnings("unchecked")
  void absentOperationalRecoveryRowStillHydratesTheCommittedTerminalReceipt() throws Exception {
    ResultSet persisted = persistedMaterial(false);
    when(jdbc.query(
            anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> {
          RowMapper<Object> mapper = invocation.getArgument(2);
          return List.of(mapper.mapRow(persisted, 0));
        });
    GraphLeaseAuthority graphLease = mock(GraphLeaseAuthority.class);
    when(graphLeaseAuthorities.orderedStream()).thenReturn(Stream.of(graphLease));
    JdbcEvidenceFinalizationProjectionReader reader = reader();

    ProjectionEvidenceState enriched = reader.enrich(projectionRow(false), actor(), pendingState());

    assertThat(enriched.terminalProposal()).isNotNull();
    assertThat(enriched.dossierVersion()).isEqualTo(3L);
    assertThat(enriched.recovery()).isEqualTo(Recovery.none());
    verify(graphLease, never()).requireCurrent(any());
  }

  @Test
  void missingDurableRowsReturnTheExactPendingStateAndDoNotConsultGraph() {
    when(jdbc.query(
            anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());
    GraphLeaseAuthority graphLease = mock(GraphLeaseAuthority.class);
    when(graphLeaseAuthorities.orderedStream()).thenReturn(Stream.of(graphLease));
    JdbcEvidenceFinalizationProjectionReader reader = reader();
    ProjectionEvidenceState pending = pendingState();

    ProjectionEvidenceState enriched = reader.enrich(projectionRow(false), actor(), pending);

    assertThat(enriched).isSameAs(pending);
    verify(graphLease, never()).requireCurrent(any());
  }

  @Test
  void readerDoesNotExposeTheActivityReceiptReplayBoundary() {
    assertThat(Arrays.stream(JdbcEvidenceFinalizationProjectionReader.class.getMethods())
            .map(method -> method.getName()))
        .doesNotContain("findForActivity", "loadCommittedReceipt", "lookupForActivity");
  }

  private JdbcEvidenceFinalizationProjectionReader reader() {
    return new JdbcEvidenceFinalizationProjectionReader(
        jdbc, transactions, graphLeaseAuthorities);
  }

  private static ProjectionEvidenceState pendingState() {
    return ProjectionEvidenceState.pending(
        OffsetDateTime.parse("2026-07-23T12:00:00Z"));
  }

  private static AuthenticatedActor actor() {
    return new AuthenticatedActor("USER_P5_PROJECTION", ActorRole.USER);
  }

  private static ProjectionRow projectionRow(boolean historyMode) {
    return new ProjectionRow(
        TENANT,
        CASE_ID,
        ROOM_ID,
        "SHADOW",
        "READY",
        4,
        12,
        9,
        "READY_TO_FREEZE",
        OffsetDateTime.parse("2026-07-23T11:00:00Z"),
        "SHADOW",
        "ACTIVE",
        "READY",
        4L,
        12L,
        7L,
        9L,
        "evidence-workflow.synthetic.v1",
        "evidence.v2.0.0",
        "evidence-checkpoint.v2",
        false,
        null,
        pendingState(),
        historyMode,
        actor().actorId(),
        actor().role().name());
  }

  private static EvidenceFinalizationReceipt receipt() {
    BatchMergeBinding binding = new BatchMergeBinding(
        MANIFEST_HASH,
        3,
        PROPOSAL_HASH,
        "LOGICAL_RUN_P5_PROJECTION",
        "COMMAND_P5_PROJECTION",
        "ATTEMPT_P5_PROJECTION",
        "grt.v1.018f6b7ec30a7430982fffc520c8195c");
    return EvidenceFinalizationReceipt.committedSyntheticBatchMerge(
        "RECEIPT_P5_PROJECTION",
        "b".repeat(64),
        "c".repeat(64),
        TENANT,
        CASE_ID,
        4,
        9,
        3,
        12,
        7,
        binding,
        COMMITTED_AT);
  }

  private static EvidenceCurrentAuthoritySnapshot authority() {
    return new EvidenceCurrentAuthoritySnapshot(
        AUTHORITY_HASH,
        "GRAPH_BINDING_P5_PROJECTION",
        "SIGNED_SYNTHETIC_SHADOW",
        "evidence-clerk.v2",
        TENANT,
        CASE_ID,
        ROOM_ID,
        4,
        9,
        actor().actorId(),
        actor().role().name(),
        "PARTICIPANT_P5_PROJECTION",
        "e".repeat(64),
        "AGENT_SESSION_P5_PROJECTION",
        3,
        12,
        7,
        List.of("FACT_P5_PROJECTION"),
        List.of("SOURCE_P5_PROJECTION"));
  }

  private static ResultSet persistedMaterial() throws Exception {
    return persistedMaterial(true);
  }

  private static ResultSet persistedMaterial(boolean includeRecovery) throws Exception {
    EvidenceFinalizationReceipt receipt = receipt();
    BatchMergeBinding binding = (BatchMergeBinding) receipt.operationBinding();
    EvidenceCurrentAuthoritySnapshot authority = authority();
    EvidenceTerminalSummary summary =
        EvidenceTerminalSummary.create(receipt, authority, 41, 42);
    Map<String, String> strings = new HashMap<>();
    strings.put("receipt_schema_version", receipt.schemaVersion());
    strings.put("receipt_id", receipt.receiptId());
    strings.put("receipt_hash", receipt.receiptHash());
    strings.put("receipt_operation_type", receipt.operationType().name());
    strings.put("receipt_operation_key", receipt.operationKey());
    strings.put("receipt_request_hash", receipt.requestHash());
    strings.put("receipt_result_hash", receipt.resultHash());
    strings.put("receipt_commit_scope", receipt.commitScope());
    strings.put("receipt_status", receipt.status());
    strings.put("receipt_tenant_surrogate", receipt.tenantSurrogate());
    strings.put("receipt_case_id", receipt.caseId());
    strings.put("receipt_room_id", ROOM_ID);
    strings.put("receipt_graph_binding_id", authority.graphBindingId());
    strings.put("receipt_operation_binding_json", JSON.writeValueAsString(binding.toContractJson()));
    strings.put("receipt_domain_event_ids_json", "[]");
    strings.put("receipt_outbox_ids_json", "[]");
    strings.put("receipt_authority_snapshot_hash", AUTHORITY_HASH);
    strings.put("summary_schema_version", summary.schemaVersion());
    strings.put("summary_hash", summary.summaryHash());
    strings.put("summary_authority_snapshot_hash", summary.authoritySnapshotHash());
    strings.put("summary_graph_thread_id", summary.graphThreadId());
    strings.put("summary_manifest_hash", summary.manifestHash());
    strings.put("summary_proposal_hash", summary.proposalHash());
    strings.put("summary_current_fact_ids_json", JSON.writeValueAsString(summary.currentFactIds()));
    strings.put("summary_current_source_refs_json", JSON.writeValueAsString(summary.currentSourceRefs()));
    strings.put("authority_graph_binding_id", authority.graphBindingId());
    strings.put("authority_runtime_mode", authority.runtimeMode());
    strings.put("authority_agent_profile_id", authority.agentProfileId());
    strings.put("authority_room_id", authority.roomId());
    strings.put("authority_actor_id", authority.actorId());
    strings.put("authority_actor_role", authority.actorRole());
    strings.put("authority_participant_id", authority.participantId());
    strings.put("authority_actor_scope_hash", authority.actorScopeHash());
    strings.put("authority_agent_session_id", authority.agentSessionId());
    strings.put("authority_current_fact_ids_json", JSON.writeValueAsString(authority.currentFactIds()));
    strings.put("authority_current_source_refs_json", JSON.writeValueAsString(authority.currentSourceRefs()));
    if (includeRecovery) {
      strings.put("recovery_room_type", "EVIDENCE");
      strings.put("recovery_runtime_mode", "SIGNED_SYNTHETIC_SHADOW");
    }

    Map<String, Long> longs = Map.ofEntries(
        Map.entry("receipt_room_epoch", receipt.roomEpoch()),
        Map.entry("receipt_fencing_token", receipt.fencingToken()),
        Map.entry("receipt_source_revision", receipt.sourceRevision()),
        Map.entry("receipt_process_revision", receipt.processRevision()),
        Map.entry("receipt_room_revision", receipt.roomRevision()),
        Map.entry("receipt_committed_at_epoch_second", COMMITTED_AT.getEpochSecond()),
        Map.entry("summary_graph_lease_fencing_token", summary.graphLeaseFencingToken()),
        Map.entry("summary_java_finalization_fencing_token", summary.javaFinalizationFencingToken()),
        Map.entry("summary_committed_at_epoch_second", COMMITTED_AT.getEpochSecond()));
    Map<String, Boolean> booleans = Map.of(
        "receipt_formal_domain_write", false,
        "receipt_formal_sink_eligible", false,
        "receipt_hearing_opened", false,
        "recovery_java_signed_synthetic", true,
        "recovery_formal_sink_eligible", false,
        "recovery_temporal_evidence_allocation", false);
    Map<String, Integer> integers = Map.of(
        "receipt_merge_count", receipt.mergeCount(),
        "receipt_committed_at_nano", COMMITTED_AT.getNano(),
        "summary_committed_at_nano", COMMITTED_AT.getNano());

    ResultSet row = mock(ResultSet.class);
    when(row.getString(anyString()))
        .thenAnswer(invocation -> strings.get(invocation.getArgument(0)));
    when(row.getLong(anyString()))
        .thenAnswer(invocation -> longs.getOrDefault(invocation.getArgument(0), 0L));
    when(row.getInt(anyString()))
        .thenAnswer(invocation -> integers.getOrDefault(invocation.getArgument(0), 0));
    when(row.getBoolean(anyString()))
        .thenAnswer(invocation -> booleans.getOrDefault(invocation.getArgument(0), false));
    return row;
  }
}
