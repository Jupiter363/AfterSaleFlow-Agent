package com.example.dispute.workflow.projection.evidence;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.ActiveGraphRun;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.AssessmentCounts;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.PartyCompletion;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.Recovery;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.TerminalProposal;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.VersionPins;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Component;

/** Reads a frozen Evidence projection bound to one explicitly supported viewer. */
@Component
public class EvidenceProcessProjectionAdapter {

    private static final Set<ActorRole> ALLOWED_VIEWERS =
            Set.of(ActorRole.USER, ActorRole.MERCHANT, ActorRole.PLATFORM_REVIEWER);

    private static final String READ_SQL =
            """
            select projection.tenant_surrogate,
                   projection.case_id,
                   projection.writer_mode,
                   projection.writer_activation_status,
                   projection.room_epoch as projection_room_epoch,
                   projection.process_revision as projection_process_revision,
                   projection.fencing_token as projection_fencing_token,
                   projection.room_phase,
                   projection.projected_at,
                   epoch.room_id,
                   epoch.writer_mode as epoch_writer_mode,
                   epoch.lifecycle_status as epoch_lifecycle_status,
                   epoch.provisioning_status as epoch_provisioning_status,
                   epoch.room_epoch as epoch_room_epoch,
                   epoch.process_revision as epoch_process_revision,
                   epoch.room_revision,
                   epoch.fencing_token as epoch_fencing_token,
                   epoch.room_workflow_build_id,
                   epoch.graph_version,
                   epoch.checkpoint_schema_version,
                   active_run.command_id,
                   active_run.logical_run_id,
                   active_run.attempt_id,
                   active_run.manifest_id,
                   active_run.manifest_hash,
                   active_run.active_graph_version,
                   active_run.active_checkpoint_schema_version,
                   active_run.run_status,
                   cast(:historyMode as boolean) as history_mode,
                   viewer.actor_id as scoped_actor_id,
                   viewer.actor_role as scoped_actor_role
               from case_process_projection projection
               join fulfillment_dispute_case dispute
                 on dispute.id = projection.case_id
               join case_access_session viewer
                 on viewer.case_id = projection.case_id
                and viewer.actor_id = :actorId
                and viewer.actor_role = :actorRole
                and viewer.status = 'ACTIVE'
                and viewer.permission_level = case
                    when :actorRole = 'USER' then 'PARTY_USER'
                    when :actorRole = 'MERCHANT' then 'PARTY_MERCHANT'
                    when :actorRole = 'PLATFORM_REVIEWER' then 'REVIEWER_ALL'
                    else '__DENY__'
                end
                and viewer.permission_scopes_json @> cast(:requiredViewerScopes as jsonb)
               left join case_room_epoch epoch
                on epoch.case_id = projection.case_id
               and epoch.room_type = 'EVIDENCE'
               and epoch.room_epoch = projection.room_epoch
               and epoch.fencing_token = projection.fencing_token
               and epoch.lifecycle_status in ('ACTIVE', 'TERMINAL')
              left join lateral (
                    select run.id as command_id,
                           run.id as logical_run_id,
                           attempt.id as attempt_id,
                           run.committed_manifest_id as manifest_id,
                           run.committed_manifest_hash as manifest_hash,
                           coalesce(attempt.graph_version, epoch.graph_version)
                               as active_graph_version,
                           coalesce(
                               attempt.checkpoint_schema_version,
                               epoch.checkpoint_schema_version
                           ) as active_checkpoint_schema_version,
                           run.run_status
                      from agent_run run
                      left join lateral (
                            select candidate.id,
                                   candidate.graph_version,
                                   candidate.checkpoint_schema_version
                              from agent_run_attempt candidate
                             where candidate.agent_run_id = run.id
                               and candidate.attempt_status in (
                                   'PENDING', 'RUNNING', 'RESULT_READY'
                               )
                             order by candidate.attempt_no desc
                             limit 1
                      ) attempt on true
                     where cast(:historyMode as boolean) = false
                       and run.case_id = projection.case_id
                       and run.room_id = epoch.room_id
                       and epoch.lifecycle_status = 'ACTIVE'
                       and run.room_type = 'EVIDENCE'
                       and run.room_epoch = epoch.room_epoch
                       and run.process_revision = epoch.process_revision
                       and run.fencing_token = epoch.fencing_token
                       and run.protocol = 'agent-stream.v2'
                       and run.run_status in ('PENDING', 'RUNNING')
                       and run.stream_operation is not null
                       and exists (
                           select 1
                             from jsonb_array_elements_text(run.stream_audience_json) audience
                             where audience.value = viewer.actor_role
                       )
                       and exists (
                           select 1
                             from jsonb_array_elements_text(
                                 run.stream_audience_actor_ids_json
                             ) audience_actor
                             where audience_actor.value = viewer.actor_id
                       )
                     order by run.created_at desc
                     limit 1
              ) active_run on true
              where projection.case_id = :caseId
                and (
                    (
                        viewer.actor_role = 'USER'
                        and (
                            viewer.actor_id = dispute.user_id
                            or exists (
                                select 1
                                  from case_participant participant
                                 where participant.case_id = projection.case_id
                                   and participant.actor_id = viewer.actor_id
                                   and participant.participant_role = 'USER'
                                   and participant.participant_status = 'ACTIVE'
                            )
                        )
                    )
                    or (
                        viewer.actor_role = 'MERCHANT'
                        and (
                            viewer.actor_id = dispute.merchant_id
                            or exists (
                                select 1
                                  from case_participant participant
                                 where participant.case_id = projection.case_id
                                   and participant.actor_id = viewer.actor_id
                                   and participant.participant_role = 'MERCHANT'
                                   and participant.participant_status = 'ACTIVE'
                            )
                        )
                    )
                    or viewer.actor_role = 'PLATFORM_REVIEWER'
                )
            """;

