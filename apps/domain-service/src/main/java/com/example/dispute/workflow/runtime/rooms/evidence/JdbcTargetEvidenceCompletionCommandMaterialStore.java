package com.example.dispute.workflow.runtime.rooms.evidence;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmission;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmissionResult;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmissionSnapshot;
import com.example.dispute.workflow.runtime.persistence.ProductionPersistenceException;
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

/** PostgreSQL append-only store; admission and material share the caller transaction. */
public final class JdbcTargetEvidenceCompletionCommandMaterialStore
    implements TargetEvidenceCompletionCommandMaterialStore {
  static final String PROVENANCE_SQL = """
      select c.command_type, c.room_type, c.room_epoch, c.command_status,
             c.result_uri, c.result_sha256,
             m.admission_id, m.activation_id, m.activation_manifest_hash,
             m.isolated_domain_db_binding_hash, m.tenant_surrogate, m.case_id,
             m.command_id, m.command_hash, m.command_envelope_hash,
             m.room_epoch, m.room_fencing_token, m.case_command_request_hash,
             m.expected_process_revision, m.expected_room_revision,
             m.actor_id, m.actor_role, m.actor_scopes_json::text,
             m.payload_schema_version, m.payload_uri, m.payload_sha256,
             m.payload_size_bytes, m.deadline_at, m.trace_id,
             m.material_schema_version, m.material_canonical_json, m.material_sha256,
             a.admission_id
        from case_command c
        left join production_runtime_evidence_completion_command_material m
          on m.tenant_surrogate = c.tenant_surrogate
         and m.case_id = c.case_id
         and m.command_id = c.command_id
         and m.room_type = c.room_type
         and m.room_epoch = c.room_epoch
         and m.room_epoch = ?
         and m.room_fencing_token = ?
         and m.case_command_request_hash = c.request_hash
         and m.expected_process_revision = c.expected_process_revision
         and m.actor_id = c.actor_id
         and m.actor_role = c.actor_role
         and m.actor_scopes_json = c.actor_scopes_json
         and m.payload_schema_version = c.payload_schema_version
         and m.payload_uri = c.payload_uri
         and m.payload_sha256 = c.payload_sha256
         and m.payload_size_bytes = c.payload_size_bytes
         and m.deadline_at = c.deadline_at
         and m.trace_id = substring(c.traceparent from 4 for 32)
         and m.execution_lane = 'PRODUCTION'
         and m.material_schema_version = 'production-runtime-evidence-completion-command-material.v1'
        left join production_runtime_command_admission a
          on a.admission_id = m.admission_id
         and a.activation_id = m.activation_id
         and a.activation_manifest_hash = m.activation_manifest_hash
         and a.execution_lane = m.execution_lane
         and a.isolated_domain_db_binding_hash = m.isolated_domain_db_binding_hash
         and a.tenant_surrogate = m.tenant_surrogate
         and a.case_id = m.case_id
         and a.command_id = m.command_id
         and a.command_hash = m.command_hash
         and a.command_envelope_hash = m.command_envelope_hash
         and a.room_epoch = m.room_epoch
         and a.room_fencing_token = m.room_fencing_token
       where c.tenant_surrogate = ? and c.case_id = ? and c.command_id = ?
      """;

  private final DataSource dataSource;
  private final ProductionActivationLedger ledger;
  private final ObjectMapper mapper;

  public JdbcTargetEvidenceCompletionCommandMaterialStore(
      DataSource dataSource, ProductionActivationLedger ledger, ObjectMapper mapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
  }

  @Override
  public AppendResult append(
      CommandAdmission admission, TargetEvidenceCompletionCommandMaterial material) {
    requireMaterial(admission, material);
    String canonical = ContractJson.canonicalString(mapper.valueToTree(material));
    String materialHash = ContractJson.sha256Hex(mapper.valueToTree(material));
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (connection.isClosed() || connection.getAutoCommit()) {
        throw new IllegalStateException(
            "target Evidence completion material requires a caller-owned transaction");
      }
      CommandAdmissionResult admitted = ledger.admitCommand(connection, admission);
      Stored stored = find(connection, admission.activationId(), admission.commandId());
      if (stored != null) {
        if (!stored.admissionId().equals(admitted.admissionId())
            || !stored.canonical().equals(canonical)
            || !stored.materialHash().equals(materialHash)) {
          throw conflict("Evidence completion material replay changed immutable authority");
        }
        return new AppendResult(stored.admissionId(), admitted.admittedAt(), materialHash, true);
      }
      insert(connection, admitted.admissionId(), admission, material, canonical, materialHash);
      return new AppendResult(admitted.admissionId(), admitted.admittedAt(), materialHash, false);
    } catch (SQLException failure) {
      throw new ProductionPersistenceException(
          "EVIDENCE_COMPLETION_MATERIAL_PERSISTENCE_FAILED", failure.getMessage(), failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  @Override
  public Optional<Provenance> readProvenance(Route route) {
    Objects.requireNonNull(route, "route");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      requireTransaction(connection);
      JoinedAuthority joined = findProvenance(connection, route);
      if (joined == null) return Optional.empty();
      if (joined.material() == null || joined.joinedAdmissionId() == null) {
        throw conflict(
            "Evidence completion case command does not exactly join material and admission");
      }
      TargetEvidenceCompletionCommandMaterial material = deserialize(joined.material());
      CommandAdmission admission = joined.material().admission();
      requireMaterial(admission, material);
      requireStoredMaterial(joined.material(), material);
      requireRoute(route, material);
      CommandAdmissionSnapshot ledgerSnapshot = ledger
          .queryCommandAdmission(connection, material.activationId(), material.commandId())
          .orElseThrow(() -> conflict("Evidence completion activation admission is absent"));
      return Optional.of(classifyProvenance(
          route,
          admission,
          material,
          joined.commandStatus(),
          joined.resultUri(),
          joined.resultHash(),
          ledgerSnapshot,
          joined.joinedAdmissionId()));
    } catch (SQLException failure) {
      throw new ProductionPersistenceException(
          "EVIDENCE_COMPLETION_PROVENANCE_READ_FAILED", failure.getMessage(), failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  static void requireMaterial(
      CommandAdmission admission, TargetEvidenceCompletionCommandMaterial material) {
    Objects.requireNonNull(admission, "admission");
    Objects.requireNonNull(material, "material");
    if (!admission.activationId().equals(material.activationId())
        || !admission.manifestHash().equals(material.activationManifestHash())
        || !admission.isolatedDomainDbBindingHash().equals(material.isolatedDomainDbBindingHash())
        || !admission.tenantSurrogate().equals(material.tenantSurrogate())
        || !admission.caseId().equals(material.caseId())
        || !admission.commandId().equals(material.commandId())
        || !admission.commandHash().equals(material.commandHash())
        || !admission.commandEnvelopeHash().equals(material.commandEnvelopeHash())
        || admission.roomEpoch() != material.roomEpoch()
        || admission.roomFencingToken() != material.roomFencingToken()) {
      throw conflict("Evidence completion material does not exactly bind its admission");
    }
  }

  static Provenance classifyProvenance(
      Route route,
      CommandAdmission admission,
      TargetEvidenceCompletionCommandMaterial material,
      String commandStatus,
      String resultUri,
      String resultHash,
      CommandAdmissionSnapshot ledgerSnapshot,
      String joinedAdmissionId) {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(admission, "admission");
    Objects.requireNonNull(material, "material");
    if (ledgerSnapshot == null) {
      throw conflict("Evidence completion activation admission is absent");
    }
    requireRoute(route, material);
    requireLedger(admission, ledgerSnapshot, joinedAdmissionId);
    if ("PENDING_ORCHESTRATION".equals(commandStatus)
        || "ORCHESTRATION_ACCEPTED".equals(commandStatus)) {
      if (resultUri != null || resultHash != null || ledgerSnapshot.completed()
          || ledgerSnapshot.completionHash() != null || ledgerSnapshot.completedAt() != null) {
        throw conflict("Evidence completion in-flight provenance contains completion evidence");
      }
      return Provenance.IN_FLIGHT;
    }
    if ("APPLIED".equals(commandStatus)) {
      if (!(JdbcTargetEvidencePartyCompletionActivities.RESULT_URI_PREFIX + route.completionId())
              .equals(resultUri)
          || resultHash == null
          || !ledgerSnapshot.completed()
          || ledgerSnapshot.completedAt() == null
          || !resultHash.equals(ledgerSnapshot.completionHash())) {
        throw conflict("Evidence completion applied provenance is not exact");
      }
      return Provenance.APPLIED_EXACT;
    }
    throw conflict("Evidence completion command status has no replay provenance");
  }

  private static void requireRoute(
      Route route, TargetEvidenceCompletionCommandMaterial material) {
    if (!route.tenantSurrogate().equals(material.tenantSurrogate())
        || !route.caseId().equals(material.caseId())
        || !route.commandId().equals(material.commandId())
        || route.roomEpoch() != material.roomEpoch()
        || route.roomFencingToken() != material.roomFencingToken()) {
      throw conflict("Evidence completion material differs from its replay route");
    }
  }

  private static void requireLedger(
      CommandAdmission admission,
      CommandAdmissionSnapshot snapshot,
      String joinedAdmissionId) {
    if (joinedAdmissionId == null
        || !joinedAdmissionId.equals(snapshot.admissionId())
        || !admission.activationId().equals(snapshot.activationId())
        || !admission.manifestHash().equals(snapshot.activationManifestHash())
        || !admission.isolatedDomainDbBindingHash().equals(snapshot.isolatedDomainDbBindingHash())
        || !admission.tenantSurrogate().equals(snapshot.tenantSurrogate())
        || !admission.caseId().equals(snapshot.caseId())
        || !admission.commandId().equals(snapshot.commandId())
        || !admission.commandHash().equals(snapshot.commandHash())
        || !admission.commandEnvelopeHash().equals(snapshot.commandEnvelopeHash())
        || admission.roomEpoch() != snapshot.roomEpoch()
        || admission.roomFencingToken() != snapshot.roomFencingToken()) {
      throw conflict("Evidence completion activation admission drifted");
    }
  }

  private static void requireStoredMaterial(
      MaterialRow row, TargetEvidenceCompletionCommandMaterial material) {
    String actorScopes = ContractJson.canonicalString(mapperForValidation().valueToTree(
        material.actorRef().actorScopes()));
    if (!row.requestHash().equals(material.caseCommandRequestHash())
        || row.expectedProcessRevision() != material.expectedProcessRevision()
        || row.expectedRoomRevision() != material.expectedRoomRevision()
        || !row.actorId().equals(material.actorRef().actorId())
        || !row.actorRole().equals(material.actorRef().actorRole().name())
        || !ContractJson.canonicalString(parseJson(row.actorScopesJson())).equals(actorScopes)
        || !row.payloadSchemaVersion().equals(material.payloadRef().schemaVersion())
        || !row.payloadUri().equals(material.payloadRef().uri())
        || !row.payloadHash().equals(material.payloadRef().sha256())
        || row.payloadSize() != material.payloadRef().sizeBytes()
        || !row.deadlineAt().equals(material.deadlineAt())
        || !row.traceId().equals(material.traceId())
        || !row.schemaVersion().equals(material.schemaVersion())) {
      throw conflict("Evidence completion stored material columns drifted");
    }
  }

  private TargetEvidenceCompletionCommandMaterial deserialize(MaterialRow row) {
    try {
      JsonNode value = mapper.readTree(row.canonical());
      if (!ContractJson.canonicalString(value).equals(row.canonical())
          || !ContractJson.sha256Hex(value).equals(row.materialHash())) {
        throw conflict("Evidence completion stored material hash is not canonical");
      }
      return mapper.treeToValue(value, TargetEvidenceCompletionCommandMaterial.class);
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      throw new ProductionPersistenceException(
          "EVIDENCE_COMPLETION_MATERIAL_INVALID",
          "stored Evidence completion material is invalid",
          failure);
    }
  }

  private static JoinedAuthority findProvenance(Connection connection, Route route)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(PROVENANCE_SQL)) {
      statement.setLong(1, route.roomEpoch());
      statement.setLong(2, route.roomFencingToken());
      statement.setString(3, route.tenantSurrogate());
      statement.setString(4, route.caseId());
      statement.setString(5, route.commandId());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) return null;
        String commandType = rows.getString(1);
        String roomType = rows.getString(2);
        long roomEpoch = rows.getLong(3);
        String status = rows.getString(4);
        String resultUri = rows.getString(5);
        String resultHash = rows.getString(6);
        MaterialRow material = rows.getString(7) == null ? null : materialRow(rows);
        String joinedAdmissionId = rows.getString(33);
        if (rows.next()) throw conflict("Evidence completion provenance is ambiguous");
        if (!"PARTY_EVIDENCE_COMPLETE".equals(commandType)
            || !"EVIDENCE".equals(roomType)
            || roomEpoch != route.roomEpoch()) {
          throw conflict("Evidence completion case command differs from its replay route");
        }
        return new JoinedAuthority(status, resultUri, resultHash, material, joinedAdmissionId);
      }
    }
  }

  private static MaterialRow materialRow(ResultSet rows) throws SQLException {
    CommandAdmission admission = new CommandAdmission(
        rows.getString(8), rows.getString(9), rows.getString(10), rows.getString(11),
        rows.getString(12), rows.getString(13), rows.getString(14), rows.getString(15),
        rows.getLong(16), rows.getLong(17));
    Timestamp deadline = rows.getTimestamp(28);
    return new MaterialRow(
        rows.getString(7), admission, rows.getString(18), rows.getLong(19), rows.getLong(20),
        rows.getString(21), rows.getString(22), rows.getString(23), rows.getString(24),
        rows.getString(25), rows.getString(26), rows.getLong(27), deadline.toInstant(),
        rows.getString(29), rows.getString(30), rows.getString(31), rows.getString(32));
  }

  private static ObjectMapper mapperForValidation() {
    return new ObjectMapper();
  }

  private static JsonNode parseJson(String json) {
    try {
      return mapperForValidation().readTree(json);
    } catch (JsonProcessingException failure) {
      throw conflict("Evidence completion actor scopes are malformed");
    }
  }

  private static void requireTransaction(Connection connection) throws SQLException {
    if (connection.isClosed() || connection.getAutoCommit()) {
      throw new IllegalStateException(
          "target Evidence completion material requires a caller-owned transaction");
    }
  }

  private static Stored find(Connection connection, String activationId, String commandId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select admission_id, material_canonical_json, material_sha256
          from production_runtime_evidence_completion_command_material
         where activation_id = ? and command_id = ? for update
        """)) {
      statement.setString(1, activationId);
      statement.setString(2, commandId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) return null;
        Stored stored = new Stored(rows.getString(1), rows.getString(2), rows.getString(3));
        if (rows.next()) throw conflict("Evidence completion material is ambiguous");
        return stored;
      }
    }
  }

  private static void insert(
      Connection connection,
      String admissionId,
      CommandAdmission admission,
      TargetEvidenceCompletionCommandMaterial material,
      String canonical,
      String materialHash)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        insert into production_runtime_evidence_completion_command_material (
          admission_id, activation_id, activation_manifest_hash, execution_lane,
          isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id,
          command_hash, command_envelope_hash, room_type, room_epoch, room_fencing_token,
          case_command_request_hash, expected_process_revision, expected_room_revision,
          actor_id, actor_role, actor_scopes_json, payload_schema_version, payload_uri,
          payload_sha256, payload_size_bytes, deadline_at, trace_id,
          material_schema_version, material_canonical_json, material_sha256)
        values (?, ?, ?, 'PRODUCTION', ?, ?, ?, ?, ?, ?, 'EVIDENCE', ?, ?,
                ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      int index = 1;
      statement.setString(index++, admissionId);
      statement.setString(index++, admission.activationId());
      statement.setString(index++, admission.manifestHash());
      statement.setString(index++, admission.isolatedDomainDbBindingHash());
      statement.setString(index++, admission.tenantSurrogate());
      statement.setString(index++, admission.caseId());
      statement.setString(index++, admission.commandId());
      statement.setString(index++, admission.commandHash());
      statement.setString(index++, admission.commandEnvelopeHash());
      statement.setLong(index++, admission.roomEpoch());
      statement.setLong(index++, admission.roomFencingToken());
      statement.setString(index++, material.caseCommandRequestHash());
      statement.setLong(index++, material.expectedProcessRevision());
      statement.setLong(index++, material.expectedRoomRevision());
      statement.setString(index++, material.actorRef().actorId());
      statement.setString(index++, material.actorRef().actorRole().name());
      statement.setString(index++, ContractJson.canonicalString(
          new ObjectMapper().valueToTree(material.actorRef().actorScopes())));
      statement.setString(index++, material.payloadRef().schemaVersion());
      statement.setString(index++, material.payloadRef().uri());
      statement.setString(index++, material.payloadRef().sha256());
      statement.setLong(index++, material.payloadRef().sizeBytes());
      statement.setTimestamp(index++, java.sql.Timestamp.from(material.deadlineAt()));
      statement.setString(index++, material.traceId());
      statement.setString(index++, material.schemaVersion());
      statement.setString(index++, canonical);
      statement.setString(index, materialHash);
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("target Evidence completion material insert failed");
      }
    }
  }

  private static ProductionPersistenceException conflict(String message) {
    return new ProductionPersistenceException("EVIDENCE_COMPLETION_MATERIAL_CONFLICT", message);
  }

  private record Stored(String admissionId, String canonical, String materialHash) {}

  private record JoinedAuthority(
      String commandStatus,
      String resultUri,
      String resultHash,
      MaterialRow material,
      String joinedAdmissionId) {}

  private record MaterialRow(
      String admissionId,
      CommandAdmission admission,
      String requestHash,
      long expectedProcessRevision,
      long expectedRoomRevision,
      String actorId,
      String actorRole,
      String actorScopesJson,
      String payloadSchemaVersion,
      String payloadUri,
      String payloadHash,
      long payloadSize,
      Instant deadlineAt,
      String traceId,
      String schemaVersion,
      String canonical,
      String materialHash) {}
}
