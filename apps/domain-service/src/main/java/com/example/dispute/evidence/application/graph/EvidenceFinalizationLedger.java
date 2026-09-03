package com.example.dispute.evidence.application.graph;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable transaction port for Evidence finalization receipts.
 *
 * <p>An implementation must lock the semantic operation key, return the existing receipt for the
 * same request hash, reject another request hash, lock and revalidate the supplied Java authority
 * snapshot, and persist a new receipt atomically with the effects it describes. Phase 5 synthetic
 * receipts describe no formal effects.
 */
public interface EvidenceFinalizationLedger {

  Optional<EvidenceFinalizationReceipt> findCommitted(Lookup lookup);

  EvidenceFinalizationReceipt commitOrReplay(CommitRequest request);

  /** Explicit lookup adapter used by B2 after an Activity response is lost. */
  default EvidenceActivityProtocol.ReceiptLookupResult lookupForWorkflow(
      EvidenceActivityProtocol.ActivityRequest request) {
    Objects.requireNonNull(request, "request");
    Optional<EvidenceFinalizationReceipt> committed =
        findCommitted(new Lookup(request.tenantSurrogate(), request.operationKey()));
    if (committed.isEmpty()) {
      return EvidenceActivityProtocol.ReceiptLookupResult.notCommitted();
    }
    EvidenceFinalizationReceipt receipt = committed.orElseThrow();
    requireExactReplay(receipt, request.operationKey(), request.requestHash());
    var ref = receipt.toSyntheticReceiptRef();
    if (!ref.matches(request)) {
      throw new IdempotencyConflictException(
          "committed receipt conflicts with the workflow lookup authority");
    }
    return EvidenceActivityProtocol.ReceiptLookupResult.committed(ref);
  }

  static void requireExactReplay(
      EvidenceFinalizationReceipt receipt, String operationKey, String requestHash) {
    Objects.requireNonNull(receipt, "receipt");
    if (!receipt.operationKey().equals(operationKey)
        || !receipt.requestHash().equals(requestHash)) {
      throw new IdempotencyConflictException(
          "operation key already committed with a different canonical request");
    }
  }

  /** Semantic lookup key. Request hash must never be used to hide an existing conflict. */
  record Lookup(String tenantSurrogate, String operationKey) {
    public Lookup {
      if (tenantSurrogate == null || tenantSurrogate.isBlank()) {
        throw new IllegalArgumentException("tenantSurrogate must not be blank");
      }
      if (operationKey == null || operationKey.isBlank() || operationKey.length() > 512) {
        throw new IllegalArgumentException("operationKey is invalid");
      }
    }
  }

  record CommitRequest(
      EvidenceFinalizationReceipt candidate, AuthorityRequirement authorityRequirement) {
    public CommitRequest {
      Objects.requireNonNull(candidate, "candidate");
      Objects.requireNonNull(authorityRequirement, "authorityRequirement");
      if (!EvidenceFinalizationReceipt.ISOLATED_SYNTHETIC_LEDGER.equals(candidate.commitScope())
          || candidate.formalDomainWrite()
          || candidate.formalSinkEligible()) {
        throw new IllegalArgumentException("Phase 5 ledger accepts only synthetic receipts");
      }
      if (!candidate.tenantSurrogate().equals(authorityRequirement.tenantSurrogate())
          || !candidate.caseId().equals(authorityRequirement.caseId())
          || candidate.roomEpoch() != authorityRequirement.roomEpoch()
          || candidate.fencingToken() != authorityRequirement.javaRoomFencingToken()
          || candidate.sourceRevision() != authorityRequirement.sourceRevision()
          || candidate.processRevision() != authorityRequirement.processRevision()
          || candidate.roomRevision() != authorityRequirement.roomRevision()) {
        throw new IllegalArgumentException("candidate does not match its authority requirement");
      }
    }
  }

  /**
   * Values a durable adapter must lock and compare in the same transaction as receipt insert. The
   * fact/source lists are sorted canonical Java-ledger truth, not caller allowlists.
   */
  record AuthorityRequirement(
      String authoritySnapshotHash,
      String runtimeMode,
      String agentProfileId,
      String tenantSurrogate,
      String caseId,
      String roomId,
      long roomEpoch,
      long javaRoomFencingToken,
      String actorId,
      String actorRole,
      String participantId,
      String actorScopeHash,
      String agentSessionId,
      long sourceRevision,
      long processRevision,
      long roomRevision,
      List<String> currentFactIds,
      List<String> currentSourceRefs,
      List<ActualLoadRequirement> actualLoadRequirements) {
    public AuthorityRequirement {
      if (authoritySnapshotHash == null || !authoritySnapshotHash.matches("^[0-9a-f]{64}$")) {
        throw new IllegalArgumentException("authoritySnapshotHash must be lowercase SHA-256");
      }
      Objects.requireNonNull(runtimeMode, "runtimeMode");
      Objects.requireNonNull(agentProfileId, "agentProfileId");
      Objects.requireNonNull(tenantSurrogate, "tenantSurrogate");
      Objects.requireNonNull(caseId, "caseId");
      Objects.requireNonNull(roomId, "roomId");
      Objects.requireNonNull(actorId, "actorId");
      Objects.requireNonNull(actorRole, "actorRole");
      Objects.requireNonNull(participantId, "participantId");
      Objects.requireNonNull(actorScopeHash, "actorScopeHash");
      Objects.requireNonNull(agentSessionId, "agentSessionId");
      currentFactIds = List.copyOf(currentFactIds);
      currentSourceRefs = List.copyOf(currentSourceRefs);
      actualLoadRequirements = List.copyOf(actualLoadRequirements);
      if (!currentFactIds.equals(currentFactIds.stream().sorted().toList())
          || currentFactIds.size() != currentFactIds.stream().distinct().count()
          || !currentSourceRefs.equals(currentSourceRefs.stream().sorted().toList())
          || currentSourceRefs.size() != currentSourceRefs.stream().distinct().count()
          || !actualLoadRequirements.equals(
              actualLoadRequirements.stream()
                  .sorted(java.util.Comparator.comparing(ActualLoadRequirement::evidenceId))
                  .toList())
          || actualLoadRequirements.size()
              != actualLoadRequirements.stream()
                  .map(ActualLoadRequirement::receiptId)
                  .distinct()
                  .count()) {
        throw new IllegalArgumentException("authority references must be unique and sorted");
      }
    }
  }

  /** Immutable loader-ledger row the commit transaction must re-resolve by ref/hash. */
  record ActualLoadRequirement(
      String evidenceId,
      String itemHash,
      String receiptId,
      String receiptHash,
      String manifestHash,
      long javaRoomFencingToken) {
    public ActualLoadRequirement {
      Objects.requireNonNull(evidenceId, "evidenceId");
      Objects.requireNonNull(itemHash, "itemHash");
      Objects.requireNonNull(receiptId, "receiptId");
      Objects.requireNonNull(receiptHash, "receiptHash");
      Objects.requireNonNull(manifestHash, "manifestHash");
      if (javaRoomFencingToken < 1) {
        throw new IllegalArgumentException("javaRoomFencingToken must be positive");
      }
    }
  }

  final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(String message) {
      super(message);
    }
  }
}
