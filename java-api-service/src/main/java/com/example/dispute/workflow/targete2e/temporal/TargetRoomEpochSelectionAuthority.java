package com.example.dispute.workflow.targete2e.temporal;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Admission boundary between the target-E2E activation ledger and room epoch selection. */
public interface TargetRoomEpochSelectionAuthority {

    String PROFILE = "target-e2e";
    String EXECUTION_LANE = "TARGET_E2E_CANDIDATE";

    Optional<Grant> authorize(Request request);

    static TargetRoomEpochSelectionAuthority disabled() {
        return request -> Optional.empty();
    }

    record Request(
            String profile,
            String executionLane,
            String tenantSurrogate,
            String caseId,
            RoomType roomType,
            TrafficSource trafficSource) {

        public Request {
            requireExact(profile, PROFILE, "profile");
            requireExact(executionLane, EXECUTION_LANE, "executionLane");
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            Objects.requireNonNull(roomType, "roomType must not be null");
            Objects.requireNonNull(trafficSource, "trafficSource must not be null");
        }
    }

    record Grant(
            String activationId,
            String activationManifestHash,
            String isolatedDomainDbBindingHash,
            Request request,
            String selectionSchemaVersion,
            String processContractVersion,
            String caseWorkflowType,
            String caseWorkflowBuildId,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol) {

        private static final Pattern ACTIVATION_ID =
                Pattern.compile("p9act\\.v1\\.[0-9a-f]{32}");

        public Grant {
            if (activationId == null || !ACTIVATION_ID.matcher(activationId).matches()) {
                throw new IllegalArgumentException("activationId is invalid");
            }
            requireHash(activationManifestHash, "activationManifestHash");
            requireHash(isolatedDomainDbBindingHash, "isolatedDomainDbBindingHash");
            Objects.requireNonNull(request, "request must not be null");
            requireExact(
                    selectionSchemaVersion,
                    TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION,
                    "selectionSchemaVersion");
            requireExact(
                    processContractVersion,
                    TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
                    "processContractVersion");
            requireExact(
                    caseWorkflowType,
                    TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE,
                    "caseWorkflowType");
            requireText(caseWorkflowBuildId, "caseWorkflowBuildId");
            requireText(roomWorkflowType, "roomWorkflowType");
            requireText(roomWorkflowBuildId, "roomWorkflowBuildId");
            if (!TargetTypedRoomProtocol.workflowType(request.roomType())
                    .equals(roomWorkflowType)) {
                throw new IllegalArgumentException(
                        "roomWorkflowType does not match the target room type");
            }
            requireExact(graphKey, TargetTypedRoomProtocol.GRAPH_KEY, "graphKey");
            requireExact(graphVersion, TargetTypedRoomProtocol.GRAPH_VERSION, "graphVersion");
            requireExact(
                    checkpointSchemaVersion,
                    TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                    "checkpointSchemaVersion");
            requireExact(
                    streamProtocol,
                    TargetTypedRoomProtocol.STREAM_PROTOCOL,
                    "streamProtocol");
        }

        public boolean exactlyBinds(Request expected) {
            return request.equals(expected);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireExact(String value, String expected, String field) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
        }
    }
}
