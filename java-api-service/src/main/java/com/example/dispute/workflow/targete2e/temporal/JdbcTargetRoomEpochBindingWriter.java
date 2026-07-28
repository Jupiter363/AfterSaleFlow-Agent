package com.example.dispute.workflow.targete2e.temporal;

import com.example.dispute.workflow.application.epoch.RoomEpochSelection.TargetActivationBinding;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes the immutable room-epoch activation binding in the allocator's domain transaction. */
@Repository
@ConditionalOnProperty(
        name = "app.orchestration.new-epoch-mode",
        havingValue = "TEMPORAL")
public class JdbcTargetRoomEpochBindingWriter implements TargetRoomEpochBindingWriter {

    private static final String INSERT_BINDING =
            """
            insert into target_e2e_room_epoch_binding (
                epoch_id,
                activation_id,
                activation_manifest_hash,
                execution_lane,
                isolated_domain_db_binding_hash,
                tenant_surrogate,
                case_id,
                room_type,
                room_epoch,
                room_fencing_token
            ) values (
                :epochId,
                :activationId,
                :activationManifestHash,
                :executionLane,
                :isolatedDomainDbBindingHash,
                :tenantSurrogate,
                :caseId,
                :roomType,
                :roomEpoch,
                :roomFencingToken
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Autowired
    public JdbcTargetRoomEpochBindingWriter(DataSource dataSource) {
        this(new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    JdbcTargetRoomEpochBindingWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void persist(BindingContext context) {
        Objects.requireNonNull(context, "context");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "target room epoch binding requires the active writable allocation transaction");
        }
        TargetActivationBinding activation = context.selection().targetActivationBinding();
        int inserted = jdbc.update(
                INSERT_BINDING,
                new MapSqlParameterSource()
                        .addValue("epochId", context.epochId())
                        .addValue("activationId", activation.activationId())
                        .addValue("activationManifestHash", activation.activationManifestHash())
                        .addValue("executionLane", activation.executionLane())
                        .addValue(
                                "isolatedDomainDbBindingHash",
                                activation.isolatedDomainDbBindingHash())
                        .addValue("tenantSurrogate", context.tenantSurrogate())
                        .addValue("caseId", context.caseId())
                        .addValue("roomType", context.roomType().name())
                        .addValue("roomEpoch", context.roomEpoch())
                        .addValue("roomFencingToken", context.fencingToken()));
        if (inserted != 1) {
            throw new IllegalStateException(
                    "target room epoch activation binding was not persisted exactly once");
        }
    }
}
