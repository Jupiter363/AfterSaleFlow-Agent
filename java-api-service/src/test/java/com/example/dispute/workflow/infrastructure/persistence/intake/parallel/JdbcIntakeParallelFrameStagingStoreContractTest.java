package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressKind;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.StagingConflictException;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdbcIntakeParallelFrameStagingStoreContractTest {

    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "example",
            "dispute",
            "workflow",
            "infrastructure",
            "persistence",
            "intake",
            "parallel",
            "JdbcIntakeParallelFrameStagingStore.java");

    @Test
    void keepsFrameIngressAndSealInsideTechnicalTransactionsOnly() throws Exception {
        String source = normalizedSource();

        assertThat(source)
                .contains("implements intakeparallelframestagingport")
                .contains("propagation = propagation.requires_new")
                .contains("for update of frame_set, attempt, slot, generation, authority")
                .contains("eventwriter.appendincurrenttransaction")
                .contains("insert into intake_parallel_frame_ingress")
                .contains("insert into intake_parallel_frame_result")
                .contains("update intake_parallel_frame_generation")
                .contains("update intake_parallel_frame_slot")
                .doesNotContain("insert into room_message")
                .doesNotContain("insert into case_dossier")
                .doesNotContain("update case_process_projection")
                .doesNotContain("update case_command");
    }

    @Test
    void replaysAnExactIngressBeforeRejectingNewWorkAndLetsJavaAllocateSequence()
            throws Exception {
        String source = normalizedSource();
        int replay = source.indexOf("optional<map<string, object>> replay = findingressreplay(identity)");
        int runningGate = source.indexOf("requirerunningcollecting(authority)", replay);
        int priorSequence = source.indexOf("long previoussequence = number(authority, \"last_sequence_no\")", runningGate);
        int javaSequence = source.indexOf("long globalsequence = math.addexact(previoussequence, 1l)", priorSequence);

        assertThat(replay).isGreaterThanOrEqualTo(0);
        assertThat(runningGate).isGreaterThan(replay);
        assertThat(priorSequence).isGreaterThan(runningGate);
        assertThat(javaSequence).isGreaterThan(priorSequence);
        assertThat(source)
                .contains("ingress_identity = :ingressidentity")
                .contains("stream_session_id = :streamsessionid")
                .contains("transport_sequence = :transportsequence")
                .contains("canonical_payload_sha256")
                .contains("highest_contiguous_sequence_no")
                .contains("stream_protocol = 'agent-stream.v4'");
    }

    @Test
    void sealingExactThreeFramesStillDoesNotGrantReadyOrWriteBusinessState()
            throws Exception {
        String source = normalizedSource();

        assertThat(source)
                .contains("boolean exactthreesealed = exactthreesealed(command.framesetid())")
                .contains("exactthreesealed, assemblystate.collecting")
                .doesNotContain("assembly_state = 'ready'")
                .doesNotContain("intaketurnproposal")
                .doesNotContain("insert into intake_parallel_proposal_artifact");
    }

    @Test
    void publishesV4CatchUpOnlyThroughTheAfterCommitStreamBoundary() throws Exception {
        String source = normalizedSource();

        assertThat(source)
                .contains("private final agentrunstreameventservice streameventservice")
                .contains("streameventservice.wakeupaftercommit(runid, attemptid, durablehighwatermark)")
                .contains("schedulestreamcatchup( command.runid(), command.attemptid(), eventreceipt.durablehighwatermark())")
                .doesNotContain("wakeupPublisher.publish")
                .doesNotContain("publish(runid)");
    }

    @Test
    void bindsRetriesAndEveryFrameMutationToCurrentV080Authority() throws Exception {
        String source = normalizedSource();

        assertThat(source)
                .contains("authority.current_binding_id")
                .contains("authority.current_generation as current_binding_generation")
                .contains("slot.current_generation as current_frame_generation")
                .doesNotContain("authority.current_generation, authority.authority_version")
                .contains("authority.authority_version as current_authority_version")
                .contains("requirecurrenteventauthority(row)")
                .contains("repair_code")
                .contains("validation_path")
                .contains("failure_retryable")
                .contains("current_generation = :expectedgeneration")
                .contains("slot_state = :expectedstate")
                .contains("requireretrypredecessor(admission)");
    }

    @Test
    void keepsRoomIdentitySeparateFromRoomEpochIdentityDuringAdmission() throws Exception {
        String source = normalizedSource();

        assertThat(source)
                .contains("run.room_id as run_room_id")
                .contains("admission.roomid().equals(text(row, \"run_room_id\"))")
                .doesNotContain("admission.roomid().equals(text(row, \"room_epoch_id\"))");
    }

    @Test
    void terminalizesOnlyTheExactUncommittedParallelAttemptAndReplaysItsFailure()
            throws Exception {
        String source = normalizedSource();

        assertThat(source)
                .contains("public framesetfailurereceipt failuncommitted")
                .contains("for update of frame_set, attempt")
                .contains("command.commandrequestsha256()")
                .contains("\"agent-stream.v4\".equals(text(row, \"protocol\"))")
                .contains("\"uncommitted\".equals(text(row, \"finalization_status\"))")
                .contains("assembly_state = 'failed_uncommitted'")
                .contains("and assembly_state = 'collecting'")
                .contains("and version = :expectedversion")
                .contains("intake_parallel_failure_replay_conflict");
    }

    @Test
    void bindsUsageToFrameTypeAndGenerationWithoutInventingAFrameId() {
        Map<String, Object> authority = Map.of(
                "current_frame_generation", 1L,
                "frame_type", "QUALITY_FRAME",
                "current_frame_id", "IFR_quality_1");
        AgentStreamEventV4.Payload exactUsage = AgentStreamEventV4.Payload.usagePayload(
                AgentStreamEventV4.FrameType.QUALITY_FRAME,
                1,
                new Usage(10, 5, 15));

        assertThatCode(() -> JdbcIntakeParallelFrameStagingStore.requireCurrentFrameAuthority(
                        authority, 1L, IngressKind.USAGE, exactUsage))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> JdbcIntakeParallelFrameStagingStore.requireCurrentFrameAuthority(
                        authority,
                        1L,
                        IngressKind.USAGE,
                        AgentStreamEventV4.Payload.usagePayload(
                                AgentStreamEventV4.FrameType.DIALOGUE_FRAME,
                                1,
                                new Usage(10, 5, 15))))
                .isInstanceOfSatisfying(
                        StagingConflictException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("INTAKE_PARALLEL_FRAME_USAGE_AUTHORITY_DRIFT"));
    }

    private static String normalizedSource() throws Exception {
        return Files.readString(SOURCE)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
