from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main" / "java" / "com" / "example" / "dispute"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_temporal_workflow_owns_wait_signal_timeout_and_retry() -> None:
    workflow = text(
        JAVA
        / "workflow"
        / "temporal"
        / "CaseFulfillmentDisputeWorkflowImpl.java"
    )
    contract = text(
        JAVA
        / "workflow"
        / "temporal"
        / "CaseFulfillmentDisputeWorkflow.java"
    )
    assert "Workflow.await(" in workflow
    assert "RetryOptions.newBuilder()" in workflow
    assert "submitPartyEvidence" in contract
    assert "submitReviewerSignal" in contract
    assert "activities.planRemedy" in workflow


def test_hearing_controller_is_async_and_python_does_not_own_global_state() -> None:
    controller = text(
        JAVA / "workflow" / "api" / "WorkflowController.java"
    )
    application = text(
        JAVA
        / "workflow"
        / "application"
        / "WorkflowApplicationService.java"
    )
    activity = text(
        JAVA
        / "workflow"
        / "application"
        / "CaseFulfillmentDisputeActivitiesImpl.java"
    )
    assert "WorkflowClient.start(" in application
    assert ".getResult(" not in application
    assert ".getResult(" not in controller
    assert "transactions.execute(" in activity
    assert "agentClient.analyze(" in activity
    assert "@Transactional" not in activity


def test_hearing_state_records_drafts_and_submissions_are_real_entities() -> None:
    for name, table in {
        "HearingStateEntity.java": "hearing_state",
        "HearingRecordEntity.java": "hearing_stage_record",
        "AdjudicationDraftEntity.java": "adjudication_draft",
        "PartySubmissionEntity.java": "dispute_submission",
    }.items():
        entity = text(
            JAVA / "infrastructure" / "persistence" / "entity" / name
        )
        assert f'@Table(name = "{table}")' in entity
        assert "@Column" in entity


def test_worker_is_explicitly_enabled_only_in_compose() -> None:
    config = text(
        JAVA
        / "workflow"
        / "config"
        / "TemporalWorkerConfiguration.java"
    )
    compose = text(ROOT / "docker-compose.yml")
    local = text(
        ROOT
        / "java-api-service"
        / "src"
        / "main"
        / "resources"
        / "application-local.yml"
    )
    assert "@ConditionalOnProperty(" in config
    assert 'havingValue = "true"' in config
    assert 'APP_TEMPORAL_WORKER_ENABLED: "true"' in compose
    assert "APP_TEMPORAL_WORKER_ENABLED:false" in local


def test_phase9_public_api_and_service_auth_headers_exist() -> None:
    controller = text(
        JAVA / "workflow" / "api" / "WorkflowController.java"
    )
    client = text(
        JAVA
        / "workflow"
        / "infrastructure"
        / "RestClientHearingAgentClient.java"
    )
    for route in (
        "/workflow/start",
        "/hearing",
        "/submissions/user",
        "/submissions/merchant",
        "/workflow/reviewer-signal",
        "/adjudication-draft",
    ):
        assert route in controller
    assert "/agent-api/v1/hearings/analyze" in client
    assert '"X-Role", "SYSTEM"' in client
    assert "TraceIdFilter.TRACE_HEADER" in client
