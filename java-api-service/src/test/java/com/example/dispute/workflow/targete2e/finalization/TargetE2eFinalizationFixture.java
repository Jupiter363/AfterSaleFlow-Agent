package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AuthorizationRequest;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AuthorizationDecision;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.Lifecycle;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider.RuntimeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    static final String ACTIVATION_ID = "p9act.v1." + "1".repeat(32);
    static final String ACTIVATION_MANIFEST_HASH = "9".repeat(64);
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private TargetE2eFinalizationFixture() {}

    static Fixture valid() {
        String threadId = "grt.v1." + "b".repeat(32);
        var actor = new IntakePrivateThreadRegistration.ActorScope(
                "user-target-e2e",
                ActorRole.USER,
                Audience.USER,
                List.of("graph.command.execute"));
        IntakeGraphThreadBinding binding = targetBinding(threadId, actor);
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
                "urn:target-e2e:proposal:intake:001",
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
        ObjectNode proposalSource = MAPPER.createObjectNode();
        proposalSource.put("schema_version", "target-e2e-room-proposal-source.v1");
        proposalSource.put("room_type", "INTAKE");
        ObjectNode normalizedProposal = proposalSource.putObject("proposal");
        normalizedProposal.put("schema_version", "target-e2e-intake-proposal.v1");
        normalizedProposal.put("proposal_id", proposal.artifactId());
        normalizedProposal.put("command_id", command.commandId());
        normalizedProposal.put("logical_run_id", RUN_ID);
        normalizedProposal.put("attempt_id", ATTEMPT_ID);
        normalizedProposal.put("payload_schema_version", proposal.schemaVersion());
        normalizedProposal.put("payload_ref", proposal.uri());
        normalizedProposal.put("payload_hash", proposal.sha256());
        normalizedProposal.put("terminal_class", "COMPLETED");
        normalizedProposal.put("formal_authority", false);
        String proposalHash = ContractJson.sha256Hex(normalizedProposal);

        ObjectNode commandEnvelope = MAPPER.createObjectNode();
        commandEnvelope.put("schema_version", "target-e2e-graph-command-envelope.v1");
        commandEnvelope.put("execution_lane", TargetE2eExecutionLaneVerifier.EXECUTION_LANE);
        commandEnvelope.put("activation_id", ACTIVATION_ID);
        commandEnvelope.put("room_fencing_token", state.run().fencingToken());
        commandEnvelope.put("command_hash", ContractJson.sha256Hex(MAPPER.valueToTree(command)));
        commandEnvelope.set("command", MAPPER.valueToTree(command));
        putSelfHash(commandEnvelope, "command_envelope_hash");

        ObjectNode resultEnvelope = MAPPER.createObjectNode();
        resultEnvelope.put("schema_version", "target-e2e-graph-result-envelope.v1");
        resultEnvelope.put("execution_lane", TargetE2eExecutionLaneVerifier.EXECUTION_LANE);
        resultEnvelope.put("activation_id", ACTIVATION_ID);
        resultEnvelope.put("room_fencing_token", state.run().fencingToken());
        resultEnvelope.put("command_hash", commandEnvelope.required("command_hash").textValue());
        resultEnvelope.put(
                "command_envelope_hash",
                commandEnvelope.required("command_envelope_hash").textValue());
        resultEnvelope.put("result_hash", result.resultHash());
        resultEnvelope.put("proposal_hash", proposalHash);
        resultEnvelope.put("graph_output_authority", "PROPOSAL_ONLY");
        resultEnvelope.set("result", MAPPER.valueToTree(graphResult));
        putSelfHash(resultEnvelope, "result_envelope_hash");

        ObjectNode dbBinding = MAPPER.createObjectNode();
        dbBinding.put("schema_version", "target-e2e-isolated-domain-db-binding.v1");
        dbBinding.put("environment_id", "p9-isolated-preprod-01");
        dbBinding.put("environment_generation", 7);
        dbBinding.put("activation_id", ACTIVATION_ID);
        dbBinding.put("binding_kind", "ISOLATED_DOMAIN_POSTGRESQL");
        dbBinding.put("cluster_identity", "p9-domain-cluster-01");
        dbBinding.put("database_identity", "p9-domain-db-01");
        dbBinding.put("runtime_principal_identity", "p9-java-domain-runtime-01");
        putSelfHash(dbBinding, "binding_hash");
        var evidence = new TargetE2eFinalizationEvidence(
                ACTIVATION_MANIFEST_HASH,
                commandEnvelope,
                resultEnvelope,
                proposalSource,
                dbBinding);
        return new Fixture(request, result, state, runtime, proposal, evidence);
    }

    static AuthorizationDecision activeDecision(Fixture fixture) {
        return AuthorizationDecision.allowed(new ActivationGrant(
                ACTIVATION_ID,
                TargetE2eExecutionLaneVerifier.EXECUTION_LANE,
                TENANT,
                Set.of(CASE_ID),
                Set.of(com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE),
                BUILD_ID,
                TargetE2eExecutionLaneVerifier.GRAPH_KEY,
                TargetE2eExecutionLaneVerifier.GRAPH_VERSION,
                TargetE2eExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION,
                ACTIVATION_MANIFEST_HASH,
                fixture.evidence().isolatedDomainDbBinding().required("binding_hash").textValue(),
                Lifecycle.ACTIVE,
                null,
                NOW.minusSeconds(120),
                NOW.plusSeconds(120),
                null));
    }

    static TargetE2eAuthorizedIntakeFinalizationSource authorizedSource(Fixture fixture) {
        return new TargetE2eAuthorizedIntakeFinalizationSource(
                (request, result) -> java.util.Optional.of(fixture.state()),
                request -> activeDecision(fixture),
                () -> fixture.runtime(),
                new TargetE2eExecutionLaneVerifier(
                        java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)),
                (request, result, runtime, state) -> fixture.evidence(),
                new TargetE2eFinalizationBindingVerifier(MAPPER));
    }

    private static void putSelfHash(ObjectNode value, String field) {
        ObjectNode preimage = value.deepCopy();
        preimage.remove(field);
        value.put(field, ContractJson.sha256Hex(preimage));
    }

    private static IntakeGraphThreadBinding targetBinding(
            String threadId, IntakePrivateThreadRegistration.ActorScope actor) {
        String actorScopeHash = IntakeContractHashes.actorScopeHash(actor);
        var unsigned = new IntakePrivateThreadRegistration(
                "graph-private-thread-registration.v1",
                "REG_TARGET_E2E",
                TENANT,
                CASE_ID,
                "INTAKE",
                4,
                threadId,
                actor,
                actorScopeHash,
                "SESSION_TARGET_E2E",
                TargetE2eExecutionLaneVerifier.GRAPH_KEY,
                TargetE2eExecutionLaneVerifier.GRAPH_VERSION,
                TargetE2eExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION,
                "intake-graph-state.v2",
                "intake-prompt.v2",
                "intake-model.target-e2e.v1",
                "intake-turn-proposal.v2",
                "intake-policy.v2",
                "intake-guardrail.v2",
                "no-tools.v1",
                WriterMode.TEMPORAL,
                NOW.minusSeconds(60),
                "0".repeat(64));
        var registration = new IntakePrivateThreadRegistration(
                unsigned.schemaVersion(),
                unsigned.registrationId(),
                unsigned.tenantSurrogate(),
                unsigned.caseId(),
                unsigned.roomType(),
                unsigned.roomEpoch(),
                unsigned.threadId(),
                unsigned.actorScope(),
                unsigned.actorScopeHash(),
                unsigned.agentSessionId(),
                unsigned.graphKey(),
                unsigned.graphVersion(),
                unsigned.checkpointSchemaVersion(),
                unsigned.stateSchemaVersion(),
                unsigned.promptVersion(),
                unsigned.modelProfileId(),
                unsigned.outputSchemaVersion(),
                unsigned.policyVersion(),
                unsigned.guardrailVersion(),
                unsigned.toolPolicyVersion(),
                unsigned.writerMode(),
                unsigned.issuedAt(),
                IntakeContractHashes.registrationHash(unsigned));
        return new IntakeGraphThreadBinding(registration, 91);
    }

    record Fixture(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            TargetE2eIntakeFinalizationState state,
            RuntimeContext runtime,
            ArtifactPointer proposal,
            TargetE2eFinalizationEvidence evidence) {

        AuthorizationRequest authorizationRequest() {
            var verified = new TargetE2eFinalizationBindingVerifier(MAPPER)
                    .verify(request, result, state, evidence);
            return new AuthorizationRequest(
                    TENANT,
                    CASE_ID,
                    ROOM_ID,
                    com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE,
                    RUN_ID,
                    runtime.workflowId(),
                    runtime.workflowRunId(),
                    runtime.workflowBuildId(),
                    request.command().commandId(),
                    verified.commandHash(),
                    verified.commandEnvelopeHash(),
                    request.command().roomEpoch(),
                    state.run().fencingToken());
        }
    }
}
