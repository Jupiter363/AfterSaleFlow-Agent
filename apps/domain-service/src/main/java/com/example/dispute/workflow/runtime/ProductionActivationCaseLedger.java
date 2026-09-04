package com.example.dispute.workflow.runtime;

/**
 * Atomic bounded-case ledger shared by every call site in one isolated synthetic activation.
 *
 * <p>{@link Action#RESERVE_BEFORE_EPOCH_SELECTION} may create a slot. {@link
 * Action#REQUIRE_EXISTING} must never create one and is used by all downstream call sites. Every
 * generated case ID also creates a global tombstone outside environment/activation partitioning;
 * tombstones survive drain and revocation forever.
 */
@FunctionalInterface
public interface ProductionActivationCaseLedger {

  ReservationResult apply(Action action, Reservation reservation);

  static ProductionActivationCaseLedger denyAll() {
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
    SLOT_CONFLICT,
    GENERATED_CASE_ID_GLOBAL_CONFLICT
  }

  record Reservation(
      String environmentId,
      long environmentGeneration,
      String activationId,
      int slotNumber,
      String caseId,
      String caseIdPrefix,
      int maxCases,
      String fixtureSetId,
      String fixtureSetHash) {

    public Reservation {
      ProductionActivationContract.identifier(environmentId, "environmentId");
      ProductionActivationContract.generation(environmentGeneration);
      ProductionActivationContract.activationId(activationId);
      if (slotNumber < 1 || slotNumber > maxCases) {
        throw new IllegalArgumentException("slotNumber must be inside the activation capacity");
      }
      ProductionActivationContract.caseId(caseId);
      ProductionActivationContract.caseIdPrefix(caseIdPrefix);
      if (!caseId.startsWith(caseIdPrefix) || caseId.length() == caseIdPrefix.length()) {
        throw new IllegalArgumentException("caseId is outside the synthetic prefix");
      }
      if (maxCases < 1 || maxCases > 16) {
        throw new IllegalArgumentException("maxCases must be inside 1..16");
      }
      ProductionActivationContract.identifier(fixtureSetId, "fixtureSetId");
      ProductionActivationContract.sha256(fixtureSetHash, "fixtureSetHash");
    }
  }
}
