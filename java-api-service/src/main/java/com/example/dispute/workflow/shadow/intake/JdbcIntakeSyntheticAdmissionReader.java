package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.shadow.intake.IntakeSyntheticAdmissionReader.AdmissionQuery;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticAdmissionReader.AdmissionPins;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticAdmissionReader.PersistedAdmission;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Narrow SQL dependency on the additive V043_3 admission relation.
 *
 * <p>The relation and column names intentionally live only in this class so the admission-owner
 * integration can align the final expand-only migration without changing runtime authority logic.
 */
public final class JdbcIntakeSyntheticAdmissionReader implements IntakeSyntheticAdmissionReader {

    static final String ADMISSION_SQL = """
        select schema_version, traffic_source, admission_status,
               issued_at_epoch_seconds, not_before_epoch_seconds, expires_at_epoch_seconds,
               epoch_id, party_authority_id, case_command_id, payload_authority_id,
               access_session_id, registration_id, tenant_surrogate, case_id,
               room_type, writer_mode, room_epoch, fencing_token, actor_id, actor_role,
               command_id, command_sequence, command_type, party, actor_scope_hash,
               payload_ref, payload_hash, command_operation_key, request_hash,
               accepted_room_revision, thread_id, agent_session_id, process_revision, room_revision,
               deadline_epoch_millis,
               retry_provider_attempts, retry_activity_attempts, retry_repairs,
               logical_run_id, attempt_id, selection_hash, registration_hash,
               case_workflow_type, case_workflow_build_id,
               room_workflow_type, room_workflow_build_id, process_contract_version,
               graph_key, graph_version,
               checkpoint_schema_version, prompt_version, model_profile_id,
               output_schema_version, policy_version, guardrail_version,
               tool_policy_version, state_schema_version, stream_protocol,
               cohort_policy_version, agent_key, agent_session_profile_version,
               memory_policy_id, pinned_versions::text as pinned_versions_json,
               parity_baseline_ref, parity_baseline_hash, authorization_hash
          from case_intake_synthetic_activity_admission
         where tenant_surrogate = ? and case_id = ? and room_epoch = ?
           and fencing_token = ? and command_id = ? and command_type = ?
           and party = ? and actor_scope_hash = ? and request_hash = ?
           and thread_id = ? and agent_session_id = ?
        """;

    @Override
    public List<PersistedAdmission> find(Connection connection, AdmissionQuery query)
            throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(query, "query must not be null");
        var envelope = query.envelope();
        try (PreparedStatement statement = connection.prepareStatement(ADMISSION_SQL)) {
            statement.setString(1, envelope.tenantSurrogate());
            statement.setString(2, envelope.caseId());
            statement.setLong(3, envelope.roomEpoch());
            statement.setLong(4, envelope.fencingToken());
            statement.setString(5, envelope.commandId());
            statement.setString(6, envelope.commandType().name());
            statement.setString(7, envelope.party().name());
            statement.setString(8, envelope.actorScopeHash());
            statement.setString(9, query.requestHash());
            statement.setString(10, query.threadId());
            statement.setString(11, query.agentSessionId());
            try (ResultSet result = statement.executeQuery()) {
                List<PersistedAdmission> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(map(result));
                    if (rows.size() == 2) {
                        break;
                    }
                }
                return List.copyOf(rows);
            }
        }
    }

    private static PersistedAdmission map(ResultSet row) throws SQLException {
        RetryBudget retryBudget = new RetryBudget(
                "intake-retry-budget.v1",
                row.getInt("retry_provider_attempts"),
                row.getInt("retry_activity_attempts"),
                row.getInt("retry_repairs"));
        AdmissionPins pins = new AdmissionPins(
                row.getString("case_workflow_type"),
                row.getString("case_workflow_build_id"),
                row.getString("room_workflow_type"),
                row.getString("room_workflow_build_id"),
                row.getString("process_contract_version"),
                row.getString("graph_key"),
                row.getString("graph_version"),
                row.getString("checkpoint_schema_version"),
                row.getString("state_schema_version"),
                row.getString("stream_protocol"),
                row.getString("prompt_version"),
                row.getString("model_profile_id"),
                row.getString("output_schema_version"),
                row.getString("policy_version"),
                row.getString("guardrail_version"),
                row.getString("tool_policy_version"),
                row.getString("cohort_policy_version"),
                row.getString("agent_key"),
                row.getString("agent_session_profile_version"),
                row.getString("memory_policy_id"));
        return new PersistedAdmission(
                row.getString("schema_version"),
                row.getString("traffic_source"),
                row.getString("admission_status"),
                row.getLong("issued_at_epoch_seconds"),
                row.getLong("not_before_epoch_seconds"),
                row.getLong("expires_at_epoch_seconds"),
                row.getString("epoch_id"),
                row.getString("party_authority_id"),
                row.getString("case_command_id"),
                row.getString("payload_authority_id"),
                row.getString("access_session_id"),
                row.getString("registration_id"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getString("room_type"),
                row.getString("writer_mode"),
                row.getLong("room_epoch"),
                row.getLong("fencing_token"),
                row.getString("actor_id"),
                ActorRole.valueOf(row.getString("actor_role")),
                row.getString("command_id"),
                row.getLong("command_sequence"),
                IntakeCommandType.valueOf(row.getString("command_type")),
                IntakeParty.valueOf(row.getString("party")),
                row.getString("actor_scope_hash"),
                row.getString("payload_ref"),
                row.getString("payload_hash"),
                row.getString("command_operation_key"),
                row.getString("request_hash"),
                row.getLong("accepted_room_revision"),
                row.getString("thread_id"),
                row.getString("agent_session_id"),
                row.getLong("process_revision"),
                row.getLong("room_revision"),
                row.getLong("deadline_epoch_millis"),
                retryBudget,
                row.getString("logical_run_id"),
                row.getString("attempt_id"),
                row.getString("selection_hash"),
                row.getString("registration_hash"),
                pins,
                row.getString("pinned_versions_json"),
                row.getString("parity_baseline_ref"),
                row.getString("parity_baseline_hash"),
                row.getString("authorization_hash"));
    }
}
