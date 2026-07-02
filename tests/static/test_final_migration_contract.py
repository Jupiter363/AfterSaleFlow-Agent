from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = (
    ROOT
    / "java-api-service"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
)


def migration(name: str) -> str:
    return (MIGRATIONS / name).read_text(encoding="utf-8").lower()


def test_final_forward_migrations_exist() -> None:
    assert (MIGRATIONS / "V007__final_dispute_core.sql").is_file()
    assert (
        MIGRATIONS / "V008__final_agent_hearing_governance.sql"
    ).is_file()


def test_final_core_renames_the_business_fact_tables() -> None:
    sql = migration("V007__final_dispute_core.sql")

    for fragment in (
        "alter table fulfillment_case rename to fulfillment_dispute_case",
        "alter table party_submission rename to dispute_submission",
        "alter table claim_issue_evidence_matrix rename to claim_issue_evidence_link",
        "alter table hearing_record rename to hearing_stage_record",
        "alter table approval_record rename to human_review_record",
        "alter table evaluation_trace rename to evaluation_record",
        "hearing_route",
        "logistics_id",
        "initiator_role",
    ):
        assert fragment in sql


def test_final_governance_tables_cover_agents_panel_and_versions() -> None:
    sql = migration("V008__final_agent_hearing_governance.sql")

    required = {
        "evidence_dossier_item",
        "case_timeline_event",
        "deliberation_report",
        "deliberation_finding",
        "remedy_action",
        "approval_policy_decision",
        "agent_run",
        "agent_tool_call",
        "agent_guardrail_event",
        "agent_memory_entry",
        "skill_version",
        "prompt_version",
    }

    for table in required:
        assert f"create table {table}" in sql

    assert "non_final boolean not null default true" in sql
    assert "auto_approve boolean not null default false" in sql
    assert "action_hash varchar(128)" in sql
    assert "profile_version varchar(64)" in sql
