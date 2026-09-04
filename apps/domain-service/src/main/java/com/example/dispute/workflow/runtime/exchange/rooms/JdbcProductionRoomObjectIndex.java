package com.example.dispute.workflow.runtime.exchange.rooms;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.Authority;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Append-only implementation; any identity drift is rejected rather than selecting a newer row. */
public class JdbcProductionRoomObjectIndex implements ProductionRoomObjectIndex {
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcProductionRoomObjectIndex(DataSource dataSource) {
    this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
  }

  @Override @Transactional public void bindInput(
      Authority authority, RoomGraphCommand command, StoredObject object, Kind kind) {
    if (kind == Kind.PROPOSAL) throw new IllegalArgumentException("proposal requires proposal identity");
    requireAuthority(authority, command);
    insert(authority, command, object, kind, null);
  }

  @Override @Transactional public void rebindInputs(Authority sourceAuthority, RoomGraphCommand sourceCommand,
      Authority targetAuthority, RoomGraphCommand targetCommand) {
    requireAuthority(sourceAuthority, sourceCommand);
    requireAuthority(targetAuthority, targetCommand);
    if (!sourceCommand.logicalRunId().equals(targetCommand.logicalRunId())
        || sourceCommand.commandId().equals(targetCommand.commandId())
        || sourceCommand.attemptId().equals(targetCommand.attemptId())
        || !Objects.equals(sourceCommand.domainSnapshotRef(), targetCommand.domainSnapshotRef())
        || !Objects.equals(sourceCommand.eventRef(), targetCommand.eventRef())) {
      throw new IllegalArgumentException("retry command does not preserve immutable room inputs");
    }
    List<Map<String, Object>> inputs = jdbc.queryForList("""
        select binding.object_ref, binding.object_kind, object.artifact_id,
               object.schema_version, object.content_sha256, object.size_bytes,
               object.storage_bucket, object.storage_key
          from production_runtime_room_object_binding binding
          join production_runtime_room_object_index object
            on object.object_ref = binding.object_ref
         where binding.activation_id=:activationId and binding.tenant_surrogate=:tenant
           and binding.case_id=:caseId and binding.room_type=:room
           and binding.room_epoch=:epoch and binding.room_fencing_token=:fence
           and binding.command_id=:commandId and binding.logical_run_id=:run
           and binding.attempt_id=:attempt
           and binding.object_kind in ('COMMAND_INPUT','MANIFEST_ASSET')
        """, params(sourceAuthority, sourceCommand));
    if (inputs.isEmpty()) {
      throw new IllegalStateException("retry source has no admitted immutable room inputs");
    }
    for (Map<String, Object> row : inputs) {
      Kind kind = Kind.valueOf(text(row, "object_kind"));
      StoredObject object = object(row);
      insert(targetAuthority, targetCommand, object, kind, null);
    }
  }

  @Override @Transactional public StoredObject recordProposal(Authority authority, RoomGraphCommand command,
      ProposalIdentity proposal, String bucket, String key) {
    requireAuthority(authority, command);
    Objects.requireNonNull(proposal, "proposal");
    StoredObject object = new StoredObject(proposalObjectRef(authority, command, proposal),
        proposal.proposalId(), proposal.schemaVersion(), proposal.sha256(),
        proposal.sizeBytes(), bucket, key);
    return insert(authority, command, object, Kind.PROPOSAL, proposal);
  }

  @Override public Optional<StoredObject> findAdmitted(Authority authority, RoomGraphCommand command,
      ProductionRoomExchangeContract.ObjectRef ref) {
    requireAuthority(authority, command);
    return unique("""
        select object.object_ref, object.artifact_id, object.schema_version,
               object.content_sha256, object.size_bytes, object.storage_bucket, object.storage_key
          from production_runtime_room_object_binding binding
          join production_runtime_room_object_index object on object.object_ref=binding.object_ref
         where binding.activation_id=:activationId and binding.tenant_surrogate=:tenant
           and binding.case_id=:caseId and binding.room_type=:room
           and binding.room_epoch=:epoch and binding.room_fencing_token=:fence
           and binding.command_id=:commandId and binding.logical_run_id=:run
           and binding.attempt_id=:attempt and binding.object_ref=:objectRef
           and (:allowAssetSize=true or object.artifact_id=:artifactId)
           and object.schema_version=:schemaVersion and object.content_sha256=:sha256
           and (:allowAssetSize=true or object.size_bytes=:size)
        """, params(authority, command).addValue("objectRef", ref.uri()).addValue("artifactId", ref.artifactId())
        .addValue("schemaVersion", ref.schemaVersion()).addValue("sha256", ref.sha256()).addValue("size", ref.sizeBytes())
        .addValue("allowAssetSize", "production-runtime-evidence-asset.v1".equals(ref.schemaVersion())));
  }

