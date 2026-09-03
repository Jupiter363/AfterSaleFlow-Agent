from __future__ import annotations

import ast
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
PYTHON_APP = ROOT / "apps/agent-runtime/app"
GRAPH_RUNTIME = PYTHON_APP / "graph_runtime"
COMPOSE = ROOT / "docker-compose.yml"
POSTGRES_INIT = ROOT / "infra/services/postgresql/init-multiple-databases.sh"
PYTHON_DOCKERFILE = ROOT / "apps/agent-runtime/Dockerfile"
TEMPORAL_DYNAMIC_CONFIG = ROOT / "infra/services/temporal/dynamicconfig/development-sql.yaml"

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
JAVA_INTAKE_EXCHANGE = GRAPH_RUNTIME / "intake_exchange.py"
JAVA_INTAKE_EXCHANGE_ENDPOINTS = {
    "INTAKE_PAYLOAD_LOAD_PATH": "/internal/graph/intake/v2/payload:load",
    "INTAKE_PROPOSAL_PUT_PATH": "/internal/graph/intake/v2/proposals:put",
}
JAVA_TARGET_E2E_ROOM_EXCHANGE = GRAPH_RUNTIME / "target_e2e_room_exchange.py"
JAVA_TARGET_E2E_ROOM_EXCHANGE_ENDPOINTS = {
    "TARGET_E2E_ROOM_OBJECT_LOAD_PATH": "/internal/graph/target-e2e/rooms/object:load",
    "TARGET_E2E_ROOM_PROPOSAL_PUT_PATH": "/internal/graph/target-e2e/rooms/proposal:put",
}
NON_MODEL_HTTP_EXCHANGES = {
    JAVA_INTAKE_EXCHANGE,
    JAVA_TARGET_E2E_ROOM_EXCHANGE,
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
        if imported and path not in NON_MODEL_HTTP_EXCHANGES:
            violations[path.relative_to(ROOT).as_posix()] = imported

    assert not violations, (
        "Graph orchestration must call the governed model Runnable instead of issuing model HTTP: "
        f"{violations}"
    )


def test_non_model_http_is_confined_to_fixed_java_intake_exchange_endpoints() -> None:
    tree = ast.parse(
        JAVA_INTAKE_EXCHANGE.read_text(encoding="utf-8"),
        filename=str(JAVA_INTAKE_EXCHANGE),
    )
    assert {
        module
        for module in _imports(JAVA_INTAKE_EXCHANGE)
        if _matches_prefix(module, MODEL_HTTP_MODULES)
    } == {"httpx"}
    assignments = {
        target.id: node.value.value
        for node in tree.body
        if isinstance(node, ast.Assign)
        and isinstance(node.value, ast.Constant)
        and isinstance(node.value.value, str)
        for target in node.targets
        if isinstance(target, ast.Name)
        and target.id in JAVA_INTAKE_EXCHANGE_ENDPOINTS
    }
    assert assignments == JAVA_INTAKE_EXCHANGE_ENDPOINTS

    exchange_calls = [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "self"
        and node.func.attr == "_post"
    ]
    assert {
        node.args[0].id
        for node in exchange_calls
        if node.args and isinstance(node.args[0], ast.Name)
    } == set(JAVA_INTAKE_EXCHANGE_ENDPOINTS)
    assert len(exchange_calls) == len(JAVA_INTAKE_EXCHANGE_ENDPOINTS)

    assert [
        node.func.attr
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "httpx"
    ] == ["AsyncClient"]
    http_client_calls = [
        node.func.attr
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "client"
    ]
    assert sorted(http_client_calls) == ["aclose", "stream"]


def test_target_e2e_non_model_http_is_confined_to_fixed_java_exchange_endpoints() -> None:
    tree = ast.parse(
        JAVA_TARGET_E2E_ROOM_EXCHANGE.read_text(encoding="utf-8"),
        filename=str(JAVA_TARGET_E2E_ROOM_EXCHANGE),
    )
    assert {
        module
        for module in _imports(JAVA_TARGET_E2E_ROOM_EXCHANGE)
        if _matches_prefix(module, MODEL_HTTP_MODULES)
    } == {"httpx"}
    assignments = {
        target.id: node.value.value
        for node in tree.body
        if isinstance(node, ast.Assign)
        and isinstance(node.value, ast.Constant)
        and isinstance(node.value.value, str)
        for target in node.targets
        if isinstance(target, ast.Name)
        and target.id in JAVA_TARGET_E2E_ROOM_EXCHANGE_ENDPOINTS
    }
    assert assignments == JAVA_TARGET_E2E_ROOM_EXCHANGE_ENDPOINTS

    allowed_assignment = next(
        node
        for node in tree.body
        if isinstance(node, ast.Assign)
        and any(
            isinstance(target, ast.Name) and target.id == "_ALLOWED_EXCHANGE_PATHS"
            for target in node.targets
        )
    )
    assert isinstance(allowed_assignment.value, ast.Call)
    assert isinstance(allowed_assignment.value.func, ast.Name)
    assert allowed_assignment.value.func.id == "frozenset"
    assert len(allowed_assignment.value.args) == 1
    allowed_paths = allowed_assignment.value.args[0]
    assert isinstance(allowed_paths, ast.Set)
    assert {
        element.id
        for element in allowed_paths.elts
        if isinstance(element, ast.Name)
    } == set(JAVA_TARGET_E2E_ROOM_EXCHANGE_ENDPOINTS)

    post_function = next(
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.AsyncFunctionDef) and node.name == "_post"
    )
    assert any(
        isinstance(node, ast.Compare)
        and isinstance(node.left, ast.Name)
        and node.left.id == "path"
        and len(node.ops) == 1
        and isinstance(node.ops[0], ast.NotIn)
        and len(node.comparators) == 1
        and isinstance(node.comparators[0], ast.Name)
        and node.comparators[0].id == "_ALLOWED_EXCHANGE_PATHS"
        for node in ast.walk(post_function)
    )

    exchange_calls = [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and isinstance(node.func.value, ast.Attribute)
        and isinstance(node.func.value.value, ast.Name)
        and node.func.value.value.id == "self"
        and node.func.value.attr == "_exchange"
        and node.func.attr == "_post"
    ]
    assert {
        node.args[0].id
        for node in exchange_calls
        if node.args and isinstance(node.args[0], ast.Name)
    } == set(JAVA_TARGET_E2E_ROOM_EXCHANGE_ENDPOINTS)
    assert len(exchange_calls) == len(JAVA_TARGET_E2E_ROOM_EXCHANGE_ENDPOINTS)

    assert [
        node.func.attr
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "httpx"
    ] == ["AsyncClient"]
    http_client_calls = [
        node.func.attr
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "client"
    ]
    assert sorted(http_client_calls) == ["aclose", "post"]


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


