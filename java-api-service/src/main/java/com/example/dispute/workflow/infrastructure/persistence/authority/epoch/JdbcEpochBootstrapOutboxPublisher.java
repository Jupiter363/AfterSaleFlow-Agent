package com.example.dispute.workflow.infrastructure.persistence.authority.epoch;

import com.example.dispute.workflow.application.authority.epoch.EpochAuthorityException;
import com.example.dispute.workflow.application.authority.epoch.EpochBootstrapOutbox;
import com.example.dispute.workflow.application.authority.epoch.EpochBootstrapOutboxPublisher;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC publisher used only after the epoch service has asserted both party rows. */
@Repository
public class JdbcEpochBootstrapOutboxPublisher implements EpochBootstrapOutboxPublisher {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEpochBootstrapOutboxPublisher(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public String publish(EpochBootstrapOutbox outbox) {
        Objects.requireNonNull(outbox, "outbox must not be null");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", outbox.outboxId())
                .addValue("epochId", outbox.epochId())
                .addValue("tenant", outbox.tenantSurrogate())
                .addValue("caseId", outbox.caseId())
                .addValue("roomEpoch", outbox.roomEpoch())
                .addValue("fence", outbox.fencingToken())
                .addValue("writerMode", outbox.writerMode().name())
                .addValue("caseWorkflowId", outbox.caseWorkflowId())
                .addValue("roomWorkflowId", outbox.roomWorkflowId())
                .addValue("workflowType", outbox.workflowType())
                .addValue("taskQueue", outbox.taskQueue())
                .addValue("updateId", outbox.updateId())
                .addValue("payloadJson", outbox.payloadJson())
                .addValue("payloadSha256", outbox.payloadSha256())
                .addValue("availableAt", outbox.availableAt());
        try {
            jdbc.update(
                    """
                    insert into room_epoch_bootstrap_outbox (
                        id, epoch_id, tenant_surrogate, case_id, room_type, room_epoch,
                        fencing_token, writer_mode, case_workflow_id, room_workflow_id,
                        workflow_type, task_queue, update_id, payload_json, payload_sha256,
                        outbox_status, available_at, attempt_count, created_at, updated_at
                    ) values (
                        :id, :epochId, :tenant, :caseId, 'INTAKE', :roomEpoch, :fence,
                        :writerMode, :caseWorkflowId, :roomWorkflowId, :workflowType,
                        :taskQueue, :updateId, :payloadJson, :payloadSha256, 'PENDING',
                        :availableAt, 0, :availableAt, :availableAt
                    ) on conflict do nothing
                    """,
                    params);
        } catch (DataAccessException ex) {
            throw new EpochAuthorityException("AUTHORITY_BOOTSTRAP_WRITE_FAILED", ex.getMessage());
        }
        Integer exactRows = jdbc.queryForObject(
                """
                select count(*)
                  from room_epoch_bootstrap_outbox
                 where id = :id
                   and epoch_id = :epochId
                   and tenant_surrogate = :tenant
                   and case_id = :caseId
                   and room_type = 'INTAKE'
                   and room_epoch = :roomEpoch
                   and fencing_token = :fence
                   and writer_mode = :writerMode
                   and case_workflow_id = :caseWorkflowId
                   and room_workflow_id = :roomWorkflowId
                   and workflow_type = :workflowType
                   and task_queue = :taskQueue
                   and update_id = :updateId
                   and payload_json = :payloadJson
                   and payload_sha256 = :payloadSha256
                   and available_at = :availableAt
                """,
                params,
                Integer.class);
        if (exactRows == null || exactRows != 1) {
            throw new EpochAuthorityException(
                    "AUTHORITY_BOOTSTRAP_CONFLICT",
                    "bootstrap retry must match the complete immutable outbox tuple");
        }
        return outbox.outboxId();
    }
}
