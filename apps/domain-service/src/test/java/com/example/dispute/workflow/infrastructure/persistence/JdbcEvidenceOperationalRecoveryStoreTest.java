package com.example.dispute.workflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ActivityRequest;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.InvocationMode;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceFinalizationReceiptRef.OperationType;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationKeys;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore.JavaRecoveryAuthority;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryStore.RecoveryScope;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcEvidenceOperationalRecoveryStoreTest {

  @Test
  void receiptReadsAreDelegatedToTheC3BridgeRatherThanReparsedFromJdbcRows() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    JdbcEvidenceOperationalRecoveryStore.DurableReceiptReader reader = mock(
        JdbcEvidenceOperationalRecoveryStore.DurableReceiptReader.class);
    ActivityRequest request = activityRequest();
    when(reader.findCommitted(request)).thenReturn(Optional.empty());

    var store = new JdbcEvidenceOperationalRecoveryStore(jdbc, reader);

    assertThat(store.findCommitted(request)).isEmpty();
    verify(reader).findCommitted(request);
  }

  private static ActivityRequest activityRequest() {
    String caseId = "CASE_P5_SYNTHETIC_ACTIVITY";
    String manifestHash = "a".repeat(64);
    return new ActivityRequest(
        "evidence-activity-request.v1", OperationType.GRAPH_REQUEST,
        "TENANT_P5_SYNTHETIC_ACTIVITY", caseId, 7, 11, manifestHash, 4, 6,
        EvidenceOperationKeys.graphRequest(caseId, 7, manifestHash, "RUN_P5_ACTIVITY"),
        "f".repeat(64), InvocationMode.RETRY_RECONCILE_ONLY);
  }

  @Test
  @SuppressWarnings("unchecked")
  void onlyReadsTheSingleCurrentJavaAuthorityRow() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    JdbcEvidenceOperationalRecoveryStore.DurableReceiptReader reader = ignored -> Optional.empty();
    JavaRecoveryAuthority authority = new JavaRecoveryAuthority(
        "TENANT_P5_SYNTHETIC_ACTIVITY", "CASE_P5_SYNTHETIC_ACTIVITY", "ROOM_P5_EVIDENCE_1",
        "EVIDENCE", "2".repeat(64), 7, 11, 3, 4, 6,
        "SIGNED_SYNTHETIC_SHADOW", true, false, false);
    when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(authority));
    var store = new JdbcEvidenceOperationalRecoveryStore(jdbc, reader);
    RecoveryScope scope = new RecoveryScope(
        "TENANT_P5_SYNTHETIC_ACTIVITY", "CASE_P5_SYNTHETIC_ACTIVITY", 7, 11, 3, 4, 6,
        "2".repeat(64));

    assertThat(store.findCurrentJavaAuthority(scope)).contains(authority);
    verify(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void bindsRecoveryToTheCurrentAuthoritySnapshotAndRequestedRoomFence() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    JdbcEvidenceOperationalRecoveryStore.DurableReceiptReader reader = ignored -> Optional.empty();
    RecoveryScope scope = new RecoveryScope(
        "TENANT_P5_SYNTHETIC_ACTIVITY", "CASE_P5_SYNTHETIC_ACTIVITY", 7, 11, 3, 4, 6,
        "2".repeat(64));
    when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());
    var store = new JdbcEvidenceOperationalRecoveryStore(jdbc, reader);

    assertThat(store.findCurrentJavaAuthority(scope)).isEmpty();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<MapSqlParameterSource> parameters = ArgumentCaptor.forClass(
        MapSqlParameterSource.class);
    verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
    assertThat(sql.getValue())
        .contains("join case_evidence_current_authority_snapshot authority")
        .contains("authority.authority_snapshot_hash = recovery.authority_snapshot_hash")
        .contains("authority.tenant_surrogate = recovery.tenant_surrogate")
        .contains("authority.case_id = recovery.case_id")
        .contains("authority.room_id = recovery.room_id")
        .contains("authority.room_epoch = recovery.room_epoch")
        .contains("authority.java_room_fencing_token = recovery.java_room_fencing_token")
        .contains("authority.source_revision = recovery.source_revision")
        .contains("authority.process_revision = recovery.process_revision")
        .contains("authority.room_revision = recovery.room_revision")
        .contains("recovery.room_epoch = :roomEpoch")
        .contains("recovery.java_room_fencing_token = :javaRoomFencingToken")
        .contains("recovery.source_revision = :sourceRevision")
        .contains("recovery.process_revision = :processRevision")
        .contains("recovery.room_revision = :roomRevision")
        .contains("recovery.is_current = true")
        .contains("authority.is_current = true");
    assertThat(parameters.getValue().getValues())
        .containsEntry("tenantSurrogate", scope.tenantSurrogate())
        .containsEntry("caseId", scope.caseId())
        .containsEntry("roomEpoch", scope.roomEpoch())
        .containsEntry("javaRoomFencingToken", scope.javaRoomFencingToken())
        .containsEntry("sourceRevision", scope.sourceRevision())
        .containsEntry("processRevision", scope.processRevision())
        .containsEntry("roomRevision", scope.roomRevision())
        .containsEntry("authoritySnapshotHash", scope.authoritySnapshotHash());
  }
}
