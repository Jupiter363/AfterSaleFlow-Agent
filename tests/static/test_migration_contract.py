# 文件作用：自动化测试文件，验证 test_migration_contract 相关模块的行为、契约或页面布局。

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_DIR = (
    ROOT / "java-api-service" / "src" / "main" / "resources" / "db" / "migration"
)

EXPECTED_MIGRATIONS = [
    "V001__init_case_tables.sql",
    "V002__init_evidence_tables.sql",
    "V003__init_hearing_tables.sql",
    "V004__init_review_executor_tables.sql",
    "V005__init_policy_audit_tables.sql",
    "V006__init_router_flow_tables.sql",
    "V007__final_dispute_core.sql",
    "V008__final_agent_hearing_governance.sql",
    "V009__freeze_review_and_execution_chain.sql",
    "V010__case_rooms_and_participants.sql",
    "V011__evidence_verification_and_hearing_settlement.sql",
    "V012__case_events_and_notification_outbox.sql",
    "V013__append_only_room_stream.sql",
]

REQUIRED_TABLES = {
    "fulfillment_case",
    "evidence_dossier",
    "evidence_item",
    "party_claim",
    "issue",
    "claim_issue_evidence_matrix",
    "evidence_request",
    "party_submission",
    "hearing_state",
    "hearing_record",
    "adjudication_draft",
    "remedy_plan",
    "review_packet",
    "review_task",
    "approval_record",
    "action_record",
    "audit_log",
    "policy_rule",
    "evaluation_trace",
    "route_decision",
    "flow_conclusion",
    "evidence_dossier_item",
    "case_timeline_event",
    "agent_run",
    "agent_tool_call",
    "agent_guardrail_event",
    "agent_memory_entry",
    "skill_version",
    "prompt_version",
    "deliberation_report",
    "deliberation_finding",
    "remedy_action",
    "approval_policy_decision",
    "case_participant",
    "case_room",
    "room_message",
    "case_phase_clock",
    "evidence_verification",
    "evidence_party_completion",
    "hearing_round",
    "settlement_proposal",
    "settlement_confirmation",
    "notification",
    "notification_outbox",
}


# 所属模块：跨服务契约测试 > test_migration_contract；函数角色：模块公开业务函数。
# 具体功能：`migration_text` 围绕展示文本计算该函数独立负责的业务派生值；关键协作调用：`lower`、`join`、`read_text`。
# 上下游：上游为 本文件的 `test_all_required_tables_are_created_once`、`test_business_tables_have_ids_timestamps_and_audit_actors`、`test_json_money_time_and_soft_delete_types_follow_contract`、`test_critical_uniqueness_foreign_keys_and_indexes_exist`；下游为 协作调用 `lower`、`join`、`read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def migration_text() -> str:
    return "\n".join(
        (MIGRATION_DIR / name).read_text(encoding="utf-8")
        for name in EXPECTED_MIGRATIONS
    ).lower()


# 所属模块：跨服务契约测试 > test_migration_contract；函数角色：模块公开业务函数。
# 具体功能：`table_body` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`re.search`、`match.group`。
# 上下游：上游为 本文件的 `test_business_tables_have_ids_timestamps_and_audit_actors`、`test_json_money_time_and_soft_delete_types_follow_contract`、`test_immutable_audit_and_execution_records_have_no_delete_cascade`；下游为 协作调用 `re.search`、`match.group`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def table_body(sql: str, table: str) -> str:
    match = re.search(
        rf"create\s+table\s+{table}\s*\((.*?)\);",
        sql,
        flags=re.DOTALL | re.IGNORECASE,
    )
    assert match, f"missing CREATE TABLE for {table}"
    return match.group(1)


# 所属模块：跨服务契约测试 > test_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_ordered_flyway_migrations_exist` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`MIGRATION_DIR.glob`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `MIGRATION_DIR.glob`。
# 系统意义：固定“跨服务契约测试 > test_migration_contract”的可观察契约，防止后续重构改变业务结果。
def test_ordered_flyway_migrations_exist() -> None:
    actual = sorted(path.name for path in MIGRATION_DIR.glob("V*.sql"))

    assert actual == EXPECTED_MIGRATIONS


# 所属模块：跨服务契约测试 > test_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_all_required_tables_are_created_once` 把上游材料组装为本阶段可消费的被测业务场景；关键协作调用：`re.findall`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `migration_text`。
# 系统意义：固定“跨服务契约测试 > test_migration_contract”的可观察契约，防止后续重构改变业务结果。
def test_all_required_tables_are_created_once() -> None:
    sql = migration_text()
    created = re.findall(r"create\s+table\s+([a-z_]+)\s*\(", sql)

    assert set(created) == REQUIRED_TABLES
    assert len(created) == len(REQUIRED_TABLES)


