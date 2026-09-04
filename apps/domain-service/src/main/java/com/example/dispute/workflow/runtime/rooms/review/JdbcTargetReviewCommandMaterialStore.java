package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmission;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmissionResult;
import com.example.dispute.workflow.runtime.persistence.ProductionPersistenceException;
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

/** PostgreSQL append-only Review material. It never owns the admission transaction. */
public final class JdbcTargetReviewCommandMaterialStore implements TargetReviewCommandMaterialStore {
  private final DataSource dataSource;
  private final ProductionActivationLedger ledger;
  private final ObjectMapper mapper;

  public JdbcTargetReviewCommandMaterialStore(DataSource dataSource, ProductionActivationLedger ledger,
      ObjectMapper mapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
  }

  @Override public AppendResult append(CommandAdmission admission, TargetReviewCommandMaterial material) {
    Objects.requireNonNull(admission, "admission"); requireMaterial(admission, material);
    Canonical canonical = canonical(material);
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      requireTransaction(connection);
      CommandAdmissionResult admitted = ledger.admitCommand(connection, admission);
      Row current = findByAdmission(connection, admission.activationId(), admission.commandId(), true);
      if (current != null) {
        requireExact(admission, current);
        if (!current.json.equals(canonical.json) || !current.sha256.equals(canonical.sha256)) {
          throw conflict("Review command replay changed immutable material");
        }
        return new AppendResult(current.admissionId, admitted.admittedAt(), current.sha256, true);
      }
      insert(connection, admitted.admissionId(), admission, canonical);
      return new AppendResult(admitted.admissionId(), admitted.admittedAt(), canonical.sha256, false);
    } catch (SQLException failure) {
      throw new ProductionPersistenceException("REVIEW_MATERIAL_PERSISTENCE_FAILED", failure.getMessage(), failure);
    } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
  }

  @Override public Optional<Snapshot> readByRoute(Route route) {
    Objects.requireNonNull(route, "route");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      Row row = findByRoute(connection, route);
      if (row == null) return Optional.empty();
      CommandAdmission admission = row.admission(); requireExact(admission, row);
      TargetReviewCommandMaterial material = deserialize(row); requireMaterial(admission, material);
      return Optional.of(new Snapshot(row.admissionId, admission, material, row.sha256, row.storedAt));
    } catch (SQLException failure) {
      throw new ProductionPersistenceException("REVIEW_MATERIAL_ROUTE_READ_FAILED", failure.getMessage(), failure);
    } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
  }

  @Override public java.util.List<Snapshot> readByCommand(CommandRoute route) {
    Objects.requireNonNull(route, "route");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    String sql = "select admission_id, activation_id, activation_manifest_hash, isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id, command_hash, command_envelope_hash, room_epoch, room_fencing_token, material_canonical_json, material_sha256, stored_at from production_runtime_review_command_material where tenant_surrogate = ? and case_id = ? and command_id = ? and room_epoch = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, route.tenantSurrogate()); statement.setString(2, route.caseId());
      statement.setString(3, route.commandId()); statement.setLong(4, route.roomEpoch());
      try (ResultSet rows = statement.executeQuery()) {
        java.util.List<Snapshot> snapshots = new java.util.ArrayList<>();
        while (rows.next()) {
          Row row = row(rows); CommandAdmission admission = row.admission(); requireExact(admission, row);
          TargetReviewCommandMaterial material = deserialize(row); requireMaterial(admission, material);
          snapshots.add(new Snapshot(row.admissionId, admission, material, row.sha256, row.storedAt));
        }
        return java.util.List.copyOf(snapshots);
      }
    } catch (SQLException failure) {
      throw new ProductionPersistenceException("REVIEW_MATERIAL_COMMAND_READ_FAILED", failure.getMessage(), failure);
    } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
  }

  private Canonical canonical(TargetReviewCommandMaterial value) {
    JsonNode node = mapper.valueToTree(value);
    return new Canonical(ContractJson.canonicalString(node), ContractJson.sha256Hex(node));
  }
  private TargetReviewCommandMaterial deserialize(Row row) {
    try {
      JsonNode node = mapper.readTree(row.json);
      if (!ContractJson.canonicalString(node).equals(row.json) || !ContractJson.sha256Hex(node).equals(row.sha256)) {
        throw conflict("stored Review material hash is not canonical");
      }
      return mapper.treeToValue(node, TargetReviewCommandMaterial.class);
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      throw new ProductionPersistenceException("REVIEW_MATERIAL_INVALID", "stored Review material is invalid", failure);
    }
  }
  private static void requireMaterial(CommandAdmission admission, TargetReviewCommandMaterial material) {
    Objects.requireNonNull(material, "material"); var command = material.request().command();
    if (!TargetReviewCommandMaterial.TARGET_LANE.equals(material.executionLane())
        || !admission.activationId().equals(material.activationId())
        || !admission.manifestHash().equals(material.activationManifestHash())
        || admission.roomFencingToken() != material.roomFencingToken()
        || !admission.commandHash().equals(material.commandHash())
        || !admission.commandEnvelopeHash().equals(material.commandEnvelopeHash())
        || !admission.tenantSurrogate().equals(command.tenantSurrogate())
        || !admission.caseId().equals(command.caseId()) || !admission.commandId().equals(command.commandId())
        || admission.roomEpoch() != command.roomEpoch() || !"REVIEW".equals(command.roomType().name())) {
      throw conflict("Review material does not exactly bind its admission");
    }
  }
  private static void requireExact(CommandAdmission admission, Row row) {
    if (!admission.activationId().equals(row.activationId) || !admission.manifestHash().equals(row.manifestHash)
        || !admission.isolatedDomainDbBindingHash().equals(row.bindingHash)
        || !admission.tenantSurrogate().equals(row.tenant) || !admission.caseId().equals(row.caseId)
        || !admission.commandId().equals(row.commandId) || !admission.commandHash().equals(row.commandHash)
        || !admission.commandEnvelopeHash().equals(row.envelopeHash) || admission.roomEpoch() != row.epoch
        || admission.roomFencingToken() != row.fence) throw conflict("Review durable bindings differ from admission");
  }
  private static void requireTransaction(Connection connection) throws SQLException {
    if (connection.getAutoCommit()) throw new IllegalStateException("target Review material requires a caller-owned transaction");
  }
  private static void insert(Connection c, String admissionId, CommandAdmission a, Canonical m) throws SQLException {
    String sql = """
      insert into production_runtime_review_command_material (admission_id, activation_id, activation_manifest_hash,
        execution_lane, isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id, command_hash,
        command_envelope_hash, room_type, room_epoch, room_fencing_token, material_schema_version,
        material_canonical_json, material_sha256)
      values (?, ?, ?, 'PRODUCTION', ?, ?, ?, ?, ?, ?, 'REVIEW', ?, ?,
        'production-runtime-review-command-material.v1', ?, ?)
      """;
    try (PreparedStatement s = c.prepareStatement(sql)) {
      int i = 1; s.setString(i++, admissionId); s.setString(i++, a.activationId()); s.setString(i++, a.manifestHash());
      s.setString(i++, a.isolatedDomainDbBindingHash()); s.setString(i++, a.tenantSurrogate());
      s.setString(i++, a.caseId()); s.setString(i++, a.commandId()); s.setString(i++, a.commandHash());
      s.setString(i++, a.commandEnvelopeHash()); s.setLong(i++, a.roomEpoch()); s.setLong(i++, a.roomFencingToken());
      s.setString(i++, m.json); s.setString(i, m.sha256); s.executeUpdate();
    }
  }
  private static Row findByAdmission(Connection c, String activationId, String commandId, boolean lock) throws SQLException {
    String sql = "select admission_id, activation_id, activation_manifest_hash, isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id, command_hash, command_envelope_hash, room_epoch, room_fencing_token, material_canonical_json, material_sha256, stored_at from production_runtime_review_command_material where activation_id = ? and command_id = ?" + (lock ? " for update" : "");
    try (PreparedStatement s = c.prepareStatement(sql)) { s.setString(1, activationId); s.setString(2, commandId);
      try (ResultSet r = s.executeQuery()) { return r.next() ? row(r) : null; } }
  }
  private static Row findByRoute(Connection c, Route route) throws SQLException {
    String sql = "select admission_id, activation_id, activation_manifest_hash, isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id, command_hash, command_envelope_hash, room_epoch, room_fencing_token, material_canonical_json, material_sha256, stored_at from production_runtime_review_command_material where tenant_surrogate = ? and case_id = ? and command_id = ? and room_epoch = ? and room_fencing_token = ?";
    try (PreparedStatement s = c.prepareStatement(sql)) {
      s.setString(1, route.tenantSurrogate()); s.setString(2, route.caseId()); s.setString(3, route.commandId());
      s.setLong(4, route.roomEpoch()); s.setLong(5, route.roomFencingToken());
      try (ResultSet r = s.executeQuery()) { if (!r.next()) return null; Row row = row(r);
        if (r.next()) throw conflict("Review route is ambiguous"); return row; }
    }
  }
  private static Row row(ResultSet r) throws SQLException {
    return new Row(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
        r.getString(7), r.getString(8), r.getString(9), r.getLong(10), r.getLong(11), r.getString(12),
        r.getString(13), r.getTimestamp(14).toInstant());
  }
  private static ProductionPersistenceException conflict(String message) {
    return new ProductionPersistenceException("REVIEW_MATERIAL_CONFLICT", message);
  }
  private record Canonical(String json, String sha256) {}
  private record Row(String admissionId, String activationId, String manifestHash, String bindingHash, String tenant,
      String caseId, String commandId, String commandHash, String envelopeHash, long epoch, long fence,
      String json, String sha256, Instant storedAt) {
    CommandAdmission admission() { return new CommandAdmission(activationId, manifestHash, bindingHash, tenant, caseId,
        commandId, commandHash, envelopeHash, epoch, fence); }
  }
}
