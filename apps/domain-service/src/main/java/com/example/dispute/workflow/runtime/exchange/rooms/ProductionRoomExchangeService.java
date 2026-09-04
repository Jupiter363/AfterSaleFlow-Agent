package com.example.dispute.workflow.runtime.exchange.rooms;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Command-scoped, fail-closed immutable exchange for Evidence, Hearing and Review graphs. */
public final class ProductionRoomExchangeService {
  private static final String PROPOSAL_BUCKET = "production-runtime-intake-activation";
  private static final String PROPOSAL_PREFIX = "room-proposals";
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final io.minio.MinioClient minio;
  private final ProductionRoomObjectIndex objectIndex;

  public ProductionRoomExchangeService(DataSource dataSource, ObjectMapper mapper, io.minio.MinioClient minio,
      ProductionRoomObjectIndex objectIndex) {
    this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
    this.mapper = Objects.requireNonNull(mapper).copy(); this.minio = Objects.requireNonNull(minio);
    this.objectIndex = Objects.requireNonNull(objectIndex);
  }

  public LoadResponse load(LoadRequest request) {
    Resolved resolved = resolve(request.authority());
    ProductionRoomObjectIndex.StoredObject stored = objectIndex.findAdmitted(request.authority(), resolved.command(), request.objectRef())
        .orElseThrow(() -> reject("object reference is not admitted command material"));
    byte[] payload = get(stored, request.objectRef().sizeBytes());
    if (!hash(payload).equals(request.objectRef().sha256())) throw reject("object payload hash differs from immutable reference");
    if (!"production-runtime-evidence-asset.v1".equals(request.objectRef().schemaVersion()) && payload.length != request.objectRef().sizeBytes()) throw reject("object payload size differs from immutable reference");
    if (payload.length > request.objectRef().sizeBytes()) throw reject("object payload exceeds immutable reference");
    requireCanonicalJson(payload);
    return new LoadResponse("production-runtime-room-object-load-response.v1", request.authority(),
        new LoadReceipt(request.objectRef().artifactId(), request.objectRef().schemaVersion(), request.objectRef().uri(), request.objectRef().sha256(), payload.length),
        Base64.getEncoder().encodeToString(payload));
  }

  public PutResponse put(PutRequest request) {
    Resolved resolved = resolve(request.authority());
    byte[] payload;
    try { payload = Base64.getDecoder().decode(request.proposal().canonicalPayloadBase64()); }
    catch (IllegalArgumentException failure) { throw reject("proposal payload is not base64"); }
    if (payload.length != request.proposal().sizeBytes() || !hash(payload).equals(request.proposal().sha256())) throw reject("proposal payload differs from receipt");
    JsonNode document = requireCanonicalJson(payload);
    requireProposalBinding(document, request, resolved.command());
    String key = proposalKey(resolved, request);
    writeIdempotent(key, payload, request.proposal());
    ProductionRoomObjectIndex.StoredObject stored = objectIndex.recordProposal(request.authority(), resolved.command(),
        new ProductionRoomObjectIndex.ProposalIdentity(request.proposal().proposalId(), request.proposal().schemaVersion(),
            request.proposal().sha256(), request.proposal().sizeBytes(), request.checkpointNs(), request.checkpointId(), request.cognitiveRevision()),
        PROPOSAL_BUCKET, key);
    String ref = stored.objectRef();
    return new PutResponse("production-runtime-room-proposal-put-response.v1", request.authority(),
        new ProposalReceipt(request.proposal().proposalId(), request.proposal().schemaVersion(), request.proposal().sha256(), request.proposal().sizeBytes(), ref));
  }

