package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.shadow.intake.IntakeShadowParityService.ParitySnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.util.Objects;

/**
 * Read-only authority source for the comparison-only synthetic Intake runtime.
 *
 * <p>The Activity requests intentionally contain references rather than private snapshot bodies,
 * Graph lineage, immutable output receipts, or legacy projection facts. Implementations must load
 * those values from the persisted signed-synthetic authority tuple and private stores. Returning an
 * Activity receipt from this boundary is forbidden; the adapters below build receipts only after
 * the existing publishers and signed Graph clients have completed.
 */
public interface IntakeSyntheticRuntimeSource {

    SnapshotInput loadSnapshot(SnapshotPublicationRequest request);

    GraphInput loadGraph(GraphExecutionRequest request);

    GraphArtifacts loadGraphArtifacts(GraphArtifactQuery query);

    ParityInput loadParity(TurnFinalizationRequest request);

    /** Exact persisted Activity authority evidence, including pins and retry mode. */
    record ActivityAuthority(
            ActivityEnvelope envelope,
            String threadId,
            String agentSessionId,
            String operationKey,
            String requestHash) {

        public ActivityAuthority {
            Objects.requireNonNull(envelope, "envelope must not be null");
            Objects.requireNonNull(threadId, "threadId must not be null");
            Objects.requireNonNull(agentSessionId, "agentSessionId must not be null");
            Objects.requireNonNull(operationKey, "operationKey must not be null");
            Objects.requireNonNull(requestHash, "requestHash must not be null");
        }
    }

    /** Full private input consumed by the existing snapshot publisher. */
    record SnapshotInput(
            ActivityAuthority authority,
            long domainRevision,
            IntakeDomainSnapshotPublisher.SnapshotRequest publication) {

        public SnapshotInput {
            Objects.requireNonNull(authority, "authority must not be null");
            Objects.requireNonNull(publication, "publication must not be null");
            if (domainRevision < 0) {
                throw new IllegalArgumentException("domainRevision must not be negative");
            }
        }
    }

    /** Full command-factory and AgentRun lineage input for one signed Graph invocation. */
    record GraphInput(
            ActivityAuthority authority,
            IntakeGraphCommandFactory.CommandRequest command,
            AgentRunCommandBindingFactory.Context bindingContext,
            long attemptNo,
            int attemptLimit,
            String previousAttemptId,
            boolean resetRequired,
            int publicSequenceOffset) {

        public GraphInput {
            Objects.requireNonNull(authority, "authority must not be null");
            Objects.requireNonNull(command, "command must not be null");
            Objects.requireNonNull(bindingContext, "bindingContext must not be null");
            if (attemptNo < 1 || attemptLimit < attemptNo || attemptLimit > 3) {
                throw new IllegalArgumentException("Graph attempt lineage is invalid");
            }
        }
    }

    /** Query used to bind returned Graph data to immutable object-store metadata. */
    record GraphArtifactQuery(
            GraphExecutionRequest activityRequest,
            RoomGraphCommand command,
            RoomGraphResult result,
            String resultRef) {

        public GraphArtifactQuery {
            Objects.requireNonNull(activityRequest, "activityRequest must not be null");
            Objects.requireNonNull(command, "command must not be null");
            Objects.requireNonNull(result, "result must not be null");
            Objects.requireNonNull(resultRef, "resultRef must not be null");
        }
    }

    /** Exact immutable put metadata absent from {@link RoomGraphResult}. */
    record GraphArtifacts(
            ActivityAuthority authority,
            ImmutablePayloadRef result,
            ImmutablePayloadRef proposal) {

        public GraphArtifacts {
            Objects.requireNonNull(authority, "authority must not be null");
            Objects.requireNonNull(result, "result must not be null");
            Objects.requireNonNull(proposal, "proposal must not be null");
        }
    }

    /** Legacy and shadow projection values are restricted to classifications and SHA-256 hashes. */
    record ParityInput(
            ActivityAuthority authority,
            String resultHash,
            String proposalHash,
            ParitySnapshot legacy,
            ParitySnapshot shadow,
            IntakeDomainEventType projectedEventType) {

        public ParityInput {
            Objects.requireNonNull(authority, "authority must not be null");
            Objects.requireNonNull(resultHash, "resultHash must not be null");
            Objects.requireNonNull(proposalHash, "proposalHash must not be null");
            Objects.requireNonNull(legacy, "legacy must not be null");
            Objects.requireNonNull(shadow, "shadow must not be null");
            Objects.requireNonNull(projectedEventType, "projectedEventType must not be null");
        }
    }
}