  @Override public Optional<StoredObject> findProposal(ProposalLookup lookup) {
    MapSqlParameterSource p = new MapSqlParameterSource().addValue("activationId", lookup.activationId())
        .addValue("tenant", lookup.tenantSurrogate()).addValue("caseId", lookup.caseId()).addValue("room", lookup.roomType())
        .addValue("epoch", lookup.roomEpoch()).addValue("fence", lookup.roomFencingToken()).addValue("commandId", lookup.commandId())
        .addValue("run", lookup.logicalRunId()).addValue("attempt", lookup.attemptId()).addValue("proposalId", lookup.proposalId())
        .addValue("schemaVersion", lookup.payloadSchemaVersion()).addValue("objectRef", lookup.payloadRef()).addValue("sha256", lookup.payloadHash());
    return unique("""
        select object.object_ref, object.artifact_id, object.schema_version,
               object.content_sha256, object.size_bytes, object.storage_bucket, object.storage_key
          from production_runtime_room_object_binding binding
          join production_runtime_room_object_index object on object.object_ref=binding.object_ref
         where binding.object_kind='PROPOSAL' and binding.activation_id=:activationId
           and binding.tenant_surrogate=:tenant and binding.case_id=:caseId
           and binding.room_type=:room and binding.room_epoch=:epoch
           and binding.room_fencing_token=:fence and binding.command_id=:commandId
           and binding.logical_run_id=:run and binding.attempt_id=:attempt
           and binding.artifact_id=:proposalId and binding.schema_version=:schemaVersion
           and binding.object_ref=:objectRef and object.content_sha256=:sha256
        """, p);
  }

  private StoredObject insert(Authority authority, RoomGraphCommand c, StoredObject requested,
      Kind kind, ProposalIdentity proposal) {
    StoredObject stored = ensureObject(authority, c, requested, kind, proposal);
    insertBinding(authority, c, stored, kind, proposal);
    return stored;
  }

  private StoredObject ensureObject(Authority authority, RoomGraphCommand c, StoredObject o,
      Kind kind, ProposalIdentity proposal) {
    MapSqlParameterSource p = params(authority, c).addValue("objectRef", o.objectRef()).addValue("kind", kind.name())
        .addValue("artifactId", o.artifactId()).addValue("schemaVersion", o.schemaVersion()).addValue("sha256", o.sha256())
        .addValue("size", o.sizeBytes()).addValue("bucket", o.storageBucket()).addValue("key", o.storageKey())
        .addValue("checkpointNs", proposal == null ? null : proposal.checkpointNs())
        .addValue("checkpointId", proposal == null ? null : proposal.checkpointId())
        .addValue("revision", proposal == null ? null : proposal.cognitiveRevision());
    List<Map<String, Object>> existing = jdbc.queryForList(
        "select object_ref, object_kind, artifact_id, schema_version, content_sha256, size_bytes, storage_bucket, storage_key from production_runtime_room_object_index where object_ref=:objectRef", p);
    if (!existing.isEmpty()) {
      if (existing.size() != 1 || !sameObject(existing.getFirst(), p)) {
        throw new IllegalStateException("target room object index replay drift");
      }
      return object(existing.getFirst());
    }
    List<Map<String, Object>> migrated = jdbc.queryForList(
        "select object_ref, object_kind, artifact_id, schema_version, content_sha256, size_bytes, storage_bucket, storage_key from production_runtime_room_object_index where storage_bucket=:bucket and storage_key=:key", p);
    if (!migrated.isEmpty()) {
      if (migrated.size() != 1 || !sameObject(migrated.getFirst(), p)) {
        throw new IllegalStateException("target room object storage replay drift");
      }
      return object(migrated.getFirst());
    }
    int count = jdbc.update("""
        insert into production_runtime_room_object_index (object_ref, object_kind, activation_id, tenant_surrogate, case_id, room_type,
          room_epoch, room_fencing_token, command_id, logical_run_id, attempt_id, artifact_id, schema_version, content_sha256,
          size_bytes, storage_bucket, storage_key, checkpoint_ns, checkpoint_id, cognitive_revision)
        values (:objectRef,:kind,:activationId,:tenant,:caseId,:room,:epoch,:fence,:commandId,:run,:attempt,:artifactId,
          :schemaVersion,:sha256,:size,:bucket,:key,:checkpointNs,:checkpointId,:revision)
        on conflict do nothing
        """, p);
    if (count < 0 || count > 1) {
      throw new IllegalStateException("target room object index write failed");
    }
    List<Map<String, Object>> persisted = jdbc.queryForList(
        "select object_ref, object_kind, artifact_id, schema_version, content_sha256, size_bytes, storage_bucket, storage_key from production_runtime_room_object_index where object_ref=:objectRef", p);
    if (persisted.isEmpty()) {
      persisted = jdbc.queryForList(
          "select object_ref, object_kind, artifact_id, schema_version, content_sha256, size_bytes, storage_bucket, storage_key from production_runtime_room_object_index where storage_bucket=:bucket and storage_key=:key", p);
    }
    if (persisted.size() != 1 || !sameObject(persisted.getFirst(), p)) {
      throw new IllegalStateException("target room object index first-wins conflict");
    }
    return object(persisted.getFirst());
  }

