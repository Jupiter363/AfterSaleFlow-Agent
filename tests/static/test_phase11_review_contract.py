from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main" / "java" / "com" / "example" / "dispute"

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")

def test_review_api_and_real_human_gate_exist() -> None:
    controller = read(JAVA / "review" / "api" / "ReviewController.java")
    service = read(JAVA / "review" / "application" / "ReviewApplicationService.java")
    workflow = read(JAVA / "workflow" / "temporal" / "CaseFulfillmentDisputeWorkflowImpl.java")
    for path in ('"/{taskId}/packet"', '"/{taskId}/decision"'):
        assert path in controller
    assert "autoApprove" in read(JAVA / "review" / "domain" / "ApprovalPolicyDecision.java")
    assert "false" in read(JAVA / "review" / "domain" / "ApprovalPolicyEngine.java")
    assert "WAITING_HUMAN_REVIEW" in workflow
    assert "CUSTOMER_SERVICE" in service and "assertCanDecide" in service
    assert "originalPlanJson" in read(JAVA / "infrastructure" / "persistence" / "entity" / "ApprovalRecordEntity.java")

def test_all_review_actions_and_temporal_evidence_return_are_supported() -> None:
    decision = read(JAVA / "domain" / "model" / "ApprovalDecisionType.java")
    workflow = read(JAVA / "workflow" / "temporal" / "CaseFulfillmentDisputeWorkflowImpl.java")
    for action in ("APPROVE", "MODIFY_AND_APPROVE", "REJECT", "REQUEST_MORE_EVIDENCE", "ESCALATE_MANUAL"):
        assert action in decision
    assert "REQUEST_MORE_EVIDENCE" in workflow
    assert "Workflow.await(" in workflow

def test_reviewer_frontend_is_backend_driven_and_role_gated() -> None:
    app = read(ROOT / "frontend" / "src" / "App.vue")
    actor = read(ROOT / "frontend" / "src" / "state" / "actor.js")
    review = read(
        ROOT / "frontend" / "src" / "views" / "ReviewWorkbenchView.vue"
    )
    api = read(ROOT / "frontend" / "src" / "api" / "review.js")
    package = read(ROOT / "frontend" / "package.json")
    assert "PLATFORM_REVIEWER" in app
    assert "CUSTOMER_SERVICE" in actor
    assert "MODIFY_AND_APPROVE" in review
    assert "REQUEST_MORE_EVIDENCE" in review
    assert "/reviews" in api
    assert '"element-plus"' in package and '"vue"' in package


def test_frontend_container_keeps_application_writable_for_non_root_user() -> None:
    dockerfile = read(ROOT / "frontend" / "Dockerfile")
    assert "AS build" in dockerfile
    assert "COPY --from=build --chown=node:node /app/dist ./dist" in dockerfile
    assert "USER node" in dockerfile


def test_frontend_container_pins_the_runtime_package_manager() -> None:
    dockerfile = read(ROOT / "frontend" / "Dockerfile")
    package = read(ROOT / "frontend" / "package.json")
    assert "ENV COREPACK_HOME=/opt/corepack" in dockerfile
    assert '"packageManager": "pnpm@11.7.0"' in package


def test_frontend_healthchecks_use_the_ipv4_listener() -> None:
    dockerfile = read(ROOT / "frontend" / "Dockerfile")
    compose = read(ROOT / "docker-compose.yml")
    assert "http://127.0.0.1:5173/healthz" in dockerfile
    assert "http://127.0.0.1:5173/" in compose
