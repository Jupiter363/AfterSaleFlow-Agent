package com.example.dispute.workflow.infrastructure.persistence.authority.bridge;

import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.CommandSource;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.DomainEventSource;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.StartSource;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Read-only JDBC authority bridge. Every bridge operation owns one PostgreSQL
 * REPEATABLE_READ snapshot and fails closed unless the expected authority
 * cardinality and tuple are present in that snapshot.
 *
 * <p>This class deliberately has no write API and does not depend on a Temporal
 * repository. It is safe to construct in the DISABLED/SIGNED_SYNTHETIC_SHADOW
 * gate; command reads reject ACTIVITY_ORCHESTRATED rows.
 */
public final class JdbcIntakeChildBridgeReadPort implements IntakeChildBridgeReadPort {

    public static final int REQUIRED_ISOLATION = Connection.TRANSACTION_REPEATABLE_READ;
    public static final String INERT_DISPOSITION = "INERT_EXTERNAL_EVENT";
    public static final String REGISTERED = "REGISTERED";
    public static final String ACTIVE = "ACTIVE";

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private static final String EPOCH_SQL = """
        select epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
               selection_hash, writer_mode, case_workflow_type, case_workflow_build_id,
               room_workflow_type, room_workflow_build_id, process_contract_version,
               graph_key, graph_version, checkpoint_schema_version, state_schema_version,
               stream_protocol, prompt_version, model_profile_id, output_schema_version,
               policy_version, guardrail_version, tool_policy_version, cohort_policy_version,
               agent_key, agent_session_profile_version, memory_policy_id
          from case_intake_epoch_selection_binding
         where epoch_id = ? and tenant_surrogate = ? and case_id = ?
           and room_type = 'INTAKE' and room_epoch = ? and fencing_token = ?
           and writer_mode = 'SHADOW'
        """;

    private static final String PARTY_SQL = """
        select p.party, p.tenant_surrogate, p.case_id, p.session_tenant_id,
               p.session_case_id, p.room_type, p.room_epoch, p.fencing_token,
               p.registration_id, p.registration_hash, p.thread_id, p.actor_id,
               p.actor_role, p.audience, p.actor_scope_hash, p.access_session_id,
               p.permission_level, p.agent_session_id, p.agent_key, p.prompt_version,
               p.agent_session_profile_version, p.prompt_profile_id, p.memory_policy_id,
               t.registration_status, a.status as access_status, g.status as agent_status,
               t.graph_key, t.graph_version, t.checkpoint_schema_version,
               t.state_schema_version, t.prompt_version as registration_prompt_version,
               t.model_profile_id as registration_model_profile_id,
               t.output_schema_version, t.policy_version, t.guardrail_version,
               t.tool_policy_version, t.writer_mode
          from case_intake_epoch_party_authority p
          join case_intake_graph_thread_binding t
            on t.registration_id = p.registration_id
           and t.tenant_surrogate = p.tenant_surrogate and t.case_id = p.case_id
           and t.room_type = p.room_type and t.room_epoch = p.room_epoch
           and t.fencing_token = p.fencing_token and t.thread_id = p.thread_id
           and t.actor_id = p.actor_id and t.actor_role = p.actor_role
           and t.actor_scope_hash = p.actor_scope_hash
           and t.agent_session_id = p.agent_session_id and t.audience = p.audience
           and t.registration_hash = p.registration_hash
          join case_access_session a
            on a.id = p.access_session_id and a.tenant_id = p.session_tenant_id
           and a.case_id = p.session_case_id and a.actor_id = p.actor_id
           and a.actor_role = p.actor_role and a.permission_level = p.permission_level
          join agent_conversation_session g
            on g.id = p.agent_session_id and g.tenant_id = p.session_tenant_id
           and g.case_id = p.session_case_id and g.room_type = p.room_type
           and g.access_session_id = p.access_session_id and g.actor_id = p.actor_id
           and g.actor_role = p.actor_role and g.agent_key = p.agent_key
           and g.prompt_profile_id = p.prompt_profile_id
           and g.memory_policy_id = p.memory_policy_id
         where p.epoch_id = ? and p.tenant_surrogate = ? and p.case_id = ?
           and p.room_type = 'INTAKE' and p.room_epoch = ? and p.fencing_token = ?
           and p.party in ('INITIATOR', 'RESPONDENT')
           and t.registration_status = 'REGISTERED' and a.status = 'ACTIVE' and g.status = 'ACTIVE'
         order by p.party
        """;

