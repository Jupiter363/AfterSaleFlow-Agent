package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonLedger.CommitRequest;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonLedger.CommitResult;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL comparison ledger with same-operation/same-value replay semantics. */
public final class JdbcIntakeSyntheticComparisonLedger
        implements IntakeSyntheticComparisonLedger {

    private static final String SELECT_BY_OPERATION =
            """
            select comparison_payload::text, receipt_payload::text
              from case_intake_shadow_comparison
            where operation_key = :operationKey
            """;

    private static final String SELECT_AUTHORITY_ROUTE =
            """
            select command.case_command_id, command.case_command_sequence,
                   command.accepted_room_revision, selection.epoch_id,
                   party.authority_id as party_authority_id, party.party,
                   party.access_session_id, party.registration_id,
                   party.actor_id, party.actor_role
              from case_intake_epoch_selection_binding selection
              join case_intake_epoch_party_authority party
                on party.epoch_id = selection.epoch_id
               and party.tenant_surrogate = selection.tenant_surrogate
               and party.case_id = selection.case_id
               and party.room_type = selection.room_type
               and party.room_epoch = selection.room_epoch
               and party.fencing_token = selection.fencing_token
              join case_intake_command_authority command
                on command.epoch_id = selection.epoch_id
               and command.party_authority_id = party.authority_id
               and command.access_session_id = party.access_session_id
               and command.registration_id = party.registration_id
               and command.tenant_surrogate = party.tenant_surrogate
               and command.case_id = party.case_id
               and command.room_type = party.room_type
               and command.room_epoch = party.room_epoch
               and command.fencing_token = party.fencing_token
               and command.thread_id = party.thread_id
               and command.actor_id = party.actor_id
               and command.actor_role = party.actor_role
               and command.actor_scope_hash = party.actor_scope_hash
               and command.agent_session_id = party.agent_session_id
             where selection.tenant_surrogate = :tenant
               and selection.case_id = :caseId
               and selection.room_type = 'INTAKE'
               and selection.room_epoch = :roomEpoch
               and selection.fencing_token = :fencingToken
               and selection.writer_mode = 'SHADOW'
               and party.party = :party
               and party.thread_id = :threadId
               and party.agent_session_id = :agentSessionId
               and party.actor_scope_hash = :actorScopeHash
               and command.command_id = :commandId
               and command.command_type = 'INTAKE_MESSAGE'
               and command.case_command_sequence = :commandSequence
               and command.request_hash = :requestHash
               and command.accepted_room_revision = :roomRevision
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public JdbcIntakeSyntheticComparisonLedger(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    @Override
    public CommitResult commit(CommitRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return Objects.requireNonNull(
                transactions.execute(status -> commitInTransaction(request)),
                "comparison transaction returned no result");
    }

    private CommitResult commitInTransaction(CommitRequest request) {
        TurnFinalizationRequest finalization = request.finalization();
        String comparisonKey = IntakeSyntheticComparisonReceiptFactory.comparisonKey(finalization);
        if (!comparisonKey.equals(request.comparison().comparisonKeyHash())) {
            throw new IllegalArgumentException(
                    "comparison key does not match the finalization authority tuple");
        }
        String comparisonPayload = json(request.comparison());
        String comparisonHash =
                IntakeSyntheticComparisonReceiptFactory.comparisonHash(
                        request.comparison(), objectMapper);
        OffsetDateTime recordedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        TurnFinalizationReceipt receipt =
                IntakeSyntheticComparisonReceiptFactory.create(
                        finalization,
                        request.comparison(),
                        request.projectedEventType(),
                        recordedAt);
        String receiptPayload = json(receipt);

        Optional<Stored> existing = load(finalization);
        if (existing.isPresent()) {
            return replayExisting(request, existing.orElseThrow());
        }

        var envelope = finalization.envelope();
        var graph = finalization.graphExecution();
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("comparisonKey", comparisonKey)
                        .addValue("tenant", envelope.tenantSurrogate())
                        .addValue("caseId", envelope.caseId())
                        .addValue("party", envelope.party().name())
                        .addValue("roomEpoch", envelope.roomEpoch())
                        .addValue("fencingToken", envelope.fencingToken())
                        .addValue("threadId", finalization.threadId())
                        .addValue("agentSessionId", finalization.agentSessionId())
                        .addValue("actorScopeHash", envelope.actorScopeHash())
                        .addValue("commandId", envelope.commandId())
                        .addValue("commandSequence", envelope.commandSequence())
                        .addValue("roomRevision", envelope.roomRevision())
                        .addValue("operationKey", finalization.operationKey())
                        .addValue("requestHash", finalization.requestHash())
                        .addValue("resultHash", graph.operation().resultHash())
                        .addValue("proposalHash", graph.graphExecutionRef().proposalHash())
                        .addValue("comparisonHash", comparisonHash)
                        .addValue("verdict", request.comparison().verdict().name())
                        .addValue("projectedEventType", request.projectedEventType().name())
                        .addValue("comparisonPayload", comparisonPayload)
                        .addValue("receiptPayload", receiptPayload)
                        .addValue("recordedAt", recordedAt);

        AuthorityRoute route = requireAuthorityRoute(parameters);
        lockActiveAuthority(route, parameters);

        int inserted =
                jdbc.update(
                        """
                        insert into case_intake_shadow_comparison (
                            comparison_key_hash, epoch_id, party_authority_id, party,
                            case_command_id, tenant_surrogate, case_id, room_type,
                            room_epoch, fencing_token, access_session_id, registration_id,
                            thread_id, actor_id, actor_role, agent_session_id,
                            actor_scope_hash, command_id, command_type, case_command_sequence,
                            accepted_room_revision, operation_key, request_hash,
                            result_hash, proposal_hash, comparison_hash, verdict, projected_event_type,
                            comparison_payload, receipt_payload, recorded_at
                        )
                        select
                            :comparisonKey, selection.epoch_id, party.authority_id, party.party,
                            command.case_command_id, :tenant, :caseId, 'INTAKE',
                            :roomEpoch, :fencingToken, party.access_session_id,
                            party.registration_id, :threadId, party.actor_id,
                            party.actor_role, :agentSessionId, :actorScopeHash,
                            :commandId, command.command_type, command.case_command_sequence,
                            command.accepted_room_revision, :operationKey, :requestHash,
                            :resultHash, :proposalHash, :comparisonHash, :verdict,
                            :projectedEventType,
                            cast(:comparisonPayload as jsonb), cast(:receiptPayload as jsonb),
                            :recordedAt
                          from case_intake_epoch_selection_binding selection
                          join case_intake_epoch_party_authority party
                            on party.epoch_id = selection.epoch_id
                           and party.tenant_surrogate = selection.tenant_surrogate
                           and party.case_id = selection.case_id
                           and party.room_type = selection.room_type
                           and party.room_epoch = selection.room_epoch
                           and party.fencing_token = selection.fencing_token
                          join case_intake_command_authority command
                            on command.epoch_id = selection.epoch_id
                           and command.party_authority_id = party.authority_id
                           and command.access_session_id = party.access_session_id
                           and command.registration_id = party.registration_id
                           and command.tenant_surrogate = party.tenant_surrogate
                           and command.case_id = party.case_id
                           and command.room_type = party.room_type
                           and command.room_epoch = party.room_epoch
                           and command.fencing_token = party.fencing_token
                           and command.thread_id = party.thread_id
                           and command.actor_id = party.actor_id
                           and command.actor_role = party.actor_role
                           and command.actor_scope_hash = party.actor_scope_hash
                           and command.agent_session_id = party.agent_session_id
                          join case_room_epoch epoch
                            on epoch.id = selection.epoch_id
                           and epoch.tenant_surrogate = selection.tenant_surrogate
                           and epoch.case_id = selection.case_id
                           and epoch.room_type = selection.room_type
                           and epoch.room_epoch = selection.room_epoch
                           and epoch.fencing_token = selection.fencing_token
                          join case_access_session access_session
                            on access_session.id = party.access_session_id
                          join agent_conversation_session agent_session
                            on agent_session.id = party.agent_session_id
                          join case_intake_graph_thread_binding registration
                            on registration.registration_id = party.registration_id
                         where selection.tenant_surrogate = :tenant
                           and selection.case_id = :caseId
                           and selection.room_type = 'INTAKE'
                           and selection.room_epoch = :roomEpoch
                           and selection.fencing_token = :fencingToken
                           and selection.writer_mode = 'SHADOW'
                           and epoch.writer_mode = 'SHADOW'
                           and epoch.lifecycle_status = 'ACTIVE'
                           and access_session.status = 'ACTIVE'
                           and agent_session.status = 'ACTIVE'
                           and registration.registration_status = 'REGISTERED'
                           and party.party = :party
                           and party.thread_id = :threadId
                           and party.agent_session_id = :agentSessionId
                           and party.actor_scope_hash = :actorScopeHash
                           and command.command_id = :commandId
                           and command.command_type = 'INTAKE_MESSAGE'
                           and command.case_command_sequence = :commandSequence
                           and command.request_hash = :requestHash
                           and command.accepted_room_revision = :roomRevision
                        on conflict (operation_key) do nothing
                        """,
                        parameters);
        Stored stored =
                load(finalization).orElseThrow(() -> new SecurityException(
                        "comparison storage requires an exact signed-synthetic authority row"));
        CommitResult result = replayExisting(request, stored);
        return new CommitResult(result.comparison(), result.receipt(), inserted == 1);
    }

    private CommitResult replayExisting(CommitRequest request, Stored stored) {
        if (!stored.comparison().equals(request.comparison())) {
            throw new IllegalStateException(
                    "comparison operation was already committed with a different value");
        }
        if (stored.receipt().committedEvent().eventType() != request.projectedEventType()) {
            throw new IllegalStateException(
                    "comparison operation was already committed with a different event type");
        }
        stored.receipt().requireMatches(request.finalization());
        return new CommitResult(stored.comparison(), stored.receipt(), false);
    }

    private AuthorityRoute requireAuthorityRoute(MapSqlParameterSource parameters) {
        var routes = jdbc.query(
                SELECT_AUTHORITY_ROUTE,
                parameters,
                (resultSet, rowNumber) -> new AuthorityRoute(
                        resultSet.getString("case_command_id"),
                        resultSet.getLong("case_command_sequence"),
                        resultSet.getLong("accepted_room_revision"),
                        resultSet.getString("epoch_id"),
                        resultSet.getString("party_authority_id"),
                        resultSet.getString("party"),
                        resultSet.getString("access_session_id"),
                        resultSet.getString("registration_id"),
                        resultSet.getString("actor_id"),
                        resultSet.getString("actor_role")));
        if (routes.size() != 1) {
            throw new SecurityException(
                    "comparison storage requires one exact persisted message authority route");
        }
        return routes.getFirst();
    }

    /** Matches the R1.5 revocation order, then locks the active epoch before the insert. */
    private void lockActiveAuthority(
            AuthorityRoute route, MapSqlParameterSource parameters) {
        requireLockedRow(
                "select id from case_access_session "
                        + "where id = :id and status = 'ACTIVE' for share",
                Map.of("id", route.accessSessionId()),
                "active access session");
        requireLockedRow(
                "select id from agent_conversation_session "
                        + "where id = :id and status = 'ACTIVE' for share",
                Map.of("id", parameters.getValue("agentSessionId")),
                "active Agent Session");
        requireLockedRow(
                "select registration_id from case_intake_graph_thread_binding "
                        + "where registration_id = :id and registration_status = 'REGISTERED' "
                        + "for share",
                Map.of("id", route.registrationId()),
                "registered Graph thread");
        requireLockedRow(
                """
                select id from case_room_epoch
                 where id = :epochId
                   and tenant_surrogate = :tenant
                   and case_id = :caseId
                   and room_type = 'INTAKE'
                   and room_epoch = :roomEpoch
                   and fencing_token = :fencingToken
                   and writer_mode = 'SHADOW'
                   and lifecycle_status = 'ACTIVE'
                 for share
                """,
                parameters,
                "active signed-synthetic Intake epoch");
    }

    private void requireLockedRow(
            String sql, Map<String, ?> parameters, String description) {
        requireSingleLockedRow(jdbc.queryForList(sql, parameters, String.class), description);
    }

    private void requireLockedRow(
            String sql, MapSqlParameterSource parameters, String description) {
        requireSingleLockedRow(jdbc.queryForList(sql, parameters, String.class), description);
    }

    private static void requireSingleLockedRow(
            java.util.List<String> rows, String description) {
        if (rows.size() != 1) {
            throw new SecurityException("comparison storage requires " + description);
        }
    }

    @Override
    public Optional<CommitResult> find(TurnFinalizationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return load(request).map(stored -> {
            stored.receipt().requireMatches(request);
            return new CommitResult(stored.comparison(), stored.receipt(), false);
        });
    }

    private Optional<Stored> load(TurnFinalizationRequest request) {
        return jdbc.query(
                        SELECT_BY_OPERATION,
                        Map.of("operationKey", request.operationKey()),
                        (resultSet, rowNumber) ->
                                new Stored(
                                        read(
                                                resultSet.getString("comparison_payload"),
                                                IntakeShadowComparison.class),
                                        read(
                                                resultSet.getString("receipt_payload"),
                                                TurnFinalizationReceipt.class)))
                .stream()
                .findFirst();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("comparison ledger value is not JSON", exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored comparison ledger value is invalid", exception);
        }
    }

    private record Stored(
            IntakeShadowComparison comparison, TurnFinalizationReceipt receipt) {}

    private record AuthorityRoute(
            String caseCommandId,
            long caseCommandSequence,
            long acceptedRoomRevision,
            String epochId,
            String partyAuthorityId,
            String party,
            String accessSessionId,
            String registrationId,
            String actorId,
            String actorRole) {}
}
