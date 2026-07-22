package com.example.dispute.workflow.projection.intake;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.projection.intake.IntakeProcessProjectionView.VersionPins;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Component;

/** Reads the Java-owned process/epoch tuple without exposing workflow or cognitive-store bindings. */
@Component
public class IntakeProcessProjectionAdapter {

    private static final String READ_SQL =
            """
            select projection.writer_mode,
                   projection.writer_activation_status,
                   projection.room_epoch as projection_room_epoch,
                   projection.process_revision as projection_process_revision,
                   projection.fencing_token as projection_fencing_token,
                   projection.room_phase,
                   projection.projected_at,
                   epoch.writer_mode as epoch_writer_mode,
                   epoch.lifecycle_status as epoch_lifecycle_status,
                   epoch.provisioning_status as epoch_provisioning_status,
                   epoch.room_epoch as epoch_room_epoch,
                   epoch.process_revision as epoch_process_revision,
                   epoch.room_revision,
                   epoch.fencing_token as epoch_fencing_token,
                   epoch.process_contract_version,
                   epoch.selection_schema_version,
                   epoch.stream_protocol,
                   epoch.temporal_build_id,
                   epoch.room_workflow_build_id,
                   epoch.graph_version,
                   epoch.checkpoint_schema_version,
                   active_run.logical_run_id,
                   active_run.attempt_id,
                   active_run.run_status,
                   active_run.last_sequence_no
              from case_process_projection projection
              left join case_room_epoch epoch
                on epoch.case_id = projection.case_id
               and epoch.room_type = 'INTAKE'
               and epoch.lifecycle_status = 'ACTIVE'
              left join lateral (
                    select run.id as logical_run_id,
                           run.run_status,
                           attempt.id as attempt_id,
                           attempt.last_sequence_no
                      from agent_run run
                      left join lateral (
                            select candidate.id, candidate.last_sequence_no
                              from agent_run_attempt candidate
                             where candidate.agent_run_id = run.id
                               and candidate.attempt_status in (
                                   'PENDING', 'RUNNING', 'RESULT_READY'
                               )
                             order by candidate.attempt_no desc
                             limit 1
                      ) attempt on true
                     where run.case_id = projection.case_id
                       and run.room_id = epoch.room_id
                       and run.room_type = 'INTAKE'
                       and run.room_epoch = epoch.room_epoch
                       and run.process_revision = epoch.process_revision
                       and run.fencing_token = epoch.fencing_token
                       and run.protocol = 'agent-stream.v2'
                       and run.run_status in ('PENDING', 'RUNNING')
                       and run.stream_operation is not null
                       and exists (
                           select 1
                             from jsonb_array_elements_text(run.stream_audience_json) audience
                            where audience.value = :actorRole
                       )
                       and exists (
                           select 1
                             from jsonb_array_elements_text(
                                 run.stream_audience_actor_ids_json
                             ) audience_actor
                            where audience_actor.value = :actorId
                       )
                     order by run.created_at desc
                     limit 1
              ) active_run on true
             where projection.case_id = :caseId
            """;

    private final NamedParameterJdbcOperations jdbc;

    public IntakeProcessProjectionAdapter(NamedParameterJdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Optional<IntakeProcessProjectionView> read(
            String caseId, AuthenticatedActor actor) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        Objects.requireNonNull(actor, "actor");
        List<ProjectionRow> rows = jdbc.query(
                READ_SQL,
                new MapSqlParameterSource("caseId", caseId)
                        .addValue("actorId", actor.actorId())
                        .addValue("actorRole", actor.role().name()),
                IntakeProcessProjectionAdapter::row);
        if (rows.size() > 1) {
            return Optional.of(processing(rows.getFirst()));
        }
        return rows.stream().findFirst().map(this::adapt);
    }

    public IntakeProcessProjectionView adapt(ProjectionRow row) {
        Objects.requireNonNull(row, "row");
        String writerMode = normalized(row.writerMode());
        if ("LEGACY".equals(writerMode)) {
            return currentLegacy(row);
        }
        if (!tupleIsCurrent(row, writerMode) || !phaseIsKnown(row.roomPhase())) {
            return processing(row);
        }
        return new IntakeProcessProjectionView(
                IntakeProcessProjectionView.SCHEMA_VERSION,
                IntakeProcessProjectionView.CURRENT,
                writerMode,
                row.projectionRoomEpoch(),
                row.projectionProcessRevision(),
                row.roomRevision(),
                row.projectionFencingToken(),
                normalized(row.roomPhase()),
                pendingState(row.roomPhase()),
                row.activeLogicalRunId(),
                row.activeAttemptId(),
                row.activeRunStatus(),
                streamCursor(row),
                versionPins(row),
                row.projectedAt());
    }

    private static IntakeProcessProjectionView currentLegacy(ProjectionRow row) {
        return new IntakeProcessProjectionView(
                IntakeProcessProjectionView.SCHEMA_VERSION,
                IntakeProcessProjectionView.CURRENT,
                "LEGACY",
                row.projectionRoomEpoch(),
                row.projectionProcessRevision(),
                row.epochPresent() ? row.roomRevision() : 0,
                row.projectionFencingToken(),
                normalized(row.roomPhase()),
                pendingState(row.roomPhase()),
                null,
                null,
                null,
                null,
                row.epochPresent() ? versionPins(row) : VersionPins.unavailable(),
                row.projectedAt());
    }