  private void insertBinding(Authority authority, RoomGraphCommand c, StoredObject o,
      Kind kind, ProposalIdentity proposal) {
    MapSqlParameterSource p = params(authority, c).addValue("objectRef", o.objectRef())
        .addValue("kind", kind.name()).addValue("artifactId", o.artifactId())
        .addValue("schemaVersion", o.schemaVersion()).addValue("sha256", o.sha256())
        .addValue("size", o.sizeBytes()).addValue("checkpointNs", proposal == null ? null : proposal.checkpointNs())
        .addValue("checkpointId", proposal == null ? null : proposal.checkpointId())
        .addValue("revision", proposal == null ? null : proposal.cognitiveRevision());
    List<Map<String, Object>> existing = jdbc.queryForList("""
        select object_kind, artifact_id, schema_version, logical_run_id, attempt_id,
               checkpoint_ns, checkpoint_id, cognitive_revision
          from production_runtime_room_object_binding
         where activation_id=:activationId and tenant_surrogate=:tenant and case_id=:caseId
           and room_type=:room and room_epoch=:epoch and room_fencing_token=:fence
           and command_id=:commandId and object_ref=:objectRef
        """, p);
    if (!existing.isEmpty()) {
      if (existing.size() != 1 || !sameBinding(existing.getFirst(), p)) {
        throw new IllegalStateException("target room object binding replay drift");
      }
      return;
    }
    int count = jdbc.update("""
        insert into production_runtime_room_object_binding (
          object_ref, object_kind, artifact_id, schema_version,
          activation_id, tenant_surrogate, case_id, room_type,
          room_epoch, room_fencing_token, command_id, logical_run_id, attempt_id,
          checkpoint_ns, checkpoint_id, cognitive_revision)
        values (:objectRef,:kind,:artifactId,:schemaVersion,:activationId,:tenant,:caseId,:room,:epoch,:fence,
          :commandId,:run,:attempt,:checkpointNs,:checkpointId,:revision)
        on conflict do nothing
        """, p);
    if (count < 0 || count > 1) {
      throw new IllegalStateException("target room object binding write failed");
    }
    List<Map<String, Object>> persisted = jdbc.queryForList("""
        select object_kind, artifact_id, schema_version, logical_run_id, attempt_id,
               checkpoint_ns, checkpoint_id, cognitive_revision
          from production_runtime_room_object_binding
         where activation_id=:activationId and tenant_surrogate=:tenant and case_id=:caseId
           and room_type=:room and room_epoch=:epoch and room_fencing_token=:fence
           and command_id=:commandId and object_ref=:objectRef
        """, p);
    if (persisted.size() != 1 || !sameBinding(persisted.getFirst(), p)) {
      throw new IllegalStateException("target room object binding first-wins conflict");
    }
  }

  private Optional<StoredObject> unique(String sql, MapSqlParameterSource p) {
    List<StoredObject> values = jdbc.query(sql, p, (rs, row) -> new StoredObject(
        rs.getString("object_ref"), rs.getString("artifact_id"), rs.getString("schema_version"),
        rs.getString("content_sha256"), rs.getLong("size_bytes"), rs.getString("storage_bucket"),
        rs.getString("storage_key")));
    if (values.size() > 1) throw new IllegalStateException("target room object index is ambiguous");
    return values.stream().findFirst();
  }

