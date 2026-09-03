package com.example.dispute.workflow.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.infrastructure.persistence.entity.RoomEpochBootstrapOutboxEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.BootstrapOutboxStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RoomEpochBootstrapOutboxEntityTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(RoomEpochProvisioningFixtures.REQUESTED_AT, ZoneOffset.UTC);

    @Test
    void stableUpdateIdentitySurvivesExpiredLeaseReclaim() {
        var command = RoomEpochProvisioningFixtures.command("EPOCH_1", "CASE_1");
        var outbox =
                RoomEpochBootstrapOutboxEntity.pending(
                        "REBOOT_1",
                        command,
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                        "{\"schemaVersion\":\"provision-room-epoch.v1\"}",
                        command.payloadSha256(),
                        NOW);

        outbox.claim("lease-1", NOW, NOW.plusSeconds(10));
        outbox.claim("lease-2", NOW.plusSeconds(10), NOW.plusSeconds(20));

        assertThat(outbox.getOutboxStatus()).isEqualTo(BootstrapOutboxStatus.CLAIMED);
        assertThat(outbox.getAttemptCount()).isEqualTo(2);
        assertThat(outbox.getLeaseOwner()).isEqualTo("lease-2");
        assertThat(outbox.getUpdateId()).isEqualTo(command.updateId());
    }

    @Test
    void activeLeaseCannotBeStolen() {
        var command = RoomEpochProvisioningFixtures.command("EPOCH_1", "CASE_1");
        var outbox =
                RoomEpochBootstrapOutboxEntity.pending(
                        "REBOOT_1",
                        command,
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                        "{}",
                        command.payloadSha256(),
                        NOW);
        outbox.claim("lease-1", NOW, NOW.plusSeconds(10));

        assertThatThrownBy(
                        () ->
                                outbox.claim(
                                        "lease-2",
                                        NOW.plusSeconds(9),
                                        NOW.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
    }
}
