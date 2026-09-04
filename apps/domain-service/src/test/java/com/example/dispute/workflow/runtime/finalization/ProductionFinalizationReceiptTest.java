package com.example.dispute.workflow.runtime.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceipt.CommitFacts;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceiptLedger.AppendCommand;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceiptLedger.StoredReceipt;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProductionFinalizationReceiptTest {

    @Test
    void receiptUsesExactSnakeCaseContractAndRoundTripsOriginalCanonicalBytes() {
        var fixture = ProductionFinalizationFixture.valid();
        var verified = fixture.authorizationRequest();
        var evidence = new ProductionFinalizationBindingVerifier(mapper())
                .verify(fixture.request(), fixture.result(), fixture.state(), fixture.evidence());
        ProductionFinalizationReceipt receipt = receipt(fixture, evidence, "2".repeat(64));

        byte[] bytes = ProductionFinalizationReceiptCodec.canonicalBytes(receipt);
        var tree = ProductionFinalizationReceiptCodec.toTree(receipt);
        var decoded = ProductionFinalizationReceiptCodec.decodeCanonical(bytes);

        assertThat(decoded).isEqualTo(receipt);
        assertThat(tree.has("room_fencing_token")).isTrue();
        assertThat(tree.has("fencing_token")).isFalse();
        assertThat(tree.required("domain_commit_status").textValue()).isEqualTo("COMMITTED");
        assertThat(tree.required("formal_writer").textValue()).isEqualTo("JAVA_FINALIZER_ONLY");
        assertThat(receipt.commandHash()).isEqualTo(verified.commandHash());

        byte[] nonCanonical = (" \n" + new String(bytes, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ProductionFinalizationReceiptCodec.decodeCanonical(nonCanonical))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("canonical");
    }

    @Test
    void manifestDbAndProposalHashesUseTheirFrozenPreimages() {
        var fixture = ProductionFinalizationFixture.valid();
        var source = ProductionFinalizationFixture.authorizedSource(fixture);
        var authorized = source.resolve(fixture.request(), fixture.result());
        var facts = new ProductionAgentRunV2FinalizationFactsProvider(source)
                .create(authorized, fixture.request(), fixture.result());
        var manifest = new AgentRunV2ManifestFactory(mapper())
                .create(fixture.request(), fixture.result(), facts);

        assertThat(ProductionFinalizationReceiptCodec.requireManifestHash(
                        manifest.manifest(), manifest.manifestHash()))
                .isEqualTo(manifest.manifestHash());
        assertThatThrownBy(() -> ProductionFinalizationReceiptCodec.requireManifestHash(
                        manifest.manifest(), "f".repeat(64)))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("full validated manifest");

        ObjectNode wrongProposal = fixture.evidence().proposalSource().deepCopy();
        ((ObjectNode) wrongProposal.required("proposal"))
                .put("payload_hash", "f".repeat(64));
        var invalidProposalEvidence = new ProductionFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                wrongProposal,
                fixture.evidence().isolatedDomainDbBinding());
        assertThatThrownBy(() -> new ProductionFinalizationBindingVerifier(mapper())
                        .verify(
                                fixture.request(), fixture.result(), fixture.state(),
                                invalidProposalEvidence))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("payload_hash");

        ObjectNode wrongDb = fixture.evidence().isolatedDomainDbBinding().deepCopy();
        wrongDb.put("binding_hash", "f".repeat(64));
        var invalidDbEvidence = new ProductionFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                fixture.evidence().proposalSource(),
                wrongDb);
        assertThatThrownBy(() -> new ProductionFinalizationBindingVerifier(mapper())
                        .verify(
                                fixture.request(), fixture.result(), fixture.state(),
                                invalidDbEvidence))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("binding_hash");

        ObjectNode wrongAuthority = fixture.evidence().proposalSource().deepCopy();
        ((ObjectNode) wrongAuthority.required("proposal"))
                .put("formal_authority", "false");
        var invalidAuthorityEvidence = new ProductionFinalizationEvidence(
                fixture.evidence().activationManifestHash(),
                fixture.evidence().commandEnvelope(),
                fixture.evidence().resultEnvelope(),
                wrongAuthority,
                fixture.evidence().isolatedDomainDbBinding());
        assertThatThrownBy(() -> new ProductionFinalizationBindingVerifier(mapper())
                        .verify(
                                fixture.request(), fixture.result(), fixture.state(),
                                invalidAuthorityEvidence))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("formal authority");
    }

    @Test
    void sameIdentityReplayReturnsOriginalBytesAndDifferentHashConflicts() {
        var fixture = ProductionFinalizationFixture.valid();
        var evidence = new ProductionFinalizationBindingVerifier(mapper())
                .verify(fixture.request(), fixture.result(), fixture.state(), fixture.evidence());
        ProductionFinalizationReceipt original = receipt(fixture, evidence, "2".repeat(64));
        byte[] bytes = ProductionFinalizationReceiptCodec.canonicalBytes(original);
        var stored = new StoredReceipt(
                "p9fin.v1." + "3".repeat(32),
                fixture.evidence().activationManifestHash(),
                original,
                bytes);
        var same = new AppendCommand(fixture.evidence().activationManifestHash(), original);

        ProductionFinalizationReceiptLedger.requireExact(stored, same);
        assertThat(stored.canonicalBytes()).containsExactly(bytes);
        assertThat(stored.receipt().domainCommitStatus())
                .isEqualTo(ProductionFinalizationReceipt.DomainCommitStatus.COMMITTED);

        var conflicting = receipt(fixture, evidence, "4".repeat(64));
        assertThatThrownBy(() -> ProductionFinalizationReceiptLedger.requireExact(
                        stored,
                        new AppendCommand(
                                fixture.evidence().activationManifestHash(), conflicting)))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("different receipt evidence");
    }

    private static ProductionFinalizationReceipt receipt(
            ProductionFinalizationFixture.Fixture fixture,
            ProductionFinalizationBindingVerifier.VerifiedEvidence evidence,
            String manifestHash) {
        return ProductionFinalizationReceipt.committed(new CommitFacts(
                ProductionFinalizationFixture.ACTIVATION_ID,
                ProductionFinalizationFixture.TENANT,
                ProductionFinalizationFixture.CASE_ID,
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
                "agent-manifest-production-runtime",
                manifestHash,
                evidence.isolatedDomainDbBindingHash(),
                ProductionFinalizationFixture.NOW));
    }

    private static JsonMapper mapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
