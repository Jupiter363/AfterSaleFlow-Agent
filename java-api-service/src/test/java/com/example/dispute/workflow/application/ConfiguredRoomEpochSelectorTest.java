package com.example.dispute.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.epoch.ConfiguredRoomEpochSelector;
import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext;
import com.example.dispute.workflow.config.IntakeEpochSelectionProperties;
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
        assertThat(selection.selectionSchemaVersion())
                .isEqualTo(ConfiguredRoomEpochSelector.SELECTION_SCHEMA_VERSION);
        assertThat(selection.workflowType())
                .isEqualTo(ConfiguredRoomEpochSelector.LEGACY_WORKFLOW_TYPE);
        assertThat(selection.roomWorkflowType()).isNull();
    }

    @Test
    void rejectsShadowWhenNonLegacyAllocationIsNotExplicitlyEnabled() {
        var selector =
                selector(
                        new OrchestrationCutoverProperties(WriterMode.SHADOW, false, false),
                        shadowProperties());

        assertThatThrownBy(
                        () ->
                                selector.selectForNewEpoch(
                                        RoomType.INTAKE,
                                        RoomEpochSelectionContext.verifiedSignedSynthetic(
                                                "tenant-1", "case-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("non-LEGACY room epoch allocation is disabled");
    }

    @Test
    void permitsOnlyExplicitSignedSyntheticShadowWithoutOpeningTheTemporalWriter() {
        var properties = new OrchestrationCutoverProperties(WriterMode.SHADOW, true, false);

        var selection = selector(properties, shadowProperties())
                .selectForNewEpoch(
                        RoomType.INTAKE,
                        RoomEpochSelectionContext.verifiedSignedSynthetic(
                                "tenant-1", "case-1"));

        assertThat(selection.writerMode()).isEqualTo(WriterMode.SHADOW);
        assertThat(selection.selectionSchemaVersion())
                .isEqualTo(ConfiguredRoomEpochSelector.INTAKE_SELECTION_SCHEMA_VERSION);
        assertThat(selection.caseWorkflowType()).isEqualTo("CaseProcessWorkflow");
        assertThat(selection.caseWorkflowBuildId()).isEqualTo("after-sale-control.local-dev");
        assertThat(selection.roomWorkflowType()).isEqualTo("IntakeRoomWorkflow");
        assertThat(selection.roomWorkflowBuildId()).isEqualTo("intake-room.synthetic.v1");
        assertThat(selection.graphKey()).isEqualTo("intake.v2");
        assertThat(selection.graphVersion()).isEqualTo("2.0.0");
        assertThat(selection.checkpointSchemaVersion()).isEqualTo("intake-checkpoint.v2");
    }

    @Test
    void forcesAuthenticatedRealCasesToLegacyEvenWhenShadowControlsAreOpen() {
        var properties = new OrchestrationCutoverProperties(WriterMode.SHADOW, true, false);

        var selection = selector(properties, shadowProperties())
                .selectForNewEpoch(
                        RoomType.INTAKE,
                        RoomEpochSelectionContext.realCase("tenant-1", "case-1"));

        assertThat(selection.writerMode()).isEqualTo(WriterMode.LEGACY);
        assertThat(selection.selectionSchemaVersion()).isEqualTo("room-epoch-selection.v1");
    }

    @Test
    void contextFreeSelectionCannotOpenShadow() {
        var properties = new OrchestrationCutoverProperties(WriterMode.SHADOW, true, false);

        var selection = selector(properties, shadowProperties())
                .selectForNewEpoch(RoomType.INTAKE);

        assertThat(selection.writerMode()).isEqualTo(WriterMode.LEGACY);
    }

    @Test
    void forcesNonIntakeRoomsBackToTheLegacyV1Selection() {
        var properties = new OrchestrationCutoverProperties(WriterMode.SHADOW, true, false);

        var selection = selector(properties).selectForNewEpoch(RoomType.EVIDENCE);

        assertThat(selection.writerMode()).isEqualTo(WriterMode.LEGACY);
        assertThat(selection.selectionSchemaVersion()).isEqualTo("room-epoch-selection.v1");
        assertThat(selection.roomWorkflowType()).isNull();
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
    void rejectsTemporalEvenWhenBothLegacyLocksAreOpen() {
        var properties = new OrchestrationCutoverProperties(WriterMode.TEMPORAL, true, true);

        assertThatThrownBy(() -> selector(properties).selectForNewEpoch(RoomType.INTAKE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TEMPORAL Intake epoch selection is forbidden under the current gate");
    }

    @Test
    void rejectsIncompleteOrMixedVersionSelectionBindings() {
        assertThatThrownBy(
                        () ->
                                new com.example.dispute.workflow.application.epoch.RoomEpochSelection(
                                        WriterMode.SHADOW,
                                        "room-epoch-selection.v2",
                                        "case-process-contract.v1",
                                        "CaseProcessWorkflow",
                                        "case-build",
                                        null,
                                        null,
                                        "intake.v2",
                                        "2.0.0",
                                        "intake-checkpoint.v2",
                                        "agent-stream.v2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("roomWorkflowType must not be blank");

        assertThatThrownBy(
                        () ->
                                new com.example.dispute.workflow.application.epoch.RoomEpochSelection(
                                        WriterMode.LEGACY,
                                        "room-epoch-selection.v1",
                                        "case-process-contract.v1",
                                        "LegacyJavaRoomState",
                                        "legacy-java.v1",
                                        "IntakeRoomWorkflow",
                                        "room-build",
                                        "intake.v2",
                                        "1.0.0",
                                        "checkpoint.v1",
                                        "agent-stream.v2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("v1 selection cannot contain a room Workflow binding");
    }

    private static ConfiguredRoomEpochSelector selector(
            OrchestrationCutoverProperties properties) {
        return selector(
                properties,
                new IntakeEpochSelectionProperties(WriterMode.LEGACY, 0, null, false));
    }

    private static ConfiguredRoomEpochSelector selector(
            OrchestrationCutoverProperties properties,
            IntakeEpochSelectionProperties intakeProperties) {
        TemporalWorkerProperties worker = mock(TemporalWorkerProperties.class);
        when(worker.legacyBuildId()).thenReturn("after-sale-control.local-dev");
        return new ConfiguredRoomEpochSelector(properties, worker, intakeProperties);
    }

    private static IntakeEpochSelectionProperties shadowProperties() {
        return new IntakeEpochSelectionProperties(
                WriterMode.SHADOW, 10_000, "intake-shadow.v1", true);
    }
}
