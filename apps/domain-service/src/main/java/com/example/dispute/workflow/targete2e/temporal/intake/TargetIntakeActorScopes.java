package com.example.dispute.workflow.targete2e.temporal.intake;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.ActorScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;

/** Canonical target-lane party scopes shared by Intake startup and command binding. */
public final class TargetIntakeActorScopes {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TargetIntakeActorScopes() {}

  public static ActorScope scope(String caseId, String actorId, ActorRole actorRole) {
    if (caseId == null || caseId.isBlank()) {
      throw new IllegalArgumentException("caseId must not be blank");
    }
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    Objects.requireNonNull(actorRole, "actorRole");
    Audience audience = switch (actorRole) {
      case USER -> Audience.USER;
      case MERCHANT -> Audience.MERCHANT;
      default -> throw new IllegalArgumentException("target Intake actor must be USER or MERCHANT");
    };
    return new ActorScope(
        actorId,
        actorRole,
        audience,
        List.of("case:" + caseId + ":command:INTAKE_MESSAGE"));
  }

  public static String hash(String caseId, String actorId, ActorRole actorRole) {
    return ContractJson.sha256Hex(MAPPER.valueToTree(scope(caseId, actorId, actorRole)));
  }
}
