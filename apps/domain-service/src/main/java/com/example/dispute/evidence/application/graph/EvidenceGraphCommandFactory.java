package com.example.dispute.evidence.application.graph;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Builds proposal-only Evidence commands from Java-verified manifest authority. */
public final class EvidenceGraphCommandFactory {

  public static final String AGENT_PROFILE_ID = "evidence-clerk.v2";
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

  private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

  static {
    MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public RoomGraphCommand create(CommandRequest request) {
    Objects.requireNonNull(request, "request");
    requireCurrentAuthority(
        request.manifest(), request.binding(), request.currentAuthority(), true);

    ObjectNode commandBinding =
        (ObjectNode) request.manifest().document().required("command_binding");
    requireEqual(
        commandBinding.required("command_id").textValue(), request.commandId(), "command id");
    requireEqual(
        commandBinding.required("logical_run_id").textValue(),
        request.logicalRunId(),
        "logical run id");
    requireEqual(
        commandBinding.required("attempt_id").textValue(), request.attemptId(), "attempt id");

    ActorRole actorRole = actorRole(request.currentAuthority().actorRole());
    Audience audience = Audience.valueOf(actorRole.name());
    RoomGraphCommand.ActorScope actorScope =
        new RoomGraphCommand.ActorScope(
            request.currentAuthority().actorId(), actorRole, audience, request.actorCapabilities());
    String actorScopeHash = ContractJson.sha256Hex(MAPPER.valueToTree(actorScope));
    requireEqual(
        actorScopeHash, request.currentAuthority().actorScopeHash(), "canonical actor scope hash");

    ObjectNode profile = request.manifest().profileVersions();
    requireEqual(
        commandBinding.required("deadline_at").textValue(),
        request.deadlineAt().toString(),
        "command deadline");
    RoomGraphCommand command = command(request, actorScope, profile, "0".repeat(64));
    ObjectNode preimage = MAPPER.valueToTree(command);
    preimage.remove("request_hash");
    String requestHash = ContractJson.sha256Hex(preimage);
    return command(request, actorScope, profile, requestHash);
  }

  private static RoomGraphCommand command(
      CommandRequest request,
      RoomGraphCommand.ActorScope actorScope,
      ObjectNode profile,
      String requestHash) {
    EvidenceGraphBinding binding = request.binding();
    EvidenceBatchManifest manifest = request.manifest();
    return new RoomGraphCommand(
        "room-graph-command.v1",
        request.commandId(),
        request.logicalRunId(),
        request.attemptId(),
        binding.tenantSurrogate(),
        binding.caseId(),
        RoomType.EVIDENCE,
        binding.roomEpoch(),
        binding.graphKey(),
        binding.graphVersion(),
        binding.checkpointSchemaVersion(),
        binding.threadId(),
        actorScope,
        request.currentAuthority().processRevision(),
        request.stageCode(),
        request.stageSequence(),
        new RoomGraphCommand.SnapshotRef(
            binding.manifestId(),
            EvidenceBatchManifest.SCHEMA_VERSION,
            binding.manifestPayloadUri(),
            binding.manifestPayloadSha256(),
            binding.manifestPayloadSizeBytes()),
        null,
        new RoomGraphCommand.InvocationContext(
            request.agentProfileId(),
            profile.required("prompt_version").textValue(),
            profile.required("model_profile_id").textValue(),
            profile.required("terminal_output_schema_version").textValue(),
            profile.required("policy_version").textValue(),
            profile.required("guardrail_version").textValue(),
            request.actorCapabilities(),
            request.envelopeKeyId(),
            request.envelopeNonce()),
        new RoomGraphCommand.RetryBudget(
            request.providerAttemptsRemaining(),
            request.activityAttemptsRemaining(),
            request.repairsRemaining()),
        request.deadlineAt(),
        request.traceparent(),
        requestHash);
  }

  /** Revalidates every Java-owned authority field without accepting a Graph lease token. */
  public static void requireCurrentAuthority(
      EvidenceBatchManifest manifest,
      EvidenceGraphBinding binding,
      CurrentAuthority current,
      boolean graphExecutionRequested) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(current, "current");
    if (current.runtimeMode() == RuntimeMode.DISABLED && graphExecutionRequested) {
      throw rejected("EVIDENCE_GRAPH_DISABLED");
    }
    if (current.runtimeMode() != RuntimeMode.SIGNED_SYNTHETIC_SHADOW) {
      throw rejected("EVIDENCE_FORMAL_FINALIZER_UNAVAILABLE");
    }
    if (binding.formalSinkEligible()
        || !"SHADOW".equals(binding.writerMode())
        || !"SHADOW".equals(manifest.text("writer_mode"))
        || manifest.document().required("formal_sink_eligible").booleanValue()) {
      throw rejected("EVIDENCE_SYNTHETIC_AUTHORITY_INVALID");
    }

    requireEqual(binding.tenantSurrogate(), current.tenantSurrogate(), "tenant");
    requireEqual(binding.caseId(), current.caseId(), "case");
    requireEqual(binding.roomEpoch(), current.roomEpoch(), "room epoch");
    requireEqual(binding.javaRoomFencingToken(), current.javaRoomFencingToken(), "Java room fence");
    requireEqual(binding.actorScopeHash(), current.actorScopeHash(), "actor scope");
    requireEqual(binding.agentSessionId(), current.agentSessionId(), "agent session");

    requireEqual(manifest.text("tenant_surrogate"), current.tenantSurrogate(), "manifest tenant");
    requireEqual(manifest.text("case_id"), current.caseId(), "manifest case");
    requireEqual(manifest.text("room_id"), current.roomId(), "Evidence room");
    requireEqual(manifest.text("room_type"), "EVIDENCE", "room type");
    requireEqual(manifest.number("room_epoch"), current.roomEpoch(), "manifest room epoch");
    requireEqual(
        manifest.number("fencing_token"),
        current.javaRoomFencingToken(),
        "manifest Java room fence");
    requireEqual(manifest.text("actor_id"), current.actorId(), "actor");
    requireEqual(manifest.text("actor_role"), current.actorRole(), "actor role");
    requireEqual(manifest.text("participant_id"), current.participantId(), "participant");
    requireEqual(
        manifest.text("actor_scope_hash"), current.actorScopeHash(), "manifest actor scope");
    requireEqual(
        manifest.text("agent_session_id"), current.agentSessionId(), "manifest agent session");
    requireEqual(
        manifest.number("submission_revision"), current.sourceRevision(), "source revision");

    requireEqual(binding.manifestId(), manifest.manifestId(), "manifest id");
    requireEqual(binding.manifestHash(), manifest.manifestHash(), "manifest hash");
    requireEqual(binding.registrationId(), manifest.text("registration_id"), "registration id");
    requireEqual(
        binding.syntheticFixtureId(),
        manifest.text("synthetic_fixture_id"),
        "synthetic fixture id");
    requireEqual(
        binding.manifestPayloadSha256(), manifest.payloadSha256(), "manifest payload hash");
    requireEqual(
        binding.manifestPayloadSizeBytes(),
        (long) manifest.payloadSizeBytes(),
        "manifest payload size");
    requireEqual(binding.threadId(), manifest.text("thread_id"), "thread");
    requireEqual(
        binding.graphVersion(),
        manifest.profileVersions().required("graph_version").textValue(),
        "graph version");
    requireEqual(
        binding.checkpointSchemaVersion(),
        manifest.profileVersions().required("checkpoint_schema_version").textValue(),
        "checkpoint schema");
    requireEqual(
        binding.stateSchemaVersion(),
        manifest.profileVersions().required("state_schema_version").textValue(),
        "state schema");
    requireEqual(
        binding.assessmentOutputSchemaVersion(),
        manifest.profileVersions().required("assessment_output_schema_version").textValue(),
        "assessment schema");
    requireEqual(
        binding.terminalOutputSchemaVersion(),
        manifest.profileVersions().required("terminal_output_schema_version").textValue(),
        "terminal schema");
  }

