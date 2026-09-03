# 文件作用：自动化测试文件，验证 test_repository_contract 相关模块的行为、契约或页面布局。

from __future__ import annotations

import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest
import yaml


ROOT = Path(__file__).resolve().parents[2]

REQUIRED_ROOT_FILES = {
    "README.md",
    ".gitignore",
    ".gitattributes",
    ".editorconfig",
    ".env.example",
    "docker-compose.yml",
}

REQUIRED_DOCUMENT_FILES = {
    "README.md",
    "architecture/README.md",
    "architecture/temporal-first-agent-platform.md",
    "architecture/intake-room-context-and-streaming.md",
    "architecture/evidence-room-context-binding-and-token-streaming.md",
    "architecture/adr/0018-production-contract-baseline-v1.md",
    "acceptance/temporal-first-agent-platform-verification-checklist.md",
    "acceptance/canonical-full-chain-uat-fixture.md",
    "contracts/README.md",
    "release/current-uat-baseline.md",
    "development/contributing.md",
    "development/code-style.md",
    "security/security-policy.md",
    "frontend/README.md",
    "deployment/README.md",
    "deployment/temporal.md",
    "release/README.md",
    "runbooks/README.md",
    "testing/temporal-history-fixtures.md",
}

REQUIRED_DIRECTORIES = {
    "docs",
    "deploy",
    "scripts",
    "frontend",
    "java-api-service",
    "python-agent-service",
    "ocr-parser-service",
    "tests",
    "infra-tests",
}

REQUIRED_SERVICES = {
    "postgresql",
    "redis",
    "elasticsearch",
    "minio",
    "minio-init",
    "temporal-server",
    "langfuse",
    "litellm-proxy",
    "java-api-service",
    "python-agent-service",
    "ocr-parser-service",
    "frontend",
    "nginx",
}

REQUIRED_SCRIPTS = {
    "generate-secrets.sh",
    "dev-up.sh",
    "dev-down.sh",
    "dev-reset.sh",
    "init-db.sh",
    "init-minio.sh",
    "init-es.sh",
    "smoke-test.sh",
    "generate-openapi.sh",
}

REQUIRED_ENV_KEYS = {
    "COMPOSE_PROJECT_NAME",
    "APP_ENV",
    "TZ",
    "DASHSCOPE_API_KEY",
    "DEFAULT_LLM_PROVIDER",
    "DEFAULT_LLM_MODEL",
    "DEFAULT_LLM_API_BASE",
    "LLM_ENABLE_THINKING",
    "LITELLM_MASTER_KEY",
    "LITELLM_SALT_KEY",
    "LITELLM_BASE_URL",
    "LITELLM_DEFAULT_MODEL",
    "POSTGRES_HOST",
    "POSTGRES_PORT",
    "POSTGRES_DB",
    "POSTGRES_USER",
    "POSTGRES_PASSWORD",
    "JAVA_DB_NAME",
    "TEMPORAL_DB_NAME",
    "LANGFUSE_DB_NAME",
    "LITELLM_DB_NAME",
    "REDIS_HOST",
    "REDIS_PORT",
    "REDIS_PASSWORD",
    "MINIO_ENDPOINT",
    "MINIO_ROOT_USER",
    "MINIO_ROOT_PASSWORD",
    "ELASTICSEARCH_URL",
    "TEMPORAL_ADDRESS",
    "TEMPORAL_TASK_QUEUE",
    "LANGFUSE_HOST",
    "LANGFUSE_PUBLIC_KEY",
    "LANGFUSE_SECRET_KEY",
    "JAVA_SERVICE_SECRET",
    "PYTHON_AGENT_SERVICE_SECRET",
    "OCR_SERVICE_SECRET",
    "VITE_API_BASE_URL",
    "FEATURE_HUMAN_REVIEW_REQUIRED",
    "FEATURE_TOOL_EXECUTOR_SIMULATION",
    "ENABLE_AUDIT_LOG",
    "ENABLE_SENSITIVE_LOG_MASKING",
    "EVIDENCE_WINDOW",
    "HEARING_WINDOW",
    "HEARING_PARTY_STAGE_WINDOW",
    "SSE_HEARTBEAT",
    "SEED_DEMO_DISPUTES",
}

