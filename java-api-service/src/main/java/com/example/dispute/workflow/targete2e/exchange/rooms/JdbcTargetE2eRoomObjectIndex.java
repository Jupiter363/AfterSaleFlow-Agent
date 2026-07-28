package com.example.dispute.workflow.targete2e.exchange.rooms;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Append-only implementation; any identity drift is rejected rather than selecting a newer row. */
public final class JdbcTargetE2eRoomObjectIndex implements TargetE2eRoomObjectIndex {
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcTargetE2eRoomObjectIndex(DataSource dataSource) {
    this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
  }

  @Override public void bindInput(Authority authority, RoomGraphCommand command, StoredObject object, Kind kind) {
    if (kind == Kind.PROPOSAL) throw new IllegalArgumentException("proposal requires proposal identity");
    requireAuthority(authority, command);
    insert(authority, command, object, kind, null);
  }

  @Override public StoredObject recordProposal(Authority authority, RoomGraphCommand command,
      ProposalIdentity proposal, String bucket, String key) {
    requireAuthority(authority, command);
    StoredObject object = new StoredObject("urn:target-e2e:proposal:" + command.roomType().name().toLowerCase()
        + ":" + proposal.sha256(), proposal.proposalId(), proposal.schemaVersion(), proposal.sha256(),
        proposal.sizeBytes(), bucket, key);
    insert(authority, command, object, Kind.PROPOSAL, proposal);
    return object;
  }

  @Override public Optional<StoredObject> findAdmitted(Authority authority, RoomGraphCommand command,
      TargetE2eRoomExchangeContract.ObjectRef ref) {
    requireAuthority(authority, command);
    return unique("""
        select object_ref, artifact_id, schema_version, content_sha256, size_bytes, storage_bucket, storage_key
          from target_e2e_room_object_index
         where activation_id=:activationId and tenant_surrogate=:tenant and case_id=:caseId and room_type=:room
           and room_epoch=:epoch and room_fencing_token=:fence and command_id=:commandId and object_ref=:objectRef
           and (:allowAssetSize=true or artifact_id=:artifactId) and schema_version=:schemaVersion and content_sha256=:sha256
           and (:allowAssetSize=true or size_bytes=:size)
        """, params(authority, command).addValue("objectRef", ref.uri()).addValue("artifactId", ref.artifactId())
        .addValue("schemaVersion", ref.schemaVersion()).addValue("sha256", ref.sha256()).addValue("size", ref.sizeBytes())
        .addValue("allowAssetSize", "target-e2e-evidence-asset.v1".equals(ref.schemaVersion())));
  }

  @Override public Optional<StoredObject> findProposal(ProposalLookup lookup) {
    MapSqlParameterSource p = new MapSqlParameterSource().addValue("activationId", lookup.activationId())
        .addValue("tenant", lookup.tenantSurrogate()).addValue("caseId", lookup.caseId()).addValue("room", lookup.roomType())
        .addValue("epoch", lookup.roomEpoch()).addValue("fence", lookup.roomFencingToken()).addValue("commandId", lookup.commandId())
        .addValue("run", lookup.logicalRunId()).addValue("attempt", lookup.attemptId()).addValue("proposalId", lookup.proposalId())
        .addValue("schemaVersion", lookup.payloadSchemaVersion()).addValue("objectRef", lookup.payloadRef()).addValue("sha256", lookup.payloadHash());
    return unique("""
        select object_ref, artifact_id, schema_version, content_sha256, size_bytes, storage_bucket, storage_key
          from target_e2e_room_object_index
         where object_kind='PROPOSAL' and activation_id=:activationId and tenant_surrogate=:tenant and case_id=:caseId
           and room_type=:room and room_epoch=:epoch and room_fencing_token=:fence and command_id=:commandId
           and logical_run_id=:run and attempt_id=:attempt and artifact_id=:proposalId and schema_version=:schemaVersion
           and object_ref=:objectRef and content_sha256=:sha256
        """, p);
  }

