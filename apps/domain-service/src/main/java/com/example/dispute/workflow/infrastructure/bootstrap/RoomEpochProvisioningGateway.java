package com.example.dispute.workflow.infrastructure.bootstrap;

import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;

public interface RoomEpochProvisioningGateway {

    ProvisionRoomEpochReceipt provision(ProvisioningRequest request);

    record ProvisioningRequest(
            String workflowType,
            String taskQueue,
            String updateId,
            String payloadSha256,
            ProvisionRoomEpoch command) {}
}
