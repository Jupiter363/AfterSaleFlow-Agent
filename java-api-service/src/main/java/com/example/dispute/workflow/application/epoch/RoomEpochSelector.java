package com.example.dispute.workflow.application.epoch;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;

public interface RoomEpochSelector {

    RoomEpochSelection selectForNewEpoch(RoomType roomType);
}
