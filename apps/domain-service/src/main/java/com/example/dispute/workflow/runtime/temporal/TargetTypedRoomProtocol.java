package com.example.dispute.workflow.runtime.temporal;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeRoomProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowProtocol;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeRoomWorkflowImpl;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Frozen room-type to Temporal Workflow binding for the isolated target lane. */
public final class TargetTypedRoomProtocol {

    public static final String SELECTION_SCHEMA_VERSION = "room-epoch-selection.v2";
    public static final String PROCESS_CONTRACT_VERSION = "case-process-contract.v1";
    public static final String CASE_WORKFLOW_TYPE = "CaseProcessWorkflow";
    public static final String EVIDENCE_WORKFLOW_TYPE = "EvidenceRoomWorkflow";
    public static final String HEARING_WORKFLOW_TYPE = "HearingRoomWorkflow";
    public static final String GRAPH_KEY = "all-rooms.production-runtime.v2";
    public static final String PREDECESSOR_GRAPH_VERSION = "production-runtime-graph.2026-08-18.1";
    public static final String PREVIOUS_GRAPH_VERSION = "production-runtime-graph.2026-08-18.2";
    public static final String GRAPH_VERSION = "production-runtime-graph.2026-08-18.3";
    public static final String CHECKPOINT_SCHEMA_VERSION = "production-runtime-checkpoint.v2";
    public static final String STREAM_PROTOCOL = "agent-stream.v3";

    private static final Set<String> SUPPORTED_GRAPH_VERSIONS =
            Set.of(PREDECESSOR_GRAPH_VERSION, PREVIOUS_GRAPH_VERSION, GRAPH_VERSION);

    private TargetTypedRoomProtocol() {}

    /** Accepts persisted target-lane values without allowing an unknown graph release. */
    public static boolean supportsGraphVersion(String graphVersion) {
        return SUPPORTED_GRAPH_VERSIONS.contains(graphVersion);
    }

    public static String workflowType(RoomType roomType) {
        return switch (Objects.requireNonNull(roomType, "roomType")) {
            case INTAKE -> IntakeWorkflowProtocol.WORKFLOW_TYPE;
            case EVIDENCE -> EVIDENCE_WORKFLOW_TYPE;
            case HEARING -> HEARING_WORKFLOW_TYPE;
            case REVIEW -> OutcomeRoomProtocol.WORKFLOW_TYPE;
        };
    }

    public static List<Class<?>> additionalWorkflowImplementations() {
        return List.of(
                EvidenceRoomWorkflowImpl.class,
                HearingRoomWorkflowImpl.class,
                OutcomeRoomWorkflowImpl.class);
    }
}