  private void insert(Authority authority, RoomGraphCommand c, StoredObject o, Kind kind, ProposalIdentity proposal) {
    MapSqlParameterSource p = params(authority, c).addValue("objectRef", o.objectRef()).addValue("kind", kind.name())
        .addValue("artifactId", o.artifactId()).addValue("schemaVersion", o.schemaVersion()).addValue("sha256", o.sha256())
        .addValue("size", o.sizeBytes()).addValue("bucket", o.storageBucket()).addValue("key", o.storageKey())
        .addValue("checkpointNs", proposal == null ? null : proposal.checkpointNs())
        .addValue("checkpointId", proposal == null ? null : proposal.checkpointId())
        .addValue("revision", proposal == null ? null : proposal.cognitiveRevision());
    if (proposal != null) {
      List<Map<String, Object>> identity = jdbc.queryForList("""
          select object_kind, artifact_id, schema_version, content_sha256, size_bytes, storage_bucket, storage_key,
                 checkpoint_ns, checkpoint_id, cognitive_revision
            from target_e2e_room_object_index
           where activation_id=:activationId and tenant_surrogate=:tenant and case_id=:caseId and room_type=:room
             and room_epoch=:epoch and room_fencing_token=:fence and command_id=:commandId and logical_run_id=:run
             and attempt_id=:attempt and artifact_id=:artifactId and schema_version=:schemaVersion
             and checkpoint_ns=:checkpointNs and checkpoint_id=:checkpointId and cognitive_revision=:revision
          """, p);
      if (!identity.isEmpty()) {
        if (identity.size() != 1 || !same(identity.getFirst(), p)) {
          throw new IllegalStateException("target room proposal identity replay drift");
        }
        return;
      }
    }
    List<Map<String, Object>> existing = jdbc.queryForList("select object_kind, artifact_id, schema_version, content_sha256, size_bytes, storage_bucket, storage_key, checkpoint_ns, checkpoint_id, cognitive_revision from target_e2e_room_object_index where object_ref=:objectRef", p);
    if (!existing.isEmpty()) {
      if (existing.size() != 1 || !same(existing.getFirst(), p)) throw new IllegalStateException("target room object index replay drift");
      return;
    }
    int count = jdbc.update("""
        insert into target_e2e_room_object_index (object_ref, object_kind, activation_id, tenant_surrogate, case_id, room_type,
          room_epoch, room_fencing_token, command_id, logical_run_id, attempt_id, artifact_id, schema_version, content_sha256,
          size_bytes, storage_bucket, storage_key, checkpoint_ns, checkpoint_id, cognitive_revision)
        values (:objectRef,:kind,:activationId,:tenant,:caseId,:room,:epoch,:fence,:commandId,:run,:attempt,:artifactId,
          :schemaVersion,:sha256,:size,:bucket,:key,:checkpointNs,:checkpointId,:revision)
        """, p);
    if (count != 1) throw new IllegalStateException("target room object index write failed");
  }

  private Optional<StoredObject> unique(String sql, MapSqlParameterSource p) {
    List<StoredObject> values = jdbc.query(sql, p, (rs, row) -> new StoredObject(rs.getString("object_ref"),
        rs.getString("artifact_id"), rs.getString("schema_version"), rs.getString("content_sha256"),
        rs.getLong("size_bytes"), rs.getString("storage_bucket"), rs.getString("storage_key")));
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

  private static boolean same(Map<String, Object> row, MapSqlParameterSource p) {
    return Objects.equals(row.get("object_kind"), p.getValue("kind")) && Objects.equals(row.get("artifact_id"), p.getValue("artifactId"))
        && Objects.equals(row.get("schema_version"), p.getValue("schemaVersion")) && Objects.equals(row.get("content_sha256"), p.getValue("sha256"))
        && ((Number) row.get("size_bytes")).longValue() == ((Number) p.getValue("size")).longValue()
        && Objects.equals(row.get("storage_bucket"), p.getValue("bucket")) && Objects.equals(row.get("storage_key"), p.getValue("key"))
        && Objects.equals(row.get("checkpoint_ns"), p.getValue("checkpointNs")) && Objects.equals(row.get("checkpoint_id"), p.getValue("checkpointId"))
        && Objects.equals(row.get("cognitive_revision"), p.getValue("revision"));
  }
}
