package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetE2eFinalizationBindingVerifierTest {

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
