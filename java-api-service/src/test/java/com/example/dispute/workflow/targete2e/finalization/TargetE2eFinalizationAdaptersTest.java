package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeProposalStore.ProposalMetadata;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeProposalStore.StoredProposal;
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

class TargetE2eFinalizationAdaptersTest {

    @Test
    void factsAndRequestResolverBindExactAuthorizedStateAndReplayDeterministically() {
        var fixture = TargetE2eFinalizationFixture.valid();
        AtomicInteger authorizations = new AtomicInteger();
        var source = new TargetE2eAuthorizedIntakeFinalizationSource(
                (request, result) -> Optional.of(fixture.state()),
                request -> {
                    authorizations.incrementAndGet();
                    assertThat(request.workflowBuildId()).isEqualTo(TargetE2eFinalizationFixture.BUILD_ID);
                    return TargetE2eFinalizationFixture.activeDecision(fixture);
                },
                () -> fixture.runtime(),
                new TargetE2eExecutionLaneVerifier(Clock.fixed(
                        TargetE2eFinalizationFixture.NOW, ZoneOffset.UTC)),
                (request, result, runtime, state) -> fixture.evidence(),
                new TargetE2eFinalizationBindingVerifier(
                        JsonMapper.builder().findAndAddModules().build()));
        var factsProvider = new TargetE2eAgentRunV2FinalizationFactsProvider(source);
        var mapper = JsonMapper.builder().findAndAddModules().build();

        var firstFacts = factsProvider.resolve(fixture.request(), fixture.result());
        var replayFacts = factsProvider.resolve(fixture.request(), fixture.result());
        assertThat(replayFacts).isEqualTo(firstFacts);
        assertThat(firstFacts.workflowId()).isEqualTo(fixture.runtime().workflowId());
        assertThat(firstFacts.workflowRunId()).isEqualTo(fixture.runtime().workflowRunId());
        assertThat(firstFacts.workflowBuildId()).isEqualTo(fixture.runtime().workflowBuildId());
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
        var resolver = new TargetE2eIntakeFinalizationRequestResolver(
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
        firstRequest.requireCanonicalRequestHash();
        assertThat(authorizations).hasValue(4);
    }

    @Test
    void proposalReaderRequiresExactMetadataAndContentHash() {
        byte[] payload = "{\"schema_version\":\"intake-turn-proposal.v2\"}"
                .getBytes(StandardCharsets.UTF_8);
        String hash = sha256(payload);
        var pointer = new ArtifactPointer(
                "PROPOSAL_EXACT",
                "intake-turn-proposal.v2",
                "minio://target-e2e/intake/intake-turn-proposal.v2/PROPOSAL_EXACT/"
                        + hash
                        + ".json",
                hash);
        var store = new StubStore(pointer, payload);
        var reader = new TargetE2eIntakeProposalReader(store);

        IntakeProposalReference reference = reader.resolve(pointer);
        var loaded = reader.load(reference);

        assertThat(reference.objectVersion()).isEqualTo(hash);
        assertThat(loaded.payload()).containsExactly(payload);

        store.payload = "changed".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.load(reference))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .hasMessageContaining("immutable reference");
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class StubStore implements TargetE2eIntakeProposalStore {
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
