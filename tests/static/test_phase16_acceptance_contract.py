from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_phase16_runtime_test_suites_exist() -> None:
    for relative in (
        "tests/api/test_api_contracts.py",
        "tests/e2e/test_main_flows.py",
        "tests/load/test_performance_budget.py",
        "tests/fixtures/case_payloads.json",
    ):
        assert (ROOT / relative).exists(), relative


def test_phase16_ci_quality_gate_exists() -> None:
    workflow = ROOT / ".github/workflows/quality-gate.yml"
    assert workflow.exists()
    text = workflow.read_text(encoding="utf-8")
    for required in (
        "mvn",
        "pytest",
        "pnpm",
        "docker compose config",
        "scripts/smoke-test.sh",
        "secret",
    ):
        assert required in text


def test_phase16_release_review_and_rollback_docs_exist() -> None:
    contributing = read("CONTRIBUTING.md")
    deployment = read("docs/deployment/README.md")
    codex = read("docs/codex/README.md")
    release = read("docs/release/README.md")
    for required in (
        "Code Review",
        "rollback",
        "release",
        "smoke-test",
    ):
        combined = "\n".join([contributing, deployment, codex, release])
        assert required in combined
