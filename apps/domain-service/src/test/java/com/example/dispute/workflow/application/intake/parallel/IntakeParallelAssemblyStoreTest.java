package com.example.dispute.workflow.application.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.SealedFrameRecord;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntakeParallelAssemblyStoreTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void immutableArtifactClonesEveryCanonicalByteAuthority() {
        byte[] proposal = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] graph = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] command = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] source = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] result = "{}".getBytes(StandardCharsets.UTF_8);

        ReadyArtifact artifact = artifact(proposal, graph, command, source, result);
        proposal[0] = '[';
        artifact.canonicalGraphResultBytes()[0] = '[';

        assertThat(new String(artifact.canonicalProposalBytes(), StandardCharsets.UTF_8))
                .isEqualTo("{}");
        assertThat(new String(artifact.canonicalGraphResultBytes(), StandardCharsets.UTF_8))
                .isEqualTo("{}");
    }

    @Test
    void rejectsArtifactIdentifiersThatDoNotDeriveFromTheirProtocolHashes() {
        assertThatThrownBy(() -> new ReadyArtifact(
                        HASH_A,
                        "intake.proposal.wrong",
                        "urn:target-e2e:proposal:intake:" + HASH_A,
                        HASH_A,
                        bytes(),
                        "profile.v1",
                        "intake.graph-result." + HASH_B.substring(0, 32),
                        "urn:target-e2e:result:intake:" + HASH_B,
                        HASH_B,
                        bytes(),
                        bytes(),
                        HASH_A,
                        bytes(),
                        HASH_B,
                        bytes(),
                        HASH_A,
                        "checkpoint.v1",
                        HASH_B,
                        "tools.none.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content addressed");
    }

    @Test
    void publishReadyRejectsMissingSelectedFrameProofs() {
        Map<FrameType, IntakeParallelAssemblyStore.SelectedFrameProof> proofs =
                new EnumMap<>(FrameType.class);
        proofs.put(
                FrameType.DIALOGUE_FRAME,
                IntakeParallelAssemblyStore.SelectedFrameProof.from(
                        frame(FrameType.DIALOGUE_FRAME)));
        assertThatThrownBy(() -> new IntakeParallelAssemblyStore.PublishReady(
                        new IntakeParallelAssemblyStore.AssemblyLookup(
                                "FRAME_SET_1", "RUN_1", "ATTEMPT_1", "COMMAND_1", HASH_A),
                        0,
                        proofs,
                        artifact(bytes(), bytes(), bytes(), bytes(), bytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact three");
    }

    private static ReadyArtifact artifact(
            byte[] proposal, byte[] graph, byte[] command, byte[] source, byte[] result) {
        return new ReadyArtifact(
                HASH_A,
                "intake.proposal." + HASH_A.substring(0, 32),
                "urn:target-e2e:proposal:intake:" + HASH_A,
                HASH_A,
                proposal,
                "profile.v1",
                "intake.graph-result." + HASH_B.substring(0, 32),
                "urn:target-e2e:result:intake:" + HASH_B,
                HASH_B,
                graph,
                command,
                HASH_A,
                source,
                HASH_B,
                result,
                HASH_A,
                "checkpoint.v1",
                HASH_B,
                "tools.none.v1");
    }

    private static SealedFrameRecord frame(FrameType type) {
        return new SealedFrameRecord(
                type,
                1,
                "FRAME_" + type.name(),
                "RESULT_" + type.name(),
                "{}",
                HASH_A,
                HASH_B,
                0,
                1,
                2,
                3,
                4,
                1);
    }

    private static byte[] bytes() {
        return "{}".getBytes(StandardCharsets.UTF_8);
    }
}
