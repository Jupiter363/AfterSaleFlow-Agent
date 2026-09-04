package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmission;
import java.time.Instant;
import java.util.Objects;

/** Immutable, admitted work handed from the case-control queue to the Hearing graph runner. */
public record TargetHearingCommandMaterial(
    String schemaVersion,
    CommandAdmission admission,
    ExecuteAgentRunRequest request,
    PartyStageAuthority partyStageAuthority,
    String commandHash,
    String commandEnvelopeHash) {

  public static final String SCHEMA_VERSION = "production-runtime-hearing-command-material.v1";

  public TargetHearingCommandMaterial {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("unsupported Hearing material schema");
    }
    admission = Objects.requireNonNull(admission, "admission");
    request = Objects.requireNonNull(request, "request");
    requireHash(commandHash, "commandHash");
    requireHash(commandEnvelopeHash, "commandEnvelopeHash");
    RoomGraphCommand command = request.command();
    boolean exact = command.roomType().name().equals("HEARING")
        && admission.tenantSurrogate().equals(command.tenantSurrogate())
        && admission.caseId().equals(command.caseId())
        && admission.commandId().equals(command.commandId())
        && admission.roomEpoch() == command.roomEpoch()
        && admission.commandHash().equals(commandHash)
        && admission.commandEnvelopeHash().equals(commandEnvelopeHash);
    if (partyStageAuthority != null) {
      exact = exact
          && partyStageAuthority.tenantSurrogate().equals(command.tenantSurrogate())
          && partyStageAuthority.caseId().equals(command.caseId())
          && partyStageAuthority.roomEpoch() == command.roomEpoch()
          && partyStageAuthority.fencingToken() == admission.roomFencingToken();
    }
    if (!exact) {
      throw new IllegalArgumentException("Hearing material does not exactly bind its admitted graph command");
    }
  }

  /** External Hearing commands retain the established shape without fabricating party authority. */
  public TargetHearingCommandMaterial(
      String schemaVersion,
      CommandAdmission admission,
      ExecuteAgentRunRequest request,
      String commandHash,
      String commandEnvelopeHash) {
    this(schemaVersion, admission, request, null, commandHash, commandEnvelopeHash);
  }

  public record PartyStageAuthority(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      long partyStageWindowSeconds,
      Instant hearingDeadlineAt) {
    public static final String SCHEMA_VERSION = "target-hearing-party-stage-authority.v1";

    public PartyStageAuthority {
      if (!SCHEMA_VERSION.equals(schemaVersion)
          || tenantSurrogate == null || tenantSurrogate.isBlank()
          || caseId == null || caseId.isBlank()
          || roomEpoch < 0 || fencingToken < 1
          || partyStageWindowSeconds < 1 || partyStageWindowSeconds > 1_200
          || hearingDeadlineAt == null) {
        throw new IllegalArgumentException("invalid Hearing party-stage authority");
      }
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}
