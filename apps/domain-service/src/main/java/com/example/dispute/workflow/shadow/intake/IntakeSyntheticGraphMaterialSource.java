package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ActivityAuthority;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.GraphArtifactQuery;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import java.util.Objects;

/** Supplies execution lineage and immutable put metadata owned outside the Domain authority tables. */
public interface IntakeSyntheticGraphMaterialSource {

    GraphPlan loadPlan(GraphPlanQuery query);

    ArtifactMaterial loadArtifacts(GraphArtifactQuery query);

    record GraphPlanQuery(
            ActivityAuthority authority,
            String roomId,
            String epochId,
            String admittedLogicalRunId,
            String admittedAttemptId,
            IntakeGraphThreadBinding threadBinding,
            IntakeSnapshotReference initialSnapshot,
            IntakeEventReference event) {

        public GraphPlanQuery {
            Objects.requireNonNull(authority, "authority must not be null");
            Objects.requireNonNull(roomId, "roomId must not be null");
            Objects.requireNonNull(epochId, "epochId must not be null");
            Objects.requireNonNull(admittedLogicalRunId, "admittedLogicalRunId must not be null");
            Objects.requireNonNull(admittedAttemptId, "admittedAttemptId must not be null");
            Objects.requireNonNull(threadBinding, "threadBinding must not be null");
            Objects.requireNonNull(initialSnapshot, "initialSnapshot must not be null");
            Objects.requireNonNull(event, "event must not be null");
        }
    }

    record GraphPlan(
            String logicalRunId,
            String attemptId,
            long attemptNo,
            int attemptLimit,
            String previousAttemptId,
            boolean resetRequired,
            int publicSequenceOffset,
            String stageCode,
            String agentProfileId,
            String operation,
            String logicalIdempotencyKey,
            String envelopeKeyId,
            String envelopeNonce) {

        public GraphPlan {
            Objects.requireNonNull(logicalRunId, "logicalRunId must not be null");
            Objects.requireNonNull(attemptId, "attemptId must not be null");
            Objects.requireNonNull(stageCode, "stageCode must not be null");
            Objects.requireNonNull(agentProfileId, "agentProfileId must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            Objects.requireNonNull(logicalIdempotencyKey, "logicalIdempotencyKey must not be null");
            Objects.requireNonNull(envelopeKeyId, "envelopeKeyId must not be null");
            Objects.requireNonNull(envelopeNonce, "envelopeNonce must not be null");
            if (attemptNo < 1 || attemptLimit < attemptNo || attemptLimit > 3) {
                throw new IllegalArgumentException("Graph attempt lineage is invalid");
            }
        }
    }

    record ArtifactMaterial(ImmutablePayloadRef result, ImmutablePayloadRef proposal) {

        public ArtifactMaterial {
            Objects.requireNonNull(result, "result must not be null");
            Objects.requireNonNull(proposal, "proposal must not be null");
        }
    }
}
