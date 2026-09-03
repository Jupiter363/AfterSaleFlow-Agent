package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.time.Instant;
import java.util.Objects;

/** One repeatable-read snapshot of all Java-owned facts required by Intake finalization. */
public record TargetE2eIntakeFinalizationState(
        LogicalRun run,
        Attempt attempt,
        Epoch epoch,
        Projection projection,
        String threadRegistrationStatus,
        String participantStatus,
        String accessSessionStatus,
        String agentSessionStatus,
        IntakeGraphThreadBinding threadBinding,
        IntakeSnapshotReference initialSnapshot,
        IntakeEventReference event,
        ArtifactPointer graphOutput) {

    public TargetE2eIntakeFinalizationState {
        run = Objects.requireNonNull(run, "run");
        attempt = Objects.requireNonNull(attempt, "attempt");
        epoch = Objects.requireNonNull(epoch, "epoch");
        projection = Objects.requireNonNull(projection, "projection");
        threadRegistrationStatus = Objects.requireNonNull(
                threadRegistrationStatus, "threadRegistrationStatus");
        participantStatus = Objects.requireNonNull(participantStatus, "participantStatus");
        accessSessionStatus = Objects.requireNonNull(accessSessionStatus, "accessSessionStatus");
        agentSessionStatus = Objects.requireNonNull(agentSessionStatus, "agentSessionStatus");
        threadBinding = Objects.requireNonNull(threadBinding, "threadBinding");
        initialSnapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        graphOutput = Objects.requireNonNull(graphOutput, "graphOutput");
    }

    public record LogicalRun(
            String agentRunId,
            String tenantSurrogate,
            String caseId,
            String roomId,
            String roomEpochId,
            String roomType,
            String logicalIdempotencyKey,
            String protocol,
            String executorKind,
            String runStatus,
            String finalizationStatus,
            long roomEpoch,
            long processRevision,
            long fencingToken,
            String requestHash,
            String logicalInputHash,
            String resultReadyAttemptId,
            String committedAttemptId,
            String finalResultHash) {}

    public record Attempt(
            String attemptId,
            String agentRunId,
            long attemptNo,
            String attemptStatus,
            String executorKind,
            String provider,
            String modelProfileId,
            String modelVersion,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String checkpointId,
            String promptVersion,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String requestHash,
            String commandId,
            String commandRequestHash,
            String logicalInputHash,
            String resultHash,
            boolean finalFrameObserved,
            long lastSequenceNo,
            long latencyMs,
            Instant completedAt,
            RoomGraphCommand persistedCommand,
            ExecuteAgentRunResult persistedResult) {}

    public record Epoch(
            String epochId,
            String tenantSurrogate,
            String caseId,
            String roomId,
            String roomType,
            String writerMode,
            String lifecycleStatus,
            String provisioningStatus,
            long roomEpoch,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol) {}

    public record Projection(
            String tenantSurrogate,
            String caseId,
            String currentRoom,
            String roomPhase,
            String writerMode,
            String writerActivationStatus,
            long processRevision,
            long roomEpoch,
            long fencingToken,
            long lastCommandSequence) {}
}
