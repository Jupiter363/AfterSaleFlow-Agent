package com.example.dispute.workflow.runtime.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.AuthorizationDecision;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.RuntimeAttestation;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionFinalizationBindingVerifierTest {

    @Test
    void acceptsHistoricalReceiptAuthorityWithCurrentRuntimeAttestation() {
        var fixture = ProductionFinalizationFixture.validParallel();
        ActivationGrant historical =
                ProductionFinalizationFixture.activeDecision(fixture).grant();
        String currentActivationId = "p9act.v1." + "2".repeat(32);
        String currentManifestHash = "8".repeat(64);
        String currentDbBindingHash = "7".repeat(64);
        var currentRuntime = new ProductionFinalizationRuntimeContextProvider.RuntimeContext(
                fixture.runtime().workflowId(),
                fixture.runtime().workflowRunId(),
                "current-finalizer-build",
                currentActivationId,
                currentManifestHash,
                currentDbBindingHash);
        var runtimeAttestation = new RuntimeAttestation(
                currentActivationId,
                historical.activationId(),
                historical.executionLane(),
                historical.tenantSurrogate(),
                historical.allowedRoomTypes(),
                currentRuntime.workflowBuildId(),
                historical.graphKey(),
                historical.graphVersion(),
                historical.checkpointSchemaVersion(),
                currentManifestHash,
                currentDbBindingHash,
                historical.lifecycle(),
                historical.issuedAt(),
                historical.expiresAt(),
                historical.revokedAt());

        var verifier = verifier();
        var source = new ProductionAuthorizedIntakeFinalizationSource(
                (request, result) -> java.util.Optional.of(fixture.state()),
                request -> AuthorizationDecision.allowed(historical, runtimeAttestation),
                () -> currentRuntime,
                new ProductionExecutionLaneVerifier(java.time.Clock.fixed(
                        ProductionFinalizationFixture.NOW, java.time.ZoneOffset.UTC)),
                (request, result, runtime, state) -> fixture.evidence(),
                verifier);
        var authorized = source.resolve(fixture.request(), fixture.result());
        var prepared = new ProductionIntakeRoomFinalizationStrategy(
                        source,
                        new ProductionAgentRunV2FinalizationFactsProvider(source),
                        org.mockito.Mockito.mock(
                                ProductionIntakeParallelAssemblyFinalizationPort.class))
                .prepare(fixture.request(), fixture.result());
        var verified = authorized.evidence();

        assertThat(fixture.request().streamProtocol()).isEqualTo("agent-stream.v4");
        assertThat(ExecuteAgentRunRequest.isParallelIntakeCommand(fixture.request().command()))
                .isTrue();
        assertThat(fixture.state().epoch().streamProtocol()).isEqualTo("agent-stream.v3");
        assertThat(verified.graphActivationId())
                .isEqualTo(ProductionFinalizationFixture.ACTIVATION_ID);
        assertThat(verified.finalizationActivationId())
                .isEqualTo(ProductionFinalizationFixture.ACTIVATION_ID);
        assertThat(prepared.receiptBindings().activationId())
                .isEqualTo(ProductionFinalizationFixture.ACTIVATION_ID);
        assertThat(prepared.activationManifestHash())
                .isEqualTo(fixture.evidence().activationManifestHash());
        assertThat(prepared.receiptBindings().isolatedDomainDbBindingHash())
                .isEqualTo(fixture.evidence()
                        .isolatedDomainDbBinding()
                        .required("binding_hash")
                        .textValue());
        assertThat(verified.commandEnvelopeHash())
                .isEqualTo(fixture.evidence()
                        .commandEnvelope()
                        .required("command_envelope_hash")
                        .textValue());
        assertThat(verified.isolatedDomainDbBindingHash())
                .isEqualTo(fixture.evidence()
                        .isolatedDomainDbBinding()
                        .required("binding_hash")
                        .textValue());

        var foreignHandoff = new RuntimeAttestation(
                runtimeAttestation.activationId(),
                "p9act.v1." + "3".repeat(32),
                runtimeAttestation.executionLane(),
                runtimeAttestation.tenantSurrogate(),
                runtimeAttestation.allowedRoomTypes(),
                runtimeAttestation.expectedAgentBuildId(),
                runtimeAttestation.graphKey(),
                runtimeAttestation.graphVersion(),
                runtimeAttestation.checkpointSchemaVersion(),
                runtimeAttestation.activationManifestHash(),
                runtimeAttestation.isolatedDomainDbBindingHash(),
                runtimeAttestation.lifecycle(),
                runtimeAttestation.issuedAt(),
                runtimeAttestation.expiresAt(),
                runtimeAttestation.revokedAt());
        var foreignSource = new ProductionAuthorizedIntakeFinalizationSource(
                (request, result) -> java.util.Optional.of(fixture.state()),
                request -> AuthorizationDecision.allowed(historical, foreignHandoff),
                () -> currentRuntime,
                new ProductionExecutionLaneVerifier(java.time.Clock.fixed(
                        ProductionFinalizationFixture.NOW, java.time.ZoneOffset.UTC)),
                (request, result, runtime, state) -> fixture.evidence(),
                verifier);
        assertThatThrownBy(() -> foreignSource.resolve(fixture.request(), fixture.result()))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("runtime handoff authority");

        var staleRuntimeSource = new ProductionAuthorizedIntakeFinalizationSource(
                (request, result) -> java.util.Optional.of(fixture.state()),
                request -> AuthorizationDecision.allowed(
                        historical,
                        ProductionFinalizationFixture.runtimeAttestation(fixture, historical)),
                () -> currentRuntime,
                new ProductionExecutionLaneVerifier(java.time.Clock.fixed(
                        ProductionFinalizationFixture.NOW, java.time.ZoneOffset.UTC)),
                (request, result, runtime, state) -> fixture.evidence(),
                verifier);
        assertThatThrownBy(() -> staleRuntimeSource.resolve(fixture.request(), fixture.result()))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("runtime activation");
    }

    @Test
    void rejectsHistoricalGraphActivationForLegacyProfile() {
        var fixture = ProductionFinalizationFixture.valid();
        ObjectNode currentDbBinding = fixture.evidence().isolatedDomainDbBinding().deepCopy();
        currentDbBinding.put("activation_id", "p9act.v1." + "2".repeat(32));
        putSelfHash(currentDbBinding, "binding_hash");
        var currentEvidence = new ProductionFinalizationEvidence(
                "8".repeat(64),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                fixture.evidence().proposalSource(),
                currentDbBinding);

        assertThatThrownBy(() -> verifier()
                        .verify(
                                fixture.request(),
                                fixture.result(),
                                fixture.state(),
                                currentEvidence))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("receipt authority activation id");
    }

    @Test
    void acceptsDistinctProposalAndArtifactIdsDerivedFromTheSamePayloadHash() {
        var fixture = ProductionFinalizationFixture.valid();

        var verified = verifier()
                .verify(fixture.request(), fixture.result(), fixture.state(), fixture.evidence());

        String hashPrefix = fixture.proposal().sha256().substring(0, 32);
        assertThat(fixture.evidence()
                        .proposalSource()
                        .required("proposal")
                        .required("proposal_id")
                        .textValue())
                .isEqualTo("target-proposal." + hashPrefix);
        assertThat(fixture.proposal().artifactId()).isEqualTo("intake.proposal." + hashPrefix);
        assertThat(fixture.proposal().uri()).startsWith("minio://");
        assertThat(fixture.evidence()
                        .proposalSource()
                        .required("proposal")
                        .required("payload_ref")
                        .textValue())
                .isEqualTo("urn:production-runtime:proposal:intake:" + fixture.proposal().sha256())
                .isNotEqualTo(fixture.proposal().uri());
        assertThat(verified.proposalHash()).hasSize(64);
        assertThat(verified.executionProvider()).isEqualTo("production-runtime-provider");
        assertThat(verified.executionModel()).isEqualTo("production-runtime-model-1");
    }

    @Test
    void rejectsMissingBlankAndOversizedExecutionIdentity() {
        var fixture = ProductionFinalizationFixture.valid();
        ObjectNode missingProvider = fixture.evidence().resultEnvelope().deepCopy();
        missingProvider.remove("execution_provider");
        assertExecutionEvidenceRejected(fixture, missingProvider, "fields are not exact");

        ObjectNode blankProvider = fixture.evidence().resultEnvelope().deepCopy();
        blankProvider.put("execution_provider", " ");
        putSelfHash(blankProvider, "result_envelope_hash");
        assertExecutionEvidenceRejected(blankProvider, fixture, "execution_provider");

        ObjectNode oversizedModel = fixture.evidence().resultEnvelope().deepCopy();
        oversizedModel.put("execution_model", "m".repeat(129));
        putSelfHash(oversizedModel, "result_envelope_hash");
        assertExecutionEvidenceRejected(oversizedModel, fixture, "execution_model");
    }

    @Test
    void rejectsProposalSourceThatSubstitutesTheMinioArtifactUri() {
        var fixture = ProductionFinalizationFixture.valid();
        ObjectNode proposalSource = fixture.evidence().proposalSource().deepCopy();
        ((ObjectNode) proposalSource.required("proposal"))
                .put("payload_ref", fixture.proposal().uri());
        var evidence = new ProductionFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                proposalSource,
                fixture.evidence().isolatedDomainDbBinding());

        assertThatThrownBy(() -> verifier()
                        .verify(fixture.request(), fixture.result(), fixture.state(), evidence))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("payload_ref");
    }

    @Test
    void rejectsProposalSourceUrnForADifferentPayloadHash() {
        var fixture = ProductionFinalizationFixture.valid();
        ObjectNode proposalSource = fixture.evidence().proposalSource().deepCopy();
        ((ObjectNode) proposalSource.required("proposal"))
                .put("payload_ref", "urn:production-runtime:proposal:intake:" + "f".repeat(64));
        var evidence = new ProductionFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                proposalSource,
                fixture.evidence().isolatedDomainDbBinding());

        assertThatThrownBy(() -> verifier()
                        .verify(fixture.request(), fixture.result(), fixture.state(), evidence))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("payload_ref");
    }

    @Test
    void rejectsProposalIdThatCopiesTheArtifactId() {
        var fixture = ProductionFinalizationFixture.valid();
        ObjectNode proposalSource = fixture.evidence().proposalSource().deepCopy();
        ((ObjectNode) proposalSource.required("proposal"))
                .put("proposal_id", fixture.proposal().artifactId());
        var evidence = new ProductionFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                proposalSource,
                fixture.evidence().isolatedDomainDbBinding());

        assertThatThrownBy(() -> verifier()
                        .verify(fixture.request(), fixture.result(), fixture.state(), evidence))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("proposal_id");
    }

    @Test
    void rejectsArtifactIdThatIsNotDerivedFromThePayloadHash() {
        var fixture = ProductionFinalizationFixture.valid();
        var invalidPointer = new ArtifactPointer(
                "intake.proposal." + "f".repeat(32),
                fixture.proposal().schemaVersion(),
                fixture.proposal().uri(),
                fixture.proposal().sha256());
        ExecuteAgentRunResult invalidResult = withProposal(fixture.result(), invalidPointer);

        assertThatThrownBy(() -> verifier()
                        .verify(fixture.request(), invalidResult, fixture.state(), fixture.evidence()))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("artifact_id");
    }

    @Test
    void acceptsRfc8785EquivalentIntegerAndLongNodesInGraphEnvelopes() throws Exception {
        var fixture = ProductionFinalizationFixture.valid();
        var mapper = JsonMapper.builder().findAndAddModules().build();
        ObjectNode commandEnvelope = (ObjectNode) mapper.readTree(
                ContractJson.canonicalString(fixture.evidence().commandEnvelope()));
        ObjectNode resultEnvelope = (ObjectNode) mapper.readTree(
                ContractJson.canonicalString(fixture.evidence().resultEnvelope()));
        var evidence = new ProductionFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                commandEnvelope,
                resultEnvelope,
                fixture.evidence().proposalSource(),
                fixture.evidence().isolatedDomainDbBinding());

        assertThat(commandEnvelope.required("command").required("room_epoch").getClass())
                .isNotEqualTo(fixture.evidence()
                        .commandEnvelope()
                        .required("command")
                        .required("room_epoch")
                        .getClass());
        assertThat(resultEnvelope.required("result").required("cognitive_revision").getClass())
                .isNotEqualTo(fixture.evidence()
                        .resultEnvelope()
                        .required("result")
                        .required("cognitive_revision")
                        .getClass());
        var verified = verifier()
                .verify(fixture.request(), fixture.result(), fixture.state(), evidence);

        assertThat(verified.commandHash()).isEqualTo(
                fixture.evidence().commandEnvelope().required("command_hash").textValue());
        assertThat(verified.resultHash()).isEqualTo(
                fixture.evidence().resultEnvelope().required("result_hash").textValue());
    }

    private static ProductionFinalizationBindingVerifier verifier() {
        return new ProductionFinalizationBindingVerifier(
                JsonMapper.builder().findAndAddModules().build());
    }

    private static void assertExecutionEvidenceRejected(
            ProductionFinalizationFixture.Fixture fixture,
            ObjectNode resultEnvelope,
            String message) {
        assertExecutionEvidenceRejected(resultEnvelope, fixture, message);
    }

    private static void assertExecutionEvidenceRejected(
            ObjectNode resultEnvelope,
            ProductionFinalizationFixture.Fixture fixture,
            String message) {
        var evidence = new ProductionFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                resultEnvelope,
                fixture.evidence().proposalSource(),
                fixture.evidence().isolatedDomainDbBinding());
        assertThatThrownBy(() -> verifier()
                        .verify(fixture.request(), fixture.result(), fixture.state(), evidence))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining(message);
    }

    private static void putSelfHash(ObjectNode value, String field) {
        ObjectNode preimage = value.deepCopy();
        preimage.remove(field);
        value.put(field, ContractJson.sha256Hex(preimage));
    }

    private static ExecuteAgentRunResult withProposal(
            ExecuteAgentRunResult result, ArtifactPointer proposal) {
        RoomGraphResult current = result.graphResult();
        RoomGraphResult unsigned = new RoomGraphResult(
                current.schemaVersion(),
                current.commandId(),
                current.logicalRunId(),
                current.attemptId(),
                current.graphKey(),
                current.graphVersion(),
                current.checkpointId(),
                current.cognitiveRevision(),
                current.status(),
                current.publicEventProposals(),
                List.of(new RoomGraphResult.ArtifactOperation(
                        ArtifactOperationType.PROPOSE_PATCH, proposal)),
                current.needsInput(),
                current.needsReview(),
                current.error(),
                "0".repeat(64),
                current.usage(),
                current.executionMetadata());
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
        return new ExecuteAgentRunResult(
                result.schemaVersion(),
                result.agentRunId(),
                result.logicalRunId(),
                result.attemptId(),
                result.attemptNo(),
                result.outcome(),
                graphResult,
                graphResult.outputHash(),
                result.lastSequenceNo(),
                result.publicOutputEmitted(),
                result.errorCode(),
                result.retryable(),
                result.recoveryAction(),
                result.completedAt());
    }
}
