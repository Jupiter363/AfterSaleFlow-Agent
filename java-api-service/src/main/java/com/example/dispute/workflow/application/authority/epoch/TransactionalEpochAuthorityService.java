package com.example.dispute.workflow.application.authority.epoch;

import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockedRows;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockRequest;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ACID implementation of epoch selection, bilateral party binding and bootstrap publication. */
@Service
public final class TransactionalEpochAuthorityService implements EpochAuthorityService {

    private final NamedParameterJdbcTemplate jdbc;
    private final EpochAuthorityLockCoordinator lockCoordinator;
    private final EpochBootstrapOutboxPublisher bootstrapPublisher;

    public TransactionalEpochAuthorityService(
            NamedParameterJdbcTemplate jdbc,
            EpochAuthorityLockCoordinator lockCoordinator,
            EpochBootstrapOutboxPublisher bootstrapPublisher) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.lockCoordinator = Objects.requireNonNull(lockCoordinator, "lockCoordinator must not be null");
        this.bootstrapPublisher = Objects.requireNonNull(bootstrapPublisher, "bootstrapPublisher must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public EpochBindingReceipt bind(BindRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        EpochSelectionBinding selection = request.selection();
        if (!selection.epochId().equals(request.bootstrap().epochId())
                || !selection.tenantSurrogate().equals(request.bootstrap().tenantSurrogate())
                || !selection.caseId().equals(request.bootstrap().caseId())
                || selection.roomEpoch() != request.bootstrap().roomEpoch()
                || selection.fencingToken() != request.bootstrap().fencingToken()) {
            throw new EpochAuthorityException("AUTHORITY_TUPLE_MISMATCH", "bootstrap does not match selected epoch");
        }
        Map<Party, EpochPartyAuthority> parties = validateParties(request);
        LockedRows locked = lockCoordinator.lockForShare(request.locks());
        // This check intentionally occurs after all three table locks are held.
        lockCoordinator.requireActive(locked);
        boolean created = persistSelection(selection);
        persistParty(parties.get(Party.INITIATOR));
        persistParty(parties.get(Party.RESPONDENT));
        assertExactlyTwoParties(selection.epochId());
        String outboxId = bootstrapPublisher.publish(request.bootstrap());
        return new EpochBindingReceipt(
                selection.epochId(), outboxId, List.of(parties.get(Party.INITIATOR), parties.get(Party.RESPONDENT)), created);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAccessSession(RevocationRequest request) {
        revoke(request, "case_access_session", "status", "id");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAgentSession(RevocationRequest request) {
        revoke(request, "agent_conversation_session", "status", "id");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void retireRegistration(RevocationRequest request) {
        revoke(request, "case_intake_graph_thread_binding", "registration_status", "registration_id");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AcceptanceSnapshot acceptInert(AcceptanceRequest request) {
        LockedRows locked = lockCoordinator.lockForShare(request.locks());
        // Recheck only after the complete lock set: revoke-first therefore rejects deterministically.
        lockCoordinator.requireActive(locked);
        return new AcceptanceSnapshot(request.epochId(), request.fencingToken(), true);
    }

    private Map<Party, EpochPartyAuthority> validateParties(BindRequest request) {
        EnumMap<Party, EpochPartyAuthority> result = new EnumMap<>(Party.class);
        for (EpochPartyAuthority authority : request.parties()) {
            EpochSelectionBinding selection = request.selection();
            if (!selection.epochId().equals(authority.epochId())
                    || !selection.tenantSurrogate().equals(authority.tenantSurrogate())
                    || !selection.caseId().equals(authority.caseId())
                    || selection.roomEpoch() != authority.roomEpoch()
                    || selection.fencingToken() != authority.fencingToken()) {
                throw new EpochAuthorityException("AUTHORITY_TUPLE_MISMATCH", "party does not match selected epoch");
            }
            if (result.put(authority.party(), authority) != null) {
                throw new EpochAuthorityException("AUTHORITY_DUPLICATE_PARTY", "one party has multiple authorities");
            }
            String expectedActor = authority.party() == Party.INITIATOR
                    ? request.caseParties().initiatorId()
                    : request.caseParties().respondentId();
            com.example.dispute.config.ActorRole expectedRole = authority.party() == Party.INITIATOR
                    ? request.caseParties().initiatorRole()
                    : request.caseParties().respondentRole();
            if (!expectedActor.equals(authority.actorId()) || expectedRole != authority.actorRole()) {
                throw new EpochAuthorityException(
                        "AUTHORITY_PARTY_MISMATCH", "party authority must match immutable case party facts");
            }
            if (!selection.promptVersion().equals(authority.promptVersion())
                    || !selection.agentSessionProfileVersion()
                            .equals(authority.agentSessionProfileVersion())
                    || !selection.agentKey().equals(authority.agentKey())
                    || !selection.memoryPolicyId().equals(authority.memoryPolicyId())) {
                throw new EpochAuthorityException(
                        "AUTHORITY_PROFILE_MISMATCH", "party profile pins must equal epoch selection");
            }
        }
        if (!result.keySet().equals(java.util.Set.of(Party.INITIATOR, Party.RESPONDENT))) {
            throw new EpochAuthorityException("AUTHORITY_PARTY_CARDINALITY", "both party authorities are required");
        }
        requireLocksMatchAuthorities(request.locks(), result.values());
        return result;
    }

    private static void requireLocksMatchAuthorities(
            LockRequest locks, java.util.Collection<EpochPartyAuthority> authorities) {
        java.util.Set<String> accessIds = authorities.stream()
                .map(EpochPartyAuthority::accessSessionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        java.util.Set<String> agentIds = authorities.stream()
                .map(EpochPartyAuthority::agentSessionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        java.util.Set<String> registrationIds = authorities.stream()
                .map(EpochPartyAuthority::registrationId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!accessIds.equals(java.util.Set.copyOf(locks.accessSessionIds()))
                || !agentIds.equals(java.util.Set.copyOf(locks.agentSessionIds()))
                || !registrationIds.equals(java.util.Set.copyOf(locks.registrationIds()))) {
            throw new EpochAuthorityException(
                    "AUTHORITY_LOCK_SCOPE_MISMATCH",
                    "the locked rows must be the exact bilateral authority route");
        }
    }

    private boolean persistSelection(EpochSelectionBinding selection) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("epochId", selection.epochId())
                .addValue("tenant", selection.tenantSurrogate())
                .addValue("caseId", selection.caseId())
                .addValue("roomEpoch", selection.roomEpoch())
                .addValue("fence", selection.fencingToken())
                .addValue("selectionHash", selection.selectionHash())
                .addValue("writerMode", selection.writerMode().name())
                .addValue("caseWorkflowType", selection.caseWorkflowType())
                .addValue("caseWorkflowBuildId", selection.caseWorkflowBuildId())
                .addValue("roomWorkflowType", selection.roomWorkflowType())
                .addValue("roomWorkflowBuildId", selection.roomWorkflowBuildId())
                .addValue("processContractVersion", selection.processContractVersion())
                .addValue("graphKey", selection.graphKey())
                .addValue("graphVersion", selection.graphVersion())
                .addValue("checkpointSchemaVersion", selection.checkpointSchemaVersion())
                .addValue("stateSchemaVersion", selection.stateSchemaVersion())
                .addValue("streamProtocol", selection.streamProtocol())
                .addValue("promptVersion", selection.promptVersion())
                .addValue("modelProfileId", selection.modelProfileId())
                .addValue("outputSchemaVersion", selection.outputSchemaVersion())
                .addValue("policyVersion", selection.policyVersion())
                .addValue("guardrailVersion", selection.guardrailVersion())
                .addValue("toolPolicyVersion", selection.toolPolicyVersion())
                .addValue("cohortPolicyVersion", selection.cohortPolicyVersion())
                .addValue("agentKey", selection.agentKey())
                .addValue("agentSessionProfileVersion", selection.agentSessionProfileVersion())
                .addValue("memoryPolicyId", selection.memoryPolicyId());
        int inserted = jdbc.update(
                """
                insert into case_intake_epoch_selection_binding (
                    epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
                    selection_hash, writer_mode, case_workflow_type, case_workflow_build_id,
                    room_workflow_type, room_workflow_build_id, process_contract_version, graph_key,
                    graph_version, checkpoint_schema_version, state_schema_version, stream_protocol,
                    prompt_version, model_profile_id, output_schema_version, policy_version,
                    guardrail_version, tool_policy_version, cohort_policy_version, agent_key,
                    agent_session_profile_version, memory_policy_id
                ) values (
                    :epochId, :tenant, :caseId, 'INTAKE', :roomEpoch, :fence,
                    :selectionHash, :writerMode, :caseWorkflowType, :caseWorkflowBuildId,
                    :roomWorkflowType, :roomWorkflowBuildId, :processContractVersion, :graphKey,
                    :graphVersion, :checkpointSchemaVersion, :stateSchemaVersion, :streamProtocol,
                    :promptVersion, :modelProfileId, :outputSchemaVersion, :policyVersion,
                    :guardrailVersion, :toolPolicyVersion, :cohortPolicyVersion, :agentKey,
                    :agentSessionProfileVersion, :memoryPolicyId
                ) on conflict do nothing
                """,
                params);
        if (inserted == 1) {
            return true;
        }
        String existingHash = jdbc.queryForObject(
                "select selection_hash from case_intake_epoch_selection_binding where epoch_id = :epochId",
                Map.of("epochId", selection.epochId()),
                String.class);
        if (!selection.selectionHash().equals(existingHash)) {
            throw new EpochAuthorityException("AUTHORITY_SELECTION_CONFLICT", "epoch selection is immutable");
        }
        return false;
    }

    private void persistParty(EpochPartyAuthority authority) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("authorityId", authority.authorityId())
                .addValue("epochId", authority.epochId())
                .addValue("party", authority.party().name())
                .addValue("tenant", authority.tenantSurrogate())
                .addValue("caseId", authority.caseId())
                .addValue("sessionTenant", authority.sessionTenantId())
                .addValue("sessionCase", authority.sessionCaseId())
                .addValue("roomEpoch", authority.roomEpoch())
                .addValue("fence", authority.fencingToken())
                .addValue("registrationId", authority.registrationId())
                .addValue("registrationHash", authority.registrationHash())
                .addValue("threadId", authority.threadId())
                .addValue("actorId", authority.actorId())
                .addValue("actorRole", authority.actorRole().name())
                .addValue("audience", authority.audience().name())
                .addValue("actorScopeHash", authority.actorScopeHash())
                .addValue("accessSessionId", authority.accessSessionId())
                .addValue("permissionLevel", authority.permissionLevel())
                .addValue("agentSessionId", authority.agentSessionId())
                .addValue("agentKey", authority.agentKey())
                .addValue("promptVersion", authority.promptVersion())
                .addValue("agentSessionProfileVersion", authority.agentSessionProfileVersion())
                .addValue("promptProfileId", authority.promptProfileId())
                .addValue("memoryPolicyId", authority.memoryPolicyId())
                .addValue("createdAt", authority.createdAt());
        jdbc.update(
                """
                insert into case_intake_epoch_party_authority (
                    authority_id, epoch_id, party, tenant_surrogate, case_id, session_tenant_id,
                    session_case_id, room_type, room_epoch, fencing_token, registration_id,
                    registration_hash, thread_id, actor_id, actor_role, audience, actor_scope_hash,
                    access_session_id, permission_level, agent_session_id, agent_key, prompt_version,
                    agent_session_profile_version, prompt_profile_id, memory_policy_id, created_at
                ) values (
                    :authorityId, :epochId, :party, :tenant, :caseId, :sessionTenant, :sessionCase,
                    'INTAKE', :roomEpoch, :fence, :registrationId, :registrationHash, :threadId,
                    :actorId, :actorRole, :audience, :actorScopeHash, :accessSessionId,
                    :permissionLevel, :agentSessionId, :agentKey, :promptVersion,
                    :agentSessionProfileVersion, :promptProfileId, :memoryPolicyId, :createdAt
                ) on conflict do nothing
                """,
                params);
        String persistedAuthorityId = jdbc.queryForObject(
                """
                select authority_id
                  from case_intake_epoch_party_authority
                 where epoch_id = :epochId and party = :party
                """,
                new MapSqlParameterSource()
                        .addValue("epochId", authority.epochId())
                        .addValue("party", authority.party().name()),
                String.class);
        if (!authority.authorityId().equals(persistedAuthorityId)) {
            throw new EpochAuthorityException(
                    "AUTHORITY_REBINDING", "an epoch party cannot be rebound to a different authority");
        }
    }

    private void assertExactlyTwoParties(String epochId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from case_intake_epoch_party_authority where epoch_id = :epochId",
                Map.of("epochId", epochId),
                Integer.class);
        if (count == null || count != 2) {
            throw new EpochAuthorityException(
                    "AUTHORITY_PARTY_CARDINALITY", "bootstrap requires exactly two party authority rows");
        }
        List<String> parties = jdbc.queryForList(
                "select party from case_intake_epoch_party_authority where epoch_id = :epochId order by party",
                Map.of("epochId", epochId),
                String.class);
        if (!parties.equals(List.of("INITIATOR", "RESPONDENT"))) {
            throw new EpochAuthorityException(
                    "AUTHORITY_PARTY_CARDINALITY", "bootstrap requires one INITIATOR and one RESPONDENT");
        }
    }

    private void revoke(RevocationRequest request, String table, String statusColumn, String idColumn) {
        lockCoordinator.lockForUpdate(request.locks());
        // Every revocation update is after all locks and therefore conflicts with acceptance FOR SHARE.
        String sql = table.equals("case_intake_graph_thread_binding")
                ? "update case_intake_graph_thread_binding "
                        + "set registration_status = :status, retired_at = :updatedAt "
                        + "where registration_id in (:ids)"
                : "update " + table + " set " + statusColumn
                        + " = :status, updated_at = :updatedAt where " + idColumn + " in (:ids)";
        List<String> ids = switch (table) {
            case "case_access_session" -> request.locks().accessSessionIds();
            case "agent_conversation_session" -> request.locks().agentSessionIds();
            default -> request.locks().registrationIds();
        };
        String status = table.equals("case_intake_graph_thread_binding") ? "RETIRED" : "REVOKED";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("updatedAt", request.revokedAt())
                .addValue("ids", ids));
    }
}
