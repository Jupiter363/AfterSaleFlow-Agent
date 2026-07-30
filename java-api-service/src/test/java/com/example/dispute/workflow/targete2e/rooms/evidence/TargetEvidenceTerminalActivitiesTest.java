package com.example.dispute.workflow.targete2e.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart.ExecutionLane;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class TargetEvidenceTerminalActivitiesTest {

  private static final String WORKFLOW_RUN_ID = "evidence-run-1";
  private static final String USER_COMPLETION_ID = "EVIDENCE_COMPLETE_USER";
  private static final String MERCHANT_COMPLETION_ID = "EVIDENCE_COMPLETE_MERCHANT";
  private static final String USER_COMMAND_ID = "evidence-complete:" + USER_COMPLETION_ID;
  private static final String MERCHANT_COMMAND_ID =
      "evidence-complete:" + MERCHANT_COMPLETION_ID;

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
  void terminalActivationLifecycleIsQueryOnlyAfterDrainOrRevocation() {
    assertThat(JdbcTargetEvidenceTerminalActivities.validActivationLifecycle("ACTIVE", false))
        .isTrue();
    assertThat(
            JdbcTargetEvidenceTerminalActivities.validActivationLifecycle("DRAIN_ONLY", false))
        .isTrue();
    assertThat(JdbcTargetEvidenceTerminalActivities.validActivationLifecycle("DRAINED", true))
        .isTrue();
    assertThat(
            JdbcTargetEvidenceTerminalActivities.validActivationLifecycle(
                "REVOKED_TERMINAL", true))
        .isTrue();
    assertThat(JdbcTargetEvidenceTerminalActivities.validActivationLifecycle("DRAINED", false))
        .isFalse();
  }

  @Test
  void rejectsNonCanonicalWorkflowIdAndFrozenRunIdDriftBeforeJdbcAuthority() {
    var request = request();
    String canonical = canonicalWorkflowId();

    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireWorkflowIdentity(
                    request, "forged-evidence-workflow", WORKFLOW_RUN_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical room workflow");
    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireWorkflowIdentity(
                    request, canonical, "wrong-run"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity drifted");
  }

  @Test
  void locksExactTargetEpochAuthorityBeforeTerminalWrites() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, false);
    when(row.getString(1)).thenReturn("epoch-evidence-4");
    when(row.getString(2)).thenReturn("ACTIVE");
    when(row.getLong(3)).thenReturn(8L);
    when(row.getLong(4)).thenReturn(5L);
    when(row.getString(5)).thenReturn("p9act.v1." + "a".repeat(32));
    when(row.getString(6)).thenReturn("ACTIVE");
    when(row.getBoolean(7)).thenReturn(true);
    var request = request();
    var identity =
        JdbcTargetEvidenceTerminalActivities.requireWorkflowIdentity(
            request, canonicalWorkflowId(), WORKFLOW_RUN_ID);

    var authority =
        JdbcTargetEvidenceTerminalActivities.lockAuthority(connection, request, identity);

    assertThat(authority.lifecycleStatus()).isEqualTo("ACTIVE");
    assertThatCode(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    authority, request, false))
        .doesNotThrowAnyException();
    verify(connection)
        .prepareStatement(
            argThat(
                sql ->
                    sql.contains("epoch.room_temporal_workflow_id = ?")
                        && sql.contains("epoch.room_temporal_run_id = ?")
                        && sql.contains("epoch.room_workflow_build_id = ?")
                        && sql.contains("binding.execution_lane = 'TARGET_E2E_CANDIDATE'")
                        && sql.contains("activation.execution_lane = 'TARGET_E2E_CANDIDATE'")
                        && sql.contains("activation.lifecycle_status in (")
                        && sql.contains("'ACTIVE', 'DRAIN_ONLY', 'DRAINED', 'REVOKED_TERMINAL'")
                        && sql.contains("as accepts_new_write")
                        && sql.contains("for update of epoch")));
  }

  @Test
  void locksBothAppliedCommandsInDeterministicOrderBeforeEpochAuthority() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, true, false);
    when(row.getString(1)).thenReturn(MERCHANT_COMMAND_ID, USER_COMMAND_ID);

    assertThatCode(
            () ->
                JdbcTargetEvidenceTerminalActivities.lockCompletionCommands(
                    connection, request()))
        .doesNotThrowAnyException();
    verify(connection)
        .prepareStatement(
            argThat(
                sql ->
                    sql.contains("command_status = 'APPLIED'")
                        && sql.contains("order by command_id")
                        && sql.contains("for update")));
  }

  @Test
  void storedReplayAllowsExpiredActiveActivationButRequiresExactTerminalCoordinates() {
    var request = request();

    assertThatCode(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    new JdbcTargetEvidenceTerminalActivities.Authority(
                        "epoch-evidence-4",
                        "TERMINAL",
                        9,
                        6,
                        "p9act.v1." + "a".repeat(32),
                        "ACTIVE",
                        false),
                    request,
                    true))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    new JdbcTargetEvidenceTerminalActivities.Authority(
                        "epoch-evidence-4",
                        "ACTIVE",
                        9,
                        6,
                        "p9act.v1." + "a".repeat(32),
                        "ACTIVE",
                        false),
                    request,
                    true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("coordinates drifted");
  }

  @Test
  void expiredActiveActivationAllowsOnlyStoredTerminalReplay() {
    var request = request();
    var expiredActiveNewWrite =
        new JdbcTargetEvidenceTerminalActivities.Authority(
            "epoch-evidence-4",
            "ACTIVE",
            request.expectedProcessRevision(),
            request.expectedRoomRevision(),
            "p9act.v1." + "a".repeat(32),
            "ACTIVE",
            false);

    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    expiredActiveNewWrite, request, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("coordinates drifted");

    assertThatCode(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    new JdbcTargetEvidenceTerminalActivities.Authority(
                        "epoch-evidence-4",
                        "ACTIVE",
                        request.expectedProcessRevision(),
                        request.expectedRoomRevision(),
                        "p9act.v1." + "a".repeat(32),
                        "DRAIN_ONLY",
                        true),
                    request,
                    false))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesTerminalizationWithoutTwoCommandAppliedCompletionFacts() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(false);
    JdbcTargetEvidenceTerminalActivities subject = subject();

    assertThatThrownBy(
            () ->
                subject.requirePersistedCompletionFactsAfterCommandLock(
                    connection, request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("two durable party completions");
    verify(connection, never())
        .prepareStatement(argThat(sql -> sql.toLowerCase().contains("insert")));
  }

  @Test
  void acceptsOnlyExactDurablePartyCompletionRowsFromTheCommandLane() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, true, false);
    when(row.getString(1))
        .thenReturn(USER_COMPLETION_ID, MERCHANT_COMPLETION_ID);
    when(row.getString(2)).thenReturn(USER_COMMAND_ID, MERCHANT_COMMAND_ID);
    when(row.getInt(3)).thenReturn(3, 3);
    when(row.getString(4)).thenReturn("USER", "MERCHANT");
    when(row.getString(5)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(6)).thenReturn("COMPLETED", "COMPLETED");
    when(row.getString(7)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(8)).thenReturn("a".repeat(64), "b".repeat(64));
    when(row.getLong(9)).thenReturn(6L, 7L);
    when(row.getString(10))
        .thenReturn(
            partyCompletionResultHash(USER_COMPLETION_ID, "a".repeat(64), 6, 3),
            partyCompletionResultHash(MERCHANT_COMPLETION_ID, "b".repeat(64), 7, 4));

    assertThatCode(
            () ->
                subject()
                    .requirePersistedCompletionFactsAfterCommandLock(
                        connection, request()))
        .doesNotThrowAnyException();
    verify(connection)
        .prepareStatement(
            argThat(
                sql ->
                    sql.contains("join case_command command_row")
                        && sql.contains(
                            "command_row.command_id = 'evidence-complete:' || completion.id")
                        && sql.contains("command_row.command_status = 'APPLIED'")
                        && sql.contains("command_row.result_uri = ? || completion.id")
                        && sql.contains("for update of completion, command_row, participant")
                        && !sql.toLowerCase().contains("insert into evidence_party_completion")));
    verify(statement)
        .setString(3, JdbcTargetEvidencePartyCompletionActivities.RESULT_URI_PREFIX);
  }

  @Test
  void rejectsParticipantOrIdempotencyDriftInDurableCompletionFacts() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, true, false);
    when(row.getString(1))
        .thenReturn(USER_COMPLETION_ID, MERCHANT_COMPLETION_ID);
    when(row.getString(2)).thenReturn(USER_COMMAND_ID, MERCHANT_COMMAND_ID);
    when(row.getInt(3)).thenReturn(3, 3);
    when(row.getString(4)).thenReturn("USER", "MERCHANT");
    when(row.getString(5)).thenReturn("FORGED_USER", "MERCHANT_E2E");
    when(row.getString(6)).thenReturn("COMPLETED", "COMPLETED");
    when(row.getString(7)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(8)).thenReturn("a".repeat(64), "b".repeat(64));
    when(row.getLong(9)).thenReturn(6L, 7L);
    when(row.getString(10))
        .thenReturn(
            partyCompletionResultHash(USER_COMPLETION_ID, "a".repeat(64), 6, 3),
            partyCompletionResultHash(MERCHANT_COMPLETION_ID, "b".repeat(64), 7, 4));

    assertThatThrownBy(
            () ->
                subject()
                    .requirePersistedCompletionFactsAfterCommandLock(
                        connection, request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("completion drifted");
  }

  @Test
  void rejectsTamperedPartyCommandResultHash() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, true, false);
    when(row.getString(1))
        .thenReturn(USER_COMPLETION_ID, MERCHANT_COMPLETION_ID);
    when(row.getString(2)).thenReturn(USER_COMMAND_ID, MERCHANT_COMMAND_ID);
    when(row.getInt(3)).thenReturn(3, 3);
    when(row.getString(4)).thenReturn("USER", "MERCHANT");
    when(row.getString(5)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(6)).thenReturn("COMPLETED", "COMPLETED");
    when(row.getString(7)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(8)).thenReturn("a".repeat(64), "b".repeat(64));
    when(row.getLong(9)).thenReturn(6L, 7L);
    when(row.getString(10))
        .thenReturn(
            "f".repeat(64),
            partyCompletionResultHash(MERCHANT_COMPLETION_ID, "b".repeat(64), 7, 4));

    assertThatThrownBy(
            () ->
                subject()
                    .requirePersistedCompletionFactsAfterCommandLock(
                        connection, request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("completion drifted");
  }

  @Test
  void rejectsIdempotencyKeyDriftInDurableCompletionFacts() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, true, false);
    when(row.getString(1))
        .thenReturn(USER_COMPLETION_ID, MERCHANT_COMPLETION_ID);
    when(row.getString(2)).thenReturn("evidence-complete:FORGED", MERCHANT_COMMAND_ID);
    when(row.getInt(3)).thenReturn(3, 3);
    when(row.getString(4)).thenReturn("USER", "MERCHANT");
    when(row.getString(5)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(6)).thenReturn("COMPLETED", "COMPLETED");
    when(row.getString(7)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(8)).thenReturn("a".repeat(64), "b".repeat(64));
    when(row.getLong(9)).thenReturn(6L, 7L);
    when(row.getString(10))
        .thenReturn(
            partyCompletionResultHash(USER_COMPLETION_ID, "a".repeat(64), 6, 3),
            partyCompletionResultHash(MERCHANT_COMPLETION_ID, "b".repeat(64), 7, 4));

    assertThatThrownBy(
            () ->
                subject()
                    .requirePersistedCompletionFactsAfterCommandLock(
                        connection, request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("completion drifted");
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
    JdbcTargetEvidenceTerminalActivities subject = subject();
    var request = new TargetEvidenceTerminalActivities.TerminalRequest(
        start(), 8, 5, USER_COMMAND_ID, MERCHANT_COMMAND_ID);

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

  @Test
  void storedTerminalReplayRebuildsEveryDurableFieldAndCanonicalBytes() {
    JdbcTargetEvidenceTerminalActivities subject = subject();
    var request = request();
    Instant committedAt = Instant.parse("2026-07-30T00:30:00Z");
    Instant deadline = committedAt.plus(java.time.Duration.ofHours(3));
    String requestHash = subject.hash(request);
    String seed = ContractJson.sha256Hex(new ObjectMapper().findAndRegisterModules().valueToTree(
        java.util.List.of(request.start().caseId(), request.start().roomEpoch(), requestHash)));
    String receiptId = "EVDTERM_" + seed.substring(0, 32);
    String hearingRoomId = "ROOM_HEARING_" + seed.substring(0, 28);
    String receiptHash = subject.receiptHash(
        request, "DOSSIER_E2E", 3, hearingRoomId, deadline, 9, 6);
    byte[] canonical = subject.receiptCanonical(
        receiptId, receiptHash, requestHash, request, "DOSSIER_E2E", 3,
        hearingRoomId, deadline, 9, 6, committedAt);
    var stored = new JdbcTargetEvidenceTerminalActivities.Stored(
        receiptId, receiptHash, requestHash, request.start().tenantSurrogate(),
        request.start().caseId(), request.start().roomEpoch(), request.start().fencingToken(),
        request.initiatorCompletionId(), request.respondentCompletionId(), "DOSSIER_E2E", 3,
        hearingRoomId, deadline, 9, 6, canonical, committedAt);

    assertThatCode(() -> subject.requireStoredReplay(request, stored)).doesNotThrowAnyException();

    var drifted = new JdbcTargetEvidenceTerminalActivities.Stored(
        receiptId, "f".repeat(64), requestHash, request.start().tenantSurrogate(),
        request.start().caseId(), request.start().roomEpoch(), request.start().fencingToken(),
        request.initiatorCompletionId(), request.respondentCompletionId(), "DOSSIER_E2E", 3,
        hearingRoomId, deadline, 9, 6, canonical, committedAt);
    assertThatThrownBy(() -> subject.requireStoredReplay(request, drifted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("replay drifted");
  }

  private static EvidenceRoomStart start() {
    return new EvidenceRoomStart(
        "evidence-room-start.v1", "tenant-e2e", "CASE_E2E", "ROOM_EVIDENCE", 4, 9,
        "USER_E2E", "MERCHANT_E2E", Instant.parse("2026-07-28T00:00:00Z"),
        Instant.parse("2026-07-28T01:00:00Z"), 1, 6, 3, "local-control-build",
        ExecutionLane.TARGET_E2E_CANDIDATE);
  }

  private static TargetEvidenceTerminalActivities.TerminalRequest request() {
    return new TargetEvidenceTerminalActivities.TerminalRequest(
        start(),
        8,
        5,
        USER_COMMAND_ID,
        MERCHANT_COMMAND_ID,
        canonicalWorkflowId(),
        WORKFLOW_RUN_ID);
  }

  private static String canonicalWorkflowId() {
    return CaseProcessWorkflowProtocol.roomWorkflowId("CASE_E2E", RoomType.EVIDENCE, 4);
  }

  private static String partyCompletionResultHash(
      String completionId,
      String requestHash,
      long expectedProcessRevision,
      long expectedRoomRevision) {
    return ContractJson.sha256Hex(
        new ObjectMapper()
            .valueToTree(
                java.util.List.of(
                    "target-e2e-evidence-party-receipt.v1",
                    completionId,
                    requestHash,
                    expectedProcessRevision,
                    expectedRoomRevision)));
  }

  private static JdbcTargetEvidenceTerminalActivities subject() {
    EvidenceDossierFreezer dossierFreezer = mock(EvidenceDossierFreezer.class);
    when(dossierFreezer.targetVersion("CASE_E2E")).thenReturn(3);
    return new JdbcTargetEvidenceTerminalActivities(
        mock(javax.sql.DataSource.class),
        mock(TransactionTemplate.class),
        dossierFreezer,
        mock(RoomEpochAllocator.class),
        new ObjectMapper().findAndRegisterModules(),
        Clock.systemUTC());
  }
}
