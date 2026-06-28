from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main"


def test_phase7_modules_and_route_api_exist() -> None:
    java_root = JAVA / "java" / "com" / "example" / "dispute"
    for module in ("router", "regularflow", "ruleflow", "policy"):
        assert (java_root / module).is_dir()

    controller = (
        java_root / "router" / "api" / "RouterController.java"
    ).read_text(encoding="utf-8")
    assert '/api/v1/cases/{caseId}/route' in controller
    assert 'RequestHeader("Idempotency-Key")' in controller


def test_route_enum_matches_the_three_formal_paths_exactly() -> None:
    route_enum = (
        JAVA
        / "java"
        / "com"
        / "example"
        / "dispute"
        / "domain"
        / "model"
        / "RouteType.java"
    ).read_text(encoding="utf-8")
    for route in (
        "REGULAR_FULFILLMENT",
        "RULE_BASED_RESOLUTION",
        "DISPUTE_HEARING",
    ):
        assert route in route_enum
    assert "HUMAN_REVIEW" not in route_enum


def test_route_and_conclusion_are_persistent_and_forced_downstream() -> None:
    migration = (
        JAVA / "resources" / "db" / "migration" / "V006__init_router_flow_tables.sql"
    ).read_text(encoding="utf-8")
    assert "create table route_decision" in migration
    assert "create table flow_conclusion" in migration
    assert "requires_remedy_planning boolean not null default true" in migration
    assert "requires_human_review boolean not null default true" in migration
    assert "check (requires_remedy_planning and requires_human_review)" in migration
    assert "uq_route_decision_case unique (case_id)" in migration


def test_router_does_not_close_cases_or_execute_actions() -> None:
    service = (
        JAVA
        / "java"
        / "com"
        / "example"
        / "dispute"
        / "router"
        / "application"
        / "RouterApplicationService.java"
    ).read_text(encoding="utf-8")
    assert "CaseStatus.CLOSED" not in service
    assert "ActionRecord" not in service
    assert "ToolExecutor" not in service
