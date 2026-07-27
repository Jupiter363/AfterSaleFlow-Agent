package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AuthorizationDecision;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider.RuntimeContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;

final class TargetE2eFinalizationFixture {

    static final Instant NOW = Instant.parse("2026-07-27T08:00:00Z");
    static final String HASH = "a".repeat(64);
    static final String RUN_ID = "RUN_TARGET_E2E";
    static final String ATTEMPT_ID = "ATTEMPT_TARGET_E2E";
    static final String CASE_ID = "CASE_TARGET_E2E";
    static final String TENANT = "tenant-target-e2e";
    static final String ROOM_ID = "ROOM_TARGET_E2E";
    static final String BUILD_ID = "target-e2e-agent-build";

    private TargetE2eFinalizationFixture() {}

    static Fixture valid() {
        String threadId = "grt.v1." + "b".repeat(32);
        var actor = new IntakePrivateThreadRegistration.ActorScope(
                "user-target-e2e",
                ActorRole.USER,
                Audience.USER,
                List.of("graph.command.execute"));
        IntakeGraphThreadBinding binding = new IntakePrivateThreadRegistrationFactory(() -> threadId)
                .issue(new IntakePrivateThreadRegistrationFactory.IssueRequest(
                        "REG_TARGET_E2E",
                        TENANT,
                        CASE_ID,
                        4,
                        91,
                        actor,
                        "SESSION_TARGET_E2E",
                        new IntakePrivateThreadRegistrationFactory.VersionPins(
                                "2.0.0",
                                "intake-checkpoint.v2",
                                "intake-prompt.v2",
                                "intake-model.target-e2e.v1",
                                "intake-policy.v2",
                                "intake-guardrail.v2",
                                "no-tools.v1"),
                        WriterMode.TEMPORAL,
                        NOW.minusSeconds(60)));
        var snapshot = new IntakeSnapshotReference(
                "SNAPSHOT_BINDING_TARGET_E2E",
                binding.registration().registrationId(),
                TENANT,
                CASE_ID,
                4,
                91,
                threadId,
                binding.registration().actorScopeHash(),
                binding.registration().agentSessionId(),
                new RoomGraphCommand.SnapshotRef(
                        "SNAPSHOT_TARGET_E2E",
                        "intake-domain-snapshot.v2",
                        "urn:intake:snapshot:target-e2e",
                        "c".repeat(64),
                        512),
                "snapshot-version-1",
                12,
                8,
                12,
                3,
                NOW.minusSeconds(50));
        var event = new IntakeEventReference(
                "EVENT_BINDING_TARGET_E2E",
                binding.registration().registrationId(),
                "EVENT_TARGET_E2E",
                "MESSAGE_TARGET_E2E",
                TENANT,
                CASE_ID,
                4,
                91,
                threadId,
                binding.registration().actorScopeHash(),
                binding.registration().agentSessionId(),
                new RoomGraphCommand.SnapshotRef(
                        "EVENT_TARGET_E2E",
                        "intake-turn-event.v2",
                        "urn:intake:event:target-e2e",
                        "d".repeat(64),
                        256),
                "event-version-1",
                4,
                13,
                Audience.USER,
                NOW.minusSeconds(40),
                NOW.minusSeconds(39));
        RoomGraphCommand command = new IntakeGraphCommandFactory().create(
                new IntakeGraphCommandFactory.CommandRequest(
                        "COMMAND_TARGET_E2E",
                        RUN_ID,
                        ATTEMPT_ID,
                        binding,
                        snapshot,
                        event,
                        14,
                        "INTAKE_ACTIVE",
                        7,
                        "intake-agent.v2",
                        2,
                        3,
                        1,
                        NOW.plusSeconds(300),
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                        "graph-envelope.target-e2e.v1",
                        "nonce-target-e2e"));
        ArtifactPointer proposal = new ArtifactPointer(
                "PROPOSAL_TARGET_E2E",
                "intake-turn-proposal.v2",
                "minio://target-e2e/intake/intake-turn-proposal.v2/PROPOSAL_TARGET_E2E/"
                        + HASH
                        + ".json",
                HASH);
        RoomGraphResult unsigned = new RoomGraphResult(
                "room-graph-result.v1",
                command.commandId(),
                RUN_ID,
                ATTEMPT_ID,
                command.graphKey(),
                command.graphVersion(),
                "CHECKPOINT_TARGET_E2E",
                9,
                GraphStatus.COMPLETED,
                List.of(),
                List.of(new RoomGraphResult.ArtifactOperation(
                        ArtifactOperationType.PROPOSE_PATCH, proposal)),
                null,
                null,
                null,
                "0".repeat(64),
                new Usage(20, 10, 30),
                new RoomGraphResult.ExecutionMetadata(
                        command.invocationContext().promptProfileId(),
                        command.invocationContext().modelProfileId(),
                        command.invocationContext().outputSchemaVersion(),
                        command.invocationContext().policyVersion(),
                        command.invocationContext().guardrailVersion()));
        RoomGraphResult graphResult = new RoomGraphResult(
                unsigned.schemaVersion(),
                unsigned.commandId(),
                unsigned.logicalRunId(),
                unsigned.attemptId(),
                unsigned.graphKey(),
                unsigned.graphVersion(),
                unsigned.checkpointId(),
                unsigned.cognitiveRevision(),
                unsigned.status(),
                unsigned.publicEventProposals(),
                unsigned.artifactOperations(),
                unsigned.needsInput(),
                unsigned.needsReview(),
                unsigned.error(),
                IntakeContractHashes.graphResultHash(unsigned),
                unsigned.usage(),
                unsigned.executionMetadata());
        var request = new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                RUN_ID,
                1,
                1,
                "agent-stream.v2",
                "e".repeat(64),
                null,
                false,
                0,
                command);
        var result = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                RUN_ID,
                RUN_ID,
                ATTEMPT_ID,
                1,
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graphResult,
                graphResult.outputHash(),
                11,
                true,
                null,
                false,
                null,
                NOW.minusSeconds(1));
        var run = new TargetE2eIntakeFinalizationState.LogicalRun(
                RUN_ID,
                TENANT,
                CASE_ID,
                ROOM_ID,
                "EPOCH_TARGET_E2E",
                "INTAKE",
                "key:target-e2e",
                "agent-stream.v2",
                "TEMPORAL_ACTIVITY",
                "RESULT_READY",
                "UNCOMMITTED",
                4,
                14,
                91,
                command.requestHash(),
                request.logicalInputHash(),
                ATTEMPT_ID,
                null,
                graphResult.outputHash());
        var attempt = new TargetE2eIntakeFinalizationState.Attempt(
                ATTEMPT_ID,
                RUN_ID,
                1,
                "RESULT_READY",
                "TEMPORAL_ACTIVITY",
                "target-e2e-provider",
                graphResult.executionMetadata().modelProfileId(),
                "target-e2e-model-1",
                graphResult.graphKey(),
                graphResult.graphVersion(),
                command.checkpointSchemaVersion(),
                graphResult.checkpointId(),
                graphResult.executionMetadata().promptVersion(),
                graphResult.executionMetadata().schemaVersion(),
                graphResult.executionMetadata().policyVersion(),
                graphResult.executionMetadata().guardrailVersion(),
                command.requestHash(),
                command.commandId(),
                command.requestHash(),
                request.logicalInputHash(),
                result.resultHash(),
                true,
                result.lastSequenceNo(),
                123,
                result.completedAt(),
                command,
                result);
        var epoch = new TargetE2eIntakeFinalizationState.Epoch(
                run.roomEpochId(),
                TENANT,
                CASE_ID,
                ROOM_ID,
                "INTAKE",
                "TEMPORAL",
                "ACTIVE",
                "READY",
                run.roomEpoch(),
                run.processRevision(),
                8,
                run.fencingToken(),
                command.graphKey(),
                command.graphVersion(),
                command.checkpointSchemaVersion(),
                "agent-stream.v2");
        var projection = new TargetE2eIntakeFinalizationState.Projection(
                TENANT,
                CASE_ID,
                "INTAKE",
                command.stageCode(),
                "TEMPORAL",
                "READY",
                run.processRevision(),
                run.roomEpoch(),
                run.fencingToken(),
                command.stageSequence());
        var state = new TargetE2eIntakeFinalizationState(
                run,
                attempt,
                epoch,
                projection,
                "REGISTERED",
                "ACTIVE",
                "ACTIVE",
                "ACTIVE",
                binding,
                snapshot,
                event,
                new ArtifactPointer(
                        "GRAPH_RESULT_TARGET_E2E",
                        graphResult.schemaVersion(),
                        "urn:graph-result:target-e2e",
                        result.resultHash()));
        var runtime = new RuntimeContext(
                TemporalAgentRunV2WorkflowLauncher.workflowId(RUN_ID),
                "TEMPORAL_RUN_TARGET_E2E",
                BUILD_ID);
        return new Fixture(request, result, state, runtime, proposal);
    }

    static AuthorizationDecision activeDecision() {
        return AuthorizationDecision.allowed(new ActivationGrant(
                "ACTIVATION_TARGET_E2E",
                TargetE2eExecutionLaneVerifier.EXECUTION_LANE,
                TENANT,
                Set.of(CASE_ID),
                Set.of(com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE),
                BUILD_ID,
                NOW.minusSeconds(120),
                NOW.plusSeconds(120),
                null));
    }

    record Fixture(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            TargetE2eIntakeFinalizationState state,
            RuntimeContext runtime,
            ArtifactPointer proposal) {}
}
