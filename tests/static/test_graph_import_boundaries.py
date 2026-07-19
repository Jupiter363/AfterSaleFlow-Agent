from __future__ import annotations

import ast
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PYTHON_APP = ROOT / "python-agent-service/app"
GRAPH_RUNTIME = PYTHON_APP / "graph_runtime"
COMPOSE = ROOT / "docker-compose.yml"
POSTGRES_INIT = ROOT / "deploy/postgresql/init-multiple-databases.sh"
PYTHON_DOCKERFILE = ROOT / "python-agent-service/Dockerfile"

RAW_SAVER_OWNERS = {
    GRAPH_RUNTIME / "checkpoint.py",
    GRAPH_RUNTIME / "migrations.py",
}
STATE_KERNEL_FILES = {
    GRAPH_RUNTIME / "reducers.py",
    GRAPH_RUNTIME / "result.py",
    GRAPH_RUNTIME / "state.py",
    GRAPH_RUNTIME / "state_lens.py",
    GRAPH_RUNTIME / "topology.py",
}
MODEL_HTTP_MODULES = {
    "aiohttp",
    "httpx",
    "requests",
    "urllib.request",
}
STATE_KERNEL_FORBIDDEN_MODULES = {
    *MODEL_HTTP_MODULES,
    "app.llm",
    "app.model_runtime",
    "langchain_core.language_models",
    "langchain_core.messages",
    "langgraph.checkpoint",
    "psycopg",
    "psycopg_pool",
}


def _python_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*.py") if "__pycache__" not in path.parts)


def _imports(path: Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    imported: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module)
    return imported


def _matches_prefix(module: str, forbidden: set[str]) -> bool:
    return any(module == prefix or module.startswith(f"{prefix}.") for prefix in forbidden)


def test_production_code_has_no_in_memory_graph_recovery_path() -> None:
    violations: list[str] = []
    forbidden_symbols = {"MemorySaver", "InMemorySaver"}

    for path in _python_files(PYTHON_APP):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module:
                if node.module == "langgraph.checkpoint.memory":
                    violations.append(path.relative_to(ROOT).as_posix())
                if any(alias.name in forbidden_symbols for alias in node.names):
                    violations.append(path.relative_to(ROOT).as_posix())
            elif isinstance(node, ast.Name) and node.id in forbidden_symbols:
                violations.append(path.relative_to(ROOT).as_posix())

    assert not sorted(set(violations)), (
        "production Graph recovery must use the fenced PostgreSQL saver: "
        f"{sorted(set(violations))}"
    )


def test_raw_postgres_saver_is_confined_to_owned_adapters() -> None:
    violations: list[str] = []

    for path in _python_files(PYTHON_APP):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        uses_raw_saver = any(
            (
                isinstance(node, ast.ImportFrom)
                and node.module == "langgraph.checkpoint.postgres.aio"
                and any(alias.name == "AsyncPostgresSaver" for alias in node.names)
            )
            or (isinstance(node, ast.Name) and node.id == "AsyncPostgresSaver")
            for node in ast.walk(tree)
        )
        if uses_raw_saver and path not in RAW_SAVER_OWNERS:
            violations.append(path.relative_to(ROOT).as_posix())

    assert not violations, (
        "raw AsyncPostgresSaver must stay behind FencedPostgresSaver or the migration job: "
        f"{violations}"
    )


def test_state_kernel_cannot_import_clients_pools_or_model_runtime() -> None:
    violations: dict[str, list[str]] = {}

    for path in sorted(STATE_KERNEL_FILES):
        imported = sorted(
            module
            for module in _imports(path)
            if _matches_prefix(module, STATE_KERNEL_FORBIDDEN_MODULES)
        )
        if imported:
            violations[path.relative_to(ROOT).as_posix()] = imported

    assert not violations, (
        "Graph state, reducers, lens, topology, and result projection must remain "
        f"JSON-only deterministic code: {violations}"
    )


def test_graph_runtime_cannot_bypass_governed_model_transport() -> None:
    violations: dict[str, list[str]] = {}

    for path in _python_files(GRAPH_RUNTIME):
        imported = sorted(
            module
            for module in _imports(path)
            if _matches_prefix(module, MODEL_HTTP_MODULES)
        )
        if imported:
            violations[path.relative_to(ROOT).as_posix()] = imported

    assert not violations, (
        "Graph orchestration must call the governed model Runnable instead of issuing model HTTP: "
        f"{violations}"
    )


def test_compose_does_not_share_bootstrap_or_graph_credentials_with_services() -> None:
    compose = COMPOSE.read_text(encoding="utf-8")

    for required in (
        "POSTGRES_USER: ${JAVA_DB_USER:-dispute_app}",
        "POSTGRES_PASSWORD: ${JAVA_DB_PASSWORD}",
        "POSTGRES_USER: ${TEMPORAL_DB_USER:-temporal_app}",
        "POSTGRES_PWD: ${TEMPORAL_DB_PASSWORD}",
        "${LANGFUSE_DB_USER:-langfuse_app}:${LANGFUSE_DB_PASSWORD}",
        "${LITELLM_DB_USER:-litellm_app}:${LITELLM_DB_PASSWORD}",
        "GRAPH_GATEWAY_MODE: ${GRAPH_GATEWAY_MODE:-DISABLED}",
        "${GRAPH_RUNTIME_USER:-graph_runtime}:${GRAPH_RUNTIME_PASSWORD}",
    ):
        assert required in compose

    java_environment = compose.split("x-java-environment:", 1)[1].split(
        "x-java-core-depends-on:", 1
    )[0]
    assert "${POSTGRES_USER}" not in java_environment
    assert "GRAPH_RUNTIME_PASSWORD" not in java_environment
    assert "GRAPH_MIGRATOR_PASSWORD" not in java_environment


def test_graph_database_bootstrap_and_image_are_least_privilege_and_locked() -> None:
    bootstrap = POSTGRES_INIT.read_text(encoding="utf-8")
    dockerfile = PYTHON_DOCKERFILE.read_text(encoding="utf-8")

    for required in (
        "create_owner_role \"${GRAPH_OWNER_USER}\"",
        "create_login_role \"${GRAPH_MIGRATOR_USER}\"",
        "create_login_role \"${GRAPH_RUNTIME_USER}\"",
        "create_login_role \"${GRAPH_RETENTION_USER}\"",
        "grant_role \"${GRAPH_OWNER_USER}\" \"${GRAPH_MIGRATOR_USER}\"",
        "revoke all on database %I from public",
        "revoke all on schema public from public",
        "grant usage on schema %I to %I, %I",
    ):
        assert required in bootstrap

    assert "COPY requirements.txt requirements.lock ./" in dockerfile
    assert "--require-hashes --requirement requirements.lock" in dockerfile
    assert "python -m pip check" in dockerfile
    assert "COPY migrations ./migrations" in dockerfile
    assert "pip install --upgrade pip" not in dockerfile