  private static MapSqlParameterSource params(Authority authority, RoomGraphCommand c) {
    return new MapSqlParameterSource().addValue("activationId", authority.activationId()).addValue("tenant", c.tenantSurrogate())
        .addValue("caseId", c.caseId()).addValue("room", c.roomType().name()).addValue("epoch", c.roomEpoch())
        .addValue("fence", authority.roomFencingToken()).addValue("commandId", c.commandId()).addValue("run", c.logicalRunId()).addValue("attempt", c.attemptId());
  }

  private static void requireAuthority(Authority a, RoomGraphCommand c) {
    if (!a.activationId().matches("p9act\\.v1\\.[0-9a-f]{32}") || !a.tenantSurrogate().equals(c.tenantSurrogate())
        || !a.caseId().equals(c.caseId()) || !a.roomType().equals(c.roomType().name()) || a.roomEpoch() != c.roomEpoch()
        || !a.commandId().equals(c.commandId()) || !a.logicalRunId().equals(c.logicalRunId()) || !a.attemptId().equals(c.attemptId())) {
      throw new IllegalArgumentException("authority does not bind target room command");
    }
  }

  private static boolean sameObject(Map<String, Object> row, MapSqlParameterSource p) {
    return Objects.equals(row.get("object_kind"), p.getValue("kind")) && Objects.equals(row.get("artifact_id"), p.getValue("artifactId"))
        && Objects.equals(row.get("schema_version"), p.getValue("schemaVersion")) && Objects.equals(row.get("content_sha256"), p.getValue("sha256"))
        && ((Number) row.get("size_bytes")).longValue() == ((Number) p.getValue("size")).longValue()
        && Objects.equals(row.get("storage_bucket"), p.getValue("bucket"))
        && Objects.equals(row.get("storage_key"), p.getValue("key"));
  }

  static boolean sameBinding(Map<String, Object> row, MapSqlParameterSource p) {
    return Objects.equals(row.get("object_kind"), p.getValue("kind"))
        && Objects.equals(row.get("artifact_id"), p.getValue("artifactId"))
        && Objects.equals(row.get("schema_version"), p.getValue("schemaVersion"))
        && Objects.equals(row.get("logical_run_id"), p.getValue("run"))
        && Objects.equals(row.get("attempt_id"), p.getValue("attempt"))
        && Objects.equals(row.get("checkpoint_ns"), p.getValue("checkpointNs"))
        && Objects.equals(row.get("checkpoint_id"), p.getValue("checkpointId"))
        && Objects.equals(row.get("cognitive_revision"), p.getValue("revision"));
  }

  private static StoredObject object(Map<String, Object> row) {
    return new StoredObject(text(row, "object_ref"), text(row, "artifact_id"),
        text(row, "schema_version"), text(row, "content_sha256"),
        ((Number) row.get("size_bytes")).longValue(), text(row, "storage_bucket"),
        text(row, "storage_key"));
  }

  private static String text(Map<String, Object> row, String field) {
    Object value = row.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalStateException("target room object row is malformed");
    }
    return text;
  }

  static String proposalObjectRef(
      Authority authority, RoomGraphCommand command, ProposalIdentity proposal) {
    ObjectNode identity = JsonNodeFactory.instance.objectNode();
    identity.put("schema_version", "production-runtime-room-proposal-object-identity.v1");
    identity.put("activation_id", authority.activationId());
    identity.put("tenant_surrogate", command.tenantSurrogate());
    identity.put("case_id", command.caseId());
    identity.put("room_type", command.roomType().name());
    identity.put("room_epoch", command.roomEpoch());
    identity.put("room_fencing_token", authority.roomFencingToken());
    identity.put("command_id", command.commandId());
    identity.put("logical_run_id", command.logicalRunId());
    identity.put("attempt_id", command.attemptId());
    identity.put("proposal_id", proposal.proposalId());
    identity.put("payload_schema_version", proposal.schemaVersion());
    identity.put("payload_hash", proposal.sha256());
    identity.put("checkpoint_ns", proposal.checkpointNs());
    identity.put("checkpoint_id", proposal.checkpointId());
    identity.put("cognitive_revision", proposal.cognitiveRevision());
    return "urn:production-runtime:proposal:"
        + command.roomType().name().toLowerCase(Locale.ROOT)
        + ":"
        + ContractJson.sha256Hex(identity);
  }
}