    private static final String COMMAND_SQL = """
        select ca.command_id, ca.case_command_sequence, ca.command_type,
               ca.epoch_id, ca.access_session_id, ca.registration_id,
               ca.tenant_surrogate, ca.case_id, ca.room_type, ca.room_epoch,
               ca.fencing_token, ca.thread_id, ca.actor_id, ca.actor_role,
               ca.actor_scope_hash, ca.agent_session_id, ca.payload_authority_id,
               ca.request_hash, ca.accepted_room_revision, ca.execution_disposition,
               pa.content_sha256, pa.source_kind, pa.schema_version as payload_schema_version,
               pa.object_uri as authority_payload_uri, pa.size_bytes as authority_payload_size_bytes,
               c.payload_uri, c.payload_size_bytes, c.expected_process_revision,
               c.deadline_at, c.command_type as case_command_type,
               p.party, t.registration_status, a.status as access_status, g.status as agent_status,
               t.graph_key, t.graph_version, t.thread_id as registered_thread_id,
               s.fencing_token as selected_fencing_token, s.case_workflow_type,
               s.case_workflow_build_id, s.room_workflow_type, s.room_workflow_build_id
          from case_intake_command_authority ca
          join case_intake_command_payload_authority pa
            on pa.payload_authority_id = ca.payload_authority_id
           and pa.epoch_id = ca.epoch_id and pa.party_authority_id = ca.party_authority_id
           and pa.access_session_id = ca.access_session_id
           and pa.registration_id = ca.registration_id
           and pa.tenant_surrogate = ca.tenant_surrogate and pa.case_id = ca.case_id
           and pa.room_type = ca.room_type and pa.room_epoch = ca.room_epoch
           and pa.fencing_token = ca.fencing_token and pa.thread_id = ca.thread_id
           and pa.actor_scope_hash = ca.actor_scope_hash
           and pa.agent_session_id = ca.agent_session_id and pa.command_id = ca.command_id
          join case_command c
            on c.id = ca.case_command_id and c.tenant_surrogate = ca.tenant_surrogate
           and c.case_id = ca.case_id and c.command_id = ca.command_id
           and c.request_hash = ca.request_hash
           and c.payload_schema_version = pa.schema_version
           and c.payload_uri = pa.object_uri and c.payload_sha256 = pa.content_sha256
           and c.payload_size_bytes = pa.size_bytes
          join case_intake_epoch_party_authority p
            on p.authority_id = ca.party_authority_id and p.epoch_id = ca.epoch_id
           and p.tenant_surrogate = ca.tenant_surrogate and p.case_id = ca.case_id
           and p.room_type = ca.room_type and p.room_epoch = ca.room_epoch
           and p.fencing_token = ca.fencing_token and p.access_session_id = ca.access_session_id
           and p.registration_id = ca.registration_id and p.thread_id = ca.thread_id
           and p.actor_id = ca.actor_id and p.actor_role = ca.actor_role
           and p.actor_scope_hash = ca.actor_scope_hash and p.agent_session_id = ca.agent_session_id
          join case_intake_graph_thread_binding t
            on t.registration_id = ca.registration_id and t.tenant_surrogate = ca.tenant_surrogate
           and t.case_id = ca.case_id and t.room_type = ca.room_type
           and t.room_epoch = ca.room_epoch and t.fencing_token = ca.fencing_token
           and t.thread_id = ca.thread_id and t.actor_id = ca.actor_id
           and t.actor_role = ca.actor_role and t.actor_scope_hash = ca.actor_scope_hash
           and t.agent_session_id = ca.agent_session_id and t.audience = p.audience
           and t.registration_hash = p.registration_hash
          join case_access_session a
            on a.id = ca.access_session_id and a.tenant_id = p.session_tenant_id
           and a.case_id = p.session_case_id and a.actor_id = ca.actor_id
           and a.actor_role = ca.actor_role and a.permission_level = p.permission_level
          join agent_conversation_session g
            on g.id = ca.agent_session_id and g.tenant_id = p.session_tenant_id
           and g.case_id = p.session_case_id and g.room_type = ca.room_type
           and g.access_session_id = ca.access_session_id and g.actor_id = ca.actor_id
           and g.actor_role = ca.actor_role and g.agent_key = p.agent_key
           and g.prompt_profile_id = p.prompt_profile_id and g.memory_policy_id = p.memory_policy_id
          join case_intake_epoch_selection_binding s
            on s.epoch_id = ca.epoch_id and s.tenant_surrogate = ca.tenant_surrogate
           and s.case_id = ca.case_id and s.room_type = ca.room_type
           and s.room_epoch = ca.room_epoch and s.fencing_token = ca.fencing_token
         where ca.tenant_surrogate = ? and ca.case_id = ? and ca.command_id = ?
           and ca.room_type = 'INTAKE' and ca.room_epoch = ?
           and ca.actor_id = ? and ca.actor_role = ?
           and t.registration_status = 'REGISTERED' and a.status = 'ACTIVE' and g.status = 'ACTIVE'
           and s.writer_mode = 'SHADOW'
        """;

