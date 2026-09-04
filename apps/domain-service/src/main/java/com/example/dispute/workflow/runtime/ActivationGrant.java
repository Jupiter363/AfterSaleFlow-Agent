package com.example.dispute.workflow.runtime;

import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.BuildBindings;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.CaseScope;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.DatabaseIdentities;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.GraphBinding;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.ImageDigests;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.RoomType;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Non-sensitive signed bindings released only after an exact authorization succeeds. */
public record ActivationGrant(
    String activationId,
    String manifestHash,
    String executionLane,
    String environmentId,
    long environmentGeneration,
    String candidateSha,
    String tenantSurrogate,
    CaseScope caseScope,
    Set<RoomType> allowedRoomTypes,
    BuildBindings buildBindings,
    GraphBinding graphBinding,
    ImageDigests imageDigests,
    String temporalNamespace,
    DatabaseIdentities databaseIdentities,
    boolean javaDomainCommitAllowed,
    boolean externalEffectsAllowed,
    boolean graphDomainWriteAllowed,
    boolean productionTrafficAllowed,
    Instant issuedAt,
    Instant expiresAt) {

  public ActivationGrant {
    ProductionActivationContract.activationId(activationId);
    ProductionActivationContract.sha256(manifestHash, "manifestHash");
    if (!ProductionActivationContract.LANE.equals(executionLane)) {
      throw new IllegalArgumentException("activation grant lane is invalid");
    }
    ProductionActivationContract.identifier(environmentId, "environmentId");
    ProductionActivationContract.generation(environmentGeneration);
    ProductionActivationContract.candidateSha(candidateSha);
    ProductionActivationContract.identifier(tenantSurrogate, "tenantSurrogate");
    Objects.requireNonNull(caseScope, "caseScope");
    allowedRoomTypes = Set.copyOf(Objects.requireNonNull(allowedRoomTypes, "allowedRoomTypes"));
    Objects.requireNonNull(buildBindings, "buildBindings");
    Objects.requireNonNull(graphBinding, "graphBinding");
    Objects.requireNonNull(imageDigests, "imageDigests");
    ProductionActivationContract.identifier(temporalNamespace, "temporalNamespace");
    Objects.requireNonNull(databaseIdentities, "databaseIdentities");
    if (!javaDomainCommitAllowed
        || externalEffectsAllowed
        || graphDomainWriteAllowed
        || productionTrafficAllowed) {
      throw new IllegalArgumentException("activation grant effect policy is invalid");
    }
    Objects.requireNonNull(issuedAt, "issuedAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
}
