package com.example.dispute.workflow.targete2e.temporal.intake;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** Resolves immutable case-party authority for one target Intake room epoch. */
@ActivityInterface
public interface TargetIntakePartyScopeSource {

  String SCHEMA_VERSION = "target-intake-party-scopes.v1";

  @ActivityMethod(name = "ResolveTargetIntakePartyScopes")
  ResolvedPartyScopes resolve(Request request);

  record Request(
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long roomFencingToken) {
    public Request {
      requireIdentifier(tenantSurrogate, 128, "tenantSurrogate");
      requireIdentifier(caseId, 64, "caseId");
      if (roomEpoch < 0 || roomFencingToken < 1) {
        throw new IllegalArgumentException("target Intake party scope epoch or fence is invalid");
      }
    }
  }

  record PartyBinding(
      IntakeParty party, String actorId, ActorRole actorRole, String actorScopeHash) {
    public PartyBinding {
      Objects.requireNonNull(party, "party must not be null");
      requireIdentifier(actorId, 128, "actorId");
      requirePartyRole(actorRole);
      requireSha256(actorScopeHash, "actorScopeHash");
    }
  }

  record ResolvedPartyScopes(
      String schemaVersion,
      String activationId,
      String activationManifestHash,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long roomFencingToken,
      PartyBinding initiator,
      PartyBinding respondent) {
    public ResolvedPartyScopes {
      if (!SCHEMA_VERSION.equals(schemaVersion)) {
        throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
      }
      requireIdentifier(activationId, 64, "activationId");
      requireSha256(activationManifestHash, "activationManifestHash");
      new Request(tenantSurrogate, caseId, roomEpoch, roomFencingToken);
      Objects.requireNonNull(initiator, "initiator must not be null");
      Objects.requireNonNull(respondent, "respondent must not be null");
      if (initiator.party() != IntakeParty.INITIATOR
          || respondent.party() != IntakeParty.RESPONDENT
          || initiator.actorId().equals(respondent.actorId())
          || initiator.actorRole() == respondent.actorRole()) {
        throw new IllegalArgumentException("target Intake parties must be distinct USER/MERCHANT actors");
      }
      requireCanonicalScope(caseId, initiator);
      requireCanonicalScope(caseId, respondent);
    }

    public static ResolvedPartyScopes create(
        String activationId,
        String activationManifestHash,
        Request request,
        String initiatorId,
        ActorRole initiatorRole,
        String respondentId,
        ActorRole respondentRole) {
      Objects.requireNonNull(request, "request must not be null");
      return new ResolvedPartyScopes(
          SCHEMA_VERSION,
          activationId,
          activationManifestHash,
          request.tenantSurrogate(),
          request.caseId(),
          request.roomEpoch(),
          request.roomFencingToken(),
          new PartyBinding(
              IntakeParty.INITIATOR,
              initiatorId,
              initiatorRole,
              TargetIntakeActorScopes.hash(request.caseId(), initiatorId, initiatorRole)),
          new PartyBinding(
              IntakeParty.RESPONDENT,
              respondentId,
              respondentRole,
              TargetIntakeActorScopes.hash(request.caseId(), respondentId, respondentRole)));
    }

    public void requireMatches(Request request) {
      Objects.requireNonNull(request, "request must not be null");
      if (!tenantSurrogate.equals(request.tenantSurrogate())
          || !caseId.equals(request.caseId())
          || roomEpoch != request.roomEpoch()
          || roomFencingToken != request.roomFencingToken()) {
        throw new IllegalArgumentException("target Intake party scopes are outside the requested epoch");
      }
    }

    public PartyBinding actor(String actorId, ActorRole actorRole) {
      requireIdentifier(actorId, 128, "actorId");
      requirePartyRole(actorRole);
      if (initiator.actorId().equals(actorId) && initiator.actorRole() == actorRole) {
        return initiator;
      }
      if (respondent.actorId().equals(actorId) && respondent.actorRole() == actorRole) {
        return respondent;
      }
      throw new IllegalArgumentException("target Intake actor is not an assigned case party");
    }
  }

  private static void requireCanonicalScope(String caseId, PartyBinding binding) {
    String expected = TargetIntakeActorScopes.hash(caseId, binding.actorId(), binding.actorRole());
    if (!expected.equals(binding.actorScopeHash())) {
      throw new IllegalArgumentException("target Intake actor scope hash is not canonical");
    }
  }

  private static void requirePartyRole(ActorRole role) {
    if (role != ActorRole.USER && role != ActorRole.MERCHANT) {
      throw new IllegalArgumentException("target Intake actor role must be USER or MERCHANT");
    }
  }

  private static void requireIdentifier(String value, int maximumLength, String field) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }

  private static void requireSha256(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}
