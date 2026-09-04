package com.example.dispute.workflow.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.projection.intake.IntakeProcessProjectionAdapter;
import com.example.dispute.workflow.projection.intake.IntakeProcessProjectionAdapter.ProjectionRow;
import com.example.dispute.workflow.projection.intake.IntakeProcessProjectionView;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class IntakeProcessProjectionAdapterTest {

    private final IntakeProcessProjectionAdapter adapter =
            new IntakeProcessProjectionAdapter(mock(NamedParameterJdbcOperations.class));

    @Test
    @SuppressWarnings("unchecked")
    void readScopesActiveRunToAuthorizedActorAndExactIntakeTuple() {
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        when(jdbc.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        IntakeProcessProjectionAdapter scopedAdapter =
                new IntakeProcessProjectionAdapter(jdbc);

        scopedAdapter.read(
                "CASE_SAFE", new AuthenticatedActor("user-safe", ActorRole.USER));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains(
                        "epoch.room_type = projection.current_room",
                        "epoch.room_epoch = projection.room_epoch",
                        "epoch.fencing_token = projection.fencing_token",
                        "epoch.lifecycle_status in ('ACTIVE', 'TERMINAL')",
                        "epoch.lifecycle_status = 'ACTIVE'",
                        "run.room_id = epoch.room_id",
                        "run.room_epoch = epoch.room_epoch",
                        "run.fencing_token = epoch.fencing_token",
                        "run.stream_audience_json",
                        "run.stream_audience_actor_ids_json",
                        "command.expected_process_revision = projection.process_revision",
                        "'PENDING_ORCHESTRATION', 'ORCHESTRATION_ACCEPTED'",
                        "command_admission_pending");
        assertThat(parameters.getValue().getValue("actorId")).isEqualTo("user-safe");
        assertThat(parameters.getValue().getValue("actorRole")).isEqualTo("USER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void advancedRoomRequiresTerminalLatestIntakeAndReturnsCompletedCurrentProjection() {
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        ProjectionRow evidenceTuple = new ProjectionRow(
                "TEMPORAL",
                "READY",
                0,
                8,
                2,
                "COMPLETED",
                OffsetDateTime.parse("2026-08-13T22:49:10Z"),
                "TEMPORAL",
                "ACTIVE",
                "READY",
                0L,
                8L,
                0L,
                2L,
                "case-process-contract.v1",
                "room-epoch-selection.v2",
                "agent-stream.v2",
                "case-build-1",
                "evidence-build-1",
                "production-runtime-graph.2026-07-27.1",
                "production-runtime-checkpoint.v1",
                null,
                null,
                null,
                null);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(evidenceTuple));
        IntakeProcessProjectionAdapter scopedAdapter =
                new IntakeProcessProjectionAdapter(jdbc);

        IntakeProcessProjectionView view = scopedAdapter
                .read("CASE_ADVANCED", new AuthenticatedActor("merchant-safe", ActorRole.MERCHANT))
                .orElseThrow();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains(
                        "projection.current_room in ('EVIDENCE', 'HEARING', 'REVIEW')",
                        "completed_intake.lifecycle_status = 'TERMINAL'",
                        "completed_intake.provisioning_status = 'READY'",
                        "completed_intake.temporal_workflow_id =",
                        "projection.temporal_workflow_id",
                        "newer_intake.room_epoch >");
        assertThat(view.projectionState()).isEqualTo("CURRENT");
        assertThat(view.roomPhase()).isEqualTo("COMPLETED");
        assertThat(view.pendingState()).isEqualTo("NONE");
        assertThat(view.processRevision()).isEqualTo(8);
        assertThat(view.roomRevision()).isZero();
        assertThat(view.fencingToken()).isEqualTo(2);
    }

    @Test
    void mapsCurrentShadowTupleToVersionedInformationalProjection() {
        IntakeProcessProjectionView view = adapter.adapt(currentShadowRow());

        assertThat(view.schemaVersion()).isEqualTo("intake-process-projection.v1");
        assertThat(view.projectionState()).isEqualTo("CURRENT");
        assertThat(view.writerMode()).isEqualTo("SHADOW");
        assertThat(view.roomEpoch()).isEqualTo(4);
        assertThat(view.processRevision()).isEqualTo(12);
        assertThat(view.roomRevision()).isEqualTo(7);
        assertThat(view.fencingToken()).isEqualTo(9);
        assertThat(view.roomPhase()).isEqualTo("WAITING_PARTY");
        assertThat(view.pendingState()).isEqualTo("WAITING_PARTY");
        assertThat(view.commandAdmissionState()).isEqualTo("READY");
        assertThat(view.versionPins().processContractVersion())
                .isEqualTo("case-process.v2");
        assertThat(view.versionPins().graphVersion()).isEqualTo("2.0.0");
        assertThat(view.activeLogicalRunId()).isEqualTo("run-1");
        assertThat(view.activeAttemptId()).isEqualTo("attempt-2");
        assertThat(view.streamCursor()).isEqualTo("v2:attempt-2:6");
        assertThat(view.projectedAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-22T03:04:05Z"));
    }

    @Test
    void staleTupleReturnsProcessingWithoutInferringRoomState() {
        ProjectionRow current = currentShadowRow();
        ProjectionRow stale = new ProjectionRow(
                current.writerMode(),
                current.writerActivationStatus(),
                current.projectionRoomEpoch(),
                current.projectionProcessRevision(),
                current.projectionFencingToken(),
                "COMPLETED",
                current.projectedAt(),
                current.epochWriterMode(),
                current.epochLifecycleStatus(),
                current.epochProvisioningStatus(),
                current.epochRoomEpochValue(),
                current.epochProcessRevisionValue() + 1,
                current.roomRevisionValue(),
                current.epochFencingTokenValue(),
                current.processContractVersion(),
                current.selectionSchemaVersion(),
                current.streamProtocol(),
                current.temporalBuildId(),
                current.roomWorkflowBuildId(),
                current.graphVersion(),
                current.checkpointSchemaVersion(),
                current.activeLogicalRunId(),
                current.activeAttemptId(),
                current.activeRunStatus(),
                current.lastSequenceNo());

        IntakeProcessProjectionView view = adapter.adapt(stale);

        assertThat(view.projectionState()).isEqualTo("PROCESSING");
        assertThat(view.roomPhase()).isEqualTo("PROCESSING");
        assertThat(view.pendingState()).isEqualTo("PROCESSING");
        assertThat(view.activeLogicalRunId()).isNull();
    }

    @Test
    void nonReadyFutureProjectionReturnsProcessing() {
        ProjectionRow current = currentShadowRow();
        ProjectionRow preparing = new ProjectionRow(
                current.writerMode(),
                "PREPARING",
                current.projectionRoomEpoch(),
                current.projectionProcessRevision(),
                current.projectionFencingToken(),
                current.roomPhase(),
                current.projectedAt(),
                current.epochWriterMode(),
                current.epochLifecycleStatus(),
                "PROVISIONING",
                current.epochRoomEpochValue(),
                current.epochProcessRevisionValue(),
                current.roomRevisionValue(),
                current.epochFencingTokenValue(),
                current.processContractVersion(),
                current.selectionSchemaVersion(),
                current.streamProtocol(),
                current.temporalBuildId(),
                current.roomWorkflowBuildId(),
                current.graphVersion(),
                current.checkpointSchemaVersion(),
                current.activeLogicalRunId(),
                current.activeAttemptId(),
                current.activeRunStatus(),
                current.lastSequenceNo());

        assertThat(adapter.adapt(preparing).projectionState()).isEqualTo("PROCESSING");
    }

    @Test
    void reportsPendingDuringTheFormalVisibleGapAndReadyAfterApplied() {
        ProjectionRow current = currentShadowRow();

        IntakeProcessProjectionView pending =
                adapter.adapt(withCommandAdmissionPending(current, true));
        IntakeProcessProjectionView applied =
                adapter.adapt(withCommandAdmissionPending(current, false));

        assertThat(pending.projectionState()).isEqualTo("CURRENT");
        assertThat(pending.commandAdmissionState()).isEqualTo("PENDING");
        assertThat(applied.projectionState()).isEqualTo("CURRENT");
        assertThat(applied.commandAdmissionState()).isEqualTo("READY");
    }

    @Test
    void mapsExactTerminalTupleToClosedProjectionWithoutAnActiveRun() {
        ProjectionRow current = currentShadowRow();
        ProjectionRow terminal = projectionRow(
                current, "TERMINAL", "CLOSED", "TERMINAL", null, null, null, null);

        IntakeProcessProjectionView view = adapter.adapt(terminal);

        assertThat(view.projectionState()).isEqualTo("CURRENT");
        assertThat(view.roomPhase()).isEqualTo("CLOSED");
        assertThat(view.pendingState()).isEqualTo("NONE");
        assertThat(view.activeLogicalRunId()).isNull();
        assertThat(view.streamCursor()).isNull();
    }

    @Test
    void terminalTupleFailsClosedForNonTerminalPhaseOrActiveRun() {
        ProjectionRow current = currentShadowRow();
        ProjectionRow nonTerminalPhase = projectionRow(
                current, "TERMINAL", "WAITING_PARTY", "TERMINAL", null, null, null, null);
        ProjectionRow terminalWithRun = projectionRow(
                current,
                "TERMINAL",
                "CLOSED",
                "TERMINAL",
                "run-stale",
                "attempt-stale",
                "RUNNING",
                7L);

        assertThat(adapter.adapt(nonTerminalPhase).projectionState()).isEqualTo("PROCESSING");
        assertThat(adapter.adapt(terminalWithRun).projectionState()).isEqualTo("PROCESSING");
    }

    @Test
    void malformedActiveRunTupleReturnsProcessingInsteadOfAClientVisibleCurrentState() {
        ProjectionRow current = currentShadowRow();
        ProjectionRow missingAttemptCursor = projectionRow(
                current,
                current.writerActivationStatus(),
                current.roomPhase(),
                current.epochLifecycleStatus(),
                "run-1",
                "attempt-2",
                "RUNNING",
                null);

        IntakeProcessProjectionView view = adapter.adapt(missingAttemptCursor);

        assertThat(view.projectionState()).isEqualTo("PROCESSING");
        assertThat(view.activeLogicalRunId()).isNull();
    }

    @Test
    void legacyProjectionDoesNotRequireAnEpochTuple() {
        ProjectionRow legacy = new ProjectionRow(
                "LEGACY",
                "READY",
                0,
                3,
                0,
                "OPEN",
                OffsetDateTime.parse("2026-07-22T03:04:05Z"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        IntakeProcessProjectionView view = adapter.adapt(legacy);

        assertThat(view.projectionState()).isEqualTo("CURRENT");
        assertThat(view.writerMode()).isEqualTo("LEGACY");
        assertThat(view.roomRevision()).isZero();
        assertThat(view.pendingState()).isEqualTo("NONE");
    }

    @Test
    void publicViewContainsNoWorkflowIdentifiersRefsOrHashes() {
        assertThat(Arrays.stream(IntakeProcessProjectionView.class.getRecordComponents())
                        .map(component -> component.getName().toLowerCase())
                        .toList())
                .noneMatch(
                        name -> name.contains("workflowid")
                                || name.contains("ref")
                                || name.contains("hash"));
    }

    private static ProjectionRow currentShadowRow() {
        return new ProjectionRow(
                "SHADOW",
                "READY",
                4,
                12,
                9,
                "WAITING_PARTY",
                OffsetDateTime.parse("2026-07-22T03:04:05Z"),
                "SHADOW",
                "ACTIVE",
                "READY",
                4L,
                12L,
                7L,
                9L,
                "case-process.v2",
                "room-epoch-selection.v2",
                "agent-stream.v2",
                "case-build-1",
                "intake-build-1",
                "2.0.0",
                "checkpoint.v2",
                "run-1",
                "attempt-2",
                "RUNNING",
                6L);
    }

    private static ProjectionRow projectionRow(
            ProjectionRow source,
            String writerActivationStatus,
            String roomPhase,
            String epochLifecycleStatus,
            String activeLogicalRunId,
            String activeAttemptId,
            String activeRunStatus,
            Long lastSequenceNo) {
        return new ProjectionRow(
                source.writerMode(),
                writerActivationStatus,
                source.projectionRoomEpoch(),
                source.projectionProcessRevision(),
                source.projectionFencingToken(),
                roomPhase,
                source.projectedAt(),
                source.epochWriterMode(),
                epochLifecycleStatus,
                source.epochProvisioningStatus(),
                source.epochRoomEpochValue(),
                source.epochProcessRevisionValue(),
                source.roomRevisionValue(),
                source.epochFencingTokenValue(),
                source.processContractVersion(),
                source.selectionSchemaVersion(),
                source.streamProtocol(),
                source.temporalBuildId(),
                source.roomWorkflowBuildId(),
                source.graphVersion(),
                source.checkpointSchemaVersion(),
                activeLogicalRunId,
                activeAttemptId,
                activeRunStatus,
                lastSequenceNo);
    }

    private static ProjectionRow withCommandAdmissionPending(
            ProjectionRow source, boolean commandAdmissionPending) {
        return new ProjectionRow(
                source.writerMode(),
                source.writerActivationStatus(),
                source.projectionRoomEpoch(),
                source.projectionProcessRevision(),
                source.projectionFencingToken(),
                source.roomPhase(),
                source.projectedAt(),
                source.epochWriterMode(),
                source.epochLifecycleStatus(),
                source.epochProvisioningStatus(),
                source.epochRoomEpochValue(),
                source.epochProcessRevisionValue(),
                source.roomRevisionValue(),
                source.epochFencingTokenValue(),
                source.processContractVersion(),
                source.selectionSchemaVersion(),
                source.streamProtocol(),
                source.temporalBuildId(),
                source.roomWorkflowBuildId(),
                source.graphVersion(),
                source.checkpointSchemaVersion(),
                commandAdmissionPending,
                source.activeLogicalRunId(),
                source.activeAttemptId(),
                source.activeRunStatus(),
                source.lastSequenceNo());
    }
}
