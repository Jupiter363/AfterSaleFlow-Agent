from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


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


def test_host_ports_are_local_only_and_every_runtime_is_health_checked() -> None:
    compose = read(ROOT / "docker-compose.yml")
    assert compose.count('127.0.0.1:${') >= 13
    assert compose.count("healthcheck:") >= 14
    assert '127.0.0.1:${NGINX_PORT:-8080}:80' in compose


def test_frontend_container_serves_a_production_build_as_non_root() -> None:
    dockerfile = read(ROOT / "frontend" / "Dockerfile")
    server = read(ROOT / "frontend" / "server.mjs")
    assert "pnpm run build" in dockerfile
    assert 'CMD ["node", "server.mjs"]' in dockerfile
    assert "pnpm run dev" not in dockerfile
    assert "USER node" in dockerfile
    assert "Content-Security-Policy" in server
    assert 'url.pathname === "/healthz"' in server


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


def test_deployment_document_covers_start_stop_security_and_troubleshooting() -> None:
    deployment = read(ROOT / "docs" / "deployment" / "README.md")
    for contract in (
        "./scripts/dev-up.sh",
        "./scripts/dev-down.sh",
        "./scripts/smoke-test.sh",
        "127.0.0.1",
        "镜像覆盖",
        "故障排查",
        "DEEPSEEK_API_KEY",
    ):
        assert contract in deployment
