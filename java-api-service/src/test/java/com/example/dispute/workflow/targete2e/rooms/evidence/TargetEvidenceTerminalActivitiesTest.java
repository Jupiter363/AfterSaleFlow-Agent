package com.example.dispute.workflow.targete2e.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class TargetEvidenceTerminalActivitiesTest {

  @Test
  void terminalRequestRejectsMissingOrUnfencedPartyCompletionCoordinates() {
    EvidenceRoomStart start = start();

    assertThatThrownBy(() -> new TargetEvidenceTerminalActivities.TerminalRequest(
        start, 10, 7, "", "COMPLETE_MERCHANT"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TargetRoomProgressReceipt(
        null, 4, 9, 11, 8, "receipt", "a".repeat(64)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void terminalReceiptGateCountsEveryAdmittedEvidenceMaterialAndFailsClosed() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(1L);
    JdbcTargetEvidenceTerminalActivities subject = new JdbcTargetEvidenceTerminalActivities(
        mock(javax.sql.DataSource.class), mock(TransactionTemplate.class),
        mock(EvidenceDossierFreezer.class), mock(RoomEpochAllocator.class),
        new ObjectMapper(), Clock.systemUTC());
    var request = new TargetEvidenceTerminalActivities.TerminalRequest(
        start(), 8, 5, "COMPLETE_USER", "COMPLETE_MERCHANT");

    assertThatThrownBy(() -> subject.requireAgentRunReceipts(connection, request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("all EVIDENCE_SUBMIT AgentRun formal receipts are required");
    verify(connection).prepareStatement(argThat(sql ->
        !sql.contains("command_type")
            && sql.contains("receipt.attempt_id")
            && sql.contains("receipt.command_hash = material.command_hash")
            && sql.contains("receipt.command_envelope_hash = material.command_envelope_hash")
            && sql.contains("receipt.checkpoint_schema_version")));
  }

  private static EvidenceRoomStart start() {
    return new EvidenceRoomStart(
        "evidence-room-start.v1", "tenant-e2e", "CASE_E2E", "ROOM_EVIDENCE", 4, 9,
        "USER_E2E", "MERCHANT_E2E", Instant.parse("2026-07-28T00:00:00Z"),
        Instant.parse("2026-07-28T01:00:00Z"), 1, 8, 5, "target-e2e-control");
  }
}
