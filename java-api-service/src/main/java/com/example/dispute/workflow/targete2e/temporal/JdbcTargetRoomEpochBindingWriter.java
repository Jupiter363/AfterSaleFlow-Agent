package com.example.dispute.workflow.targete2e.temporal;

import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection.TargetActivationBinding;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochBindingWriter.SuccessorContext;
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

    private static final String SELECT_SUCCESSOR_AUTHORITY =
            """
            select binding.activation_id,
                   binding.activation_manifest_hash,
                   binding.execution_lane,
                   binding.isolated_domain_db_binding_hash
              from case_room_epoch epoch
              join case_process_projection projection
                on projection.case_id = epoch.case_id
               and projection.tenant_surrogate = epoch.tenant_surrogate
               and projection.current_room = epoch.room_type
               and projection.room_epoch = epoch.room_epoch
               and projection.fencing_token = epoch.fencing_token
               and projection.writer_mode = epoch.writer_mode
               and projection.process_revision = epoch.process_revision
               and projection.temporal_workflow_id = epoch.temporal_workflow_id
               and projection.temporal_run_id = epoch.temporal_run_id
               and projection.temporal_build_id = epoch.temporal_build_id
              join target_e2e_room_epoch_binding binding
                on binding.epoch_id = epoch.id
               and binding.tenant_surrogate = epoch.tenant_surrogate
               and binding.case_id = epoch.case_id
               and binding.room_type = epoch.room_type
               and binding.room_epoch = epoch.room_epoch
               and binding.room_fencing_token = epoch.fencing_token
              join target_e2e_activation activation
                on activation.activation_id = binding.activation_id
               and activation.manifest_hash = binding.activation_manifest_hash
               and activation.execution_lane = binding.execution_lane
               and activation.isolated_domain_db_binding_hash =
                       binding.isolated_domain_db_binding_hash
               and activation.tenant_surrogate = binding.tenant_surrogate
             where epoch.id = :sourceEpochId
               and epoch.tenant_surrogate = :tenantSurrogate
               and epoch.case_id = :caseId
               and epoch.room_type = :sourceRoomType
               and epoch.room_epoch = :sourceRoomEpoch
               and epoch.fencing_token = :sourceFencingToken
               and epoch.process_revision = :sourceProcessRevision
               and epoch.temporal_workflow_id = :sourceTemporalWorkflowId
               and epoch.writer_mode = 'TEMPORAL'
               and epoch.lifecycle_status = 'ACTIVE'
               and epoch.provisioning_status = 'READY'
               and epoch.temporal_run_id is not null
               and epoch.room_temporal_run_id is not null
               and projection.writer_activation_status = 'READY'
               and epoch.selection_schema_version = :selectionSchemaVersion
               and epoch.process_contract_version = :processContractVersion
               and epoch.workflow_type = :caseWorkflowType
               and epoch.temporal_build_id = :caseWorkflowBuildId
               and epoch.room_workflow_type = :sourceRoomWorkflowType
               and epoch.room_workflow_build_id = :roomWorkflowBuildId
               and epoch.graph_key = :graphKey
               and epoch.graph_version = :graphVersion
               and epoch.checkpoint_schema_version = :checkpointSchemaVersion
               and epoch.stream_protocol = :streamProtocol
               and epoch.temporal_build_id = activation.case_build_id
               and epoch.room_workflow_build_id = activation.control_build_id
               and epoch.graph_key = activation.graph_key
               and epoch.graph_version = activation.graph_version
               and epoch.checkpoint_schema_version =
                       activation.graph_checkpoint_schema_version
               and binding.execution_lane = 'TARGET_E2E_CANDIDATE'
               and (
                    (activation.lifecycle_status = 'ACTIVE'
                        and activation.expires_at > clock_timestamp())
                    or activation.lifecycle_status = 'DRAIN_ONLY'
               )
               and activation.formal_writer = 'JAVA_FINALIZER_ONLY'
               and activation.java_domain_commit_allowed = true
               and activation.external_effects_allowed = false
               and activation.production_traffic_allowed = false
               and cast(:nextRoomType as varchar) = any(activation.allowed_room_types)
             for update of epoch, projection, binding, activation
            """;

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
                room_fencing_token,
                intake_room_message_execution_profile_id
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
                :roomFencingToken,
                :intakeRoomMessageExecutionProfileId
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
    public RoomEpochSelection selectSuccessor(SuccessorContext context) {
        Objects.requireNonNull(context, "context");
        requireWritableTransaction();
        RoomEpochSelection source = context.sourceSelection();
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("sourceEpochId", context.sourceEpochId())
                        .addValue("tenantSurrogate", context.tenantSurrogate())
                        .addValue("caseId", context.caseId())
                        .addValue("sourceRoomType", context.sourceRoomType().name())
                        .addValue("sourceRoomEpoch", context.sourceRoomEpoch())
                        .addValue("sourceFencingToken", context.sourceFencingToken())
                        .addValue("sourceProcessRevision", context.sourceProcessRevision())
                        .addValue("sourceTemporalWorkflowId", context.sourceTemporalWorkflowId())
                        .addValue("nextRoomType", context.nextRoomType().name())
                        .addValue("selectionSchemaVersion", source.selectionSchemaVersion())
                        .addValue("processContractVersion", source.processContractVersion())
                        .addValue("caseWorkflowType", source.caseWorkflowType())
                        .addValue("caseWorkflowBuildId", source.caseWorkflowBuildId())
                        .addValue("sourceRoomWorkflowType", source.roomWorkflowType())
                        .addValue("roomWorkflowBuildId", source.roomWorkflowBuildId())
                        .addValue("graphKey", source.graphKey())
                        .addValue("graphVersion", source.graphVersion())
                        .addValue("checkpointSchemaVersion", source.checkpointSchemaVersion())
                        .addValue("streamProtocol", source.streamProtocol());
        var rows =
                jdbc.query(
                        SELECT_SUCCESSOR_AUTHORITY,
                        parameters,
                        (row, ignored) ->
                                new TargetActivationBinding(
                                        row.getString(1),
                                        row.getString(2),
                                        row.getString(3),
                                        row.getString(4)));
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "TEMPORAL room epoch selection requires exact target activation authority");
        }
        return new RoomEpochSelection(
                WriterMode.TEMPORAL,
                source.selectionSchemaVersion(),
                source.processContractVersion(),
                source.caseWorkflowType(),
                source.caseWorkflowBuildId(),
                TargetTypedRoomProtocol.workflowType(context.nextRoomType()),
                source.roomWorkflowBuildId(),
                source.graphKey(),
                source.graphVersion(),
                source.checkpointSchemaVersion(),
                source.streamProtocol(),
                rows.getFirst());
    }

    @Override
    public void persist(BindingContext context) {
        Objects.requireNonNull(context, "context");
        requireWritableTransaction();
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
                        .addValue("roomFencingToken", context.fencingToken())
                        .addValue(
                                "intakeRoomMessageExecutionProfileId",
                                IntakeRoomMessageExecutionProfile
                                        .forNewTargetEpoch(context.roomType())
                                        .name()));
        if (inserted != 1) {
            throw new IllegalStateException(
                    "target room epoch activation binding was not persisted exactly once");
        }
    }

    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "target room epoch binding requires the active writable allocation transaction");
        }
    }
}
