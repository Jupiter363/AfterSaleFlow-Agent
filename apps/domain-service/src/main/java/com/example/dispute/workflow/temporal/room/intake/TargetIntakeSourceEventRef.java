package com.example.dispute.workflow.temporal.room.intake;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import java.util.Objects;

/**
 * Target-only observation of a case-timeline event that occupies the Intake event sequence.
 *
 * <p>The cursor event carries no Intake business transition. It exists only so the child can
 * observe the same contiguous case-event cursor used by the durable formal turn receipts.
 */
public record TargetIntakeSourceEventRef(
    String schemaVersion,
    String eventId,
    long eventSequence,
    String eventType,
    String tenantSurrogate,
    String caseId,
    RoomType roomType,
    long roomEpoch,
    long fencingToken,
    String payloadHash) {

  public static final String SCHEMA_VERSION = "target-intake-source-event-ref.v1";
  public static final String ROOM_MESSAGE_CREATED = "ROOM_MESSAGE_CREATED";
  public static final String INTAKE_PROJECTION_READY = "INTAKE_PROJECTION_READY";

  public TargetIntakeSourceEventRef {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be target-intake-source-event-ref.v1");
    }
    requireText(eventId, "eventId");
    requireText(eventType, "eventType");
    requireText(tenantSurrogate, "tenantSurrogate");
    requireText(caseId, "caseId");
    Objects.requireNonNull(roomType, "roomType must not be null");
    if (eventSequence < 1 || roomEpoch < 0 || fencingToken < 1) {
      throw new IllegalArgumentException("event sequence, room epoch, and fence must be valid");
    }
    if (payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("payloadHash must be lowercase SHA-256");
    }
  }

  public static TargetIntakeSourceEventRef from(
      CaseDomainEventRef event, long fencingToken) {
    Objects.requireNonNull(event, "event must not be null");
    return new TargetIntakeSourceEventRef(
        SCHEMA_VERSION,
        event.eventId(),
        event.caseEventSequence(),
        event.eventType(),
        event.tenantSurrogate(),
        event.caseId(),
        event.roomType(),
        event.roomEpoch(),
        fencingToken,
        event.payloadRef().sha256());
  }

  public static TargetIntakeSourceEventRef fromGlobalIntakeProjectionReady(
      CaseDomainEventRef event, long roomEpoch, long fencingToken) {
    Objects.requireNonNull(event, "event must not be null");
    if (!INTAKE_PROJECTION_READY.equals(event.eventType())
        || event.roomType() != null
        || event.roomEpoch() != 0) {
      throw new IllegalArgumentException(
          "global Intake projection cursor requires the canonical unscoped event");
    }
    return new TargetIntakeSourceEventRef(
        SCHEMA_VERSION,
        event.eventId(),
        event.caseEventSequence(),
        event.eventType(),
        event.tenantSurrogate(),
        event.caseId(),
        RoomType.INTAKE,
        roomEpoch,
        fencingToken,
        event.payloadRef().sha256());
  }

  /** Formal Intake events must arrive through their exact committed operation receipt. */
  public static boolean isCursorOnlyEventType(String eventType) {
    requireText(eventType, "eventType");
    return switch (eventType) {
      case "TURN_NEEDS_INPUT",
          "INTAKE_TURN_NEEDS_INPUT",
          "TURN_READY_TO_CONFIRM",
          "INTAKE_TURN_READY_TO_CONFIRM",
          "INITIATOR_ACCEPTED",
          "INITIATOR_INTAKE_COMPLETED",
          "NOT_ADMISSIBLE",
          "INTAKE_REJECTED",
          "CANCELLED",
          "INTAKE_CANCELLED",
          "RESPONDENT_CONFIRMED",
          "RESPONDENT_INTAKE_COMPLETED" -> false;
      default -> true;
    };
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
