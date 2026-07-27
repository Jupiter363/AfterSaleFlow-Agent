package com.example.dispute.workflow.targete2e.ingress;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.domain.MessageType;
import java.time.Instant;
import java.util.List;

final class TestRequests {

    private TestRequests() {}

    static TargetIntakeMessageRequest message(TargetIntakeActivationGrant grant) {
        return new TargetIntakeMessageRequest(
                grant.caseId(),
                "ROOM_INTAKE",
                "MESSAGE_1",
                MessageType.PARTY_TEXT,
                "Package was not received.",
                List.of("PHOTO_1"),
                new AuthenticatedActor("user-local", ActorRole.USER),
                "intake-message-key",
                "TRACE_TARGET_INTAKE",
                Instant.parse("2026-07-27T01:00:00Z"),
                grant);
    }
}
