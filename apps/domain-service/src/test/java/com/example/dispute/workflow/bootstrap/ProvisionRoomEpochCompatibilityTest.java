package com.example.dispute.workflow.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochProvisioningMapper;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ProvisionRoomEpochCompatibilityTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void v1JsonHashAndReplayRemainCompatibleWithoutRoomWorkflowFields() throws Exception {
        ProvisionRoomEpoch command =
                RoomEpochProvisioningFixtures.command("EPOCH_V1", "CASE_V1");

        String json = mapper.writeValueAsString(command);
        ProvisionRoomEpoch replayed = mapper.readValue(json, ProvisionRoomEpoch.class);

        assertThat(json)
                .doesNotContain("roomWorkflowType")
                .doesNotContain("roomWorkflowBuildId")
                .contains("\"workflowType\":\"EvidenceRoomWorkflow\"")
                .contains("\"temporalBuildId\":\"build-1\"");
        assertThat(replayed).isEqualTo(command);
        assertThat(command.payloadSha256())
                .isEqualTo("fa211c04d6ed240851d6b49eae21ae923a1ceda657a5c366a603598f8c254ffd");
        assertThat(replayed.payloadSha256()).isEqualTo(command.payloadSha256());
        assertThat(RoomEpochProvisioningFixtures.receipt(command).matches(replayed)).isTrue();
    }

    @Test
    void v2JsonHashAndReceiptBindTheExactRoomWorkflowSelection() throws Exception {
        ProvisionRoomEpoch command =
                RoomEpochProvisioningFixtures.v2Command(
                        "EPOCH_V2",
                        "CASE_V2",
                        RoomType.INTAKE,
                        "IntakeRoomWorkflow",
                        "intake-room.synthetic.v1");
        ProvisionRoomEpoch otherBuild =
                RoomEpochProvisioningFixtures.v2Command(
                        "EPOCH_V2",
                        "CASE_V2",
                        RoomType.INTAKE,
                        "IntakeRoomWorkflow",
                        "intake-room.synthetic.v2");

        String json = mapper.writeValueAsString(command);

        assertThat(json)
                .contains("\"roomWorkflowType\":\"IntakeRoomWorkflow\"")
                .contains("\"roomWorkflowBuildId\":\"intake-room.synthetic.v1\"");
        assertThat(mapper.readValue(json, ProvisionRoomEpoch.class)).isEqualTo(command);
        assertThat(command.payloadSha256()).isNotEqualTo(otherBuild.payloadSha256());
        assertThat(RoomEpochProvisioningFixtures.receipt(command).matches(command)).isTrue();
        assertThat(RoomEpochProvisioningFixtures.receipt(command).matches(otherBuild)).isFalse();
    }

    @Test
    void mapperCarriesPersistedV2CaseAndRoomWorkflowPinsIntoBootstrap() {
        OffsetDateTime now =
                OffsetDateTime.of(2026, 7, 21, 8, 0, 0, 0, ZoneOffset.UTC);
        String tenant = "tenant";
        String caseId = "CASE_MAPPED_V2";
        String caseWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(tenant, caseId);
        CaseRoomEpochEntity epoch =
                CaseRoomEpochEntity.active(
                        "EPOCH_MAPPED_V2",
                        tenant,
                        caseId,
                        "ROOM_1",
                        RoomType.INTAKE,
                        1,
                        WriterMode.SHADOW,
                        10,
                        3,
                        7,
                        caseWorkflowId,
                        null,
                        "after-sale-control.local-dev",
                        "intake.v2",
                        "2.0.0",
                        "intake-checkpoint.v2",
                        "agent-stream.v2",
                        "room-epoch-selection.v2",
                        "case-process-contract.v1",
                        "CaseProcessWorkflow",
                        "IntakeRoomWorkflow",
                        "intake-room.synthetic.v1",
                        now);
        CaseProcessProjectionEntity projection = mock(CaseProcessProjectionEntity.class);
        when(projection.getTenantSurrogate()).thenReturn(tenant);
        when(projection.getCaseId()).thenReturn(caseId);
        when(projection.getRoomEpoch()).thenReturn(1L);
        when(projection.getFencingToken()).thenReturn(7L);
        when(projection.getWriterMode()).thenReturn(WriterMode.SHADOW);
        when(projection.getProcessRevision()).thenReturn(10L);
        when(projection.getTemporalWorkflowId()).thenReturn(caseWorkflowId);
        when(projection.getTemporalBuildId()).thenReturn("after-sale-control.local-dev");
        when(projection.getMacroPhase()).thenReturn("INTAKE");
        when(projection.getCurrentRoom()).thenReturn("INTAKE");
        when(projection.getRoomPhase()).thenReturn("OPEN");
        when(projection.getLastCommandSequence()).thenReturn(4L);
        when(projection.getLastCaseEventSequence()).thenReturn(6L);

        ProvisionRoomEpoch command =
                new RoomEpochProvisioningMapper(mapper)
                        .fromLockedState(epoch, projection, now);

        assertThat(command.caseWorkflowType()).isEqualTo("CaseProcessWorkflow");
        assertThat(command.caseWorkflowBuildId())
                .isEqualTo("after-sale-control.local-dev");
        assertThat(command.roomWorkflowType()).isEqualTo("IntakeRoomWorkflow");
        assertThat(command.roomWorkflowBuildId()).isEqualTo("intake-room.synthetic.v1");
    }

    @Test
    void v2RejectsNonIntakeAndWrongTypedChildSelections() {
        assertThatThrownBy(
                        () ->
                                RoomEpochProvisioningFixtures.v2Command(
                                        "EPOCH_EVIDENCE",
                                        "CASE_EVIDENCE",
                                        RoomType.EVIDENCE,
                                        "EvidenceRoomWorkflow",
                                        "evidence-room.forbidden.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "non-LEGACY v2 bootstrap requires the IntakeRoomWorkflow binding");

        assertThatThrownBy(
                        () ->
                                RoomEpochProvisioningFixtures.v2Command(
                                        "EPOCH_WRONG_CHILD",
                                        "CASE_WRONG_CHILD",
                                        RoomType.INTAKE,
                                        "RoomControlWorkflow",
                                        "room-control.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "non-LEGACY v2 bootstrap requires the IntakeRoomWorkflow binding");
    }

    @Test
    void targetV2BindsTypedWorkflowAndReceiptForEveryExistingRoomType() {
        for (RoomType roomType : RoomType.values()) {
            ProvisionRoomEpoch command =
                    RoomEpochProvisioningFixtures.targetV2Command(
                            "EPOCH_TARGET_" + roomType.name(),
                            "CASE_TARGET_" + roomType.name(),
                            roomType,
                            TargetTypedRoomProtocol.workflowType(roomType));

            var receipt = RoomEpochProvisioningFixtures.receipt(command);

            assertThat(command.writerMode()).isEqualTo(WriterMode.TEMPORAL);
            assertThat(command.selectionSchemaVersion()).isEqualTo("room-epoch-selection.v2");
            assertThat(receipt.matches(command)).isTrue();
            assertThat(receipt.roomType()).isEqualTo(roomType);
        }
    }

    @Test
    void targetV2PreparingEpochPersistsEveryExactTypedRoomPin() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-27T10:00:00Z");
        for (RoomType roomType : RoomType.values()) {
            String caseId = "CASE_TARGET_ENTITY_" + roomType.name();
            String roomWorkflowType = TargetTypedRoomProtocol.workflowType(roomType);
            CaseRoomEpochEntity epoch =
                    CaseRoomEpochEntity.preparing(
                            "EPOCH_TARGET_ENTITY_" + roomType.name(),
                            "tenant",
                            caseId,
                            "ROOM_1",
                            roomType,
                            1,
                            10,
                            0,
                            7,
                            CaseProcessWorkflowProtocol.caseWorkflowId("tenant", caseId),
                            "p9-case-build",
                            "all-rooms.target-e2e.v1",
                            TargetTypedRoomProtocol.GRAPH_VERSION,
                            "target-e2e-checkpoint.v1",
                            "agent-stream.v2",
                            "room-epoch-selection.v2",
                            "case-process-contract.v1",
                            "CaseProcessWorkflow",
                            roomWorkflowType,
                            "p9-control-build",
                            now);

            assertThat(epoch.getWriterMode()).isEqualTo(WriterMode.TEMPORAL);
            assertThat(epoch.getRoomType()).isEqualTo(roomType);
            assertThat(epoch.getRoomWorkflowType()).isEqualTo(roomWorkflowType);
            assertThat(epoch.getRoomWorkflowBuildId()).isEqualTo("p9-control-build");
        }
    }
}
