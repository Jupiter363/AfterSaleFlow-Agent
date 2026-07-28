package com.example.dispute.workflow.targete2e.ingress.rooms;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Java-owned private Review invocation. It carries an advisory request, never a decision authority. */
public final class TargetE2eReviewInvocationPublisher {
  private final MinioTargetE2eRoomCommandPayloadPublisher publisher;
  private final TargetE2eRoomObjectIndex index;
  private final ObjectMapper mapper;

  public TargetE2eReviewInvocationPublisher(MinioTargetE2eRoomCommandPayloadPublisher publisher,
      TargetE2eRoomObjectIndex index, ObjectMapper mapper) {
    this.publisher = Objects.requireNonNull(publisher); this.index = Objects.requireNonNull(index);
    this.mapper = Objects.requireNonNull(mapper).copy();
  }

  public MinioTargetE2eRoomCommandPayloadPublisher.PublishedObject publish(
      RoomGraphCommand command, JdbcTargetReviewInvocationFactsLoader.Facts facts) {
    if (command.roomType().name().equals("REVIEW") == false || !facts.actionHash().matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Review invocation authority is invalid");
    }
    String artifactId = "review-invocation:" + command.commandId();
    String ref = "urn:target-e2e:object:" + artifactId;
    ObjectNode request = mapper.createObjectNode();
    request.put("review_id", facts.reviewTaskId()); request.put("case_id", command.caseId());
    request.put("review_packet_version", facts.packetVersion()); request.put("reviewer_role", "PLATFORM_REVIEWER");
    request.put("question", "Provide an advisory review of the frozen review packet.");
    request.set("available_fact_refs", facts.refs().facts()); request.set("available_rule_refs", facts.refs().rules());
    request.set("available_draft_refs", facts.refs().drafts()); request.set("available_deliberation_refs", facts.refs().deliberations());
    request.set("frozen_packet", facts.frozenPacket());
    String requestHash = ContractJson.sha256Hex(request);
    ObjectNode privateCommand = mapper.createObjectNode();
    privateCommand.put("schema_version", "outcome-graph-command.v1");
    privateCommand.put("authorization_schema_version", "review-packet-authorization.v1");
    privateCommand.put("command_id", command.commandId()); privateCommand.put("thread_id", command.threadId());
    privateCommand.put("tenant_surrogate", command.tenantSurrogate()); privateCommand.put("case_id", command.caseId());
    privateCommand.put("review_task_id", facts.reviewTaskId()); privateCommand.put("reviewer_actor_hash", facts.reviewerActorHash());
    privateCommand.put("packet_id", facts.packetId()); privateCommand.put("frozen_packet_ref", ref);
    privateCommand.put("frozen_packet_hash", facts.frozenPacketHash()); privateCommand.put("frozen_packet_version", facts.packetVersion());
    privateCommand.put("action_hash", facts.actionHash()); privateCommand.put("review_task_status", facts.taskStatus());
    privateCommand.put("review_deadline", facts.deadline().toString()); privateCommand.putObject("authorized_artifact_refs");
    privateCommand.put("room_epoch", command.roomEpoch()); privateCommand.put("process_revision", command.processRevision());
    privateCommand.put("fencing_token", facts.fencingToken()); privateCommand.set("fact_refs", facts.refs().facts());
    privateCommand.set("rule_refs", facts.refs().rules()); privateCommand.set("draft_refs", facts.refs().drafts());
    privateCommand.set("deliberation_refs", facts.refs().deliberations());
    privateCommand.put("question_hash", sha256(request.path("question").asText())); privateCommand.put("request_hash", requestHash);
    ObjectNode pins = privateCommand.putObject("version_pins");
    pins.put("graph_key", "outcome/review"); pins.put("graph_version", "outcome.review.v1");
    pins.put("checkpoint_schema_version", "outcome.review.checkpoint.v1"); pins.put("state_schema_version", "outcome.review.graph-state.v1");
    pins.put("prompt_version", "outcome.review.prompt.v1"); pins.put("model_profile_id", "outcome.review.model-profile.v1");
    pins.put("output_schema_version", "ReviewCopilotAnswer.v1"); pins.put("policy_version", "outcome.review.advisory-only.v1");
    pins.put("guardrail_version", "outcome.review.guardrails.v1"); pins.put("tool_policy_version", "outcome.review.no-tools.v1");
    ObjectNode document = mapper.createObjectNode(); document.put("schema_version", "target-e2e-review-invocation.v1");
    document.set("private_command", privateCommand); document.set("request", request);
    return publisher.publishCanonicalOpaque(artifactId, "REVIEW", document);
  }

  public void bind(Authority authority, RoomGraphCommand command,
      MinioTargetE2eRoomCommandPayloadPublisher.PublishedObject published) {
    publisher.bind(authority, command, published, TargetE2eRoomObjectIndex.Kind.COMMAND_INPUT);
  }

  private static String sha256(String value) {
    try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
  }
}
