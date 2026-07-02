from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main" / "java" / "com" / "example" / "dispute"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_remedy_planner_supports_all_routes_and_gates_every_action() -> None:
    planner = read(JAVA / "remedy" / "domain" / "RemedyPlanner.java")
    assert "TRANSFERRED" in read(
        JAVA / "domain" / "model" / "RouteType.java"
    )
    assert "SIMPLE_HEARING" in read(
        JAVA / "domain" / "model" / "RouteType.java"
    )
    assert "FULL_HEARING" in planner
    assert "PLATFORM_REVIEW_APPROVED" in planner
    assert "requiresApproval" in read(
        JAVA / "remedy" / "domain" / "PlannedRemedyAction.java"
    )
    assert "Tool" not in planner


def test_remedy_plan_persists_risk_idempotency_preconditions_and_notifications() -> None:
    entity = read(
        JAVA
        / "infrastructure"
        / "persistence"
        / "entity"
        / "RemedyPlanEntity.java"
    )
    service = read(
        JAVA / "remedy" / "application" / "RemedyApplicationService.java"
    )
    for column in (
        "actions_json",
        "preconditions_json",
        "notification_plan_json",
        "risk_level",
        "requires_human_review",
    ):
        assert column in entity
    assert "REMEDY_PLAN_CREATED" in service
    assert "markRemedyPlanned" in service


def test_workflow_enters_remedy_before_approval_and_api_is_read_only() -> None:
    workflow = read(
        JAVA
        / "workflow"
        / "temporal"
        / "CaseFulfillmentDisputeWorkflowImpl.java"
    )
    activity = read(
        JAVA
        / "workflow"
        / "temporal"
        / "CaseFulfillmentDisputeActivities.java"
    )
    controller = read(JAVA / "remedy" / "api" / "RemedyController.java")
    assert "planRemedy" in activity
    assert "createReviewTask" in activity
    assert "activities.planRemedy" in workflow
    assert workflow.index("activities.planRemedy") < workflow.index(
        "activities.createReviewTask"
    )
    assert "@GetMapping" in controller
    assert "@PostMapping" not in controller
