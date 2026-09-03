package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmissionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** JDBC implementation. The activation admission and material row are committed atomically. */
public final class JdbcTargetHearingCommandMaterialStore implements TargetHearingCommandMaterialStore {
  private final DataSource dataSource;
  private final TargetE2EActivationLedger ledger;
  private final ObjectMapper mapper;

  public JdbcTargetHearingCommandMaterialStore(DataSource dataSource, TargetE2EActivationLedger ledger,
      ObjectMapper mapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
  }

  @Override
  public AppendResult append(TargetHearingCommandMaterial material) {
    Objects.requireNonNull(material, "material");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (!DataSourceUtils.isConnectionTransactional(connection, dataSource) || connection.getAutoCommit()) {
        throw new IllegalStateException("target Hearing material requires a caller-owned Spring transaction");
      }
      CommandAdmissionResult admitted = ledger.admitCommand(connection, material.admission());
      Canonical canonical = canonical(material);
      Persisted existing = find(connection, material.admission().activationId(), material.admission().commandId());
      if (existing != null) {
        if (!existing.json.equals(canonical.json) || !existing.sha256.equals(canonical.sha256)) {
          throw new IllegalStateException("Hearing material replay conflicts with existing immutable material");
        }
        return new AppendResult(existing.admissionId, admitted.admittedAt(), true);
      }
      try (PreparedStatement statement = connection.prepareStatement("""
          insert into target_e2e_hearing_command_material (
              admission_id, activation_id, activation_manifest_hash, isolated_domain_db_binding_hash,
              tenant_surrogate, case_id, command_id, command_hash, command_envelope_hash,
              room_epoch, room_fencing_token, material_canonical_json, material_sha256)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """)) {
        CommandAdmission admission = material.admission();
        int i = 1;
        statement.setString(i++, admitted.admissionId());
        statement.setString(i++, admission.activationId());
        statement.setString(i++, admission.manifestHash());
        statement.setString(i++, admission.isolatedDomainDbBindingHash());
        statement.setString(i++, admission.tenantSurrogate());
        statement.setString(i++, admission.caseId());
        statement.setString(i++, admission.commandId());
        statement.setString(i++, admission.commandHash());
        statement.setString(i++, admission.commandEnvelopeHash());
        statement.setLong(i++, admission.roomEpoch());
        statement.setLong(i++, admission.roomFencingToken());
        statement.setString(i++, canonical.json);
        statement.setString(i, canonical.sha256);
        if (statement.executeUpdate() != 1) throw new IllegalStateException("Hearing material insert failed");
      }
      return new AppendResult(admitted.admissionId(), admitted.admittedAt(), false);
    } catch (SQLException failure) {
      throw new IllegalStateException("target Hearing material persistence failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  @Override
  public Optional<Snapshot> readByRoute(Route route) {
    Objects.requireNonNull(route, "route");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (PreparedStatement statement = connection.prepareStatement("""
        select m.admission_id, m.activation_id, m.activation_manifest_hash,
               m.isolated_domain_db_binding_hash, m.tenant_surrogate, m.case_id, m.command_id,
               m.command_hash, m.command_envelope_hash, m.room_epoch, m.room_fencing_token,
               m.material_canonical_json, m.material_sha256, m.stored_at
          from target_e2e_hearing_command_material m
         where m.tenant_surrogate = ? and m.case_id = ? and m.command_id = ?
           and m.room_epoch = ? and m.room_fencing_token = ?
        """)) {
      statement.setString(1, route.tenantSurrogate()); statement.setString(2, route.caseId());
      statement.setString(3, route.commandId()); statement.setLong(4, route.roomEpoch());
      statement.setLong(5, route.fencingToken());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return Optional.empty();
        Snapshot snapshot = snapshot(result);
        if (result.next()) throw new IllegalStateException("Hearing route is ambiguous");
        return Optional.of(snapshot);
      }
    } catch (SQLException | JsonProcessingException failure) {
      throw new IllegalStateException("target Hearing material read failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  @Override
  public Optional<Snapshot> readByCommand(CommandRoute route) {
    Objects.requireNonNull(route, "route");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (PreparedStatement statement = connection.prepareStatement("""
        select m.admission_id, m.activation_id, m.activation_manifest_hash,
               m.isolated_domain_db_binding_hash, m.tenant_surrogate, m.case_id, m.command_id,
               m.command_hash, m.command_envelope_hash, m.room_epoch, m.room_fencing_token,
               m.material_canonical_json, m.material_sha256, m.stored_at
          from target_e2e_hearing_command_material m
         where m.tenant_surrogate = ? and m.case_id = ? and m.command_id = ? and m.room_epoch = ?
        """)) {
      statement.setString(1, route.tenantSurrogate()); statement.setString(2, route.caseId());
      statement.setString(3, route.commandId()); statement.setLong(4, route.roomEpoch());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return Optional.empty();
        Snapshot snapshot = snapshot(result);
        if (result.next()) throw new IllegalStateException("Hearing command route is ambiguous");
        return Optional.of(snapshot);
      }
    } catch (SQLException | JsonProcessingException failure) {
      throw new IllegalStateException("target Hearing command read failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private Snapshot snapshot(ResultSet result) throws SQLException, JsonProcessingException {
    CommandAdmission admission = new CommandAdmission(result.getString("activation_id"),
        result.getString("activation_manifest_hash"), result.getString("isolated_domain_db_binding_hash"),
        result.getString("tenant_surrogate"), result.getString("case_id"), result.getString("command_id"),
        result.getString("command_hash"), result.getString("command_envelope_hash"),
        result.getLong("room_epoch"), result.getLong("room_fencing_token"));
    String json = result.getString("material_canonical_json");
    JsonNode node = mapper.readTree(json);
    if (!ContractJson.canonicalString(node).equals(json)
        || !ContractJson.sha256Hex(node).equals(result.getString("material_sha256"))) {
      throw new IllegalStateException("stored Hearing material hash mismatch");
    }
    TargetHearingCommandMaterial material = mapper.treeToValue(node, TargetHearingCommandMaterial.class);
    if (!material.admission().equals(admission)) {
      throw new IllegalStateException("stored Hearing material admission mismatch");
    }
    return new Snapshot(result.getString("admission_id"), admission, material,
        result.getString("material_sha256"), result.getTimestamp("stored_at").toInstant());
  }

  private Canonical canonical(TargetHearingCommandMaterial material) {
    JsonNode node = mapper.valueToTree(material);
    return new Canonical(ContractJson.canonicalString(node), ContractJson.sha256Hex(node));
  }
  private static Persisted find(Connection connection, String activationId, String commandId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("select admission_id, material_canonical_json, material_sha256 from target_e2e_hearing_command_material where activation_id = ? and command_id = ? for update")) {
      statement.setString(1, activationId); statement.setString(2, commandId);
      try (ResultSet result = statement.executeQuery()) { return result.next() ? new Persisted(result.getString(1), result.getString(2), result.getString(3)) : null; }
    }
  }
  private record Canonical(String json, String sha256) {}
  private record Persisted(String admissionId, String json, String sha256) {}
}
