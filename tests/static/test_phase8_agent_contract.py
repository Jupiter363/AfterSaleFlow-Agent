from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "python-agent-service"
APP = SERVICE / "app"


def test_python_agent_dependencies_and_container_are_formal() -> None:
    requirements = (SERVICE / "requirements.txt").read_text(encoding="utf-8")
    assert "langgraph==1.2.6" in requirements
    assert "langfuse==4.11.0" in requirements
    assert "fastapi==0.138.0" in requirements

    dockerfile = (SERVICE / "Dockerfile").read_text(encoding="utf-8")
    assert "USER app" in dockerfile
    assert "HEALTHCHECK" in dockerfile
    assert "app.main:create_app" in dockerfile


def test_all_c1_to_c6_nodes_and_conditional_gap_path_exist() -> None:
    graph = (APP / "graph.py").read_text(encoding="utf-8")
    for node in (
        "issue_framing_node",
        "evidence_gap_request_node",
        "party_liaison_node",
        "evidence_cross_check_node",
        "rule_application_node",
        "adjudication_draft_node",
    ):
        assert node in graph
        assert (APP / "prompts" / f"{node}.md").is_file()
    assert "add_conditional_edges" in graph
    assert "requires_supplemental_evidence" in graph


def test_agent_output_is_non_final_and_forced_to_human_review() -> None:
    schemas = (APP / "schemas" / "models.py").read_text(encoding="utf-8")
    assert 'requires_human_review: Literal[True] = True' in schemas
    assert 'is_final_decision: Literal[False] = False' in schemas
    assert 'draft_status: Literal["PENDING_HUMAN_REVIEW"]' in schemas

    workflow = (APP / "workflow.py").read_text(encoding="utf-8")
    assert "MANUAL_REVIEW_REQUIRED" in workflow
    assert "AGENT_OUTPUT_SCHEMA_INVALID" in workflow


def test_agent_has_no_execution_tool_and_masks_trace_inputs() -> None:
    production_python = "\n".join(
        path.read_text(encoding="utf-8")
        for path in APP.glob("*.py")
    )
    assert "ToolExecutor" not in production_python
    assert "refund.create" not in production_python
    assert "close_case" not in production_python
    assert "redacted_trace_input" in production_python
    assert "sha256" in (APP / "tracing.py").read_text(encoding="utf-8")


def test_agent_api_and_langfuse_headless_keys_are_wired() -> None:
    main = (APP / "main.py").read_text(encoding="utf-8")
    assert "/agent-api/v1/intake/analyze" in main
    assert "/agent-api/v1/hearings/analyze" in main
    assert "X-Service-Secret" in main

    compose = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
    assert "LANGFUSE_INIT_PROJECT_PUBLIC_KEY: ${LANGFUSE_PUBLIC_KEY}" in compose
    assert "LANGFUSE_INIT_PROJECT_SECRET_KEY: ${LANGFUSE_SECRET_KEY}" in compose
    assert "PROMPT_VERSION: hearing-v1" in compose
