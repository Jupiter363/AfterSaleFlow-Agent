package com.example.dispute.workflow.runtime.persistence;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Framework-neutral PostgreSQL activation authority.
 *
 * <p>The database owns replay serialization, environment-generation high-water and scope checks.
 * This adapter owns transaction boundaries only for activation/control-ledger operations.
 */
public final class ProductionActivationLedger {

    private static final Set<String> ROOM_TYPES =
            Set.of("INTAKE", "EVIDENCE", "HEARING", "REVIEW");
    private static final String INSERT_ACTIVATION = """
            insert into production_runtime_activation (
                activation_id, contract_version, manifest_hash, execution_lane,
                environment_id, environment_generation, candidate_sha, nonce,
                tenant_surrogate, issued_at, expires_at, lifecycle_status,
                lifecycle_changed_at, case_scope_mode, case_scope_hash,
                explicit_case_count, synthetic_case_id_prefix, synthetic_max_cases,
                synthetic_fixture_set_id, synthetic_fixture_set_hash,
                synthetic_fixture_bytes_canonical_hash,
                contains_real_case_or_party_data, case_external_effects_allowed,
                allowed_room_types, case_build_id, control_build_id, agent_build_id,
                graph_key, graph_version, graph_checkpoint_schema_version,
                graph_binding_hash, graph_code_build_id, java_api_image_digest,
                temporal_control_worker_image_digest, temporal_agent_worker_image_digest,
                python_agent_image_digest, frontend_image_digest, temporal_namespace,
                domain_cluster_identity, domain_database_identity,
                domain_runtime_principal_identity, isolated_domain_db_binding_hash,
                graph_cluster_identity, graph_database_identity,
                graph_runtime_principal_identity, isolated_graph_db_binding_hash,
                binding_set_hash, graph_output_authority,
                graph_domain_credentials_present, graph_domain_write_allowed, formal_writer,
                java_domain_commit_allowed, external_effects_allowed,
                production_traffic_allowed, production_promotion_authority,
                migration_promotion_authority, production_formal_selector,
                production_production_runtime_activation, registered_at
            ) values (
                ?, 'production-runtime-activation.v1', ?, 'PRODUCTION',
                ?, ?, ?, ?, ?, ?, ?, 'REGISTERED', current_timestamp,
                ?, ?, ?, ?, ?, ?, ?, ?, false, false, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSAL_ONLY', false, false,
                'JAVA_FINALIZER_ONLY', true, false, false, false, false, 'LEGACY',
                'DISABLED', current_timestamp
            ) on conflict do nothing
            """;
    private static final String SELECT_CONFLICT = """
            select activation_id, nonce, environment_id, environment_generation,
                   manifest_hash, candidate_sha, tenant_surrogate, case_scope_hash,
                   binding_set_hash, lifecycle_status, expires_at
              from production_runtime_activation
             where activation_id = ? or nonce = ?
             order by activation_id
             for update
            """;
    private static final String INSERT_CASE = """
            insert into production_runtime_case_reservation (
                reservation_id, activation_id, environment_id, environment_generation,
                tenant_surrogate, reservation_kind, slot_number, case_id,
                case_scope_hash, fixture_set_id, fixture_set_hash, fixture_bytes_canonical_hash,
                contains_real_case_or_party_data, external_effects_allowed, reserved_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, false, current_timestamp)
            """;

    private final DataSource dataSource;
    private final Clock clock;

