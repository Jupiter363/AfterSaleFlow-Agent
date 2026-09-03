package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressKind;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameManifest;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameRetryAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.SlotState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.StagingConflictException;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
    void bindsReplacementGenerationCreationToTheResetEventClock() throws Exception {
        String source = normalizedSource();
        int retryInsert = source.indexOf("private static final string insert_retry_generation_sql");
        int nextStatement = source.indexOf("private static final string insert_slot_sql", retryInsert);
        int admitRetry = source.indexOf("public frameretryreceipt admitretry", nextStatement);
        int retryUpdate = source.indexOf("jdbc.update(insert_retry_generation_sql", admitRetry);
        int startFrame = source.indexOf("private void startframe", retryUpdate);

        assertThat(retryInsert).isGreaterThanOrEqualTo(0);
        assertThat(nextStatement).isGreaterThan(retryInsert);
        assertThat(source.substring(retryInsert, nextStatement))
                .contains("created_at, updated_at")
                .contains(":admittedat, :admittedat");
        assertThat(admitRetry).isGreaterThan(nextStatement);
        assertThat(retryUpdate).isGreaterThan(admitRetry);
        assertThat(source.substring(admitRetry, retryUpdate))
                .contains("timestamp.from(admission.admittedat())");
        assertThat(startFrame).isGreaterThan(retryUpdate);
        assertThat(source.substring(startFrame))
                .contains("started_at = :occurredat")
                .contains("updated_at = greatest(updated_at, :occurredat)");
        assertThat(source)
                .contains("public_event.created_at as public_event_occurred_at")
                .contains("command.completedat().equals(instant(ingress, \"public_event_occurred_at\"))")
                .contains("command.completedat().equals(instant(result, \"sealed_at\"))");
    }

    @Test
    void rejectsRetryAndIngressReplayWhenTheSourceEventTimeDrifts() {
        Instant admittedAt = Instant.parse("2026-08-24T01:00:00.123456Z");
        FrameRetryAdmission retry = new FrameRetryAdmission(
                "FRAME_SET_1",
                new FrameManifest(
                        FrameType.DIALOGUE_FRAME,
                        2,
                        "FRAME_DIALOGUE_2",
                        FrameType.DIALOGUE_FRAME.promptProfileId(),
                        FrameType.DIALOGUE_FRAME.outputSchemaId(),
                        "qwen3.7-max-no-thinking-strict",
                        "a".repeat(64),
                        "b".repeat(64)),
                1,
                SlotState.FAILED,
                "OUTPUT_SCHEMA_INVALID",
                "$",
                admittedAt);
        Map<String, Object> storedRetry = Map.of(
                "generation_created_at", Timestamp.from(admittedAt));
        assertThatCode(() -> JdbcIntakeParallelFrameStagingStore
                        .requireRetryAdmissionTime(retry, storedRetry))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> JdbcIntakeParallelFrameStagingStore
                        .requireRetryAdmissionTime(
                                retry,
                                Map.of(
                                        "generation_created_at",
                                        Timestamp.from(admittedAt.plusNanos(1_000)))))
                .isInstanceOfSatisfying(
                        StagingConflictException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("INTAKE_PARALLEL_RETRY_REPLAY_CONFLICT"));

        MapSqlParameterSource expected = new MapSqlParameterSource()
                .addValue("frameSetId", "FRAME_SET_1")
                .addValue("runId", "RUN_1")
                .addValue("attemptId", "ATTEMPT_1")
                .addValue("frameType", "DIALOGUE_FRAME")
                .addValue("frameGeneration", 2L)
                .addValue("ingressIdentity", "reset:dialogue:2")
                .addValue("streamSessionId", "STREAM_1")
                .addValue("transportSequence", 3L)
                .addValue("eventKind", "frame_generation_reset")
                .addValue("localIndex", null)
                .addValue("canonicalPayloadSha256", "c".repeat(64))
                .addValue("occurredAt", Timestamp.from(admittedAt));
        Map<String, Object> storedIngress = new HashMap<>();
        storedIngress.put("frame_set_id", "FRAME_SET_1");
        storedIngress.put("agent_run_id", "RUN_1");
        storedIngress.put("agent_run_attempt_id", "ATTEMPT_1");
        storedIngress.put("frame_type", "DIALOGUE_FRAME");
        storedIngress.put("frame_generation", 2L);
        storedIngress.put("ingress_identity", "reset:dialogue:2");
        storedIngress.put("stream_session_id", "STREAM_1");
        storedIngress.put("transport_sequence", 3L);
        storedIngress.put("event_kind", "frame_generation_reset");
        storedIngress.put("local_index", null);
        storedIngress.put("canonical_payload_sha256", "c".repeat(64));
        storedIngress.put("public_event_occurred_at", Timestamp.from(admittedAt));
        assertThatCode(() -> JdbcIntakeParallelFrameStagingStore
                        .requireExactIngressReplay(expected, storedIngress))
                .doesNotThrowAnyException();
        storedIngress.put(
                "public_event_occurred_at", Timestamp.from(admittedAt.plusNanos(1_000)));
        assertThatThrownBy(() -> JdbcIntakeParallelFrameStagingStore
                        .requireExactIngressReplay(expected, storedIngress))
                .isInstanceOfSatisfying(
                        StagingConflictException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("INTAKE_PARALLEL_INGRESS_REPLAY_CONFLICT"));
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
    void publishesVersionedAdmissionReceiptAuthorityBeforeHttpExecution()
            throws Exception {
        String source = normalizedSource();

        assertThat(source)
                .contains("public publishedadmissionreceipt publishadmissionreceipt")
                .contains("decodeandvalidateadmissionreceipt(publication)")
                .contains("lock_admission_receipt_authority_sql")
                .contains("insert into intake_parallel_admission_receipt_history")
                .contains("update intake_parallel_admission_receipt_authority")
                .contains("current_receipt_generation = :expectedreceiptgeneration")
                .contains("public optional<publishedadmissionreceipt> findcurrentadmissionreceipt")
                .contains("load_current_admission_receipt_sql")
                .doesNotContain("selectedgenerations")
                .doesNotContain("public framesetfailurereceipt failuncommitted");
    }

    @Test
    void appliesOneGraphAbandonmentOnlyToExactCurrentStartedLanes()
            throws Exception {
        String source = normalizedSource();
        int apply = source.indexOf(
                "public abandonmentreceipt applyabandonment(abandonmentapplication application)");
        int frameLock = source.indexOf("lock_execution_plan_sql", apply);
        int admissionLoad = source.indexOf("load_admission_receipt_by_hash_sql", frameLock);
        int currentAdmission = source.indexOf(
                "lock_admission_receipt_authority_sql", admissionLoad);
        int insert = source.indexOf("insert_frame_abandonment_sql", currentAdmission);
        int generationCas = source.indexOf("mark_generation_ambiguous_sql", insert);
        int slotCas = source.indexOf("mark_slot_ambiguous_sql", generationCas);

        assertThat(apply).isGreaterThanOrEqualTo(0);
        assertThat(frameLock).isGreaterThan(apply);
        assertThat(admissionLoad).isGreaterThan(frameLock);
        assertThat(currentAdmission).isGreaterThan(admissionLoad);
        assertThat(insert).isGreaterThan(currentAdmission);
        assertThat(generationCas).isGreaterThan(insert);
        assertThat(slotCas).isGreaterThan(generationCas);
        assertThat(source)
                .contains("if (state != slotstate.started) { continue;")
                .contains("parallel abandonment requires at least one current started lane")
                .contains("provider_call_lease_state = 'ambiguous'")
                .contains("failure_code = 'call_state_ambiguous'")
                .contains("slot_state = 'ambiguous'")
                .contains("requirestoredabandonment(application, existing.getfirst())")
                .contains("contractjson.canonicalize(graphreceipt.get(\"admission_receipt\"))");
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

    @Test
    void selectsFrameTypeIntoTheLockedAuthorityUsedByUsageIngress() throws Exception {
        String source = normalizedSource();
        int lockFrame = source.indexOf("private static final string lock_frame_sql");
        int nextQuery = source.indexOf(
                "private static final string lock_execution_plan_sql", lockFrame);

        assertThat(lockFrame).isGreaterThanOrEqualTo(0);
        assertThat(nextQuery).isGreaterThan(lockFrame);
        assertThat(source.substring(lockFrame, nextQuery))
                .contains("slot.frame_type, slot.current_generation as current_frame_generation");
        assertThat(source)
                .contains("text(row, \"frame_type\").equals(payload.frametype().name())");
    }

    private static String normalizedSource() throws Exception {
        return Files.readString(SOURCE)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