    private final NamedParameterJdbcOperations jdbc;

    public EvidenceProcessProjectionAdapter(NamedParameterJdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Optional<EvidenceProcessProjectionView> read(
            String caseId, AuthenticatedActor actor, boolean historyMode) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        requireAllowedViewer(actor);
        List<ProjectionRow> rows = jdbc.query(
                READ_SQL,
                new MapSqlParameterSource("caseId", caseId)
                        .addValue("actorId", actor.actorId())
                        .addValue("actorRole", actor.role().name())
                        .addValue("requiredViewerScopes", "[\"CASE_READ\",\"EVIDENCE_READ\"]")
                        .addValue("historyMode", historyMode),
                EvidenceProcessProjectionAdapter::row);
        if (rows.size() > 1) {
            return Optional.of(processing(rows.getFirst(), requireViewerBinding(rows.getFirst(), actor)));
        }
        return rows.stream().findFirst().map(row -> adapt(row, actor));
    }

    public Optional<EvidenceProcessProjectionView> read(
            String caseId, AuthenticatedActor actor) {
        return read(caseId, actor, false);
    }

    public EvidenceProcessProjectionView adapt(ProjectionRow row, AuthenticatedActor actor) {
        Objects.requireNonNull(row, "row");
        requireAllowedViewer(actor);
        ViewerBinding viewer = requireViewerBinding(row, actor);
        String writerMode = normalized(row.writerMode());
        if ("LEGACY".equals(writerMode)) {
            return legacy(row, viewer);
        }
        if (!"SHADOW".equals(writerMode)) {
            throw new IllegalArgumentException("unsupported Evidence writer mode: " + writerMode);
        }
        requireSyntheticShadow(row);
        if (!tupleIsCurrent(row)
                || !phaseIsKnown(row.roomPhase())
                || !activeRunTupleIsCurrent(row)) {
            return processing(row, viewer);
        }
        return projection(row, viewer, EvidenceProcessProjectionView.AVAILABLE, true);
    }

    private static EvidenceProcessProjectionView legacy(
            ProjectionRow row, ViewerBinding viewer) {
        ProjectionEvidenceState state = requireState(row);
        String phase = wirePhase(row.roomPhase());
        String terminalReason = terminalReason(phase, state);
        return new EvidenceProcessProjectionView(
                        EvidenceProcessProjectionView.SCHEMA_VERSION,
                        "0".repeat(64),
                        EvidenceProcessProjectionView.UNAVAILABLE,
                        row.tenantSurrogate(),
                        row.caseId(),
                        null,
                        0,
                        0,
                        "LEGACY",
                        "DISABLED",
                        false,
                        false,
                        false,
                        viewer.actorId(),
                        viewer.actorRole(),
                        viewerScopeHash(viewer),
                        viewer.actorRole(),
                        phase,
                        terminalReason,
                        pendingState(phase, row.roomPhase(), row.historyMode(), null),
                        null,
                        state.originalDeadlineAt(),
                        state.warningSent(),
                        state.warningSentAt(),
                        state.partyCompletion(),
                        state.assessmentCounts(),
                        state.dossierVersion(),
                        row.historyMode(),
                        state.lastEventSequence(),
                        null,
                        proposalForPhase(phase, state.terminalProposal()),
                        state.recovery(),
                        VersionPins.legacy(),
                        row.projectionProcessRevision(),
                        row.epochPresent() ? row.roomRevision() : 0,
                        requireProjectedAt(row))
                .withComputedHash();
    }

