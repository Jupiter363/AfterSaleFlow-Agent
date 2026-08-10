package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.room.application.EvidenceAgentTurnResult;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Loads and validates the immutable proposal selected by one completed target Evidence run. */
public final class TargetEvidenceTurnProposalLoader {
  public static final String SCHEMA_VERSION = "target-e2e-evidence-turn-proposal.v1";
  private static final long MAX_PROPOSAL_BYTES = 65_536;
  private static final Set<String> EXACT_FIELDS = Set.of(
      "schema_version", "command_id", "logical_run_id", "attempt_id",
      "tenant_surrogate", "case_id", "room_epoch", "fencing_token", "thread_id",
      "actor_id", "actor_role", "actor_scope_hash", "input_hash",
      "evidence_turn_result", "room_utterance", "room_utterance_sha256", "usage",
      "completed_at", "proposal_hash");
  private static final Set<String> EXACT_USAGE_FIELDS =
      Set.of("input_tokens", "output_tokens", "total_tokens");

  private final JdbcTemplate jdbc;
  private final MinioClient minio;
  private final ObjectMapper objectMapper;

  public TargetEvidenceTurnProposalLoader(
      DataSource dataSource, MinioClient minio, ObjectMapper objectMapper) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.minio = Objects.requireNonNull(minio, "minio");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
  }

  public LoadedProposal load(
      CommitCommand outer,
      TargetEvidenceCommandMaterialStore.MaterialSnapshot snapshot) {
    Objects.requireNonNull(outer, "outer");
    Objects.requireNonNull(snapshot, "snapshot");
    var material = snapshot.material();
    var graph = outer.request().command();
    require(TargetEvidenceCommandMaterial.SCHEMA_VERSION.equals(material.schemaVersion()),
        "formal Evidence material is not v2");
    require(material.evidenceAgentTurnCommand() != null,
        "formal Evidence material has no turn command");
    require(material.request().equals(outer.request()),
        "formal Evidence material differs from the outer request");
    require(snapshot.admission().activationId().equals(material.activationId())
            && snapshot.admission().manifestHash().equals(material.activationManifestHash())
            && snapshot.admission().roomFencingToken() == material.roomFencingToken(),
        "formal Evidence admission differs from material");

    RoomGraphResult result = requireCompletedResult(outer);
    var operation = result.artifactOperations().getFirst();
    var pointer = operation.artifact();
    require(SCHEMA_VERSION.equals(pointer.schemaVersion()), "Evidence proposal schema is invalid");
    require(pointer.uri().startsWith("urn:target-e2e:proposal:"),
        "Evidence proposal reference is not opaque target storage authority");

    List<IndexedProposal> indexed = jdbc.query("""
        select object.object_ref, object.artifact_id, object.schema_version,
               object.content_sha256, object.size_bytes, object.storage_bucket, object.storage_key
          from target_e2e_room_object_binding binding
          join target_e2e_room_object_index object on object.object_ref = binding.object_ref
         where binding.object_kind = 'PROPOSAL' and object.object_kind = 'PROPOSAL'
           and binding.activation_id = ? and binding.tenant_surrogate = ?
           and binding.case_id = ? and binding.room_type = 'EVIDENCE'
           and binding.room_epoch = ? and binding.room_fencing_token = ?
           and binding.command_id = ? and binding.logical_run_id = ? and binding.attempt_id = ?
           and binding.artifact_id = ? and binding.schema_version = ?
           and binding.object_ref = ? and object.content_sha256 = ?
        """, (row, ignored) -> new IndexedProposal(
            row.getString("object_ref"), row.getString("artifact_id"),
            row.getString("schema_version"), row.getString("content_sha256"),
            row.getLong("size_bytes"), row.getString("storage_bucket"),
            row.getString("storage_key")),
        material.activationId(), graph.tenantSurrogate(), graph.caseId(), graph.roomEpoch(),
        material.roomFencingToken(), graph.commandId(), graph.logicalRunId(), graph.attemptId(),
        pointer.artifactId(), pointer.schemaVersion(), pointer.uri(), pointer.sha256());
    require(indexed.size() == 1, "Evidence proposal index row is absent or ambiguous");
    IndexedProposal stored = indexed.getFirst();
    require(stored.sizeBytes() > 0 && stored.sizeBytes() <= MAX_PROPOSAL_BYTES,
        "Evidence proposal size is invalid");
    require(stored.objectRef().equals(pointer.uri())
            && stored.artifactId().equals(pointer.artifactId())
            && stored.schemaVersion().equals(pointer.schemaVersion())
            && stored.sha256().equals(pointer.sha256()),
        "Evidence proposal pointer and index differ");

    byte[] payload = readExact(stored);
    require(payload.length == stored.sizeBytes(), "Evidence proposal size differs from index");
    require(sha256(payload).equals(stored.sha256()), "Evidence proposal bytes differ from index");
    return parse(payload, pointer.uri(), pointer.sha256(), graph, material, result);
  }

  private RoomGraphResult requireCompletedResult(CommitCommand outer) {
    require(outer.result().outcome()
            == com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult.Outcome.COMPLETED,
        "Evidence outer result is not completed");
    RoomGraphResult result = outer.result().graphResult();
    require(result != null, "Evidence graph result is absent");
    var graph = outer.request().command();
    require(result.status() == GraphStatus.COMPLETED && result.needsInput() == null
            && result.needsReview() == null && result.error() == null,
        "Evidence graph result is not formalizable");
    require(result.publicEventProposals().isEmpty(),
        "Evidence graph result cannot publish public events");
    require(result.artifactOperations().size() == 1
            && result.artifactOperations().getFirst().operation()
                == ArtifactOperationType.PROPOSE_PATCH,
        "Evidence graph result must contain one proposal pointer");
    require(result.commandId().equals(graph.commandId())
            && result.logicalRunId().equals(graph.logicalRunId())
            && result.attemptId().equals(graph.attemptId())
            && result.graphKey().equals(graph.graphKey())
            && result.graphVersion().equals(graph.graphVersion())
            && result.outputHash().equals(outer.result().resultHash()),
        "Evidence graph result differs from outer authority");
    return result;
  }

  private LoadedProposal parse(
      byte[] payload,
      String payloadRef,
      String payloadHash,
      com.example.dispute.workflow.contract.v1.RoomGraphCommand graph,
      TargetEvidenceCommandMaterial material,
      RoomGraphResult graphResult) {
    try {
      JsonNode parsed = objectMapper.readTree(payload);
      require(parsed != null && parsed.isObject(), "Evidence proposal is not an object");
      require(MessageDigest.isEqual(payload, ContractJson.canonicalize(parsed)),
          "Evidence proposal bytes are not canonical JSON");
      ObjectNode document = (ObjectNode) parsed;
      require(fieldNames(document).equals(EXACT_FIELDS),
          "Evidence proposal fields are not exact");
      require(SCHEMA_VERSION.equals(text(document, "schema_version")),
          "Evidence proposal schema differs");
      require(text(document, "command_id").equals(graph.commandId())
              && text(document, "logical_run_id").equals(graph.logicalRunId())
              && text(document, "attempt_id").equals(graph.attemptId())
              && text(document, "tenant_surrogate").equals(graph.tenantSurrogate())
              && text(document, "case_id").equals(graph.caseId())
              && integer(document, "room_epoch") == graph.roomEpoch()
              && integer(document, "fencing_token") == material.roomFencingToken()
              && text(document, "thread_id").equals(graph.threadId())
              && text(document, "actor_id").equals(graph.actorScope().actorId())
              && text(document, "actor_role").equals(graph.actorScope().actorRole().name())
              && text(document, "actor_scope_hash").equals(
                  ContractJson.sha256Hex(objectMapper.valueToTree(graph.actorScope())))
              && text(document, "input_hash").equals(graph.domainSnapshotRef().sha256()),
          "Evidence proposal authority differs from command");

      JsonNode resultNode = document.get("evidence_turn_result");
      require(resultNode != null && resultNode.isObject(),
          "Evidence proposal result is not an object");
      EvidenceAgentTurnResult result =
          objectMapper.treeToValue(resultNode, EvidenceAgentTurnResult.class);
      require(objectMapper.valueToTree(result).equals(resultNode),
          "Evidence proposal result is not an exact EvidenceAgentTurnResult");
      String roomUtterance = text(document, "room_utterance");
      require(roomUtterance.equals(result.roomUtterance()),
          "Evidence proposal room utterance differs from result");
      String roomUtteranceHash = text(document, "room_utterance_sha256");
      require(roomUtteranceHash.matches("[0-9a-f]{64}")
              && roomUtteranceHash.equals(sha256(
                  roomUtterance.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
          "Evidence proposal room utterance hash differs");

      JsonNode usageNode = document.get("usage");
      require(usageNode != null && usageNode.isObject()
              && fieldNames((ObjectNode) usageNode).equals(EXACT_USAGE_FIELDS),
          "Evidence proposal usage fields are not exact");
      Usage usage = new Usage(
          integer(usageNode, "input_tokens"), integer(usageNode, "output_tokens"),
          integer(usageNode, "total_tokens"));
      require(usage.inputTokens() >= 0 && usage.outputTokens() >= 0
              && usage.totalTokens() == Math.addExact(usage.inputTokens(), usage.outputTokens()),
          "Evidence proposal usage is invalid");
      require(graphResult.usage().inputTokens() == usage.inputTokens()
              && graphResult.usage().outputTokens() == usage.outputTokens()
              && graphResult.usage().totalTokens() == usage.totalTokens(),
          "Evidence proposal usage differs from graph result");

      String completedAtText = text(document, "completed_at");
      Instant completedAt = Instant.parse(completedAtText);
      require(completedAt.toString().equals(completedAtText),
          "Evidence proposal completion time is not canonical UTC");
      String proposalHash = text(document, "proposal_hash");
      require(proposalHash.matches("[0-9a-f]{64}"), "Evidence proposal self hash is invalid");
      ObjectNode selfless = document.deepCopy();
      selfless.remove("proposal_hash");
      require(proposalHash.equals(ContractJson.sha256Hex(selfless)),
          "Evidence proposal self hash differs");
      return new LoadedProposal(
          payloadRef, payloadHash, proposalHash, graph.commandId(), graph.logicalRunId(),
          graph.attemptId(), graph.tenantSurrogate(), graph.caseId(), graph.roomEpoch(),
          material.roomFencingToken(), graph.threadId(), graph.actorScope().actorId(),
          graph.actorScope().actorRole().name(), text(document, "actor_scope_hash"),
          text(document, "input_hash"), resultNode, result, roomUtterance,
          roomUtteranceHash, usage, completedAt);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("Evidence proposal is malformed", failure);
    }
  }

  private byte[] readExact(IndexedProposal stored) {
    try (var input = minio.getObject(GetObjectArgs.builder()
        .bucket(stored.storageBucket()).object(stored.storageKey()).build())) {
      return input.readNBytes(Math.toIntExact(stored.sizeBytes()) + 1);
    } catch (Exception failure) {
      throw new IllegalStateException("Evidence proposal cannot be read", failure);
    }
  }

  private static Set<String> fieldNames(ObjectNode node) {
    Set<String> names = new HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return Set.copyOf(names);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    require(value != null && value.isTextual() && !value.textValue().isBlank(),
        "Evidence proposal " + field + " is invalid");
    return value.textValue();
  }

  private static long integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    require(value != null && value.isIntegralNumber() && value.canConvertToLong(),
        "Evidence proposal " + field + " is invalid");
    return value.longValue();
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  public record LoadedProposal(
      String payloadRef,
      String payloadHash,
      String proposalHash,
      String commandId,
      String logicalRunId,
      String attemptId,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      String threadId,
      String actorId,
      String actorRole,
      String actorScopeHash,
      String inputHash,
      JsonNode evidenceTurnResultJson,
      EvidenceAgentTurnResult evidenceTurnResult,
      String roomUtterance,
      String roomUtteranceSha256,
      Usage usage,
      Instant completedAt) {
    public LoadedProposal {
      require(payloadRef != null && payloadRef.startsWith("urn:target-e2e:proposal:"),
          "loaded Evidence proposal ref is invalid");
      require(payloadHash != null && payloadHash.matches("[0-9a-f]{64}")
              && proposalHash != null && proposalHash.matches("[0-9a-f]{64}"),
          "loaded Evidence proposal hashes are invalid");
      Objects.requireNonNull(evidenceTurnResultJson, "evidenceTurnResultJson");
      Objects.requireNonNull(evidenceTurnResult, "evidenceTurnResult");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(completedAt, "completedAt");
      evidenceTurnResultJson = evidenceTurnResultJson.deepCopy();
    }

    @Override
    public JsonNode evidenceTurnResultJson() {
      return evidenceTurnResultJson.deepCopy();
    }
  }

  public record Usage(long inputTokens, long outputTokens, long totalTokens) {}

  private record IndexedProposal(
      String objectRef,
      String artifactId,
      String schemaVersion,
      String sha256,
      long sizeBytes,
      String storageBucket,
      String storageKey) {}
}