  private static ActorRole actorRole(String role) {
    if (!List.of("USER", "MERCHANT").contains(role)) {
      throw rejected("EVIDENCE_ACTOR_ROLE_NOT_PARTY");
    }
    return ActorRole.valueOf(role);
  }

  private static void requireEqual(Object actual, Object expected, String field) {
    if (!Objects.equals(actual, expected)) {
      throw rejected("EVIDENCE_AUTHORITY_MISMATCH:" + field);
    }
  }

  private static IllegalArgumentException rejected(String code) {
    return new IllegalArgumentException(code);
  }

  public enum RuntimeMode {
    DISABLED,
    SIGNED_SYNTHETIC_SHADOW
  }

  /** Current Java-ledger authority; it intentionally contains no Graph lease fence. */
  public record CurrentAuthority(
      RuntimeMode runtimeMode,
      String tenantSurrogate,
      String caseId,
      String roomId,
      long roomEpoch,
      long javaRoomFencingToken,
      String actorId,
      String actorRole,
      String participantId,
      String actorScopeHash,
      String agentSessionId,
      long sourceRevision,
      long processRevision,
      long roomRevision) {
    public CurrentAuthority {
      Objects.requireNonNull(runtimeMode, "runtimeMode");
      bounded(tenantSurrogate, "tenantSurrogate");
      bounded(caseId, "caseId");
      bounded(roomId, "roomId");
      bounded(actorId, "actorId");
      bounded(actorRole, "actorRole");
      bounded(participantId, "participantId");
      if (actorScopeHash == null || !actorScopeHash.matches("^[0-9a-f]{64}$")) {
        throw new IllegalArgumentException("actorScopeHash must be lowercase SHA-256");
      }
      bounded(agentSessionId, "agentSessionId");
      if (roomEpoch < 0
          || javaRoomFencingToken < 1
          || sourceRevision < 1
          || processRevision < 0
          || roomRevision < 0
          || roomEpoch > MAX_SAFE_INTEGER
          || javaRoomFencingToken > MAX_SAFE_INTEGER
          || sourceRevision > MAX_SAFE_INTEGER
          || processRevision > MAX_SAFE_INTEGER
          || roomRevision > MAX_SAFE_INTEGER) {
        throw new IllegalArgumentException("authority epoch, fence, or revision is invalid");
      }
    }
  }

