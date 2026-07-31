package com.example.dispute.workflow.targete2e.artifact;

import com.example.dispute.casecore.application.ImportedCaseIdFactory;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Allocates a synthetic case ID and its activation reservation in the import transaction.
 *
 * <p>The slot is part of the signed activation scope. It is therefore selected from the durable
 * reservation facts, rather than from a process-local counter or random UUID suffix.
 */
final class TargetE2eSyntheticCaseIdFactory implements ImportedCaseIdFactory {

  private static final String LOCK_SCOPE_SQL = """
      select environment_id, environment_generation, tenant_surrogate, case_scope_hash,
             case_scope_mode, synthetic_case_id_prefix, synthetic_max_cases,
             synthetic_fixture_set_id, synthetic_fixture_set_hash, lifecycle_status, expires_at
        from target_e2e_activation
       where activation_id = ?
       for update
      """;

  private static final String NEXT_SLOT_SQL = """
      select candidate.slot_number
        from generate_series(1, ?) candidate(slot_number)
       where not exists (
             select 1
               from target_e2e_case_reservation reservation
              where reservation.activation_id = ?
                and reservation.slot_number = candidate.slot_number
       )
       order by candidate.slot_number
       limit 1
      """;

  private static final String INSERT_RESERVATION_SQL = """
      insert into target_e2e_case_reservation (
          reservation_id, activation_id, environment_id, environment_generation,
          tenant_surrogate, reservation_kind, slot_number, case_id, case_scope_hash,
          fixture_set_id, fixture_set_hash, fixture_bytes_canonical_hash,
          contains_real_case_or_party_data, external_effects_allowed, reserved_at
      ) values (?, ?, ?, ?, ?, 'ISOLATED_SYNTHETIC_NEW_CASE', ?, ?, ?, ?, ?, ?,
                false, false, clock_timestamp())
      """;

  private final DataSource dataSource;
  private final String activationId;
  private final Clock clock;

  TargetE2eSyntheticCaseIdFactory(DataSource dataSource, String activationId, Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    if (activationId == null || !activationId.matches("p9act\\.v1\\.[0-9a-f]{32}")) {
      throw new IllegalArgumentException("target E2E activationId is invalid");
    }
    this.activationId = activationId;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public String nextCaseId() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("target E2E synthetic case allocation requires an import transaction");
    }
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      Scope scope = lockScope(connection);
      int slot = nextSlot(connection, scope.maximumCases());
      String caseId = scope.caseIdPrefix() + slot;
      insertReservation(connection, scope, slot, caseId);
      return caseId;
    } catch (SQLException failure) {
      throw new IllegalStateException("target E2E synthetic case allocation failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private Scope lockScope(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(LOCK_SCOPE_SQL)) {
      statement.setString(1, activationId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw activationUnavailable("NOT_REGISTERED", null);
        }
        java.sql.Timestamp expiresAt = result.getTimestamp("expires_at");
        if (expiresAt == null) {
          throw activationUnavailable("EXPIRY_MISSING", null);
        }
        Scope scope = new Scope(
            result.getString("environment_id"),
            result.getLong("environment_generation"),
            result.getString("tenant_surrogate"),
            result.getString("case_scope_hash"),
            result.getString("case_scope_mode"),
            result.getString("synthetic_case_id_prefix"),
            result.getInt("synthetic_max_cases"),
            result.getString("synthetic_fixture_set_id"),
            result.getString("synthetic_fixture_set_hash"),
            result.getString("lifecycle_status"),
            expiresAt.toInstant());
        if (!scope.expiresAt().isAfter(clock.instant())) {
          throw new BusinessException(
              ErrorCode.TARGET_E2E_ACTIVATION_EXPIRED,
              "target E2E activation has expired",
              Map.of(
                  "activation_id", activationId,
                  "expired_at", scope.expiresAt().toString()));
        }
        if (!"ACTIVE".equals(scope.lifecycleStatus())) {
          throw activationUnavailable("LIFECYCLE_" + scope.lifecycleStatus(), scope.expiresAt());
        }
        if (!"ISOLATED_SYNTHETIC_NEW_CASES".equals(scope.mode())
            || scope.maximumCases() < 1
            || scope.maximumCases() > 16
            || scope.caseIdPrefix() == null
            || !scope.caseIdPrefix().matches("[A-Z][A-Z0-9_]{2,31}")) {
          throw activationUnavailable("SCOPE_MISMATCH", scope.expiresAt());
        }
        return scope;
      }
    }
  }

  private int nextSlot(Connection connection, int maximumCases) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(NEXT_SLOT_SQL)) {
      statement.setInt(1, maximumCases);
      statement.setString(2, activationId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new BusinessException(
              ErrorCode.TARGET_E2E_CASE_CAPACITY_EXHAUSTED,
              "target E2E synthetic case capacity is exhausted",
              Map.of("activation_id", activationId, "maximum_cases", maximumCases));
        }
        return result.getInt(1);
      }
    }
  }

  private BusinessException activationUnavailable(String reason, Instant expiresAt) {
    Map<String, Object> details = new java.util.LinkedHashMap<>();
    details.put("activation_id", activationId);
    details.put("reason", reason);
    if (expiresAt != null) {
      details.put("expires_at", expiresAt.toString());
    }
    return new BusinessException(
        ErrorCode.TARGET_E2E_ACTIVATION_UNAVAILABLE,
        "target E2E activation is unavailable",
        details);
  }

  private void insertReservation(Connection connection, Scope scope, int slot, String caseId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_RESERVATION_SQL)) {
      int index = 1;
      statement.setString(index++, "p9case.v1." + UUID.randomUUID().toString().replace("-", ""));
      statement.setString(index++, activationId);
      statement.setString(index++, scope.environmentId());
      statement.setLong(index++, scope.environmentGeneration());
      statement.setString(index++, scope.tenantSurrogate());
      statement.setInt(index++, slot);
      statement.setString(index++, caseId);
      statement.setString(index++, scope.caseScopeHash());
      statement.setString(index++, scope.fixtureSetId());
      statement.setString(index++, scope.fixtureSetHash());
      statement.setString(index, scope.fixtureSetHash());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("target E2E synthetic case reservation was not inserted");
      }
    }
  }

  private record Scope(
      String environmentId,
      long environmentGeneration,
      String tenantSurrogate,
      String caseScopeHash,
      String mode,
      String caseIdPrefix,
      int maximumCases,
      String fixtureSetId,
      String fixtureSetHash,
      String lifecycleStatus,
      java.time.Instant expiresAt) {}
}
