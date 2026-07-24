package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeWorkflowStart(
        String schemaVersion,
        String workflowId,
        String caseId,
        String reviewTaskId,
        String frozenReviewPacketRef,
        String frozenReviewPacketHash,
        String adjudicationDraftRef,
        String adjudicationDraftHash,
        String actionSnapshotRef,
        String actionSnapshotHash,
        String requiredOperationSetRef,
        String requiredOperationSetHash,
        long requiredOperationCount,
        long epoch,
        long revision,
        long fence,
        Instant reviewDeadlineAt,
        OutcomeWireTypes.RuntimeMode runtimeMode,
        String workflowBuild,
        String policyVersion,
        String graphVersion,
        String promptVersion,
        String modelProfile,
        boolean syntheticOnly) {

    public static final String SCHEMA_VERSION = "outcome-workflow-start.v1";

    public OutcomeWorkflowStart {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        identifier(reviewTaskId, "reviewTaskId");
        opaqueRef(frozenReviewPacketRef, "frozenReviewPacketRef");
        sha256(frozenReviewPacketHash, "frozenReviewPacketHash");
        opaqueRef(adjudicationDraftRef, "adjudicationDraftRef");
        sha256(adjudicationDraftHash, "adjudicationDraftHash");
        opaqueRef(actionSnapshotRef, "actionSnapshotRef");
        sha256(actionSnapshotHash, "actionSnapshotHash");
        opaqueRef(requiredOperationSetRef, "requiredOperationSetRef");
        sha256(requiredOperationSetHash, "requiredOperationSetHash");
        count(requiredOperationCount, "requiredOperationCount");
        coordinates(epoch, revision, fence);
        instant(reviewDeadlineAt, "reviewDeadlineAt");
        required(runtimeMode, "runtimeMode");
        versionPin(workflowBuild, "workflowBuild");
        versionPin(policyVersion, "policyVersion");
        versionPin(graphVersion, "graphVersion");
        versionPin(promptVersion, "promptVersion");
        versionPin(modelProfile, "modelProfile");
        if (runtimeMode == OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW
                && !syntheticOnly) {
            throw new IllegalArgumentException("synthetic shadow start must be synthetic only");
        }
        if (runtimeMode == OutcomeWireTypes.RuntimeMode.TEMPORAL && syntheticOnly) {
            throw new IllegalArgumentException("formal future start cannot be marked synthetic");
        }
    }
}
