package com.example.dispute.workflow.targete2e;

/**
 * Atomic bounded-case ledger shared by every call site in one isolated synthetic activation.
 *
 * <p>{@link Action#RESERVE_BEFORE_EPOCH_SELECTION} may create a slot. {@link
 * Action#REQUIRE_EXISTING} must never create one and is used by all downstream call sites.
 */
@FunctionalInterface
public interface TargetE2eActivationCaseLedger {

  ReservationResult apply(Action action, Reservation reservation);

  static TargetE2eActivationCaseLedger denyAll() {
    return (action, reservation) -> ReservationResult.NOT_RESERVED;
  }

  enum Action {
    RESERVE_BEFORE_EPOCH_SELECTION,
    REQUIRE_EXISTING
  }

  enum ReservationResult {
    RESERVED,
    ALREADY_RESERVED_IDENTICALLY,
    NOT_RESERVED,
    CAPACITY_EXHAUSTED,
    CONFLICT
  }

  record Reservation(
      String environmentId,
      long environmentGeneration,
      String activationId,
      String caseId,
      String caseIdPrefix,
      int maxCases,
      String fixtureSetId,
      String fixtureSetHash) {

    public Reservation {
      TargetE2eActivationContract.identifier(environmentId, "environmentId");
      TargetE2eActivationContract.generation(environmentGeneration);
      TargetE2eActivationContract.activationId(activationId);
      TargetE2eActivationContract.caseId(caseId);
      TargetE2eActivationContract.caseIdPrefix(caseIdPrefix);
      if (!caseId.startsWith(caseIdPrefix) || caseId.length() == caseIdPrefix.length()) {
        throw new IllegalArgumentException("caseId is outside the synthetic prefix");
      }
      if (maxCases < 1 || maxCases > 16) {
        throw new IllegalArgumentException("maxCases must be inside 1..16");
      }
      TargetE2eActivationContract.identifier(fixtureSetId, "fixtureSetId");
      TargetE2eActivationContract.sha256(fixtureSetHash, "fixtureSetHash");
    }
  }
}
