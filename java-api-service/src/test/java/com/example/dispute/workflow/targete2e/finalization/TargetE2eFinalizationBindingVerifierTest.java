package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.AuthorizationDecision;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetE2eFinalizationBindingVerifierTest {

    @Test
    void acceptsHistoricalGraphActivationWhenCurrentGrantOwnsFormalWriteBinding() {
        var fixture = TargetE2eFinalizationFixture.validParallel();
        String currentActivationId = "p9act.v1." + "2".repeat(32);
        String currentManifestHash = "8".repeat(64);
        ObjectNode currentDbBinding = fixture.evidence().isolatedDomainDbBinding().deepCopy();
        currentDbBinding.put("activation_id", currentActivationId);
        putSelfHash(currentDbBinding, "binding_hash");
        var currentEvidence = new TargetE2eFinalizationEvidence(
                currentManifestHash,
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                fixture.evidence().proposalSource(),
                currentDbBinding);
        ActivationGrant historical =
                TargetE2eFinalizationFixture.activeDecision(fixture).grant();
        var currentGrant = new ActivationGrant(
                currentActivationId,
                historical.executionLane(),
                historical.tenantSurrogate(),
                historical.allowedCaseIds(),
                historical.allowedRoomTypes(),
                historical.expectedAgentBuildId(),
                historical.graphKey(),
                historical.graphVersion(),
                historical.checkpointSchemaVersion(),
                currentManifestHash,
                currentDbBinding.required("binding_hash").textValue(),
                historical.lifecycle(),
                historical.acceptedCommandProof(),
                historical.issuedAt(),
                historical.expiresAt(),
                historical.revokedAt());

        var verifier = verifier();
        var source = new TargetE2eAuthorizedIntakeFinalizationSource(
                (request, result) -> java.util.Optional.of(fixture.state()),
                request -> AuthorizationDecision.allowed(currentGrant),
                () -> fixture.runtime(),
                new TargetE2eExecutionLaneVerifier(java.time.Clock.fixed(
                        TargetE2eFinalizationFixture.NOW, java.time.ZoneOffset.UTC)),
                (request, result, runtime, state) -> currentEvidence,
                verifier);
        var authorized = source.resolve(fixture.request(), fixture.result());
        var prepared = new TargetE2eIntakeRoomFinalizationStrategy(
                        source,
                        new TargetE2eAgentRunV2FinalizationFactsProvider(source),
                        org.mockito.Mockito.mock(
                                TargetE2eIntakeParallelAssemblyFinalizationPort.class))
                .prepare(fixture.request(), fixture.result());
        var verified = authorized.evidence();

        assertThatThrownBy(() -> verifier.requireGrantBindings(historical, verified))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("finalization activation id");
        assertThat(fixture.request().streamProtocol()).isEqualTo("agent-stream.v4");
        assertThat(ExecuteAgentRunRequest.isParallelIntakeCommand(fixture.request().command()))
                .isTrue();
        assertThat(fixture.state().epoch().streamProtocol()).isEqualTo("agent-stream.v3");
        assertThat(verified.graphActivationId())
                .isEqualTo(TargetE2eFinalizationFixture.ACTIVATION_ID);
        assertThat(verified.finalizationActivationId()).isEqualTo(currentActivationId);
        assertThat(prepared.receiptBindings().activationId()).isEqualTo(currentActivationId);
        assertThat(prepared.activationManifestHash()).isEqualTo(currentManifestHash);
        assertThat(prepared.receiptBindings().isolatedDomainDbBindingHash())
                .isEqualTo(currentDbBinding.required("binding_hash").textValue());
        assertThat(verified.commandEnvelopeHash())
                .isEqualTo(fixture.evidence()
                        .commandEnvelope()
                        .required("command_envelope_hash")
                        .textValue());
        assertThat(verified.isolatedDomainDbBindingHash())
                .isEqualTo(currentDbBinding.required("binding_hash").textValue());
    }

    @Test
    void rejectsHistoricalGraphActivationForLegacyProfile() {
        var fixture = TargetE2eFinalizationFixture.valid();
        ObjectNode currentDbBinding = fixture.evidence().isolatedDomainDbBinding().deepCopy();
        currentDbBinding.put("activation_id", "p9act.v1." + "2".repeat(32));
        putSelfHash(currentDbBinding, "binding_hash");
        var currentEvidence = new TargetE2eFinalizationEvidence(
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
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("legacy finalization activation id");
    }

    @Test
    void acceptsDistinctProposalAndArtifactIdsDerivedFromTheSamePayloadHash() {
        var fixture = TargetE2eFinalizationFixture.valid();

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
                .isEqualTo("urn:target-e2e:proposal:intake:" + fixture.proposal().sha256())
                .isNotEqualTo(fixture.proposal().uri());
        assertThat(verified.proposalHash()).hasSize(64);
        assertThat(verified.executionProvider()).isEqualTo("target-e2e-provider");
        assertThat(verified.executionModel()).isEqualTo("target-e2e-model-1");
    }

    @Test
    void rejectsMissingBlankAndOversizedExecutionIdentity() {
        var fixture = TargetE2eFinalizationFixture.valid();
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
        var fixture = TargetE2eFinalizationFixture.valid();
        ObjectNode proposalSource = fixture.evidence().proposalSource().deepCopy();
        ((ObjectNode) proposalSource.required("proposal"))
                .put("payload_ref", fixture.proposal().uri());
        var evidence = new TargetE2eFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                proposalSource,
                fixture.evidence().isolatedDomainDbBinding());

        assertThatThrownBy(() -> verifier()
                        .verify(fixture.request(), fixture.result(), fixture.state(), evidence))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("payload_ref");
    }

    @Test
    void rejectsProposalSourceUrnForADifferentPayloadHash() {
        var fixture = TargetE2eFinalizationFixture.valid();
        ObjectNode proposalSource = fixture.evidence().proposalSource().deepCopy();
        ((ObjectNode) proposalSource.required("proposal"))
                .put("payload_ref", "urn:target-e2e:proposal:intake:" + "f".repeat(64));
        var evidence = new TargetE2eFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                proposalSource,
                fixture.evidence().isolatedDomainDbBinding());

        assertThatThrownBy(() -> verifier()
                        .verify(fixture.request(), fixture.result(), fixture.state(), evidence))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("payload_ref");
    }

    @Test
    void rejectsProposalIdThatCopiesTheArtifactId() {
        var fixture = TargetE2eFinalizationFixture.valid();
        ObjectNode proposalSource = fixture.evidence().proposalSource().deepCopy();
        ((ObjectNode) proposalSource.required("proposal"))
                .put("proposal_id", fixture.proposal().artifactId());
        var evidence = new TargetE2eFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                proposalSource,
                fixture.evidence().isolatedDomainDbBinding());

        assertThatThrownBy(() -> verifier()
                        .verify(fixture.request(), fixture.result(), fixture.state(), evidence))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("proposal_id");
    }

    @Test
    void rejectsArtifactIdThatIsNotDerivedFromThePayloadHash() {
        var fixture = TargetE2eFinalizationFixture.valid();
        var invalidPointer = new ArtifactPointer(
                "intake.proposal." + "f".repeat(32),
                fixture.proposal().schemaVersion(),
                fixture.proposal().uri(),
                fixture.proposal().sha256());
        ExecuteAgentRunResult invalidResult = withProposal(fixture.result(), invalidPointer);

        assertThatThrownBy(() -> verifier()
                        .verify(fixture.request(), invalidResult, fixture.state(), fixture.evidence()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("artifact_id");
    }

    @Test
    void acceptsRfc8785EquivalentIntegerAndLongNodesInGraphEnvelopes() throws Exception {
        var fixture = TargetE2eFinalizationFixture.valid();
        var mapper = JsonMapper.builder().findAndAddModules().build();
        ObjectNode commandEnvelope = (ObjectNode) mapper.readTree(
                ContractJson.canonicalString(fixture.evidence().commandEnvelope()));
        ObjectNode resultEnvelope = (ObjectNode) mapper.readTree(
                ContractJson.canonicalString(fixture.evidence().resultEnvelope()));
        var evidence = new TargetE2eFinalizationEvidence(
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

    private static TargetE2eFinalizationBindingVerifier verifier() {
        return new TargetE2eFinalizationBindingVerifier(
                JsonMapper.builder().findAndAddModules().build());
    }

    private static void assertExecutionEvidenceRejected(
            TargetE2eFinalizationFixture.Fixture fixture,
            ObjectNode resultEnvelope,
            String message) {
        assertExecutionEvidenceRejected(resultEnvelope, fixture, message);
    }

    private static void assertExecutionEvidenceRejected(
            ObjectNode resultEnvelope,
            TargetE2eFinalizationFixture.Fixture fixture,
            String message) {
        var evidence = new TargetE2eFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                resultEnvelope,
                fixture.evidence().proposalSource(),
                fixture.evidence().isolatedDomainDbBinding());
        assertThatThrownBy(() -> verifier()
                        .verify(fixture.request(), fixture.result(), fixture.state(), evidence))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
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
