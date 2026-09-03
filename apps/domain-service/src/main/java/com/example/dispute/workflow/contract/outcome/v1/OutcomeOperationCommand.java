package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeOperationCommand(
        String schemaVersion,
        String workflowId,
        String caseId,
        String commandId,
        String operationId,
        String operationKeyHash,
        String approvalReceiptRef,
        String approvalReceiptHash,
        String approvedActionSnapshotRef,
        String approvedActionSnapshotHash,
        String requestRef,
        String requestHash,
        String externalIdempotencyKeyHash,
        OutcomeWireTypes.EffectClass effectClass,
        boolean requiredForClosure,
        boolean compensable,
        long operationSequence,
        long epoch,
        long sourceRevision,
        long revision,
        long fence,
        long committedEventSequence,
        long attemptNo,
        Instant deadlineAt,
        String toolCapabilityVersion,
        OutcomeWireTypes.RuntimeMode runtimeMode,
        OutcomeWireTypes.SyntheticNoopMarker syntheticNoopMarker,
        boolean syntheticOnly) {

    public static final String SCHEMA_VERSION = "outcome-operation-command.v1";

    public OutcomeOperationCommand {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        identifier(commandId, "commandId");
        identifier(operationId, "operationId");
        sha256(operationKeyHash, "operationKeyHash");
        opaqueRef(approvalReceiptRef, "approvalReceiptRef");
        sha256(approvalReceiptHash, "approvalReceiptHash");
        opaqueRef(approvedActionSnapshotRef, "approvedActionSnapshotRef");
        sha256(approvedActionSnapshotHash, "approvedActionSnapshotHash");
        opaqueRef(requestRef, "requestRef");
        sha256(requestHash, "requestHash");
        sha256(externalIdempotencyKeyHash, "externalIdempotencyKeyHash");
        required(effectClass, "effectClass");
        OutcomeWireTypes.operationSequence(operationSequence);
        coordinates(epoch, revision, fence);
        eventOrder(sourceRevision, revision, committedEventSequence);
        positive(attemptNo, "attemptNo");
        instant(deadlineAt, "deadlineAt");
        versionPin(toolCapabilityVersion, "toolCapabilityVersion");
        required(runtimeMode, "runtimeMode");
        if (runtimeMode == OutcomeWireTypes.RuntimeMode.DISABLED) {
            throw new IllegalArgumentException("disabled mode cannot issue an operation command");
        }
        if (runtimeMode == OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW) {
            if (!syntheticOnly
                    || syntheticNoopMarker != OutcomeWireTypes.SyntheticNoopMarker.JAVA_SIGNED_SYNTHETIC_NOOP_V1
                    || effectClass != OutcomeWireTypes.EffectClass.NO_EXTERNAL_EFFECT
                    || compensable) {
                throw new IllegalArgumentException("synthetic command markers are invalid");
            }
        } else if (syntheticOnly || syntheticNoopMarker != null) {
            throw new IllegalArgumentException("formal future command cannot carry synthetic markers");
        }
    }
}
