package com.example.dispute.workflow.runtime.temporal.intake;

import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.application.authority.payload.IntakeBranchCommand;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.net.URI;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Reads an immutable target branch object and its exact registered private-thread binding. */
public final class JdbcTargetIntakeBranchContextSource implements TargetIntakeBranchContextSource {

  public static final String TARGET_INTAKE_BUCKET = "production-runtime-intake-activation";
  public static final String TARGET_INTAKE_PREFIX = "browser-messages";
  private static final int MAX_BYTES = 16 * 1024;
  private static final String BINDING_SQL = """
      select thread.thread_id, thread.agent_session_id,
             activation.control_build_id as workflow_build_id,
             thread.graph_version, thread.checkpoint_schema_version,
             thread.prompt_version, thread.model_profile_id,
             thread.output_schema_version, thread.policy_version,
             thread.guardrail_version, thread.tool_policy_version
        from case_intake_graph_thread_binding thread
        join production_runtime_room_epoch_binding binding
          on binding.tenant_surrogate = thread.tenant_surrogate
         and binding.case_id = thread.case_id
         and binding.room_type = thread.room_type
         and binding.room_epoch = thread.room_epoch
         and binding.room_fencing_token = thread.fencing_token
        join production_runtime_activation activation
          on activation.activation_id = binding.activation_id
         and activation.manifest_hash = binding.activation_manifest_hash
         and activation.execution_lane = binding.execution_lane
         and activation.isolated_domain_db_binding_hash =
             binding.isolated_domain_db_binding_hash
       where thread.tenant_surrogate = :tenantSurrogate
         and thread.case_id = :caseId
         and thread.room_type = 'INTAKE'
         and thread.room_epoch = :roomEpoch
         and thread.fencing_token = :fencingToken
         and thread.actor_id = :actorId
         and thread.actor_role = :actorRole
         and thread.actor_scope_hash = :actorScopeHash
         and thread.registration_status = 'REGISTERED'
         and thread.writer_mode = 'TEMPORAL'
         and thread.graph_key = 'all-rooms.production-runtime.v2'
         and thread.graph_key = activation.graph_key
         and thread.graph_version = activation.graph_version
         and thread.checkpoint_schema_version = activation.graph_checkpoint_schema_version
         and thread.state_schema_version = 'intake-graph-state.v2'
         and thread.output_schema_version = 'production-runtime-room-proposal-source.v2'
         and activation.tenant_surrogate = thread.tenant_surrogate
         and activation.graph_key = 'all-rooms.production-runtime.v2'
         and binding.activation_id = :activationId
         and binding.activation_manifest_hash = :activationManifestHash
         and binding.execution_lane = 'PRODUCTION'
         and activation.execution_lane = 'PRODUCTION'
         and activation.lifecycle_status in ('ACTIVE', 'DRAIN_ONLY')
      """;

  private final MinioClient minio;
  private final ObjectMapper mapper;
  private final NamedParameterJdbcTemplate jdbc;
  private final String bucket;
  private final String prefix;

