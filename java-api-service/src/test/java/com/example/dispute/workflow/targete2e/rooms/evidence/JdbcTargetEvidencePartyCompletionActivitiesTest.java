package com.example.dispute.workflow.targete2e.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceParticipantBindingActivities.Binding;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidencePartyCompletionActivities.Request;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart.ExecutionLane;
import java.time.Instant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class JdbcTargetEvidencePartyCompletionActivitiesTest {

  @Test
  void freshCompletionRequiresTheImmediatelyPrecedingProjectionCommandCursor()
      throws Exception {
    Request request = request("user-local", ActorRole.USER);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, false);
    when(rows.getLong(1)).thenReturn(request.expectedProcessRevision());
    when(rows.getLong(2)).thenReturn(request.command().caseCommandSequence() - 1);

    assertThatCode(
            () ->
                JdbcTargetEvidencePartyCompletionActivities.lockProjection(
                    connection, request, false))
        .doesNotThrowAnyException();

    verify(connection)
        .prepareStatement(
            argThat(
                sql ->
                    sql.contains("select process_revision, last_command_sequence")
                        && sql.contains("for update")));
  }

  @Test
  void appliedCompletionReplayRequiresTheCommittedProjectionCommandCursor()
      throws Exception {
    Request request = request("user-local", ActorRole.USER);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, false);
    when(rows.getLong(1)).thenReturn(request.expectedProcessRevision() + 1);
    when(rows.getLong(2)).thenReturn(request.command().caseCommandSequence());

    assertThatCode(
            () ->
                JdbcTargetEvidencePartyCompletionActivities.lockProjection(
                    connection, request, true))
        .doesNotThrowAnyException();
  }

  @Test
  void appliedCompletionReplayRejectsAProjectionWhoseCursorWasNotAdvanced()
      throws Exception {
    Request request = request("user-local", ActorRole.USER);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, false);
    when(rows.getLong(1)).thenReturn(request.expectedProcessRevision() + 1);
    when(rows.getLong(2)).thenReturn(request.command().caseCommandSequence() - 1);

    assertThatThrownBy(
            () ->
                JdbcTargetEvidencePartyCompletionActivities.lockProjection(
                    connection, request, true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("process projection drifted");
  }

  @Test
  void completionAtomicallyAdvancesRevisionAndCommandCursorWithExactCas()
      throws Exception {
    Request request = request("user-local", ActorRole.USER);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeUpdate()).thenReturn(1);
    long nextProcessRevision = request.expectedProcessRevision() + 1;

    JdbcTargetEvidencePartyCompletionActivities.updateProjection(
        connection, request, nextProcessRevision);

    verify(connection)
        .prepareStatement(
            argThat(
                sql ->
                    sql.contains("set process_revision = ?, last_command_sequence = ?")
                        && sql.contains(
                            "and process_revision = ? and last_command_sequence = ?")));
    verify(statement).setLong(1, nextProcessRevision);
    verify(statement).setLong(2, request.command().caseCommandSequence());
    verify(statement).setLong(7, request.expectedProcessRevision());
    verify(statement).setLong(8, request.command().caseCommandSequence() - 1);
  }

  @Test
  void merchantInitiatedCompletionUsesThePersistedActorRoleInsteadOfParticipantPosition() {
    EvidenceRoomStart start = start("merchant-local", "user-local");
    Binding participants = binding(start, "merchant-local", "user-local");
    Request request =
        new Request(
            start,
            participants,
            command(start, "merchant-local", ActorRole.MERCHANT),
            start.initialProcessRevision(),
            start.initialRoomRevision());

    assertThatCode(() -> JdbcTargetEvidencePartyCompletionActivities.requireRequest(request))
        .doesNotThrowAnyException();
  }

  @Test
  void completionRejectsAParticipantBindingFromAnotherFence() {
    EvidenceRoomStart start = start("merchant-local", "user-local");
    Binding drifted =
        new Binding(
            start.tenantSurrogate(),
            start.caseId(),
            start.roomEpoch(),
            start.fencingToken() + 1,
            start.initiatorParticipantId(),
            start.respondentParticipantId(),
            "b".repeat(64));
    Request request =
        new Request(
            start,
            drifted,
            command(start, "merchant-local", ActorRole.MERCHANT),
            start.initialProcessRevision(),
            start.initialRoomRevision());

    assertThatThrownBy(
            () -> JdbcTargetEvidencePartyCompletionActivities.requireRequest(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inconsistent");
  }

  @Test
  void appliedResultUriUsesTheDatabaseAllowedUrnScheme() {
    assertThat(JdbcTargetEvidencePartyCompletionActivities.RESULT_URI_PREFIX)
        .isEqualTo("urn:target-evidence-completion:");
  }

  @Test
  void completionAcceptsOnlyTheCanonicalParentCaseWorkflowCaller() {
    EvidenceRoomStart start = start("user-local", "merchant-local");
    Request request =
        new Request(
            start,
            binding(start, "user-local", "merchant-local"),
            command(start, "user-local", ActorRole.USER),
            start.initialProcessRevision(),
            start.initialRoomRevision());
    String workflowId =
        CaseProcessWorkflowProtocol.caseWorkflowId(
            start.tenantSurrogate(), start.caseId());

    assertThat(
            JdbcTargetEvidencePartyCompletionActivities.requireCaseWorkflowIdentity(
                request, workflowId, "run-evidence-1"))
        .extracting(
            JdbcTargetEvidencePartyCompletionActivities.WorkflowIdentity::workflowId,
            JdbcTargetEvidencePartyCompletionActivities.WorkflowIdentity::workflowRunId)
        .containsExactly(workflowId, "run-evidence-1");
    assertThatThrownBy(
            () ->
                JdbcTargetEvidencePartyCompletionActivities.requireCaseWorkflowIdentity(
                    request, "evidence:another-case:2", "run-evidence-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical case workflow");
  }

  @Test
  void continuedCaseRunUsesThePersistedFirstExecutionChainAnchor() throws Exception {
    EvidenceRoomStart start = start("user-local", "merchant-local");
    Request request =
        new Request(
            start,
            binding(start, "user-local", "merchant-local"),
            command(start, "user-local", ActorRole.USER),
            start.initialProcessRevision(),
            start.initialRoomRevision());
    String workflowId = CaseProcessWorkflowProtocol.caseWorkflowId(
        start.tenantSurrogate(), start.caseId());
    var currentRun =
        JdbcTargetEvidencePartyCompletionActivities.requireCaseWorkflowIdentity(
            request, workflowId, "run-B-after-continue-as-new");
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, false);
    when(rows.getString(1)).thenReturn("epoch-evidence-2");
    when(rows.getString(2)).thenReturn("ACTIVE");
    when(rows.getLong(3)).thenReturn(start.initialProcessRevision());
    when(rows.getLong(4)).thenReturn(start.initialRoomRevision());
    when(rows.getString(5)).thenReturn("p9act.v1." + "a".repeat(32));
    when(rows.getString(6)).thenReturn("ACTIVE");
    when(rows.getBoolean(7)).thenReturn(true);

    assertThatCode(
            () -> JdbcTargetEvidencePartyCompletionActivities.lockEpoch(
                connection, request, currentRun))
        .doesNotThrowAnyException();

    verify(connection).prepareStatement(argThat(sql ->
        sql.contains("epoch.temporal_workflow_id = ?")
            && sql.contains("coalesce(btrim(epoch.temporal_run_id), '') <> ''")
            && !sql.contains("epoch.temporal_run_id = ?")
            && sql.contains("epoch.room_temporal_workflow_id = ?")
            && sql.contains("coalesce(btrim(epoch.room_temporal_run_id), '') <> ''")
            && sql.contains("'ACTIVE', 'DRAIN_ONLY', 'DRAINED', 'REVOKED_TERMINAL'")));
    verify(statement).setString(6, workflowId);
    verify(statement, never()).setString(anyInt(), eq("run-B-after-continue-as-new"));
  }

  @Test
  void completionMaterialAuthorityQueryKeepsTerminalActivationsVisibleForAppliedReplay()
      throws Exception {
    EvidenceRoomStart start = start("user-local", "merchant-local");
    Request request =
        new Request(
            start,
            binding(start, "user-local", "merchant-local"),
            command(start, "user-local", ActorRole.USER),
            start.initialProcessRevision(),
            start.initialRoomRevision());
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(false);

    assertThatThrownBy(
            () ->
                JdbcTargetEvidencePartyCompletionActivities.lockCompletionMaterial(
                    connection, request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("admission material is absent");

    verify(connection).prepareStatement(argThat(sql ->
        sql.contains("activation.lifecycle_status in")
            && sql.contains("'ACTIVE', 'DRAIN_ONLY', 'DRAINED', 'REVOKED_TERMINAL'")));
  }

  @Test
  void completionRequiresTheBusinessCompletionAndCanonicalTimelineIntent() {
    EvidenceRoomStart start = start("user-local", "merchant-local");
    String completionId = "EVIDENCE_COMPLETE_1";
    String eventId = "EVENT_COMPLETION_1";
    var intent =
        new TreeMap<String, Object>(
            Map.of(
                "completion_id", completionId,
                "case_id", start.caseId(),
                "dossier_version", 3,
                "participant_id", "user-local",
                "participant_role", "USER",
                "room_epoch", start.roomEpoch()));
    var intentNode = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(intent);
    CaseCommandRef canonical =
        command(
            start,
            "evidence-complete:" + completionId,
            "user-local",
            ActorRole.USER,
            new PayloadRef(
                "target-e2e-evidence-completion.v1",
                "urn:target-e2e:timeline-event:" + eventId,
                ContractJson.sha256Hex(intentNode),
                ContractJson.canonicalize(intentNode).length));
    Request request =
        new Request(
            start,
            binding(start, "user-local", "merchant-local"),
            canonical,
            start.initialProcessRevision(),
            start.initialRoomRevision());
    var fact =
        new JdbcTargetEvidencePartyCompletionActivities.CompletionIntent(
            completionId,
            3,
            "USER",
            "user-local",
            "COMPLETED",
            "api-complete-1",
            "user-local",
            ContractJson.canonicalString(intentNode),
            "target-evidence-completion:" + completionId,
            "user-local");

    assertThatCode(
            () ->
                JdbcTargetEvidencePartyCompletionActivities.requireCompletionIntent(
                    request, "USER", eventId, fact))
        .doesNotThrowAnyException();

    Request forged =
        new Request(
            start,
            binding(start, "user-local", "merchant-local"),
            command(start, "user-local", ActorRole.USER),
            start.initialProcessRevision(),
            start.initialRoomRevision());
    assertThatThrownBy(
            () ->
                JdbcTargetEvidencePartyCompletionActivities.requireCompletionIntent(
                    forged, "USER", eventId, fact))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("intent");
  }

  @Test
  void expiredActiveActivationAllowsOnlyAppliedReplayCoordinates() {
    EvidenceRoomStart start = start("user-local", "merchant-local");
    Request request =
        new Request(
            start,
            binding(start, "user-local", "merchant-local"),
            command(start, "user-local", ActorRole.USER),
            start.initialProcessRevision(),
            start.initialRoomRevision());
    var expiredActiveReplay =
        new JdbcTargetEvidencePartyCompletionActivities.Epoch(
            "epoch-evidence-2",
            "ACTIVE",
            start.initialProcessRevision() + 1,
            start.initialRoomRevision() + 1,
            "p9act.v1." + "a".repeat(32),
            "ACTIVE",
            false);

    assertThatCode(
            () ->
                JdbcTargetEvidencePartyCompletionActivities.requireCoordinates(
                    request, expiredActiveReplay, true))
        .doesNotThrowAnyException();

    var expiredActiveNewWrite =
        new JdbcTargetEvidencePartyCompletionActivities.Epoch(
            "epoch-evidence-2",
            "ACTIVE",
            start.initialProcessRevision(),
            start.initialRoomRevision(),
            "p9act.v1." + "a".repeat(32),
            "ACTIVE",
            false);
    assertThatThrownBy(
            () ->
                JdbcTargetEvidencePartyCompletionActivities.requireCoordinates(
                    request, expiredActiveNewWrite, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("authority drifted");
  }

  @Test
  void appliedReplayAcceptsDrainedAndRevokedTerminalButNewWritesDoNot() {
    EvidenceRoomStart start = start("user-local", "merchant-local");
    Request request =
        new Request(
            start,
            binding(start, "user-local", "merchant-local"),
            command(start, "user-local", ActorRole.USER),
            start.initialProcessRevision(),
            start.initialRoomRevision());

    assertThat(
            List.of("DRAINED", "REVOKED_TERMINAL").stream()
                .allMatch(
                    lifecycle ->
                        JdbcTargetEvidencePartyCompletionActivities.activationLifecycleAllows(
                            true, lifecycle)))
        .isTrue();
    assertThat(
            List.of("DRAINED", "REVOKED_TERMINAL").stream()
                .noneMatch(
                    lifecycle ->
                        JdbcTargetEvidencePartyCompletionActivities.activationLifecycleAllows(
                            false, lifecycle)))
        .isTrue();

    for (String lifecycle : List.of("DRAINED", "REVOKED_TERMINAL")) {
      var appliedReplay =
          new JdbcTargetEvidencePartyCompletionActivities.Epoch(
              "epoch-evidence-2",
              "ACTIVE",
              start.initialProcessRevision() + 1,
              start.initialRoomRevision() + 1,
              "p9act.v1." + "a".repeat(32),
              lifecycle,
              false);
      assertThatCode(
              () ->
                  JdbcTargetEvidencePartyCompletionActivities.requireCoordinates(
                      request, appliedReplay, true))
          .doesNotThrowAnyException();

      var newWrite =
          new JdbcTargetEvidencePartyCompletionActivities.Epoch(
              "epoch-evidence-2",
              "ACTIVE",
              start.initialProcessRevision(),
              start.initialRoomRevision(),
              "p9act.v1." + "a".repeat(32),
              lifecycle,
              false);
      assertThatThrownBy(
              () ->
                  JdbcTargetEvidencePartyCompletionActivities.requireCoordinates(
                      request, newWrite, false))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("authority drifted");
    }
  }

  @Test
  void replayStillRejectsAnUnboundOrRetiredActivation() {
    EvidenceRoomStart start = start("user-local", "merchant-local");
    Request request =
        new Request(
            start,
            binding(start, "user-local", "merchant-local"),
            command(start, "user-local", ActorRole.USER),
            start.initialProcessRevision(),
            start.initialRoomRevision());

    assertThatThrownBy(
            () ->
                JdbcTargetEvidencePartyCompletionActivities.requireCoordinates(
                    request,
                    new JdbcTargetEvidencePartyCompletionActivities.Epoch(
                        "epoch-evidence-2",
                        "ACTIVE",
                        start.initialProcessRevision() + 1,
                        start.initialRoomRevision() + 1,
                        "",
                        "RETIRED",
                        false),
                    true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("authority drifted");
  }

  @Test
  void completionRequiresExactDedicatedMaterialIncludingTraceAndRoomRevision() {
    EvidenceRoomStart start = start("user-local", "merchant-local");
    Request request = new Request(
        start,
        binding(start, "user-local", "merchant-local"),
        command(start, "user-local", ActorRole.USER),
        start.initialProcessRevision(),
        start.initialRoomRevision());
    TargetEvidenceCompletionCommandMaterial material =
        TargetEvidenceCompletionCommandMaterial.create(
            "p9act.v1." + "a".repeat(32), "b".repeat(64), "c".repeat(64),
            start.tenantSurrogate(), start.caseId(), request.command().commandId(),
            start.roomEpoch(), start.fencingToken(), request.expectedProcessRevision(),
            request.expectedRoomRevision(), request.command().actorRef(),
            request.command().payloadRef(), request.command().deadlineAt(),
            "d".repeat(32), request.command().requestHash());

    assertThatCode(
            () -> JdbcTargetEvidencePartyCompletionActivities.requireCompletionMaterial(
                request, material))
        .doesNotThrowAnyException();

    TargetEvidenceCompletionCommandMaterial drifted =
        TargetEvidenceCompletionCommandMaterial.create(
            material.activationId(), material.activationManifestHash(),
            material.isolatedDomainDbBindingHash(), material.tenantSurrogate(), material.caseId(),
            material.commandId(), material.roomEpoch(), material.roomFencingToken(),
            material.expectedProcessRevision(), material.expectedRoomRevision() + 1,
            material.actorRef(), material.payloadRef(), material.deadlineAt(), material.traceId(),
            material.caseCommandRequestHash());
    assertThatThrownBy(
            () -> JdbcTargetEvidencePartyCompletionActivities.requireCompletionMaterial(
                request, drifted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("material differs");
  }

  private static EvidenceRoomStart start(String initiator, String respondent) {
    return new EvidenceRoomStart(
        "evidence-room-start.v1",
        "tenant-e2e",
        "CASE_E2E_MERCHANT_INITIATED",
        "ROOM_EVIDENCE_MERCHANT_INITIATED",
        2,
        9,
        initiator,
        respondent,
        Instant.parse("2026-07-30T00:00:00Z"),
        Instant.parse("2026-07-30T03:00:00Z"),
        1,
        7,
        5,
        "local-final-control",
        ExecutionLane.TARGET_E2E_CANDIDATE);
  }

  private static Binding binding(
      EvidenceRoomStart start, String initiator, String respondent) {
    return new Binding(
        start.tenantSurrogate(),
        start.caseId(),
        start.roomEpoch(),
        start.fencingToken(),
        initiator,
        respondent,
        "a".repeat(64));
  }

  private static CaseCommandRef command(
      EvidenceRoomStart start, String actorId, ActorRole actorRole) {
    return command(
        start,
        "evidence-complete:merchant-1",
        actorId,
        actorRole,
        new PayloadRef(
            "target-e2e-evidence-completion.v1",
            "urn:target-e2e:evidence-completion-intent",
            "c".repeat(64),
            128));
  }

  private static Request request(String actorId, ActorRole actorRole) {
    EvidenceRoomStart start = start("user-local", "merchant-local");
    return new Request(
        start,
        binding(start, "user-local", "merchant-local"),
        command(start, actorId, actorRole),
        start.initialProcessRevision(),
        start.initialRoomRevision());
  }

  private static CaseCommandRef command(
      EvidenceRoomStart start,
      String commandId,
      String actorId,
      ActorRole actorRole,
      PayloadRef payload) {
    Instant occurredAt = Instant.parse("2026-07-30T00:10:00Z");
    return new CaseCommandRef(
        "case-command-ref.v1",
        commandId,
        start.tenantSurrogate(),
        start.caseId(),
        11,
        CommandType.PARTY_EVIDENCE_COMPLETE,
        RoomType.EVIDENCE,
        start.roomEpoch(),
        new ActorRef(
            actorId,
            actorRole,
            List.of(
                "case:"
                    + start.caseId()
                    + ":command:PARTY_EVIDENCE_COMPLETE")),
        payload,
        start.initialProcessRevision(),
        occurredAt,
        occurredAt.plusSeconds(600),
        "00-" + "d".repeat(32) + "-" + "e".repeat(16) + "-01",
        "f".repeat(64));
  }
}
