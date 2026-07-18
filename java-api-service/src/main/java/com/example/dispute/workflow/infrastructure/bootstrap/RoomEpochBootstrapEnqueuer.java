package com.example.dispute.workflow.infrastructure.bootstrap;

import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import java.time.OffsetDateTime;

public interface RoomEpochBootstrapEnqueuer {

    String enqueue(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            OffsetDateTime availableAt);
}
