package com.example.dispute.evidence.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.evidence.application.graph.EvidenceAssetAuthorization.ActualLoadReceipt;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseAuthority;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.ActualLoadRequirement;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.AuthorityRequirement;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.CommitRequest;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt.BatchMergeBinding;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcEvidenceFinalizationReceiptLedgerTest {

  private static final Instant COMMITTED_AT = Instant.parse("2026-07-23T10:15:30Z");
  private static final Instant NANO_COMMITTED_AT =
      Instant.parse("2026-07-23T10:15:30.123456789Z");
  private static final String REQUEST_HASH = "b".repeat(64);
  private static final String RESULT_HASH = "c".repeat(64);
  private static final String AUTHORITY_HASH = "d".repeat(64);
  private static final String GRAPH_BINDING_ID = "EVIDENCE_GRAPH_BINDING_P5_1";
  private static final String ACTOR_SCOPE_HASH = "e".repeat(64);
  private static final String MANIFEST_HASH = "a".repeat(64);
  private static final String PROPOSAL_HASH = "f".repeat(64);

  @Mock private NamedParameterJdbcTemplate jdbc;
  @Mock private TransactionTemplate transactions;
  @Mock private TransactionStatus transactionStatus;
  @Mock private GraphLeaseAuthority graphLeaseAuthority;

  private JdbcEvidenceFinalizationReceiptLedger ledger;

  @BeforeEach
  void setUp() {
    ledger = new JdbcEvidenceFinalizationReceiptLedger(jdbc, transactions, graphLeaseAuthority);
    lenient().when(transactions.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(transactionStatus);
            });
  }

  @Test
  void exactCommittedReplayWinsBeforeAuthorityAndGraphLeaseChecks() {
    EvidenceFinalizationReceipt existing = receipt(COMMITTED_AT, REQUEST_HASH);
    EvidenceFinalizationReceipt retry = receipt(COMMITTED_AT.plusSeconds(20), REQUEST_HASH);
    when(jdbc.query(
            contains("case_evidence_finalization_receipt where"),
            anyMap(),
            any(RowMapper.class)))
        .thenReturn(List.of(existing));

    EvidenceFinalizationReceipt replayed =
        ledger.commitOrReplay(new CommitRequest(retry, requirement(7, List.of(loadRequirement()))));

    assertThat(replayed).isSameAs(existing);
    verify(jdbc, never()).query(contains("current_authority_snapshot"), anyMap(), any(RowMapper.class));
    verify(jdbc, never()).query(contains("asset_load_receipt"), anyMap(), any(RowMapper.class));
    verify(graphLeaseAuthority, never()).requireCurrent(any());
    verify(jdbc, never()).update(contains("insert into case_evidence_finalization_receipt"), anyMap());
  }

  @Test
  void nanosecondCommittedAtSurvivesCommitResponseLossReplayWithoutChangingReceiptHash()
      throws SQLException {
    EvidenceFinalizationReceipt candidate = receipt(NANO_COMMITTED_AT, REQUEST_HASH);
    AuthorityRequirement requirement = requirement(7, List.of(loadRequirement()));
    ResultSet persistedRow = persistedReceiptRow(candidate);
    AtomicInteger receiptQueries = new AtomicInteger();
    when(jdbc.query(
            contains("case_evidence_finalization_receipt where"),
            anyMap(),
            any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              if (receiptQueries.getAndIncrement() < 2) {
                return List.of();
              }
              RowMapper<EvidenceFinalizationReceipt> mapper = invocation.getArgument(2);
              return List.of(mapper.mapRow(persistedRow, 0));
            });
    when(jdbc.queryForObject(contains("pg_advisory_xact_lock"), anyMap(), any(Class.class)))
        .thenReturn(new Object());
    when(jdbc.query(
            contains("case_evidence_current_authority_snapshot"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenReturn(List.of(authority(requirement)));
    when(jdbc.query(
            contains("case_evidence_asset_load_receipt load_receipt"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenReturn(List.of(actualLoad("LOAD_RECEIPT_1", MANIFEST_HASH, 7, 7001)));
    when(jdbc.queryForObject(contains("finalization_fencing_token_seq"), anyMap(), any(Class.class)))
        .thenReturn(9001L);
    when(jdbc.update(
            contains("insert into case_evidence_finalization_receipt ("),
            any(MapSqlParameterSource.class)))
        .thenReturn(1);
    when(jdbc.update(
            contains("insert into case_evidence_finalization_receipt_load_binding"),
            any(MapSqlParameterSource.class)))
        .thenReturn(1);
    when(jdbc.update(
            contains("insert into case_evidence_terminal_summary"),
            any(MapSqlParameterSource.class)))
        .thenReturn(1);

    EvidenceFinalizationReceipt committed =
        ledger.commitOrReplay(new CommitRequest(candidate, requirement));
    EvidenceFinalizationReceipt replayed =
        ledger.commitOrReplay(
            new CommitRequest(
                receipt(NANO_COMMITTED_AT.plusSeconds(20), REQUEST_HASH), requirement));

    assertThat(committed).isSameAs(candidate);
    assertThat(replayed.committedAt()).isEqualTo(NANO_COMMITTED_AT);
    assertThat(replayed.receiptHash()).isEqualTo(candidate.receiptHash());
    ArgumentCaptor<MapSqlParameterSource> receiptParameters =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(jdbc)
        .update(
            contains("insert into case_evidence_finalization_receipt ("),
            receiptParameters.capture());
    assertThat(receiptParameters.getValue().getValue("committedAtEpochSecond"))
        .isEqualTo(NANO_COMMITTED_AT.getEpochSecond());
    assertThat(receiptParameters.getValue().getValue("committedAtNano"))
        .isEqualTo(NANO_COMMITTED_AT.getNano());
    assertThat(((OffsetDateTime) receiptParameters.getValue().getValue("committedAt")).toInstant())
        .isEqualTo(NANO_COMMITTED_AT.truncatedTo(ChronoUnit.MICROS));
    assertThat(receiptParameters.getValue().getValue("roomId"))
        .isEqualTo("ROOM_P5_SYNTHETIC_1");
    assertThat(receiptParameters.getValue().getValue("graphBindingId"))
        .isEqualTo(GRAPH_BINDING_ID);
  }

  @Test
  void migrationEnforcesCompleteAuthorityAndExactTimestampScope() throws IOException {
    String migration = migrationSql();

    assertThat(migration)
        .contains(
            "committed_at_epoch_second bigint not null",
            "committed_at_nano integer not null",
            "create or replace function enforce_evidence_finalization_receipt_authority()",
            "or authority.tenant_surrogate is distinct from new.tenant_surrogate",
            "or authority.case_id is distinct from new.case_id",
            "or authority.room_id is distinct from new.room_id",
            "or authority.room_epoch is distinct from new.room_epoch",
            "or authority.java_room_fencing_token is distinct from new.fencing_token",
            "or authority.source_revision is distinct from new.source_revision",
            "or authority.process_revision is distinct from new.process_revision",
            "or authority.room_revision is distinct from new.room_revision",
            "or authority.graph_binding_id is distinct from new.graph_binding_id",
            "from case_room_epoch",
            "and room_type = 'EVIDENCE'",
            "or java_room.room_id is distinct from new.room_id",
            "or java_room.process_revision is distinct from new.process_revision",
            "or java_room.room_revision is distinct from new.room_revision",
            "or not authority.is_current",
            "or authority.runtime_mode is distinct from new.runtime_mode",
            "or not new.java_signed_synthetic",
            "or new.formal_sink_eligible",
            "or new.temporal_evidence_allocation",
            "is distinct from new.operation_binding_json ->> 'thread_id'",
            "is distinct from new.operation_binding_json ->> 'manifest_hash'",
            "create or replace function enforce_evidence_operational_recovery_authority()",
            "is distinct from new.java_room_fencing_token",
            "or authority.graph_binding_id is distinct from new.graph_binding_id",
            "or actual_load.graph_binding_id is distinct from finalization.graph_binding_id",
            "after insert or update on case_evidence_operational_recovery");
  }

  @Test
  void sameOperationKeyWithDifferentRequestHashConflictsBeforeAuthorityLookup() {
    EvidenceFinalizationReceipt existing = receipt(COMMITTED_AT, REQUEST_HASH);
    EvidenceFinalizationReceipt conflicting = receipt(COMMITTED_AT, "1".repeat(64));
    when(jdbc.query(
            contains("case_evidence_finalization_receipt where"),
            anyMap(),
            any(RowMapper.class)))
        .thenReturn(List.of(existing));

    assertThatThrownBy(
            () ->
                ledger.commitOrReplay(
                    new CommitRequest(conflicting, requirement(7, List.of(loadRequirement())))))
        .isInstanceOf(EvidenceFinalizationLedger.IdempotencyConflictException.class)
        .hasMessageContaining("different canonical request");
    verify(jdbc, never()).query(contains("current_authority_snapshot"), anyMap(), any(RowMapper.class));
  }

  @Test
  void newReceiptLocksAuthorityAndActualLoadThenCommitsReceiptAndSummaryAtomically() {
    EvidenceFinalizationReceipt candidate = receipt(COMMITTED_AT, REQUEST_HASH);
    AuthorityRequirement requirement = requirement(7, List.of(loadRequirement()));
    stubMissingReceiptTwice();
    when(jdbc.queryForObject(contains("pg_advisory_xact_lock"), anyMap(), any(Class.class)))
        .thenReturn(new Object());
    when(jdbc.query(
            contains("case_evidence_current_authority_snapshot"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenReturn(List.of(authority(requirement)));
    when(jdbc.query(
            contains("case_evidence_asset_load_receipt load_receipt"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenReturn(List.of(actualLoad("LOAD_RECEIPT_1", MANIFEST_HASH, 7, 7001)));
    when(jdbc.queryForObject(contains("finalization_fencing_token_seq"), anyMap(), any(Class.class)))
        .thenReturn(9001L);
    when(jdbc.update(contains("insert into case_evidence_finalization_receipt"), any(MapSqlParameterSource.class)))
        .thenReturn(1);
    when(jdbc.update(
            contains("insert into case_evidence_finalization_receipt_load_binding"),
            any(MapSqlParameterSource.class)))
        .thenReturn(1);
    when(jdbc.update(contains("insert into case_evidence_terminal_summary"), any(MapSqlParameterSource.class)))
        .thenReturn(1);

    EvidenceFinalizationReceipt committed =
        ledger.commitOrReplay(new CommitRequest(candidate, requirement));

    assertThat(committed).isSameAs(candidate);
    ArgumentCaptor<MapSqlParameterSource> summaryParameters =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(jdbc)
        .update(contains("insert into case_evidence_terminal_summary"), summaryParameters.capture());
    assertThat(summaryParameters.getValue().getValue("graphThreadId"))
        .isEqualTo("grt.v1.018f6b7ec30a7430982fffc520c8195c");
    assertThat(summaryParameters.getValue().getValue("javaRoomFencingToken")).isEqualTo(7L);
    assertThat(summaryParameters.getValue().getValue("graphLeaseFencingToken")).isEqualTo(7001L);
    assertThat(summaryParameters.getValue().getValue("javaFinalizationFencingToken"))
        .isEqualTo(9001L);
    verify(graphLeaseAuthority).requireCurrent(any());
  }

  @Test
  void takeoverAndForeignCallerAllowlistsFailBeforeAnyInsert() {
    EvidenceFinalizationReceipt candidate = receipt(COMMITTED_AT, REQUEST_HASH);
    AuthorityRequirement requirement = requirement(7, List.of(loadRequirement()));
    stubMissingReceiptTwice();
    when(jdbc.queryForObject(contains("pg_advisory_xact_lock"), anyMap(), any(Class.class)))
        .thenReturn(new Object());
    when(jdbc.query(
            contains("case_evidence_current_authority_snapshot"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenReturn(List.of(authority(requirement, 8, List.of("FACT_FOREIGN"))));

    assertThatThrownBy(() -> ledger.commitOrReplay(new CommitRequest(candidate, requirement)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("EVIDENCE_AUTHORITY_CHANGED_BEFORE_COMMIT");
    verify(jdbc, never()).query(contains("asset_load_receipt"), anyMap(), any(RowMapper.class));
    verify(jdbc, never()).update(contains("insert into case_evidence_finalization_receipt"), anyMap());
  }

  @Test
  void forgedSelfHashedOrForeignActualLoadReceiptFailsBeforeInsert() {
    EvidenceFinalizationReceipt candidate = receipt(COMMITTED_AT, REQUEST_HASH);
    AuthorityRequirement requirement = requirement(7, List.of(loadRequirement()));
    stubMissingReceiptTwice();
    when(jdbc.queryForObject(contains("pg_advisory_xact_lock"), anyMap(), any(Class.class)))
        .thenReturn(new Object());
    when(jdbc.query(
            contains("case_evidence_current_authority_snapshot"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenReturn(List.of(authority(requirement)));
    ActualLoadReceipt forgedButCanonical =
        actualLoad("LOAD_RECEIPT_FORGED", "2".repeat(64), 7, 7001);
    when(jdbc.query(
            contains("case_evidence_asset_load_receipt load_receipt"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenReturn(List.of(forgedButCanonical));

    assertThatThrownBy(() -> ledger.commitOrReplay(new CommitRequest(candidate, requirement)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("EVIDENCE_ACTUAL_LOAD_RECEIPT_CONFLICT");
    verify(jdbc, never()).update(contains("insert into case_evidence_finalization_receipt"), anyMap());
  }

  @Test
  void mixedGraphLeaseFencesCannotProduceATerminalSummary() {
    EvidenceFinalizationReceipt candidate = receipt(COMMITTED_AT, REQUEST_HASH);
    List<ActualLoadRequirement> loads =
        List.of(
            loadRequirement(),
            loadRequirement("EVIDENCE_SYNTH_002", "LOAD_RECEIPT_2", 7002));
    AuthorityRequirement requirement = requirement(7, loads);
    stubMissingReceiptTwice();
    when(jdbc.queryForObject(contains("pg_advisory_xact_lock"), anyMap(), any(Class.class)))
        .thenReturn(new Object());
    when(jdbc.query(
            contains("case_evidence_current_authority_snapshot"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenReturn(List.of(authority(requirement)));
    AtomicInteger query = new AtomicInteger();
    when(jdbc.query(
            contains("case_evidence_asset_load_receipt load_receipt"),
            any(MapSqlParameterSource.class),
            any(RowMapper.class)))
        .thenAnswer(
            ignored -> {
              int index = query.getAndIncrement();
              return List.of(
                  actualLoad(
                      index == 0 ? "LOAD_RECEIPT_1" : "LOAD_RECEIPT_2",
                      MANIFEST_HASH,
                      7,
                      index == 0 ? 7001 : 7002,
                      index == 0 ? "EVIDENCE_SYNTH_001" : "EVIDENCE_SYNTH_002"));
            });

    assertThatThrownBy(() -> ledger.commitOrReplay(new CommitRequest(candidate, requirement)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("EVIDENCE_GRAPH_LEASE_FENCE_MIXED_OR_STALE");
  }

  private void stubMissingReceiptTwice() {
    when(jdbc.query(
            contains("case_evidence_finalization_receipt where"),
            anyMap(),
            any(RowMapper.class)))
        .thenReturn(List.of(), List.of());
  }

  private static ResultSet persistedReceiptRow(EvidenceFinalizationReceipt receipt)
      throws SQLException {
    ResultSet row = mock(ResultSet.class);
    when(row.getString("schema_version")).thenReturn(receipt.schemaVersion());
    when(row.getString("receipt_id")).thenReturn(receipt.receiptId());
    when(row.getString("receipt_hash")).thenReturn(receipt.receiptHash());
    when(row.getString("operation_type")).thenReturn(receipt.operationType().name());
    when(row.getString("operation_key")).thenReturn(receipt.operationKey());
    when(row.getString("request_hash")).thenReturn(receipt.requestHash());
    when(row.getString("result_hash")).thenReturn(receipt.resultHash());
    when(row.getString("commit_scope")).thenReturn(receipt.commitScope());
    when(row.getString("status")).thenReturn(receipt.status());
    when(row.getBoolean("formal_domain_write")).thenReturn(receipt.formalDomainWrite());
    when(row.getBoolean("formal_sink_eligible")).thenReturn(receipt.formalSinkEligible());
    when(row.getString("tenant_surrogate")).thenReturn(receipt.tenantSurrogate());
    when(row.getString("case_id")).thenReturn(receipt.caseId());
    when(row.getLong("room_epoch")).thenReturn(receipt.roomEpoch());
    when(row.getLong("fencing_token")).thenReturn(receipt.fencingToken());
    when(row.getLong("source_revision")).thenReturn(receipt.sourceRevision());
    when(row.getLong("process_revision")).thenReturn(receipt.processRevision());
    when(row.getLong("room_revision")).thenReturn(receipt.roomRevision());
    when(row.getString("operation_binding_json"))
        .thenReturn(receipt.operationBinding().toContractJson().toString());
    when(row.getInt("merge_count")).thenReturn(receipt.mergeCount());
    when(row.getString("domain_event_ids_json")).thenReturn("[]");
    when(row.getString("outbox_ids_json")).thenReturn("[]");
    when(row.getBoolean("hearing_opened")).thenReturn(receipt.hearingOpened());
    when(row.getLong("committed_at_epoch_second"))
        .thenReturn(receipt.committedAt().getEpochSecond());
    when(row.getInt("committed_at_nano")).thenReturn(receipt.committedAt().getNano());
    return row;
  }

  private static String migrationSql() throws IOException {
    try (InputStream input =
        Objects.requireNonNull(
            JdbcEvidenceFinalizationReceiptLedgerTest.class.getResourceAsStream(
                "/db/migration/V043_5__evidence_finalization_and_operational_recovery.sql"))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static EvidenceFinalizationReceipt receipt(Instant committedAt, String requestHash) {
    BatchMergeBinding binding =
        new BatchMergeBinding(
            MANIFEST_HASH,
            1,
            PROPOSAL_HASH,
            "LOGICAL_RUN_1",
            "COMMAND_1",
            "ATTEMPT_1",
            "grt.v1.018f6b7ec30a7430982fffc520c8195c");
    return EvidenceFinalizationReceipt.committedSyntheticBatchMerge(
        "RECEIPT_" + requestHash.substring(0, 32),
        requestHash,
        RESULT_HASH,
        "TENANT_P5_SYNTHETIC_1",
        "CASE_P5_SYNTHETIC_1",
        1,
        7,
        3,
        4,
        5,
        binding,
        committedAt);
  }

  private static AuthorityRequirement requirement(
      long javaFence, List<ActualLoadRequirement> loads) {
    return new AuthorityRequirement(
        AUTHORITY_HASH,
        "SIGNED_SYNTHETIC_SHADOW",
        "evidence-clerk.v2",
        "TENANT_P5_SYNTHETIC_1",
        "CASE_P5_SYNTHETIC_1",
        "ROOM_P5_SYNTHETIC_1",
        1,
        javaFence,
        "USER_P5_SYNTHETIC_1",
        "USER",
        "PARTICIPANT_P5_SYNTHETIC_1",
        ACTOR_SCOPE_HASH,
        "AGENT_SESSION_P5_1",
        3,
        4,
        5,
        List.of("FACT_ORDER_DAMAGE"),
        List.of("SOURCE_SYNTHETIC_001"),
        loads);
  }

  private static EvidenceCurrentAuthoritySnapshot authority(AuthorityRequirement requirement) {
    return EvidenceCurrentAuthoritySnapshot.from(requirement, GRAPH_BINDING_ID);
  }

  private static EvidenceCurrentAuthoritySnapshot authority(
      AuthorityRequirement requirement, long javaFence, List<String> facts) {
    EvidenceCurrentAuthoritySnapshot source =
        EvidenceCurrentAuthoritySnapshot.from(requirement, GRAPH_BINDING_ID);
    return new EvidenceCurrentAuthoritySnapshot(
        source.authoritySnapshotHash(),
        source.graphBindingId(),
        source.runtimeMode(),
        source.agentProfileId(),
        source.tenantSurrogate(),
        source.caseId(),
        source.roomId(),
        source.roomEpoch(),
        javaFence,
        source.actorId(),
        source.actorRole(),
        source.participantId(),
        source.actorScopeHash(),
        source.agentSessionId(),
        source.sourceRevision(),
        source.processRevision(),
        source.roomRevision(),
        facts,
        source.currentSourceRefs());
  }

  private static ActualLoadRequirement loadRequirement() {
    return loadRequirement("EVIDENCE_SYNTH_001", "LOAD_RECEIPT_1");
  }

  private static ActualLoadRequirement loadRequirement(String evidenceId, String receiptId) {
    return loadRequirement(evidenceId, receiptId, 7001);
  }

  private static ActualLoadRequirement loadRequirement(
      String evidenceId, String receiptId, long graphFence) {
    ActualLoadReceipt receipt = actualLoad(receiptId, MANIFEST_HASH, 7, graphFence, evidenceId);
    return new ActualLoadRequirement(
        receipt.evidenceId(),
        receipt.itemHash(),
        receipt.receiptId(),
        receipt.receiptHash(),
        receipt.manifestHash(),
        receipt.javaRoomFencingToken());
  }

  private static ActualLoadReceipt actualLoad(
      String receiptId, String manifestHash, long javaFence, long graphFence) {
    return actualLoad(receiptId, manifestHash, javaFence, graphFence, "EVIDENCE_SYNTH_001");
  }

  private static ActualLoadReceipt actualLoad(
      String receiptId,
      String manifestHash,
      long javaFence,
      long graphFence,
      String evidenceId) {
    String capabilityId = "CAPABILITY_" + receiptId;
    String capabilityHash = Integer.toHexString(receiptId.hashCode() & 0xffff) + "3".repeat(60);
    capabilityHash = (capabilityHash + "3".repeat(64)).substring(0, 64);
    String nonce = "NONCE_" + receiptId;
    String itemHash = evidenceId.endsWith("2") ? "4".repeat(64) : "1".repeat(64);
    List<String> modalities = List.of("PDF_METADATA", "TEXT");
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("receipt_id", receiptId);
    value.put("capability_id", capabilityId);
    value.put("capability_hash", capabilityHash);
    value.put("capability_nonce", nonce);
    value.put("manifest_id", "MANIFEST_P5_SYNTHETIC_ONE");
    value.put("manifest_hash", manifestHash);
    value.put("evidence_id", evidenceId);
    value.put("item_hash", itemHash);
    value.put("object_ref", "urn:synthetic-evidence:fixture-100/" + evidenceId);
    value.put("immutable_object_version", "OBJECT_VERSION_001");
    value.put("object_sha256", "2".repeat(64));
    value.put("content_type", "application/pdf");
    value.put("byte_size", 1025);
    value.put("java_room_fencing_token", javaFence);
    value.put("graph_lease_fencing_token", graphFence);
    value.put("load_status", "LOADED");
    value.putPOJO("loaded_modalities", modalities);
    value.put("loaded_at", COMMITTED_AT.toString());
    String receiptHash = ContractJson.sha256Hex(value);
    return new ActualLoadReceipt(
        receiptId,
        receiptHash,
        capabilityId,
        capabilityHash,
        nonce,
        "MANIFEST_P5_SYNTHETIC_ONE",
        manifestHash,
        evidenceId,
        itemHash,
        "urn:synthetic-evidence:fixture-100/" + evidenceId,
        "OBJECT_VERSION_001",
        "2".repeat(64),
        "application/pdf",
        1025,
        javaFence,
        graphFence,
        "LOADED",
        modalities,
        COMMITTED_AT);
  }
}