  public record CommandRequest(
      String commandId,
      String logicalRunId,
      String attemptId,
      EvidenceBatchManifest manifest,
      EvidenceGraphBinding binding,
      CurrentAuthority currentAuthority,
      List<String> actorCapabilities,
      String stageCode,
      long stageSequence,
      String agentProfileId,
      int providerAttemptsRemaining,
      int activityAttemptsRemaining,
      int repairsRemaining,
      Instant deadlineAt,
      String traceparent,
      String envelopeKeyId,
      String envelopeNonce) {
    public CommandRequest {
      bounded(commandId, "commandId");
      bounded(logicalRunId, "logicalRunId");
      bounded(attemptId, "attemptId");
      Objects.requireNonNull(manifest, "manifest");
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(currentAuthority, "currentAuthority");
      actorCapabilities = List.copyOf(actorCapabilities);
      if (actorCapabilities.size() > 32
          || actorCapabilities.size() != new HashSet<>(actorCapabilities).size()
          || !actorCapabilities.equals(actorCapabilities.stream().sorted().toList())
          || actorCapabilities.stream().anyMatch(value -> !isIdentifier(value))) {
        throw new IllegalArgumentException(
            "actorCapabilities must be unique sorted bounded identifiers");
      }
      bounded(stageCode, "stageCode");
      if (stageSequence < 0
          || stageSequence > MAX_SAFE_INTEGER
          || currentAuthority.processRevision() > MAX_SAFE_INTEGER
          || providerAttemptsRemaining < 0
          || providerAttemptsRemaining > 2
          || activityAttemptsRemaining < 0
          || activityAttemptsRemaining > 3
          || repairsRemaining < 0
          || repairsRemaining > 1) {
        throw new IllegalArgumentException("stage or retry budget is invalid");
      }
      bounded(agentProfileId, "agentProfileId");
      if (!AGENT_PROFILE_ID.equals(agentProfileId)) {
        throw new IllegalArgumentException("agentProfileId must be " + AGENT_PROFILE_ID);
      }
      Objects.requireNonNull(deadlineAt, "deadlineAt");
      if (traceparent == null
          || !traceparent.matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")) {
        throw new IllegalArgumentException("traceparent is invalid");
      }
      bounded(envelopeKeyId, "envelopeKeyId");
      bounded(envelopeNonce, "envelopeNonce");
    }
  }

  private static void bounded(String value, String field) {
    if (!isIdentifier(value)) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
  }

  private static boolean isIdentifier(String value) {
    return value != null && IDENTIFIER.matcher(value).matches();
  }
}
