package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcTargetHearingCommandMaterialStoreTest {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final Instant STORED_AT = Instant.parse("2026-08-16T04:30:00Z");

  @Test
  void singleRouteReadBindsExactMaterialAndFailsClosedForAbsenceAmbiguityOrDrift()
      throws Exception {
    TargetHearingCommandMaterial material = material();
    PersistedRow row = row(material);
    TargetHearingCommandMaterialStore.Route route = route(material);
    Harness exact = harness(new PersistedRow[] {row}, new PersistedRow[] {row});

    TargetHearingCommandMaterialStore.Snapshot initial =
        exact.store().readByRoute(route).orElseThrow();
    TargetHearingCommandMaterialStore.Snapshot recovery =
        exact.store().readByRoute(route).orElseThrow();

    assertThat(initial.material()).isEqualTo(material);
    assertThat(initial).isEqualTo(recovery);
    assertThat(initial.admission().commandHash())
        .isNotEqualTo(initial.material().request().command().requestHash());
    verify(exact.statement(), times(2)).setString(1, material.admission().tenantSurrogate());
    verify(exact.statement(), times(2)).setString(2, material.admission().caseId());
    verify(exact.statement(), times(2)).setString(3, material.admission().commandId());
    verify(exact.statement(), times(2)).setLong(4, material.admission().roomEpoch());
    verify(exact.statement(), times(2)).setLong(5, material.admission().roomFencingToken());
    verify(exact.connection(), never()).prepareStatement(
        org.mockito.ArgumentMatchers.argThat(sql -> sql.toLowerCase().contains("insert")));
    verifyNoInteractions(exact.ledger());

    assertThat(harness(new PersistedRow[0]).store().readByRoute(route)).isEmpty();
    assertThatThrownBy(
            () -> harness(new PersistedRow[] {row, row}).store().readByRoute(route))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Hearing route is ambiguous");

    PersistedRow hashDrift = row.withMaterialSha256(hash('0'));
    assertThatThrownBy(
            () -> harness(new PersistedRow[] {hashDrift}).store().readByRoute(route))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("stored Hearing material hash mismatch");

    PersistedRow canonicalDrift = row.withCanonicalJson(row.canonicalJson() + " ");
    assertThatThrownBy(
            () -> harness(new PersistedRow[] {canonicalDrift}).store().readByRoute(route))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("stored Hearing material hash mismatch");

    PersistedRow admissionDrift = row.withCommandHash(hash('f'));
    assertThatThrownBy(
            () -> harness(new PersistedRow[] {admissionDrift}).store().readByRoute(route))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("stored Hearing material admission mismatch");
  }

  private static Harness harness(PersistedRow[]... queryRows) throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    TargetE2EActivationLedger ledger = mock(TargetE2EActivationLedger.class);
    List<ResultSet> results = new ArrayList<>();
    for (PersistedRow[] rows : queryRows) {
      results.add(resultSet(rows));
    }
    AtomicInteger query = new AtomicInteger();
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenAnswer(
        ignored -> results.get(query.getAndIncrement()));
    return new Harness(
        new JdbcTargetHearingCommandMaterialStore(dataSource, ledger, MAPPER),
        connection,
        statement,
        ledger);
  }

  private static ResultSet resultSet(PersistedRow... rows) throws SQLException {
    ResultSet result = mock(ResultSet.class);
    AtomicInteger cursor = new AtomicInteger(-1);
    when(result.next()).thenAnswer(
        ignored -> cursor.incrementAndGet() < rows.length);
    when(result.getString(anyString())).thenAnswer(
        invocation -> current(rows, cursor).text(invocation.getArgument(0)));
    when(result.getLong(anyString())).thenAnswer(
        invocation -> current(rows, cursor).number(invocation.getArgument(0)));
    when(result.getTimestamp(anyString())).thenAnswer(
        invocation -> Timestamp.from(current(rows, cursor).storedAt()));
    return result;
  }

  private static PersistedRow current(PersistedRow[] rows, AtomicInteger cursor)
      throws SQLException {
    int index = cursor.get();
    if (index < 0 || index >= rows.length) {
      throw new SQLException("result cursor is not positioned on a row");
    }
    return rows[index];
  }

  private static PersistedRow row(TargetHearingCommandMaterial material) {
    var materialNode = MAPPER.valueToTree(material);
    String canonical = ContractJson.canonicalString(materialNode);
    return new PersistedRow(
        "ADMISSION_HEARING_4",
        material.admission(),
        canonical,
        ContractJson.sha256Hex(materialNode),
        STORED_AT);
  }

  private static TargetHearingCommandMaterialStore.Route route(
      TargetHearingCommandMaterial material) {
    CommandAdmission admission = material.admission();
    return new TargetHearingCommandMaterialStore.Route(
        admission.tenantSurrogate(), admission.caseId(), admission.commandId(),
        admission.roomEpoch(), admission.roomFencingToken());
  }

  private static TargetHearingCommandMaterial material() {
    RoomGraphCommand command = new RoomGraphCommand(
        "room-graph-command.v1",
        "hearing-stage:4:test",
        "target-hearing-run:test",
        "target-hearing-run:test:1",
        "legacy-default",
        "CASE_HEARING_MATERIAL",
        RoomType.HEARING,
        0,
        "all-rooms.target-e2e.v1",
        "target-e2e-graph.2026-07-27.1",
        "target-e2e-checkpoint.v1",
        "grt.v1.0123456789abcdef0123456789abcdef",
        new RoomGraphCommand.ActorScope(
            "hearing-control", ActorRole.SYSTEM, Audience.SYSTEM,
            List.of("hearing:INTAKE_QUESTIONS_GENERATING")),
        14,
        "INTAKE_QUESTIONS_GENERATING",
        4,
        new RoomGraphCommand.SnapshotRef(
            "HEARING_STATE_1", "hearing-state.v1", "minio://hearing/state", hash('1'), 1),
        new RoomGraphCommand.SnapshotRef(
            "HEARING_EVENT_1", "hearing-event.v1", "minio://hearing/event", hash('2'), 1),
        new RoomGraphCommand.InvocationContext(
            "agent-hearing", "prompt-hearing", "model-hearing", "hearing-output.v1",
            "policy-hearing", "guardrail-hearing", List.of(), "key-hearing", "nonce-hearing"),
        new RoomGraphCommand.RetryBudget(2, 3, 1),
        Instant.parse("2026-08-16T04:35:00Z"),
        "00-0123456789abcdef0123456789abcdef-0000000000000001-01",
        hash('8'));
    ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(
        ExecuteAgentRunRequest.SCHEMA_VERSION,
        command.logicalRunId(),
        1,
        3,
        "agent-stream.v2",
        hash('9'),
        null,
        false,
        0,
        command);
    CommandAdmission admission = new CommandAdmission(
        "p9act.v1.test",
        hash('a'),
        hash('b'),
        command.tenantSurrogate(),
        command.caseId(),
        command.commandId(),
        hash('c'),
        hash('d'),
        command.roomEpoch(),
        3);
    return new TargetHearingCommandMaterial(
        TargetHearingCommandMaterial.SCHEMA_VERSION,
        admission,
        request,
        admission.commandHash(),
        admission.commandEnvelopeHash());
  }

  private static String hash(char value) {
    return String.valueOf(value).repeat(64);
  }

  private record Harness(
      JdbcTargetHearingCommandMaterialStore store,
      Connection connection,
      PreparedStatement statement,
      TargetE2EActivationLedger ledger) {}

  private record PersistedRow(
      String admissionId,
      CommandAdmission admission,
      String canonicalJson,
      String materialSha256,
      Instant storedAt) {

    String text(String column) {
      return switch (column) {
        case "admission_id" -> admissionId;
        case "activation_id" -> admission.activationId();
        case "activation_manifest_hash" -> admission.manifestHash();
        case "isolated_domain_db_binding_hash" -> admission.isolatedDomainDbBindingHash();
        case "tenant_surrogate" -> admission.tenantSurrogate();
        case "case_id" -> admission.caseId();
        case "command_id" -> admission.commandId();
        case "command_hash" -> admission.commandHash();
        case "command_envelope_hash" -> admission.commandEnvelopeHash();
        case "material_canonical_json" -> canonicalJson;
        case "material_sha256" -> materialSha256;
        default -> throw new IllegalArgumentException("unexpected text column " + column);
      };
    }

    long number(String column) {
      return switch (column) {
        case "room_epoch" -> admission.roomEpoch();
        case "room_fencing_token" -> admission.roomFencingToken();
        default -> throw new IllegalArgumentException("unexpected numeric column " + column);
      };
    }

    PersistedRow withMaterialSha256(String value) {
      return new PersistedRow(admissionId, admission, canonicalJson, value, storedAt);
    }

    PersistedRow withCanonicalJson(String value) {
      return new PersistedRow(admissionId, admission, value, materialSha256, storedAt);
    }

    PersistedRow withCommandHash(String value) {
      return new PersistedRow(
          admissionId,
          new CommandAdmission(
              admission.activationId(), admission.manifestHash(),
              admission.isolatedDomainDbBindingHash(), admission.tenantSurrogate(),
              admission.caseId(), admission.commandId(), value,
              admission.commandEnvelopeHash(), admission.roomEpoch(),
              admission.roomFencingToken()),
          canonicalJson,
          materialSha256,
          storedAt);
    }
  }
}
