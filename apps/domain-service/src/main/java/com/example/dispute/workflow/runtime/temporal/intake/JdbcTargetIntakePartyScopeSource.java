package com.example.dispute.workflow.runtime.temporal.intake;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** JDBC authority reader for target Intake party roles and canonical actor scopes. */
public final class JdbcTargetIntakePartyScopeSource implements TargetIntakePartyScopeSource {

  static final String AUTHORITY_SQL = """
      select activation.activation_id,
             activation.manifest_hash as activation_manifest_hash,
             dispute.user_id, dispute.merchant_id,
             dispute.initiator_id, dispute.initiator_role,
             dispute.respondent_id, dispute.respondent_role
        from case_room_epoch epoch
        join production_runtime_room_epoch_binding binding
          on binding.epoch_id = epoch.id
         and binding.tenant_surrogate = epoch.tenant_surrogate
         and binding.case_id = epoch.case_id
         and binding.room_type = epoch.room_type
         and binding.room_epoch = epoch.room_epoch
         and binding.room_fencing_token = epoch.fencing_token
        join production_runtime_activation activation
          on activation.activation_id = binding.activation_id
         and activation.manifest_hash = binding.activation_manifest_hash
         and activation.execution_lane = binding.execution_lane
         and activation.isolated_domain_db_binding_hash =
             binding.isolated_domain_db_binding_hash
         and activation.tenant_surrogate = binding.tenant_surrogate
        join production_runtime_case_reservation reservation
          on reservation.activation_id = binding.activation_id
         and reservation.tenant_surrogate = binding.tenant_surrogate
         and reservation.case_id = binding.case_id
        join fulfillment_dispute_case dispute
          on dispute.id = epoch.case_id
       where epoch.tenant_surrogate = :tenantSurrogate
         and epoch.case_id = :caseId
         and epoch.room_type = 'INTAKE'
         and epoch.room_epoch = :roomEpoch
         and epoch.fencing_token = :roomFencingToken
         and epoch.writer_mode = 'TEMPORAL'
         and (
              (epoch.lifecycle_status = 'PROVISIONING'
               and epoch.provisioning_status = 'PROVISIONING'
               and epoch.room_temporal_run_id is null)
              or
              (epoch.lifecycle_status = 'ACTIVE'
               and epoch.provisioning_status = 'READY'
               and coalesce(btrim(epoch.room_temporal_run_id), '') <> '')
         )
         and binding.execution_lane = 'PRODUCTION'
         and binding.room_type = 'INTAKE'
         and activation.execution_lane = 'PRODUCTION'
         and ((activation.lifecycle_status = 'ACTIVE'
               and activation.expires_at > clock_timestamp())
              or activation.lifecycle_status = 'DRAIN_ONLY')
      """;

  private final NamedParameterJdbcOperations jdbc;

  public JdbcTargetIntakePartyScopeSource(DataSource dataSource) {
    this(new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
  }

  JdbcTargetIntakePartyScopeSource(NamedParameterJdbcOperations jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public ResolvedPartyScopes resolve(Request request) {
    Objects.requireNonNull(request, "request");
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("tenantSurrogate", request.tenantSurrogate())
            .addValue("caseId", request.caseId())
            .addValue("roomEpoch", request.roomEpoch())
            .addValue("roomFencingToken", request.roomFencingToken());
    List<Map<String, Object>> rows = jdbc.queryForList(AUTHORITY_SQL, parameters);
    if (rows.size() != 1) {
      throw new IllegalArgumentException(
          "target Intake party authority is absent or ambiguous for the exact room activation");
    }
    Map<String, Object> row = rows.getFirst();
    String userId = text(row, "user_id");
    String merchantId = text(row, "merchant_id");
    String initiatorId = text(row, "initiator_id");
    ActorRole initiatorRole = role(row, "initiator_role");
    String respondentId = text(row, "respondent_id");
    ActorRole respondentRole = role(row, "respondent_role");
    requireExactPartyAssignment(
        userId, merchantId, initiatorId, initiatorRole, respondentId, respondentRole);
    return ResolvedPartyScopes.create(
        text(row, "activation_id"),
        text(row, "activation_manifest_hash"),
        request,
        initiatorId,
        initiatorRole,
        respondentId,
        respondentRole);
  }

  private static void requireExactPartyAssignment(
      String userId,
      String merchantId,
      String initiatorId,
      ActorRole initiatorRole,
      String respondentId,
      ActorRole respondentRole) {
    boolean userInitiated =
        initiatorRole == ActorRole.USER
            && initiatorId.equals(userId)
            && respondentRole == ActorRole.MERCHANT
            && respondentId.equals(merchantId);
    boolean merchantInitiated =
        initiatorRole == ActorRole.MERCHANT
            && initiatorId.equals(merchantId)
            && respondentRole == ActorRole.USER
            && respondentId.equals(userId);
    if (userId.equals(merchantId)
        || initiatorId.equals(respondentId)
        || (!userInitiated && !merchantInitiated)) {
      throw new IllegalArgumentException(
          "target Intake party authority is not a distinct USER/MERCHANT assignment");
    }
  }

  private static ActorRole role(Map<String, Object> row, String column) {
    try {
      return ActorRole.valueOf(text(row, column));
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("target Intake party role is invalid", failure);
    }
  }

  private static String text(Map<String, Object> row, String column) {
    Object value = row.get(column);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("target Intake party authority column is invalid: " + column);
    }
    return text;
  }
}
