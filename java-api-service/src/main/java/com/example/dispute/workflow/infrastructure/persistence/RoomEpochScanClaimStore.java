package com.example.dispute.workflow.infrastructure.persistence;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoomEpochScanClaimStore {

    private static final String DOMAIN_EVENT_RECOVERY = "domain_event_recovery";
    private static final String PROJECTION_RECONCILIATION = "projection_reconciliation";

    private final NamedParameterJdbcTemplate jdbc;

    public RoomEpochScanClaimStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClaimedRoomEpoch> claimDomainEventRecovery(
            int batchSize, Duration claimDuration) {
        return claim(DOMAIN_EVENT_RECOVERY, ClaimOrder.FAIR, batchSize, claimDuration);
    }

    public List<ClaimedRoomEpoch> claimPriorityDomainEventRecovery(
            int batchSize, Duration claimDuration) {
        return claim(DOMAIN_EVENT_RECOVERY, ClaimOrder.DOMAIN_PRIORITY, batchSize, claimDuration);
    }

    public List<ClaimedRoomEpoch> claimProjectionReconciliation(
            int batchSize, Duration claimDuration) {
        return claim(PROJECTION_RECONCILIATION, ClaimOrder.FAIR, batchSize, claimDuration);
    }

    public List<ClaimedRoomEpoch> claimPriorityProjectionReconciliation(
            int batchSize, Duration claimDuration) {
        return claim(
                PROJECTION_RECONCILIATION,
                ClaimOrder.PROJECTION_PRIORITY,
                batchSize,
                claimDuration);
    }

    public boolean renewDomainEventRecovery(
            ClaimedRoomEpoch claim, Duration claimDuration) {
        return renew(DOMAIN_EVENT_RECOVERY, claim, claimDuration);
    }

    public boolean renewProjectionReconciliation(
            ClaimedRoomEpoch claim, Duration claimDuration) {
        return renew(PROJECTION_RECONCILIATION, claim, claimDuration);
    }

    public boolean completeDomainEventRecovery(
            ClaimedRoomEpoch claim, Duration nextScanDelay) {
        return complete(DOMAIN_EVENT_RECOVERY, claim, nextScanDelay);
    }

    public boolean completeProjectionReconciliation(
            ClaimedRoomEpoch claim, Duration nextScanDelay) {
        return complete(PROJECTION_RECONCILIATION, claim, nextScanDelay);
    }

    private List<ClaimedRoomEpoch> claim(
            String scanPrefix,
            ClaimOrder claimOrder,
            int batchSize,
            Duration claimDuration) {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        long claimDurationMillis = positiveMillis(claimDuration, "claimDuration");

        String claimToken = UUID.randomUUID().toString();
        String nextScanColumn = scanPrefix + "_next_scan_at";
        String claimTokenColumn = scanPrefix + "_claim_token";
        String claimedUntilColumn = scanPrefix + "_claimed_until";
        String priorityJoin = priorityJoin(claimOrder);
        String projectionPredicate =
                claimOrder == ClaimOrder.DOMAIN_PRIORITY
                        ? ""
                        : projectionReadyPredicate(scanPrefix, "epoch");
        String orderBy = claimOrder(claimOrder, nextScanColumn);
        String sql =
                """
                with candidates as (
                    select epoch.id
                      from case_room_epoch epoch
                      %s
                     where epoch.lifecycle_status = 'ACTIVE'
                        and epoch.writer_mode in ('SHADOW', 'TEMPORAL')
                        and epoch.provisioning_status = 'READY'
                        and epoch.temporal_workflow_id is not null
                        and epoch.temporal_run_id is not null
                       and epoch.room_temporal_workflow_id is not null
                       and epoch.room_temporal_run_id is not null
                       %s
                       and epoch.%s <= clock_timestamp()
                       and (epoch.%s is null or epoch.%s <= clock_timestamp())
                     order by %s
                     for update of epoch skip locked
                     limit :batchSize
                )
                update case_room_epoch epoch
                   set %s = :claimToken,
                       %s = clock_timestamp()
                           + (:claimDurationMillis * interval '1 millisecond')
                  from candidates
                 where epoch.id = candidates.id
                returning epoch.id,
                          epoch.tenant_surrogate,
                          epoch.case_id,
                          epoch.room_type,
                          epoch.room_epoch,
                          epoch.fencing_token,
                          epoch.temporal_workflow_id
                """
                        .formatted(
                                priorityJoin,
                                projectionPredicate,
                                nextScanColumn,
                                claimedUntilColumn,
                                claimedUntilColumn,
                                orderBy,
                                claimTokenColumn,
                                claimedUntilColumn);
        Map<String, Object> parameters =
                Map.of(
                        "claimDurationMillis", claimDurationMillis,
                        "claimToken", claimToken,
                        "batchSize", batchSize);
        return jdbc.query(
                sql, parameters, (resultSet, rowNumber) -> mapClaim(resultSet, claimToken));
    }

    private static String priorityJoin(ClaimOrder claimOrder) {
        return switch (claimOrder) {
            case FAIR -> "";
            case DOMAIN_PRIORITY ->
                    """
                    join case_process_projection priority_projection
                      on priority_projection.case_id = epoch.case_id
                     and priority_projection.writer_activation_status = 'READY'
                     and priority_projection.writer_mode = epoch.writer_mode
                     and priority_projection.room_epoch = epoch.room_epoch
                     and priority_projection.fencing_token = epoch.fencing_token
                     and priority_projection.process_revision = epoch.process_revision
                     and priority_projection.temporal_workflow_id = epoch.temporal_workflow_id
                     and priority_projection.temporal_run_id = epoch.temporal_run_id
                    join lateral (
                        select timeline.sequence_no, timeline.created_at
                          from case_timeline_event timeline
                         where timeline.case_id = epoch.case_id
                         order by timeline.sequence_no desc,
                                  timeline.created_at desc,
                                  timeline.id
                         limit 1
                    ) priority_timeline
                      on priority_timeline.sequence_no
                         > priority_projection.last_case_event_sequence
                    """;
            case PROJECTION_PRIORITY ->
                    """
                    join lateral (
                        select command.deadline_at, command.accepted_at
                          from case_command command
                         where command.tenant_surrogate = epoch.tenant_surrogate
                           and command.case_id = epoch.case_id
                           and command.command_status in (
                               'PENDING_ORCHESTRATION', 'ORCHESTRATION_ACCEPTED'
                           )
                           and command.deadline_at > clock_timestamp()
                         order by command.deadline_at,
                                  command.accepted_at desc,
                                  command.id
                         limit 1
                    ) priority_command on true
                    """;
        };
    }

    private static String claimOrder(ClaimOrder claimOrder, String nextScanColumn) {
        return switch (claimOrder) {
            case FAIR -> "epoch." + nextScanColumn + ", epoch.updated_at, epoch.id";
            case DOMAIN_PRIORITY ->
                    "priority_timeline.created_at desc, "
                            + "priority_timeline.sequence_no desc, epoch.updated_at desc, epoch.id";
            case PROJECTION_PRIORITY ->
                    "priority_command.deadline_at, priority_command.accepted_at desc, "
                            + "epoch.updated_at desc, epoch.id";
        };
    }

    private boolean renew(
            String scanPrefix, ClaimedRoomEpoch claim, Duration claimDuration) {
        requireClaim(claim);
        long claimDurationMillis = positiveMillis(claimDuration, "claimDuration");
        String sql =
                """
                update case_room_epoch
                   set %s_claimed_until = clock_timestamp()
                       + (:claimDurationMillis * interval '1 millisecond')
                 where id = :epochId
                   and %s_claim_token = :claimToken
                   and %s_claimed_until > clock_timestamp()
                   and lifecycle_status = 'ACTIVE'
                   and writer_mode in ('SHADOW', 'TEMPORAL')
                   and provisioning_status = 'READY'
                   and temporal_run_id is not null
                   and room_temporal_workflow_id is not null
                   and room_temporal_run_id is not null
                   and tenant_surrogate = :tenantSurrogate
                   and case_id = :caseId
                   and room_type = :roomType
                   and room_epoch = :roomEpoch
                   and fencing_token = :fencingToken
                   and temporal_workflow_id = :temporalWorkflowId
                   %s
                """
                        .formatted(
                                scanPrefix,
                                scanPrefix,
                                scanPrefix,
                                projectionReadyPredicate(scanPrefix, "case_room_epoch"));
        return jdbc.update(sql, claimParameters(claim, claimDurationMillis, null)) == 1;
    }

    private boolean complete(
            String scanPrefix, ClaimedRoomEpoch claim, Duration nextScanDelay) {
        requireClaim(claim);
        long nextScanDelayMillis = positiveMillis(nextScanDelay, "nextScanDelay");
        String sql =
                """
                update case_room_epoch
                   set %s_next_scan_at = clock_timestamp()
                           + (:nextScanDelayMillis * interval '1 millisecond'),
                       %s_claim_token = null,
                       %s_claimed_until = null
                 where id = :epochId
                   and %s_claim_token = :claimToken
                   and %s_claimed_until > clock_timestamp()
                   and lifecycle_status = 'ACTIVE'
                   and writer_mode in ('SHADOW', 'TEMPORAL')
                   and provisioning_status = 'READY'
                   and temporal_run_id is not null
                   and room_temporal_workflow_id is not null
                   and room_temporal_run_id is not null
                   and tenant_surrogate = :tenantSurrogate
                   and case_id = :caseId
                   and room_type = :roomType
                   and room_epoch = :roomEpoch
                   and fencing_token = :fencingToken
                   and temporal_workflow_id = :temporalWorkflowId
                   %s
                """
                        .formatted(
                                scanPrefix,
                                scanPrefix,
                                scanPrefix,
                                scanPrefix,
                                scanPrefix,
                                projectionReadyPredicate(scanPrefix, "case_room_epoch"));
        return jdbc.update(sql, claimParameters(claim, null, nextScanDelayMillis)) == 1;
    }

    private static String projectionReadyPredicate(
            String scanPrefix, String epochAlias) {
        if (!DOMAIN_EVENT_RECOVERY.equals(scanPrefix)) {
            return "";
        }
        return """
                and exists (
                    select 1
                      from case_process_projection projection
                     where projection.case_id = %1$s.case_id
                       and projection.writer_activation_status = 'READY'
                       and projection.writer_mode = %1$s.writer_mode
                       and projection.room_epoch = %1$s.room_epoch
                       and projection.fencing_token = %1$s.fencing_token
                       and projection.process_revision = %1$s.process_revision
                       and projection.temporal_workflow_id = %1$s.temporal_workflow_id
                       and projection.temporal_run_id = %1$s.temporal_run_id
                )
                """
                .formatted(epochAlias);
    }

    private static Map<String, Object> claimParameters(
            ClaimedRoomEpoch claim,
            Long claimDurationMillis,
            Long nextScanDelayMillis) {
        java.util.HashMap<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("epochId", claim.epochId());
        parameters.put("claimToken", claim.claimToken());
        parameters.put("tenantSurrogate", claim.tenantSurrogate());
        parameters.put("caseId", claim.caseId());
        parameters.put("roomType", claim.roomType().name());
        parameters.put("roomEpoch", claim.roomEpoch());
        parameters.put("fencingToken", claim.fencingToken());
        parameters.put("temporalWorkflowId", claim.temporalWorkflowId());
        if (claimDurationMillis != null) {
            parameters.put("claimDurationMillis", claimDurationMillis);
        }
        if (nextScanDelayMillis != null) {
            parameters.put("nextScanDelayMillis", nextScanDelayMillis);
        }
        return Map.copyOf(parameters);
    }

    private static void requireClaim(ClaimedRoomEpoch claim) {
        if (claim == null) {
            throw new IllegalArgumentException("claim must be configured");
        }
    }

    private static long positiveMillis(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        try {
            long millis = duration.toMillis();
            if (millis < 1) {
                throw new IllegalArgumentException(field + " must be at least one millisecond");
            }
            return millis;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(field + " is too large", overflow);
        }
    }

    private static ClaimedRoomEpoch mapClaim(ResultSet resultSet, String claimToken)
            throws SQLException {
        return new ClaimedRoomEpoch(
                claimToken,
                resultSet.getString("id"),
                resultSet.getString("tenant_surrogate"),
                resultSet.getString("case_id"),
                RoomType.valueOf(resultSet.getString("room_type")),
                resultSet.getLong("room_epoch"),
                resultSet.getLong("fencing_token"),
                resultSet.getString("temporal_workflow_id"));
    }

    public record ClaimedRoomEpoch(
            String claimToken,
            String epochId,
            String tenantSurrogate,
            String caseId,
            RoomType roomType,
            long roomEpoch,
            long fencingToken,
            String temporalWorkflowId) {}

    private enum ClaimOrder {
        FAIR,
        DOMAIN_PRIORITY,
        PROJECTION_PRIORITY
    }
}
