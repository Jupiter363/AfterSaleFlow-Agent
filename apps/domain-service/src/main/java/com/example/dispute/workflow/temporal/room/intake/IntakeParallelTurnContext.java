package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable, command-bound authority used by the Java exact-three assembler.
 *
 * <p>This is stored inside the canonical target command material before the AgentRun starts. It
 * deliberately contains the previous persisted dossier and the authenticated current message,
 * rather than reading either value from a mutable table after the three provider calls finish.
 */
public record IntakeParallelTurnContext(
    String schemaVersion,
    String sourceType,
    String sourceMessageId,
    String currentMessageText,
    String currentMessageSha256,
    long cognitiveRevision,
    JsonNode previousDossier,
    String previousDossierSha256,
    String sourceSnapshotSha256,
    String sourceEventSha256,
    String executionProvider,
    String executionModel) {

  public static final String SCHEMA_VERSION = "intake-parallel-turn-context.v1";
  public static final String SOURCE_TYPE = "ROOM_MESSAGE";

  public IntakeParallelTurnContext {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-parallel-turn-context.v1");
    }
    if (!SOURCE_TYPE.equals(sourceType)) {
      throw new IllegalArgumentException("parallel turn context accepts only ROOM_MESSAGE");
    }
    requireIdentifier(sourceMessageId, "sourceMessageId");
    if (currentMessageText == null
        || currentMessageText.isBlank()
        || currentMessageText.length() > 8_192) {
      throw new IllegalArgumentException(
          "currentMessageText must contain 1..8192 characters");
    }
    currentMessageSha256 = requireHash(currentMessageSha256, "currentMessageSha256");
    if (!currentMessageSha256.equals(messageHash(currentMessageText))) {
      throw new IllegalArgumentException(
          "currentMessageSha256 does not bind the authenticated message text");
    }
    if (cognitiveRevision < 1) {
      throw new IllegalArgumentException("cognitiveRevision must be positive");
    }
    if (previousDossier == null || !previousDossier.isObject()) {
      throw new IllegalArgumentException("previousDossier must be one JSON object");
    }
    previousDossier = previousDossier.deepCopy();
    previousDossierSha256 = requireHash(previousDossierSha256, "previousDossierSha256");
    if (!previousDossierSha256.equals(ContractJson.sha256Hex(previousDossier))) {
      throw new IllegalArgumentException(
          "previousDossierSha256 does not bind the frozen previous dossier");
    }
    sourceSnapshotSha256 = requireHash(sourceSnapshotSha256, "sourceSnapshotSha256");
    sourceEventSha256 = requireHash(sourceEventSha256, "sourceEventSha256");
    executionProvider = bounded(executionProvider, 64, "executionProvider");
    executionModel = bounded(executionModel, 128, "executionModel");
  }

  @Override
  public JsonNode previousDossier() {
    return previousDossier.deepCopy();
  }

  public void requireMatches(RoomGraphCommand command) {
    Objects.requireNonNull(command, "command");
    if (command.eventRef() == null
        || !sourceSnapshotSha256.equals(command.domainSnapshotRef().sha256())
        || !sourceEventSha256.equals(command.eventRef().sha256())
        || !executionModel.equals(command.invocationContext().modelProfileId())) {
      throw new IllegalArgumentException(
          "parallel turn context differs from the immutable graph command");
    }
  }

  public static String messageHash(String text) {
    Objects.requireNonNull(text, "text");
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static String bounded(String value, int maximum, String field) {
    if (value == null || value.isBlank() || value.length() > maximum) {
      throw new IllegalArgumentException(field + " is outside its bounded length");
    }
    return value;
  }
}
