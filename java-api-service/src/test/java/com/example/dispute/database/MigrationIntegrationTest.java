/*
 * 所属模块：数据库迁移入口。
 * 文件职责：验证MigrationIntegration，覆盖 「migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；独立执行 Flyway 迁移并验证 PostgreSQL Schema 可用。
 * 关键边界：迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
 */
package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【数据库迁移入口 / 自动化测试层】类型「MigrationIntegrationTest」。
// 类型职责：集中验证MigrationIntegration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」、「loadTables」、「columnType」、「numericDefinition」、「loadIndexes」、「countRows」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Testcontainers
class MigrationIntegrationTest {

    private static final String DATABASE_NAME = "dispute_system";
    private static final String USERNAME = "dispute_test";
    private static final String PASSWORD = "local_test_password";

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", DATABASE_NAME)
                    .withEnv("POSTGRES_USER", USERNAME)
                    .withEnv("POSTGRES_PASSWORD", PASSWORD)
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forListeningPort());

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema()」。
    // 具体功能：「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema()」：复现“核对完整业务行为（场景方法「migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」）”场景：驱动 「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」、「Flyway.configure」、「DriverManager.getConnection」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「:」、「fulfillment_dispute_case」、「evidence_dossier」、「evidence_dossier_item」。
    // 上游调用：「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema()」守住「数据库迁移入口」的可执行规格，尤其防止 「:」、「fulfillment_dispute_case」、「evidence_dossier」、「evidence_dossier_item」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void migrationsApplyOnceAndCreateTheCompletePostgresqlSchema() throws SQLException {
        String jdbcUrl =
                "jdbc:postgresql://"
                        + POSTGRESQL.getHost()
                        + ":"
                        + POSTGRESQL.getMappedPort(5432)
                        + "/"
                        + DATABASE_NAME;
        Flyway flyway =
                Flyway.configure()
                        .dataSource(jdbcUrl, USERNAME, PASSWORD)
                        .locations("classpath:db/migration")
                        .load();

        MigrateResult first = flyway.migrate();
        MigrateResult second = flyway.migrate();

        assertThat(first.migrationsExecuted).isEqualTo(50);
        assertThat(second.migrationsExecuted).isZero();

        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            assertThat(loadTables(connection))
                    .containsExactlyInAnyOrder(
                            "fulfillment_dispute_case",
                            "evidence_dossier",
                            "evidence_dossier_item",
                            "evidence_item",
                            "party_claim",
                            "issue",
                            "claim_issue_evidence_link",
                            "evidence_request",
                            "dispute_submission",
                            "case_timeline_event",
                            "hearing_state",
                            "hearing_stage_record",
                            "adjudication_draft",
                            "deliberation_report",
                            "deliberation_finding",
                            "remedy_plan",
                            "remedy_action",
                            "approval_policy_decision",
                            "review_packet",
                            "review_task",
                            "human_review_record",
                            "action_record",
                            "agent_run",
                            "agent_run_attempt",
                            "agent_run_stream_event",
                            "agent_tool_call",
                            "agent_guardrail_event",
                            "agent_memory_entry",
                            "skill_version",
                            "prompt_version",
                            "audit_log",
                            "policy_rule",
                            "evaluation_record",
                            "route_decision",
                            "flow_conclusion",
                            "case_participant",
                            "case_room",
                            "room_message",
                            "case_phase_clock",
                            "evidence_verification",
                            "evidence_party_completion",
                            "hearing_round",
                            "hearing_round_party_submission",
                            "settlement_proposal",
                            "settlement_confirmation",
                            "notification",
                            "notification_outbox",
                            "room_turn_memory",
                            "case_intake_dossier",
                            "case_intake_party_completion",
                            "case_access_session",
                            "agent_conversation_session",
                            "agent_session_dossier",
                            "evidence_submission_batch",
                            "agent_a2a_message",
                            "simulated_import_template_cursor",
                            "demo_case_purge_audit",
                            "hearing_flow_instance",
                            "hearing_flow_stage",
                            "hearing_flow_action",
                            "hearing_trial_dossier",
                            "hearing_flow_artifact",
                            "case_command",
                            "case_command_outbox",
                            "case_process_projection",
                            "case_room_epoch",
                            "room_epoch_bootstrap_outbox",
                            "case_intake_graph_thread_binding",
                            "case_intake_snapshot_binding",
                            "case_intake_epoch_selection_binding",
                            "case_intake_epoch_party_authority",
                            "case_intake_command_payload_authority",
                            "case_intake_command_authority",
                            "case_intake_shadow_comparison",
                            "case_intake_synthetic_activity_admission",
                            "domain_operation",
                            "process_reconciliation_issue",
                            "immutable_payload_snapshot",
                            "agent_execution_manifest");
            assertThat(
                            countRows(
                                    connection,
                                    "simulated_import_template_cursor",
                                    "id = 'external-case-template' and next_template_no = 1"))
                    .isOne();
            assertThat(columnType(connection, "evidence_item", "metadata_json"))
                    .isEqualTo("jsonb");
            assertThat(columnType(connection, "action_record", "execution_time"))
                    .isEqualTo("timestamp with time zone");
            assertThat(
                            columnType(
                                    connection,
                                    "review_packet",
                                    "agent_run_refs_json"))
                    .isEqualTo("jsonb");
            assertThat(
                            columnType(
                                    connection,
                                    "human_review_record",
                                    "approval_expires_at"))
                    .isEqualTo("timestamp with time zone");
            assertThat(
                            columnType(
                                    connection,
                                    "action_record",
                                    "external_result_ref"))
                    .isEqualTo("character varying");
            assertThat(columnType(connection, "notification", "dismissed_at"))
                    .isEqualTo("timestamp with time zone");
            assertThat(columnType(connection, "room_message", "message_source"))
                    .isEqualTo("character varying");
            assertThat(columnType(connection, "hearing_flow_action", "participant_id"))
                    .isEqualTo("character varying");
            assertThat(numericDefinition(connection, "remedy_plan", "total_amount"))
                    .isEqualTo("18:2");
            assertThat(loadIndexes(connection))
                    .contains(
                            "idx_fulfillment_case_user_id",
                            "idx_fulfillment_dispute_case_status",
                            "idx_evidence_item_case_id",
                            "idx_review_task_status",
                            "idx_action_record_case_id",
                            "idx_agent_run_case",
                            "uq_agent_run_logical_idempotency_v2",
                            "uq_agent_run_attempt_number",
                            "uq_agent_run_stream_event_attempt_sequence_v2",
                            "idx_deliberation_case",
                            "idx_audit_log_case_id",
                            "uq_policy_rule_code_version",
                            "idx_route_decision_type_created",
                            "idx_flow_conclusion_status",
                            "uq_dispute_external_source",
                            "uq_case_room_type",
                            "uq_settlement_confirmation_role",
                            "uq_notification_business_recipient",
                            "idx_notification_recipient_visible",
                            "uq_agent_a2a_jury_review_report",
                            "uq_evidence_completion_participant",
                            "uq_hearing_flow_action_generated",
                            "uq_hearing_flow_action_party",
                            "uq_hearing_flow_artifact_case_type",
                            "uq_case_command_tenant_command",
                            "uq_case_command_case_sequence",
                            "idx_case_command_outbox_pending",
                            "uq_case_process_projection_workflow",
                            "uq_case_room_epoch_case_room_epoch",
                            "uq_case_room_epoch_active_case",
                            "uq_case_room_epoch_active_workflow",
                            "uq_case_room_epoch_writer_slot",
                            "idx_case_room_epoch_workflow_history",
                            "idx_room_epoch_bootstrap_pending",
                            "idx_room_epoch_bootstrap_lease",
                            "uq_r15_case_room_epoch_selection",
                            "uq_r15_graph_thread_authority",
                            "uq_r15_payload_command_route",
                            "uq_r15_command_payload",
                            "uq_intake_shadow_comparison_party_authority",
                            "uq_intake_shadow_comparison_command_authority",
                            "idx_intake_shadow_comparison_epoch",
                            "idx_intake_shadow_comparison_command",
                            "uq_intake_synthetic_admission_kid_jti",
                            "idx_intake_synthetic_admission_activity",
                            "idx_intake_synthetic_admission_authority",
                            "uq_domain_operation_tenant_key",
                            "idx_payload_snapshot_case_visibility",
                            "uq_agent_execution_manifest_logical_run");
            assertThat(loadIndexDefinitions(connection, "case_intake_command_payload_authority"))
                    .allSatisfy(definition -> assertThat(definition).doesNotContain("object_uri"));
            assertThat(
                            countRows(
                                    connection,
                                    "fulfillment_dispute_case",
                                    "source_type = 'EXTERNAL_IMPORT'"))
                    .isZero();
            assertHearingRoundFiveIsSupported(connection);
            assertThat(loadTriggers(connection))
                    .contains(
                            "trg_room_message_append_only",
                            "trg_case_timeline_event_append_only",
                            "trg_hearing_flow_action_append_only",
                            "trg_hearing_trial_dossier_append_only",
                            "trg_hearing_flow_artifact_append_only",
                            "trg_immutable_payload_snapshot_append_only",
                            "trg_agent_execution_manifest_append_only",
                            "trg_case_room_epoch_immutable_selection",
                            "trg_case_process_projection_activation",
                            "trg_r15_selection_immutable",
                            "trg_r15_party_immutable",
                            "trg_r15_payload_immutable",
                            "trg_r15_command_immutable",
                            "trg_r15_existing_private_event_assertion",
                            "trg_r15_command_exact_comparison",
                            "trg_intake_shadow_comparison_immutable",
                            "trg_intake_synthetic_admission_payload_exact",
                            "trg_intake_synthetic_admission_immutable");
            assertThat(loadStatementLevelTruncateTriggers(connection))
                    .contains(
                            "trg_intake_shadow_comparison_no_truncate",
                            "trg_intake_synthetic_admission_no_truncate");
            assertFormalJuryReportUniqueness(connection);
            assertAppendOnlyTablesRejectMutation(connection);
        }
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.loadTables(Connection)」。
    // 具体功能：「MigrationIntegrationTest.loadTables(Connection)」：作为测试辅助方法为“核对完整业务行为（场景方法「loadTables」）”组装或读取「HashSet」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「MigrationIntegrationTest.loadTables(Connection)」由本测试类中的 「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」 调用。
    // 下游影响：「MigrationIntegrationTest.loadTables(Connection)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「MigrationIntegrationTest.loadTables(Connection)」守住「数据库迁移入口」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static Set<String> loadTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                """
                                select table_name
                                from information_schema.tables
                                where table_schema = 'public'
                                  and table_type = 'BASE TABLE'
                                  and table_name <> 'flyway_schema_history'
                                """)) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return tables;
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.columnType(Connection,String,String)」。
    // 具体功能：「MigrationIntegrationTest.columnType(Connection,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「columnType」）”组装或读取「connection.prepareStatement」、「statement.setString」、「statement.executeQuery」、「result.next」，供本测试类的场景方法复用。
    // 上游调用：「MigrationIntegrationTest.columnType(Connection,String,String)」由本测试类中的 「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」 调用。
    // 下游影响：「MigrationIntegrationTest.columnType(Connection,String,String)」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「MigrationIntegrationTest.columnType(Connection,String,String)」守住「数据库迁移入口」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static String columnType(Connection connection, String table, String column)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                        select data_type
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = ?
                          and column_name = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.numericDefinition(Connection,String,String)」。
    // 具体功能：「MigrationIntegrationTest.numericDefinition(Connection,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「numericDefinition」）”组装或读取「connection.prepareStatement」、「statement.setString」、「statement.executeQuery」、「result.next」，供本测试类的场景方法复用。
    // 上游调用：「MigrationIntegrationTest.numericDefinition(Connection,String,String)」由本测试类中的 「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」 调用。
    // 下游影响：「MigrationIntegrationTest.numericDefinition(Connection,String,String)」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「MigrationIntegrationTest.numericDefinition(Connection,String,String)」守住「数据库迁移入口」的可执行规格，尤其防止 「:」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static String numericDefinition(
            Connection connection, String table, String column) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                        select numeric_precision, numeric_scale
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = ?
                          and column_name = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1) + ":" + result.getInt(2);
            }
        }
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.loadIndexes(Connection)」。
    // 具体功能：「MigrationIntegrationTest.loadIndexes(Connection)」：作为测试辅助方法为“核对完整业务行为（场景方法「loadIndexes」）”组装或读取「HashSet」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「MigrationIntegrationTest.loadIndexes(Connection)」由本测试类中的 「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」 调用。
    // 下游影响：「MigrationIntegrationTest.loadIndexes(Connection)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「MigrationIntegrationTest.loadIndexes(Connection)」守住「数据库迁移入口」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static Set<String> loadIndexDefinitions(Connection connection, String table)
            throws SQLException {
        Set<String> definitions = new HashSet<>();
        try (var statement =
                connection.prepareStatement(
                        """
                        select indexdef
                        from pg_indexes
                        where schemaname = 'public'
                          and tablename = ?
                        """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    definitions.add(result.getString(1));
                }
            }
        }
        return definitions;
    }

    private static Set<String> loadIndexes(Connection connection) throws SQLException {
        Set<String> indexes = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "select indexname from pg_indexes where schemaname = 'public'")) {
            while (result.next()) {
                indexes.add(result.getString(1));
            }
        }
        return indexes;
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.countRows(Connection,String,String)」。
    // 具体功能：「MigrationIntegrationTest.countRows(Connection,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「countRows」）”组装或读取「connection.createStatement」、「statement.executeQuery」、「result.next」、「result.getLong」，供本测试类的场景方法复用。
    // 上游调用：「MigrationIntegrationTest.countRows(Connection,String,String)」由本测试类中的 「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」 调用。
    // 下游影响：「MigrationIntegrationTest.countRows(Connection,String,String)」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「MigrationIntegrationTest.countRows(Connection,String,String)」守住「数据库迁移入口」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static long countRows(
            Connection connection, String table, String condition)
            throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "select count(*) from " + table + " where " + condition)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.loadTriggers(Connection)」。
    // 具体功能：「MigrationIntegrationTest.loadTriggers(Connection)」：作为测试辅助方法为“核对完整业务行为（场景方法「loadTriggers」）”组装或读取「HashSet」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「MigrationIntegrationTest.loadTriggers(Connection)」由本测试类中的 「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」 调用。
    // 下游影响：「MigrationIntegrationTest.loadTriggers(Connection)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「MigrationIntegrationTest.loadTriggers(Connection)」守住「数据库迁移入口」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static Set<String> loadTriggers(Connection connection) throws SQLException {
        Set<String> triggers = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                """
                                select trigger_name
                                from information_schema.triggers
                                where trigger_schema = 'public'
                                """)) {
            while (result.next()) {
                triggers.add(result.getString(1));
            }
        }
        return triggers;
    }

    private static Set<String> loadStatementLevelTruncateTriggers(Connection connection)
            throws SQLException {
        Set<String> triggers = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                """
                                select trigger.tgname
                                from pg_catalog.pg_trigger trigger
                                join pg_catalog.pg_class target on target.oid = trigger.tgrelid
                                join pg_catalog.pg_namespace schema on schema.oid = target.relnamespace
                                where schema.nspname = 'public'
                                  and not trigger.tgisinternal
                                  and (trigger.tgtype & 1) = 0
                                  and (trigger.tgtype & 32) <> 0
                                """)) {
            while (result.next()) {
                triggers.add(result.getString(1));
            }
        }
        return triggers;
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.assertAppendOnlyTablesRejectMutation(Connection)」。
    // 具体功能：「MigrationIntegrationTest.assertAppendOnlyTablesRejectMutation(Connection)」：作为测试辅助方法为“核对完整业务行为（场景方法「assertAppendOnlyTablesRejectMutation」）”组装或读取「connection.createStatement」、「statement.executeUpdate」、「assertThatSqlFails」，供本测试类的场景方法复用。
    // 上游调用：「MigrationIntegrationTest.assertAppendOnlyTablesRejectMutation(Connection)」由本测试类中的 「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」 调用。
    // 下游影响：「MigrationIntegrationTest.assertAppendOnlyTablesRejectMutation(Connection)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「MigrationIntegrationTest.assertAppendOnlyTablesRejectMutation(Connection)」守住「数据库迁移入口」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static void assertAppendOnlyTablesRejectMutation(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key,
                        case_type, case_status, initiator_role, initiator_id,
                        respondent_role, respondent_id, risk_level, title, description,
                        created_by, updated_by
                    ) values (
                        'CASE_APPEND_ONLY', 'user-local', 'merchant-local', 'append-only-case',
                        'DISPUTE', 'EVIDENCE_OPEN', 'USER', 'user-local',
                        'MERCHANT', 'merchant-local', 'HIGH', 'Append-only test',
                        'Database immutability test', 'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into case_room (
                        id, case_id, room_type, room_status, opened_at,
                        created_by, updated_by
                    ) values (
                        'ROOM_APPEND_ONLY', 'CASE_APPEND_ONLY', 'EVIDENCE', 'OPEN', now(),
                        'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into room_message (
                        id, case_id, room_id, sequence_no, sender_type, sender_role,
                        sender_id, message_source, message_type, message_text,
                        idempotency_key, created_by
                    ) values (
                        'MESSAGE_APPEND_ONLY', 'CASE_APPEND_ONLY', 'ROOM_APPEND_ONLY', 1,
                        'PARTY', 'USER', 'user-local', 'PARTY_ACTION', 'PARTY_TEXT', 'original',
                        'append-only-message', 'user-local'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into case_timeline_event (
                        id, case_id, sequence_no, event_type, event_time, created_by
                    ) values (
                        'EVENT_APPEND_ONLY', 'CASE_APPEND_ONLY', 1, 'ROOM_MESSAGE_CREATED',
                        now(), 'test'
                    )
                    """);
        }

        assertThatSqlFails(
                connection,
                "update room_message set message_text = 'mutated' where id = 'MESSAGE_APPEND_ONLY'",
                "room_message is append-only");
        assertThatSqlFails(
                connection,
                "delete from case_timeline_event where id = 'EVENT_APPEND_ONLY'",
                "case_timeline_event is append-only");
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.assertHearingRoundFiveIsSupported(Connection)」。
    // 具体功能：「MigrationIntegrationTest.assertHearingRoundFiveIsSupported(Connection)」：作为测试辅助方法为“核对完整业务行为（场景方法「assertHearingRoundFiveIsSupported」）”组装或读取「connection.createStatement」、「statement.executeUpdate」，供本测试类的场景方法复用。
    // 上游调用：「MigrationIntegrationTest.assertHearingRoundFiveIsSupported(Connection)」由本测试类中的 「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」 调用。
    // 下游影响：「MigrationIntegrationTest.assertHearingRoundFiveIsSupported(Connection)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「MigrationIntegrationTest.assertHearingRoundFiveIsSupported(Connection)」守住「数据库迁移入口」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static void assertHearingRoundFiveIsSupported(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key,
                        case_type, case_status, initiator_role, initiator_id,
                        respondent_role, respondent_id, hearing_route, risk_level,
                        title, description, current_room, created_by, updated_by
                    ) values (
                        'CASE_ROUND_FIVE', 'user-local', 'merchant-local',
                        'round-five-case', 'DISPUTE', 'HEARING', 'USER', 'user-local',
                        'MERCHANT', 'merchant-local', 'FULL_HEARING',
                        'HIGH', 'Round five test',
                        'Database constraint must match configurable hearing rounds.',
                        'HEARING', 'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into hearing_round (
                        id, case_id, round_no, round_status, dossier_version,
                        opened_at, round_deadline_at, summary_json,
                        created_by, updated_by
                    ) values (
                        'HROUND_FIVE', 'CASE_ROUND_FIVE', 5, 'OPEN', 1,
                        now(), now() + interval '5 minutes', '{}',
                        'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into hearing_round_party_submission (
                        id, case_id, round_id, round_no, participant_role,
                        participant_id, submission_source, submission_json,
                        submitted_at, created_by, updated_by
                    ) values (
                        'HROUND_SUB_FIVE', 'CASE_ROUND_FIVE', 'HROUND_FIVE',
                        5, 'USER', 'user-local', 'PARTY_ACTION', '{}',
                        now(), 'user-local', 'user-local'
                    )
                    """);
        }
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.assertFormalJuryReportUniqueness(Connection)」。
    // 具体功能：「MigrationIntegrationTest.assertFormalJuryReportUniqueness(Connection)」：作为测试辅助方法为“核对完整业务行为（场景方法「assertFormalJuryReportUniqueness」）”组装或读取「connection.createStatement」、「statement.executeUpdate」、「assertThatSqlFails」，供本测试类的场景方法复用。
    // 上游调用：「MigrationIntegrationTest.assertFormalJuryReportUniqueness(Connection)」由本测试类中的 「MigrationIntegrationTest.migrationsApplyOnceAndCreateTheCompletePostgresqlSchema」 调用。
    // 下游影响：「MigrationIntegrationTest.assertFormalJuryReportUniqueness(Connection)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「MigrationIntegrationTest.assertFormalJuryReportUniqueness(Connection)」守住「数据库迁移入口」的可执行规格，尤其防止 「uq_agent_a2a_jury_review_report」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static void assertFormalJuryReportUniqueness(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key,
                        case_type, case_status, initiator_role, initiator_id,
                        respondent_role, respondent_id, risk_level, title, description,
                        created_by, updated_by
                    ) values (
                        'CASE_A2A_UNIQUENESS', 'user-local', 'merchant-local',
                        'a2a-uniqueness-case', 'DISPUTE', 'HEARING', 'USER', 'user-local',
                        'MERCHANT', 'merchant-local', 'HIGH',
                        'A2A uniqueness test', 'Formal jury reports must be unique.',
                        'test', 'test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_a2a_message (
                        id, case_id, round_no, from_agent, to_agent, message_type,
                        input_refs_json, payload_json, visibility, created_at, created_by
                    ) values (
                        'A2A_JURY_REPORT_1', 'CASE_A2A_UNIQUENESS', 3,
                        'JURY_PANEL', 'PRESIDING_JUDGE', 'JURY_REVIEW_REPORT',
                        '{}', '{}', 'REVIEWER_VISIBLE', now(), 'jury-panel'
                    )
                    """);
        }

        assertThatSqlFails(
                connection,
                """
                insert into agent_a2a_message (
                    id, case_id, round_no, from_agent, to_agent, message_type,
                    input_refs_json, payload_json, visibility, created_at, created_by
                ) values (
                    'A2A_JURY_REPORT_2', 'CASE_A2A_UNIQUENESS', 3,
                    'JURY_PANEL', 'PRESIDING_JUDGE', 'JURY_REVIEW_REPORT',
                    '{}', '{}', 'REVIEWER_VISIBLE', now(), 'jury-panel'
                )
                """,
                "uq_agent_a2a_jury_review_report");

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into agent_a2a_message (
                        id, case_id, round_no, from_agent, to_agent, message_type,
                        input_refs_json, payload_json, visibility, created_at, created_by
                    ) values
                        (
                            'A2A_SILENT_NOTE_1', 'CASE_A2A_UNIQUENESS', 2,
                            'JURY_PANEL', 'PRESIDING_JUDGE', 'JURY_SILENT_NOTE',
                            '{}', '{}', 'SYSTEM_AUDIT_ONLY', now(), 'jury-panel'
                        ),
                        (
                            'A2A_SILENT_NOTE_2', 'CASE_A2A_UNIQUENESS', 2,
                            'JURY_PANEL', 'PRESIDING_JUDGE', 'JURY_SILENT_NOTE',
                            '{}', '{}', 'SYSTEM_AUDIT_ONLY', now(), 'jury-panel'
                        )
                    """);
        }
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「MigrationIntegrationTest.assertThatSqlFails(Connection,String,String)」。
    // 具体功能：「MigrationIntegrationTest.assertThatSqlFails(Connection,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「assertThatSqlFails」）”组装或读取「connection.createStatement」、「statement.executeUpdate」、「hasMessageContaining」、「isInstanceOf」，供本测试类的场景方法复用。
    // 上游调用：「MigrationIntegrationTest.assertThatSqlFails(Connection,String,String)」由本测试类中的 「MigrationIntegrationTest.assertAppendOnlyTablesRejectMutation」、「MigrationIntegrationTest.assertFormalJuryReportUniqueness」 调用。
    // 下游影响：「MigrationIntegrationTest.assertThatSqlFails(Connection,String,String)」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「MigrationIntegrationTest.assertThatSqlFails(Connection,String,String)」守住「数据库迁移入口」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static void assertThatSqlFails(
            Connection connection, String sql, String expectedMessage) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> {
                            try (Statement statement = connection.createStatement()) {
                                statement.executeUpdate(sql);
                            }
                        })
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(expectedMessage);
    }
}
