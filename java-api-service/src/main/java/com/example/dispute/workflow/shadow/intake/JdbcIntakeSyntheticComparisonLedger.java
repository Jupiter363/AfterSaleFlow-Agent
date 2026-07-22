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

/** PostgreSQL comparison ledger with same-operation/same-value replay semantics. */
public final class JdbcIntakeSyntheticComparisonLedger
        implements IntakeSyntheticComparisonLedger {

    private static final String SELECT_BY_OPERATION =
            """
            select comparison_payload::text, receipt_payload::text
              from case_intake_shadow_comparison
             where operation_key = :operationKey
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcIntakeSyntheticComparisonLedger(
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public CommitResult commit(CommitRequest request) {
        Objects.requireNonNull(request, "request must not be null");
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

        var envelope = finalization.envelope();
        var graph = finalization.graphExecution();
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("comparisonKey", comparisonKey)
                        .addValue("tenant", envelope.tenantSurrogate())
                        .addValue("caseId", envelope.caseId())
                        .addValue("roomEpoch", envelope.roomEpoch())
                        .addValue("fencingToken", envelope.fencingToken())
                        .addValue("threadId", finalization.threadId())
                        .addValue("agentSessionId", finalization.agentSessionId())
                        .addValue("actorScopeHash", envelope.actorScopeHash())
                        .addValue("commandId", envelope.commandId())
                        .addValue("operationKey", finalization.operationKey())
                        .addValue("requestHash", finalization.requestHash())
                        .addValue("resultHash", graph.operation().resultHash())
                        .addValue("proposalHash", graph.graphExecutionRef().proposalHash())
                        .addValue("comparisonHash", comparisonHash)
                        .addValue("verdict", request.comparison().verdict().name())
                        .addValue("comparisonPayload", comparisonPayload)
                        .addValue("receiptPayload", receiptPayload)
                        .addValue("recordedAt", recordedAt);

        int inserted =
                jdbc.update(
                        """
                        insert into case_intake_shadow_comparison (
                            comparison_key_hash, epoch_id, tenant_surrogate, case_id,
                            room_epoch, fencing_token, thread_id, agent_session_id,
                            actor_scope_hash, command_id, operation_key, request_hash,
                            result_hash, proposal_hash, comparison_hash, verdict,
                            comparison_payload, receipt_payload, recorded_at
                        )
                        select
                            :comparisonKey, selection.epoch_id, :tenant, :caseId,
                            :roomEpoch, :fencingToken, :threadId, :agentSessionId,
                            :actorScopeHash, :commandId, :operationKey, :requestHash,
                            :resultHash, :proposalHash, :comparisonHash, :verdict,
                            cast(:comparisonPayload as jsonb), cast(:receiptPayload as jsonb),
                            :recordedAt
                          from case_intake_epoch_selection_binding selection
                          join case_intake_epoch_party_authority party
                            on party.epoch_id = selection.epoch_id
                           and party.tenant_surrogate = selection.tenant_surrogate
                           and party.case_id = selection.case_id
                           and party.room_epoch = selection.room_epoch
                           and party.fencing_token = selection.fencing_token
                         where selection.tenant_surrogate = :tenant
                           and selection.case_id = :caseId
                           and selection.room_epoch = :roomEpoch
                           and selection.fencing_token = :fencingToken
                           and selection.writer_mode = 'SHADOW'
                           and party.thread_id = :threadId
                           and party.agent_session_id = :agentSessionId
                           and party.actor_scope_hash = :actorScopeHash
                        on conflict (operation_key) do nothing
                        """,
                        parameters);
        Stored stored =
                load(finalization).orElseThrow(() -> new SecurityException(
                        "comparison storage requires an exact signed-synthetic authority row"));
        if (!stored.comparison().equals(request.comparison())) {
            throw new IllegalStateException(
                    "comparison operation was already committed with a different value");
        }
        stored.receipt().requireMatches(finalization);
        return new CommitResult(stored.comparison(), stored.receipt(), inserted == 1);
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
}
