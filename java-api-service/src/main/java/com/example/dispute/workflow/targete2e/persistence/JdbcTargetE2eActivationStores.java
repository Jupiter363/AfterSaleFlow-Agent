package com.example.dispute.workflow.targete2e.persistence;

import com.example.dispute.workflow.targete2e.DrainAcceptedCommand;
import com.example.dispute.workflow.targete2e.TargetE2eActivationCaseLedger;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.BuildBindings;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.CaseScope;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.DatabaseIdentity;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.ExplicitCaseIds;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.GraphBinding;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.ImageDigests;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.IsolatedSyntheticNewCases;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.MeasuredAuthorityFacts;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.SyntheticFixtureDeployment;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore;
import com.example.dispute.workflow.targete2e.TargetE2eActivationReplayStore;
import com.example.dispute.workflow.targete2e.TargetE2eIsolatedDomainDbBinding;
import java.nio.ByteBuffer;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** PostgreSQL-backed activation replay, case reservation, and lifecycle authority. */
public final class JdbcTargetE2eActivationStores
    implements TargetE2eActivationReplayStore,
        TargetE2eActivationCaseLedger,
        TargetE2eActivationLifecycleStore {

  private static final String ACTIVATION_SELECT =
      """
      select environment_id, environment_generation, activation_id, manifest_hash,
             expires_at, lifecycle_status, lifecycle_changed_at, registered_at,
             drain_only_at, drained_at
        from target_e2e_activation
       where activation_id = ?
       for update
      """;

  private final DataSource dataSource;
  private final Clock clock;
  private final TargetE2EActivationLedger ledger;

  public JdbcTargetE2eActivationStores(DataSource dataSource, Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ledger = new TargetE2EActivationLedger(dataSource, clock);
  }

  @Override
  public RegistrationResult registerOrAttach(Registration registration) {
    Objects.requireNonNull(registration, "registration");
    try {
      TargetE2EActivationLedger.RegistrationDisposition disposition =
          ledger.registerOrAttach(toLedgerRegistration(registration)).disposition();
      return disposition == TargetE2EActivationLedger.RegistrationDisposition.REGISTERED
          ? RegistrationResult.REGISTERED
          : RegistrationResult.ATTACHED_EXISTING;
    } catch (TargetE2EPersistenceException failure) {
      if ("NONCE_REPLAY_OR_BINDING_CONFLICT".equals(failure.code())) {
        return RegistrationResult.CONFLICT;
      }
      RegistrationResult generation = generationFailure(registration);
      if (generation != null) {
        return generation;
      }
      throw failure;
    }
  }

  @Override
  public RegistrationResult attachExistingForDrain(Registration registration) {
    Objects.requireNonNull(registration, "registration");
    String scopeHash = caseScopeHash(registration.bindings().caseScope());
    String bindingHash = bindingSetHash(registration.bindings());
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                select activation.activation_id, activation.nonce,
                       activation.environment_id, activation.environment_generation,
                       activation.manifest_hash, activation.candidate_sha,
                       activation.tenant_surrogate, activation.case_scope_hash,
                       activation.binding_set_hash, watermark.highest_generation,
                       watermark.highest_activation_id
                  from target_e2e_activation activation
                  left join target_e2e_environment_generation_watermark watermark
                    on watermark.environment_id = activation.environment_id
                 where activation.activation_id = ? or activation.nonce = ?
                 order by activation.activation_id
                """)) {
      statement.setString(1, registration.activationId());
      statement.setString(2, registration.nonce());
      List<DrainAttachment> rows = new ArrayList<>();
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          rows.add(
              new DrainAttachment(
                  result.getString("activation_id"),
                  result.getString("nonce"),
                  result.getString("environment_id"),
                  result.getLong("environment_generation"),
                  result.getString("manifest_hash"),
                  result.getString("candidate_sha"),
                  result.getString("tenant_surrogate"),
                  result.getString("case_scope_hash"),
                  result.getString("binding_set_hash"),
                  result.getObject("highest_generation", Long.class),
                  result.getString("highest_activation_id")));
        }
      }
      if (rows.size() != 1) {
        return RegistrationResult.CONFLICT;
      }
      DrainAttachment row = rows.getFirst();
      if (row.highestGeneration() == null) {
        return RegistrationResult.CONFLICT;
      }
      if (registration.environmentGeneration() < row.highestGeneration()) {
        return RegistrationResult.ENVIRONMENT_GENERATION_STALE;
      }
      if (registration.environmentGeneration() != row.highestGeneration()
          || !registration.activationId().equals(row.highestActivationId())) {
        return RegistrationResult.ENVIRONMENT_GENERATION_CONFLICT;
      }
      boolean exact =
          registration.activationId().equals(row.activationId())
              && registration.nonce().equals(row.nonce())
              && registration.environmentId().equals(row.environmentId())
              && registration.environmentGeneration() == row.environmentGeneration()
              && registration.manifestHash().equals(row.manifestHash())
              && registration.bindings().candidateSha().equals(row.candidateSha())
              && registration.bindings().tenantSurrogate().equals(row.tenantSurrogate())
              && scopeHash.equals(row.caseScopeHash())
              && bindingHash.equals(row.bindingSetHash());
      return exact ? RegistrationResult.ATTACHED_EXISTING : RegistrationResult.CONFLICT;
    } catch (SQLException failure) {
      throw persistenceFailure("ACTIVATION_DRAIN_ATTACH_FAILED", failure);
    }
  }

  @Override
  public ReservationResult apply(Action action, Reservation reservation) {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(reservation, "reservation");
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
        lockReservationKeys(connection, reservation);
        CaseActivation activation = lockCaseActivation(connection, reservation.activationId());
        if (!activation.matches(reservation)) {
          commitOwnedTransaction(connection, callerTransaction);
          return ReservationResult.SLOT_CONFLICT;
        }
        PersistedReservation existing =
            findReservation(connection, reservation.activationId(), reservation.slotNumber());
        if (existing != null) {
          commitOwnedTransaction(connection, callerTransaction);
          return existing.matches(reservation)
              ? ReservationResult.ALREADY_RESERVED_IDENTICALLY
              : ReservationResult.SLOT_CONFLICT;
        }
        if (action == Action.REQUIRE_EXISTING) {
          commitOwnedTransaction(connection, callerTransaction);
          return ReservationResult.NOT_RESERVED;
        }
        if (!"ACTIVE".equals(activation.lifecycleStatus())
            || !activation.expiresAt().isAfter(clock.instant())) {
          commitOwnedTransaction(connection, callerTransaction);
          return ReservationResult.NOT_RESERVED;
        }
        if (caseIdClaimed(connection, reservation.caseId())) {
          commitOwnedTransaction(connection, callerTransaction);
          return ReservationResult.GENERATED_CASE_ID_GLOBAL_CONFLICT;
        }
        if (reservationCount(connection, reservation.activationId()) >= reservation.maxCases()) {
          commitOwnedTransaction(connection, callerTransaction);
          return ReservationResult.CAPACITY_EXHAUSTED;
        }
        insertReservation(connection, activation, reservation);
        commitOwnedTransaction(connection, callerTransaction);
        return ReservationResult.RESERVED;
      } catch (RuntimeException | SQLException failure) {
        rollbackOwnedTransaction(connection, callerTransaction, failure);
        if (failure instanceof TargetE2EPersistenceException persistenceFailure) {
          throw persistenceFailure;
        }
        throw persistenceFailure("CASE_RESERVATION_FAILED", failure);
      }
    } catch (SQLException failure) {
      throw persistenceFailure("CASE_RESERVATION_CONNECTION_FAILED", failure);
    } finally {
      restoreAutoCommit(connection, restoreAutoCommit);
      DataSourceUtils.releaseConnection(connection, dataSource);
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
      throw persistenceFailure("CASE_RESERVATION_CONNECTION_RESET_FAILED", failure);
    }
  }

  @Override
  public LifecycleState refresh(ActivationIdentity identity, Instant expiresAt, Instant now) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(now, "now");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        LifecycleActivation activation = lockActivation(connection, identity, expiresAt);
        LifecycleState state = activation.state();
        if (state == LifecycleState.REGISTERED) {
          Instant activatedAt = activation.lifecycleChangedAt();
          transition(
              connection,
              identity.activationId(),
              LifecycleState.REGISTERED,
              LifecycleState.ACTIVE,
              "activated_at",
              activatedAt);
          state = LifecycleState.ACTIVE;
        }
        if (state == LifecycleState.ACTIVE && !now.isBefore(expiresAt)) {
          Instant drainOnlyAt = latest(now, expiresAt, activation.lifecycleChangedAt());
          transition(
              connection,
              identity.activationId(),
              LifecycleState.ACTIVE,
              LifecycleState.DRAIN_ONLY,
              "drain_only_at",
              drainOnlyAt);
          state = LifecycleState.DRAIN_ONLY;
        }
        connection.commit();
        return state;
      } catch (RuntimeException | SQLException failure) {
        rollback(connection, failure);
        if (failure instanceof TargetE2EPersistenceException persistenceFailure) {
          throw persistenceFailure;
        }
        throw persistenceFailure("ACTIVATION_REFRESH_FAILED", failure);
      }
    } catch (SQLException failure) {
      throw persistenceFailure("ACTIVATION_REFRESH_CONNECTION_FAILED", failure);
    }
  }

  @Override
  public boolean hasAcceptedCommandBeforeExpiry(
      ActivationIdentity identity, DrainAcceptedCommand command, Instant expiresAt) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(expiresAt, "expiresAt");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                select 1
                  from target_e2e_activation activation
                  join target_e2e_command_admission admission
                    on admission.activation_id = activation.activation_id
                 where activation.environment_id = ?
                   and activation.environment_generation = ?
                   and activation.activation_id = ?
                   and activation.manifest_hash = ?
                   and activation.expires_at = ?
                   and admission.command_id = ?
                   and admission.command_hash = ?
                   and admission.command_envelope_hash = ?
                   and admission.room_epoch = ?
                   and admission.room_fencing_token = ?
                   and admission.admitted_at = ?
                   and admission.admitted_at < activation.expires_at
                """)) {
      int index = 1;
      statement.setString(index++, identity.environmentId());
      statement.setLong(index++, identity.environmentGeneration());
      statement.setString(index++, identity.activationId());
      statement.setString(index++, identity.manifestHash());
      statement.setTimestamp(index++, Timestamp.from(expiresAt));
      statement.setString(index++, command.commandId());
      statement.setString(index++, command.commandHash());
      statement.setString(index++, command.commandEnvelopeHash());
      statement.setLong(index++, command.roomEpoch());
      statement.setLong(index++, command.fencingToken());
      statement.setTimestamp(index, Timestamp.from(command.admittedAt()));
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    } catch (SQLException failure) {
      throw persistenceFailure("ACCEPTED_COMMAND_PROOF_FAILED", failure);
    }
  }

  @Override
  public TransitionResult markDrained(ActivationIdentity identity, DrainCompletionProof proof) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(proof, "proof");
    if (proof.unresolvedAcceptedWork() != 0) {
      return TransitionResult.REJECTED_UNRESOLVED_WORK;
    }
    if (proof.attachedReplicas() != 0) {
      return TransitionResult.REJECTED_REPLICAS_ATTACHED;
    }
    if (!proof.evidenceSealed()) {
      return TransitionResult.REJECTED_EVIDENCE_NOT_SEALED;
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        LifecycleActivation activation = lockActivation(connection, identity, null);
        if (activation.state() == LifecycleState.DRAINED) {
          connection.commit();
          return TransitionResult.ALREADY_IN_TARGET_STATE;
        }
        if (activation.state() != LifecycleState.DRAIN_ONLY) {
          connection.commit();
          return TransitionResult.REJECTED_WRONG_STATE;
        }
        if (activation.drainOnlyAt() == null
            || proof.completedAt().isBefore(activation.drainOnlyAt())
            || proof.completedAt().isBefore(activation.lifecycleChangedAt())) {
          connection.commit();
          return TransitionResult.REJECTED_TIMESTAMP_ORDER;
        }
        if (hasUnresolvedAcceptedWork(connection, identity.activationId())) {
          connection.commit();
          return TransitionResult.REJECTED_UNRESOLVED_WORK;
        }
        transition(
            connection,
            identity.activationId(),
            LifecycleState.DRAIN_ONLY,
            LifecycleState.DRAINED,
            "drained_at",
            proof.completedAt());
        connection.commit();
        return TransitionResult.TRANSITIONED;
      } catch (RuntimeException | SQLException failure) {
        rollback(connection, failure);
        if (failure instanceof TargetE2EPersistenceException persistenceFailure) {
          throw persistenceFailure;
        }
        throw persistenceFailure("ACTIVATION_DRAIN_FAILED", failure);
      }
    } catch (SQLException failure) {
      throw persistenceFailure("ACTIVATION_DRAIN_CONNECTION_FAILED", failure);
    }
  }

  @Override
  public TransitionResult revokeTerminal(ActivationIdentity identity, Instant revokedAt) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(revokedAt, "revokedAt");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        LifecycleActivation activation = lockActivation(connection, identity, null);
        if (activation.state() == LifecycleState.REVOKED_TERMINAL) {
          connection.commit();
          return TransitionResult.ALREADY_IN_TARGET_STATE;
        }
        if (activation.state() != LifecycleState.DRAINED) {
          connection.commit();
          return TransitionResult.REJECTED_WRONG_STATE;
        }
        if (activation.drainedAt() == null
            || !revokedAt.isAfter(activation.drainedAt())
            || revokedAt.isBefore(activation.lifecycleChangedAt())) {
          connection.commit();
          return TransitionResult.REJECTED_TIMESTAMP_ORDER;
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                """
                update target_e2e_activation
                   set lifecycle_status = 'REVOKED_TERMINAL',
                       lifecycle_changed_at = ?, revoked_at = ?,
                       all_replicas_detached = true, evidence_sealed = true
                 where activation_id = ? and lifecycle_status = 'DRAINED'
                """)) {
          statement.setTimestamp(1, Timestamp.from(revokedAt));
          statement.setTimestamp(2, Timestamp.from(revokedAt));
          statement.setString(3, identity.activationId());
          requireUpdated(statement, "activation is no longer DRAINED");
        }
        connection.commit();
        return TransitionResult.TRANSITIONED;
      } catch (RuntimeException | SQLException failure) {
        rollback(connection, failure);
        if (failure instanceof TargetE2EPersistenceException persistenceFailure) {
          throw persistenceFailure;
        }
        throw persistenceFailure("ACTIVATION_REVOKE_FAILED", failure);
      }
    } catch (SQLException failure) {
      throw persistenceFailure("ACTIVATION_REVOKE_CONNECTION_FAILED", failure);
    }
  }

  private TargetE2EActivationLedger.ActivationRegistration toLedgerRegistration(
      Registration registration) {
    BindingSnapshot bindings = registration.bindings();
    CaseScope caseScope = bindings.caseScope();
    String scopeHash = caseScopeHash(caseScope);
    TargetE2EActivationLedger.CaseScope persistedScope;
    if (caseScope instanceof ExplicitCaseIds explicit) {
      persistedScope =
          new TargetE2EActivationLedger.ExplicitCaseScope(
              scopeHash, explicit.allowedCaseIds().stream().sorted().toList());
    } else {
      IsolatedSyntheticNewCases synthetic = (IsolatedSyntheticNewCases) caseScope;
      SyntheticFixtureDeployment fixture = bindings.syntheticFixtureDeployment().orElseThrow();
      persistedScope =
          new TargetE2EActivationLedger.SyntheticCaseScope(
              scopeHash,
              synthetic.caseIdPrefix(),
              synthetic.maxCases(),
              synthetic.fixtureSetId(),
              synthetic.fixtureSetHash(),
              fixture.measuredCanonicalHash());
    }
    BuildBindings builds = bindings.buildBindings();
    GraphBinding graph = bindings.graphBinding();
    ImageDigests images = bindings.imageDigests();
    DatabaseIdentity domain = bindings.databaseIdentities().domain();
    DatabaseIdentity graphDatabase = bindings.databaseIdentities().graph();
    return new TargetE2EActivationLedger.ActivationRegistration(
        registration.activationId(),
        registration.manifestHash(),
        registration.environmentId(),
        registration.environmentGeneration(),
        bindings.candidateSha(),
        registration.nonce(),
        bindings.tenantSurrogate(),
        registration.issuedAt(),
        registration.expiresAt(),
        persistedScope,
        bindings.allowedRoomTypes().stream().map(Enum::name).sorted().toList(),
        new TargetE2EActivationLedger.BuildBindings(
            builds.caseBuildId(), builds.controlBuildId(), builds.agentBuildId()),
        new TargetE2EActivationLedger.GraphBinding(
            graph.key(),
            graph.version(),
            graph.checkpointSchemaVersion(),
            graph.bindingHash(),
            graph.codeBuildId()),
        new TargetE2EActivationLedger.ImageDigests(
            images.javaApi(),
            images.temporalControlWorker(),
            images.temporalAgentWorker(),
            images.pythonAgent(),
            images.frontend()),
        bindings.temporalNamespace(),
        isolatedDomainDatabaseBinding(registration, domain),
        databaseBinding(graphDatabase),
        bindingSetHash(bindings));
  }

  private static TargetE2EActivationLedger.DatabaseBinding isolatedDomainDatabaseBinding(
      Registration registration, DatabaseIdentity identity) {
    return new TargetE2EActivationLedger.DatabaseBinding(
        identity.clusterIdentity(),
        identity.databaseIdentity(),
        identity.runtimePrincipalIdentity(),
        TargetE2eIsolatedDomainDbBinding.hash(
            registration.environmentId(),
            registration.environmentGeneration(),
            registration.activationId(),
            identity.clusterIdentity(),
            identity.databaseIdentity(),
            identity.runtimePrincipalIdentity()));
  }

  private static TargetE2EActivationLedger.DatabaseBinding databaseBinding(
      DatabaseIdentity identity) {
    return new TargetE2EActivationLedger.DatabaseBinding(
        identity.clusterIdentity(),
        identity.databaseIdentity(),
        identity.runtimePrincipalIdentity(),
        hash(
            "target-e2e-database-binding.v1",
            identity.clusterIdentity(),
            identity.databaseIdentity(),
            identity.runtimePrincipalIdentity()));
  }

  private RegistrationResult generationFailure(Registration registration) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                select highest_generation, highest_activation_id
                  from target_e2e_environment_generation_watermark
                 where environment_id = ?
                """)) {
      statement.setString(1, registration.environmentId());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        long highest = result.getLong("highest_generation");
        if (registration.environmentGeneration() < highest) {
          return RegistrationResult.ENVIRONMENT_GENERATION_STALE;
        }
        if (registration.environmentGeneration() == highest
            && !registration.activationId().equals(result.getString("highest_activation_id"))) {
          return RegistrationResult.ENVIRONMENT_GENERATION_CONFLICT;
        }
        return null;
      }
    } catch (SQLException failure) {
      throw persistenceFailure("ACTIVATION_GENERATION_QUERY_FAILED", failure);
    }
  }

  private static void lockReservationKeys(Connection connection, Reservation reservation)
      throws SQLException {
    List<String> keys =
        List.of(
                "target-e2e-activation:" + reservation.activationId(),
                "target-e2e-case:" + reservation.caseId())
            .stream()
            .sorted(Comparator.naturalOrder())
            .toList();
    for (String key : keys) {
      try (PreparedStatement statement =
          connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        statement.setString(1, key);
        statement.executeQuery().close();
      }
    }
  }

  private static CaseActivation lockCaseActivation(Connection connection, String activationId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            select environment_id, environment_generation, tenant_surrogate,
                   case_scope_hash, case_scope_mode, synthetic_case_id_prefix,
                   synthetic_max_cases, synthetic_fixture_set_id,
                   synthetic_fixture_set_hash, lifecycle_status, expires_at
              from target_e2e_activation
             where activation_id = ?
             for update
            """)) {
      statement.setString(1, activationId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new TargetE2EPersistenceException(
              "ACTIVATION_NOT_FOUND", "case reservation activation is not registered");
        }
        return new CaseActivation(
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
            result.getTimestamp("expires_at").toInstant());
      }
    }
  }

  private static PersistedReservation findReservation(
      Connection connection, String activationId, int slotNumber) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            select environment_id, environment_generation, slot_number, case_id,
                   fixture_set_id, fixture_set_hash
              from target_e2e_case_reservation
             where activation_id = ? and slot_number = ?
            """)) {
      statement.setString(1, activationId);
      statement.setInt(2, slotNumber);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        return new PersistedReservation(
            result.getString("environment_id"),
            result.getLong("environment_generation"),
            result.getInt("slot_number"),
            result.getString("case_id"),
            result.getString("fixture_set_id"),
            result.getString("fixture_set_hash"));
      }
    }
  }

  private static boolean caseIdClaimed(Connection connection, String caseId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("select 1 from target_e2e_case_id_claim where case_id = ?")) {
      statement.setString(1, caseId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static int reservationCount(Connection connection, String activationId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select count(*) from target_e2e_case_reservation where activation_id = ?")) {
      statement.setString(1, activationId);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static void insertReservation(
      Connection connection, CaseActivation activation, Reservation reservation) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            insert into target_e2e_case_reservation (
                reservation_id, activation_id, environment_id, environment_generation,
                tenant_surrogate, reservation_kind, slot_number, case_id,
                case_scope_hash, fixture_set_id, fixture_set_hash,
                fixture_bytes_canonical_hash, contains_real_case_or_party_data,
                external_effects_allowed, reserved_at
            ) values (?, ?, ?, ?, ?, 'ISOLATED_SYNTHETIC_NEW_CASE', ?, ?, ?, ?, ?, ?,
                      false, false, clock_timestamp())
            """)) {
      int index = 1;
      statement.setString(index++, "p9case.v1." + compactUuid());
      statement.setString(index++, reservation.activationId());
      statement.setString(index++, activation.environmentId());
      statement.setLong(index++, activation.environmentGeneration());
      statement.setString(index++, activation.tenantSurrogate());
      statement.setInt(index++, reservation.slotNumber());
      statement.setString(index++, reservation.caseId());
      statement.setString(index++, activation.caseScopeHash());
      statement.setString(index++, reservation.fixtureSetId());
      statement.setString(index++, reservation.fixtureSetHash());
      statement.setString(index, reservation.fixtureSetHash());
      statement.executeUpdate();
    }
  }

  private static LifecycleActivation lockActivation(
      Connection connection, ActivationIdentity identity, Instant expectedExpiresAt)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ACTIVATION_SELECT)) {
      statement.setString(1, identity.activationId());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new TargetE2EPersistenceException(
              "ACTIVATION_NOT_FOUND", "activation lifecycle identity is not registered");
        }
        Instant expiresAt = result.getTimestamp("expires_at").toInstant();
        boolean exact =
            identity.environmentId().equals(result.getString("environment_id"))
                && identity.environmentGeneration() == result.getLong("environment_generation")
                && identity.manifestHash().equals(result.getString("manifest_hash"))
                && (expectedExpiresAt == null || expectedExpiresAt.equals(expiresAt));
        if (!exact) {
          throw new TargetE2EPersistenceException(
              "ACTIVATION_IDENTITY_CONFLICT", "activation lifecycle identity does not match");
        }
        return new LifecycleActivation(
            LifecycleState.valueOf(result.getString("lifecycle_status")),
            expiresAt,
            result.getTimestamp("lifecycle_changed_at").toInstant(),
            instant(result, "drain_only_at"),
            instant(result, "drained_at"));
      }
    }
  }

  private static void transition(
      Connection connection,
      String activationId,
      LifecycleState expected,
      LifecycleState target,
      String timestampColumn,
      Instant timestamp)
      throws SQLException {
    if (!List.of("activated_at", "drain_only_at", "drained_at").contains(timestampColumn)) {
      throw new IllegalArgumentException("unsupported lifecycle timestamp column");
    }
    String sql =
        "update target_e2e_activation set lifecycle_status = ?, lifecycle_changed_at = ?, "
            + timestampColumn
            + " = ? where activation_id = ? and lifecycle_status = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, target.name());
      statement.setTimestamp(2, Timestamp.from(timestamp));
      statement.setTimestamp(3, Timestamp.from(timestamp));
      statement.setString(4, activationId);
      statement.setString(5, expected.name());
      requireUpdated(statement, "activation lifecycle changed concurrently");
    }
  }

  private static void requireUpdated(PreparedStatement statement, String message)
      throws SQLException {
    if (statement.executeUpdate() != 1) {
      throw new TargetE2EPersistenceException("ACTIVATION_STATE_CONFLICT", message);
    }
  }

  private static boolean hasUnresolvedAcceptedWork(Connection connection, String activationId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            select 1
              from target_e2e_command_admission admission
              left join target_e2e_command_completion completion
                on completion.admission_id = admission.admission_id
             where admission.activation_id = ? and completion.admission_id is null
             limit 1
            """)) {
      statement.setString(1, activationId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static String caseScopeHash(CaseScope scope) {
    DigestWriter digest = new DigestWriter("target-e2e-case-scope.v1");
    if (scope instanceof ExplicitCaseIds explicit) {
      digest.add("EXPLICIT_CASE_IDS");
      explicit.allowedCaseIds().stream().sorted().forEach(digest::add);
    } else {
      IsolatedSyntheticNewCases synthetic = (IsolatedSyntheticNewCases) scope;
      digest.add("ISOLATED_SYNTHETIC_NEW_CASES");
      digest.add(synthetic.caseIdPrefix());
      digest.add(Integer.toString(synthetic.maxCases()));
      digest.add(synthetic.fixtureSetId());
      digest.add(synthetic.fixtureSetHash());
      digest.add(Boolean.toString(synthetic.containsRealCaseOrPartyData()));
      digest.add(Boolean.toString(synthetic.externalEffectsAllowed()));
    }
    return digest.finish();
  }

  private static String bindingSetHash(BindingSnapshot bindings) {
    DigestWriter digest = new DigestWriter("target-e2e-binding-set.v1");
    digest.add(bindings.candidateSha());
    digest.add(bindings.tenantSurrogate());
    digest.add(caseScopeHash(bindings.caseScope()));
    bindings.allowedRoomTypes().stream().map(Enum::name).sorted().forEach(digest::add);
    BuildBindings builds = bindings.buildBindings();
    digest.add(builds.caseBuildId());
    digest.add(builds.controlBuildId());
    digest.add(builds.agentBuildId());
    GraphBinding graph = bindings.graphBinding();
    digest.add(graph.key());
    digest.add(graph.version());
    digest.add(graph.checkpointSchemaVersion());
    digest.add(graph.bindingHash());
    digest.add(graph.codeBuildId());
    ImageDigests images = bindings.imageDigests();
    digest.add(images.javaApi());
    digest.add(images.temporalControlWorker());
    digest.add(images.temporalAgentWorker());
    digest.add(images.pythonAgent());
    digest.add(images.frontend());
    digest.add(bindings.temporalNamespace());
    addDatabase(digest, bindings.databaseIdentities().domain());
    addDatabase(digest, bindings.databaseIdentities().graph());
    bindings
        .syntheticFixtureDeployment()
        .ifPresentOrElse(
            fixture -> {
              digest.add(fixture.fixtureSetId());
              digest.add(fixture.readOnlyPathBinding());
              digest.add(fixture.measuredCanonicalHash());
            },
            () -> digest.add("NO_SYNTHETIC_FIXTURE"));
    addAuthority(digest, bindings.authorityFacts());
    return digest.finish();
  }

  private static void addDatabase(DigestWriter digest, DatabaseIdentity database) {
    digest.add(database.clusterIdentity());
    digest.add(database.databaseIdentity());
    digest.add(database.runtimePrincipalIdentity());
  }

  private static void addAuthority(DigestWriter digest, MeasuredAuthorityFacts authority) {
    digest.add(Boolean.toString(authority.isolatedDeployment()));
    digest.add(authority.environmentClass());
    digest.add(authority.graphOutputAuthority());
    digest.add(Boolean.toString(authority.graphDomainCredentialsPresent()));
    digest.add(Boolean.toString(authority.graphDomainPrivilegesPresent()));
    digest.add(Boolean.toString(authority.graphDomainWriteAllowed()));
    digest.add(authority.formalWriter());
    digest.add(Boolean.toString(authority.javaDomainCommitAllowed()));
    digest.add(Boolean.toString(authority.externalEffectsAllowed()));
    digest.add(Boolean.toString(authority.productionTrafficAllowed()));
    digest.add(Boolean.toString(authority.productionPromotionAuthority()));
    digest.add(Boolean.toString(authority.migrationPromotionAuthority()));
    digest.add(authority.formalCaseSelectorDefault());
    digest.add(authority.targetE2EActivationDefault());
  }

  private static String hash(String marker, String... values) {
    DigestWriter digest = new DigestWriter(marker);
    for (String value : values) {
      digest.add(value);
    }
    return digest.finish();
  }

  private static Instant latest(Instant first, Instant second, Instant third) {
    Instant latest = first.isAfter(second) ? first : second;
    return latest.isAfter(third) ? latest : third;
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static void rollback(Connection connection, Throwable failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private static TargetE2EPersistenceException persistenceFailure(
      String code, Throwable failure) {
    return new TargetE2EPersistenceException(code, failure.getMessage(), failure);
  }

  private static String compactUuid() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private record DrainAttachment(
      String activationId,
      String nonce,
      String environmentId,
      long environmentGeneration,
      String manifestHash,
      String candidateSha,
      String tenantSurrogate,
      String caseScopeHash,
      String bindingSetHash,
      Long highestGeneration,
      String highestActivationId) {}

  private record CaseActivation(
      String environmentId,
      long environmentGeneration,
      String tenantSurrogate,
      String caseScopeHash,
      String caseScopeMode,
      String caseIdPrefix,
      int maximumCases,
      String fixtureSetId,
      String fixtureSetHash,
      String lifecycleStatus,
      Instant expiresAt) {

    boolean matches(Reservation reservation) {
      return "ISOLATED_SYNTHETIC_NEW_CASES".equals(caseScopeMode)
          && environmentId.equals(reservation.environmentId())
          && environmentGeneration == reservation.environmentGeneration()
          && caseIdPrefix.equals(reservation.caseIdPrefix())
          && maximumCases == reservation.maxCases()
          && fixtureSetId.equals(reservation.fixtureSetId())
          && fixtureSetHash.equals(reservation.fixtureSetHash());
    }
  }

  private record PersistedReservation(
      String environmentId,
      long environmentGeneration,
      int slotNumber,
      String caseId,
      String fixtureSetId,
      String fixtureSetHash) {

    boolean matches(Reservation reservation) {
      return environmentId.equals(reservation.environmentId())
          && environmentGeneration == reservation.environmentGeneration()
          && slotNumber == reservation.slotNumber()
          && caseId.equals(reservation.caseId())
          && fixtureSetId.equals(reservation.fixtureSetId())
          && fixtureSetHash.equals(reservation.fixtureSetHash());
    }
  }

  private record LifecycleActivation(
      LifecycleState state,
      Instant expiresAt,
      Instant lifecycleChangedAt,
      Instant drainOnlyAt,
      Instant drainedAt) {}

  private static final class DigestWriter {

    private final MessageDigest digest;

    private DigestWriter(String marker) {
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException failure) {
        throw new IllegalStateException("SHA-256 is unavailable", failure);
      }
      add(marker);
    }

    private void add(String value) {
      byte[] bytes = Objects.requireNonNull(value, "hash value").getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      digest.update(bytes);
    }

    private String finish() {
      return HexFormat.of().formatHex(digest.digest());
    }
  }
}