def test_python_agent_mounts_authoritative_contracts_read_only() -> None:
    compose = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))

    assert "./contracts/agent-platform/v1:/contracts/agent-platform/v1:ro" in compose[
        "services"
    ]["python-agent-service"]["volumes"]


def test_temporal_setup_does_not_create_bootstrapped_databases() -> None:
    compose = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))

    assert compose["services"]["temporal-server"]["environment"][
        "SKIP_DB_CREATE"
    ] == "true"


def test_temporal_server_enables_versioning_for_the_control_worker() -> None:
    compose = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
    services = compose["services"]
    temporal = services["temporal-server"]
    control_worker = services["java-control-worker"]
    dynamic_config = yaml.safe_load(TEMPORAL_DYNAMIC_CONFIG.read_text(encoding="utf-8"))

    assert (
        "./infra/services/temporal/dynamicconfig:/etc/temporal/config/dynamicconfig:ro"
        in temporal["volumes"]
    )
    assert temporal["environment"]["DYNAMIC_CONFIG_FILE_PATH"] == (
        "config/dynamicconfig/development-sql.yaml"
    )
    for setting in (
        "frontend.workerVersioningDataAPIs",
        "frontend.workerVersioningWorkflowAPIs",
        "frontend.enableExecuteMultiOperation",
    ):
        assert dynamic_config[setting] == [{"value": True, "constraints": {}}]
    assert control_worker["environment"]["TEMPORAL_WORKER_VERSIONING_MODE"] == (
        "${TEMPORAL_CONTROL_WORKER_VERSIONING_MODE:-BUILD_ID}"
    )


def test_demo_dispute_seeding_is_scoped_to_the_api_process() -> None:
    compose = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
    services = compose["services"]

    assert services["java-api-service"]["environment"]["SEED_DEMO_DISPUTES"] == (
        "${SEED_DEMO_DISPUTES:-false}"
    )
    assert services["java-control-worker"]["environment"]["SEED_DEMO_DISPUTES"] == (
        "false"
    )
    assert services["java-agent-worker"]["environment"]["SEED_DEMO_DISPUTES"] == (
        "false"
    )


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
        'create_isolated_database "${GRAPH_DB_NAME}" "${POSTGRES_USER}"',
        "revoke temporary on database %I from public, %I, %I, %I, %I",
        "set search_path to %I, pg_catalog, pg_temp",
    ):
        assert required in bootstrap

    assert 'create_isolated_database "${GRAPH_DB_NAME}" "${GRAPH_OWNER_USER}"' not in bootstrap

    assert "COPY requirements.txt requirements.lock ./" in dockerfile
    assert "--require-hashes --requirement requirements.lock" in dockerfile
    assert "python -m pip check" in dockerfile
    assert "COPY migrations ./migrations" in dockerfile
    assert "pip install --upgrade pip" not in dockerfile
