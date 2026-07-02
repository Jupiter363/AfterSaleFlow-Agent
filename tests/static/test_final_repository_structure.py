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
PYTHON = ROOT / "python-agent-service" / "app"
FRONTEND = ROOT / "frontend" / "src"


def test_final_java_bounded_contexts_are_explicit() -> None:
    expected = {
        "casecore",
        "intake",
        "evidence",
        "routing",
        "hearing",
        "deliberation",
        "remedy",
        "review",
        "execution",
        "evaluation",
        "workflow",
        "platform",
    }

    missing = sorted(name for name in expected if not (JAVA / name).is_dir())

    assert missing == []


def test_agent_service_has_governed_runtime_structure() -> None:
    expected = {"api", "agents", "harness", "prompts", "schemas", "skills"}

    missing = sorted(name for name in expected if not (PYTHON / name).is_dir())

    assert missing == []


def test_frontend_is_partitioned_by_final_workspace_responsibility() -> None:
    expected = {
        "api",
        "components/agent",
        "components/evidence",
        "components/hearing",
        "components/review",
        "components/shared",
        "router",
        "schemas",
        "stores",
        "views/disputes",
        "views/reviews",
    }

    missing = sorted(name for name in expected if not (FRONTEND / name).is_dir())

    assert missing == []


def test_repository_documentation_names_the_final_product_and_boundaries() -> None:
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    module_map = (
        ROOT / "docs" / "architecture" / "final-module-map.md"
    ).read_text(encoding="utf-8")

    assert readme.startswith("# AI Native 履约争端审理系统")
    assert "Agent Runtime Harness" in readme
    assert "Platform Human Review" in readme
    assert "Tool Executor" in readme
    assert "业务事实" in module_map
    assert "禁止依赖" in module_map