GENERATED_SECRET_KEYS = {
    "LITELLM_MASTER_KEY",
    "LITELLM_SALT_KEY",
    "POSTGRES_USER",
    "POSTGRES_PASSWORD",
    "REDIS_PASSWORD",
    "MINIO_ROOT_USER",
    "MINIO_ROOT_PASSWORD",
    "ELASTICSEARCH_PASSWORD",
    "LANGFUSE_PUBLIC_KEY",
    "LANGFUSE_SECRET_KEY",
    "LANGFUSE_SALT",
    "LANGFUSE_NEXTAUTH_SECRET",
    "JAVA_SERVICE_SECRET",
    "PYTHON_AGENT_SERVICE_SECRET",
    "OCR_SERVICE_SECRET",
}


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：模块公开业务函数。
# 具体功能：`parse_env_example` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`splitlines`、`raw_line.strip`、`line.split`。
# 上下游：上游为 本文件的 `test_env_example_is_complete_and_contains_only_placeholders`；下游为 协作调用 `splitlines`、`raw_line.strip`、`line.split`、`read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def parse_env_example() -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in (ROOT / ".env.example").read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        values[key] = value
    return values


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_repository_contains_required_root_files_and_directories` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`is_file`、`is_dir`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `is_file`、`is_dir`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_repository_contains_required_root_files_and_directories() -> None:
    missing_files = sorted(name for name in REQUIRED_ROOT_FILES if not (ROOT / name).is_file())
    missing_documents = sorted(
        name for name in REQUIRED_DOCUMENT_FILES if not (ROOT / "docs" / name).is_file()
    )
    missing_directories = sorted(
        name for name in REQUIRED_DIRECTORIES if not (ROOT / name).is_dir()
    )

    assert not missing_files, f"missing root files: {missing_files}"
    assert not missing_documents, f"missing production documents: {missing_documents}"
    assert not missing_directories, f"missing directories: {missing_directories}"


def test_current_architecture_documents_match_the_uat_release_identity() -> None:
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    intake = (ROOT / "docs/architecture/intake-room-context-and-streaming.md").read_text(
        encoding="utf-8"
    )
    evidence = (
        ROOT / "docs/architecture/evidence-room-context-binding-and-token-streaming.md"
    ).read_text(encoding="utf-8")
    platform = (
        ROOT / "docs/architecture/temporal-first-agent-platform.md"
    ).read_text(encoding="utf-8")
    acceptance = (
        ROOT / "docs/acceptance/temporal-first-agent-platform-verification-checklist.md"
    ).read_text(encoding="utf-8")

    for document in (readme, intake, evidence, platform, acceptance):
        assert "target-e2e-graph.2026-08-18.3" in document
        assert "qwen3.8-flash" in document

    for document in (readme, intake, platform, acceptance):
        assert "PARALLEL_FRAMES_V1" in document
        assert "agent-stream.v4" in document

    for document in (readme, evidence, acceptance):
        assert "evidence-turn-result.v3" in document

    assert "G017" in intake
    assert "V094" in intake


def test_production_contract_baseline_v1_catalog_is_exact_and_replay_safe() -> None:
    catalog = json.loads(
        (ROOT / "contracts/agent-platform/production-baseline.v1.json").read_text(
            encoding="utf-8"
        )
    )

    assert catalog["schema_version"] == "production-contract-baseline.v1"
    assert catalog["status"] == "CURRENT"
    assert catalog["source"]["branch"] == "main"
    assert catalog["source"]["runtime_commit"] == (
        "10526e58b954498f69bae00ea709f6f9e4981971"
    )
    assert catalog["versioning_policy"] == {
        "baseline_is_release_catalog": True,
        "wire_contract_versions_are_immutable": True,
        "versionless_wire_contracts_allowed": False,
        "renumber_existing_wire_contracts": False,
    }
    assert catalog["graph"] == {
        "key": "all-rooms.target-e2e.v2",
        "version": "target-e2e-graph.2026-08-18.3",
        "checkpoint_schema": "target-e2e-checkpoint.v2",
        "command_schema": "room-graph-command.v1",
        "result_schema": "room-graph-result.v1",
    }
    assert catalog["intake"]["execution_profile"] == "PARALLEL_FRAMES_V1"
    assert catalog["intake"]["public_stream"] == "agent-stream.v4"
    assert catalog["evidence"] == {
        "context_contract": "evidence_room_context.v2",
        "frame_stream": "evidence_turn_stream.v3",
        "result_contract": "evidence-turn-result.v3",
        "public_stream": "agent-stream.v3",
    }
    assert catalog["hearing"]["flow_contract"] == "hearing_flow.v2"
    assert catalog["hearing"]["answer_bundle"] == "hearing_answer_bundle.v4"
    assert catalog["outcome"]["contract_family"] == "outcome.v1"
    assert catalog["model"] == {
        "id": "qwen3.8-flash",
        "thinking_enabled": False,
        "strict_json_schema": True,
    }
    assert catalog["persistence"] == {
        "domain_migration_ceiling": "V094",
        "graph_migration_ceiling": "G017",
    }

    current_sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (
            ROOT
            / "java-api-service/src/main/java/com/example/dispute/workflow/targete2e/temporal/TargetTypedRoomProtocol.java",
            ROOT
            / "java-api-service/src/main/java/com/example/dispute/workflow/targete2e/graph/TargetE2EIntakeParallelAssemblyCoordinator.java",
            ROOT / "python-agent-service/app/config.py",
            ROOT / "frontend/src/api/agentStream.js",
            ROOT / "python-agent-service/app/agents/dispute_intake_officer/workflow.py",
            ROOT / "python-agent-service/app/agents/evidence_clerk/v2_contracts.py",
            ROOT / "python-agent-service/app/schemas/hearing_flow.py",
            ROOT / "contracts/agent-platform/v1/room-graph-command.schema.json",
            ROOT / "contracts/agent-platform/v1/room-graph-result.schema.json",
            ROOT
            / "contracts/agent-platform/outcome/v1/outcome-workflow-start.schema.json",
        )
    )
    for identity in (
        catalog["graph"]["key"],
        catalog["graph"]["version"],
        catalog["graph"]["checkpoint_schema"],
        catalog["graph"]["command_schema"],
        catalog["graph"]["result_schema"],
        catalog["intake"]["execution_profile"],
        catalog["intake"]["context_contract"],
        catalog["intake"]["public_stream"],
        catalog["evidence"]["frame_stream"],
        catalog["evidence"]["result_contract"],
        catalog["hearing"]["flow_contract"],
        catalog["hearing"]["answer_bundle"],
        catalog["outcome"]["workflow_start"],
        catalog["model"]["id"],
    ):
        assert identity in current_sources

    assert (
        ROOT
        / "java-api-service/src/main/resources/db/migration/V094__target_e2e_graph_patch_release_identity.sql"
    ).is_file()
    assert (
        ROOT
        / "python-agent-service/migrations/graph/G017_fanout_command_terminalization_authority.sql"
    ).is_file()


def test_java_maven_settings_use_the_official_central_repository() -> None:
    settings = ET.parse(ROOT / "java-api-service" / ".mvn" / "settings.xml")
    namespace = {"m": "http://maven.apache.org/SETTINGS/1.2.0"}
    mirrors = settings.findall("m:mirrors/m:mirror", namespace)

    assert len(mirrors) == 1
    mirror = mirrors[0]
    assert mirror.findtext("m:id", namespaces=namespace) == "maven-central"
    assert mirror.findtext("m:url", namespaces=namespace) == (
        "https://repo.maven.apache.org/maven2/"
    )
    assert mirror.findtext("m:mirrorOf", namespaces=namespace) == "central"


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_env_example_is_complete_and_contains_only_placeholders` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`values.keys`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `parse_env_example`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_env_example_is_complete_and_contains_only_placeholders() -> None:
    values = parse_env_example()

    assert REQUIRED_ENV_KEYS <= values.keys()
    assert values["DASHSCOPE_API_KEY"] == "__PASTE_YOUR_DASHSCOPE_API_KEY_HERE__"
    assert values["DEFAULT_LLM_PROVIDER"] == "litellm"
    assert values["DEFAULT_LLM_MODEL"] == "qwen3.8-flash"
    assert values["LITELLM_DEFAULT_MODEL"] == "qwen3.8-flash"
    assert values["LLM_ENABLE_THINKING"] == "false"
    assert values["LLM_STRICT_JSON_SCHEMA_ENABLED"] == "true"
    for key in GENERATED_SECRET_KEYS:
        assert values[key] == "__GENERATED_BY_CODEX__", key


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_compose_declares_every_required_service_with_healthchecks` 把上游材料组装为本阶段可消费的被测业务场景；关键协作调用：`yaml.safe_load`、`read_text`、`services.keys`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `yaml.safe_load`、`read_text`、`services.keys`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_compose_declares_every_required_service_with_healthchecks() -> None:
    compose = yaml.safe_load((ROOT / "docker-compose.yml").read_text(encoding="utf-8"))
    services = compose["services"]

    assert REQUIRED_SERVICES <= services.keys()
    assert all("healthcheck" in services[name] for name in REQUIRED_SERVICES)


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_qwen_credentials_stop_at_litellm_gateway` 验证结构化模型调用在固定案例中的输出、边界和失败行为；关键协作调用：`yaml.safe_load`、`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `yaml.safe_load`、`read_text`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_qwen_credentials_stop_at_litellm_gateway() -> None:
    compose = yaml.safe_load((ROOT / "docker-compose.yml").read_text(encoding="utf-8"))
    services = compose["services"]
    proxy_environment = services["litellm-proxy"]["environment"]
    agent_environment = services["python-agent-service"]["environment"]
    litellm_config = yaml.safe_load(
        (ROOT / "deploy" / "litellm" / "config.yaml").read_text(encoding="utf-8")
    )

    assert "DASHSCOPE_API_KEY" in proxy_environment
    assert "DASHSCOPE_API_KEY" not in agent_environment
    assert agent_environment["LITELLM_MODEL"] == (
        "${LITELLM_DEFAULT_MODEL:-qwen3.8-flash}"
    )
    assert agent_environment["LLM_STRICT_JSON_SCHEMA_ENABLED"] == (
        "${LLM_STRICT_JSON_SCHEMA_ENABLED:-true}"
    )
    model = litellm_config["model_list"][0]
    assert model["model_name"] == "qwen3.8-flash"
    assert model["litellm_params"]["model"] == "openai/qwen3.8-flash"
    assert model["litellm_params"]["extra_body"] == {"enable_thinking": False}


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_compose_has_persistent_volumes_and_expected_host_ports` 把上游材料组装为本阶段可消费的被测业务场景；关键协作调用：`yaml.safe_load`、`read_text`、`volumes.keys`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `yaml.safe_load`、`read_text`、`volumes.keys`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_compose_has_persistent_volumes_and_expected_host_ports() -> None:
    compose = yaml.safe_load((ROOT / "docker-compose.yml").read_text(encoding="utf-8"))
    services = compose["services"]
    volumes = compose["volumes"]

    assert {"postgresql_data", "redis_data", "elasticsearch_data", "minio_data"} <= volumes.keys()
    assert (
        "127.0.0.1:${JAVA_API_PORT:-8080}:8080"
        in services["java-api-service"]["ports"]
    )
    assert (
        "127.0.0.1:${PYTHON_AGENT_PORT:-18000}:8000"
        in services["python-agent-service"]["ports"]
    )
    assert (
        "127.0.0.1:${OCR_SERVICE_PORT:-18010}:8010"
        in services["ocr-parser-service"]["ports"]
    )
    assert "127.0.0.1:${NGINX_PORT:-18080}:80" in services["nginx"]["ports"]


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_required_scripts_exist_and_fail_fast` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`、`text.startswith`、`re.search`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`、`text.startswith`、`re.search`、`is_file`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_required_scripts_exist_and_fail_fast() -> None:
    scripts_dir = ROOT / "scripts"
    missing = sorted(name for name in REQUIRED_SCRIPTS if not (scripts_dir / name).is_file())

    assert not missing, f"missing scripts: {missing}"
    for name in REQUIRED_SCRIPTS:
        text = (scripts_dir / name).read_text(encoding="utf-8")
        assert text.startswith("#!/usr/bin/env bash"), name
        assert re.search(r"^set -euo pipefail$", text, re.MULTILINE), name


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_gitignore_excludes_secrets_and_generated_outputs` 验证结构化输出在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_gitignore_excludes_secrets_and_generated_outputs() -> None:
    text = (ROOT / ".gitignore").read_text(encoding="utf-8")

    for pattern in (
        ".env",
        ".env.*",
        "!.env.example",
        "**/__pycache__/",
        "**/.pytest_cache/",
        "**/target/",
        "**/node_modules/",
        "**/dist/",
        "**/.venv/",
        ".worktrees/",
    ):
        assert pattern in text


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_gitattributes_preserves_container_and_shell_line_endings` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_gitattributes_preserves_container_and_shell_line_endings() -> None:
    text = (ROOT / ".gitattributes").read_text(encoding="utf-8")

    for rule in (
        "* text=auto",
        "*.sh text eol=lf",
        "*.yml text eol=lf",
        "*.yaml text eol=lf",
        "Dockerfile text eol=lf",
    ):
        assert rule in text


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_java_public_controllers_use_only_final_unversioned_api_roots` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`join`、`path.read_text`、`source_root.rglob`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `join`、`path.read_text`、`source_root.rglob`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_java_public_controllers_use_only_final_unversioned_api_roots() -> None:
    source_root = ROOT / "java-api-service" / "src" / "main" / "java"
    controller_sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in source_root.rglob("*Controller.java")
    )

    assert "/api/v1" not in controller_sources
    assert '"/api/disputes' in controller_sources
    assert '"/api/notifications' in controller_sources
    assert '"/api/reviews' in controller_sources


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_nginx_supports_replayable_sse_without_exposing_internal_routes` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_nginx_supports_replayable_sse_without_exposing_internal_routes() -> None:
    nginx = (ROOT / "deploy" / "nginx" / "default.conf").read_text(
        encoding="utf-8"
    )

    assert "location ~ ^/api/disputes/.*/events$" in nginx
    assert "proxy_buffering off;" in nginx
    assert "proxy_cache off;" in nginx
    assert "proxy_read_timeout 4h;" in nginx
    assert "location ^~ /internal/" in nginx
    assert "return 404;" in nginx


def test_nginx_only_proxies_the_public_model_health_endpoint() -> None:
    nginx = (ROOT / "deploy" / "nginx" / "default.conf").read_text(
        encoding="utf-8"
    )

    assert "server python-agent-service:8000;" in nginx
    assert "location = /agent-api/health/model" in nginx

    model_health_proxy = nginx.split(
        "location = /agent-api/health/model {", maxsplit=1
    )[1].split(
        "}", maxsplit=1
    )[0]
    assert "proxy_pass http://python_agent_upstream/health/model;" in model_health_proxy
    assert "proxy_http_version 1.1;" in model_health_proxy
    assert "proxy_set_header X-Request-Id $request_id;" in model_health_proxy
    assert (
        "proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;"
        in model_health_proxy
    )
    assert "proxy_set_header X-Forwarded-Proto $scheme;" in model_health_proxy
    assert "proxy_connect_timeout 5s;" in model_health_proxy
    assert "proxy_read_timeout 20s;" in model_health_proxy
    assert "proxy_send_timeout 10s;" in model_health_proxy

    denied_agent_api = nginx.split("location ^~ /agent-api/ {", maxsplit=1)[1].split(
        "}", maxsplit=1
    )[0]
    assert "return 404;" in denied_agent_api
    assert "proxy_pass" not in denied_agent_api


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_room_timing_configuration_is_declared_with_final_defaults` 验证运行配置在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_room_timing_configuration_is_declared_with_final_defaults() -> None:
    application = (
        ROOT / "java-api-service" / "src" / "main" / "resources" / "application.yml"
    ).read_text(encoding="utf-8")

    assert "evidence-window: ${EVIDENCE_WINDOW:PT2H}" in application
    assert "hearing-window: ${HEARING_WINDOW:PT3H}" in application
    assert "hearing-party-stage-window: ${HEARING_PARTY_STAGE_WINDOW:PT20M}" in application
    assert "sse-heartbeat: ${SSE_HEARTBEAT:PT15S}" in application
    assert "seed-demo-disputes: ${SEED_DEMO_DISPUTES:false}" in application


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_windows_secret_generator_preserves_user_key_and_hides_secrets` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`example.write_text`、`subprocess.run`、`destination.read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `example.write_text`、`subprocess.run`、`destination.read_text`、`shutil.which`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_windows_secret_generator_preserves_user_key_and_hides_secrets(
    tmp_path: Path,
) -> None:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")
    example = tmp_path / ".env.example"
    destination = tmp_path / ".env"
    example.write_text((ROOT / ".env.example").read_text(encoding="utf-8"), encoding="utf-8")

    result = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            "scripts\\generate-secrets.ps1",
            "-EnvFile",
            str(destination),
            "-ExampleFile",
            str(example),
        ],
        check=False,
        capture_output=True,
        text=True,
        cwd=ROOT,
    )

    assert result.returncode == 0, result.stderr
    generated = destination.read_text(encoding="utf-8-sig")
    values = dict(
        line.split("=", 1)
        for line in generated.splitlines()
        if line and not line.startswith("#")
    )
    assert values["DASHSCOPE_API_KEY"] == "__PASTE_YOUR_DASHSCOPE_API_KEY_HERE__"
    assert all(values[key] != "__GENERATED_BY_CODEX__" for key in GENERATED_SECRET_KEYS)
    assert all(values[key] not in result.stdout for key in GENERATED_SECRET_KEYS)


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_windows_secret_generator_defaults_to_project_root` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`scripts.mkdir`、`write_text`、`subprocess.run`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `scripts.mkdir`、`write_text`、`subprocess.run`、`is_file`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_windows_secret_generator_defaults_to_project_root(tmp_path: Path) -> None:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")
    project = tmp_path / "project"
    scripts = project / "scripts"
    scripts.mkdir(parents=True)
    (scripts / "generate-secrets.ps1").write_text(
        (ROOT / "scripts" / "generate-secrets.ps1").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    (project / ".env.example").write_text(
        (ROOT / ".env.example").read_text(encoding="utf-8"),
        encoding="utf-8",
    )

    result = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            "scripts\\generate-secrets.ps1",
        ],
        check=False,
        capture_output=True,
        text=True,
        cwd=project,
    )

    assert result.returncode == 0, result.stderr
    assert (project / ".env").is_file()


# 所属模块：跨服务契约测试 > test_repository_contract；函数角色：回归测试用例。
# 具体功能：`test_java_service_uses_java_21_multistage_nonroot_image` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`、`dockerfile.count`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`、`dockerfile.count`。
# 系统意义：固定“跨服务契约测试 > test_repository_contract”的可观察契约，防止后续重构改变业务结果。
def test_java_service_uses_java_21_multistage_nonroot_image() -> None:
    dockerfile = (ROOT / "java-api-service" / "Dockerfile").read_text(encoding="utf-8")
    compose = yaml.safe_load((ROOT / "docker-compose.yml").read_text(encoding="utf-8"))

    assert (
        "FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu@sha256:"
        in dockerfile
    )
    assert " AS build" in dockerfile
    assert "RUN ./mvnw" in dockerfile
    assert dockerfile.count(
        "FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu@sha256:"
    ) == 2
    assert "USER app" in dockerfile

    services = compose["services"]
    api_healthcheck = " ".join(services["java-api-service"]["healthcheck"]["test"])
    assert "http://localhost:8080/actuator/health" in api_healthcheck
    for worker in ("java-control-worker", "java-agent-worker"):
        worker_healthcheck = " ".join(services[worker]["healthcheck"]["test"])
        assert "kill -0 1" in worker_healthcheck