    private static IntakeProcessProjectionView processing(ProjectionRow row) {
        return new IntakeProcessProjectionView(
                IntakeProcessProjectionView.SCHEMA_VERSION,
                IntakeProcessProjectionView.PROCESSING,
                normalizedOr(row.writerMode(), "SHADOW"),
                row.projectionRoomEpoch(),
                row.projectionProcessRevision(),
                row.epochPresent() ? row.roomRevision() : 0,
                row.projectionFencingToken(),
                IntakeProcessProjectionView.PROCESSING,
                IntakeProcessProjectionView.PROCESSING,
                null,
                null,
                null,
                null,
                row.epochPresent() ? versionPins(row) : VersionPins.unavailable(),
                row.projectedAt());
    }

    private static boolean tupleIsCurrent(ProjectionRow row, String writerMode) {
        return row.epochPresent()
                && "READY".equals(normalized(row.writerActivationStatus()))
                && "ACTIVE".equals(normalized(row.epochLifecycleStatus()))
                && "READY".equals(normalized(row.epochProvisioningStatus()))
                && writerMode.equals(normalized(row.epochWriterMode()))
                && row.projectionRoomEpoch() == row.epochRoomEpoch()
                && row.projectionProcessRevision() == row.epochProcessRevision()
                && row.projectionFencingToken() == row.epochFencingToken();
    }

    private static boolean phaseIsKnown(String roomPhase) {
        return switch (normalized(roomPhase)) {
            case "OPEN",
                    "WAITING_PARTY",
                    "WAITING_TIMER",
                    "AGENT_RUNNING",
                    "READY_TO_CONFIRM",
                    "REVIEW_PENDING",
                    "TOOL_RUNNING",
                    "COMPLETED",
                    "FAILED" -> true;
            default -> false;
        };
    }

    private static String pendingState(String roomPhase) {
        return switch (normalized(roomPhase)) {
            case "WAITING_PARTY", "WAITING_TIMER", "AGENT_RUNNING", "REVIEW_PENDING",
                    "TOOL_RUNNING", "FAILED" -> normalized(roomPhase);
            default -> "NONE";
        };
    }

    private static VersionPins versionPins(ProjectionRow row) {
        return new VersionPins(
                row.processContractVersion(),
                row.selectionSchemaVersion(),
                row.streamProtocol(),
                row.temporalBuildId(),
                row.roomWorkflowBuildId(),
                row.graphVersion(),
                row.checkpointSchemaVersion());
    }

    private static String streamCursor(ProjectionRow row) {
        if (row.activeLogicalRunId() == null) {
            return null;
        }
        if (row.activeAttemptId() == null || row.lastSequenceNo() == null) {
            return "-1";
        }
        return "v2:" + row.activeAttemptId() + ':' + row.lastSequenceNo();
    }

    private static ProjectionRow row(ResultSet resultSet, int ignored) throws SQLException {
        Long epochRoomEpoch = nullableLong(resultSet, "epoch_room_epoch");
        return new ProjectionRow(
                resultSet.getString("writer_mode"),
                resultSet.getString("writer_activation_status"),
                resultSet.getLong("projection_room_epoch"),
                resultSet.getLong("projection_process_revision"),
                resultSet.getLong("projection_fencing_token"),
                resultSet.getString("room_phase"),
                resultSet.getObject("projected_at", OffsetDateTime.class),
                resultSet.getString("epoch_writer_mode"),
                resultSet.getString("epoch_lifecycle_status"),
                resultSet.getString("epoch_provisioning_status"),
                epochRoomEpoch,
                nullableLong(resultSet, "epoch_process_revision"),
                nullableLong(resultSet, "room_revision"),
                nullableLong(resultSet, "epoch_fencing_token"),
                resultSet.getString("process_contract_version"),
                resultSet.getString("selection_schema_version"),
                resultSet.getString("stream_protocol"),
                resultSet.getString("temporal_build_id"),
                resultSet.getString("room_workflow_build_id"),
                resultSet.getString("graph_version"),
                resultSet.getString("checkpoint_schema_version"),
                resultSet.getString("logical_run_id"),
                resultSet.getString("attempt_id"),
                resultSet.getString("run_status"),
                nullableLong(resultSet, "last_sequence_no"));
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizedOr(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    public record ProjectionRow(
            String writerMode,
            String writerActivationStatus,
            long projectionRoomEpoch,
            long projectionProcessRevision,
            long projectionFencingToken,
            String roomPhase,
            OffsetDateTime projectedAt,
            String epochWriterMode,
            String epochLifecycleStatus,
            String epochProvisioningStatus,
            Long epochRoomEpochValue,
            Long epochProcessRevisionValue,
            Long roomRevisionValue,
            Long epochFencingTokenValue,
            String processContractVersion,
            String selectionSchemaVersion,
            String streamProtocol,
            String temporalBuildId,
            String roomWorkflowBuildId,
            String graphVersion,
            String checkpointSchemaVersion,
            String activeLogicalRunId,
            String activeAttemptId,
            String activeRunStatus,
            Long lastSequenceNo) {

        boolean epochPresent() {
            return epochRoomEpochValue != null;
        }

        long epochRoomEpoch() {
            return epochRoomEpochValue == null ? 0 : epochRoomEpochValue;
        }

        long epochProcessRevision() {
            return epochProcessRevisionValue == null ? 0 : epochProcessRevisionValue;
        }

        long roomRevision() {
            return roomRevisionValue == null ? 0 : roomRevisionValue;
        }

        long epochFencingToken() {
            return epochFencingTokenValue == null ? 0 : epochFencingTokenValue;
        }
    }
}
