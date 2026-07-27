package com.example.dispute.workflow.targete2e;

import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.RoomType;
import java.util.Objects;

/** Exact case target presented by a guarded target-architecture call site. */
public record ActivationRequest(
    ActivationScope scope, String tenantSurrogate, RoomType roomType, String caseId) {

  public ActivationRequest {
    Objects.requireNonNull(scope, "scope");
    TargetE2eActivationContract.identifier(tenantSurrogate, "tenantSurrogate");
    Objects.requireNonNull(roomType, "roomType");
    TargetE2eActivationContract.caseId(caseId);
  }
}