    private static EvidenceProcessProjectionView processing(
            ProjectionRow row, ViewerBinding viewer) {
        return projection(row, viewer, EvidenceProcessProjectionView.PROCESSING, false);
    }

    private static EvidenceProcessProjectionView projection(
            ProjectionRow row,
            ViewerBinding viewer,
            String projectionState,
            boolean exposeActiveRun) {
        ProjectionEvidenceState state = requireState(row);
        String phase = wirePhase(row.roomPhase());
        ActiveGraphRun activeRun = exposeActiveRun && !row.historyMode() && !"COMPLETED".equals(phase)
                ? row.activeGraphRun()
                : null;
        String pendingState = pendingState(phase, row.roomPhase(), row.historyMode(), activeRun);
        String pendingOperationKey = activeRun == null
                ? null
                : activeRun.expectedOperationKey(row.caseId(), row.projectionRoomEpoch());
        return new EvidenceProcessProjectionView(
                        EvidenceProcessProjectionView.SCHEMA_VERSION,
                        "0".repeat(64),
                        projectionState,
                        row.tenantSurrogate(),
                        row.caseId(),
                        row.roomId(),
                        row.projectionRoomEpoch(),
                        row.projectionFencingToken(),
                        "SHADOW",
                        "SIGNED_SYNTHETIC_SHADOW",
                        false,
                        false,
                        false,
                        viewer.actorId(),
                        viewer.actorRole(),
                        viewerScopeHash(viewer),
                        viewer.actorRole(),
                        phase,
                        terminalReason(phase, state),
                        pendingState,
                        pendingOperationKey,
                        state.originalDeadlineAt(),
                        state.warningSent(),
                        state.warningSentAt(),
                        state.partyCompletion(),
                        state.assessmentCounts(),
                        state.dossierVersion(),
                        row.historyMode(),
                        state.lastEventSequence(),
                        activeRun,
                        proposalForPhase(phase, state.terminalProposal()),
                        state.recovery(),
                        shadowPins(row),
                        row.projectionProcessRevision(),
                        row.epochPresent() ? row.roomRevision() : 0,
                        requireProjectedAt(row))
                .withComputedHash();
    }

    private static VersionPins shadowPins(ProjectionRow row) {
        return VersionPins.shadow(
                requiredPin(row.roomWorkflowBuildId(), "roomWorkflowBuildId"),
                requiredPin(row.graphVersion(), "graphVersion"),
                requiredPin(row.checkpointSchemaVersion(), "checkpointSchemaVersion"));
    }

