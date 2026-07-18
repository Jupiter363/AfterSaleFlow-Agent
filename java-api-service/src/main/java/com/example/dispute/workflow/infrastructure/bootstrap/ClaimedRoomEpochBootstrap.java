package com.example.dispute.workflow.infrastructure.bootstrap;

import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import java.time.OffsetDateTime;

public record ClaimedRoomEpochBootstrap(
        String outboxId,
        String epochId,
        String workflowType,
        String taskQueue,
        String updateId,
        String payloadSha256,
        ProvisionRoomEpoch command,
        int attemptCount,
        String leaseToken,
        OffsetDateTime leaseExpiresAt) {

    public RoomEpochProvisioningGateway.ProvisioningRequest toGatewayRequest() {
        return new RoomEpochProvisioningGateway.ProvisioningRequest(
                workflowType, taskQueue, updateId, payloadSha256, command);
    }
}
