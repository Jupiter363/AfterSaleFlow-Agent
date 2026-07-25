package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OutcomeV045MigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src",
            "main",
            "resources",
            "db",
            "migration",
            "V045__outcome_operation_receipt_compensation.sql");
    private static final Path LEDGER_SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "example",
            "dispute",
            "executor",
            "infrastructure",
            "persistence",
            "JdbcOutcomeOperationLedger.java");

    @Test
    void addsOnlyTheReservedOutcomeProjectionAndAppendOnlyLedgers() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("create table outcome_process_projection")
                .contains("create table outcome_operation (")
                .contains("create table outcome_operation_attempt_observation")
                .contains("create table outcome_operation_receipt")
                .contains("create table outcome_compensation_parent_binding")
                .contains("create view outcome_closure_readiness")
                .doesNotContain("alter table review_packet")
                .doesNotContain("alter table human_review_record")
                .doesNotContain("alter table action_record")
                .doesNotContain("update review_packet")
                .doesNotContain("update human_review_record")
                .doesNotContain("update action_record");
    }

    @Test
    void fencesProjectionAndForbidsTemporalOutcomeAllocation() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("room_type = 'review'")
                .contains("writer_mode = 'legacy' and runtime_mode = 'disabled'")
                .contains("runtime_mode = 'java_signed_synthetic_noop_shadow'")
                .contains("v045 engineering scope forbids temporal outcome allocation")
                .contains("new.process_revision <> old.process_revision + 1")
                .contains("new.outcome_revision <> old.outcome_revision + 1")
                .contains("outcome projection revision fence rejected")
                .contains("outcome operation projection fence is stale");
    }

    @Test
    void bootstrapsOnlyAtTheEstablishedDecisionRecordedState() throws Exception {
        String projection = functionSegment(
                normalizedSql(),
                "create function enforce_outcome_projection_authority()",
                "create trigger trg_outcome_projection_authority");
        String bootstrap = functionSegment(
                projection,
                "if tg_op = 'insert' then",
                "else if (new.projection_id, new.schema_version");

        Matcher matcher = Pattern.compile("new\\.process_state <> '([^']+)'")
                .matcher(bootstrap);
        Set<String> admittedBootstrapStates = new LinkedHashSet<>();
        while (matcher.find()) {
            admittedBootstrapStates.add(matcher.group(1));
        }
        assertThat(admittedBootstrapStates).containsExactly("decision_recorded");
        assertThat(occurrences(bootstrap, "new.process_state <> 'decision_recorded'"))
                .isEqualTo(1);
        assertThat(bootstrap)
                .contains("outcome projection bootstrap state is illegal")
                .doesNotContain("'ready_to_close'")
                .doesNotContain("'closed'")
                .doesNotContain("'evaluation_pending'")
                .doesNotContain("'evaluated'");
    }

    @Test
    void bindsOperationToExecutableDecisionPacketActionAndIdempotencyIdentity()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("uq_outcome_operation_scope_key")
                .contains("uq_outcome_operation_scope_key_hash")
                .contains("uq_outcome_operation_external_key")
                .contains("value.decision_type in ('approve', 'modify_and_approve')")
                .contains("value.decision_json ->> 'request_hash' = new.decision_request_hash")
                .contains("value.decision_json ->> 'packet_content_hash' = new.review_packet_hash")
                .contains("value.decision_json ->> 'approved_action_hash' = new.action_snapshot_hash")
                .contains("value.decision_json ->> 'policy_version' = new.decision_policy_version")
                .contains("outcome operation execution-authorized decision receipt is invalid")
                .contains("outcome operation actionrecord binding is invalid");
    }

    @Test
    void rejectsAnOperationWhoseAuthorityDiffersFromTheLockedProjection() throws Exception {
        String function = functionSegment(
                normalizedSql(),
                "create function enforce_outcome_operation_binding()",
                "create trigger trg_outcome_operation_binding");

        assertThat(function)
                .contains("from outcome_process_projection value")
                .contains("for update")
                .contains(
                        "(new.approval_record_id, new.decision_request_hash,"
                                + " new.action_snapshot_hash) is distinct from"
                                + " (projection.decision_authority_receipt_id,"
                                + " projection.decision_request_hash,"
                                + " projection.approved_operation_set_hash)")
                .contains("outcome operation authority does not match the locked projection");
    }

    @Test
    void modelsAmbiguousReconciliationWithoutTreatingItAsATerminalReceipt()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("'ambiguous', 'reconciling', 'no_effect_confirmed'")
                .contains("ambiguous outcome operation must enter reconciling")
                .contains("reconciling outcome operation forbids another invocation")
                .contains("receipt_status = 'succeeded'")
                .contains("receipt_status = 'failed'")
                .doesNotContain("receipt_status = 'ambiguous'")
                .contains("ambiguous outcome operation requires reconciling before receipt")
                .contains("provider_status_query")
                .contains("java_reconciliation");
    }

    @Test
    void rejectsRedispatchWithoutBothPredecessorAndOperationRetryAuthority()
            throws Exception {
        String function = functionSegment(
                normalizedSql(),
                "create function enforce_outcome_attempt_sequence()",
                "create trigger trg_outcome_attempt_sequence");

        assertThat(function)
                .contains("parent.retry_class = 'non_retryable' and new.retry_permitted")
                .contains("non_retryable outcome operation cannot publish retry authority")
                .contains(
                        "new.observation_type = 'invocation_dispatched' and"
                                + " (not previous.retry_permitted"
                                + " or parent.retry_class = 'non_retryable')")
                .contains("outcome operation redispatch has no retry authority")
                .contains("previous.observation_type = 'ambiguous' and new.observation_type <> 'reconciling'")
                .contains(
                        "previous.observation_type = 'reconciling' and"
                                + " new.observation_type not in"
                                + " ('reconciling', 'no_effect_confirmed')");
    }

    @Test
    void serializesAttemptAndReceiptTransitionsOnOneOperationLifecycleLock()
            throws Exception {
        String sql = normalizedSql();

        assertThat(occurrences(
                        sql,
                        "'outcome-operation-lifecycle:' || new.operation_id"))
                .isEqualTo(2);
        assertThat(occurrences(
                        sql,
                        "where value.operation_id = new.operation_id for update;"))
                .isEqualTo(2);
        assertThat(sql)
                .contains("terminal outcome receipt forbids later attempts")
                .contains("ambiguous outcome operation requires reconciling before receipt");
    }

    @Test
    void freezesTheAuthorityDerivedRequiredOperationCountAndSupportsZeroOperations()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("expected_required_operation_count bigint not null")
                .contains("expected_required_operation_count >= 0")
                .contains("value.action_snapshot_hash = new.approved_operation_set_hash")
                .contains(
                        "jsonb_array_length(value.approved_plan_json -> 'actions')"
                                + " + jsonb_array_length(value.approved_plan_json -> 'notifications')"
                                + " = new.expected_required_operation_count")
                .contains("old.expected_required_operation_count")
                .contains("outcome projection immutable authority changed")
                .contains("left join outcome_operation operation")
                .contains("operation.operation_kind = 'operation' ) = projection.expected_required_operation_count")
                .contains("outcome_required_action_set_is_exact(projection.projection_id)")
                .contains("outcome_required_action_records_succeeded(projection.projection_id)")
                .doesNotContain("required_original_operation_count > 0");
    }

    @Test
    void normalizesActionsAndNotificationsIntoOneUniqueApprovedIdentityMultiset()
            throws Exception {
        String projection = functionSegment(
                normalizedSql(),
                "create function enforce_outcome_projection_authority()",
                "create trigger trg_outcome_projection_authority");

        assertThat(projection)
                .contains("jsonb_typeof(value.approved_plan_json -> 'actions') = 'array'")
                .contains("jsonb_typeof(value.approved_plan_json -> 'notifications') = 'array'")
                .contains("entry.value ->> 'action_type' as action_type")
                .contains("entry.value ->> 'idempotency_key' as idempotency_key")
                .contains("with ordinality notification(value, ordinal)")
                .contains(
                        "'remedy:' || new.case_id || ':' || approved_plan.plan_version::text"
                                + " || ':notification:' || (notification.ordinal - 1)::text || ':'")
                .contains("count(distinct idempotency_key)")
                .contains("distinct_approved_identity_count <> approved_identity_count")
                .contains("outcome projection approved action identity shape is invalid")
                .contains("outcome projection approved action identities are not a unique exact multiset");
    }

    @Test
    void rejectsNumberBooleanArrayAndObjectApprovedActionIdentityFieldsBeforeTextCoercion()
            throws Exception {
        String projection = functionSegment(
                normalizedSql(),
                "create function enforce_outcome_projection_authority()",
                "create trigger trg_outcome_projection_authority");

        assertAppearsInOrder(
                projection,
                "jsonb_typeof(entry.value) <> 'object'",
                "jsonb_typeof(entry.value -> 'action_type') <> 'string'",
                "nullif(btrim(coalesce(entry.value ->> 'action_type', '')), '') is null",
                "jsonb_typeof(entry.value -> 'idempotency_key') <> 'string'",
                "nullif(btrim(coalesce(entry.value ->> 'idempotency_key', '')), '') is null");
        assertThat(occurrences(
                        projection,
                        "jsonb_typeof(entry.value -> 'action_type') <> 'string'"))
                .isEqualTo(1);
        assertThat(occurrences(
                        projection,
                        "jsonb_typeof(entry.value -> 'idempotency_key') <> 'string'"))
                .isEqualTo(1);
    }

    @Test
    void requiresFormalActionsButKeepsTheSyntheticNoopNullExceptionNarrow()
            throws Exception {
        String sql = normalizedSql();
        String reservation = functionSegment(
                sql,
                "create function enforce_outcome_operation_binding()",
                "create trigger trg_outcome_operation_binding");

        assertThat(sql)
                .contains("action_record_id varchar(64),")
                .contains("unique (action_record_id)")
                .doesNotContain("action_record_id varchar(64) not null");
        assertThat(reservation)
                .contains("new.operation_kind = 'operation' and new.required_for_closure")
                .contains("projection.runtime_mode = 'java_signed_synthetic_noop_shadow'")
                .contains("new.action_record_id is not null")
                .contains("new.adapter_id <> 'synthetic_noop_only'")
                .contains("left(new.tenant_surrogate, 18) <> 'outcome_synthetic_'")
                .contains("left(new.case_id, 18) <> 'outcome_synthetic_'")
                .contains("new.operation_sequence > projection.expected_required_operation_count")
                .contains("elsif new.action_record_id is null")
                .contains("outcome_required_action_record_is_authorized(")
                .contains("outcome required operation has no exact approved actionrecord authority")
                .doesNotContain("new.operation_kind = 'compensation' and new.action_record_id is null");
    }

    @Test
    void provesTheFormalApprovedMultisetInBothDirectionsAndRequiresSucceededActionsOnlyAtClosure()
            throws Exception {
        String sql = normalizedSql();
        String exactSet = functionSegment(
                sql,
                "create function outcome_required_action_set_is_exact(",
                "create function outcome_required_action_records_succeeded(");
        String succeeded = functionSegment(
                sql,
                "create function outcome_required_action_records_succeeded(",
                "create function enforce_outcome_operation_binding()");
        String compensation = functionSegment(
                sql,
                "create function enforce_outcome_compensation_parent()",
                "create trigger trg_outcome_compensation_parent");

        assertThat(exactSet)
                .contains("required_original as (")
                .contains("reserved_identity as (")
                .contains("operation.action_record_id is null")
                .contains("outcome_required_action_record_is_authorized(")
                .contains("select action_type, idempotency_key from approved_identity")
                .contains("select action_type, idempotency_key from reserved_identity");
        assertThat(occurrences(exactSet, "except all")).isEqualTo(2);
        assertThat(succeeded)
                .contains("action.execution_status is distinct from 'succeeded'")
                .contains("outcome_required_action_record_is_authorized(")
                .contains("runtime_mode = 'java_signed_synthetic_noop_shadow' then true");
        assertThat(compensation)
                .contains("outcome_required_action_set_is_exact(child.projection_id)")
                .doesNotContain("outcome_required_action_records_succeeded");
        assertThat(sql)
                .contains("and outcome_required_action_set_is_exact(projection.projection_id)")
                .contains("and outcome_required_action_records_succeeded(projection.projection_id) as closure_ready");
    }

    @Test
    void rechecksExactAuthorityAtTerminalStateWithoutAnUnresolvedForwardFunctionCall()
            throws Exception {
        String projection = functionSegment(
                normalizedSql(),
                "create function enforce_outcome_projection_authority()",
                "create trigger trg_outcome_projection_authority");

        assertThat(projection)
                .contains("execute 'select outcome_required_action_set_is_exact($1), '")
                .contains("|| 'outcome_required_action_records_succeeded($1)'")
                .contains("into required_action_set_exact, required_action_records_succeeded")
                .contains("or not required_action_set_exact")
                .contains("or not required_action_records_succeeded");
    }

    @Test
    void locksFormalActionRecordsBeforeReservationReadinessAndTerminalAuthorityChecks()
            throws Exception {
        String sql = normalizedSql();
        String singleLock = functionSegment(
                sql,
                "create function outcome_lock_action_record(",
                "create function outcome_lock_required_action_records(");
        String orderedLocks = functionSegment(
                sql,
                "create function outcome_lock_required_action_records(",
                "create function outcome_required_action_record_is_authorized(");
        String reservation = functionSegment(
                sql,
                "create function enforce_outcome_operation_binding()",
                "create trigger trg_outcome_operation_binding");
        String terminal = functionSegment(
                sql,
                "create function enforce_outcome_projection_authority()",
                "create trigger trg_outcome_projection_authority");

        assertThat(singleLock)
                .contains("where value.id = p_action_record_id for share")
                .contains("return found");
        assertThat(orderedLocks)
                .contains("projection.runtime_mode <> 'java_signed_synthetic_noop_shadow'")
                .contains("operation.operation_kind = 'operation'")
                .contains("operation.required_for_closure")
                .contains("order by action.id for share of action")
                .contains("return true");
        assertAppearsInOrder(
                reservation,
                "outcome_lock_action_record(new.action_record_id)",
                "outcome_required_action_record_is_authorized(",
                "from action_record value",
                "for share;");
        assertAppearsInOrder(
                terminal,
                "perform outcome_lock_required_action_records(new.projection_id)",
                "execute 'select outcome_required_action_set_is_exact($1), '",
                "select count(operation.operation_id) filter");
        assertThat(functionSegment(
                        sql,
                        "create function outcome_required_action_set_is_exact(",
                        "create function outcome_required_action_records_succeeded("))
                .contains("perform outcome_lock_required_action_records(p_projection_id)");
        assertThat(functionSegment(
                        sql,
                        "create function outcome_required_action_records_succeeded(",
                        "create function enforce_outcome_operation_binding()"))
                .contains("perform outcome_lock_required_action_records(p_projection_id)");
    }

    @Test
    void serializesBoundActionRecordUpdatesWithoutInvertingTheOutcomeScopeLock()
            throws Exception {
        String guard = functionSegment(
                normalizedSql(),
                "create function enforce_bound_outcome_action_record_update()",
                "create trigger trg_bound_outcome_action_record_update");

        assertAppearsInOrder(
                guard,
                "where operation.action_record_id = old.id",
                "operation.operation_kind = 'operation'",
                "operation.required_for_closure",
                "if not pg_try_advisory_xact_lock(hashtextextended(",
                "'outcome-compensation-order:' || binding.tenant_surrogate || ':'",
                "errcode = '40001'",
                "outcome actionrecord scope lock is busy; retry the whole update",
                "from outcome_process_projection projection");
        assertThat(guard)
                .doesNotContain("perform pg_advisory_xact_lock(hashtextextended(")
                .contains("new is distinct from old")
                .contains("outcome terminal projection freezes the bound actionrecord")
                .contains("bound outcome actionrecord authority identity is immutable")
                .contains("new.case_id, new.plan_id, new.approval_record_id")
                .contains("new.action_type, new.idempotency_key, new.review_packet_id")
                .contains("new.review_packet_id, new.action_snapshot_hash")
                .doesNotContain("new.request_json")
                .doesNotContain("new.result_json")
                .doesNotContain("new.executed_by")
                .doesNotContain("new.attempt_count")
                .doesNotContain("new.execution_status, old.execution_status");
        assertThat(extractInClauseValues(guard, "if bound_process_state in ("))
                .containsExactlyInAnyOrder(
                        "ready_to_close", "closed", "evaluation_pending", "evaluated");
    }

    @Test
    void enforcesExactCompensationParentReceiptAndClosureBlockers() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("fk_outcome_compensation_parent_receipt")
                .contains("parent_operation_id, parent_receipt_id, parent_receipt_hash")
                .contains("parent_receipt.receipt_status <> 'succeeded'")
                .contains("deferrable initially deferred")
                .contains("compensation outcome operation requires an exact parent receipt")
                .contains("unresolved_operation_count")
                .contains("reconciliation_operation_count")
                .contains("pending_compensation_count")
                .contains("receipt.receipt_status <> 'succeeded'")
                .contains("receipt.closure_disposition <> 'satisfied'")
                .contains("as closure_ready");
    }

    @Test
    void enforcesUniqueOneBasedExactReverseCompensationOrder() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("unique (parent_operation_id)")
                .contains("unique (tenant_surrogate, case_id, outcome_epoch, reverse_order)")
                .contains("reverse_order >= 1")
                .contains("expected_reverse_order := existing_binding_count + 1")
                .contains("order by candidate.operation_sequence desc, candidate.operation_id desc")
                .contains("where ranked.ranked_reverse_order = expected_reverse_order")
                .contains("new.reverse_order <> expected_reverse_order")
                .contains("outcome compensation parent operation or receipt binding is invalid");
    }

    @Test
    void freezesCompensationEligibilityBehindATerminalScopeLockedBarrier() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("outcome compensation barrier requires every approved required original operation terminal and resolved")
                .contains("terminal_required_operation_count")
                .contains("unresolved_reconciliation_count")
                .contains("latest.observation_type in ('ambiguous', 'reconciling')")
                .contains("new original terminal receipts are forbidden after compensation starts");
        assertThat(occurrences(
                        sql,
                        "'outcome-compensation-order:' || new.tenant_surrogate || ':' || new.case_id || ':' || new.outcome_epoch::text"))
                .isEqualTo(5);
    }

    @Test
    void serializesClosureWithReservationAndRejectsPostClosureCompensation() throws Exception {
        String sql = normalizedSql();
        String projection = functionSegment(
                sql,
                "create function enforce_outcome_projection_authority()",
                "create trigger trg_outcome_projection_authority");
        String reservation = functionSegment(
                sql,
                "create function enforce_outcome_operation_binding()",
                "create trigger trg_outcome_operation_binding");
        String compensation = functionSegment(
                sql,
                "create function enforce_outcome_compensation_parent()",
                "create trigger trg_outcome_compensation_parent");

        String lockProtocol = functionSegment(
                projection, "scope_lock_key := hashtextextended(", "select epoch.*");
        String updateLockBranch = functionSegment(
                lockProtocol,
                "if tg_op = 'update' then",
                "else perform pg_advisory_xact_lock(scope_lock_key)");
        String insertLockBranch = lockProtocol.substring(lockProtocol.indexOf("else perform"));

        assertThat(updateLockBranch)
                .contains("if not pg_try_advisory_xact_lock(scope_lock_key) then")
                .contains("errcode = '40001'")
                .contains("outcome projection scope lock is busy; retry the whole transition")
                .doesNotContain("perform pg_advisory_xact_lock(scope_lock_key)");
        assertThat(insertLockBranch)
                .contains("perform pg_advisory_xact_lock(scope_lock_key)")
                .doesNotContain("pg_try_advisory_xact_lock")
                .doesNotContain("errcode = '40001'");
        assertThat(occurrences(lockProtocol, "pg_try_advisory_xact_lock(scope_lock_key)"))
                .isEqualTo(1);
        assertThat(occurrences(lockProtocol, "perform pg_advisory_xact_lock(scope_lock_key)"))
                .isEqualTo(1);
        assertThat(reservation)
                .contains("'outcome-compensation-order:'")
                .contains("outcome operation reservation is forbidden after closure readiness");
        assertThat(extractInClauseValues(reservation, "projection.process_state in ("))
                .containsExactlyInAnyOrder(
                        "ready_to_close", "closed", "evaluation_pending", "evaluated");
        assertThat(compensation)
                .contains("'outcome-compensation-order:'")
                .contains("from outcome_process_projection value")
                .contains("for update")
                .contains("outcome compensation binding is forbidden after closure readiness");
        assertThat(extractInClauseValues(compensation, "projection.process_state in ("))
                .containsExactlyInAnyOrder(
                        "ready_to_close", "closed", "evaluation_pending", "evaluated");
    }

    @Test
    void admitsOnlyReadyAndSerializedTerminalProjectionTransitions() throws Exception {
        String projection = functionSegment(
                normalizedSql(),
                "create function enforce_outcome_projection_authority()",
                "create trigger trg_outcome_projection_authority");

        String transitionRules = functionSegment(
                projection,
                "if old.process_state in ( 'ready_to_close', 'closed',",
                "if new.process_state in ( 'ready_to_close', 'closed',");
        String readiness = projection.substring(projection.indexOf(
                "if new.process_state in ( 'ready_to_close', 'closed',"));
        String readyEntry = transitionRules.substring(transitionRules.indexOf(
                "new.process_state = 'ready_to_close'"));

        assertThat(extractTransitionPairs(transitionRules))
                .containsExactlyInAnyOrder(
                        "ready_to_close->closed",
                        "closed->evaluation_pending",
                        "evaluation_pending->evaluated");
        assertThat(extractInClauseValues(readyEntry, "old.process_state in ("))
                .containsExactlyInAnyOrder(
                        "decision_recorded",
                        "operations_reserved",
                        "operations_running",
                        "reconciling",
                        "compensating",
                        "manual_recovery");
        assertThat(extractInClauseValues(readiness, "if new.process_state in ("))
                .containsExactlyInAnyOrder(
                        "ready_to_close", "closed", "evaluation_pending", "evaluated");
        assertThat(transitionRules)
                .contains("outcome projection terminal transition is illegal")
                .doesNotContain("select count(operation.operation_id)");
        assertThat(readiness)
                .contains(
                        "count(operation.operation_id) filter ( where"
                                + " operation.required_for_closure and"
                                + " operation.operation_kind = 'operation' )")
                .contains(
                        "receipt.operation_id is null or"
                                + " receipt.receipt_status <> 'succeeded' or"
                                + " receipt.closure_disposition <> 'satisfied'")
                .contains(
                        "required_original_operation_count"
                                + " <> new.expected_required_operation_count or"
                                + " unresolved_required_operation_count <> 0")
                .contains("outcome projection terminal transition requires closure readiness")
                .doesNotContain("required_original_operation_count > 0");
        assertThat(occurrences(readiness, "select count(operation.operation_id) filter"))
                .isEqualTo(1);
        assertAppearsInOrder(
                projection,
                "if new.process_state in ( 'ready_to_close', 'closed',",
                "select count(operation.operation_id) filter",
                "from outcome_operation operation",
                "where operation.projection_id = new.projection_id",
                "if required_original_operation_count",
                "outcome projection terminal transition requires closure readiness");
    }

    @Test
    void appliesOneGlobalLifecycleLockOrderInEveryTrigger() throws Exception {
        String sql = normalizedSql();

        assertLockOrder(
                sql,
                "create function enforce_outcome_projection_authority()",
                "create trigger trg_outcome_projection_authority",
                "'outcome-compensation-order:'",
                "from case_room_epoch epoch",
                "for key share");
        assertLockOrder(
                sql,
                "create function enforce_outcome_operation_binding()",
                "create trigger trg_outcome_operation_binding",
                "'outcome-compensation-order:'",
                "from outcome_process_projection value",
                "for update");
        assertThat(functionSegment(
                        sql,
                        "create function enforce_outcome_operation_binding()",
                        "create trigger trg_outcome_operation_binding"))
                .doesNotContain("if new.operation_kind = 'compensation' then");
        assertLockOrder(
                sql,
                "create function enforce_outcome_attempt_sequence()",
                "create trigger trg_outcome_attempt_sequence",
                "'outcome-compensation-order:'",
                "'outcome-operation-lifecycle:'",
                "for update");
        assertLockOrder(
                sql,
                "create function enforce_outcome_receipt_resolution()",
                "create trigger trg_outcome_receipt_resolution",
                "'outcome-compensation-order:'",
                "'outcome-operation-lifecycle:'",
                "for update");
        assertLockOrder(
                sql,
                "create function enforce_outcome_compensation_parent()",
                "create trigger trg_outcome_compensation_parent",
                "'outcome-compensation-order:'",
                "from outcome_process_projection value",
                "where value.operation_id = new.child_operation_id for key share");
    }

    @Test
    void appliesTheSameGlobalLifecycleLockOrderInJdbcTransactions() throws Exception {
        String source = normalizedJavaSource();

        assertLockOrder(
                source,
                "public outcomeoperation reserve(",
                "public outcomeattemptobservation appendattempt(",
                "locksemantic(compensationorderkey(operation));",
                "locksemantic(\"operation:\"",
                "lockprojection(expectation(operation))");
        assertThat(functionSegment(
                        source,
                        "public outcomeoperation reserve(",
                        "public outcomeattemptobservation appendattempt("))
                .doesNotContain(
                        "if (compensationparent != null) { locksemantic(compensationorderkey(operation));");
        assertLockOrder(
                source,
                "public outcomeattemptobservation appendattempt(",
                "public outcomeoperationreceipt recordreceipt(",
                "locksemantic(compensationorderkey(observation));",
                "locksemantic(operationlifecyclekey(observation.operationid()));",
                "lockoperation(observation.operationid())");
        assertLockOrder(
                source,
                "public outcomeoperationreceipt recordreceipt(",
                "public optional<outcomeoperation> findoperation(",
                "locksemantic(compensationorderkey(receipt));",
                "locksemantic(operationlifecyclekey(receipt.operationid()));",
                "lockoperation(receipt.operationid())");
        assertThat(source)
                .contains("compensationorderkey(outcomeattemptobservation observation)")
                .contains("observation.tenantsurrogate(), observation.caseid(), observation.outcomeepoch()");
    }

    @Test
    void guardsEveryFactLedgerAgainstMutationAndIndexesRecoveryQueries() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("trg_outcome_operation_append_only")
                .contains("trg_outcome_operation_delete_append_only")
                .contains("trg_outcome_attempt_append_only")
                .contains("trg_outcome_attempt_delete_append_only")
                .contains("trg_outcome_receipt_append_only")
                .contains("trg_outcome_receipt_delete_append_only")
                .contains("trg_outcome_compensation_append_only")
                .contains("trg_outcome_compensation_delete_append_only")
                .contains("idx_outcome_operation_reconcile")
                .contains("idx_outcome_attempt_reconcile")
                .contains("idx_outcome_receipt_case_status")
                .contains("idx_outcome_compensation_parent");
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizedJavaSource() throws Exception {
        return Files.readString(LEDGER_SOURCE)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }

    private static void assertLockOrder(
            String sql,
            String functionStart,
            String functionEnd,
            String first,
            String second,
            String third) {
        String function = functionSegment(sql, functionStart, functionEnd);
        assertThat(function.indexOf(first)).isGreaterThanOrEqualTo(0);
        assertThat(function.indexOf(second)).isGreaterThan(function.indexOf(first));
        assertThat(function.indexOf(third)).isGreaterThan(function.indexOf(second));
    }

    private static void assertAppearsInOrder(String source, String... values) {
        int previous = -1;
        for (String value : values) {
            int current = source.indexOf(value, previous + 1);
            assertThat(current).as("position of %s", value).isGreaterThan(previous);
            previous = current;
        }
    }

    private static Set<String> extractInClauseValues(String source, String marker) {
        int start = source.indexOf(marker);
        assertThat(start).as("start of %s", marker).isGreaterThanOrEqualTo(0);
        int end = source.indexOf(')', start + marker.length());
        assertThat(end).as("end of %s", marker).isGreaterThan(start);
        return extractQuotedValues(source.substring(start + marker.length(), end));
    }

    private static Set<String> extractTransitionPairs(String source) {
        Matcher matcher = Pattern.compile(
                        "old\\.process_state = '([^']+)' and new\\.process_state = '([^']+)'")
                .matcher(source);
        Set<String> pairs = new LinkedHashSet<>();
        while (matcher.find()) {
            pairs.add(matcher.group(1) + "->" + matcher.group(2));
        }
        return pairs;
    }

    private static Set<String> extractQuotedValues(String source) {
        Matcher matcher = Pattern.compile("'([^']+)'").matcher(source);
        Set<String> values = new LinkedHashSet<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String functionSegment(String source, String functionStart, String functionEnd) {
        return source.substring(source.indexOf(functionStart), source.indexOf(functionEnd));
    }
}
