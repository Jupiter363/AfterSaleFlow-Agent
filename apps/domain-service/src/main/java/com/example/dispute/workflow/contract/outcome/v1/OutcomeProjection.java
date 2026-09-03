package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeProjection(
        String schemaVersion,
        String workflowId,
        String caseId,
        OutcomeWireTypes.ProjectionPhase phase,
        String terminalReviewReceiptRef,
        String terminalReviewReceiptHash,
        String requiredOperationSetRef,
        String requiredOperationSetHash,
        long requiredOperationCount,
        long terminalSuccessReceiptCount,
        long unresolvedAmbiguousCount,
        long failedRequiredReceiptCount,
        long inFlightOperationCount,
        long inFlightCompensationCount,
        long unresolvedManualRecoveryCount,
        String closureReceiptRef,
        String closureReceiptHash,
        String evaluationReceiptRef,
        String evaluationReceiptHash,
        long epoch,
        long revision,
        long fence,
        OutcomeWireTypes.WriterMode writerMode,
        OutcomeWireTypes.RuntimeMode runtimeMode,
        OutcomeWireTypes.SyntheticNoopMarker syntheticNoopMarker,
        boolean projectionOnly) {

    public static final String SCHEMA_VERSION = "outcome-process-projection.v1";

    public OutcomeProjection {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        required(phase, "phase");
        paired(terminalReviewReceiptRef, terminalReviewReceiptHash,
                "terminalReviewReceiptRef", "terminalReviewReceiptHash");
        opaqueRef(requiredOperationSetRef, "requiredOperationSetRef");
        sha256(requiredOperationSetHash, "requiredOperationSetHash");
        count(requiredOperationCount, "requiredOperationCount");
        count(terminalSuccessReceiptCount, "terminalSuccessReceiptCount");
        count(unresolvedAmbiguousCount, "unresolvedAmbiguousCount");
        count(failedRequiredReceiptCount, "failedRequiredReceiptCount");
        count(inFlightOperationCount, "inFlightOperationCount");
        count(inFlightCompensationCount, "inFlightCompensationCount");
        count(unresolvedManualRecoveryCount, "unresolvedManualRecoveryCount");
        paired(closureReceiptRef, closureReceiptHash, "closureReceiptRef", "closureReceiptHash");
        paired(evaluationReceiptRef, evaluationReceiptHash,
                "evaluationReceiptRef", "evaluationReceiptHash");
        coordinates(epoch, revision, fence);
        required(writerMode, "writerMode");
        required(runtimeMode, "runtimeMode");
        boolean shadow = runtimeMode == OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW;
        if (shadow != (syntheticNoopMarker == OutcomeWireTypes.SyntheticNoopMarker.JAVA_SIGNED_SYNTHETIC_NOOP_V1)
                || shadow != projectionOnly) {
            throw new IllegalArgumentException("synthetic projection markers are inconsistent");
        }
        if (shadow && writerMode != OutcomeWireTypes.WriterMode.SHADOW) {
            throw new IllegalArgumentException("synthetic projection must use SHADOW writer mode");
        }
        boolean terminal = phase == OutcomeWireTypes.ProjectionPhase.CLOSED
                || phase == OutcomeWireTypes.ProjectionPhase.EVALUATED;
        if (shadow && (terminal || closureReceiptRef != null || evaluationReceiptRef != null)) {
            throw new IllegalArgumentException(
                    "synthetic projection cannot carry formal closure or evaluation facts");
        }
        if (terminal && closureReceiptRef == null) {
            throw new IllegalArgumentException("terminal projection requires closure receipt");
        }
        if (terminal && terminalSuccessReceiptCount != requiredOperationCount) {
            throw new IllegalArgumentException(
                    "terminal success receipt count must equal required operation count");
        }
        if (terminal
                && (unresolvedAmbiguousCount != 0
                        || failedRequiredReceiptCount != 0
                        || inFlightOperationCount != 0
                        || inFlightCompensationCount != 0
                        || unresolvedManualRecoveryCount != 0)) {
            throw new IllegalArgumentException("closed projection cannot retain blockers");
        }
        if (phase == OutcomeWireTypes.ProjectionPhase.EVALUATED && evaluationReceiptRef == null) {
            throw new IllegalArgumentException("evaluated projection requires evaluation receipt");
        }
    }
}
