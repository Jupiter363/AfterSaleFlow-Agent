package com.example.dispute.workflow.shadow.intake.admission;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.CommandSource;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockRequest;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.ActivityAuthorization;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.AdmissionAttempt;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.VerifiedAdmission;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeCommandAdmissionLookup;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeCommandAdmissionLookup.PersistedCommandAdmission;
import com.example.dispute.workflow.shadow.intake.admission.Es256IntakeSyntheticAdmissionVerifier.VerifiedToken;
import com.example.dispute.workflow.shadow.intake.admission.IntakeSyntheticAdmissionClaims.Pins;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL-backed admission boundary. It is deliberately not a discoverable Spring bean. */
public final class JdbcIntakeSignedSyntheticAdmissionPort
        implements IntakeSignedSyntheticAdmissionPort, SignedSyntheticIntakeCommandAdmissionLookup {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String EXACT_AUTHORITY_SQL = """
            select s.epoch_id,
                   p.authority_id as party_authority_id,
                   p.access_session_id,
                   p.registration_id,
                   p.actor_id,
                   p.actor_role,
                   c.case_command_id,
                   c.payload_authority_id,
                   c.accepted_room_revision
              from case_intake_epoch_selection_binding s
              join case_room_epoch e
                on e.id = s.epoch_id
               and e.tenant_surrogate = s.tenant_surrogate
               and e.case_id = s.case_id
               and e.room_type = s.room_type
               and e.room_epoch = s.room_epoch
               and e.fencing_token = s.fencing_token
              join case_intake_epoch_party_authority p
                on p.epoch_id = s.epoch_id
               and p.tenant_surrogate = s.tenant_surrogate
               and p.case_id = s.case_id
               and p.room_type = s.room_type
               and p.room_epoch = s.room_epoch
               and p.fencing_token = s.fencing_token
              join case_intake_command_authority c
                on c.party_authority_id = p.authority_id
               and c.epoch_id = p.epoch_id
               and c.access_session_id = p.access_session_id
               and c.registration_id = p.registration_id
               and c.tenant_surrogate = p.tenant_surrogate
               and c.case_id = p.case_id
               and c.room_type = p.room_type
               and c.room_epoch = p.room_epoch
               and c.fencing_token = p.fencing_token
               and c.thread_id = p.thread_id
               and c.actor_id = p.actor_id
               and c.actor_role = p.actor_role
               and c.actor_scope_hash = p.actor_scope_hash
               and c.agent_session_id = p.agent_session_id
              join case_intake_command_payload_authority payload
                on payload.payload_authority_id = c.payload_authority_id
               and payload.command_id = c.command_id
               and payload.epoch_id = c.epoch_id
               and payload.party_authority_id = c.party_authority_id
               and payload.access_session_id = c.access_session_id
               and payload.registration_id = c.registration_id
               and payload.tenant_surrogate = c.tenant_surrogate
               and payload.case_id = c.case_id
               and payload.room_type = c.room_type
               and payload.room_epoch = c.room_epoch
               and payload.fencing_token = c.fencing_token
               and payload.thread_id = c.thread_id
               and payload.actor_scope_hash = c.actor_scope_hash
               and payload.agent_session_id = c.agent_session_id
              join case_command source_command on source_command.id = c.case_command_id
              join case_intake_graph_thread_binding registration
                on registration.registration_id = p.registration_id
               and registration.registration_hash = p.registration_hash
              join case_access_session access_row on access_row.id = p.access_session_id
              join agent_conversation_session agent on agent.id = p.agent_session_id
             where s.tenant_surrogate = :tenant
               and s.case_id = :caseId
               and s.room_type = 'INTAKE'
               and s.writer_mode = 'SHADOW'
               and s.room_epoch = :roomEpoch
               and s.fencing_token = :fence
               and s.selection_hash = :selectionHash
               and s.case_workflow_type = :caseWorkflowType
               and s.case_workflow_build_id = :caseWorkflowBuildId
               and s.room_workflow_type = :roomWorkflowType
               and s.room_workflow_build_id = :roomWorkflowBuildId
               and s.process_contract_version = :processContractVersion
               and s.graph_key = :graphKey
               and s.graph_version = :graphVersion
               and s.checkpoint_schema_version = :checkpointSchemaVersion
               and s.state_schema_version = :stateSchemaVersion
               and s.stream_protocol = :streamProtocol
               and s.prompt_version = :promptVersion
               and s.model_profile_id = :modelProfileId
               and s.output_schema_version = :outputSchemaVersion
               and s.policy_version = :policyVersion
               and s.guardrail_version = :guardrailVersion
               and s.tool_policy_version = :toolPolicyVersion
               and s.cohort_policy_version = :cohortPolicyVersion
               and s.agent_key = :agentKey
               and s.agent_session_profile_version = :agentSessionProfileVersion
               and s.memory_policy_id = :memoryPolicyId
               and e.writer_mode = 'SHADOW'
               and e.lifecycle_status = 'ACTIVE'
               and e.provisioning_status = 'READY'
               and e.process_revision = :processRevision
               and e.room_revision = :roomRevision
               and p.party = :party
               and p.registration_hash = :registrationHash
               and p.thread_id = :threadId
               and p.actor_scope_hash = :actorScopeHash
               and p.agent_session_id = :agentSessionId
               and p.prompt_version = :promptVersion
               and p.agent_key = :agentKey
               and p.agent_session_profile_version = :agentSessionProfileVersion
               and p.memory_policy_id = :memoryPolicyId
               and c.command_id = :commandId
               and c.case_command_sequence = :commandSequence
               and c.command_type = :commandType
               and c.request_hash = :requestHash
               and c.accepted_room_revision = :roomRevision
               and c.execution_disposition = 'INERT_EXTERNAL_EVENT'
               and source_command.expected_process_revision = :processRevision
               and source_command.payload_schema_version = 'intake-turn-event.v2'
               and payload.source_kind = 'EXISTING_PRIVATE_EVENT'
               and payload.schema_version = 'intake-turn-event.v2'
               and payload.object_uri = :payloadRef
               and payload.content_sha256 = :payloadHash
               and registration.registration_status = 'REGISTERED'
               and registration.writer_mode = 'SHADOW'
               and registration.graph_key = :graphKey
               and registration.graph_version = :graphVersion
               and registration.checkpoint_schema_version = :checkpointSchemaVersion
               and registration.state_schema_version = :stateSchemaVersion
               and registration.prompt_version = :promptVersion
               and registration.model_profile_id = :modelProfileId
               and registration.output_schema_version = :outputSchemaVersion
               and registration.policy_version = :policyVersion
               and registration.guardrail_version = :guardrailVersion
               and registration.tool_policy_version = :toolPolicyVersion
               and access_row.status = 'ACTIVE'
               and agent.status = 'ACTIVE'
            """;

    private static final String INSERT_SQL = """
            insert into case_intake_synthetic_activity_admission (
                receipt_id, schema_version, admission_status, authorization_hash, envelope_hash,
                token_algorithm, token_type, signing_key_id, jwt_id, claims_schema_version,
                issuer, audience, subject, issued_at_epoch_seconds, not_before_epoch_seconds,
                expires_at_epoch_seconds, traffic_source, epoch_id, party_authority_id,
                case_command_id, payload_authority_id, access_session_id, registration_id,
                tenant_surrogate, case_id, room_type, writer_mode, room_epoch, fencing_token,
                thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id, command_id,
                command_sequence, command_type, party, accepted_room_revision, payload_ref,
                payload_hash, command_operation_key, request_hash, process_revision, room_revision,
                deadline_epoch_millis,
                retry_provider_attempts, retry_activity_attempts, retry_repairs, logical_run_id,
                attempt_id, selection_hash, registration_hash, case_workflow_type,
                case_workflow_build_id, room_workflow_type, room_workflow_build_id,
                process_contract_version, graph_key, graph_version, checkpoint_schema_version,
                state_schema_version, stream_protocol, prompt_version, model_profile_id,
                output_schema_version, policy_version, guardrail_version, tool_policy_version,
                cohort_policy_version, agent_key, agent_session_profile_version, memory_policy_id,
                pinned_versions, parity_baseline_ref, parity_baseline_hash, admitted_at
            ) values (
                :receiptId, 'intake-synthetic-activity-admission.v1', 'VERIFIED',
                :authorizationHash, :envelopeHash, 'ES256', 'intake-synthetic-admission+jwt',
                :keyId, :jwtId, :claimsSchemaVersion, :issuer, :audience, :subject,
                :issuedAt, :notBefore, :expiresAt, 'AUTHENTICATED_SIGNED_SYNTHETIC',
                :epochId, :partyAuthorityId, :caseCommandId, :payloadAuthorityId,
                :accessSessionId, :registrationId, :tenant, :caseId, 'INTAKE', 'SHADOW',
                :roomEpoch, :fence, :threadId, :actorId, :actorRole, :actorScopeHash,
                :agentSessionId, :commandId, :commandSequence, :commandType, :party,
                :acceptedRoomRevision, :payloadRef, :payloadHash, :commandOperationKey,
                :requestHash, :processRevision, :roomRevision, :deadlineEpochMillis,
                :retryProvider, :retryActivity,
                :retryRepairs, :logicalRunId, :attemptId, :selectionHash, :registrationHash,
                :caseWorkflowType, :caseWorkflowBuildId, :roomWorkflowType,
                :roomWorkflowBuildId, :processContractVersion, :graphKey, :graphVersion,
                :checkpointSchemaVersion, :stateSchemaVersion, :streamProtocol, :promptVersion,
                :modelProfileId, :outputSchemaVersion, :policyVersion, :guardrailVersion,
                :toolPolicyVersion, :cohortPolicyVersion, :agentKey,
                :agentSessionProfileVersion, :memoryPolicyId, cast(:pinnedVersions as jsonb),
                :parityBaselineRef, :parityBaselineHash, current_timestamp
            ) on conflict do nothing
            """;

    private static final String EXISTING_SQL = """
            select envelope_hash, signing_key_id, jwt_id
              from case_intake_synthetic_activity_admission
             where authorization_hash = :authorizationHash
            """;

    private static final String ACTIVITY_RECEIPT_SQL = """
            select epoch_id, access_session_id, agent_session_id, registration_id,
                   authorization_hash
              from case_intake_synthetic_activity_admission
             where schema_version = 'intake-synthetic-activity-admission.v1'
               and admission_status = 'VERIFIED'
               and traffic_source = 'AUTHENTICATED_SIGNED_SYNTHETIC'
               and tenant_surrogate = :tenant
               and case_id = :caseId
               and room_epoch = :roomEpoch
               and fencing_token = :fence
               and command_id = :commandId
               and command_sequence = :commandSequence
               and command_type = :commandType
               and party = :party
               and payload_ref = :payloadRef
               and payload_hash = :payloadHash
               and command_operation_key = :commandOperationKey
               and process_revision = :processRevision
               and room_revision = :roomRevision
               and actor_scope_hash = :actorScopeHash
               and request_hash = :requestHash
               and thread_id = :threadId
               and agent_session_id = :agentSessionId
               and deadline_epoch_millis = :deadlineEpochMillis
               and retry_provider_attempts >= :retryProvider
               and retry_activity_attempts >= :retryActivity
               and retry_repairs >= :retryRepairs
               and room_workflow_build_id = :workflowBuildId
               and graph_version = :graphVersion
               and checkpoint_schema_version = :checkpointSchemaVersion
               and prompt_version = :promptVersion
               and model_profile_id = :modelProfileId
               and output_schema_version = :outputSchemaVersion
               and policy_version = :policyVersion
               and guardrail_version = :guardrailVersion
               and tool_policy_version = :toolPolicyVersion
               and deadline_epoch_millis > :nowEpochMillis
            """;

    private static final String CURRENT_AUTHORITY_COUNT_SQL = """
            select count(*)
              from case_intake_synthetic_activity_admission admission
              join case_room_epoch epoch
                on epoch.id = admission.epoch_id
               and epoch.tenant_surrogate = admission.tenant_surrogate
               and epoch.case_id = admission.case_id
               and epoch.room_type = admission.room_type
               and epoch.room_epoch = admission.room_epoch
               and epoch.fencing_token = admission.fencing_token
              join case_intake_epoch_selection_binding selection
                on selection.epoch_id = admission.epoch_id
               and selection.selection_hash = admission.selection_hash
               and selection.writer_mode = admission.writer_mode
              join case_intake_epoch_party_authority party_authority
                on party_authority.authority_id = admission.party_authority_id
               and party_authority.registration_hash = admission.registration_hash
              join case_intake_command_authority command_authority
                on command_authority.case_command_id = admission.case_command_id
               and command_authority.command_id = admission.command_id
               and command_authority.request_hash = admission.request_hash
              join case_intake_graph_thread_binding registration
                on registration.registration_id = admission.registration_id
               and registration.registration_hash = admission.registration_hash
              join case_access_session access_row
                on access_row.id = admission.access_session_id
              join agent_conversation_session agent
                on agent.id = admission.agent_session_id
             where admission.epoch_id = :epochId
               and admission.access_session_id = :accessSessionId
               and admission.agent_session_id = :agentSessionId
               and admission.registration_id = :registrationId
               and admission.authorization_hash = :authorizationHash
               and epoch.writer_mode = 'SHADOW'
               and epoch.lifecycle_status = 'ACTIVE'
               and epoch.provisioning_status = 'READY'
               and epoch.process_revision = admission.process_revision
               and epoch.room_revision = admission.room_revision
               and registration.registration_status = 'REGISTERED'
               and access_row.status = 'ACTIVE'
               and agent.status = 'ACTIVE'
            """;

    private static final String COMMAND_ADMISSION_SQL = """
            select admission.epoch_id, admission.access_session_id, admission.agent_session_id,
                   admission.registration_id, admission.tenant_surrogate, admission.case_id,
                   admission.room_epoch, admission.fencing_token, admission.command_id,
                   admission.command_sequence, admission.command_type, admission.party,
                   admission.payload_ref, admission.payload_hash, admission.command_operation_key,
                   admission.actor_scope_hash, admission.request_hash,
                   admission.process_revision, admission.room_revision,
                   admission.thread_id, admission.deadline_epoch_millis,
                   admission.retry_provider_attempts, admission.retry_activity_attempts,
                   admission.retry_repairs
              from case_intake_synthetic_activity_admission admission
              join case_room_epoch epoch
                on epoch.id = admission.epoch_id
               and epoch.tenant_surrogate = admission.tenant_surrogate
               and epoch.case_id = admission.case_id
               and epoch.room_type = admission.room_type
               and epoch.room_epoch = admission.room_epoch
               and epoch.fencing_token = admission.fencing_token
              join case_intake_graph_thread_binding registration
                on registration.registration_id = admission.registration_id
               and registration.registration_hash = admission.registration_hash
              join case_access_session access_row
                on access_row.id = admission.access_session_id
              join agent_conversation_session agent
                on agent.id = admission.agent_session_id
             where admission.schema_version = 'intake-synthetic-activity-admission.v1'
               and admission.admission_status = 'VERIFIED'
               and admission.traffic_source = 'AUTHENTICATED_SIGNED_SYNTHETIC'
               and admission.tenant_surrogate = :tenant
               and admission.case_id = :caseId
               and admission.room_epoch = :roomEpoch
               and admission.fencing_token = :fence
               and admission.command_id = :commandId
               and admission.command_sequence = :commandSequence
               and admission.command_type = 'INTAKE_MESSAGE'
               and admission.party = :party
               and admission.payload_ref = :payloadRef
               and admission.payload_hash = :payloadHash
               and admission.command_operation_key = :commandOperationKey
               and admission.actor_scope_hash = :actorScopeHash
               and admission.request_hash = :requestHash
               and admission.process_revision = :processRevision
               and admission.room_revision = :roomRevision
               and admission.deadline_epoch_millis = :deadlineEpochMillis
               and epoch.writer_mode = 'SHADOW'
               and epoch.lifecycle_status = 'ACTIVE'
               and epoch.provisioning_status = 'READY'
               and epoch.process_revision = admission.process_revision
               and epoch.room_revision = admission.room_revision
               and registration.registration_status = 'REGISTERED'
               and access_row.status = 'ACTIVE'
               and agent.status = 'ACTIVE'
            """;

    private static final String LOCK_EPOCH_SQL = """
            select id
              from case_room_epoch
             where id = :epochId
               and tenant_surrogate = :tenant
               and case_id = :caseId
               and room_type = 'INTAKE'
               and room_epoch = :roomEpoch
               and fencing_token = :fence
               and writer_mode = 'SHADOW'
               and lifecycle_status = 'ACTIVE'
               and provisioning_status = 'READY'
               and process_revision = :processRevision
               and room_revision = :roomRevision
             for share
            """;

    private final Es256IntakeSyntheticAdmissionVerifier verifier;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EpochAuthorityLockCoordinator locks;
    private final Clock clock;

    public JdbcIntakeSignedSyntheticAdmissionPort(
            Es256IntakeSyntheticAdmissionVerifier verifier,
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transactions,
            EpochAuthorityLockCoordinator locks,
            Clock clock) {
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public VerifiedAdmission admit(AdmissionAttempt attempt, IntakeWorkflowCommand command) {
        VerifiedToken token = verifier.verify(attempt, command);
        try {
            return Objects.requireNonNull(transactions.execute(status -> persist(token)),
                    "admission transaction returned null");
        } catch (IntakeSyntheticAdmissionException exception) {
            throw exception;
        } catch (DuplicateKeyException exception) {
            throw new IntakeSyntheticAdmissionException(
                    "ADMISSION_REPLAY_CONFLICT",
                    "admission envelope, authorization, or key/JTI was already used",
                    exception);
        }
    }

    @Override
    public boolean isActivityAuthorized(ActivityAuthorization authorization) {
        Objects.requireNonNull(authorization, "authorization must not be null");
        try {
            Boolean result = transactions.execute(status -> authorizeActivity(authorization));
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public PersistedCommandAdmission require(CommandRequest request, CommandSource source) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(source, "source must not be null");
        return Objects.requireNonNull(transactions.execute(status -> lookupCommandAdmission(request, source)),
                "command admission transaction returned null");
    }

    private VerifiedAdmission persist(VerifiedToken token) {
        MapSqlParameterSource params = claimParameters(token);
        AuthorityRow authority = requireExactAuthority(params);
        lockCurrentAuthority(authority, params);
        authority = requireExactAuthority(params);
        addAuthority(params, authority);
        int inserted = jdbc.update(INSERT_SQL, params);
        if (inserted == 0) {
            requireExactReplay(token);
        }
        return verifiedAdmission(token);
    }

    private boolean authorizeActivity(ActivityAuthorization authorization) {
        MapSqlParameterSource params = activityParameters(authorization);
        List<ActivityAuthorityIds> candidates = jdbc.query(
                ACTIVITY_RECEIPT_SQL,
                params,
                (resultSet, rowNum) -> new ActivityAuthorityIds(
                        resultSet.getString("epoch_id"),
                        resultSet.getString("access_session_id"),
                        resultSet.getString("agent_session_id"),
                        resultSet.getString("registration_id"),
                        resultSet.getString("authorization_hash")));
        if (candidates.size() != 1) {
            return false;
        }
        ActivityAuthorityIds ids = candidates.getFirst();
        locks.requireActive(locks.lockForShare(new LockRequest(
                List.of(ids.accessSessionId()),
                List.of(ids.agentSessionId()),
                List.of(ids.registrationId()))));
        params.addValue("epochId", ids.epochId())
                .addValue("accessSessionId", ids.accessSessionId())
                .addValue("registrationId", ids.registrationId());
        if (!lockEpoch(params, ids.epochId())) {
            return false;
        }
        List<String> authorizationHashes = jdbc.query(
                ACTIVITY_RECEIPT_SQL,
                params,
                (resultSet, rowNum) -> resultSet.getString("authorization_hash"));
        if (authorizationHashes.size() != 1
                || !authorizationHashes.getFirst().equals(ids.authorizationHash())) {
            return false;
        }
        params.addValue("authorizationHash", ids.authorizationHash());
        Integer count = jdbc.queryForObject(CURRENT_AUTHORITY_COUNT_SQL, params, Integer.class);
        return count != null && count == 1;
    }

    private PersistedCommandAdmission lookupCommandAdmission(
            CommandRequest request, CommandSource source) {
        MapSqlParameterSource params = commandAdmissionParameters(request, source);
        List<CommandAdmissionRow> rows = jdbc.query(
                COMMAND_ADMISSION_SQL, params, JdbcIntakeSignedSyntheticAdmissionPort::mapCommandAdmission);
        if (rows.size() != 1) {
            throw new IntakeSyntheticAdmissionException(
                    "ADMISSION_AUTHORITY_MISMATCH",
                    "signed synthetic command admission was not durably admitted");
        }
        CommandAdmissionRow row = rows.getFirst();
        locks.requireActive(locks.lockForShare(new LockRequest(
                List.of(row.accessSessionId()),
                List.of(row.agentSessionId()),
                List.of(row.registrationId()))));
        params.addValue("epochId", row.epochId());
        if (!lockEpoch(params, row.epochId())) {
            throw new IntakeSyntheticAdmissionException(
                    "ADMISSION_AUTHORITY_MISMATCH", "SHADOW epoch is not current");
        }
        rows = jdbc.query(
                COMMAND_ADMISSION_SQL, params, JdbcIntakeSignedSyntheticAdmissionPort::mapCommandAdmission);
        if (rows.size() != 1 || !rows.getFirst().equals(row)) {
            throw new IntakeSyntheticAdmissionException(
                    "ADMISSION_AUTHORITY_MISMATCH",
                    "signed synthetic command admission changed during authorization");
        }
        return row.toPersisted();
    }

    private AuthorityRow requireExactAuthority(MapSqlParameterSource params) {
        List<AuthorityRow> rows = jdbc.query(
                EXACT_AUTHORITY_SQL,
                params,
                (resultSet, rowNum) -> new AuthorityRow(
                        resultSet.getString("epoch_id"),
                        resultSet.getString("party_authority_id"),
                        resultSet.getString("access_session_id"),
                        resultSet.getString("registration_id"),
                        resultSet.getString("actor_id"),
                        resultSet.getString("actor_role"),
                        resultSet.getString("case_command_id"),
                        resultSet.getString("payload_authority_id"),
                        resultSet.getLong("accepted_room_revision")));
        if (rows.size() != 1) {
            throw new IntakeSyntheticAdmissionException(
                    "ADMISSION_AUTHORITY_MISMATCH",
                    "signed claims do not resolve one current SHADOW authority tuple");
        }
        return rows.getFirst();
    }

    private void lockCurrentAuthority(AuthorityRow authority, MapSqlParameterSource params) {
        locks.requireActive(locks.lockForShare(new LockRequest(
                List.of(authority.accessSessionId()),
                List.of(params.getValue("agentSessionId").toString()),
                List.of(authority.registrationId()))));
        if (!lockEpoch(params, authority.epochId())) {
            throw new IntakeSyntheticAdmissionException(
                    "ADMISSION_AUTHORITY_MISMATCH", "SHADOW epoch is not current");
        }
    }

    private boolean lockEpoch(MapSqlParameterSource params, String epochId) {
        params.addValue("epochId", epochId);
        List<String> rows = jdbc.query(
                LOCK_EPOCH_SQL, params, (resultSet, rowNum) -> resultSet.getString("id"));
        return rows.size() == 1;
    }

    private void requireExactReplay(VerifiedToken token) {
        MapSqlParameterSource params = new MapSqlParameterSource(
                "authorizationHash", token.authorizationHash());
        List<ExistingAdmission> rows = jdbc.query(
                EXISTING_SQL,
                params,
                (resultSet, rowNum) -> new ExistingAdmission(
                        resultSet.getString("envelope_hash"),
                        resultSet.getString("signing_key_id"),
                        resultSet.getString("jwt_id")));
        IntakeSyntheticAdmissionClaims claims = token.claims();
        if (rows.size() != 1
                || !rows.getFirst().equals(new ExistingAdmission(
                        token.envelopeHash(), token.keyId(), claims.jwtId()))) {
            throw new IntakeSyntheticAdmissionException(
                    "ADMISSION_REPLAY_CONFLICT",
                    "authorization hash was replayed with another signed envelope");
        }
    }

    private static MapSqlParameterSource claimParameters(VerifiedToken token) {
        IntakeSyntheticAdmissionClaims claims = token.claims();
        Pins pins = claims.pins();
        return new MapSqlParameterSource()
                .addValue("receiptId", "iadm.v1." + token.authorizationHash())
                .addValue("authorizationHash", token.authorizationHash())
                .addValue("envelopeHash", token.envelopeHash())
                .addValue("keyId", token.keyId())
                .addValue("jwtId", claims.jwtId())
                .addValue("claimsSchemaVersion", claims.schemaVersion())
                .addValue("issuer", claims.issuer())
                .addValue("audience", claims.audience())
                .addValue("subject", claims.subject())
                .addValue("issuedAt", claims.issuedAtEpochSeconds())
                .addValue("notBefore", claims.notBeforeEpochSeconds())
                .addValue("expiresAt", claims.expiresAtEpochSeconds())
                .addValue("tenant", claims.tenantSurrogate())
                .addValue("caseId", claims.caseId())
                .addValue("roomEpoch", claims.roomEpoch())
                .addValue("fence", claims.fencingToken())
                .addValue("party", claims.party().name())
                .addValue("registrationHash", claims.registrationHash())
                .addValue("threadId", claims.threadId())
                .addValue("actorScopeHash", claims.actorScopeHash())
                .addValue("agentSessionId", claims.agentSessionId())
                .addValue("commandId", claims.commandId())
                .addValue("commandSequence", claims.commandSequence())
                .addValue("commandType", claims.commandType().name())
                .addValue("payloadRef", claims.payloadRef())
                .addValue("payloadHash", claims.payloadHash())
                .addValue("commandOperationKey", claims.commandOperationKey())
                .addValue("requestHash", claims.requestHash())
                .addValue("processRevision", claims.processRevision())
                .addValue("roomRevision", claims.roomRevision())
                .addValue("deadlineEpochMillis", claims.deadlineEpochMillis())
                .addValue("retryProvider", claims.retryBudget().providerAttemptsRemaining())
                .addValue("retryActivity", claims.retryBudget().activityAttemptsRemaining())
                .addValue("retryRepairs", claims.retryBudget().repairsRemaining())
                .addValue("logicalRunId", claims.logicalRunId())
                .addValue("attemptId", claims.attemptId())
                .addValue("selectionHash", claims.selectionHash())
                .addValue("caseWorkflowType", pins.caseWorkflowType())
                .addValue("caseWorkflowBuildId", pins.caseWorkflowBuildId())
                .addValue("roomWorkflowType", pins.roomWorkflowType())
                .addValue("roomWorkflowBuildId", pins.roomWorkflowBuildId())
                .addValue("processContractVersion", pins.processContractVersion())
                .addValue("graphKey", pins.graphKey())
                .addValue("graphVersion", pins.graphVersion())
                .addValue("checkpointSchemaVersion", pins.checkpointSchemaVersion())
                .addValue("stateSchemaVersion", pins.stateSchemaVersion())
                .addValue("streamProtocol", pins.streamProtocol())
                .addValue("promptVersion", pins.promptVersion())
                .addValue("modelProfileId", pins.modelProfileId())
                .addValue("outputSchemaVersion", pins.outputSchemaVersion())
                .addValue("policyVersion", pins.policyVersion())
                .addValue("guardrailVersion", pins.guardrailVersion())
                .addValue("toolPolicyVersion", pins.toolPolicyVersion())
                .addValue("cohortPolicyVersion", pins.cohortPolicyVersion())
                .addValue("agentKey", pins.agentKey())
                .addValue("agentSessionProfileVersion", pins.agentSessionProfileVersion())
                .addValue("memoryPolicyId", pins.memoryPolicyId())
                .addValue("pinnedVersions", pinsJson(pins))
                .addValue("parityBaselineRef", claims.parityBaselineRef())
                .addValue("parityBaselineHash", claims.parityBaselineHash());
    }

    private static void addAuthority(MapSqlParameterSource params, AuthorityRow authority) {
        params.addValue("partyAuthorityId", authority.partyAuthorityId())
                .addValue("accessSessionId", authority.accessSessionId())
                .addValue("registrationId", authority.registrationId())
                .addValue("actorId", authority.actorId())
                .addValue("actorRole", authority.actorRole())
                .addValue("caseCommandId", authority.caseCommandId())
                .addValue("payloadAuthorityId", authority.payloadAuthorityId())
                .addValue("acceptedRoomRevision", authority.acceptedRoomRevision());
    }

    private MapSqlParameterSource activityParameters(ActivityAuthorization authorization) {
        PinnedVersions pins = authorization.pinnedVersions();
        return new MapSqlParameterSource()
                .addValue("tenant", authorization.tenantSurrogate())
                .addValue("caseId", authorization.caseId())
                .addValue("roomEpoch", authorization.roomEpoch())
                .addValue("fence", authorization.fencingToken())
                .addValue("commandId", authorization.commandId())
                .addValue("commandSequence", authorization.commandSequence())
                .addValue("commandType", authorization.commandType().name())
                .addValue("party", authorization.party().name())
                .addValue("payloadRef", authorization.commandPayloadRef())
                .addValue("payloadHash", authorization.commandPayloadHash())
                .addValue("commandOperationKey", authorization.commandOperationKey())
                .addValue("processRevision", authorization.processRevision())
                .addValue("roomRevision", authorization.roomRevision())
                .addValue("actorScopeHash", authorization.actorScopeHash())
                .addValue("requestHash", authorization.requestHash())
                .addValue("threadId", authorization.threadId())
                .addValue("agentSessionId", authorization.agentSessionId())
                .addValue("deadlineEpochMillis", authorization.deadlineEpochMillis())
                .addValue("retryProvider", authorization.retryBudget().providerAttemptsRemaining())
                .addValue("retryActivity", authorization.retryBudget().activityAttemptsRemaining())
                .addValue("retryRepairs", authorization.retryBudget().repairsRemaining())
                .addValue("workflowBuildId", pins.workflowBuildId())
                .addValue("graphVersion", pins.graphVersion())
                .addValue("checkpointSchemaVersion", pins.checkpointSchemaVersion())
                .addValue("promptVersion", pins.promptVersion())
                .addValue("modelProfileId", pins.modelProfileId())
                .addValue("outputSchemaVersion", pins.outputSchemaVersion())
                .addValue("policyVersion", pins.policyVersion())
                .addValue("guardrailVersion", pins.guardrailVersion())
                .addValue("toolPolicyVersion", pins.toolPolicyVersion())
                .addValue("nowEpochMillis", clock.millis());
    }

    private static MapSqlParameterSource commandAdmissionParameters(
            CommandRequest request, CommandSource source) {
        var command = request.command();
        return new MapSqlParameterSource()
                .addValue("tenant", command.tenantSurrogate())
                .addValue("caseId", command.caseId())
                .addValue("roomEpoch", command.roomEpoch())
                .addValue("fence", source.fencingToken())
                .addValue("commandId", command.commandId())
                .addValue("commandSequence", command.caseCommandSequence())
                .addValue("party", source.party().name())
                .addValue("payloadRef", command.payloadRef().uri())
                .addValue("payloadHash", command.payloadRef().sha256())
                .addValue("commandOperationKey", source.operationKey())
                .addValue("actorScopeHash", source.actorScopeHash())
                .addValue("requestHash", command.requestHash())
                .addValue("processRevision", command.expectedProcessRevision())
                .addValue("roomRevision", source.roomRevision())
                .addValue("deadlineEpochMillis", command.deadlineAt().toEpochMilli());
    }

    private static CommandAdmissionRow mapCommandAdmission(
            java.sql.ResultSet resultSet, int rowNum) throws java.sql.SQLException {
        return new CommandAdmissionRow(
                resultSet.getString("epoch_id"),
                resultSet.getString("access_session_id"),
                resultSet.getString("agent_session_id"),
                resultSet.getString("registration_id"),
                resultSet.getString("tenant_surrogate"),
                resultSet.getString("case_id"),
                resultSet.getLong("room_epoch"),
                resultSet.getLong("fencing_token"),
                resultSet.getString("command_id"),
                resultSet.getLong("command_sequence"),
                IntakeCommandType.valueOf(resultSet.getString("command_type")),
                com.example.dispute.workflow.temporal.room.intake.IntakeParty.valueOf(
                        resultSet.getString("party")),
                resultSet.getString("payload_ref"),
                resultSet.getString("payload_hash"),
                resultSet.getString("command_operation_key"),
                resultSet.getString("actor_scope_hash"),
                resultSet.getString("request_hash"),
                resultSet.getLong("process_revision"),
                resultSet.getLong("room_revision"),
                resultSet.getString("thread_id"),
                resultSet.getLong("deadline_epoch_millis"),
                resultSet.getInt("retry_provider_attempts"),
                resultSet.getInt("retry_activity_attempts"),
                resultSet.getInt("retry_repairs"));
    }

    private static String pinsJson(Pins pins) {
        try {
            return JSON.writeValueAsString(Map.ofEntries(
                    Map.entry("case_workflow_type", pins.caseWorkflowType()),
                    Map.entry("case_workflow_build_id", pins.caseWorkflowBuildId()),
                    Map.entry("room_workflow_type", pins.roomWorkflowType()),
                    Map.entry("room_workflow_build_id", pins.roomWorkflowBuildId()),
                    Map.entry("process_contract_version", pins.processContractVersion()),
                    Map.entry("graph_key", pins.graphKey()),
                    Map.entry("graph_version", pins.graphVersion()),
                    Map.entry("checkpoint_schema_version", pins.checkpointSchemaVersion()),
                    Map.entry("state_schema_version", pins.stateSchemaVersion()),
                    Map.entry("stream_protocol", pins.streamProtocol()),
                    Map.entry("prompt_version", pins.promptVersion()),
                    Map.entry("model_profile_id", pins.modelProfileId()),
                    Map.entry("output_schema_version", pins.outputSchemaVersion()),
                    Map.entry("policy_version", pins.policyVersion()),
                    Map.entry("guardrail_version", pins.guardrailVersion()),
                    Map.entry("tool_policy_version", pins.toolPolicyVersion()),
                    Map.entry("cohort_policy_version", pins.cohortPolicyVersion()),
                    Map.entry("agent_key", pins.agentKey()),
                    Map.entry("agent_session_profile_version", pins.agentSessionProfileVersion()),
                    Map.entry("memory_policy_id", pins.memoryPolicyId())));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("admission pins cannot be serialized", exception);
        }
    }

    private static VerifiedAdmission verifiedAdmission(VerifiedToken token) {
        IntakeSyntheticAdmissionClaims claims = token.claims();
        return new VerifiedAdmission(
                "intake-verified-synthetic-admission.v1",
                TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC,
                claims.tenantSurrogate(),
                claims.caseId(),
                claims.roomEpoch(),
                claims.fencingToken(),
                claims.commandId(),
                claims.commandSequence(),
                claims.commandType(),
                claims.party(),
                claims.payloadRef(),
                claims.payloadHash(),
                claims.commandOperationKey(),
                claims.actorScopeHash(),
                claims.requestHash(),
                claims.threadId(),
                claims.agentSessionId(),
                claims.deadlineEpochMillis(),
                claims.retryBudget(),
                token.authorizationHash());
    }

    private record AuthorityRow(
            String epochId,
            String partyAuthorityId,
            String accessSessionId,
            String registrationId,
            String actorId,
            String actorRole,
            String caseCommandId,
            String payloadAuthorityId,
            long acceptedRoomRevision) {}

    private record ActivityAuthorityIds(
            String epochId,
            String accessSessionId,
            String agentSessionId,
            String registrationId,
            String authorizationHash) {}

    private record ExistingAdmission(String envelopeHash, String keyId, String jwtId) {}

    private record CommandAdmissionRow(
            String epochId,
            String accessSessionId,
            String agentSessionId,
            String registrationId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String commandId,
            long commandSequence,
            IntakeCommandType commandType,
            com.example.dispute.workflow.temporal.room.intake.IntakeParty party,
            String payloadRef,
            String payloadHash,
            String operationKey,
            String actorScopeHash,
            String requestHash,
            long processRevision,
            long roomRevision,
            String threadId,
            long deadlineEpochMillis,
            int retryProviderAttempts,
            int retryActivityAttempts,
            int retryRepairs) {

        PersistedCommandAdmission toPersisted() {
            return new PersistedCommandAdmission(
                    tenantSurrogate,
                    caseId,
                    roomEpoch,
                    fencingToken,
                    commandId,
                    commandSequence,
                    commandType,
                    party,
                    payloadRef,
                    payloadHash,
                    operationKey,
                    actorScopeHash,
                    requestHash,
                    processRevision,
                    roomRevision,
                    threadId,
                    agentSessionId,
                    deadlineEpochMillis,
                    new RetryBudget(
                            "intake-retry-budget.v1",
                            retryProviderAttempts,
                            retryActivityAttempts,
                            retryRepairs));
        }
    }
}
