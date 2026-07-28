package com.example.dispute.workflow.targete2e.persistence.material;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmissionResult;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EPersistenceException;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Spring-transaction-bound material store. It deliberately never owns a JDBC transaction:
 * command admission and material persistence either commit together in the caller's transaction
 * or are both rolled back by that caller.
 */
public final class JdbcTargetIntakeCommandMaterialStore implements TargetIntakeCommandMaterialStore {

    private static final String MATERIAL_SCHEMA_VERSION = "target-e2e-intake-command-material.v1";
    private static final String CONTEXT_SCHEMA_VERSION = "intake-command-execution-context.v2";

    private final DataSource dataSource;
    private final TargetE2EActivationLedger activationLedger;
    private final ObjectMapper objectMapper;

    public JdbcTargetIntakeCommandMaterialStore(
            DataSource dataSource,
            TargetE2EActivationLedger activationLedger,
            ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.activationLedger = Objects.requireNonNull(activationLedger, "activationLedger");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                // V049 persists the unannotated execution-context envelope as camelCase. Nested
                // graph contracts retain their explicit @JsonNaming(SnakeCaseStrategy) contract.
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
    }

    @Override
    public AppendResult append(CommandAdmission admission, IntakeCommandExecutionContext context) {
        Objects.requireNonNull(admission, "admission");
        requireTargetContext(context);
        CanonicalContext canonical = canonicalize(context);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            requireSpringTransaction(connection);
            CommandAdmissionResult admissionResult = activationLedger.admitCommand(connection, admission);
            PersistedMaterial existing = find(connection, admission.activationId(), admission.commandId(), true);
            if (existing != null) {
                requireExact(admission, existing);
                if (!canonical.json().equals(existing.contextCanonicalJson())
                        || !canonical.sha256().equals(existing.contextSha256())) {
                    throw conflict("material replay has different canonical execution context");
                }
                return new AppendResult(
                        AppendDisposition.ATTACHED_IDENTICAL,
                        existing.admissionId(),
                        admissionResult.admittedAt(),
                        existing.contextSha256());
            }
            insert(connection, admissionResult.admissionId(), admission, canonical);
            PersistedMaterial stored = find(connection, admission.activationId(), admission.commandId(), true);
            if (stored == null) {
                throw new TargetE2EPersistenceException(
                        "INTAKE_MATERIAL_NOT_PERSISTED", "admitted Intake material was not persisted");
            }
            requireExact(admission, stored);
            return new AppendResult(
                    AppendDisposition.STORED,
                    stored.admissionId(),
                    admissionResult.admittedAt(),
                    stored.contextSha256());
        } catch (SQLException failure) {
            throw new TargetE2EPersistenceException(
                    "INTAKE_MATERIAL_PERSISTENCE_FAILED", failure.getMessage(), failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Override
    public Optional<MaterialSnapshot> read(CommandAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            PersistedMaterial material = find(connection, admission.activationId(), admission.commandId(), false);
            if (material == null) {
                return Optional.empty();
            }
            requireExact(admission, material);
            IntakeCommandExecutionContext context = deserialize(material);
            requireContextMatchesAdmission(context, admission);
            return Optional.of(new MaterialSnapshot(
                    material.admissionId(), admission, context, material.contextSha256(), material.storedAt()));
        } catch (SQLException failure) {
            throw new TargetE2EPersistenceException(
                    "INTAKE_MATERIAL_READ_FAILED", failure.getMessage(), failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Override
    public Optional<MaterialSnapshot> readByRoute(CommandLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            PersistedRouteMaterial material = findByRoute(connection, lookup);
            if (material == null) {
                return Optional.empty();
            }
            requireExact(material.admission(), material.material());
            IntakeCommandExecutionContext context = deserialize(material.material());
            requireContextMatchesAdmission(context, material.admission());
            return Optional.of(new MaterialSnapshot(
                    material.material().admissionId(),
                    material.admission(),
                    context,
                    material.material().contextSha256(),
                    material.material().storedAt()));
        } catch (SQLException failure) {
            throw new TargetE2EPersistenceException(
                    "INTAKE_MATERIAL_ROUTE_READ_FAILED", failure.getMessage(), failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void requireSpringTransaction(Connection connection) throws SQLException {
        if (!DataSourceUtils.isConnectionTransactional(connection, dataSource)
                || connection.getAutoCommit()) {
            throw new IllegalStateException("target Intake material requires a caller-owned Spring transaction");
        }
    }

    private CanonicalContext canonicalize(IntakeCommandExecutionContext context) {
        JsonNode tree = objectMapper.valueToTree(context);
        return new CanonicalContext(ContractJson.canonicalString(tree), ContractJson.sha256Hex(tree));
    }

    private IntakeCommandExecutionContext deserialize(PersistedMaterial material) {
        try {
            JsonNode tree = objectMapper.readTree(material.contextCanonicalJson());
            String canonical = ContractJson.canonicalString(tree);
            String recomputedHash = ContractJson.sha256Hex(tree);
            if (!canonical.equals(material.contextCanonicalJson())
                    || !recomputedHash.equals(material.contextSha256())) {
                throw new TargetE2EPersistenceException(
                        "INTAKE_MATERIAL_HASH_MISMATCH",
                        "stored Intake execution context is not its canonical self-hash");
            }
            IntakeCommandExecutionContext context =
                    objectMapper.treeToValue(tree, IntakeCommandExecutionContext.class);
            requireTargetContext(context);
            return context;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new TargetE2EPersistenceException(
                    "INTAKE_MATERIAL_CONTEXT_INVALID", "stored Intake execution context is invalid", failure);
        }
    }

    private static void requireTargetContext(IntakeCommandExecutionContext context) {
        Objects.requireNonNull(context, "context");
        if (!CONTEXT_SCHEMA_VERSION.equals(context.schemaVersion()) || context.targetAgentRun() == null) {
            throw new IllegalArgumentException("target Intake material requires a v2 execution context");
        }
    }

    private static void requireContextMatchesAdmission(
            IntakeCommandExecutionContext context, CommandAdmission admission) {
        IntakeTargetAgentRunContext target = context.targetAgentRun();
        RoomGraphCommand command = target.request().command();
        boolean exact = IntakeTargetAgentRunContext.TARGET_LANE.equals(target.executionLane())
                && admission.activationId().equals(target.activationId())
                && admission.manifestHash().equals(target.activationManifestHash())
                && admission.roomFencingToken() == target.roomFencingToken()
                && admission.commandHash().equals(target.commandHash())
                && admission.commandEnvelopeHash().equals(target.commandEnvelopeHash())
                && admission.tenantSurrogate().equals(command.tenantSurrogate())
                && admission.caseId().equals(command.caseId())
                && admission.commandId().equals(command.commandId())
                && admission.roomEpoch() == command.roomEpoch()
                && command.roomType().name().equals("INTAKE");
        if (!exact) {
            throw conflict("stored Intake execution context does not match the admitted command");
        }
    }

    private static void requireExact(CommandAdmission expected, PersistedMaterial actual) {
        boolean exact = expected.activationId().equals(actual.activationId())
                && expected.manifestHash().equals(actual.activationManifestHash())
                && expected.isolatedDomainDbBindingHash().equals(actual.isolatedDomainDbBindingHash())
                && expected.tenantSurrogate().equals(actual.tenantSurrogate())
                && expected.caseId().equals(actual.caseId())
                && expected.commandId().equals(actual.commandId())
                && expected.commandHash().equals(actual.commandHash())
                && expected.commandEnvelopeHash().equals(actual.commandEnvelopeHash())
                && expected.roomEpoch() == actual.roomEpoch()
                && expected.roomFencingToken() == actual.roomFencingToken();
        if (!exact) {
            throw conflict("admitted Intake material has different durable bindings");
        }
    }

    private static TargetE2EPersistenceException conflict(String message) {
        return new TargetE2EPersistenceException("INTAKE_MATERIAL_CONFLICT", message);
    }

    private static void insert(
            Connection connection,
            String admissionId,
            CommandAdmission admission,
            CanonicalContext context) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into target_e2e_intake_command_material (
                    admission_id, activation_id, activation_manifest_hash, execution_lane,
                    isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id,
                    command_hash, command_envelope_hash, room_type, room_epoch, room_fencing_token,
                    material_schema_version, context_schema_version, context_canonical_json,
                    context_sha256
                ) values (?, ?, ?, 'TARGET_E2E_CANDIDATE', ?, ?, ?, ?, ?, ?, 'INTAKE', ?, ?, ?, ?, ?, ?)
                """)) {
            int index = 1;
            statement.setString(index++, admissionId);
            statement.setString(index++, admission.activationId());
            statement.setString(index++, admission.manifestHash());
            statement.setString(index++, admission.isolatedDomainDbBindingHash());
            statement.setString(index++, admission.tenantSurrogate());
            statement.setString(index++, admission.caseId());
            statement.setString(index++, admission.commandId());
            statement.setString(index++, admission.commandHash());
            statement.setString(index++, admission.commandEnvelopeHash());
            statement.setLong(index++, admission.roomEpoch());
            statement.setLong(index++, admission.roomFencingToken());
            statement.setString(index++, MATERIAL_SCHEMA_VERSION);
            statement.setString(index++, CONTEXT_SCHEMA_VERSION);
            statement.setString(index++, context.json());
            statement.setString(index, context.sha256());
            statement.executeUpdate();
        }
    }

    private static PersistedMaterial find(
            Connection connection, String activationId, String commandId, boolean forUpdate)
            throws SQLException {
        String lock = forUpdate ? " for update" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                select admission_id, activation_id, activation_manifest_hash,
                       isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id,
                       command_hash, command_envelope_hash, room_epoch, room_fencing_token,
                       context_canonical_json, context_sha256, stored_at
                  from target_e2e_intake_command_material
                 where activation_id = ? and command_id = ?
                """ + lock)) {
            statement.setString(1, activationId);
            statement.setString(2, commandId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Timestamp storedAt = result.getTimestamp("stored_at");
                return new PersistedMaterial(
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
                        result.getString("context_canonical_json"),
                        result.getString("context_sha256"),
                        storedAt.toInstant());
            }
        }
    }

    private static PersistedRouteMaterial findByRoute(Connection connection, CommandLookup lookup)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select material.admission_id, material.activation_id,
                       material.activation_manifest_hash,
                       material.isolated_domain_db_binding_hash,
                       material.tenant_surrogate, material.case_id, material.command_id,
                       material.command_hash, material.command_envelope_hash,
                       material.room_epoch, material.room_fencing_token,
                       material.context_canonical_json, material.context_sha256, material.stored_at,
                       admission.activation_id as admitted_activation_id,
                       admission.activation_manifest_hash as admitted_manifest_hash,
                       admission.isolated_domain_db_binding_hash as admitted_domain_binding_hash,
                       admission.tenant_surrogate as admitted_tenant_surrogate,
                       admission.case_id as admitted_case_id,
                       admission.command_id as admitted_command_id,
                       admission.command_hash as admitted_command_hash,
                       admission.command_envelope_hash as admitted_envelope_hash,
                       admission.room_epoch as admitted_room_epoch,
                       admission.room_fencing_token as admitted_room_fencing_token
                  from target_e2e_intake_command_material material
                  join target_e2e_command_admission admission
                    on admission.admission_id = material.admission_id
                 where material.tenant_surrogate = ?
                   and material.case_id = ?
                   and material.command_id = ?
                   and material.room_epoch = ?
                   and material.room_fencing_token = ?
                 order by material.activation_id
                """)) {
            statement.setString(1, lookup.tenantSurrogate());
            statement.setString(2, lookup.caseId());
            statement.setString(3, lookup.commandId());
            statement.setLong(4, lookup.roomEpoch());
            statement.setLong(5, lookup.roomFencingToken());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                PersistedRouteMaterial first = new PersistedRouteMaterial(
                        material(result), admission(result));
                if (result.next()) {
                    throw conflict("Intake material route resolves to multiple admissions");
                }
                return first;
            }
        }
    }

    private static PersistedMaterial material(ResultSet result) throws SQLException {
        Timestamp storedAt = result.getTimestamp("stored_at");
        return new PersistedMaterial(
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
                result.getString("context_canonical_json"),
                result.getString("context_sha256"),
                storedAt.toInstant());
    }

    private static CommandAdmission admission(ResultSet result) throws SQLException {
        return new CommandAdmission(
                result.getString("admitted_activation_id"),
                result.getString("admitted_manifest_hash"),
                result.getString("admitted_domain_binding_hash"),
                result.getString("admitted_tenant_surrogate"),
                result.getString("admitted_case_id"),
                result.getString("admitted_command_id"),
                result.getString("admitted_command_hash"),
                result.getString("admitted_envelope_hash"),
                result.getLong("admitted_room_epoch"),
                result.getLong("admitted_room_fencing_token"));
    }

    private record CanonicalContext(String json, String sha256) {}

    private record PersistedMaterial(
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
            String contextCanonicalJson,
            String contextSha256,
            Instant storedAt) {}

    private record PersistedRouteMaterial(PersistedMaterial material, CommandAdmission admission) {}
}
