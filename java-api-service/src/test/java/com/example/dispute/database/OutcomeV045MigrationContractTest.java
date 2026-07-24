package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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
                .contains("jsonb_array_length(value.approved_plan_json -> 'actions') = new.expected_required_operation_count")
                .contains("old.expected_required_operation_count")
                .contains("outcome projection immutable authority changed")
                .contains("left join outcome_operation operation")
                .contains("operation.operation_kind = 'operation' ) = projection.expected_required_operation_count")
                .contains("receipt.closure_disposition <> 'satisfied' ) ) = 0 as closure_ready");
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
                .isEqualTo(4);
    }

    @Test
    void appliesOneGlobalLifecycleLockOrderInEveryTrigger() throws Exception {
        String sql = normalizedSql();

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

    private static String functionSegment(String source, String functionStart, String functionEnd) {
        return source.substring(source.indexOf(functionStart), source.indexOf(functionEnd));
    }
}
