package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmissionResult;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EPersistenceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** PostgreSQL implementation. It never opens, commits, or rolls back the caller transaction. */
public final class JdbcTargetEvidenceCommandMaterialStore implements TargetEvidenceCommandMaterialStore {
  private static final String TABLE = "target_e2e_evidence_command_material";
  private final DataSource dataSource;
  private final TargetE2EActivationLedger ledger;
  private final ObjectMapper mapper;

  public JdbcTargetEvidenceCommandMaterialStore(
      DataSource dataSource, TargetE2EActivationLedger ledger, ObjectMapper mapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
  }

  @Override
  public AppendResult append(CommandAdmission admission, TargetEvidenceCommandMaterial material) {
    Objects.requireNonNull(admission, "admission");
    requireMaterial(admission, material);
    Canonical canonical = canonical(material);
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      requireTransaction(connection);
      CommandAdmissionResult result = ledger.admitCommand(connection, admission);
      Row current = find(connection, admission.activationId(), admission.commandId(), true);
      if (current != null) {
        requireExact(admission, current);
        if (!current.canonicalJson.equals(canonical.json) || !current.sha256.equals(canonical.sha256)) {
          throw conflict("Evidence command replay changed immutable material");
        }
        return new AppendResult(current.admissionId, result.admittedAt(), current.sha256, true);
      }
      insert(connection, result.admissionId(), admission, canonical);
      return new AppendResult(result.admissionId(), result.admittedAt(), canonical.sha256, false);
    } catch (SQLException failure) {
      throw new TargetE2EPersistenceException("EVIDENCE_MATERIAL_PERSISTENCE_FAILED", failure.getMessage(), failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  @Override
  public Optional<MaterialSnapshot> readByRoute(CommandLookup lookup) {
    Objects.requireNonNull(lookup, "lookup");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      RouteRow route = findByRoute(connection, lookup);
      if (route == null) return Optional.empty();
      requireExact(route.admission, route.material);
      TargetEvidenceCommandMaterial material = deserialize(route.material);
      requireMaterial(route.admission, material);
      return Optional.of(new MaterialSnapshot(
          route.material.admissionId, route.admission, material, route.material.sha256, route.material.storedAt));
    } catch (SQLException failure) {
      throw new TargetE2EPersistenceException("EVIDENCE_MATERIAL_ROUTE_READ_FAILED", failure.getMessage(), failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  @Override
  public Optional<MaterialSnapshot> readByCommand(CommandIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      RouteRow route = findByIdentity(connection, identity);
      if (route == null) return Optional.empty();
      requireExact(route.admission, route.material);
      TargetEvidenceCommandMaterial material = deserialize(route.material);
      requireMaterial(route.admission, material);
      return Optional.of(new MaterialSnapshot(
          route.material.admissionId, route.admission, material, route.material.sha256, route.material.storedAt));
    } catch (SQLException failure) {
      throw new TargetE2EPersistenceException("EVIDENCE_MATERIAL_IDENTITY_READ_FAILED", failure.getMessage(), failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private Canonical canonical(TargetEvidenceCommandMaterial material) {
    JsonNode value = mapper.valueToTree(material);
    return new Canonical(
        material.schemaVersion(),
        ContractJson.canonicalString(value),
        ContractJson.sha256Hex(value));
  }

  private TargetEvidenceCommandMaterial deserialize(Row row) {
    try {
      JsonNode value = mapper.readTree(row.canonicalJson);
      if (!ContractJson.canonicalString(value).equals(row.canonicalJson)
          || !ContractJson.sha256Hex(value).equals(row.sha256)) {
        throw conflict("stored Evidence material hash is not canonical");
      }
      return mapper.treeToValue(value, TargetEvidenceCommandMaterial.class);
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      throw new TargetE2EPersistenceException("EVIDENCE_MATERIAL_INVALID", "stored Evidence material is invalid", failure);
    }
  }

  private static void requireMaterial(CommandAdmission admission, TargetEvidenceCommandMaterial material) {
    Objects.requireNonNull(material, "material");
    var command = material.request().command();
    boolean exact = TargetEvidenceCommandMaterial.TARGET_LANE.equals(material.executionLane())
        && admission.activationId().equals(material.activationId())
        && admission.manifestHash().equals(material.activationManifestHash())
        && admission.roomFencingToken() == material.roomFencingToken()
        && admission.commandHash().equals(material.commandHash())
        && admission.commandEnvelopeHash().equals(material.commandEnvelopeHash())
        && admission.tenantSurrogate().equals(command.tenantSurrogate())
        && admission.caseId().equals(command.caseId())
        && admission.commandId().equals(command.commandId())
        && admission.roomEpoch() == command.roomEpoch()
        && command.roomType().name().equals("EVIDENCE");
    if (!exact) throw conflict("Evidence material does not exactly bind its admission");
  }

  private static void requireExact(CommandAdmission admission, Row row) {
    if (!admission.activationId().equals(row.activationId)
        || !admission.manifestHash().equals(row.manifestHash)
        || !admission.isolatedDomainDbBindingHash().equals(row.databaseBindingHash)
        || !admission.tenantSurrogate().equals(row.tenant)
        || !admission.caseId().equals(row.caseId)
        || !admission.commandId().equals(row.commandId)
        || !admission.commandHash().equals(row.commandHash)
        || !admission.commandEnvelopeHash().equals(row.envelopeHash)
        || admission.roomEpoch() != row.epoch || admission.roomFencingToken() != row.fence) {
      throw conflict("Evidence material durable bindings differ from admission");
    }
  }

  private static void requireTransaction(Connection connection) throws SQLException {
    if (connection.getAutoCommit()) {
      throw new IllegalStateException("target Evidence material requires a caller-owned transaction");
    }
  }

  private static void insert(Connection c, String admissionId, CommandAdmission a, Canonical m) throws SQLException {
    String sql = """
        insert into target_e2e_evidence_command_material (
          admission_id, activation_id, activation_manifest_hash, execution_lane,
          isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id,
          command_hash, command_envelope_hash, room_type, room_epoch, room_fencing_token,
          material_schema_version, material_canonical_json, material_sha256)
        values (?, ?, ?, 'TARGET_E2E_CANDIDATE', ?, ?, ?, ?, ?, ?, 'EVIDENCE', ?, ?,
                ?, ?, ?)
        """;
    try (PreparedStatement s = c.prepareStatement(sql)) {
      int i = 1;
      s.setString(i++, admissionId); s.setString(i++, a.activationId()); s.setString(i++, a.manifestHash());
      s.setString(i++, a.isolatedDomainDbBindingHash()); s.setString(i++, a.tenantSurrogate());
      s.setString(i++, a.caseId()); s.setString(i++, a.commandId()); s.setString(i++, a.commandHash());
      s.setString(i++, a.commandEnvelopeHash()); s.setLong(i++, a.roomEpoch()); s.setLong(i++, a.roomFencingToken());
      s.setString(i++, m.schemaVersion); s.setString(i++, m.json); s.setString(i, m.sha256); s.executeUpdate();
    }
  }

  private static Row find(Connection c, String activationId, String commandId, boolean lock) throws SQLException {
    String sql = "select admission_id, activation_id, activation_manifest_hash, isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id, command_hash, command_envelope_hash, room_epoch, room_fencing_token, material_canonical_json, material_sha256, stored_at from " + TABLE + " where activation_id = ? and command_id = ?" + (lock ? " for update" : "");
    try (PreparedStatement s = c.prepareStatement(sql)) { s.setString(1, activationId); s.setString(2, commandId); try (ResultSet r = s.executeQuery()) { return r.next() ? row(r) : null; } }
  }

  private static RouteRow findByRoute(Connection c, CommandLookup route) throws SQLException {
    String sql = """
        select m.admission_id, m.activation_id, m.activation_manifest_hash, m.isolated_domain_db_binding_hash,
               m.tenant_surrogate, m.case_id, m.command_id, m.command_hash, m.command_envelope_hash,
               m.room_epoch, m.room_fencing_token, m.material_canonical_json, m.material_sha256, m.stored_at
          from target_e2e_evidence_command_material m
         where m.tenant_surrogate = ? and m.case_id = ? and m.command_id = ?
           and m.room_epoch = ? and m.room_fencing_token = ?
        """;
    try (PreparedStatement s = c.prepareStatement(sql)) {
      s.setString(1, route.tenantSurrogate()); s.setString(2, route.caseId()); s.setString(3, route.commandId());
      s.setLong(4, route.roomEpoch()); s.setLong(5, route.roomFencingToken());
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) return null;
        Row material = row(r);
        CommandAdmission admission = new CommandAdmission(material.activationId, material.manifestHash,
            material.databaseBindingHash, material.tenant, material.caseId, material.commandId,
            material.commandHash, material.envelopeHash, material.epoch, material.fence);
        if (r.next()) throw conflict("Evidence route is ambiguous");
        return new RouteRow(admission, material);
      }
    }
  }

  private static RouteRow findByIdentity(Connection c, CommandIdentity identity) throws SQLException {
    String sql = """
        select m.admission_id, m.activation_id, m.activation_manifest_hash, m.isolated_domain_db_binding_hash,
               m.tenant_surrogate, m.case_id, m.command_id, m.command_hash, m.command_envelope_hash,
               m.room_epoch, m.room_fencing_token, m.material_canonical_json, m.material_sha256, m.stored_at
          from target_e2e_evidence_command_material m
         where m.tenant_surrogate = ? and m.case_id = ? and m.command_id = ? and m.room_epoch = ?
        """;
    try (PreparedStatement s = c.prepareStatement(sql)) {
      s.setString(1, identity.tenantSurrogate()); s.setString(2, identity.caseId());
      s.setString(3, identity.commandId()); s.setLong(4, identity.roomEpoch());
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) return null;
        Row material = row(r);
        CommandAdmission admission = new CommandAdmission(material.activationId, material.manifestHash,
            material.databaseBindingHash, material.tenant, material.caseId, material.commandId,
            material.commandHash, material.envelopeHash, material.epoch, material.fence);
        if (r.next()) throw conflict("Evidence command identity is ambiguous");
        return new RouteRow(admission, material);
      }
    }
  }

  private static Row row(ResultSet r) throws SQLException {
    return new Row(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
        r.getString(7), r.getString(8), r.getString(9), r.getLong(10), r.getLong(11), r.getString(12), r.getString(13), r.getTimestamp(14).toInstant());
  }
  private static TargetE2EPersistenceException conflict(String message) { return new TargetE2EPersistenceException("EVIDENCE_MATERIAL_CONFLICT", message); }
  private record Canonical(String schemaVersion, String json, String sha256) {}
  private record Row(String admissionId, String activationId, String manifestHash, String databaseBindingHash, String tenant, String caseId, String commandId, String commandHash, String envelopeHash, long epoch, long fence, String canonicalJson, String sha256, Instant storedAt) {}
  private record RouteRow(CommandAdmission admission, Row material) {}
}
