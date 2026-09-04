package com.example.dispute.workflow.runtime;

import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.RoomType;
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
    ProductionActivationContract.identifier(tenantSurrogate, "tenantSurrogate");
    Objects.requireNonNull(roomType, "roomType");
    ProductionActivationContract.caseId(caseId);
    if (syntheticCaseSlot != null && (syntheticCaseSlot < 1 || syntheticCaseSlot > 16)) {
      throw new IllegalArgumentException("synthetic case slot must be inside 1..16");
    }
    Objects.requireNonNull(purpose, "purpose");
    if ((purpose == ActivationPurpose.DRAIN_ACCEPTED_COMMAND) != (drainAcceptedCommand != null)) {
      throw new IllegalArgumentException("drain purpose requires exactly one accepted command");
    }
  }
}