    private static String requiredPin(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static OffsetDateTime requireProjectedAt(ProjectionRow row) {
        if (row.projectedAt() == null) {
            throw new IllegalArgumentException("projectedAt must not be null");
        }
        return row.projectedAt();
    }

    private static ProjectionEvidenceState requireState(ProjectionRow row) {
        if (row.evidenceState() == null) {
            throw new IllegalArgumentException("evidenceState must not be null");
        }
        return row.evidenceState();
    }

    private static String terminalReason(String phase, ProjectionEvidenceState state) {
        if (!"COMPLETED".equals(phase)) {
            return null;
        }
        if (state.terminalReason() != null) {
            return state.terminalReason();
        }
        return state.partyCompletion().initiatorCompleted()
                        && state.partyCompletion().respondentCompleted()
                ? "BOTH_PARTIES_COMPLETED"
                : "DEADLINE_EXPIRED";
    }

    private static TerminalProposal proposalForPhase(
            String phase, TerminalProposal terminalProposal) {
        return switch (phase) {
            case "READY_TO_FREEZE", "COMPLETED" -> terminalProposal;
            default -> null;
        };
    }

    private static String pendingState(
            String phase,
            String sourcePhase,
            boolean historyMode,
            ActiveGraphRun activeGraphRun) {
        if (historyMode || "COMPLETED".equals(phase)) {
            return "NONE";
        }
        if (activeGraphRun != null) {
            return "AGENT_RUNNING";
        }
        return switch (normalized(sourcePhase)) {
            case "WAITING_PARTY", "WAITING_PARTIES" -> "WAITING_PARTY";
            case "WAITING_TIMER" -> "WAITING_TIMER";
            case "ASSESSING", "AGENT_RUNNING", "REVIEW_PENDING", "TOOL_RUNNING" ->
                    "REVIEW_PENDING";
            default -> "NONE";
        };
    }

    private static String wirePhase(String phase) {
        return switch (normalized(phase)) {
            case "OPEN" -> "OPEN";
            case "WAITING_PARTY", "WAITING_PARTIES", "WAITING_TIMER" -> "WAITING_PARTIES";
            case "ASSESSING", "AGENT_RUNNING", "REVIEW_PENDING", "TOOL_RUNNING" -> "ASSESSING";
            case "READY_TO_CONFIRM", "READY_TO_FREEZE" -> "READY_TO_FREEZE";
            case "CLOSED", "COMPLETED" -> "COMPLETED";
            default -> throw new IllegalArgumentException("unsupported Evidence room phase");
        };
    }

    private static void requireAllowedViewer(AuthenticatedActor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.actorId() == null
                || actor.actorId().isBlank()
                || !ALLOWED_VIEWERS.contains(actor.role())) {
            throw new IllegalArgumentException("unsupported Evidence projection viewer");
        }
    }

    private static ViewerBinding requireViewerBinding(ProjectionRow row, AuthenticatedActor actor) {
        if (!actor.actorId().equals(row.scopedActorId())
                || !actor.role().name().equals(normalized(row.scopedActorRole()))
                || !ALLOWED_VIEWERS.contains(actor.role())) {
            throw new IllegalArgumentException("stale Evidence projection viewer binding");
        }
        return new ViewerBinding(row.scopedActorId(), normalized(row.scopedActorRole()));
    }

    private static void requireSyntheticShadow(ProjectionRow row) {
        if (row.tenantSurrogate() == null
                || !row.tenantSurrogate().startsWith("TENANT_P5_SYNTHETIC_")
                || row.caseId() == null
                || !row.caseId().startsWith("CASE_P5_SYNTHETIC_")
                || row.roomId() == null) {
            throw new IllegalArgumentException("real-case Evidence shadow is forbidden");
        }
    }

    static String viewerScopeHash(AuthenticatedActor actor) {
        return viewerScopeHash(new ViewerBinding(actor.actorId(), actor.role().name()));
    }

    private static String viewerScopeHash(ViewerBinding viewer) {
        ObjectNode scope = JsonNodeFactory.instance.objectNode();
        scope.put("actor_id", viewer.actorId());
        scope.put("actor_role", viewer.actorRole());
        scope.put("audience", viewer.actorRole());
        return ContractJson.sha256Hex(scope);
    }

    private static boolean tupleIsCurrent(ProjectionRow row) {
        String lifecycleStatus = normalized(row.epochLifecycleStatus());
        return row.epochPresent()
                && activationMatchesLifecycle(
                        normalized(row.writerActivationStatus()), lifecycleStatus)
                && lifecycleMatchesPhase(lifecycleStatus, row.roomPhase())
                && "READY".equals(normalized(row.epochProvisioningStatus()))
                && "SHADOW".equals(normalized(row.epochWriterMode()))
                && row.projectionRoomEpoch() == row.epochRoomEpoch()
                && row.projectionProcessRevision() == row.epochProcessRevision()
                && row.projectionFencingToken() == row.epochFencingToken();
    }

    private static boolean activationMatchesLifecycle(
            String writerActivationStatus, String lifecycleStatus) {
        return switch (lifecycleStatus) {
            case "ACTIVE" -> "READY".equals(writerActivationStatus);
            case "TERMINAL" -> "TERMINAL".equals(writerActivationStatus);
            default -> false;
        };
    }

    private static boolean lifecycleMatchesPhase(String lifecycleStatus, String roomPhase) {
        if ("ACTIVE".equals(lifecycleStatus)) {
            return true;
        }
        return "TERMINAL".equals(lifecycleStatus)
                && switch (normalized(roomPhase)) {
                    case "CLOSED", "COMPLETED" -> true;
                    default -> false;
                };
    }

