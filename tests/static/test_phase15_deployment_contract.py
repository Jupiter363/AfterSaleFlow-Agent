# 文件作用：自动化测试文件，验证 test_phase15_deployment_contract 相关模块的行为、契约或页面布局。

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


# 所属模块：跨服务契约测试 > test_phase15_deployment_contract；函数角色：模块公开业务函数。
# 具体功能：`read` 读取并按案件、角色或会话范围筛选被测业务场景；关键协作调用：`path.read_text`。
# 上下游：上游为 本文件的 `test_compose_contains_the_complete_pinned_service_topology`、`test_host_ports_are_local_only_and_every_runtime_is_health_checked`、`test_frontend_container_serves_a_production_build_as_non_root`、`test_smoke_test_checks_health_and_lists_disputes_through_nginx`；下游为 协作调用 `path.read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# 所属模块：跨服务契约测试 > test_phase15_deployment_contract；函数角色：回归测试用例。
# 具体功能：`test_compose_contains_the_complete_pinned_service_topology` 把上游材料组装为本阶段可消费的被测业务场景。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase15_deployment_contract”的可观察契约，防止后续重构改变业务结果。
def test_compose_contains_the_complete_pinned_service_topology() -> None:
    compose = read(ROOT / "docker-compose.yml")
    for service in (
        "frontend:",
        "java-api-service:",
        "temporal-server:",
        "python-agent-service:",
        "litellm-proxy:",
        "langfuse:",
        "postgresql:",
        "redis:",
        "elasticsearch:",
        "minio:",
        "ocr-parser-service:",
        "nginx:",
    ):
        assert service in compose
    assert ":latest" not in compose
    assert "TEMPORAL_IMAGE:-temporalio/auto-setup:1.25.2" in compose
    assert "LANGFUSE_IMAGE:-ghcr.io/langfuse/langfuse:2.95.11" in compose
    assert (
        "LITELLM_IMAGE:-ghcr.io/berriai/litellm:main-v1.63.14-stable"
        in compose
    )


# 所属模块：跨服务契约测试 > test_phase15_deployment_contract；函数角色：回归测试用例。
# 具体功能：`test_host_ports_are_local_only_and_every_runtime_is_health_checked` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`compose.count`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase15_deployment_contract”的可观察契约，防止后续重构改变业务结果。
def test_host_ports_are_local_only_and_every_runtime_is_health_checked() -> None:
    compose = read(ROOT / "docker-compose.yml")
    assert compose.count('127.0.0.1:${') >= 13
    assert compose.count("healthcheck:") >= 14
    assert '127.0.0.1:${NGINX_PORT:-18080}:80' in compose


# 所属模块：跨服务契约测试 > test_phase15_deployment_contract；函数角色：回归测试用例。
# 具体功能：`test_frontend_container_serves_a_production_build_as_non_root` 把上游材料组装为本阶段可消费的被测业务场景。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase15_deployment_contract”的可观察契约，防止后续重构改变业务结果。
def test_frontend_container_serves_a_production_build_as_non_root() -> None:
    dockerfile = read(ROOT / "frontend" / "Dockerfile")
    server = read(ROOT / "frontend" / "server.mjs")
    assert "pnpm run build" in dockerfile
    assert 'CMD ["node", "server.mjs"]' in dockerfile
    assert "pnpm run dev" not in dockerfile
    assert "USER node" in dockerfile
    assert "Content-Security-Policy" in server
    assert 'url.pathname === "/healthz"' in server


# 所属模块：跨服务契约测试 > test_phase15_deployment_contract；函数角色：回归测试用例。
# 具体功能：`test_smoke_test_checks_health_and_lists_disputes_through_nginx` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase15_deployment_contract”的可观察契约，防止后续重构改变业务结果。
def test_smoke_test_checks_health_and_lists_disputes_through_nginx() -> None:
    smoke = read(ROOT / "scripts" / "smoke-test.sh")
    dev_up = read(ROOT / "scripts" / "dev-up.sh")
    dev_reset = read(ROOT / "scripts" / "dev-reset.sh")
    assert "docker compose config --services" in smoke
    assert "/api/disputes" in smoke
    assert "dispute-list-through-nginx" in smoke
    assert "--wait" in dev_up
    assert "./scripts/smoke-test.sh" in dev_up
    assert "./scripts/dev-up.sh" in dev_reset


# 所属模块：跨服务契约测试 > test_phase15_deployment_contract；函数角色：回归测试用例。
# 具体功能：`test_manual_migration_script_uses_the_runtime_jar_and_entrypoint` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase15_deployment_contract”的可观察契约，防止后续重构改变业务结果。
def test_manual_migration_script_uses_the_runtime_jar_and_entrypoint() -> None:
    init_db = read(ROOT / "scripts" / "init-db.sh")
    migration_main = read(
        ROOT
        / "java-api-service"
        / "src"
        / "main"
        / "java"
        / "com"
        / "example"
        / "dispute"
        / "database"
        / "FlywayMigrationMain.java"
    )
    assert "MSYS_NO_PATHCONV=1" in init_db
    assert "run --rm --entrypoint java" in init_db
    assert "java-api-service" in init_db
    assert "-cp /home/app/app.jar" in init_db
    assert "FlywayMigrationMain" in init_db
    assert "PropertiesLauncher" in init_db
    assert 'requiredEnvironment("POSTGRES_HOST")' in migration_main
    assert 'requiredEnvironment("JAVA_DB_NAME")' in migration_main
    assert 'locations("classpath:db/migration")' in migration_main


# 所属模块：跨服务契约测试 > test_phase15_deployment_contract；函数角色：回归测试用例。
# 具体功能：`test_deployment_document_covers_start_stop_security_and_troubleshooting` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase15_deployment_contract”的可观察契约，防止后续重构改变业务结果。
def test_deployment_document_covers_start_stop_security_and_troubleshooting() -> None:
    deployment = read(ROOT / "docs" / "deployment" / "README.md")
    for contract in (
        "./scripts/dev-up.sh",
        "./scripts/dev-down.sh",
        "./scripts/smoke-test.sh",
        "127.0.0.1",
        "镜像覆盖",
        "故障排查",
        "DASHSCOPE_API_KEY",
    ):
        assert contract in deployment
