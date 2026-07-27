package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceipt.CommitFacts;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.AppendCommand;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.StoredReceipt;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TargetE2eFinalizationReceiptTest {

    @Test
    void receiptUsesExactSnakeCaseContractAndRoundTripsOriginalCanonicalBytes() {
        var fixture = TargetE2eFinalizationFixture.valid();
        var verified = fixture.authorizationRequest();
        var evidence = new TargetE2eFinalizationBindingVerifier(mapper())
                .verify(fixture.request(), fixture.result(), fixture.state(), fixture.evidence());
        TargetE2eFinalizationReceipt receipt = receipt(fixture, evidence, "2".repeat(64));

        byte[] bytes = TargetE2eFinalizationReceiptCodec.canonicalBytes(receipt);
        var tree = TargetE2eFinalizationReceiptCodec.toTree(receipt);
        var decoded = TargetE2eFinalizationReceiptCodec.decodeCanonical(bytes);

        assertThat(decoded).isEqualTo(receipt);
        assertThat(tree.has("room_fencing_token")).isTrue();
        assertThat(tree.has("fencing_token")).isFalse();
        assertThat(tree.required("domain_commit_status").textValue()).isEqualTo("COMMITTED");
        assertThat(tree.required("formal_writer").textValue()).isEqualTo("JAVA_FINALIZER_ONLY");
        assertThat(receipt.commandHash()).isEqualTo(verified.commandHash());

        byte[] nonCanonical = (" \n" + new String(bytes, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> TargetE2eFinalizationReceiptCodec.decodeCanonical(nonCanonical))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("canonical");
    }

    @Test
    void manifestDbAndProposalHashesUseTheirFrozenPreimages() {
        var fixture = TargetE2eFinalizationFixture.valid();
        var source = TargetE2eFinalizationFixture.authorizedSource(fixture);
        var authorized = source.resolve(fixture.request(), fixture.result());
        var facts = new TargetE2eAgentRunV2FinalizationFactsProvider(source)
                .create(authorized, fixture.request(), fixture.result());
        var manifest = new AgentRunV2ManifestFactory(mapper())
                .create(fixture.request(), fixture.result(), facts);

        assertThat(TargetE2eFinalizationReceiptCodec.requireManifestHash(
                        manifest.manifest(), manifest.manifestHash()))
                .isEqualTo(manifest.manifestHash());
        assertThatThrownBy(() -> TargetE2eFinalizationReceiptCodec.requireManifestHash(
                        manifest.manifest(), "f".repeat(64)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("full validated manifest");

        ObjectNode wrongProposal = fixture.evidence().proposalSource().deepCopy();
        ((ObjectNode) wrongProposal.required("proposal"))
                .put("payload_hash", "f".repeat(64));
        var invalidProposalEvidence = new TargetE2eFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                wrongProposal,
                fixture.evidence().isolatedDomainDbBinding());
        assertThatThrownBy(() -> new TargetE2eFinalizationBindingVerifier(mapper())
                        .verify(
                                fixture.request(), fixture.result(), fixture.state(),
                                invalidProposalEvidence))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("payload_hash");

        ObjectNode wrongDb = fixture.evidence().isolatedDomainDbBinding().deepCopy();
        wrongDb.put("binding_hash", "f".repeat(64));
        var invalidDbEvidence = new TargetE2eFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                fixture.evidence().proposalSource(),
                wrongDb);
        assertThatThrownBy(() -> new TargetE2eFinalizationBindingVerifier(mapper())
                        .verify(
                                fixture.request(), fixture.result(), fixture.state(),
                                invalidDbEvidence))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("binding_hash");

        ObjectNode wrongAuthority = fixture.evidence().proposalSource().deepCopy();
        ((ObjectNode) wrongAuthority.required("proposal"))
                .put("formal_authority", "false");
        var invalidAuthorityEvidence = new TargetE2eFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                wrongAuthority,
                fixture.evidence().isolatedDomainDbBinding());
        assertThatThrownBy(() -> new TargetE2eFinalizationBindingVerifier(mapper())
                        .verify(
                                fixture.request(), fixture.result(), fixture.state(),
                                invalidAuthorityEvidence))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("formal authority");
    }

    @Test
    void sameIdentityReplayReturnsOriginalBytesAndDifferentHashConflicts() {
        var fixture = TargetE2eFinalizationFixture.valid();
        var evidence = new TargetE2eFinalizationBindingVerifier(mapper())
                .verify(fixture.request(), fixture.result(), fixture.state(), fixture.evidence());
        TargetE2eFinalizationReceipt original = receipt(fixture, evidence, "2".repeat(64));
        byte[] bytes = TargetE2eFinalizationReceiptCodec.canonicalBytes(original);
        var stored = new StoredReceipt(
                "p9fin.v1." + "3".repeat(32),
                fixture.evidence().activationManifestHash(),
                original,
                bytes);
        var same = new AppendCommand(fixture.evidence().activationManifestHash(), original);

        TargetE2eFinalizationReceiptLedger.requireExact(stored, same);
        assertThat(stored.canonicalBytes()).containsExactly(bytes);
        assertThat(stored.receipt().domainCommitStatus())
                .isEqualTo(TargetE2eFinalizationReceipt.DomainCommitStatus.COMMITTED);

        var conflicting = receipt(fixture, evidence, "4".repeat(64));
        assertThatThrownBy(() -> TargetE2eFinalizationReceiptLedger.requireExact(
                        stored,
                        new AppendCommand(
                                fixture.evidence().activationManifestHash(), conflicting)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("different receipt evidence");
    }

    private static TargetE2eFinalizationReceipt receipt(
            TargetE2eFinalizationFixture.Fixture fixture,
            TargetE2eFinalizationBindingVerifier.VerifiedEvidence evidence,
            String manifestHash) {
        return TargetE2eFinalizationReceipt.committed(new CommitFacts(
                TargetE2eFinalizationFixture.ACTIVATION_ID,
                TargetE2eFinalizationFixture.TENANT,
                TargetE2eFinalizationFixture.CASE_ID,
                RoomType.INTAKE,
                fixture.state().run().roomEpoch(),
                fixture.state().run().fencingToken(),
                fixture.state().run().processRevision(),
                fixture.state().projection().lastCommandSequence(),
                fixture.request().logicalRunId(),
                fixture.request().attemptId(),
                evidence.commandHash(),
                evidence.commandEnvelopeHash(),
                fixture.result().graphResult().graphKey(),
                fixture.result().graphResult().graphVersion(),
                fixture.request().command().checkpointSchemaVersion(),
                fixture.result().graphResult().checkpointId(),
                evidence.resultHash(),
                evidence.proposalHash(),
                evidence.resultEnvelopeHash(),
                "agent-manifest-target-e2e",
                manifestHash,
                evidence.isolatedDomainDbBindingHash(),
                TargetE2eFinalizationFixture.NOW));
    }

    private static JsonMapper mapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
