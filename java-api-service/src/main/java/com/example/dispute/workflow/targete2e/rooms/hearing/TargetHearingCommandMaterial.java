package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import java.util.Objects;

/** Immutable, admitted work handed from the case-control queue to the Hearing graph runner. */
public record TargetHearingCommandMaterial(
    String schemaVersion,
    CommandAdmission admission,
    ExecuteAgentRunRequest request,
    String commandHash,
    String commandEnvelopeHash) {

  public static final String SCHEMA_VERSION = "target-e2e-hearing-command-material.v1";

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
    if (!exact) {
      throw new IllegalArgumentException("Hearing material does not exactly bind its admitted graph command");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}
