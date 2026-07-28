package com.example.dispute.workflow.targete2e.temporal.intake;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.ActorScope;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;

/** Canonical target-lane party scopes shared by Intake startup and command binding. */
public final class TargetIntakeActorScopes {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TargetIntakeActorScopes() {}

  public static ActorScope scope(String caseId, IntakeParty party) {
    Objects.requireNonNull(party, "party");
    if (caseId == null || caseId.isBlank()) {
      throw new IllegalArgumentException("caseId must not be blank");
    }
    return switch (party) {
      case INITIATOR ->
          new ActorScope(
              "user-local",
              ActorRole.USER,
              Audience.USER,
              List.of("case:" + caseId + ":command:INTAKE_MESSAGE"));
      case RESPONDENT ->
          new ActorScope(
              "merchant-local",
              ActorRole.MERCHANT,
              Audience.MERCHANT,
              List.of("case:" + caseId + ":command:INTAKE_MESSAGE"));
    };
  }

  public static String hash(String caseId, IntakeParty party) {
    return ContractJson.sha256Hex(MAPPER.valueToTree(scope(caseId, party)));
  }
}
