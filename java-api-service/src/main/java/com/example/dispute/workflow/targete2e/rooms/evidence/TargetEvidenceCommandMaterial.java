package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/** Immutable, target-lane-only execution input for one admitted Evidence command. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TargetEvidenceCommandMaterial(
    String schemaVersion,
    String executionLane,
    String activationId,
    String activationManifestHash,
    long roomFencingToken,
    long expectedProcessRevision,
    long expectedRoomRevision,
    String commandHash,
    String commandEnvelopeHash,
    String caseCommandRequestHash,
    ExecuteAgentRunRequest request,
    EvidenceAgentTurnCommand evidenceAgentTurnCommand) {

  public static final String SCHEMA_VERSION = "target-e2e-evidence-command-material.v2";
  public static final String LEGACY_SCHEMA_VERSION = "target-e2e-evidence-command-material.v1";
  public static final String TARGET_LANE = "TARGET_E2E_CANDIDATE";

  public TargetEvidenceCommandMaterial {
    require(SCHEMA_VERSION.equals(schemaVersion) || LEGACY_SCHEMA_VERSION.equals(schemaVersion), "schemaVersion");
    require(TARGET_LANE.equals(executionLane), "executionLane");
    requireText(activationId, "activationId");
    requireHash(activationManifestHash, "activationManifestHash");
    requireHash(commandHash, "commandHash");
    requireHash(commandEnvelopeHash, "commandEnvelopeHash");
    requireHash(caseCommandRequestHash, "caseCommandRequestHash");
    if (roomFencingToken < 1 || expectedProcessRevision < 0 || expectedRoomRevision < 0) {
      throw new IllegalArgumentException("target Evidence fence or revision is invalid");
    }
    request = Objects.requireNonNull(request, "request");
    RoomGraphCommand command = request.command();
    require(command.roomType().name().equals("EVIDENCE"), "request.command.roomType");
    require(request.agentRunId().equals(command.logicalRunId()), "request.agentRunId");
    if (SCHEMA_VERSION.equals(schemaVersion)) {
      requireFormalTurn(command, Objects.requireNonNull(
          evidenceAgentTurnCommand, "evidenceAgentTurnCommand"));
    } else {
      require(evidenceAgentTurnCommand == null, "legacyEvidenceAgentTurnCommand");
    }
  }

  public TargetEvidenceCommandMaterial(
      String schemaVersion,
      String executionLane,
      String activationId,
      String activationManifestHash,
      long roomFencingToken,
      long expectedProcessRevision,
      long expectedRoomRevision,
      String commandHash,
      String commandEnvelopeHash,
      String caseCommandRequestHash,
      ExecuteAgentRunRequest request) {
    this(schemaVersion, executionLane, activationId, activationManifestHash, roomFencingToken,
        expectedProcessRevision, expectedRoomRevision, commandHash, commandEnvelopeHash,
        caseCommandRequestHash, request, null);
  }

  private static void requireFormalTurn(
      RoomGraphCommand command, EvidenceAgentTurnCommand turn) {
    var context = turn.agentContext();
    var envelope = turn.contextEnvelope();
    var event = envelope.currentEvent();
    boolean supportedEvent =
        "ROOM_OPENING".equals(event.eventType())
            ? event.messageType().name().equals("AGENT_MESSAGE")
                && event.attachmentRefs().isEmpty()
                && envelope.frozenSubmission() != null
            : "PARTY_MESSAGE".equals(event.eventType())
                && event.messageType().name().equals("PARTY_EVIDENCE_REFERENCE")
                && !event.attachmentRefs().isEmpty();
    boolean exact = command.caseId().equals(context.caseId())
        && context.roomType().name().equals("EVIDENCE")
        && command.actorScope().actorId().equals(context.actorId())
        && command.actorScope().actorRole().name().equals(context.actorRole())
        && command.caseId().equals(envelope.caseSnapshot().caseId())
        && envelope.roomPolicy().roomType().name().equals("EVIDENCE")
        && command.actorScope().actorId().equals(envelope.actorSnapshot().actorId())
        && command.actorScope().actorRole().name().equals(envelope.actorSnapshot().actorRole())
        && Objects.equals(context.accessSessionId(), envelope.actorSnapshot().accessSessionId())
        && Objects.equals(context.agentSessionId(), envelope.actorSnapshot().agentSessionId())
        && supportedEvent;
    require(exact, "evidenceAgentTurnCommand");
  }

  private static void require(boolean value, String field) {
    if (!value) {
      throw new IllegalArgumentException("target Evidence material has invalid " + field);
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}
