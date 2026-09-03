package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyLookup;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.ActorScope;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.InvocationContext;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.SnapshotRef;
import com.example.dispute.workflow.targete2e.TargetE2eIsolatedDomainDbBinding;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationEnvironmentSource.EnvironmentEvidence;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider.RuntimeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReadyAssemblyTargetE2eFinalizationEvidenceProviderTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final String RUN = "RUN_PARALLEL_EVIDENCE";
    private static final String ATTEMPT = "ATTEMPT_PARALLEL_EVIDENCE";
    private static final String COMMAND = "COMMAND_PARALLEL_EVIDENCE";
    private static final String REQUEST_HASH = "1".repeat(64);
    private static final String RESULT_HASH = "2".repeat(64);
    private static final String AUTHORITY_ACTIVATION = "p9act.v1." + "3".repeat(32);

    @Test
    void loadsCanonicalReadyEvidenceWithoutCallingTheLegacyGraph() throws Exception {
        IntakeParallelAssemblyStore store = mock(IntakeParallelAssemblyStore.class);
        ReadyArtifact artifact = artifact(validCommandEnvelopeBytes());
        ReadyLookup lookup = new ReadyLookup(RUN, ATTEMPT, COMMAND, REQUEST_HASH);
        when(store.loadReady(lookup)).thenReturn(Optional.of(artifact));
        TargetE2eFinalizationEnvironmentSource environmentSource =
                mock(TargetE2eFinalizationEnvironmentSource.class);
        when(environmentSource.loadEnvironmentEvidence(AUTHORITY_ACTIVATION))
                .thenReturn(environment());
        var provider = new ReadyAssemblyTargetE2eFinalizationEvidenceProvider(
                store, environmentSource, MAPPER);
        ExecuteAgentRunRequest request = parallelRequest();
        ExecuteAgentRunResult result = completedResult();

        TargetE2eFinalizationEvidence evidence = provider.resolve(
                request, result, mock(RuntimeContext.class), mock(TargetE2eIntakeFinalizationState.class));

        assertThat(evidence.activationManifestHash()).isEqualTo("9".repeat(64));
        assertThat(evidence.commandEnvelope())
                .isEqualTo(MAPPER.readTree(validCommandEnvelopeBytes()));
        assertThat(evidence.resultEnvelope()).isEqualTo(MAPPER.readTree("{}"));
        assertThat(evidence.proposalSource()).isEqualTo(MAPPER.readTree("{}"));
        assertThat(evidence.isolatedDomainDbBinding().required("binding_hash").textValue())
                .isEqualTo(environment().domainDbBindingHash());
        verify(store).loadReady(lookup);
        verify(environmentSource).loadEnvironmentEvidence(AUTHORITY_ACTIVATION);
        verify(environmentSource, never()).loadEnvironmentEvidence();
    }

    @Test
    void rejectsNonCanonicalReadyEnvelopeBytes() {
        IntakeParallelAssemblyStore store = mock(IntakeParallelAssemblyStore.class);
        when(store.loadReady(new ReadyLookup(RUN, ATTEMPT, COMMAND, REQUEST_HASH)))
                .thenReturn(Optional.of(artifact(("{ \"activation_id\":\""
                                + AUTHORITY_ACTIVATION
                                + "\" }")
                        .getBytes(StandardCharsets.UTF_8))));
        var provider = new ReadyAssemblyTargetE2eFinalizationEvidenceProvider(
                store, ReadyAssemblyTargetE2eFinalizationEvidenceProviderTest::environment, MAPPER);

        assertThatThrownBy(() -> provider.resolve(
                        parallelRequest(),
                        completedResult(),
                        mock(RuntimeContext.class),
                        mock(TargetE2eIntakeFinalizationState.class)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_PARALLEL_EVIDENCE_INVALID");
    }

    @Test
    void routesOnlyTheExactParallelProfileToReadyEvidence() {
        TargetE2eFinalizationEvidenceProvider legacy = mock(TargetE2eFinalizationEvidenceProvider.class);
        TargetE2eFinalizationEvidenceProvider parallel = mock(TargetE2eFinalizationEvidenceProvider.class);
        TargetE2eFinalizationEvidence expected = mock(TargetE2eFinalizationEvidence.class);
        ExecuteAgentRunRequest request = parallelRequest();
        ExecuteAgentRunResult result = completedResult();
        RuntimeContext runtime = mock(RuntimeContext.class);
        TargetE2eIntakeFinalizationState state = mock(TargetE2eIntakeFinalizationState.class);
        when(parallel.resolve(request, result, runtime, state)).thenReturn(expected);
        var router = new RoutingTargetE2eFinalizationEvidenceProvider(legacy, parallel);

        assertThat(router.resolve(request, result, runtime, state)).isSameAs(expected);
        verify(parallel).resolve(request, result, runtime, state);
        verify(legacy, never()).resolve(request, result, runtime, state);
    }

    @Test
    void preservesTheLegacyProviderForEveryNonParallelCommand() {
        TargetE2eFinalizationEvidenceProvider legacy = mock(TargetE2eFinalizationEvidenceProvider.class);
        TargetE2eFinalizationEvidenceProvider parallel = mock(TargetE2eFinalizationEvidenceProvider.class);
        TargetE2eFinalizationEvidence expected = mock(TargetE2eFinalizationEvidence.class);
        ExecuteAgentRunRequest request = legacyRequest();
        ExecuteAgentRunResult result = completedResult();
        RuntimeContext runtime = mock(RuntimeContext.class);
        TargetE2eIntakeFinalizationState state = mock(TargetE2eIntakeFinalizationState.class);
        when(legacy.resolve(request, result, runtime, state)).thenReturn(expected);
        var router = new RoutingTargetE2eFinalizationEvidenceProvider(legacy, parallel);

        assertThat(router.resolve(request, result, runtime, state)).isSameAs(expected);
        verify(legacy).resolve(request, result, runtime, state);
        verify(parallel, never()).resolve(request, result, runtime, state);
    }

    @Test
    void rejectsMixedParallelMarkersBeforeEitherProviderIsCalled() {
        TargetE2eFinalizationEvidenceProvider legacy = mock(TargetE2eFinalizationEvidenceProvider.class);
        TargetE2eFinalizationEvidenceProvider parallel = mock(TargetE2eFinalizationEvidenceProvider.class);
        ExecuteAgentRunRequest request = legacyRequest();
        when(request.command().invocationContext().agentProfileId())
                .thenReturn(ExecuteAgentRunRequest.PARALLEL_INTAKE_AGENT_PROFILE_ID);
        ExecuteAgentRunResult result = completedResult();
        RuntimeContext runtime = mock(RuntimeContext.class);
        TargetE2eIntakeFinalizationState state = mock(TargetE2eIntakeFinalizationState.class);
        var router = new RoutingTargetE2eFinalizationEvidenceProvider(legacy, parallel);

        assertThatThrownBy(() -> router.resolve(request, result, runtime, state))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_PROFILE_MIXED");
        verify(legacy, never()).resolve(request, result, runtime, state);
        verify(parallel, never()).resolve(request, result, runtime, state);
    }

    private static EnvironmentEvidence environment() {
        String activation = AUTHORITY_ACTIVATION;
        String environment = "p9-isolated-preprod-01";
        long generation = 7;
        String cluster = "p9-domain-cluster-01";
        String database = "p9-domain-db-01";
        String principal = "p9-java-domain-runtime-01";
        return new EnvironmentEvidence(
                activation,
                "9".repeat(64),
                environment,
                generation,
                cluster,
                database,
                principal,
                TargetE2eIsolatedDomainDbBinding.hash(
                        environment, generation, activation, cluster, database, principal));
    }

    private static byte[] validCommandEnvelopeBytes() {
        return ("{\"activation_id\":\"" + AUTHORITY_ACTIVATION + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static ReadyArtifact artifact(byte[] commandEnvelopeBytes) {
        String proposalHash = "3".repeat(64);
        byte[] emptyObject = "{}".getBytes(StandardCharsets.UTF_8);
        return new ReadyArtifact(
                "4".repeat(64),
                "intake.proposal." + proposalHash.substring(0, 32),
                "urn:target-e2e:proposal:intake:" + proposalHash,
                proposalHash,
                emptyObject,
                "profile.parallel.v1",
                "intake.graph-result." + RESULT_HASH.substring(0, 32),
                "urn:target-e2e:result:intake:" + RESULT_HASH,
                RESULT_HASH,
                emptyObject,
                commandEnvelopeBytes,
                "5".repeat(64),
                emptyObject,
                "6".repeat(64),
                emptyObject,
                "7".repeat(64),
                "checkpoint.parallel.v1",
                "8".repeat(64),
                "no-tools.v1");
    }

    private static ExecuteAgentRunRequest parallelRequest() {
        ExecuteAgentRunRequest request = mock(ExecuteAgentRunRequest.class);
        RoomGraphCommand command = mock(RoomGraphCommand.class);
        InvocationContext invocation = mock(InvocationContext.class);
        ActorScope actor = mock(ActorScope.class);
        when(request.command()).thenReturn(command);
        when(request.streamProtocol()).thenReturn("agent-stream.v4");
        when(request.logicalRunId()).thenReturn(RUN);
        when(request.attemptId()).thenReturn(ATTEMPT);
        when(command.commandId()).thenReturn(COMMAND);
        when(command.requestHash()).thenReturn(REQUEST_HASH);
        when(command.roomType()).thenReturn(RoomType.INTAKE);
        when(command.roomId()).thenReturn("ROOM_1");
        when(command.isExactParallelIntakeProfile()).thenReturn(true);
        when(command.invocationContext()).thenReturn(invocation);
        when(command.actorScope()).thenReturn(actor);
        when(command.eventRef()).thenReturn(mock(SnapshotRef.class));
        when(actor.actorRole()).thenReturn(ActorRole.USER);
        when(actor.audience()).thenReturn(Audience.USER);
        when(invocation.agentProfileId())
                .thenReturn(ExecuteAgentRunRequest.PARALLEL_INTAKE_AGENT_PROFILE_ID);
        when(invocation.outputSchemaVersion())
                .thenReturn(ExecuteAgentRunRequest.PARALLEL_INTAKE_OUTPUT_SCHEMA);
        return request;
    }

    private static ExecuteAgentRunRequest legacyRequest() {
        ExecuteAgentRunRequest request = mock(ExecuteAgentRunRequest.class);
        RoomGraphCommand command = mock(RoomGraphCommand.class);
        InvocationContext invocation = mock(InvocationContext.class);
        when(request.command()).thenReturn(command);
        when(request.streamProtocol()).thenReturn("agent-stream.v3");
        when(command.invocationContext()).thenReturn(invocation);
        when(invocation.agentProfileId()).thenReturn("dispute-intake-officer.v3");
        when(invocation.outputSchemaVersion()).thenReturn("target-e2e-room-proposal-source.v1");
        return request;
    }

    private static ExecuteAgentRunResult completedResult() {
        ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
        when(result.resultHash()).thenReturn(RESULT_HASH);
        when(result.graphResult()).thenReturn(mock(com.example.dispute.workflow.contract.v1.RoomGraphResult.class));
        return result;
    }
}
