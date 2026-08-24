package com.example.dispute.workflow.targete2e.artifact.finalization;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationEnvironmentSource;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationEnvironmentSource.EnvironmentEvidence;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the control-registered activation and immutable command admission evidence. */
public final class JdbcTargetE2eFinalizationAuthority
        implements TargetE2eFinalizationActivationPort, TargetE2eFinalizationEnvironmentSource {

    private static final String AUTHORIZATION_SQL = """
            select activation.activation_id, activation.manifest_hash,
                   activation.execution_lane, activation.tenant_surrogate,
                   activation.issued_at, activation.expires_at,
                   activation.lifecycle_status, activation.revoked_at,
                   activation.allowed_room_types, activation.agent_build_id,
                   activation.graph_key, activation.graph_version,
                   activation.graph_checkpoint_schema_version,
                   activation.isolated_domain_db_binding_hash,
                   activation.environment_id, activation.environment_generation,
                   activation.domain_cluster_identity, activation.domain_database_identity,
                   activation.domain_runtime_principal_identity,
                   exists (
                       select 1 from target_e2e_case_reservation reservation
                        where reservation.activation_id = activation.activation_id
                          and reservation.tenant_surrogate = ?
                          and reservation.case_id = ?
                   ) as case_reserved,
                   exists (
                       select 1
                         from target_e2e_room_epoch_binding binding
                         join case_room_epoch epoch on epoch.id = binding.epoch_id
                        where binding.activation_id = activation.activation_id
                          and binding.activation_manifest_hash = activation.manifest_hash
                          and binding.isolated_domain_db_binding_hash =
                              activation.isolated_domain_db_binding_hash
                          and binding.execution_lane = activation.execution_lane
                          and binding.tenant_surrogate = ?
                          and binding.case_id = ?
                          and binding.room_type = ?
                          and binding.room_epoch = ?
                          and binding.room_fencing_token = ?
                          and epoch.room_id = ?
                   ) as epoch_bound,
                   admission.activation_manifest_hash as admission_manifest_hash,
                   admission.isolated_domain_db_binding_hash as admission_db_binding_hash,
                   admission.tenant_surrogate as admission_tenant,
                   admission.case_id as admission_case_id,
                   admission.command_hash as admission_command_hash,
                   admission.command_envelope_hash as admission_envelope_hash,
                   admission.room_epoch as admission_room_epoch,
                   admission.room_fencing_token as admission_room_fence,
                   admission.admitted_at
              from target_e2e_activation activation
              left join target_e2e_command_admission admission
                on admission.activation_id = activation.activation_id
               and admission.command_id = ?
             where activation.activation_id = ?
            """;

    private static final String EVIDENCE_SQL = """
            select activation_id, manifest_hash, environment_id, environment_generation,
                   domain_cluster_identity, domain_database_identity,
                   domain_runtime_principal_identity, isolated_domain_db_binding_hash
              from target_e2e_activation
             where activation_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final String activationId;
    private final Clock clock;

    public JdbcTargetE2eFinalizationAuthority(
            DataSource dataSource, String activationId, Clock clock) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        if (activationId == null || !activationId.matches("p9act[.]v1[.][0-9a-f]{32}")) {
            throw new IllegalArgumentException("activationId is invalid");
        }
        this.activationId = activationId;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AuthorizationDecision authorize(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        var rows = jdbc.query(
                AUTHORIZATION_SQL,
                (result, ignored) -> authorizationRow(result),
                request.tenantSurrogate(),
                request.caseId(),
                request.tenantSurrogate(),
                request.caseId(),
                request.roomType().name(),
                request.roomEpoch(),
                request.roomFencingToken(),
                request.roomId(),
                request.commandId(),
                activationId);
        if (rows.isEmpty()) {
            return AuthorizationDecision.denied(Decision.ABSENT);
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("target E2E activation authority is not unique");
        }
        AuthorizationRow row = rows.getFirst();
        if (!row.caseReserved()
                || !row.epochBound()
                || !row.executionLane().equals("TARGET_E2E_CANDIDATE")
                || !row.tenantSurrogate().equals(request.tenantSurrogate())
                || !row.allowedRoomTypes().contains(request.roomType())
                || !row.agentBuildId().equals(request.workflowBuildId())
                || !row.graphKey().equals("all-rooms.target-e2e.v2")) {
            return AuthorizationDecision.denied(Decision.SCOPE_DENIED);
        }
        if (!row.exactAdmission(request)) {
            return AuthorizationDecision.denied(Decision.SCOPE_DENIED);
        }
        Instant now = clock.instant();
        if (row.revokedAt() != null || "REVOKED_TERMINAL".equals(row.lifecycleStatus())) {
            return AuthorizationDecision.denied(Decision.REVOKED);
        }
        if ("REGISTERED".equals(row.lifecycleStatus())) {
            return AuthorizationDecision.denied(Decision.ABSENT);
        }
        if ("ACTIVE".equals(row.lifecycleStatus()) && !now.isBefore(row.expiresAt())) {
            return AuthorizationDecision.denied(Decision.EXPIRED);
        }
        if (!"ACTIVE".equals(row.lifecycleStatus())
                && !"DRAIN_ONLY".equals(row.lifecycleStatus())) {
            return AuthorizationDecision.denied(Decision.REVOKED);
        }
        Lifecycle lifecycle = Lifecycle.valueOf(row.lifecycleStatus());
        AcceptedCommandProof proof = lifecycle == Lifecycle.DRAIN_ONLY
                ? new AcceptedCommandProof(
                        request.commandId(),
                        row.admissionCommandHash(),
                        row.admissionEnvelopeHash(),
                        row.admissionRoomEpoch(),
                        row.admissionRoomFence(),
                        row.admittedAt())
                : null;
        return AuthorizationDecision.allowed(new ActivationGrant(
                row.activationId(),
                row.executionLane(),
                row.tenantSurrogate(),
                Set.of(request.caseId()),
                row.allowedRoomTypes(),
                row.agentBuildId(),
                row.graphKey(),
                row.graphVersion(),
                row.checkpointSchemaVersion(),
                row.manifestHash(),
                row.domainDbBindingHash(),
                lifecycle,
                proof,
                row.issuedAt(),
                row.expiresAt(),
                row.revokedAt()));
    }

    @Override
    public EnvironmentEvidence loadEnvironmentEvidence() {
        var rows = jdbc.query(
                EVIDENCE_SQL,
                (result, ignored) -> new EnvironmentEvidence(
                        result.getString("activation_id"),
                        result.getString("manifest_hash"),
                        result.getString("environment_id"),
                        result.getLong("environment_generation"),
                        result.getString("domain_cluster_identity"),
                        result.getString("domain_database_identity"),
                        result.getString("domain_runtime_principal_identity"),
                        result.getString("isolated_domain_db_binding_hash")),
                activationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("target E2E activation evidence is absent or ambiguous");
        }
        return rows.getFirst();
    }

    private static AuthorizationRow authorizationRow(ResultSet result) throws SQLException {
        return new AuthorizationRow(
                result.getString("activation_id"),
                result.getString("manifest_hash"),
                result.getString("execution_lane"),
                result.getString("tenant_surrogate"),
                instant(result, "issued_at"),
                instant(result, "expires_at"),
                result.getString("lifecycle_status"),
                instant(result, "revoked_at"),
                roomTypes(result.getArray("allowed_room_types")),
                result.getString("agent_build_id"),
                result.getString("graph_key"),
                result.getString("graph_version"),
                result.getString("graph_checkpoint_schema_version"),
                result.getString("isolated_domain_db_binding_hash"),
                result.getBoolean("case_reserved"),
                result.getBoolean("epoch_bound"),
                result.getString("admission_manifest_hash"),
                result.getString("admission_db_binding_hash"),
                result.getString("admission_tenant"),
                result.getString("admission_case_id"),
                result.getString("admission_command_hash"),
                result.getString("admission_envelope_hash"),
                result.getLong("admission_room_epoch"),
                result.getLong("admission_room_fence"),
                instant(result, "admitted_at"));
    }

    private static Set<RoomType> roomTypes(Array value) throws SQLException {
        if (value == null) {
            return Set.of();
        }
        Object raw = value.getArray();
        if (!(raw instanceof Object[] elements)) {
            throw new SQLException("allowed_room_types is not a SQL array");
        }
        try {
            return Arrays.stream(elements)
                    .map(String::valueOf)
                    .map(RoomType::valueOf)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException failure) {
            throw new SQLException("allowed_room_types contains an invalid room", failure);
        }
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record AuthorizationRow(
            String activationId,
            String manifestHash,
            String executionLane,
            String tenantSurrogate,
            Instant issuedAt,
            Instant expiresAt,
            String lifecycleStatus,
            Instant revokedAt,
            Set<RoomType> allowedRoomTypes,
            String agentBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String domainDbBindingHash,
            boolean caseReserved,
            boolean epochBound,
            String admissionManifestHash,
            String admissionDbBindingHash,
            String admissionTenant,
            String admissionCaseId,
            String admissionCommandHash,
            String admissionEnvelopeHash,
            long admissionRoomEpoch,
            long admissionRoomFence,
            Instant admittedAt) {

        private boolean exactAdmission(AuthorizationRequest request) {
            return admittedAt != null
                    && admittedAt.isBefore(expiresAt)
                    && Objects.equals(manifestHash, admissionManifestHash)
                    && Objects.equals(domainDbBindingHash, admissionDbBindingHash)
                    && Objects.equals(tenantSurrogate, admissionTenant)
                    && Objects.equals(request.caseId(), admissionCaseId)
                    && Objects.equals(request.commandHash(), admissionCommandHash)
                    && Objects.equals(request.commandEnvelopeHash(), admissionEnvelopeHash)
                    && request.roomEpoch() == admissionRoomEpoch
                    && request.roomFencingToken() == admissionRoomFence;
        }
    }
}
