from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main" / "java" / "com" / "example" / "dispute"
PYTHON = ROOT / "python-agent-service" / "app"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_closure_requires_approved_successful_execution_and_is_audited() -> None:
    service = read(JAVA / "evaluation" / "application" / "CaseClosureService.java")
    entity = read(
        JAVA
        / "infrastructure"
        / "persistence"
        / "entity"
        / "FulfillmentCaseEntity.java"
    )
    for contract in (
        "CaseStatus.EXECUTING",
        "ExecutionStatus.SUCCEEDED",
        "latestApproval",
        "validateCompletedExecution",
        "CASE_CLOSED",
        "EVALUATION_STARTED",
    ):
        assert contract in service
    assert "caseStatus = CaseStatus.CLOSED" in entity
    assert "closedAt = OffsetDateTime.now" in entity


def test_evaluation_trace_persists_report_metrics_failure_and_provenance() -> None:
    entity = read(
        JAVA
        / "infrastructure"
        / "persistence"
        / "entity"
        / "EvaluationTraceEntity.java"
    )
    service = read(JAVA / "evaluation" / "application" / "CaseClosureService.java")
    for field in (
        "inputSnapshotJson",
        "metricScoresJson",
        "findingsJson",
        "reportJson",
        "evaluatorModel",
        "promptVersion",
        "latencyMs",
        "tokenUsage",
    ):
        assert field in entity
    assert "EVALUATION_COMPLETED" in service
    assert "EVALUATION_FAILED" in service
    assert "online_case_mutated" in service


def test_agent_evaluation_runs_outside_database_transactions() -> None:
    service = read(JAVA / "evaluation" / "application" / "CaseClosureService.java")
    assert "@Transactional(propagation = Propagation.NOT_SUPPORTED)" in service
    assert "evaluationAgent.analyze(" in service
    assert "transactions.executeWithoutResult(" in service


def test_evaluation_agent_is_closed_case_only_and_read_only() -> None:
    schemas = read(PYTHON / "schemas" / "models.py")
    workflow = read(PYTHON / "evaluation.py")
    prompt = read(PYTHON / "prompts" / "evaluation_analyze.md")
    main = read(PYTHON / "main.py")
    assert 'case_status: Literal["CLOSED"]' in schemas
    assert "automatic_changes_applied: Literal[False]" in schemas
    assert "online_case_mutated: Literal[False]" in schemas
    assert "draft_approval_rate=1.0" in workflow
    assert "reviewer_modification_rate=" in workflow
    assert "Never modify" in prompt
    assert "online case" in prompt
    assert '"/agent-api/v1/evaluations/analyze"' in main


def test_workflow_orders_execution_before_closure_and_evaluation() -> None:
    workflow = read(
        JAVA
        / "workflow"
        / "temporal"
        / "CaseFulfillmentDisputeWorkflowImpl.java"
    )
    execution = workflow.index("activities.executeApprovedPlan")
    closure = workflow.index("activities.closeCaseAndEvaluate")
    assert execution < closure
    assert "EVALUATION_COMPLETE" in workflow


def test_closure_and_evaluation_query_apis_are_exposed() -> None:
    controller = read(JAVA / "evaluation" / "api" / "ClosureController.java")
    assert '"/cases/{caseId}/close"' in controller
    assert '"/cases/{caseId}/evaluation"' in controller
    assert '"/evaluations/metrics"' in controller
    assert '"Idempotency-Key"' in controller
