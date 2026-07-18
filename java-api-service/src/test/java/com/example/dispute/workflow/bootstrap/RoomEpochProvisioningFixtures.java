package com.example.dispute.workflow.bootstrap;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import java.time.Instant;

final class RoomEpochProvisioningFixtures {

    static final Instant REQUESTED_AT = Instant.parse("2026-07-18T08:00:00Z");

    private RoomEpochProvisioningFixtures() {}

    static ProvisionRoomEpoch command(String epochId, String caseId) {
        String tenant = "tenant";
        return new ProvisionRoomEpoch(
                ProvisionRoomEpoch.SCHEMA_VERSION,
                epochId,
                tenant,
                caseId,
                "ROOM_1",
                RoomType.EVIDENCE,
                1,
                10,
                3,
                7,
                "EVIDENCE",
                "EVIDENCE",
                "OPEN",
                WriterMode.TEMPORAL,
                CaseProcessWorkflowProtocol.caseWorkflowId(tenant, caseId),
                CaseProcessWorkflowProtocol.roomWorkflowId(caseId, RoomType.EVIDENCE, 1),
                "room-epoch-selection.v1",
                "case-process-contract.v1",
                "EvidenceRoomWorkflow",
                "build-1",
                "evidence.graph",
                "graph-v1",
                "checkpoint-v1",
                "agent-stream.v1",
                4,
                6,
                5,
                7,
                REQUESTED_AT.plusSeconds(3600),
                null,
                null,
                REQUESTED_AT);
    }

    static ProvisionRoomEpochReceipt receipt(ProvisionRoomEpoch command) {
        return new ProvisionRoomEpochReceipt(
                "provision-room-epoch-receipt.v1",
                command.epochId(),
                command.tenantSurrogate(),
                command.caseId(),
                command.roomId(),
                command.roomType(),
                command.roomEpoch(),
                command.fencingToken(),
                command.initialProcessRevision(),
                command.initialRoomRevision(),
                command.macroPhase(),
                command.currentRoom(),
                command.roomPhase(),
                command.projectedDeadlineAt(),
                command.writerMode(),
                command.selectionSchemaVersion(),
                command.processContractVersion(),
                command.workflowType(),
                command.temporalBuildId(),
                command.graphKey(),
                command.graphVersion(),
                command.checkpointSchemaVersion(),
                command.streamProtocol(),
                command.lastCommandSequence(),
                command.lastCaseEventSequence(),
                command.firstCommandSequence(),
                command.firstCaseEventSequence(),
                command.projectionRef(),
                command.projectionSha256(),
                command.requestedAt(),
                command.caseWorkflowId(),
                "case-first-run",
                command.roomWorkflowId(),
                "room-first-run",
                command.payloadSha256());
    }
}
