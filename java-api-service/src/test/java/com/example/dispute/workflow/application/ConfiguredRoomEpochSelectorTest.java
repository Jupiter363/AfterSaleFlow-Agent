package com.example.dispute.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.epoch.ConfiguredRoomEpochSelector;
import com.example.dispute.workflow.config.OrchestrationCutoverProperties;
import com.example.dispute.workflow.config.TemporalWorkerProperties;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.junit.jupiter.api.Test;

class ConfiguredRoomEpochSelectorTest {

    @Test
    void keepsLegacyAllocationAvailableWithBothLocksClosed() {
        var selection =
                selector(new OrchestrationCutoverProperties(WriterMode.LEGACY, false, false))
                        .selectForNewEpoch(RoomType.INTAKE);

        assertThat(selection.writerMode()).isEqualTo(WriterMode.LEGACY);
        assertThat(selection.workflowType())
                .isEqualTo(ConfiguredRoomEpochSelector.LEGACY_WORKFLOW_TYPE);
    }

    @Test
    void rejectsShadowWhenNonLegacyAllocationIsNotExplicitlyEnabled() {
        var selector =
                selector(new OrchestrationCutoverProperties(WriterMode.SHADOW, false, false));

        assertThatThrownBy(() -> selector.selectForNewEpoch(RoomType.INTAKE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("non-LEGACY room epoch allocation is disabled");
    }

    @Test
    void permitsExplicitShadowWithoutOpeningTheTemporalWriter() {
        var properties = new OrchestrationCutoverProperties(WriterMode.SHADOW, true, false);

        assertThat(selector(properties).selectForNewEpoch(RoomType.INTAKE).writerMode())
                .isEqualTo(WriterMode.SHADOW);
    }

    @Test
    void rejectsTemporalWhenOnlyTheNonLegacyLockIsOpen() {
        var selector =
                selector(new OrchestrationCutoverProperties(WriterMode.TEMPORAL, true, false));

        assertThatThrownBy(() -> selector.selectForNewEpoch(RoomType.INTAKE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TEMPORAL room writer activation is disabled");
    }

    @Test
    void requiresBothLocksForAnExplicitTemporalSelection() {
        var properties = new OrchestrationCutoverProperties(WriterMode.TEMPORAL, true, true);

        assertThat(selector(properties).selectForNewEpoch(RoomType.INTAKE).writerMode())
                .isEqualTo(WriterMode.TEMPORAL);
    }

    private static ConfiguredRoomEpochSelector selector(
            OrchestrationCutoverProperties properties) {
        TemporalWorkerProperties worker = mock(TemporalWorkerProperties.class);
        when(worker.legacyBuildId()).thenReturn("after-sale-control.local-dev");
        return new ConfiguredRoomEpochSelector(properties, worker);
    }
}