    private static final String EVENT_SQL = """
        select b.binding_id, b.thread_registration_id, b.tenant_surrogate, b.case_id,
               b.room_type, b.room_epoch, b.fencing_token, b.thread_id, b.actor_scope_hash,
               b.agent_session_id, b.actor_audience, b.schema_version, b.artifact_id,
               b.object_uri, b.object_version, b.content_sha256, b.size_bytes,
               b.event_id, b.event_sequence, e.event_type, e.event_json::text,
               p.party, p.actor_id, p.actor_role, p.registration_id,
               p.access_session_id, t.registration_status, a.status as access_status,
               g.status as agent_status, s.writer_mode, s.case_workflow_type,
               s.case_workflow_build_id, s.room_workflow_type, s.room_workflow_build_id
          from case_intake_snapshot_binding b
          join case_timeline_event e on e.id = b.event_id and e.case_id = b.case_id
           and e.sequence_no = b.event_sequence
          join case_intake_epoch_selection_binding s
            on s.tenant_surrogate = b.tenant_surrogate and s.case_id = b.case_id
           and s.room_type = b.room_type and s.room_epoch = b.room_epoch
           and s.fencing_token = b.fencing_token
          join case_intake_epoch_party_authority p
            on p.epoch_id = s.epoch_id and p.registration_id = b.thread_registration_id
           and p.tenant_surrogate = b.tenant_surrogate and p.case_id = b.case_id
           and p.room_type = b.room_type and p.room_epoch = b.room_epoch
           and p.fencing_token = b.fencing_token and p.actor_scope_hash = b.actor_scope_hash
           and p.agent_session_id = b.agent_session_id and p.actor_role = b.actor_audience
          join case_intake_command_authority ca
            on ca.command_id = e.event_json ->> 'command_id' and ca.epoch_id = p.epoch_id
           and ca.party_authority_id = p.authority_id and ca.access_session_id = p.access_session_id
           and ca.registration_id = p.registration_id and ca.tenant_surrogate = p.tenant_surrogate
           and ca.case_id = p.case_id and ca.room_type = p.room_type and ca.room_epoch = p.room_epoch
           and ca.fencing_token = p.fencing_token and ca.thread_id = p.thread_id
           and ca.actor_id = p.actor_id and ca.actor_role = p.actor_role
           and ca.actor_scope_hash = p.actor_scope_hash and ca.agent_session_id = p.agent_session_id
           and ca.execution_disposition = 'INERT_EXTERNAL_EVENT'
          join case_intake_command_payload_authority pa
            on pa.payload_authority_id = ca.payload_authority_id and pa.command_id = ca.command_id
           and pa.epoch_id = ca.epoch_id and pa.party_authority_id = ca.party_authority_id
           and pa.access_session_id = ca.access_session_id and pa.registration_id = ca.registration_id
           and pa.tenant_surrogate = ca.tenant_surrogate and pa.case_id = ca.case_id
           and pa.room_type = ca.room_type and pa.room_epoch = ca.room_epoch
           and pa.fencing_token = ca.fencing_token and pa.thread_id = ca.thread_id
           and pa.actor_scope_hash = ca.actor_scope_hash and pa.agent_session_id = ca.agent_session_id
          join case_command c
            on c.id = ca.case_command_id and c.tenant_surrogate = ca.tenant_surrogate
           and c.case_id = ca.case_id and c.command_id = ca.command_id
           and c.request_hash = ca.request_hash
          join case_intake_graph_thread_binding t
            on t.registration_id = b.thread_registration_id
           and t.tenant_surrogate = b.tenant_surrogate and t.case_id = b.case_id
           and t.room_type = b.room_type and t.room_epoch = b.room_epoch
           and t.fencing_token = b.fencing_token and t.thread_id = b.thread_id
           and t.actor_scope_hash = b.actor_scope_hash and t.agent_session_id = b.agent_session_id
           and t.actor_id = p.actor_id and t.actor_role = p.actor_role and t.audience = p.audience
           and t.registration_hash = p.registration_hash
          join case_access_session a on a.id = p.access_session_id
           and a.tenant_id = p.session_tenant_id and a.case_id = p.session_case_id
           and a.actor_id = p.actor_id and a.actor_role = p.actor_role
           and a.permission_level = p.permission_level
          join agent_conversation_session g on g.id = p.agent_session_id
           and g.tenant_id = p.session_tenant_id and g.case_id = p.session_case_id
           and g.room_type = p.room_type and g.access_session_id = p.access_session_id
           and g.actor_id = p.actor_id and g.actor_role = p.actor_role
           and g.agent_key = p.agent_key and g.prompt_profile_id = p.prompt_profile_id
           and g.memory_policy_id = p.memory_policy_id
         where b.binding_type = 'EVENT' and b.schema_version = 'intake-turn-event.v2'
           and b.event_id = ? and b.tenant_surrogate = ? and b.case_id = ?
           and b.room_type = 'INTAKE' and b.room_epoch = ? and b.event_sequence = ?
           and t.registration_status = 'REGISTERED' and a.status = 'ACTIVE' and g.status = 'ACTIVE'
           and s.writer_mode = 'SHADOW'
        """;

