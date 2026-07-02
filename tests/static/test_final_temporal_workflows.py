from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = (
    ROOT
    / "java-api-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "example"
    / "dispute"
)
TEMPORAL = JAVA / "workflow" / "temporal"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_all_five_final_workflow_types_and_implementations_exist() -> None:
    names = (
        "FulfillmentDisputeWorkflow",
        "DisputeHearingWorkflow",
        "DeliberationPanelWorkflow",
        "HumanReviewWorkflow",
        "ExecutionWorkflow",
    )
    for name in names:
        assert (TEMPORAL / f"{name}.java").is_file()
        assert (TEMPORAL / f"{name}Impl.java").is_file()
        assert "@WorkflowInterface" in read(TEMPORAL / f"{name}.java")


def test_new_disputes_start_final_type_and_worker_keeps_legacy_for_replay() -> None:
    application = read(
        JAVA / "workflow" / "application" / "WorkflowApplicationService.java"
    )
    worker = read(
        JAVA / "workflow" / "config" / "TemporalWorkerConfiguration.java"
    )
    assert "FulfillmentDisputeWorkflow.class" in application
    assert "WorkflowExecutionAlreadyStarted" in application
    assert "new FulfillmentDisputeCommand(" in application
    for implementation in (
        "FulfillmentDisputeWorkflowImpl.class",
        "DisputeHearingWorkflowImpl.class",
        "DeliberationPanelWorkflowImpl.class",
        "HumanReviewWorkflowImpl.class",
        "ExecutionWorkflowImpl.class",
    ):
        assert implementation in worker
    assert "CaseFulfillmentDisputeWorkflowImpl.class" in worker


def test_workflow_code_only_owns_deterministic_control() -> None:
    implementations = "\n".join(
        read(path) for path in TEMPORAL.glob("*WorkflowImpl.java")
    )
    for forbidden in (
        "RestClient",
        "HttpClient",
        "EntityManager",
        "Repository",
        "JdbcTemplate",
        "LiteLLM",
    ):
        assert forbidden not in implementations
    assert "Workflow.newActivityStub" in implementations
    assert "Workflow.newChildWorkflowStub" in implementations
