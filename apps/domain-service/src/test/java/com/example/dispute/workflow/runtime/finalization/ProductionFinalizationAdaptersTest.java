package com.example.dispute.workflow.runtime.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeProposalStore.ProposalMetadata;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeProposalStore.StoredProposal;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProductionFinalizationAdaptersTest {

    @Test
    void factsAndRequestResolverBindExactAuthorizedStateAndReplayDeterministically() {
        var fixture = ProductionFinalizationFixture.valid();
        AtomicInteger authorizations = new AtomicInteger();
        var source = new ProductionAuthorizedIntakeFinalizationSource(
                (request, result) -> Optional.of(fixture.state()),
                request -> {
                    authorizations.incrementAndGet();
                    assertThat(request.workflowBuildId()).isEqualTo(ProductionFinalizationFixture.BUILD_ID);
                    return ProductionFinalizationFixture.activeDecision(fixture);
                },
                () -> fixture.runtime(),
                new ProductionExecutionLaneVerifier(Clock.fixed(
                        ProductionFinalizationFixture.NOW, ZoneOffset.UTC)),
                (request, result, runtime, state) -> fixture.evidence(),
                new ProductionFinalizationBindingVerifier(
                        JsonMapper.builder().findAndAddModules().build()));
        var factsProvider = new ProductionAgentRunV2FinalizationFactsProvider(source);
        var mapper = JsonMapper.builder().findAndAddModules().build();

        var firstFacts = factsProvider.resolve(fixture.request(), fixture.result());
        var replayFacts = factsProvider.resolve(fixture.request(), fixture.result());
        assertThat(replayFacts).isEqualTo(firstFacts);
        assertThat(firstFacts.workflowId()).isEqualTo(fixture.runtime().workflowId());
        assertThat(firstFacts.workflowRunId()).isEqualTo(fixture.runtime().workflowRunId());
        assertThat(firstFacts.workflowBuildId()).isEqualTo(fixture.runtime().workflowBuildId());
        assertThat(firstFacts.provider()).isEqualTo("production-runtime-provider");
        assertThat(firstFacts.model()).isEqualTo("production-runtime-model-1");
        assertThat(firstFacts.output().sha256()).isEqualTo(fixture.result().resultHash());
        assertThat(firstFacts.additionalInputs()).containsExactly(fixture.proposal());

        var manifest = new AgentRunV2ManifestFactory(mapper)
                .create(fixture.request(), fixture.result(), firstFacts)
                .manifest();
        IntakeProposalReference proposalReference = new IntakeProposalReference(
                fixture.proposal().artifactId(),
                fixture.proposal().schemaVersion(),
                fixture.proposal().uri(),
                fixture.proposal().sha256(),
                fixture.proposal().sha256(),
                1024);
        var resolver = new ProductionIntakeFinalizationRequestResolver(
                source, pointer -> proposalReference);
        var firstRequest = resolver.resolve(new CommitCommand(
                fixture.request(), fixture.result(), manifest));
        var replayRequest = resolver.resolve(new CommitCommand(
                fixture.request(), fixture.result(), manifest));

        assertThat(replayRequest).isEqualTo(firstRequest);
        assertThat(firstRequest.authority().fencingToken())
                .isEqualTo(fixture.state().run().fencingToken());
        assertThat(firstRequest.authority().checkpointId())
                .isEqualTo(fixture.result().graphResult().checkpointId());
        assertThat(firstRequest.authority().resultHash()).isEqualTo(fixture.result().resultHash());
        assertThat(firstRequest.proposalReference()).isEqualTo(proposalReference);
        assertThat(firstRequest.authority().executionOutputSchemaVersion())
                .isEqualTo("production-runtime-room-proposal-source.v1");
        assertThat(firstRequest.authority().profileVersions().outputSchemaVersion())
                .isEqualTo("intake-turn-proposal.v2");
        firstRequest.requireCanonicalRequestHash();
        assertThat(authorizations).hasValue(4);
    }

    @Test
    void proposalReaderAcceptsCanonicalSelfHashedProposalInsteadOfRawPayloadHash() {
        byte[] payload = canonicalSelfHashedProposal();
        String hash = proposalHash(payload);
        var pointer = pointer(hash);
        var store = new StubStore(pointer, payload);
        var reader = new ProductionIntakeProposalReader(store);

        IntakeProposalReference reference = reader.resolve(pointer);
        var loaded = reader.load(reference);

        assertThat(reference.objectVersion()).isEqualTo(hash);
        assertThat(sha256(payload)).isNotEqualTo(hash);
        assertThat(loaded.payload()).containsExactly(payload);
    }

    @Test
    void proposalReaderRejectsTamperedCanonicalPayloadWithUnchangedSelfHash() {
        byte[] payload = canonicalSelfHashedProposal();
        String hash = proposalHash(payload);
        var store = new StubStore(pointer(hash), payload);
        var reader = new ProductionIntakeProposalReader(store);
        IntakeProposalReference reference = reader.resolve(pointer(hash));

        store.payload = new String(payload, StandardCharsets.UTF_8)
                .replace("intake-turn-proposal.v2", "intake-turn-proposal.v3")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.load(reference))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .extracting(failure -> ((IntakeFinalizationRejectedException) failure).code())
                .isEqualTo("INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH");
    }

    @Test
    void proposalReaderRejectsNonCanonicalOrDuplicateMemberPayloads() {
        byte[] canonical = canonicalSelfHashedProposal();
        String hash = proposalHash(canonical);
        byte[] noncanonical = ("{\"schema_version\":\"intake-turn-proposal.v2\",\"proposal_hash\":\""
                        + hash
                        + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        var noncanonicalStore = new StubStore(pointer(hash), noncanonical);
        var noncanonicalReader = new ProductionIntakeProposalReader(noncanonicalStore);
        IntakeProposalReference noncanonicalReference = noncanonicalReader.resolve(pointer(hash));
        assertThatThrownBy(() -> noncanonicalReader.load(noncanonicalReference))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .extracting(failure -> ((IntakeFinalizationRejectedException) failure).code())
                .isEqualTo("INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH");

        String duplicate = new String(canonical, StandardCharsets.UTF_8)
                .replace("\"schema_version\"", "\"proposal_hash\":\"" + hash + "\",\"schema_version\"");
        var duplicateStore = new StubStore(pointer(hash), duplicate.getBytes(StandardCharsets.UTF_8));
        var duplicateReader = new ProductionIntakeProposalReader(duplicateStore);
        IntakeProposalReference duplicateReference = duplicateReader.resolve(pointer(hash));
        assertThatThrownBy(() -> duplicateReader.load(duplicateReference))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .extracting(failure -> ((IntakeFinalizationRejectedException) failure).code())
                .isEqualTo("INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH");
    }

    @Test
    void proposalReaderRejectsEmbeddedHashThatDiffersFromReference() {
        byte[] payload = canonicalSelfHashedProposal();
        String hash = proposalHash(payload);
        var store = new StubStore(pointer(hash), payload);
        var reader = new ProductionIntakeProposalReader(store);
        IntakeProposalReference reference = reader.resolve(pointer(hash));

        store.payload = new String(payload, StandardCharsets.UTF_8)
                .replace(hash, "0".repeat(64))
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.load(reference))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .extracting(failure -> ((IntakeFinalizationRejectedException) failure).code())
                .isEqualTo("INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH");
    }

    private static ArtifactPointer pointer(String hash) {
        return new ArtifactPointer(
                "PROPOSAL_EXACT",
                "intake-turn-proposal.v2",
                "minio://production-runtime/intake/intake-turn-proposal.v2/PROPOSAL_EXACT/"
                        + hash
                        + ".json",
                hash);
    }

    private static byte[] canonicalSelfHashedProposal() {
        String preimage = "{\"schema_version\":\"intake-turn-proposal.v2\"}";
        String hash = sha256(preimage.getBytes(StandardCharsets.UTF_8));
        return ("{\"proposal_hash\":\"" + hash
                        + "\",\"schema_version\":\"intake-turn-proposal.v2\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String proposalHash(byte[] payload) {
        String document = new String(payload, StandardCharsets.UTF_8);
        String prefix = "\"proposal_hash\":\"";
        int start = document.indexOf(prefix) + prefix.length();
        return document.substring(start, document.indexOf('"', start));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class StubStore implements ProductionIntakeProposalStore {
        private final ArtifactPointer pointer;
        private byte[] payload;

        private StubStore(ArtifactPointer pointer, byte[] payload) {
            this.pointer = pointer;
            this.payload = payload;
        }

        @Override
        public ProposalMetadata resolve(ArtifactPointer ignored) {
            return new ProposalMetadata(
                    pointer.artifactId(),
                    pointer.schemaVersion(),
                    pointer.uri(),
                    pointer.sha256(),
                    pointer.sha256(),
                    payload.length);
        }

        @Override
        public StoredProposal readExact(IntakeProposalReference reference) {
            return new StoredProposal(
                    reference.artifactId(),
                    reference.schemaVersion(),
                    reference.uri(),
                    reference.objectVersion(),
                    reference.sha256(),
                    reference.sizeBytes(),
                    payload);
        }
    }
}
