package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Reads the immutable V043_3 admission tuple inside the caller's authority snapshot. */
public interface IntakeSyntheticAdmissionReader {

    List<PersistedAdmission> find(Connection connection, AdmissionQuery query) throws SQLException;

    record AdmissionQuery(
            ActivityEnvelope envelope,
            String threadId,
            String agentSessionId,
            String activityOperationKey,
            String requestHash) {

        public AdmissionQuery {
            Objects.requireNonNull(envelope, "envelope must not be null");
            Objects.requireNonNull(threadId, "threadId must not be null");
            Objects.requireNonNull(agentSessionId, "agentSessionId must not be null");
            Objects.requireNonNull(activityOperationKey, "activityOperationKey must not be null");
            Objects.requireNonNull(requestHash, "requestHash must not be null");
        }
    }

    /**
     * Complete command-wide admission claims. Activity retry state is derived monotonically from
     * {@code admittedRetryBudget}; it is not rewritten on every Activity attempt.
     */
    record PersistedAdmission(
            String schemaVersion,
            String trafficSource,
            String admissionStatus,
            long issuedAtEpochSeconds,
            long notBeforeEpochSeconds,
            long expiresAtEpochSeconds,
            String epochId,
            String partyAuthorityId,
            String caseCommandId,
            String payloadAuthorityId,
            String accessSessionId,
            String registrationId,
            String tenantSurrogate,
            String caseId,
            String roomType,
            String writerMode,
            long roomEpoch,
            long fencingToken,
            String actorId,
            ActorRole actorRole,
            String commandId,
            long commandSequence,
            IntakeCommandType commandType,
            IntakeParty party,
            String actorScopeHash,
            String commandPayloadRef,
            String commandPayloadHash,
            String commandOperationKey,
            String requestHash,
            long acceptedRoomRevision,
            String threadId,
            String agentSessionId,
            long processRevision,
            long roomRevision,
            long deadlineEpochMillis,
            RetryBudget admittedRetryBudget,
            String logicalRunId,
            String attemptId,
            String selectionHash,
            String registrationHash,
            AdmissionPins pins,
            String pinnedVersionsJson,
            String parityBaselineRef,
            String parityBaselineHash,
            String authorizationHash) {

        public PersistedAdmission {
            Objects.requireNonNull(commandType, "commandType must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            Objects.requireNonNull(admittedRetryBudget, "admittedRetryBudget must not be null");
            Objects.requireNonNull(pins, "pins must not be null");
            Objects.requireNonNull(pinnedVersionsJson, "pinnedVersionsJson must not be null");
        }

        public PinnedVersions activityPinnedVersions() {
            return new PinnedVersions(
                    "intake-pinned-versions.v1",
                    pins.roomWorkflowBuildId(),
                    pins.graphVersion(),
                    pins.checkpointSchemaVersion(),
                    pins.promptVersion(),
                    pins.modelProfileId(),
                    pins.outputSchemaVersion(),
                    pins.policyVersion(),
                    pins.guardrailVersion(),
                    pins.toolPolicyVersion());
        }
    }

    record AdmissionPins(
            String caseWorkflowType,
            String caseWorkflowBuildId,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            String processContractVersion,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String stateSchemaVersion,
            String streamProtocol,
            String promptVersion,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion,
            String cohortPolicyVersion,
            String agentKey,
            String agentSessionProfileVersion,
            String memoryPolicyId) {}
}