# 所属模块：跨服务契约测试 > test_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_business_tables_have_ids_timestamps_and_audit_actors` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`re.search`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `migration_text`、`table_body`。
# 系统意义：固定“跨服务契约测试 > test_migration_contract”的可观察契约，防止后续重构改变业务结果。
def test_business_tables_have_ids_timestamps_and_audit_actors() -> None:
    sql = migration_text()
    append_only = {
        "audit_log",
        "hearing_record",
        "approval_record",
        "action_record",
        "route_decision",
        "evidence_dossier_item",
        "case_timeline_event",
        "agent_run",
        "agent_tool_call",
        "agent_guardrail_event",
        "agent_memory_entry",
        "deliberation_report",
        "deliberation_finding",
        "remedy_action",
        "approval_policy_decision",
        "room_message",
        "evidence_verification",
        "evidence_party_completion",
        "settlement_confirmation",
        "notification",
    }
    system_managed = {
        "evidence_verification",
        "notification",
        "notification_outbox",
    }

    for table in REQUIRED_TABLES:
        body = table_body(sql, table)
        assert re.search(r"\bid\s+varchar\(64\)\s+primary\s+key", body), table
        assert "created_at timestamptz" in body, table
        if table not in system_managed:
            assert "created_by varchar(128)" in body, table
        if table not in append_only:
            assert "updated_at timestamptz" in body, table
            if table not in system_managed:
                assert "updated_by varchar(128)" in body, table


# 所属模块：跨服务契约测试 > test_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_json_money_time_and_soft_delete_types_follow_contract` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`sql.count`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `migration_text`、`table_body`。
# 系统意义：固定“跨服务契约测试 > test_migration_contract”的可观察契约，防止后续重构改变业务结果。
def test_json_money_time_and_soft_delete_types_follow_contract() -> None:
    sql = migration_text()

    assert sql.count(" jsonb") >= 20
    assert "numeric(18,2)" in table_body(sql, "remedy_plan")
    assert "execution_time timestamptz" in table_body(sql, "action_record")
    assert "closed_at timestamptz" in table_body(sql, "fulfillment_case")
    for table in {
        "fulfillment_case",
        "evidence_dossier",
        "evidence_item",
        "party_claim",
        "issue",
        "policy_rule",
    }:
        assert "deleted_at timestamptz" in table_body(sql, table), table


# 所属模块：跨服务契约测试 > test_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_critical_uniqueness_foreign_keys_and_indexes_exist` 验证合议质疑结果在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `migration_text`。
# 系统意义：固定“跨服务契约测试 > test_migration_contract”的可观察契约，防止后续重构改变业务结果。
def test_critical_uniqueness_foreign_keys_and_indexes_exist() -> None:
    sql = migration_text()

    for fragment in (
        "creation_idempotency_key varchar(128) not null unique",
        "unique (case_id)",
        "idempotency_key varchar(128) not null unique",
        "rule_code varchar(128) not null",
        "foreign key (case_id) references fulfillment_case(id)",
        "create index idx_fulfillment_case_user_id",
        "create index idx_fulfillment_case_merchant_id",
        "create index idx_fulfillment_case_status",
        "create index idx_evidence_item_case_id",
        "create index idx_review_task_status",
        "create index idx_action_record_case_id",
        "create index idx_audit_log_case_id",
        "create unique index uq_policy_rule_code_version",
    ):
        assert fragment in sql


# 所属模块：跨服务契约测试 > test_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_immutable_audit_and_execution_records_have_no_delete_cascade` 验证履约执行动作在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `migration_text`、`table_body`。
# 系统意义：固定“跨服务契约测试 > test_migration_contract”的可观察契约，防止后续重构改变业务结果。
def test_immutable_audit_and_execution_records_have_no_delete_cascade() -> None:
    sql = migration_text()

    for table in ("audit_log", "approval_record", "action_record", "hearing_record"):
        body = table_body(sql, table)
        assert "on delete cascade" not in body, table


# 所属模块：跨服务契约测试 > test_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_frozen_review_and_execution_provenance_are_migrated` 验证人工复核信息在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `migration_text`。
# 系统意义：固定“跨服务契约测试 > test_migration_contract”的可观察契约，防止后续重构改变业务结果。
def test_frozen_review_and_execution_provenance_are_migrated() -> None:
    sql = migration_text()

    for fragment in (
        "add column frozen boolean not null default true",
        "add column agent_run_refs_json jsonb not null default '[]'::jsonb",
        "add column review_packet_id varchar(64)",
        "add column action_snapshot_hash varchar(128)",
        "add column approval_expires_at timestamptz",
        "add column evidence_refs_json jsonb not null default '[]'::jsonb",
        "add column rule_refs_json jsonb not null default '[]'::jsonb",
        "add column external_result_ref varchar(256)",
    ):
        assert fragment in sql
