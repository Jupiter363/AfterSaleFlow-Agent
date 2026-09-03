package com.example.dispute.workflow.targete2e;

import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.BuildBindings;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.CaseScope;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.DatabaseIdentities;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.GraphBinding;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.ImageDigests;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.RoomType;
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
    TargetE2eActivationContract.activationId(activationId);
    TargetE2eActivationContract.sha256(manifestHash, "manifestHash");
    if (!TargetE2eActivationContract.LANE.equals(executionLane)) {
      throw new IllegalArgumentException("activation grant lane is invalid");
    }
    TargetE2eActivationContract.identifier(environmentId, "environmentId");
    TargetE2eActivationContract.generation(environmentGeneration);
    TargetE2eActivationContract.candidateSha(candidateSha);
    TargetE2eActivationContract.identifier(tenantSurrogate, "tenantSurrogate");
    Objects.requireNonNull(caseScope, "caseScope");
    allowedRoomTypes = Set.copyOf(Objects.requireNonNull(allowedRoomTypes, "allowedRoomTypes"));
    Objects.requireNonNull(buildBindings, "buildBindings");
    Objects.requireNonNull(graphBinding, "graphBinding");
    Objects.requireNonNull(imageDigests, "imageDigests");
    TargetE2eActivationContract.identifier(temporalNamespace, "temporalNamespace");
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