    public ProductionActivationLedger(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public RegistrationResult registerOrAttach(ActivationRegistration registration) {
        Objects.requireNonNull(registration, "registration must not be null");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int inserted = insertActivation(connection, registration);
                if (inserted == 1) {
                    insertExplicitCases(connection, registration);
                }
                PersistedGrant persisted = exactPersistedGrant(connection, registration);
                requireCurrentGeneration(connection, persisted);
                verifyExplicitCases(connection, registration);
                if (inserted == 0
                        && persisted.lifecycleStatus() == ActivationLifecycle.ACTIVE
                        && !persisted.expiresAt().isAfter(clock.instant())) {
                    enterDrainOnly(connection, persisted.activationId());
                    persisted = persisted.withLifecycle(ActivationLifecycle.DRAIN_ONLY);
                }
                RegistrationDisposition disposition = inserted == 1
                        ? RegistrationDisposition.REGISTERED
                        : attachDisposition(persisted);
                connection.commit();
                return new RegistrationResult(
                        disposition,
                        persisted.activationId(),
                        persisted.lifecycleStatus(),
                        persisted.expiresAt());
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                if (failure instanceof ProductionPersistenceException persistenceFailure) {
                    throw persistenceFailure;
                }
                throw registrationFailure(failure);
            }
        } catch (SQLException failure) {
            throw sqlFailure("ACTIVATION_CONNECTION_FAILED", failure);
        }
    }

    public CaseReservation reserveCase(String activationId, String generatedCaseId) {
        requireText(activationId, "activationId");
        requireText(generatedCaseId, "generatedCaseId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                LockedScope scope = lockScope(connection, activationId);
                CaseReservation existing = findReservation(connection, activationId, generatedCaseId);
                if (existing != null) {
                    connection.commit();
                    return existing;
                }
                if (hasReservationOrTombstone(connection, generatedCaseId)) {
                    throw new ProductionPersistenceException(
                            "CASE_ID_ALREADY_TOMBSTONED",
                            "generated case ID is permanently bound to another activation");
                }
                if (!"ACTIVE".equals(scope.lifecycleStatus())
                        || !scope.expiresAt().isAfter(clock.instant())) {
                    throw new ProductionPersistenceException(
                            "ACTIVATION_DRAIN_ONLY", "activation cannot reserve a new case");
                }
                if ("EXPLICIT_CASE_IDS".equals(scope.mode())) {
                    throw new ProductionPersistenceException(
                            "CASE_SCOPE_MISMATCH", "case ID is not in the explicit signed scope");
                }
                if (!generatedCaseId.startsWith(scope.prefix())) {
                    throw new ProductionPersistenceException(
                            "CASE_SCOPE_MISMATCH", "generated case ID does not match the exact prefix");
                }
                int slot = nextSyntheticSlot(connection, scope);
                String reservationId = "p9case.v1." + compactUuid();
                insertCase(
                        connection,
                        reservationId,
                        scope,
                        "ISOLATED_SYNTHETIC_NEW_CASE",
                        slot,
                        generatedCaseId,
                        scope.fixtureSetId(),
                        scope.fixtureSetHash(),
                        scope.fixtureSetHash());
                CaseReservation reserved = findReservation(connection, activationId, generatedCaseId);
                connection.commit();
                return reserved;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                if (failure instanceof ProductionPersistenceException persistenceFailure) {
                    throw persistenceFailure;
                }
                throw sqlFailure("CASE_RESERVATION_FAILED", failure);
            }
        } catch (SQLException failure) {
            throw sqlFailure("CASE_RESERVATION_CONNECTION_FAILED", failure);
        }
    }

    public CommandAdmissionResult admitCommand(CommandAdmission command) {
        Objects.requireNonNull(command, "command must not be null");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                CommandAdmissionResult admitted = admitCommand(connection, command);
                connection.commit();
                return admitted;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                if (failure instanceof ProductionPersistenceException persistenceFailure) {
                    throw persistenceFailure;
                }
                throw sqlFailure("COMMAND_ADMISSION_FAILED", failure);
            }
        } catch (SQLException failure) {
            throw sqlFailure("COMMAND_ADMISSION_CONNECTION_FAILED", failure);
        }
    }

    /**
     * Admits a command on the caller-owned transaction. This method never commits or rolls back.
     */
    public CommandAdmissionResult admitCommand(Connection transaction, CommandAdmission command) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(command, "command must not be null");
        try {
            requireCallerTransaction(transaction, "command admission");
            advisoryLock(transaction, command.activationId() + ':' + command.commandId());
            CommandActivation activation = lockCommandActivation(transaction, command.activationId());
            if (activation.lifecycle() == ActivationLifecycle.DRAINED
                    || activation.lifecycle() == ActivationLifecycle.REVOKED_TERMINAL) {
                throw new ProductionPersistenceException(
                        "COMMAND_ADMISSION_TERMINAL",
                        "terminal activation command evidence is query-only");
            }

            PersistedCommand existing = findCommand(
                    transaction, command.activationId(), command.commandId());
            if (existing != null) {
                requireSameCommand(command, existing);
                if (existing.completed()) {
                    throw new ProductionPersistenceException(
                            "COMMAND_ALREADY_COMPLETED",
                            "completed command evidence is query-only");
                }
                if (activation.lifecycle() == ActivationLifecycle.DRAIN_ONLY) {
                    if (!existing.admittedAt().isBefore(activation.expiresAt())) {
                        throw new ProductionPersistenceException(
                                "COMMAND_ADMISSION_AFTER_CUTOFF",
                                "DRAIN_ONLY permits only unfinished work admitted before cutoff");
                    }
                    return new CommandAdmissionResult(
                            CommandAdmissionDisposition.ALREADY_ADMITTED_DRAIN_ONLY,
                            existing.admissionId(),
                            existing.admittedAt());
                }
                requireActiveBeforeCutoff(activation);
                return new CommandAdmissionResult(
                        CommandAdmissionDisposition.ALREADY_ADMITTED_ACTIVE,
                        existing.admissionId(),
                        existing.admittedAt());
            }

            requireActiveBeforeCutoff(activation);
            String admissionId = "p9cmd.v1." + compactUuid();
            insertCommand(transaction, admissionId, command);
            PersistedCommand admitted = findCommand(
                    transaction, command.activationId(), command.commandId());
            return new CommandAdmissionResult(
                    CommandAdmissionDisposition.ADMITTED,
                    admitted.admissionId(),
                    admitted.admittedAt());
        } catch (SQLException failure) {
            throw sqlFailure("COMMAND_ADMISSION_FAILED", failure);
        }
    }

    /** Reads durable command evidence without granting execution authority. */
    public Optional<CommandAdmissionSnapshot> queryCommandAdmission(
            String activationId, String commandId) {
        requireText(activationId, "activationId");
        requireText(commandId, "commandId");
        try (Connection connection = dataSource.getConnection()) {
            return queryCommandAdmission(connection, activationId, commandId);
        } catch (SQLException failure) {
            throw sqlFailure("COMMAND_ADMISSION_QUERY_FAILED", failure);
        }
    }

    /** Reads durable command evidence on a caller-owned connection without authorizing execution. */
    public Optional<CommandAdmissionSnapshot> queryCommandAdmission(
            Connection connection, String activationId, String commandId) {
        Objects.requireNonNull(connection, "connection must not be null");
        requireText(activationId, "activationId");
        requireText(commandId, "commandId");
        try {
            PersistedCommand command = findCommand(connection, activationId, commandId);
            if (command == null) {
                return Optional.empty();
            }
            return Optional.of(new CommandAdmissionSnapshot(
                    command.admissionId(),
                    command.activationId(),
                    command.activationManifestHash(),
                    command.isolatedDomainDbBindingHash(),
                    command.tenantSurrogate(),
                    command.caseId(),
                    command.commandId(),
                    command.commandHash(),
                    command.commandEnvelopeHash(),
                    command.roomEpoch(),
                    command.roomFencingToken(),
                    command.admittedAt(),
                    command.completed(),
                    command.completionHash(),
                    command.completedAt()));
        } catch (SQLException failure) {
            throw sqlFailure("COMMAND_ADMISSION_QUERY_FAILED", failure);
        }
    }

    public ActivationLifecycle transition(
            String activationId,
            ActivationLifecycle expected,
            ActivationLifecycle target) {
        requireText(activationId, "activationId");
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (target == ActivationLifecycle.DRAINED
                || target == ActivationLifecycle.REVOKED_TERMINAL) {
            throw new IllegalArgumentException(
                    "terminal lifecycle transitions require the attested lifecycle store");
        }
        String timestampColumn = switch (target) {
            case ACTIVE -> "activated_at";
            case DRAIN_ONLY -> "drain_only_at";
            default -> throw new IllegalArgumentException("unsupported lifecycle target " + target);
        };
        String timestampValue = target == ActivationLifecycle.DRAIN_ONLY
                ? "expires_at"
                : "clock_timestamp()";
        String sql = "update production_runtime_activation set lifecycle_status = ?, "
                + "lifecycle_changed_at = clock_timestamp(), " + timestampColumn + " = "
                + timestampValue + " where activation_id = ? and lifecycle_status = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.name());
            statement.setString(2, activationId);
            statement.setString(3, expected.name());
            if (statement.executeUpdate() != 1) {
                throw new ProductionPersistenceException(
                        "ACTIVATION_STATE_CONFLICT", "activation is not in the expected lifecycle");
            }
            return target;
        } catch (SQLException failure) {
            throw sqlFailure("ACTIVATION_TRANSITION_FAILED", failure);
        }
    }

    public CompletionResult completeCommand(CommandCompletion completion) {
        Objects.requireNonNull(completion, "completion must not be null");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                CompletionResult result = completeCommand(connection, completion);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                if (failure instanceof ProductionPersistenceException persistenceFailure) {
                    throw persistenceFailure;
                }
                throw sqlFailure("COMMAND_COMPLETION_FAILED", failure);
            }
        } catch (SQLException failure) {
            throw sqlFailure("COMMAND_COMPLETION_CONNECTION_FAILED", failure);
        }
    }

    /**
     * Completes an admitted command on the caller-owned domain transaction. This method never
     * commits or rolls back.
     */
    public CompletionResult completeCommand(
            Connection transaction, CommandCompletion completion) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(completion, "completion must not be null");
        try {
            requireCallerTransaction(transaction, "command completion");
            advisoryLock(transaction, completion.admissionId());
            try (PreparedStatement statement = transaction.prepareStatement("""
                    insert into production_runtime_command_completion (
                        admission_id, activation_id, command_id, command_hash,
                        command_envelope_hash, completion_hash
                    ) values (?, ?, ?, ?, ?, ?) on conflict do nothing
                    """)) {
                statement.setString(1, completion.admissionId());
                statement.setString(2, completion.activationId());
                statement.setString(3, completion.commandId());
                statement.setString(4, completion.commandHash());
                statement.setString(5, completion.commandEnvelopeHash());
                statement.setString(6, completion.completionHash());
                statement.executeUpdate();
            }
            return findCompletion(transaction, completion);
        } catch (SQLException failure) {
            throw sqlFailure("COMMAND_COMPLETION_FAILED", failure);
        }
    }

    private int insertActivation(Connection connection, ActivationRegistration value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ACTIVATION)) {
            int i = 1;
            statement.setString(i++, value.activationId());
            statement.setString(i++, value.manifestHash());
            statement.setString(i++, value.environmentId());
            statement.setLong(i++, value.environmentGeneration());
            statement.setString(i++, value.candidateSha());
            statement.setString(i++, value.nonce());
            statement.setString(i++, value.tenantSurrogate());
            statement.setTimestamp(i++, Timestamp.from(value.issuedAt()));
            statement.setTimestamp(i++, Timestamp.from(value.expiresAt()));
            statement.setString(i++, value.caseScope().mode());
            statement.setString(i++, value.caseScope().scopeHash());
            if (value.caseScope() instanceof ExplicitCaseScope explicit) {
                statement.setInt(i++, explicit.caseIds().size());
                statement.setNull(i++, Types.VARCHAR);
                statement.setNull(i++, Types.INTEGER);
                statement.setNull(i++, Types.VARCHAR);
                statement.setNull(i++, Types.VARCHAR);
                statement.setNull(i++, Types.VARCHAR);
            } else {
                SyntheticCaseScope synthetic = (SyntheticCaseScope) value.caseScope();
                statement.setNull(i++, Types.INTEGER);
                statement.setString(i++, synthetic.caseIdPrefix());
                statement.setInt(i++, synthetic.maxCases());
                statement.setString(i++, synthetic.fixtureSetId());
                statement.setString(i++, synthetic.fixtureSetHash());
                statement.setString(i++, synthetic.fixtureBytesCanonicalHash());
            }
            Array rooms = connection.createArrayOf("varchar", value.allowedRoomTypes().toArray());
            statement.setArray(i++, rooms);
            statement.setString(i++, value.builds().caseBuildId());
            statement.setString(i++, value.builds().controlBuildId());
            statement.setString(i++, value.builds().agentBuildId());
            statement.setString(i++, value.graph().key());
            statement.setString(i++, value.graph().version());
            statement.setString(i++, value.graph().checkpointSchemaVersion());
            statement.setString(i++, value.graph().bindingHash());
            statement.setString(i++, value.graph().codeBuildId());
            statement.setString(i++, value.images().javaApi());
            statement.setString(i++, value.images().temporalControlWorker());
            statement.setString(i++, value.images().temporalAgentWorker());
            statement.setString(i++, value.images().pythonAgent());
            statement.setString(i++, value.images().frontend());
            statement.setString(i++, value.temporalNamespace());
            i = bindDatabase(statement, i, value.domainDatabase());
            i = bindDatabase(statement, i, value.graphDatabase());
            statement.setString(i, value.bindingSetHash());
            return statement.executeUpdate();
        }
    }

    private static int bindDatabase(
            PreparedStatement statement, int index, DatabaseBinding binding) throws SQLException {
        statement.setString(index++, binding.clusterIdentity());
        statement.setString(index++, binding.databaseIdentity());
        statement.setString(index++, binding.runtimePrincipalIdentity());
        statement.setString(index++, binding.bindingHash());
        return index;
    }

    private void insertExplicitCases(Connection connection, ActivationRegistration registration)
            throws SQLException {
        if (!(registration.caseScope() instanceof ExplicitCaseScope explicit)) {
            return;
        }
        int slot = 1;
        for (String caseId : explicit.caseIds()) {
            insertCase(
                    connection,
                    "p9case.v1." + compactUuid(),
                    new LockedScope(
                            registration.activationId(),
                            registration.environmentId(),
                            registration.environmentGeneration(),
                            registration.tenantSurrogate(),
                            explicit.mode(),
                            explicit.scopeHash(),
                            null,
                            explicit.caseIds().size(),
                            null,
                            null,
                            "REGISTERED",
                            registration.expiresAt()),
                    "EXPLICIT_CASE_ID",
                    slot++,
                    caseId,
                    null,
                    null,
                    null);
        }
    }

    private static void insertCase(
            Connection connection,
            String reservationId,
            LockedScope scope,
            String kind,
            int slot,
            String caseId,
            String fixtureSetId,
            String fixtureSetHash,
            String fixtureBytesCanonicalHash)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_CASE)) {
            int i = 1;
            statement.setString(i++, reservationId);
            statement.setString(i++, scope.activationId());
            statement.setString(i++, scope.environmentId());
            statement.setLong(i++, scope.environmentGeneration());
            statement.setString(i++, scope.tenantSurrogate());
            statement.setString(i++, kind);
            statement.setInt(i++, slot);
            statement.setString(i++, caseId);
            statement.setString(i++, scope.caseScopeHash());
            if (fixtureSetId == null) {
                statement.setNull(i++, Types.VARCHAR);
                statement.setNull(i++, Types.VARCHAR);
                statement.setNull(i, Types.VARCHAR);
            } else {
                statement.setString(i++, fixtureSetId);
                statement.setString(i++, fixtureSetHash);
                statement.setString(i, fixtureBytesCanonicalHash);
            }
            statement.executeUpdate();
        }
    }

    private PersistedGrant exactPersistedGrant(
            Connection connection, ActivationRegistration expected) throws SQLException {
        List<PersistedGrant> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_CONFLICT)) {
            statement.setString(1, expected.activationId());
            statement.setString(2, expected.nonce());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new PersistedGrant(
                            result.getString("activation_id"),
                            result.getString("nonce"),
                            result.getString("environment_id"),
                            result.getLong("environment_generation"),
                            result.getString("manifest_hash"),
                            result.getString("candidate_sha"),
                            result.getString("tenant_surrogate"),
                            result.getString("case_scope_hash"),
                            result.getString("binding_set_hash"),
                            ActivationLifecycle.valueOf(result.getString("lifecycle_status")),
                            result.getTimestamp("expires_at").toInstant()));
                }
            }
        }
        if (rows.size() != 1) {
            throw new ProductionPersistenceException(
                    "NONCE_REPLAY_OR_BINDING_CONFLICT",
                    "activationId and nonce do not resolve to one durable grant");
        }
        PersistedGrant actual = rows.getFirst();
        boolean exact = actual.activationId().equals(expected.activationId())
                && actual.nonce().equals(expected.nonce())
                && actual.environmentId().equals(expected.environmentId())
                && actual.environmentGeneration() == expected.environmentGeneration()
                && actual.manifestHash().equals(expected.manifestHash())
                && actual.candidateSha().equals(expected.candidateSha())
                && actual.tenantSurrogate().equals(expected.tenantSurrogate())
                && actual.caseScopeHash().equals(expected.caseScope().scopeHash())
                && actual.bindingSetHash().equals(expected.bindingSetHash());
        if (!exact) {
            throw new ProductionPersistenceException(
                    "NONCE_REPLAY_OR_BINDING_CONFLICT",
                    "activationId or nonce is already bound to another manifest or deployment");
        }
        return actual;
    }

    private static void requireCurrentGeneration(
            Connection connection, PersistedGrant activation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select highest_generation, highest_activation_id
                  from production_runtime_environment_generation_watermark
                 where environment_id = ?
                 for share
                """)) {
            statement.setString(1, activation.environmentId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getLong("highest_generation") != activation.environmentGeneration()
                        || !activation.activationId().equals(
                                result.getString("highest_activation_id"))) {
                    throw new ProductionPersistenceException(
                            "ACTIVATION_STALE_GENERATION",
                            "activation is below the durable environment generation high-water mark");
                }
            }
        }
    }

    private static void verifyExplicitCases(
            Connection connection, ActivationRegistration registration) throws SQLException {
        if (!(registration.caseScope() instanceof ExplicitCaseScope explicit)) {
            return;
        }
        List<String> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select case_id from production_runtime_case_reservation
                 where activation_id = ? and reservation_kind = 'EXPLICIT_CASE_ID'
                 order by slot_number
                """)) {
            statement.setString(1, registration.activationId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1));
                }
            }
        }
        if (!actual.equals(explicit.caseIds())) {
            throw new ProductionPersistenceException(
                    "CASE_SCOPE_BINDING_CONFLICT", "persisted explicit case scope differs");
        }
    }

    private static LockedScope lockScope(Connection connection, String activationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select activation_id, environment_id, environment_generation, tenant_surrogate,
                       case_scope_mode, case_scope_hash, synthetic_case_id_prefix,
                       synthetic_max_cases, synthetic_fixture_set_id,
                       synthetic_fixture_set_hash, lifecycle_status, expires_at
                  from production_runtime_activation where activation_id = ? for update
                """)) {
            statement.setString(1, activationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ProductionPersistenceException(
                            "ACTIVATION_NOT_FOUND", "activation is not registered");
                }
                return new LockedScope(
                        result.getString("activation_id"),
                        result.getString("environment_id"),
                        result.getLong("environment_generation"),
                        result.getString("tenant_surrogate"),
                        result.getString("case_scope_mode"),
                        result.getString("case_scope_hash"),
                        result.getString("synthetic_case_id_prefix"),
                        result.getInt("synthetic_max_cases"),
                        result.getString("synthetic_fixture_set_id"),
                        result.getString("synthetic_fixture_set_hash"),
                        result.getString("lifecycle_status"),
                        result.getTimestamp("expires_at").toInstant());
            }
        }
    }

    private static int nextSyntheticSlot(Connection connection, LockedScope scope)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select candidate.slot_number
                  from generate_series(1, ?) candidate(slot_number)
                 where not exists (
                    select 1 from production_runtime_case_reservation reservation
                     where reservation.activation_id = ?
                       and reservation.slot_number = candidate.slot_number
                 )
                 order by candidate.slot_number limit 1
                """)) {
            statement.setInt(1, scope.maximumCases());
            statement.setString(2, scope.activationId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ProductionPersistenceException(
                            "CASE_SCOPE_EXHAUSTED", "signed synthetic case capacity is exhausted");
                }
                return result.getInt(1);
            }
        }
    }

    private static CaseReservation findReservation(
            Connection connection, String activationId, String caseId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select reservation_id, activation_id, case_id, slot_number,
                       reservation_kind, reserved_at
                  from production_runtime_case_reservation where activation_id = ? and case_id = ?
                """)) {
            statement.setString(1, activationId);
            statement.setString(2, caseId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new CaseReservation(
                        result.getString("reservation_id"),
                        result.getString("activation_id"),
                        result.getString("case_id"),
                        result.getInt("slot_number"),
                        result.getString("reservation_kind"),
                        result.getTimestamp("reserved_at").toInstant());
            }
        }
    }

    private static boolean hasReservationOrTombstone(Connection connection, String caseId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select exists (
                    select 1 from production_runtime_case_reservation where case_id = ?
                    union all
                    select 1 from production_runtime_generated_case_tombstone where generated_case_id = ?
                )
                """)) {
            statement.setString(1, caseId);
            statement.setString(2, caseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static PersistedCommand findCommand(
            Connection connection, String activationId, String commandId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select admission.admission_id, admission.activation_id,
                       admission.activation_manifest_hash,
                       admission.isolated_domain_db_binding_hash,
                       admission.tenant_surrogate, admission.case_id,
                       admission.command_id, admission.command_hash,
                       admission.command_envelope_hash, admission.room_epoch,
                       admission.room_fencing_token, admission.admitted_at,
                       completion.completion_hash, completion.completed_at
                  from production_runtime_command_admission admission
                  left join production_runtime_command_completion completion
                    on completion.admission_id = admission.admission_id
                 where admission.activation_id = ? and admission.command_id = ?
                """)) {
            statement.setString(1, activationId);
            statement.setString(2, commandId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Timestamp completedAt = result.getTimestamp("completed_at");
                return new PersistedCommand(
                        result.getString("admission_id"),
                        result.getString("activation_id"),
                        result.getString("activation_manifest_hash"),
                        result.getString("isolated_domain_db_binding_hash"),
                        result.getString("tenant_surrogate"),
                        result.getString("case_id"),
                        result.getString("command_id"),
                        result.getString("command_hash"),
                        result.getString("command_envelope_hash"),
                        result.getLong("room_epoch"),
                        result.getLong("room_fencing_token"),
                        result.getTimestamp("admitted_at").toInstant(),
                        completedAt != null,
                        result.getString("completion_hash"),
                        completedAt == null ? null : completedAt.toInstant());
            }
        }
    }

    private static void insertCommand(
            Connection connection, String admissionId, CommandAdmission command)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into production_runtime_command_admission (
                    admission_id, activation_id, activation_manifest_hash,
                    execution_lane, isolated_domain_db_binding_hash,
                    tenant_surrogate, case_id, command_id, command_hash,
                    command_envelope_hash, room_epoch, room_fencing_token
                ) values (?, ?, ?, 'PRODUCTION', ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int index = 1;
            statement.setString(index++, admissionId);
            statement.setString(index++, command.activationId());
            statement.setString(index++, command.manifestHash());
            statement.setString(index++, command.isolatedDomainDbBindingHash());
            statement.setString(index++, command.tenantSurrogate());
            statement.setString(index++, command.caseId());
            statement.setString(index++, command.commandId());
            statement.setString(index++, command.commandHash());
            statement.setString(index++, command.commandEnvelopeHash());
            statement.setLong(index++, command.roomEpoch());
            statement.setLong(index, command.roomFencingToken());
            statement.executeUpdate();
        }
    }

    private static CommandActivation lockCommandActivation(
            Connection connection, String activationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select lifecycle_status, expires_at,
                       expires_at > clock_timestamp() as before_cutoff
                  from production_runtime_activation
                 where activation_id = ?
                 for update
                """)) {
            statement.setString(1, activationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ProductionPersistenceException(
                            "ACTIVATION_NOT_FOUND", "activation is not registered");
                }
                return new CommandActivation(
                        ActivationLifecycle.valueOf(result.getString("lifecycle_status")),
                        result.getTimestamp("expires_at").toInstant(),
                        result.getBoolean("before_cutoff"));
            }
        }
    }

    private static void requireActiveBeforeCutoff(CommandActivation activation) {
        if (activation.lifecycle() != ActivationLifecycle.ACTIVE || !activation.beforeCutoff()) {
            throw new ProductionPersistenceException(
                    "COMMAND_ADMISSION_CLOSED",
                    "new or ACTIVE command admission requires a pre-cutoff ACTIVE activation");
        }
    }

    private static void advisoryLock(Connection connection, String key) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            lock.setString(1, key);
            lock.executeQuery().close();
        }
    }

    private static void requireCallerTransaction(Connection connection, String operation)
            throws SQLException {
        if (connection.isClosed() || connection.getAutoCommit()) {
            throw new IllegalArgumentException(operation + " requires a caller-owned transaction");
        }
    }

    private static void requireSameCommand(CommandAdmission expected, PersistedCommand actual) {
        if (!expected.activationId().equals(actual.activationId())
                || !expected.manifestHash().equals(actual.activationManifestHash())
                || !expected.isolatedDomainDbBindingHash()
                        .equals(actual.isolatedDomainDbBindingHash())
                || !expected.tenantSurrogate().equals(actual.tenantSurrogate())
                || !expected.caseId().equals(actual.caseId())
                || !expected.commandId().equals(actual.commandId())
                || !expected.commandHash().equals(actual.commandHash())
                || !expected.commandEnvelopeHash().equals(actual.commandEnvelopeHash())
                || expected.roomEpoch() != actual.roomEpoch()
                || expected.roomFencingToken() != actual.roomFencingToken()) {
            throw new ProductionPersistenceException(
                    "COMMAND_ADMISSION_CONFLICT",
                    "command identity is already admitted with different bindings");
        }
    }

    private static CompletionResult findCompletion(
            Connection connection, CommandCompletion expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select admission_id, activation_id, command_id, command_hash,
                       command_envelope_hash, completion_hash, completed_at
                  from production_runtime_command_completion where admission_id = ?
                """)) {
            statement.setString(1, expected.admissionId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ProductionPersistenceException(
                            "COMMAND_COMPLETION_NOT_FOUND", "completion was not persisted");
                }
                boolean exact = expected.activationId().equals(result.getString("activation_id"))
                        && expected.commandId().equals(result.getString("command_id"))
                        && expected.commandHash().equals(result.getString("command_hash"))
                        && expected.commandEnvelopeHash()
                                .equals(result.getString("command_envelope_hash"))
                        && expected.completionHash().equals(result.getString("completion_hash"));
                if (!exact) {
                    throw new ProductionPersistenceException(
                            "COMMAND_COMPLETION_CONFLICT",
                            "command completion identity has different durable bindings");
                }
                return new CompletionResult(
                        result.getString("admission_id"),
                        result.getString("completion_hash"),
                        result.getTimestamp("completed_at").toInstant());
            }
        }
    }

    private static void enterDrainOnly(Connection connection, String activationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update production_runtime_activation
                   set lifecycle_status = 'DRAIN_ONLY',
                       lifecycle_changed_at = clock_timestamp(),
                       drain_only_at = expires_at
                 where activation_id = ? and lifecycle_status = 'ACTIVE'
                """)) {
            statement.setString(1, activationId);
            if (statement.executeUpdate() != 1) {
                throw new ProductionPersistenceException(
                        "ACTIVATION_STATE_CONFLICT", "expired activation did not enter DRAIN_ONLY");
            }
        }
    }

    private RegistrationDisposition attachDisposition(PersistedGrant persisted) {
        if ((persisted.lifecycleStatus() == ActivationLifecycle.REGISTERED
                        || persisted.lifecycleStatus() == ActivationLifecycle.ACTIVE)
                && persisted.expiresAt().isAfter(clock.instant())) {
            return RegistrationDisposition.ATTACHED_EXISTING;
        }
        if (persisted.lifecycleStatus() == ActivationLifecycle.DRAIN_ONLY) {
            return RegistrationDisposition.ATTACHED_DRAIN_ONLY;
        }
        return RegistrationDisposition.ATTACHED_TERMINAL;
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static ProductionPersistenceException sqlFailure(String code, Throwable failure) {
        return new ProductionPersistenceException(code, failure.getMessage(), failure);
    }

    private static ProductionPersistenceException registrationFailure(Throwable failure) {
        String message = failure.getMessage();
        if (message != null && message.contains("stale below the durable environment generation")) {
            return new ProductionPersistenceException(
                    "ACTIVATION_STALE_GENERATION", message, failure);
        }
        return sqlFailure("ACTIVATION_REGISTRATION_FAILED", failure);
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public enum RegistrationDisposition {
        REGISTERED,
        ATTACHED_EXISTING,
        ATTACHED_DRAIN_ONLY,
        ATTACHED_TERMINAL
    }

    public enum ActivationLifecycle {
        REGISTERED,
        ACTIVE,
        DRAIN_ONLY,
        DRAINED,
        REVOKED_TERMINAL
    }

    public enum CommandAdmissionDisposition {
        ADMITTED,
        ALREADY_ADMITTED_ACTIVE,
        ALREADY_ADMITTED_DRAIN_ONLY
    }

    public record RegistrationResult(
            RegistrationDisposition disposition,
            String activationId,
            ActivationLifecycle lifecycle,
            Instant expiresAt) {}

    public sealed interface CaseScope permits ExplicitCaseScope, SyntheticCaseScope {
        String mode();

        String scopeHash();
    }

    public record ExplicitCaseScope(String scopeHash, List<String> caseIds) implements CaseScope {
        public ExplicitCaseScope {
            requireText(scopeHash, "scopeHash");
            Objects.requireNonNull(caseIds, "caseIds must not be null");
            caseIds = caseIds.stream()
                    .map(caseId -> requireText(caseId, "caseId"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (caseIds.isEmpty() || caseIds.size() > 100
                    || new LinkedHashSet<>(caseIds).size() != caseIds.size()) {
                throw new IllegalArgumentException("explicit case IDs must contain 1..100 unique IDs");
            }
        }

        @Override
        public String mode() {
            return "EXPLICIT_CASE_IDS";
        }
    }

    public record SyntheticCaseScope(
            String scopeHash,
            String caseIdPrefix,
            int maxCases,
            String fixtureSetId,
            String fixtureSetHash,
            String fixtureBytesCanonicalHash)
            implements CaseScope {
        public SyntheticCaseScope {
            requireText(scopeHash, "scopeHash");
            requireText(caseIdPrefix, "caseIdPrefix");
            requireText(fixtureSetId, "fixtureSetId");
            requireText(fixtureSetHash, "fixtureSetHash");
            requireText(fixtureBytesCanonicalHash, "fixtureBytesCanonicalHash");
            if (maxCases < 1 || maxCases > 16) {
                throw new IllegalArgumentException("maxCases must be between 1 and 16");
            }
            if (!fixtureSetHash.equals(fixtureBytesCanonicalHash)) {
                throw new IllegalArgumentException(
                        "fixtureSetHash must bind the canonical fixture bytes hash");
            }
        }

        @Override
        public String mode() {
            return "ISOLATED_SYNTHETIC_NEW_CASES";
        }
    }

    public record BuildBindings(String caseBuildId, String controlBuildId, String agentBuildId) {
        public BuildBindings {
            requireText(caseBuildId, "caseBuildId");
            requireText(controlBuildId, "controlBuildId");
            requireText(agentBuildId, "agentBuildId");
        }
    }

    public record GraphBinding(
            String key,
            String version,
            String checkpointSchemaVersion,
            String bindingHash,
            String codeBuildId) {
        public GraphBinding {
            requireText(key, "key");
            requireText(version, "version");
            requireText(checkpointSchemaVersion, "checkpointSchemaVersion");
            requireText(bindingHash, "bindingHash");
            requireText(codeBuildId, "codeBuildId");
        }
    }

    public record ImageDigests(
            String javaApi,
            String temporalControlWorker,
            String temporalAgentWorker,
            String pythonAgent,
            String frontend) {
        public ImageDigests {
            requireText(javaApi, "javaApi");
            requireText(temporalControlWorker, "temporalControlWorker");
            requireText(temporalAgentWorker, "temporalAgentWorker");
            requireText(pythonAgent, "pythonAgent");
            requireText(frontend, "frontend");
        }
    }

    public record DatabaseBinding(
            String clusterIdentity,
            String databaseIdentity,
            String runtimePrincipalIdentity,
            String bindingHash) {
        public DatabaseBinding {
            requireText(clusterIdentity, "clusterIdentity");
            requireText(databaseIdentity, "databaseIdentity");
            requireText(runtimePrincipalIdentity, "runtimePrincipalIdentity");
            requireText(bindingHash, "bindingHash");
        }
    }

    public record ActivationRegistration(
            String activationId,
            String manifestHash,
            String environmentId,
            long environmentGeneration,
            String candidateSha,
            String nonce,
            String tenantSurrogate,
            Instant issuedAt,
            Instant expiresAt,
            CaseScope caseScope,
            List<String> allowedRoomTypes,
            BuildBindings builds,
            GraphBinding graph,
            ImageDigests images,
            String temporalNamespace,
            DatabaseBinding domainDatabase,
            DatabaseBinding graphDatabase,
            String bindingSetHash) {
        public ActivationRegistration {
            requireText(activationId, "activationId");
            requireText(manifestHash, "manifestHash");
            requireText(environmentId, "environmentId");
            requireText(candidateSha, "candidateSha");
            requireText(nonce, "nonce");
            requireText(tenantSurrogate, "tenantSurrogate");
            Objects.requireNonNull(issuedAt, "issuedAt must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            Objects.requireNonNull(caseScope, "caseScope must not be null");
            Objects.requireNonNull(allowedRoomTypes, "allowedRoomTypes must not be null");
            allowedRoomTypes = allowedRoomTypes.stream().sorted().toList();
            if (allowedRoomTypes.isEmpty()
                    || allowedRoomTypes.size() > 4
                    || new LinkedHashSet<>(allowedRoomTypes).size() != allowedRoomTypes.size()
                    || !ROOM_TYPES.containsAll(allowedRoomTypes)) {
                throw new IllegalArgumentException("allowedRoomTypes must be 1..4 unique room types");
            }
            Objects.requireNonNull(builds, "builds must not be null");
            Objects.requireNonNull(graph, "graph must not be null");
            Objects.requireNonNull(images, "images must not be null");
            requireText(temporalNamespace, "temporalNamespace");
            Objects.requireNonNull(domainDatabase, "domainDatabase must not be null");
            Objects.requireNonNull(graphDatabase, "graphDatabase must not be null");
            requireText(bindingSetHash, "bindingSetHash");
            if (environmentGeneration < 1) {
                throw new IllegalArgumentException("environmentGeneration must be positive");
            }
        }
    }

    public record CaseReservation(
            String reservationId,
            String activationId,
            String caseId,
            int slotNumber,
            String reservationKind,
            Instant reservedAt) {}

    public record CommandAdmission(
            String activationId,
            String manifestHash,
            String isolatedDomainDbBindingHash,
            String tenantSurrogate,
            String caseId,
            String commandId,
            String commandHash,
            String commandEnvelopeHash,
            long roomEpoch,
            long roomFencingToken) {
        public CommandAdmission {
            requireText(activationId, "activationId");
            requireText(manifestHash, "manifestHash");
            requireText(isolatedDomainDbBindingHash, "isolatedDomainDbBindingHash");
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            requireText(commandId, "commandId");
            requireText(commandHash, "commandHash");
            requireText(commandEnvelopeHash, "commandEnvelopeHash");
            if (roomEpoch < 0 || roomFencingToken < 1) {
                throw new IllegalArgumentException("room epoch/fencing token is invalid");
            }
        }
    }

    public record CommandAdmissionResult(
            CommandAdmissionDisposition disposition, String admissionId, Instant admittedAt) {}

    public record CommandAdmissionSnapshot(
            String admissionId,
            String activationId,
            String activationManifestHash,
            String isolatedDomainDbBindingHash,
            String tenantSurrogate,
            String caseId,
            String commandId,
            String commandHash,
            String commandEnvelopeHash,
            long roomEpoch,
            long roomFencingToken,
            Instant admittedAt,
            boolean completed,
            String completionHash,
            Instant completedAt) {}

    public record CommandCompletion(
            String admissionId,
            String activationId,
            String commandId,
            String commandHash,
            String commandEnvelopeHash,
            String completionHash) {
        public CommandCompletion {
            requireText(admissionId, "admissionId");
            requireText(activationId, "activationId");
            requireText(commandId, "commandId");
            requireText(commandHash, "commandHash");
            requireText(commandEnvelopeHash, "commandEnvelopeHash");
            requireText(completionHash, "completionHash");
        }
    }

    public record CompletionResult(String admissionId, String completionHash, Instant completedAt) {}

    private record PersistedGrant(
            String activationId,
            String nonce,
            String environmentId,
            long environmentGeneration,
            String manifestHash,
            String candidateSha,
            String tenantSurrogate,
            String caseScopeHash,
            String bindingSetHash,
            ActivationLifecycle lifecycleStatus,
            Instant expiresAt) {
        PersistedGrant withLifecycle(ActivationLifecycle lifecycle) {
            return new PersistedGrant(
                    activationId, nonce, environmentId, environmentGeneration, manifestHash,
                    candidateSha, tenantSurrogate, caseScopeHash, bindingSetHash, lifecycle, expiresAt);
        }
    }

    private record LockedScope(
            String activationId,
            String environmentId,
            long environmentGeneration,
            String tenantSurrogate,
            String mode,
            String caseScopeHash,
            String prefix,
            int maximumCases,
            String fixtureSetId,
            String fixtureSetHash,
            String lifecycleStatus,
            Instant expiresAt) {}

    private record PersistedCommand(
            String admissionId,
            String activationId,
            String activationManifestHash,
            String isolatedDomainDbBindingHash,
            String tenantSurrogate,
            String caseId,
            String commandId,
            String commandHash,
            String commandEnvelopeHash,
            long roomEpoch,
            long roomFencingToken,
            Instant admittedAt,
            boolean completed,
            String completionHash,
            Instant completedAt) {}

    private record CommandActivation(
            ActivationLifecycle lifecycle, Instant expiresAt, boolean beforeCutoff) {}
}
