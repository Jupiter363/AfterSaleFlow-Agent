package com.example.dispute.workflow.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.api.EvidenceSubmissionRequest;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter.ProjectionRow;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class EvidenceProcessProjectionAdapterTest {

    private final EvidenceProcessProjectionAdapter adapter =
            new EvidenceProcessProjectionAdapter(mock(NamedParameterJdbcOperations.class));

    @Test
    @SuppressWarnings("unchecked")
    void readScopesEverySupportedViewerAndExcludesActiveRunsFromHistory() {
        for (ActorRole role : List.of(
                ActorRole.USER, ActorRole.MERCHANT, ActorRole.PLATFORM_REVIEWER)) {
            for (boolean historyMode : List.of(false, true)) {
                NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
                when(jdbc.query(
                                anyString(),
                                any(SqlParameterSource.class),
                                any(RowMapper.class)))
                        .thenReturn(List.of());
                EvidenceProcessProjectionAdapter scopedAdapter =
                        new EvidenceProcessProjectionAdapter(jdbc);

                scopedAdapter.read(
                        "CASE_SAFE",
                        new AuthenticatedActor(role.name().toLowerCase() + "-safe", role),
                        historyMode);

                ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
                ArgumentCaptor<SqlParameterSource> parameters =
                        ArgumentCaptor.forClass(SqlParameterSource.class);
                verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
                assertThat(sql.getValue())
                        .contains(
                                "epoch.room_type = 'EVIDENCE'",
                                "epoch.room_epoch = projection.room_epoch",
                                "epoch.fencing_token = projection.fencing_token",
                                "run.room_type = 'EVIDENCE'",
                                "run.room_id = epoch.room_id",
                                "run.room_epoch = epoch.room_epoch",
                                "run.fencing_token = epoch.fencing_token",
                                "run.stream_audience_json",
                                "run.stream_audience_actor_ids_json",
                                "cast(:historyMode as boolean) = false");
                assertThat(parameters.getValue().getValue("actorId"))
                        .isEqualTo(role.name().toLowerCase() + "-safe");
                assertThat(parameters.getValue().getValue("actorRole"))
                        .isEqualTo(role.name());
                assertThat(parameters.getValue().getValue("historyMode"))
                        .isEqualTo(historyMode);
            }
        }
    }

    @Test
    void mapsCurrentActorScopedShadowTupleWithoutPrivateAuthorityFields() {
        EvidenceProcessProjectionView view = adapter.adapt(currentShadowRow(false));

        assertThat(view.schemaVersion()).isEqualTo("evidence-process-projection.v1");
        assertThat(view.projectionState()).isEqualTo("AVAILABLE");
        assertThat(view.writerMode()).isEqualTo("SHADOW");
        assertThat(view.roomEpoch()).isEqualTo(4);
        assertThat(view.processRevision()).isEqualTo(12);
        assertThat(view.roomRevision()).isEqualTo(7);
        assertThat(view.fencingToken()).isEqualTo(9);
        assertThat(view.roomPhase()).isEqualTo("ASSESSING");
        assertThat(view.pendingState()).isEqualTo("AGENT_RUNNING");
        assertThat(view.activeLogicalRunId()).isEqualTo("run-1");
        assertThat(view.activeAttemptId()).isEqualTo("attempt-2");
        assertThat(view.streamCursor()).isEqualTo("v2:attempt-2:6");
        assertThat(view.versionPins().graphVersion()).isEqualTo("evidence.v2.0.0");
    }

    @Test
    void legacyProjectionRemainsAnExplicitUnavailableCompatibilityFallback() {
        ProjectionRow legacy = new ProjectionRow(
                "LEGACY",
                "READY",
                0,
                3,
                0,
                "WAITING_PARTIES",
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
                null,
                false);

        EvidenceProcessProjectionView view = adapter.adapt(legacy);

        assertThat(view.projectionState()).isEqualTo("UNAVAILABLE");
        assertThat(view.writerMode()).isEqualTo("LEGACY");
        assertThat(view.pendingState()).isEqualTo("WAITING_PARTY");
        assertThat(view.activeLogicalRunId()).isNull();
        assertThat(view.streamCursor()).isNull();
    }

    @Test
    void historyModeFailsClosedIfAnActorRunLeaksIntoTheRow() {
        EvidenceProcessProjectionView view = adapter.adapt(currentShadowRow(true));

        assertThat(view.projectionState()).isEqualTo("PROCESSING");
        assertThat(view.historyMode()).isTrue();
        assertThat(view.activeLogicalRunId()).isNull();
        assertThat(view.activeAttemptId()).isNull();
        assertThat(view.streamCursor()).isNull();
    }

    @Test
    void closedSyntheticHistoryHasNoActiveRunOrPendingState() {
        ProjectionRow active = currentShadowRow(false);
        ProjectionRow closedHistory = new ProjectionRow(
                active.writerMode(),
                "TERMINAL",
                active.projectionRoomEpoch(),
                active.projectionProcessRevision(),
                active.projectionFencingToken(),
                "COMPLETED",
                active.projectedAt(),
                active.epochWriterMode(),
                "TERMINAL",
                active.epochProvisioningStatus(),
                active.epochRoomEpochValue(),
                active.epochProcessRevisionValue(),
                active.roomRevisionValue(),
                active.epochFencingTokenValue(),
                active.processContractVersion(),
                active.selectionSchemaVersion(),
                active.streamProtocol(),
                active.temporalBuildId(),
                active.roomWorkflowBuildId(),
                active.graphVersion(),
                active.checkpointSchemaVersion(),
                null,
                null,
                null,
                null,
                true);

        EvidenceProcessProjectionView view = adapter.adapt(closedHistory);

        assertThat(view.projectionState()).isEqualTo("AVAILABLE");
        assertThat(view.roomPhase()).isEqualTo("COMPLETED");
        assertThat(view.pendingState()).isEqualTo("NONE");
        assertThat(view.historyMode()).isTrue();
        assertThat(view.activeLogicalRunId()).isNull();
    }

    @Test
    void publicViewDoesNotExposeActorIdentityOrPrivateArtifactBindings() {
        assertThat(Arrays.stream(EvidenceProcessProjectionView.class.getRecordComponents())
                        .map(component -> component.getName().toLowerCase())
                        .toList())
                .noneMatch(
                        name -> name.contains("actor")
                                || name.contains("viewer")
                                || name.contains("tenant")
                                || name.contains("hash")
                                || name.contains("ref")
                                || name.contains("receipt")
                                || name.contains("content"));
    }

    @Test
    void publicSubmissionAcceptsAtMostFiftyEvidenceIdsAndRejectsFiftyOne() {
        List<String> fifty = evidenceIds(50);
        List<String> fiftyOne = evidenceIds(51);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new EvidenceSubmissionRequest(fifty, "closed batch")))
                    .isEmpty();
            assertThat(validator.validate(new EvidenceSubmissionRequest(fiftyOne, "too many")))
                    .singleElement()
                    .satisfies(violation ->
                            assertThat(violation.getPropertyPath().toString())
                                    .isEqualTo("evidenceIds"));
        }
    }

    private static List<String> evidenceIds(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> "EVIDENCE_" + index)
                .toList();
    }

    private static ProjectionRow currentShadowRow(boolean historyMode) {
        return new ProjectionRow(
                "SHADOW",
                "READY",
                4,
                12,
                9,
                "ASSESSING",
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
                "evidence-workflow.synthetic.v1",
                "evidence.v2.0.0",
                "evidence-checkpoint.v2",
                "run-1",
                "attempt-2",
                "RUNNING",
                6L,
                historyMode);
    }
}
