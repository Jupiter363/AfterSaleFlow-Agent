package com.example.dispute.room.infrastructure.delivery;

/** Best-effort live-delivery hint. PostgreSQL remains the replay authority. */
@FunctionalInterface
public interface CaseEventWakeupPublisher {

    void publish(CaseEventWakeup wakeup);
}
