package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;

public record ProvisioningCommitment(
    String updateId,
    String payloadSha256,
    ProvisionRoomEpoch request,
    ProvisionRoomEpochReceipt receipt) {

  public ProvisioningCommitment {
    if (updateId == null || updateId.isBlank() || updateId.length() > 128) {
      throw new IllegalArgumentException("provisioning updateId is invalid");
    }
    if (request == null || receipt == null) {
      throw new IllegalArgumentException("provisioning commitment must be complete");
    }
    if (payloadSha256 == null
        || !payloadSha256.equals(request.payloadSha256())
        || !receipt.matches(request)) {
      throw new IllegalArgumentException("provisioning commitment does not match payload");
    }
  }
}