    private final DataSource dataSource;

    public JdbcIntakeChildBridgeReadPort(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public StartSource readStart(StartRequest request) {
        Objects.requireNonNull(request, "request");
        ProvisionRoomEpoch provision = request.provisioning();
        return inSnapshot(connection -> readStart(connection, provision));
    }

    @Override
    public CommandSource readCommand(CommandRequest request) {
        Objects.requireNonNull(request, "request");
        CaseCommandRef command = request.command();
        return inSnapshot(connection -> readCommand(connection, command));
    }

    @Override
    public DomainEventSource readDomainEvent(DomainEventRequest request) {
        Objects.requireNonNull(request, "request");
        CaseDomainEventRef event = request.event();
        return inSnapshot(connection -> readDomainEvent(connection, event));
    }

    private StartSource readStart(Connection connection, ProvisionRoomEpoch provision)
            throws SQLException {
        EpochRow epoch = exactlyOne(connection, EPOCH_SQL, "epoch selection", statement -> {
            statement.setString(1, provision.epochId());
            statement.setString(2, provision.tenantSurrogate());
            statement.setString(3, provision.caseId());
            statement.setLong(4, provision.roomEpoch());
            statement.setLong(5, provision.fencingToken());
        }, JdbcIntakeChildBridgeReadPort::mapEpoch);
        List<PartyRow> parties = all(connection, PARTY_SQL, "epoch party authority", statement -> {
            statement.setString(1, provision.epochId());
            statement.setString(2, provision.tenantSurrogate());
            statement.setString(3, provision.caseId());
            statement.setLong(4, provision.roomEpoch());
            statement.setLong(5, provision.fencingToken());
        }, JdbcIntakeChildBridgeReadPort::mapParty);
        if (parties.size() != 2 || parties.stream().map(PartyRow::party).distinct().count() != 2) {
            throw new IntakeAuthorityInvariantException("epoch must have exactly one initiator and respondent");
        }
        PartyRow initiator = party(parties, IntakeParty.INITIATOR);
        PartyRow respondent = party(parties, IntakeParty.RESPONDENT);
        requireEquals(epoch.writerMode(), "SHADOW", "writer mode");
        requireEquals(epoch.roomType(), "INTAKE", "room type");
        requireEquals(epoch.graphKey(), "intake.v2", "graph key");
        requireEquals(epoch.stateSchemaVersion(), "intake-graph-state.v2", "state schema");
        requireEquals(epoch.outputSchemaVersion(), "intake-turn-proposal.v2", "output schema");
        for (PartyRow row : parties) {
            requireEquals(row.graphKey(), epoch.graphKey(), "party graph key");
            requireEquals(row.graphVersion(), epoch.graphVersion(), "party graph version");
            requireEquals(row.promptVersion(), epoch.promptVersion(), "party prompt version");
            requireEquals(row.modelProfileId(), epoch.modelProfileId(), "party model profile");
            requireEquals(row.outputSchemaVersion(), epoch.outputSchemaVersion(), "party output schema");
            requireEquals(row.policyVersion(), epoch.policyVersion(), "party policy version");
            requireEquals(row.guardrailVersion(), epoch.guardrailVersion(), "party guardrail version");
            requireEquals(row.toolPolicyVersion(), epoch.toolPolicyVersion(), "party tool policy");
        }
        ActiveChildBinding binding = binding(epoch);
        return new StartSource(binding, epoch.selectionHash(), epoch.promptVersion(), epoch.modelProfileId(),
                epoch.outputSchemaVersion(), epoch.policyVersion(), epoch.guardrailVersion(),
                epoch.toolPolicyVersion(), initiator.actorScopeHash(), respondent.actorScopeHash());
    }

    private CommandSource readCommand(Connection connection, CaseCommandRef command) throws SQLException {
        CommandRow row = exactlyOne(connection, COMMAND_SQL, "command authority", statement -> {
            statement.setString(1, command.tenantSurrogate());
            statement.setString(2, command.caseId());
            statement.setString(3, command.commandId());
            statement.setLong(4, command.roomEpoch());
            statement.setString(5, command.actorRef().actorId());
            statement.setString(6, command.actorRef().actorRole().name());
        }, JdbcIntakeChildBridgeReadPort::mapCommand);
        requireEquals(row.commandId(), command.commandId(), "command id");
        requireEquals(row.tenantSurrogate(), command.tenantSurrogate(), "tenant");
        requireEquals(row.caseId(), command.caseId(), "case");
        requireEquals(row.roomEpoch(), command.roomEpoch(), "room epoch");
        requireEquals(row.commandType(), command.commandType().name(), "command type");
        requireEquals(row.payloadSchemaVersion(), command.payloadRef().schemaVersion(), "payload schema");
        requireEquals(row.payloadUri(), command.payloadRef().uri(), "payload URI");
        requireEquals(row.payloadSha256(), command.payloadRef().sha256(), "payload hash");
        requireEquals(row.payloadSizeBytes(), command.payloadRef().sizeBytes(), "payload size");
        requireEquals(row.requestHash(), command.requestHash(), "request hash");
        requireEquals(row.executionDisposition(), INERT_DISPOSITION, "execution disposition");
        IntakeParty party = intakeParty(row.party());
        ActorRole actorRole = actorRole(row.actorRole());
        requirePartyRole(party, actorRole);
        // Inert external-event reads intentionally expose no activity execution context.
        return new CommandSource(binding(row), row.commandId(), row.tenantSurrogate(), row.caseId(),
                row.roomEpoch(), row.fencingToken(), row.sequence(), commandType(row.commandType()),
                row.payloadSha256(), row.requestHash(), row.processRevision(), row.roomRevision(),
                party, row.actorScopeHash(), operationKey(row.caseId(), row.commandId()), null);
    }

    private DomainEventSource readDomainEvent(Connection connection, CaseDomainEventRef event)
            throws SQLException {
        EventRow row = exactlyOne(connection, EVENT_SQL, "event authority", statement -> {
            statement.setString(1, event.eventId());
            statement.setString(2, event.tenantSurrogate());
            statement.setString(3, event.caseId());
            statement.setLong(4, event.roomEpoch());
            statement.setLong(5, event.caseEventSequence());
        }, JdbcIntakeChildBridgeReadPort::mapEvent);
        requireEquals(row.tenantSurrogate(), event.tenantSurrogate(), "tenant");
        requireEquals(row.caseId(), event.caseId(), "case");
        requireEquals(row.roomEpoch(), event.roomEpoch(), "room epoch");
        requireEquals(row.sequence(), event.caseEventSequence(), "event sequence");
        requireEquals(row.schemaVersion(), "intake-turn-event.v2", "event schema");
        requireEquals(row.schemaVersion(), event.payloadRef().schemaVersion(), "event payload schema");
        requireEquals(row.objectUri(), event.payloadRef().uri(), "event payload URI");
        requireEquals(row.contentSha256(), event.payloadRef().sha256(), "event payload hash");
        requireEquals(row.sizeBytes(), event.payloadRef().sizeBytes(), "event payload size");
        String eventHash = sha256(row.eventJson());
        JsonNode json = parseEventJson(row.eventJson());
        String actorScope = text(json, "actor_scope_hash", row.actorScopeHash());
        String requestHash = hash(json, "request_hash");
        String resultHash = hash(json, "operation_result_hash", "result_hash");
        String commandId = identifier(json, "command_id", "commandId");
        String operationKey = text(json, "operation_key", "operationKey");
        requireHash(actorScope, "actor scope");
        requireHash(requestHash, "request hash");
        requireHash(resultHash, "result hash");
        requireIdentifier(commandId, "command id");
        requireIdentifier(operationKey, "operation key");
        IntakeParty party = intakeParty(row.party());
        IntakeDomainEventType eventType = eventType(row.eventType());
        IntakeAgentRunRef agentRun = agentRun(json, eventType);
        IntakeGraphExecutionRef graph = graphExecution(json, eventType, row.threadId());
        return new DomainEventSource(binding(row), row.eventId(), row.eventType(), eventType,
                row.tenantSurrogate(), row.caseId(), row.roomEpoch(), row.fencingToken(), row.sequence(),
                row.contentSha256(), row.objectUri(), eventHash, party, commandId, actorScope,
                operationKey, requestHash, resultHash, jsonLong(json, "process_revision", 0),
                jsonLong(json, "room_revision", 0), agentRun, graph);
    }

    private <T> T inSnapshot(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(REQUIRED_ISOLATION);
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                throw failure;
            }
        } catch (IntakeAuthorityInvariantException exception) {
            throw exception;
        } catch (SQLException exception) {
            if (retryable(exception)) {
                throw new ReadUnavailableException("authority snapshot is temporarily unavailable", exception);
            }
            throw new IntakeAuthorityInvariantException("authority snapshot read failed", exception);
        }
    }

    private static boolean retryable(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && (state.startsWith("08") || state.startsWith("40"));
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original read failure remains the actionable error.
        }
    }

    private static <T> T exactlyOne(Connection connection, String sql, String tuple,
            Binder binder, Mapper<T> mapper) throws SQLException {
        List<T> rows = all(connection, sql, tuple, binder, mapper);
        if (rows.size() != 1) {
            throw new IntakeAuthorityInvariantException(tuple + " expected one row, found " + rows.size());
        }
        return rows.getFirst();
    }

    private static <T> List<T> all(Connection connection, String sql, String tuple,
            Binder binder, Mapper<T> mapper) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(mapper.map(result));
                    if (rows.size() > 2) {
                        throw new IntakeAuthorityInvariantException(tuple + " is ambiguous");
                    }
                }
                return rows;
            }
        }
    }

    private static EpochRow mapEpoch(ResultSet r) throws SQLException {
        return new EpochRow(r.getString("epoch_id"), r.getString("tenant_surrogate"), r.getString("case_id"),
                r.getString("room_type"), r.getLong("room_epoch"), r.getLong("fencing_token"),
                r.getString("selection_hash"), r.getString("writer_mode"), r.getString("case_workflow_type"),
                r.getString("case_workflow_build_id"), r.getString("room_workflow_type"),
                r.getString("room_workflow_build_id"), r.getString("process_contract_version"), r.getString("graph_key"),
                r.getString("graph_version"), r.getString("checkpoint_schema_version"), r.getString("state_schema_version"),
                r.getString("stream_protocol"), r.getString("prompt_version"), r.getString("model_profile_id"),
                r.getString("output_schema_version"), r.getString("policy_version"), r.getString("guardrail_version"),
                r.getString("tool_policy_version"), r.getString("cohort_policy_version"), r.getString("agent_key"),
                r.getString("agent_session_profile_version"), r.getString("memory_policy_id"));
    }

    private static PartyRow mapParty(ResultSet r) throws SQLException {
        return new PartyRow(r.getString("party"), r.getString("tenant_surrogate"), r.getString("case_id"),
                r.getString("session_tenant_id"), r.getString("session_case_id"), r.getString("room_type"),
                r.getLong("room_epoch"), r.getLong("fencing_token"), r.getString("registration_id"),
                r.getString("registration_hash"), r.getString("thread_id"), r.getString("actor_id"),
                r.getString("actor_role"), r.getString("audience"), r.getString("actor_scope_hash"),
                r.getString("access_session_id"), r.getString("permission_level"), r.getString("agent_session_id"),
                r.getString("agent_key"), r.getString("prompt_version"), r.getString("agent_session_profile_version"),
                r.getString("prompt_profile_id"), r.getString("memory_policy_id"), r.getString("graph_key"),
                r.getString("graph_version"), r.getString("checkpoint_schema_version"), r.getString("state_schema_version"),
                r.getString("registration_prompt_version"), r.getString("registration_model_profile_id"),
                r.getString("output_schema_version"), r.getString("policy_version"), r.getString("guardrail_version"),
                r.getString("tool_policy_version"));
    }

    private static CommandRow mapCommand(ResultSet r) throws SQLException {
        return new CommandRow(r.getString("command_id"), r.getLong("case_command_sequence"), r.getString("command_type"),
                r.getString("epoch_id"), r.getString("access_session_id"), r.getString("registration_id"),
                r.getString("tenant_surrogate"), r.getString("case_id"), r.getString("room_type"), r.getLong("room_epoch"),
                r.getLong("fencing_token"), r.getString("thread_id"), r.getString("actor_id"), r.getString("actor_role"),
                r.getString("actor_scope_hash"), r.getString("agent_session_id"), r.getString("payload_authority_id"),
                r.getString("request_hash"), r.getLong("accepted_room_revision"), r.getString("execution_disposition"),
                r.getString("content_sha256"), r.getString("source_kind"), r.getString("payload_schema_version"),
                r.getString("payload_uri"), r.getLong("payload_size_bytes"), r.getLong("expected_process_revision"),
                r.getString("party"), r.getString("case_workflow_type"),
                r.getString("case_workflow_build_id"), r.getString("room_workflow_type"),
                r.getString("room_workflow_build_id"));
    }

    private static EventRow mapEvent(ResultSet r) throws SQLException {
        return new EventRow(r.getString("binding_id"), r.getString("thread_registration_id"), r.getString("tenant_surrogate"),
                r.getString("case_id"), r.getString("room_type"), r.getLong("room_epoch"), r.getLong("fencing_token"),
                r.getString("thread_id"), r.getString("actor_scope_hash"), r.getString("agent_session_id"),
                r.getString("actor_audience"), r.getString("schema_version"), r.getString("artifact_id"),
                r.getString("object_uri"), r.getString("object_version"), r.getString("content_sha256"),
                r.getLong("size_bytes"),
                r.getString("event_id"), r.getLong("event_sequence"), r.getString("event_type"),
                r.getString("event_json"), r.getString("party"), r.getString("case_workflow_type"),
                r.getString("case_workflow_build_id"), r.getString("room_workflow_type"),
                r.getString("room_workflow_build_id"));
    }

    private static ActiveChildBinding binding(EpochRow row) {
        return new ActiveChildBinding("active-intake-child-binding.v1", row.tenantSurrogate(), row.caseId(),
                row.roomEpoch(), row.fencingToken(), "room-epoch-selection.v2", row.caseWorkflowType(),
                row.caseWorkflowBuildId(), row.roomWorkflowType(), row.roomWorkflowBuildId());
    }

    private static ActiveChildBinding binding(CommandRow row) {
        // The command query binds the immutable epoch tuple; selection details are only used for checks.
        return new ActiveChildBinding("active-intake-child-binding.v1", row.tenantSurrogate(), row.caseId(),
                row.roomEpoch(), row.fencingToken(), "room-epoch-selection.v2",
                row.caseWorkflowType(), row.caseWorkflowBuildId(), row.roomWorkflowType(), row.roomWorkflowBuildId());
    }

    private static ActiveChildBinding binding(EventRow row) {
        return new ActiveChildBinding("active-intake-child-binding.v1", row.tenantSurrogate(), row.caseId(),
                row.roomEpoch(), row.fencingToken(), "room-epoch-selection.v2",
                row.caseWorkflowType(), row.caseWorkflowBuildId(), row.roomWorkflowType(), row.roomWorkflowBuildId());
    }

    private static PartyRow party(List<PartyRow> rows, IntakeParty party) {
        return rows.stream().filter(row -> party.name().equals(row.party())).findFirst()
                .orElseThrow(() -> new IntakeAuthorityInvariantException("missing " + party + " party"));
    }

    private static IntakeParty intakeParty(String party) {
        try { return IntakeParty.valueOf(party); }
        catch (RuntimeException exception) { throw new IntakeAuthorityInvariantException("invalid party", exception); }
    }

    private static ActorRole actorRole(String role) {
        try { return ActorRole.valueOf(role); }
        catch (RuntimeException exception) { throw new IntakeAuthorityInvariantException("invalid actor role", exception); }
    }

    private static void requirePartyRole(IntakeParty party, ActorRole role) {
        if ((party == IntakeParty.INITIATOR || party == IntakeParty.RESPONDENT)
                && role != ActorRole.USER && role != ActorRole.MERCHANT) {
            throw new IntakeAuthorityInvariantException("party authority actor role is not a participant");
        }
    }

    private static CommandType commandType(String value) {
        try {
            CommandType type = CommandType.valueOf(value);
            if (type != CommandType.INTAKE_MESSAGE && type != CommandType.INTAKE_CONFIRM && type != CommandType.INTAKE_CANCEL) {
                throw new IllegalArgumentException("unsupported Intake command");
            }
            return type;
        } catch (RuntimeException exception) {
            throw new IntakeAuthorityInvariantException("invalid Intake command type", exception);
        }
    }

    private static IntakeDomainEventType eventType(String value) {
        return switch (value) {
            case "TURN_NEEDS_INPUT", "INTAKE_TURN_NEEDS_INPUT" -> IntakeDomainEventType.TURN_NEEDS_INPUT;
            case "TURN_READY_TO_CONFIRM", "INTAKE_TURN_READY_TO_CONFIRM" -> IntakeDomainEventType.TURN_READY_TO_CONFIRM;
            case "INITIATOR_ACCEPTED", "INITIATOR_INTAKE_COMPLETED" -> IntakeDomainEventType.INITIATOR_ACCEPTED;
            case "NOT_ADMISSIBLE", "INTAKE_REJECTED" -> IntakeDomainEventType.NOT_ADMISSIBLE;
            case "CANCELLED", "INTAKE_CANCELLED" -> IntakeDomainEventType.CANCELLED;
            case "RESPONDENT_CONFIRMED", "RESPONDENT_INTAKE_COMPLETED" -> IntakeDomainEventType.RESPONDENT_CONFIRMED;
            default -> throw new IntakeAuthorityInvariantException("unsupported Intake event type");
        };
    }

    private static IntakeAgentRunRef agentRun(JsonNode json, IntakeDomainEventType type) {
        if (type == IntakeDomainEventType.TURN_NEEDS_INPUT
                || type == IntakeDomainEventType.TURN_READY_TO_CONFIRM) {
            String logical = identifier(json, "logical_agent_run_id", "logicalAgentRunId");
            String attempt = identifier(json, "attempt_id", "attemptId");
            String hash = hash(json, "final_result_hash", "finalResultHash");
            return new IntakeAgentRunRef("intake-agent-run-ref.v1", logical, attempt, hash);
        }
        return null;
    }

    private static IntakeGraphExecutionRef graphExecution(JsonNode json, IntakeDomainEventType type, String threadId) {
        if (type != IntakeDomainEventType.TURN_NEEDS_INPUT && type != IntakeDomainEventType.TURN_READY_TO_CONFIRM) return null;
        String eventThread = identifier(json, "thread_id", "threadId");
        requireEquals(eventThread, threadId, "graph thread");
        return new IntakeGraphExecutionRef("intake-graph-execution-ref.v1", eventThread,
                identifier(json, "graph_command_id", "graphCommandId"), "intake.v2",
                identifier(json, "graph_version", "graphVersion"), identifier(json, "checkpoint_id", "checkpointId"),
                reference(json, "result_ref", "resultRef"), hash(json, "result_hash"),
                reference(json, "proposal_ref", "proposalRef"), hash(json, "proposal_hash"));
    }

    private static JsonNode parseEventJson(String value) {
        try { return MAPPER.readTree(value); }
        catch (Exception exception) { throw new IntakeAuthorityInvariantException("malformed event JSON", exception); }
    }

    private static String reference(JsonNode json, String... names) { return text(json, names, null); }
    private static String identifier(JsonNode json, String... names) {
        String value = text(json, names, null);
        requireIdentifier(value, names[0]);
        return value;
    }
    private static String hash(JsonNode json, String... names) {
        String value = text(json, names, null);
        requireHash(value, names[0]);
        return value;
    }
    private static String text(JsonNode json, String name, String fallback) { return text(json, new String[] {name}, fallback); }
    private static String text(JsonNode json, String[] names, String fallback) {
        for (String name : names) if (json.hasNonNull(name)) return json.get(name).asText();
        return fallback;
    }
    private static long jsonLong(JsonNode json, String name, long fallback) { return json.has(name) ? json.get(name).asLong() : fallback; }

    private static String operationKey(String caseId, String commandId) { return "intake.operation:" + caseId + ":" + commandId; }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    private static void requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw new IntakeAuthorityInvariantException(field + " is invalid");
    }
    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IntakeAuthorityInvariantException(field + " is invalid");
    }
    private static void requireEquals(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) throw new IntakeAuthorityInvariantException(field + " mismatch");
    }

    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
    @FunctionalInterface private interface Binder { void bind(PreparedStatement statement) throws SQLException; }
    @FunctionalInterface private interface Mapper<T> { T map(ResultSet result) throws SQLException; }

    private record EpochRow(String epochId, String tenantSurrogate, String caseId, String roomType, long roomEpoch,
            long fencingToken, String selectionHash, String writerMode, String caseWorkflowType, String caseWorkflowBuildId,
            String roomWorkflowType, String roomWorkflowBuildId, String processContractVersion, String graphKey,
            String graphVersion, String checkpointSchemaVersion, String stateSchemaVersion, String streamProtocol,
            String promptVersion, String modelProfileId, String outputSchemaVersion, String policyVersion,
            String guardrailVersion, String toolPolicyVersion, String cohortPolicyVersion, String agentKey,
            String agentSessionProfileVersion, String memoryPolicyId) {}

    private record PartyRow(String party, String tenantSurrogate, String caseId, String sessionTenantId,
            String sessionCaseId, String roomType, long roomEpoch, long fencingToken, String registrationId,
            String registrationHash, String threadId, String actorId, String actorRole, String audience,
            String actorScopeHash, String accessSessionId, String permissionLevel, String agentSessionId,
            String agentKey, String promptVersion, String agentSessionProfileVersion, String promptProfileId,
            String memoryPolicyId, String graphKey, String graphVersion, String checkpointSchemaVersion,
            String stateSchemaVersion, String registrationPromptVersion, String registrationModelProfileId,
            String outputSchemaVersion, String policyVersion, String guardrailVersion, String toolPolicyVersion) {
        String modelProfileId() {
            return registrationModelProfileId;
        }
    }

    private record CommandRow(String commandId, long sequence, String commandType, String epochId, String accessSessionId,
            String registrationId, String tenantSurrogate, String caseId, String roomType, long roomEpoch,
            long fencingToken, String threadId, String actorId, String actorRole, String actorScopeHash,
            String agentSessionId, String payloadAuthorityId, String requestHash, long roomRevision,
            String executionDisposition, String payloadSha256, String sourceKind, String payloadSchemaVersion,
            String payloadUri, long payloadSizeBytes, long processRevision, String party,
            String caseWorkflowType, String caseWorkflowBuildId, String roomWorkflowType,
            String roomWorkflowBuildId) {}

    private record EventRow(String bindingId, String registrationId, String tenantSurrogate, String caseId,
            String roomType, long roomEpoch, long fencingToken, String threadId, String actorScopeHash,
            String agentSessionId, String actorAudience, String schemaVersion, String artifactId, String objectUri,
            String objectVersion, String contentSha256, long sizeBytes, String eventId, long sequence, String eventType,
            String eventJson, String party, String caseWorkflowType, String caseWorkflowBuildId,
            String roomWorkflowType, String roomWorkflowBuildId) {}
}