    private static boolean activeRunTupleIsCurrent(ProjectionRow row) {
        if (row.historyMode() || !"ACTIVE".equals(normalized(row.epochLifecycleStatus()))) {
            return !row.activeRunObserved() && row.activeGraphRun() == null;
        }
        if (!row.activeRunObserved()) {
            return row.activeGraphRun() == null;
        }
        return row.activeGraphRun() != null
                && Set.of("QUEUED", "RUNNING").contains(row.activeGraphRun().status());
    }

    private static boolean phaseIsKnown(String roomPhase) {
        try {
            wirePhase(roomPhase);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static ProjectionRow row(ResultSet resultSet, int ignored) throws SQLException {
        OffsetDateTime projectedAt = resultSet.getObject("projected_at", OffsetDateTime.class);
        String logicalRunId = resultSet.getString("logical_run_id");
        ActiveGraphRun activeGraphRun = graphRun(resultSet, logicalRunId);
        return new ProjectionRow(
                resultSet.getString("tenant_surrogate"),
                resultSet.getString("case_id"),
                resultSet.getString("room_id"),
                resultSet.getString("writer_mode"),
                resultSet.getString("writer_activation_status"),
                resultSet.getLong("projection_room_epoch"),
                resultSet.getLong("projection_process_revision"),
                resultSet.getLong("projection_fencing_token"),
                resultSet.getString("room_phase"),
                projectedAt,
                resultSet.getString("epoch_writer_mode"),
                resultSet.getString("epoch_lifecycle_status"),
                resultSet.getString("epoch_provisioning_status"),
                nullableLong(resultSet, "epoch_room_epoch"),
                nullableLong(resultSet, "epoch_process_revision"),
                nullableLong(resultSet, "room_revision"),
                nullableLong(resultSet, "epoch_fencing_token"),
                resultSet.getString("room_workflow_build_id"),
                resultSet.getString("graph_version"),
                resultSet.getString("checkpoint_schema_version"),
                logicalRunId != null,
                activeGraphRun,
                ProjectionEvidenceState.pending(projectedAt),
                resultSet.getBoolean("history_mode"),
                resultSet.getString("scoped_actor_id"),
                resultSet.getString("scoped_actor_role"));
    }

    private static ActiveGraphRun graphRun(ResultSet resultSet, String logicalRunId)
            throws SQLException {
        if (logicalRunId == null) {
            return null;
        }
        String commandId = resultSet.getString("command_id");
        String attemptId = resultSet.getString("attempt_id");
        String manifestId = resultSet.getString("manifest_id");
        String manifestHash = resultSet.getString("manifest_hash");
        String graphVersion = resultSet.getString("active_graph_version");
        String checkpoint = resultSet.getString("active_checkpoint_schema_version");
        if (commandId == null
                || attemptId == null
                || manifestId == null
                || manifestHash == null
                || graphVersion == null
                || checkpoint == null) {
            return null;
        }
        String status = "PENDING".equals(normalized(resultSet.getString("run_status")))
                ? "QUEUED"
                : normalized(resultSet.getString("run_status"));
        return new ActiveGraphRun(
                commandId,
                logicalRunId,
                attemptId,
                manifestId,
                manifestHash,
                graphVersion,
                checkpoint,
                status);
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record ProjectionEvidenceState(
            OffsetDateTime originalDeadlineAt,
            boolean warningSent,
            OffsetDateTime warningSentAt,
            PartyCompletion partyCompletion,
            AssessmentCounts assessmentCounts,
            Long dossierVersion,
            long lastEventSequence,
            String terminalReason,
            TerminalProposal terminalProposal,
            Recovery recovery) {

        public static ProjectionEvidenceState pending(OffsetDateTime deadline) {
            return new ProjectionEvidenceState(
                    deadline,
                    false,
                    null,
                    PartyCompletion.pending(),
                    AssessmentCounts.empty(),
                    null,
                    0,
                    null,
                    null,
                    Recovery.none());
        }
    }

    public record ProjectionRow(
            String tenantSurrogate,
            String caseId,
            String roomId,
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
            String roomWorkflowBuildId,
            String graphVersion,
            String checkpointSchemaVersion,
            boolean activeRunObserved,
            ActiveGraphRun activeGraphRun,
            ProjectionEvidenceState evidenceState,
            boolean historyMode,
            String scopedActorId,
            String scopedActorRole) {

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

    private record ViewerBinding(String actorId, String actorRole) {}
}
