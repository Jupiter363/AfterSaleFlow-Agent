package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;

/**
 * 父工作流对一次房间 epoch provisioning 的请求与回执绑定。
 *
 * <p>上游 provisioning 更新提供请求和匹配回执后写入携带状态；Temporal 重放或相同 {@code updateId}
 * 再次到达时以 payload 哈希保持幂等，下游子流程调度据此使用已确认的 room epoch。
 */
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
    if (!updateId.equals(request.updateId())
        || payloadSha256 == null
        || !payloadSha256.equals(request.payloadSha256())
        || !receipt.matches(request)) {
      throw new IllegalArgumentException("provisioning commitment does not match payload");
    }
  }
}