  public JdbcTargetIntakeBranchContextSource(
      MinioClient minio, ObjectMapper mapper, DataSource dataSource, String bucket, String prefix) {
    this.minio = Objects.requireNonNull(minio, "minio");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
      throw new IllegalArgumentException("target branch command bucket is invalid");
    }
    if (prefix == null || !prefix.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")) {
      throw new IllegalArgumentException("target branch command prefix is invalid");
    }
    this.bucket = bucket;
    this.prefix = prefix;
  }

  @Override
  public ResolvedBranchContext resolve(Request request) {
    Objects.requireNonNull(request, "request");
    CaseCommandRef command = request.command();
    require(command.commandType() == CommandType.INTAKE_CONFIRM || command.commandType() == CommandType.INTAKE_CANCEL,
        "command type is not an Intake branch");
    String objectKey = exactObjectKey(command);
    byte[] bytes = load(objectKey);
    require(bytes.length > 0 && bytes.length <= MAX_BYTES, "branch command object size is invalid");
    require(command.payloadRef().sha256().equals(sha256(bytes)), "branch command object hash differs from command");
    IntakeBranchCommand branch = decodeCanonical(bytes);
    BranchOperation operation = bindBranch(command, branch, request.party());
    List<Map<String, Object>> rows =
        jdbc.queryForList(BINDING_SQL, bindingParameters(request));
    if (rows.size() != 1) {
      throw new IllegalArgumentException("target Intake branch has no exact registered private-thread binding");
    }
    Map<String, Object> row = rows.getFirst();
    PinnedVersions branchPinnedVersions =
        new PinnedVersions(
            "intake-pinned-versions.v2",
            text(row, "workflow_build_id"),
            text(row, "graph_version"),
            text(row, "checkpoint_schema_version"),
            text(row, "prompt_version"),
            text(row, "model_profile_id"),
            text(row, "output_schema_version"),
            text(row, "policy_version"),
            text(row, "guardrail_version"),
            text(row, "tool_policy_version"));
    return new ResolvedBranchContext(
        text(row, "thread_id"),
        text(row, "agent_session_id"),
        operation,
        branchPinnedVersions);
  }

  private String exactObjectKey(CaseCommandRef command) {
    String expected = prefix + "/" + IntakeBranchCommand.SCHEMA_VERSION + "/" + command.commandId()
        + "/" + command.payloadRef().sha256() + ".json";
    String expectedUri = "minio://" + bucket + "/" + expected;
    require(expectedUri.equals(command.payloadRef().uri()), "branch command reference is not the exact target object");
    try {
      URI uri = URI.create(command.payloadRef().uri());
      require("minio".equals(uri.getScheme()) && bucket.equals(uri.getHost())
              && uri.getQuery() == null && uri.getFragment() == null && uri.getUserInfo() == null
              && uri.getPort() == -1 && ("/" + expected).equals(uri.getRawPath())
              && uri.getRawPath().equals(uri.getPath()),
          "branch command reference is invalid");
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("branch command reference is invalid", failure);
    }
    return expected;
  }

  private byte[] load(String objectKey) {
    try (var input = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
      return input.readNBytes(MAX_BYTES + 1);
    } catch (Exception failure) {
      throw new IllegalStateException("target branch command object is unavailable", failure);
    }
  }

  private IntakeBranchCommand decodeCanonical(byte[] bytes) {
    try {
      JsonNode document = mapper.readTree(bytes);
      require(document != null && document.isObject() && MessageDigest.isEqual(bytes, ContractJson.canonicalize(document)),
          "branch command is not canonical JSON");
      return mapper.treeToValue(document, IntakeBranchCommand.class);
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalArgumentException("branch command is malformed", failure);
    }
  }

  private static BranchOperation bindBranch(
      CaseCommandRef command,
      IntakeBranchCommand branch,
      com.example.dispute.workflow.temporal.room.intake.IntakeParty party) {
    BranchOperation operation;
    try {
      operation = BranchOperation.valueOf(branch.operation().name());
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("branch operation is invalid", failure);
    }
    Party expectedParty = party == com.example.dispute.workflow.temporal.room.intake.IntakeParty.INITIATOR
        ? Party.INITIATOR : Party.RESPONDENT;
    require(IntakeBranchCommand.SCHEMA_VERSION.equals(branch.schemaVersion())
            && command.commandId().equals(branch.commandId())
            && command.commandType() == branch.commandType()
            && expectedParty == branch.party(),
        "branch command does not match the exact command");
    require((command.commandType() == CommandType.INTAKE_CANCEL && operation == BranchOperation.CANCEL)
            || (command.commandType() == CommandType.INTAKE_CONFIRM && operation != BranchOperation.CANCEL),
        "branch operation does not match command type");
    return operation;
  }

  private static MapSqlParameterSource bindingParameters(Request request) {
    CaseCommandRef command = request.command();
    return new MapSqlParameterSource()
        .addValue("tenantSurrogate", command.tenantSurrogate())
        .addValue("caseId", command.caseId())
        .addValue("roomEpoch", command.roomEpoch())
        .addValue("fencingToken", request.roomFencingToken())
        .addValue("actorId", command.actorRef().actorId())
        .addValue("actorRole", command.actorRef().actorRole().name())
        .addValue("actorScopeHash", request.actorScopeHash())
        .addValue("activationId", request.activationId())
        .addValue("activationManifestHash", request.activationManifestHash());
  }

  private static String text(Map<String, Object> row, String column) {
    Object value = row.get(column);
    if (!(value instanceof String text) || text.isBlank() || text.length() > 128) {
      throw new IllegalArgumentException("target Intake branch binding column is invalid: " + column);
    }
    return text;
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
