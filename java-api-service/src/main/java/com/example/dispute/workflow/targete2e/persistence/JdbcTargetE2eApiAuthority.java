package com.example.dispute.workflow.targete2e.persistence;

import com.example.dispute.workflow.targete2e.TargetE2eActivationCaseLedger;
import com.example.dispute.workflow.targete2e.TargetE2eActivationCaseLedger.Action;
import com.example.dispute.workflow.targete2e.TargetE2eActivationCaseLedger.Reservation;
import com.example.dispute.workflow.targete2e.TargetE2eActivationCaseLedger.ReservationResult;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeActivationAuthority;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeActivationGrant;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeCommandAdmissionAuthority;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeCommandAdmissionAuthority.AdmissionReceipt;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeCommandAdmissionAuthority.AdmissionRequest;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeEpochBinding;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochSelectionAuthority;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** API-side authority backed only by the activation row registered by the control worker. */
public final class JdbcTargetE2eApiAuthority
    implements TargetRoomEpochSelectionAuthority,
        TargetIntakeActivationAuthority,
        TargetIntakeCommandAdmissionAuthority {

  private static final String ACTIVE_ACTIVATION =
      """
      select activation_id, manifest_hash, environment_id, environment_generation,
             tenant_surrogate, expires_at, case_scope_mode, synthetic_case_id_prefix,
             synthetic_max_cases, synthetic_fixture_set_id, synthetic_fixture_set_hash,
             case_build_id, control_build_id, agent_build_id, graph_key, graph_version,
             graph_checkpoint_schema_version, graph_binding_hash, graph_code_build_id,
             isolated_domain_db_binding_hash
        from target_e2e_activation
       where activation_id = ?
         and tenant_surrogate = ?
         and ? = any(allowed_room_types)
         and lifecycle_status = 'ACTIVE'
         and expires_at > clock_timestamp()
      """;

  private final DataSource dataSource;
  private final TargetE2eActivationCaseLedger caseLedger;
  private final String activationId;
  private final Clock clock;

  public JdbcTargetE2eApiAuthority(
      DataSource dataSource,
      TargetE2eActivationCaseLedger caseLedger,
      String activationId,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.caseLedger = Objects.requireNonNull(caseLedger, "caseLedger");
    this.activationId = requireActivationId(activationId);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Optional<Grant> authorize(Request request) {
    Objects.requireNonNull(request, "request");
    ActivationRow activation = loadActive(request.tenantSurrogate(), request.roomType().name());
    if (activation == null || !ensureCaseReservation(activation, request.caseId())) {
      return Optional.empty();
    }
    return Optional.of(
        new Grant(
            activation.activationId(),
            activation.manifestHash(),
            activation.isolatedDomainDbBindingHash(),
            request,
            TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION,
            TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
            TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE,
            activation.caseBuildId(),
            TargetTypedRoomProtocol.workflowType(request.roomType()),
            activation.controlBuildId(),
            activation.graphKey(),
            activation.graphVersion(),
            activation.graphCheckpointSchemaVersion(),
            TargetTypedRoomProtocol.STREAM_PROTOCOL));
  }

  @Override
  public TargetIntakeActivationGrant authorize(TargetIntakeEpochBinding binding) {
    Objects.requireNonNull(binding, "binding");
    ActivationRow activation = loadActive(binding.tenantSurrogate(), "INTAKE");
    if (activation == null
        || !reservationExists(activation.activationId(), binding.tenantSurrogate(), binding.caseId())
        || !activation.controlBuildId().equals(binding.temporalBuildId())) {
      return null;
    }
    return new TargetIntakeActivationGrant(
        TargetIntakeActivationGrant.TARGET_LANE,
        activation.activationId(),
        activation.manifestHash(),
        binding.tenantSurrogate(),
        binding.caseId(),
        binding.roomEpoch(),
        binding.roomFencingToken(),
        binding.processRevision(),
        binding.roomRevision(),
        binding.temporalWorkflowId(),
        binding.temporalBuildId(),
        activation.expiresAt());
  }

  /** Resolves the current active activation row again immediately before target materialization. */
  public TargetIntakeRuntimePins resolveIntakeRuntimePins(
      TargetIntakeActivationGrant grant, TargetIntakeRuntimePins expected) {
    Objects.requireNonNull(grant, "grant");
    Objects.requireNonNull(expected, "expected");
    ActivationRow activation = loadActive(grant.tenantSurrogate(), "INTAKE");
    if (activation == null
        || !reservationExists(activation.activationId(), grant.tenantSurrogate(), grant.caseId())
        || !activation.activationId().equals(grant.activationId())
        || !activation.manifestHash().equals(grant.manifestHash())
        || !activation.expiresAt().equals(grant.expiresAt())
        || !activation.controlBuildId().equals(grant.temporalBuildId())) {
      throw new IllegalStateException("target Intake activation no longer matches its epoch grant");
    }
    return expected.requireActivation(
        activation.caseBuildId(), activation.agentBuildId(), activation.graphKey(),
        activation.graphVersion(), activation.graphCheckpointSchemaVersion(),
        activation.graphBindingHash(), activation.graphCodeBuildId(),
        activation.isolatedDomainDbBindingHash());
  }

  @Override
  public AdmissionReceipt admit(AdmissionRequest request) {
    Objects.requireNonNull(request, "request");
    if (!activationId.equals(request.activationId())
        || !request.requestedAt().isBefore(request.activationExpiresAt())) {
      throw new IllegalStateException("target Intake command is outside its activation window");
    }
    String envelopeHash = admissionEnvelopeHash(request);
    Connection connection = DataSourceUtils.getConnection(dataSource);
    boolean callerTransaction =
        DataSourceUtils.isConnectionTransactional(connection, dataSource);
    boolean restoreAutoCommit = false;
    try {
      if (!callerTransaction && connection.getAutoCommit()) {
        connection.setAutoCommit(false);
        restoreAutoCommit = true;
      }
      try {
        AdmissionActivation activation = lockAdmissionActivation(connection, request);
        if (activation == null
            || !activation.expiresAt().equals(request.activationExpiresAt())
            || !clock.instant().isBefore(activation.expiresAt())) {
          throw new IllegalStateException("target Intake activation is not ACTIVE");
        }
        AdmissionReceipt existing = findAdmission(connection, request, envelopeHash);
        if (existing != null) {
          commitOwnedTransaction(connection, callerTransaction);
          return existing;
        }
        Instant admittedAt = clock.instant();
        try (PreparedStatement statement =
            connection.prepareStatement(
                """
                insert into target_e2e_command_admission (
                    admission_id, activation_id, activation_manifest_hash, execution_lane,
                    isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id,
                    command_hash, command_envelope_hash, room_epoch, room_fencing_token,
                    admitted_at
                ) values (?, ?, ?, 'TARGET_E2E_CANDIDATE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
          int index = 1;
          statement.setString(index++, "p9cmd.v1." + compactUuid());
          statement.setString(index++, request.activationId());
          statement.setString(index++, request.manifestHash());
          statement.setString(index++, activation.isolatedDomainDbBindingHash());
          statement.setString(index++, request.tenantSurrogate());
          statement.setString(index++, request.caseId());
          statement.setString(index++, request.commandId());
          statement.setString(index++, request.payloadSha256());
          statement.setString(index++, envelopeHash);
          statement.setLong(index++, request.roomEpoch());
          statement.setLong(index++, request.roomFencingToken());
          statement.setTimestamp(index, Timestamp.from(admittedAt));
          statement.executeUpdate();
        }
        AdmissionReceipt persisted = findAdmission(connection, request, envelopeHash);
        if (persisted == null) {
          throw new IllegalStateException("target Intake command admission was not persisted");
        }
        commitOwnedTransaction(connection, callerTransaction);
        return new AdmissionReceipt(
            request.activationId(),
            request.manifestHash(),
            request.commandId(),
            request.roomEpoch(),
            request.roomFencingToken(),
            persisted.admittedAt(),
            false);
      } catch (RuntimeException | SQLException failure) {
        rollbackOwnedTransaction(connection, callerTransaction, failure);
        throw persistenceFailure("target Intake command admission failed", failure);
      }
    } catch (SQLException failure) {
      throw persistenceFailure("target Intake command admission connection failed", failure);
    } finally {
      restoreAutoCommit(connection, restoreAutoCommit);
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private ActivationRow loadActive(String tenantSurrogate, String roomType) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (PreparedStatement statement = connection.prepareStatement(ACTIVE_ACTIVATION)) {
      statement.setString(1, activationId);
      statement.setString(2, tenantSurrogate);
      statement.setString(3, roomType);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        ActivationRow row =
            new ActivationRow(
                result.getString("activation_id"),
                result.getString("manifest_hash"),
                result.getString("environment_id"),
                result.getLong("environment_generation"),
                result.getString("tenant_surrogate"),
                result.getTimestamp("expires_at").toInstant(),
                result.getString("case_scope_mode"),
                result.getString("synthetic_case_id_prefix"),
                result.getInt("synthetic_max_cases"),
                result.getString("synthetic_fixture_set_id"),
                result.getString("synthetic_fixture_set_hash"),
                result.getString("case_build_id"),
                result.getString("control_build_id"),
                result.getString("agent_build_id"),
                result.getString("graph_key"),
                result.getString("graph_version"),
                result.getString("graph_checkpoint_schema_version"),
                result.getString("graph_binding_hash"),
                result.getString("graph_code_build_id"),
                result.getString("isolated_domain_db_binding_hash"));
        if (result.next()) {
          throw new IllegalStateException("target activation lookup returned multiple rows");
        }
        return row;
      }
    } catch (SQLException failure) {
      throw persistenceFailure("target activation lookup failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private boolean ensureCaseReservation(ActivationRow activation, String caseId) {
    if (reservationExists(activation.activationId(), activation.tenantSurrogate(), caseId)) {
      return true;
    }
    if (!"ISOLATED_SYNTHETIC_NEW_CASES".equals(activation.caseScopeMode())
        || activation.caseIdPrefix() == null
        || !caseId.startsWith(activation.caseIdPrefix())) {
      return false;
    }
    for (int slot = 1; slot <= activation.maximumCases(); slot++) {
      ReservationResult result =
          caseLedger.apply(
              Action.RESERVE_BEFORE_EPOCH_SELECTION,
              new Reservation(
                  activation.environmentId(),
                  activation.environmentGeneration(),
                  activation.activationId(),
                  slot,
                  caseId,
                  activation.caseIdPrefix(),
                  activation.maximumCases(),
                  activation.fixtureSetId(),
                  activation.fixtureSetHash()));
      if (result == ReservationResult.RESERVED
          || result == ReservationResult.ALREADY_RESERVED_IDENTICALLY) {
        return true;
      }
      if (result != ReservationResult.SLOT_CONFLICT
          && result != ReservationResult.NOT_RESERVED) {
        return false;
      }
    }
    return false;
  }

  private boolean reservationExists(String activation, String tenant, String caseId) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (PreparedStatement statement =
            connection.prepareStatement(
                """
                select 1 from target_e2e_case_reservation
                 where activation_id = ? and tenant_surrogate = ? and case_id = ?
                """)) {
      statement.setString(1, activation);
      statement.setString(2, tenant);
      statement.setString(3, caseId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    } catch (SQLException failure) {
      throw persistenceFailure("target case reservation lookup failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private static AdmissionActivation lockAdmissionActivation(
      Connection connection, AdmissionRequest request) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            select isolated_domain_db_binding_hash, expires_at
              from target_e2e_activation activation
             where activation.activation_id = ?
               and activation.manifest_hash = ?
               and activation.tenant_surrogate = ?
               and activation.lifecycle_status = 'ACTIVE'
               and 'INTAKE' = any(activation.allowed_room_types)
               and exists (
                    select 1 from target_e2e_case_reservation reservation
                     where reservation.activation_id = activation.activation_id
                       and reservation.tenant_surrogate = activation.tenant_surrogate
                       and reservation.case_id = ?)
             for share
            """)) {
      statement.setString(1, request.activationId());
      statement.setString(2, request.manifestHash());
      statement.setString(3, request.tenantSurrogate());
      statement.setString(4, request.caseId());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        return new AdmissionActivation(
            result.getString("isolated_domain_db_binding_hash"),
            result.getTimestamp("expires_at").toInstant());
      }
    }
  }

  private static AdmissionReceipt findAdmission(
      Connection connection, AdmissionRequest request, String envelopeHash) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            select activation_manifest_hash, command_hash, command_envelope_hash,
                   room_epoch, room_fencing_token, admitted_at
              from target_e2e_command_admission
             where activation_id = ? and command_id = ?
             for update
            """)) {
      statement.setString(1, request.activationId());
      statement.setString(2, request.commandId());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        boolean exact =
            request.manifestHash().equals(result.getString("activation_manifest_hash"))
                && request.payloadSha256().equals(result.getString("command_hash"))
                && envelopeHash.equals(result.getString("command_envelope_hash"))
                && request.roomEpoch() == result.getLong("room_epoch")
                && request.roomFencingToken() == result.getLong("room_fencing_token");
        if (!exact) {
          throw new IllegalStateException("target Intake command admission replay conflicts");
        }
        return new AdmissionReceipt(
            request.activationId(),
            request.manifestHash(),
            request.commandId(),
            request.roomEpoch(),
            request.roomFencingToken(),
            result.getTimestamp("admitted_at").toInstant(),
            true);
      }
    }
  }

  private static String admissionEnvelopeHash(AdmissionRequest request) {
    return sha256(
        String.join(
            "\n",
            "target-e2e-intake-admission.v1",
            request.executionLane(),
            request.activationId(),
            request.manifestHash(),
            request.tenantSurrogate(),
            request.caseId(),
            Long.toString(request.roomEpoch()),
            Long.toString(request.roomFencingToken()),
            Long.toString(request.processRevision()),
            request.commandId(),
            request.payloadSha256()));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static String requireActivationId(String value) {
    if (value == null || !value.matches("p9act\\.v1\\.[0-9a-f]{32}")) {
      throw new IllegalArgumentException("activationId is invalid");
    }
    return value;
  }

  private static String compactUuid() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static void rollback(Connection connection, Throwable failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private static void commitOwnedTransaction(Connection connection, boolean callerTransaction)
      throws SQLException {
    if (!callerTransaction) {
      connection.commit();
    }
  }

  private static void rollbackOwnedTransaction(
      Connection connection, boolean callerTransaction, Throwable failure) {
    if (!callerTransaction) {
      rollback(connection, failure);
    }
  }

  private static void restoreAutoCommit(Connection connection, boolean restore) {
    if (!restore) {
      return;
    }
    try {
      connection.setAutoCommit(true);
    } catch (SQLException failure) {
      throw persistenceFailure("target Intake connection reset failed", failure);
    }
  }

  private static IllegalStateException persistenceFailure(String message, Throwable failure) {
    return failure instanceof IllegalStateException stateFailure
        ? stateFailure
        : new IllegalStateException(message, failure);
  }

  private record ActivationRow(
      String activationId,
      String manifestHash,
      String environmentId,
      long environmentGeneration,
      String tenantSurrogate,
      Instant expiresAt,
      String caseScopeMode,
      String caseIdPrefix,
      int maximumCases,
      String fixtureSetId,
      String fixtureSetHash,
      String caseBuildId,
      String controlBuildId,
      String agentBuildId,
      String graphKey,
      String graphVersion,
      String graphCheckpointSchemaVersion,
      String graphBindingHash,
      String graphCodeBuildId,
      String isolatedDomainDbBindingHash) {}

  private record AdmissionActivation(String isolatedDomainDbBindingHash, Instant expiresAt) {}
}
