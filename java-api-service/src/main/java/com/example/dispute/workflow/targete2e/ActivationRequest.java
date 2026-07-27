package com.example.dispute.workflow.targete2e;

import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.RoomType;
import java.util.Objects;

/** Exact target and purpose presented by a guarded target-architecture call site. */
public record ActivationRequest(
    ActivationScope scope,
    String tenantSurrogate,
    RoomType roomType,
    String caseId,
    Integer syntheticCaseSlot,
    ActivationPurpose purpose,
    DrainAcceptedCommand drainAcceptedCommand) {

  public ActivationRequest(
      ActivationScope scope, String tenantSurrogate, RoomType roomType, String caseId) {
    this(scope, tenantSurrogate, roomType, caseId, null, ActivationPurpose.NEW_ADMISSION, null);
  }

  public ActivationRequest {
    Objects.requireNonNull(scope, "scope");
    TargetE2eActivationContract.identifier(tenantSurrogate, "tenantSurrogate");
    Objects.requireNonNull(roomType, "roomType");
    TargetE2eActivationContract.caseId(caseId);
    if (syntheticCaseSlot != null && (syntheticCaseSlot < 1 || syntheticCaseSlot > 16)) {
      throw new IllegalArgumentException("synthetic case slot must be inside 1..16");
    }
    Objects.requireNonNull(purpose, "purpose");
    if ((purpose == ActivationPurpose.DRAIN_ACCEPTED_COMMAND) != (drainAcceptedCommand != null)) {
      throw new IllegalArgumentException("drain purpose requires exactly one accepted command");
    }
  }
}