  private Resolved resolve(Authority authority) {
    String table = switch (authority.roomType()) { case "EVIDENCE" -> "production_runtime_evidence_command_material"; case "HEARING" -> "production_runtime_hearing_command_material"; case "REVIEW" -> "production_runtime_review_command_material"; default -> throw reject("room is not exchange eligible"); };
    String sql = """
      select m.material_canonical_json, m.material_sha256, m.activation_id, m.activation_manifest_hash,
             m.isolated_domain_db_binding_hash, m.tenant_surrogate, m.case_id, m.command_id,
             m.command_hash, m.command_envelope_hash, m.room_epoch, m.room_fencing_token,
             a.execution_lane, a.lifecycle_status, a.expires_at, adm.admission_id
        from %s m join production_runtime_activation a on a.activation_id=m.activation_id
        join production_runtime_case_reservation r on r.activation_id=a.activation_id and r.tenant_surrogate=m.tenant_surrogate and r.case_id=m.case_id
        join production_runtime_room_epoch_binding e on e.activation_id=m.activation_id and e.tenant_surrogate=m.tenant_surrogate and e.case_id=m.case_id and e.room_type=:roomType and e.room_epoch=m.room_epoch and e.room_fencing_token=m.room_fencing_token
        join production_runtime_command_admission adm on adm.admission_id=m.admission_id and adm.activation_id=m.activation_id and adm.tenant_surrogate=m.tenant_surrogate and adm.case_id=m.case_id and adm.command_id=m.command_id and adm.command_hash=m.command_hash and adm.command_envelope_hash=m.command_envelope_hash and adm.room_epoch=m.room_epoch and adm.room_fencing_token=m.room_fencing_token
       where m.activation_id=:activationId and m.tenant_surrogate=:tenantSurrogate and m.case_id=:caseId and m.command_id=:commandId and m.room_epoch=:roomEpoch and m.room_fencing_token=:fence
         and a.execution_lane='PRODUCTION' and a.lifecycle_status in ('ACTIVE','DRAIN_ONLY') and (a.lifecycle_status='DRAIN_ONLY' or clock_timestamp() < a.expires_at)
      """.formatted(table);
    List<Map<String,Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource()
        .addValue("activationId", authority.activationId()).addValue("tenantSurrogate", authority.tenantSurrogate())
        .addValue("caseId", authority.caseId()).addValue("commandId", authority.commandId()).addValue("roomEpoch", authority.roomEpoch()).addValue("fence", authority.roomFencingToken()).addValue("roomType", authority.roomType()));
    if (rows.size() != 1) throw reject("room exchange authority is unknown or ambiguous");
    Map<String,Object> row=rows.getFirst();
    try {
      String raw = text(row,"material_canonical_json"); JsonNode tree=mapper.readTree(raw);
      if (!raw.equals(ContractJson.canonicalString(tree)) || !text(row,"material_sha256").equals(ContractJson.sha256Hex(tree))) throw reject("stored command material is not canonical");
      ExecuteAgentRunRequest request=mapper.treeToValue(tree.path("request"), ExecuteAgentRunRequest.class);
      RoomGraphCommand command=request.command();
      requireAuthority(authority,row,command,request);
      return new Resolved(command);
    } catch (Rejected failure) { throw failure; } catch (Exception failure) { throw reject("stored command material is malformed"); }
  }

  private void requireAuthority(Authority a, Map<String,Object> row, RoomGraphCommand c, ExecuteAgentRunRequest r) {
    if (!a.activationId().equals(text(row,"activation_id")) || !a.commandHash().equals(text(row,"command_hash")) || !a.commandEnvelopeHash().equals(text(row,"command_envelope_hash")) || a.roomFencingToken()!=number(row,"room_fencing_token")
        || !a.tenantSurrogate().equals(c.tenantSurrogate()) || !a.caseId().equals(c.caseId()) || !a.roomType().equals(c.roomType().name()) || a.roomEpoch()!=c.roomEpoch() || !a.commandId().equals(c.commandId()) || !a.logicalRunId().equals(c.logicalRunId()) || !a.attemptId().equals(c.attemptId()) || !a.requestHash().equals(c.requestHash()) || !a.graphKey().equals(c.graphKey()) || !a.graphVersion().equals(c.graphVersion()) || !a.checkpointSchemaVersion().equals(c.checkpointSchemaVersion()) || a.processRevision()!=c.processRevision() || !a.stageCode().equals(c.stageCode()) || a.stageSequence()!=c.stageSequence() || !r.agentRunId().equals(a.logicalRunId())) throw reject("authority differs from admitted room command");
  }
  private byte[] get(ProductionRoomObjectIndex.StoredObject object, long maxSize) {
    try (var input=minio.getObject(GetObjectArgs.builder().bucket(object.storageBucket()).object(object.storageKey()).build())) {
      return input.readNBytes(Math.toIntExact(maxSize) + 1);
    }
    catch (Exception failure) { throw reject("immutable target object cannot be loaded"); }
  }
  private void writeIdempotent(String key, byte[] bytes, Proposal proposal) {
    try {
      try { var stat=minio.statObject(StatObjectArgs.builder().bucket(PROPOSAL_BUCKET).object(key).build()); if (stat.size()!=bytes.length) throw reject("proposal replay differs from immutable object"); byte[] existing=get(new ProductionRoomObjectIndex.StoredObject("urn:production-runtime:proposal:replay:" + proposal.sha256(), proposal.proposalId(), proposal.schemaVersion(), proposal.sha256(), proposal.sizeBytes(), PROPOSAL_BUCKET, key), proposal.sizeBytes()); if (!MessageDigest.isEqual(existing,bytes)) throw reject("proposal replay drift"); return; }
      catch (ErrorResponseException absent) { if (!"NoSuchKey".equals(absent.errorResponse().code()) && !"NoSuchObject".equals(absent.errorResponse().code())) throw absent; }
      try (var input=new ByteArrayInputStream(bytes)) { minio.putObject(PutObjectArgs.builder().bucket(PROPOSAL_BUCKET).object(key).contentType("application/json").stream(input,bytes.length,-1).build()); }
    } catch (Rejected failure) { throw failure; } catch (Exception failure) { throw reject("target proposal publication failed"); }
  }
  private static String proposalKey(Resolved r, PutRequest q) {
    // The command/checkpoint identity is stable on replay; a changed payload at that identity is rejected.
    String scope = hash((q.proposal().proposalId()+"\n"+q.checkpointNs()+"\n"+q.checkpointId()+"\n"+q.cognitiveRevision()).getBytes(StandardCharsets.UTF_8));
    return PROPOSAL_PREFIX+"/"+r.command().roomType().name().toLowerCase()+"/"+q.authority().activationId()+"/"+q.authority().commandId()+"/"+scope+".json";
  }
  private static void requireProposalBinding(JsonNode doc, PutRequest request, RoomGraphCommand command) {
    if (!doc.isObject()) throw reject("proposal must be a JSON object");
    String documentSchema = doc.path("schema_version").asText(null);
    if (!request.proposal().schemaVersion().equals(documentSchema)) {
      throw reject("proposal schema differs from its declared payload schema");
    }
    // Command identity is never accepted from an optional payload member.  The admitted outer
    // capability is mandatory, and a payload that repeats an identity must repeat it exactly.
    for (String field : List.of("command_id", "logical_run_id", "attempt_id")) {
      JsonNode repeated = doc.get(field);
      if (repeated != null && (!repeated.isTextual() || !(switch (field) {
        case "command_id" -> command.commandId().equals(repeated.textValue());
        case "logical_run_id" -> command.logicalRunId().equals(repeated.textValue());
        default -> command.attemptId().equals(repeated.textValue());
      }))) throw reject("proposal command identity conflicts with admitted authority");
    }
    if (command.eventRef() == null || command.domainSnapshotRef() == null) throw reject("admitted command has incomplete immutable input");
  }
  private JsonNode requireCanonicalJson(byte[] bytes) { try { JsonNode value=mapper.readTree(bytes); if (value==null || !MessageDigest.isEqual(bytes,ContractJson.canonicalize(value))) throw reject("payload is not canonical JSON"); return value; } catch (Rejected e) { throw e; } catch(Exception e){ throw reject("payload is not canonical JSON"); } }
  private static String hash(byte[] value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch(Exception e){throw new IllegalStateException(e);} }
  private static String text(Map<String,Object> row,String key) { Object value=row.get(key); if (!(value instanceof String text) || text.isBlank()) throw reject("authority row malformed"); return text; }
  private static long number(Map<String,Object> row,String key) { Object value=row.get(key); if (!(value instanceof Number number)) throw reject("authority row malformed"); return number.longValue(); }
  private static Rejected reject(String value) { return new Rejected(value); }
  private record Resolved(RoomGraphCommand command) {}
  public static final class Rejected extends RuntimeException { Rejected(String message){super(message);} }
}
