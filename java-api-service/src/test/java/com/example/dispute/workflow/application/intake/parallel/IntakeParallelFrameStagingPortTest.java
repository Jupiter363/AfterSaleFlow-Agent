package com.example.dispute.workflow.application.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyView;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.EventAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameManifest;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameRetryAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSealCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSealReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSetAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSlotView;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressKind;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.SlotState;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntakeParallelFrameStagingPortTest {

    @Test
    void admitsExactlyThreeGenerationOneManifestsWithOneFrozenModelView() {
        FrameSetAdmission admission = admissionWithDeadline(
                manifests(1), Instant.parse("2026-08-24T01:01:00.123456789Z"));

        assertThat(admission.executionProfileId()).isEqualTo("PARALLEL_FRAMES_V1");
        assertThat(admission.manifestsByType()).containsOnlyKeys(FrameType.values());
        assertThat(admission.manifestsByType().values())
                .extracting(FrameManifest::generation)
                .containsOnly(1L);
        assertThat(admission.turnDeadlineAt())
                .isEqualTo(Instant.parse("2026-08-24T01:01:00.123456Z"));
    }

    @Test
    void rejectsMissingDuplicateAndNonInitialFrameManifests() {
        List<FrameManifest> all = manifests(1);
        assertThatThrownBy(() -> admission(all.subList(0, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly three");

        assertThatThrownBy(() -> admission(List.of(all.get(0), all.get(0), all.get(2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate Frame type");

        assertThatThrownBy(() -> admission(manifests(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation 1");
    }

    @Test
    void frameManifestCannotUseAnotherFramesPromptOrSchema() {
        assertThatThrownBy(() -> new FrameManifest(
                        FrameType.DIALOGUE_FRAME,
                        1,
                        "FRAME_DIALOGUE_1",
                        FrameType.DOSSIER_FRAME.promptProfileId(),
                        FrameType.DIALOGUE_FRAME.outputSchemaId(),
                        "qwen3.7-max-no-thinking-strict",
                        hash('a'),
                        hash('b')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promptProfileId");
    }

    @Test
    void aSealedExactThreeSetStillHasNoFormalReadyAuthority() {
        FrameSealReceipt receipt = new FrameSealReceipt(
                "FRAME_SET_1",
                FrameType.QUALITY_FRAME,
                1,
                "RESULT_QUALITY_1",
                "FRAME_RECEIPT_QUALITY_1",
                true,
                true,
                AssemblyState.COLLECTING,
                8,
                8);

        assertThat(receipt.exactThreeSealed()).isTrue();
        assertThat(receipt.assemblyState()).isEqualTo(AssemblyState.COLLECTING);
        assertThatThrownBy(() -> new FrameSealReceipt(
                        "FRAME_SET_1",
                        FrameType.QUALITY_FRAME,
                        1,
                        "RESULT_QUALITY_1",
                        "FRAME_RECEIPT_QUALITY_1",
                        true,
                        true,
                        AssemblyState.READY,
                        8,
                        8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not itself grant READY");
    }

    @Test
    void v4FrameGenerationAndProjectionWatermarkMustFitThePublicWireContract() {
        assertThatThrownBy(() -> manifest(FrameType.DIALOGUE_FRAME, (long) Integer.MAX_VALUE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent-stream.v4 integer range");

        assertThatThrownBy(() -> new FrameSealCommand(
                        "FRAME_SET_1",
                        "RUN_1",
                        "ATTEMPT_1",
                        "STREAM_1",
                        1,
                        "seal:dialogue:1",
                        Audience.USER,
                        FrameType.DIALOGUE_FRAME,
                        1,
                        "FRAME_DIALOGUE_1",
                        "checkpoint://dialogue/1",
                        hash('a'),
                        hash('b'),
                        hash('c'),
                        "{}",
                        hash('d'),
                        hash('e'),
                        (long) Integer.MAX_VALUE + 1,
                        new IntakeParallelFrameStagingPort.ProviderUsage(1, 1, 2, 1, 1),
                        Instant.parse("2026-08-24T01:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent-stream.v4 integer range");
    }

    @Test
    void retryCanAdvanceOnlyOneFailedOrAmbiguousSlotGeneration() {
        FrameManifest replacement = manifest(FrameType.DIALOGUE_FRAME, 2);
        Instant admittedAt = Instant.parse("2026-08-24T01:00:00.123456789Z");
        FrameRetryAdmission retry = new FrameRetryAdmission(
                "FRAME_SET_1",
                replacement,
                1,
                SlotState.FAILED,
                "OUTPUT_SCHEMA_INVALID",
                "$.dialogue.segments[0]",
                admittedAt);
        assertThat(retry.replacement().generation()).isEqualTo(2);
        assertThat(retry.admittedAt())
                .isEqualTo(Instant.parse("2026-08-24T01:00:00.123456Z"));

        assertThatThrownBy(() -> new FrameRetryAdmission(
                        "FRAME_SET_1",
                        replacement,
                        1,
                        SlotState.SEALED,
                        "OUTPUT_SCHEMA_INVALID",
                        "$.dialogue",
                        admittedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FAILED or AMBIGUOUS");

        assertThatThrownBy(() -> new FrameRetryAdmission(
                        "FRAME_SET_1",
                        manifest(FrameType.DIALOGUE_FRAME, 3),
                        1,
                        SlotState.AMBIGUOUS,
                        "CALL_STATE_AMBIGUOUS",
                        "$.dialogue",
                        admittedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("advance exactly once");
    }

    @Test
    void publicIngressAndSealTimesUsePostgresqlMicrosecondPrecision() {
        Instant sourceTime = Instant.parse("2026-08-24T01:00:00.123456789Z");
        AgentStreamEventV4.Payload start = AgentStreamEventV4.Payload.frameStartPayload(
                "FRAME_DIALOGUE_1",
                AgentStreamEventV4.FrameType.DIALOGUE_FRAME,
                1,
                "FRAME_SET_RECEIPT_1",
                "intake-projection-registry.v1");
        IngressCommand ingress = new IngressCommand(
                "FRAME_SET_1",
                "RUN_1",
                "ATTEMPT_1",
                "STREAM_1",
                0,
                "start:dialogue:1",
                FrameType.DIALOGUE_FRAME,
                1,
                IngressKind.PUBLIC_FRAME_START,
                null,
                Audience.USER,
                start,
                hash('a'),
                sourceTime);
        FrameSealCommand seal = new FrameSealCommand(
                "FRAME_SET_1",
                "RUN_1",
                "ATTEMPT_1",
                "STREAM_1",
                1,
                "seal:dialogue:1",
                Audience.USER,
                FrameType.DIALOGUE_FRAME,
                1,
                "FRAME_DIALOGUE_1",
                "checkpoint://dialogue/1",
                hash('a'),
                hash('b'),
                hash('c'),
                "{}",
                hash('d'),
                hash('e'),
                1,
                new IntakeParallelFrameStagingPort.ProviderUsage(1, 1, 2, 1, 1),
                sourceTime);

        assertThat(ingress.occurredAt())
                .isEqualTo(Instant.parse("2026-08-24T01:00:00.123456Z"));
        assertThat(seal.completedAt())
                .isEqualTo(Instant.parse("2026-08-24T01:00:00.123456Z"));
    }

    @Test
    void collectingAssemblyCannotCarryProposalOrTerminalAuthority() {
        Map<FrameType, FrameSlotView> slots = slots(SlotState.SEALED);
        assertThatThrownBy(() -> new AssemblyView(
                        "FRAME_SET_1",
                        "RUN_1",
                        "ATTEMPT_1",
                        authority(),
                        hash('b'),
                        hash('c'),
                        AssemblyState.COLLECTING,
                        slots,
                        hash('d'),
                        "PROPOSAL_1",
                        hash('e'),
                        hash('f'),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COLLECTING assembly");
    }

    @Test
    void readyAndCommittedAssemblyExposeOneImmutableProposalAuthority() {
        Map<FrameType, FrameSlotView> slots = slots(SlotState.SEALED);
        AssemblyView ready = new AssemblyView(
                "FRAME_SET_1",
                "RUN_1",
                "ATTEMPT_1",
                authority(),
                hash('b'),
                hash('c'),
                AssemblyState.READY,
                slots,
                hash('d'),
                "PROPOSAL_1",
                hash('e'),
                hash('f'),
                null);
        AssemblyView committed = new AssemblyView(
                "FRAME_SET_1",
                "RUN_1",
                "ATTEMPT_1",
                authority(),
                hash('b'),
                hash('c'),
                AssemblyState.COMMITTED,
                slots,
                hash('d'),
                "PROPOSAL_1",
                hash('e'),
                hash('f'),
                "TERMINAL_RECEIPT_1");

        assertThat(ready.terminalReceiptId()).isNull();
        assertThat(committed.terminalReceiptId()).isEqualTo("TERMINAL_RECEIPT_1");
    }

    private static FrameSetAdmission admission(List<FrameManifest> manifests) {
        return admissionWithDeadline(manifests, Instant.parse("2026-08-24T01:01:00Z"));
    }

    private static FrameSetAdmission admissionWithDeadline(
            List<FrameManifest> manifests, Instant deadline) {
        return new FrameSetAdmission(
                "FRAME_SET_1",
                "RUN_1",
                "ATTEMPT_1",
                "COMMAND_1",
                "tenant-local",
                "CASE_1",
                "ROOM_1",
                1,
                9,
                "grt.v1.0123456789abcdef0123456789abcdef",
                hash('9'),
                "AGENT_SESSION_1",
                authority(),
                hash('b'),
                hash('c'),
                "PARALLEL_FRAMES_V1",
                "intake-projection-registry.v1",
                "qwen3.7-max-no-thinking-strict",
                deadline,
                manifests);
    }

    private static EventAuthority authority() {
        return new EventAuthority(
                "EVENT_BINDING_1",
                "THREAD_REGISTRATION_1",
                2,
                1,
                3,
                hash('a'));
    }

    private static List<FrameManifest> manifests(long generation) {
        return List.of(
                manifest(FrameType.DIALOGUE_FRAME, generation),
                manifest(FrameType.DOSSIER_FRAME, generation),
                manifest(FrameType.QUALITY_FRAME, generation));
    }

    private static FrameManifest manifest(FrameType type, long generation) {
        return new FrameManifest(
                type,
                generation,
                "FRAME_" + type + "_" + generation,
                type.promptProfileId(),
                type.outputSchemaId(),
                "qwen3.7-max-no-thinking-strict",
                hash((char) ('a' + type.ordinal())),
                hash((char) ('d' + type.ordinal())));
    }

    private static Map<FrameType, FrameSlotView> slots(SlotState state) {
        Map<FrameType, FrameSlotView> slots = new EnumMap<>(FrameType.class);
        for (FrameType type : FrameType.values()) {
            slots.put(
                    type,
                    new FrameSlotView(
                            type,
                            1,
                            "FRAME_" + type + "_1",
                            state,
                            state == SlotState.SEALED ? "RESULT_" + type + "_1" : null));
        }
        return Map.copyOf(slots);
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
