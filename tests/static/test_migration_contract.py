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
}


def migration_text() -> str:
    return "\n".join(
        (MIGRATION_DIR / name).read_text(encoding="utf-8")
        for name in EXPECTED_MIGRATIONS
    ).lower()


def table_body(sql: str, table: str) -> str:
    match = re.search(
        rf"create\s+table\s+{table}\s*\((.*?)\);",
        sql,
        flags=re.DOTALL | re.IGNORECASE,
    )
    assert match, f"missing CREATE TABLE for {table}"
    return match.group(1)


def test_ordered_flyway_migrations_exist() -> None:
    actual = sorted(path.name for path in MIGRATION_DIR.glob("V*.sql"))

    assert actual == EXPECTED_MIGRATIONS


def test_all_required_tables_are_created_once() -> None:
    sql = migration_text()
    created = re.findall(r"create\s+table\s+([a-z_]+)\s*\(", sql)

    assert set(created) == REQUIRED_TABLES
    assert len(created) == len(REQUIRED_TABLES)


def test_business_tables_have_ids_timestamps_and_audit_actors() -> None:
    sql = migration_text()
    append_only = {
        "audit_log",
        "hearing_record",
        "approval_record",
        "action_record",
        "route_decision",
    }

    for table in REQUIRED_TABLES:
        body = table_body(sql, table)
        assert re.search(r"\bid\s+varchar\(64\)\s+primary\s+key", body), table
        assert "created_at timestamptz" in body, table
        assert "created_by varchar(128)" in body, table
        if table not in append_only:
            assert "updated_at timestamptz" in body, table
            assert "updated_by varchar(128)" in body, table


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


def test_immutable_audit_and_execution_records_have_no_delete_cascade() -> None:
    sql = migration_text()

    for table in ("audit_log", "approval_record", "action_record", "hearing_record"):
        body = table_body(sql, table)
        assert "on delete cascade" not in body, table
