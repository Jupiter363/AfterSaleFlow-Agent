package com.example.dispute.workflow.config;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.NOTIFICATION_AND_TOOLS;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.ROOM_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.TemporalTaskQueues;
import org.junit.jupiter.api.Test;

class TemporalTaskQueueTopologyTest {

    @Test
    void exposesFourStableAndMutuallyIsolatedTaskQueues() {
        assertThat(TemporalTaskQueues.all())
                .containsExactly(
                        CASE_CONTROL,
                        ROOM_CONTROL,
                        AGENT_EXECUTION,
                        NOTIFICATION_AND_TOOLS)
                .doesNotHaveDuplicates();
    }
}
