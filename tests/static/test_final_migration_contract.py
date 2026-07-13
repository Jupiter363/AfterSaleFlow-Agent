# 文件作用：自动化测试文件，验证 test_final_migration_contract 相关模块的行为、契约或页面布局。

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


# 所属模块：跨服务契约测试 > test_final_migration_contract；函数角色：模块公开业务函数。
# 具体功能：`migration` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`lower`、`read_text`。
# 上下游：上游为 本文件的 `test_final_core_renames_the_business_fact_tables`、`test_final_governance_tables_cover_agents_panel_and_versions`、`test_room_collaboration_migrations_cover_the_final_product`、`test_room_messages_and_replay_events_are_database_append_only`；下游为 协作调用 `lower`、`read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def migration(name: str) -> str:
    return (MIGRATIONS / name).read_text(encoding="utf-8").lower()


# 所属模块：跨服务契约测试 > test_final_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_final_forward_migrations_exist` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`is_file`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `is_file`。
# 系统意义：固定“跨服务契约测试 > test_final_migration_contract”的可观察契约，防止后续重构改变业务结果。
def test_final_forward_migrations_exist() -> None:
    assert (MIGRATIONS / "V007__final_dispute_core.sql").is_file()
    assert (
        MIGRATIONS / "V008__final_agent_hearing_governance.sql"
    ).is_file()
    assert (MIGRATIONS / "V010__case_rooms_and_participants.sql").is_file()
    assert (
        MIGRATIONS
        / "V011__evidence_verification_and_hearing_settlement.sql"
    ).is_file()
    assert (
        MIGRATIONS / "V012__case_events_and_notification_outbox.sql"
    ).is_file()
    assert (MIGRATIONS / "V013__append_only_room_stream.sql").is_file()


# 所属模块：跨服务契约测试 > test_final_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_final_core_renames_the_business_fact_tables` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `migration`。
# 系统意义：固定“跨服务契约测试 > test_final_migration_contract”的可观察契约，防止后续重构改变业务结果。
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


# 所属模块：跨服务契约测试 > test_final_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_final_governance_tables_cover_agents_panel_and_versions` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `migration`。
# 系统意义：固定“跨服务契约测试 > test_final_migration_contract”的可观察契约，防止后续重构改变业务结果。
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


# 所属模块：跨服务契约测试 > test_final_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_room_collaboration_migrations_cover_the_final_product` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `migration`。
# 系统意义：固定“跨服务契约测试 > test_final_migration_contract”的可观察契约，防止后续重构改变业务结果。
def test_room_collaboration_migrations_cover_the_final_product() -> None:
    rooms = migration("V010__case_rooms_and_participants.sql")
    evidence_hearing = migration(
        "V011__evidence_verification_and_hearing_settlement.sql"
    )
    events = migration("V012__case_events_and_notification_outbox.sql")

    for table in (
        "case_participant",
        "case_room",
        "room_message",
        "case_phase_clock",
    ):
        assert f"create table {table}" in rooms

    for table in (
        "evidence_verification",
        "evidence_party_completion",
        "hearing_round",
        "settlement_proposal",
        "settlement_confirmation",
    ):
        assert f"create table {table}" in evidence_hearing

    for table in ("notification", "notification_outbox"):
        assert f"create table {table}" in events

    assert "source_type" in rooms
    assert "external_case_ref" in rooms
    assert "sequence_no" in events
    assert "audience_json" in events
    assert "business_event_key" in events


# 所属模块：跨服务契约测试 > test_final_migration_contract；函数角色：回归测试用例。
# 具体功能：`test_room_messages_and_replay_events_are_database_append_only` 验证房间消息在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `migration`。
# 系统意义：固定“跨服务契约测试 > test_final_migration_contract”的可观察契约，防止后续重构改变业务结果。
def test_room_messages_and_replay_events_are_database_append_only() -> None:
    append_only = migration("V013__append_only_room_stream.sql")

    assert "trg_room_message_append_only" in append_only
    assert "trg_case_timeline_event_append_only" in append_only
    assert "before update or delete or truncate" in append_only
