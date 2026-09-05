from __future__ import annotations

import importlib
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/uat/production-runtime"))
model_config = importlib.import_module("model_config")
common = importlib.import_module("common")


def test_default_stays_blocked_and_explicit_model_is_bound(tmp_path):
    disabled = model_config.load_model_configuration(None)
    assert disabled.mode == "DISABLED"
    assert disabled.profile_id == "production-runtime.contract-blocked"
    assert "proxy_pass" not in disabled.proxy_configuration
    source = tmp_path / "model.env"
    source.write_text(
        "DEFAULT_LLM_API_BASE=https://model.example.test/compatible-mode/v1\n"
        "LITELLM_DEFAULT_MODEL=qwen3.8-flash\n"
        "DASHSCOPE_API_KEY=synthetic-test-credential\n"
    )
    configured = model_config.load_model_configuration(source)
    assert configured.mode == "EXISTING_OPENAI_COMPATIBLE"
    assert "synthetic-test-credential" not in repr(configured)
    assert "synthetic-test-credential" not in configured.proxy_configuration
    assert "proxy_ssl_verify on" in configured.proxy_configuration
    assert "proxy_next_upstream off" in configured.proxy_configuration
    assert "location = /v1/chat/completions" in configured.proxy_configuration
    assert "location / { return 404; }" in configured.proxy_configuration
    assert configured == model_config.load_model_configuration(source)
    (tmp_path / "model-gateway.conf").write_text(configured.proxy_configuration)
    env = {
        "PRODUCTION_RUNTIME_PUBLIC_DIR": str(tmp_path),
        "PRODUCTION_RUNTIME_MODEL_MODE": configured.mode,
        "PRODUCTION_RUNTIME_MODEL": "qwen3.8-flash",
        "PRODUCTION_RUNTIME_GRAPH_MODEL_PROFILE_ID": configured.profile_id,
    }
    context = {"executor_bindings": [{"model_profile_id": configured.profile_id}]}
    model_config.validate_provisioned_model(env, context)
    with pytest.raises(common.ProductionError, match="signed graph binding"):
        model_config.validate_provisioned_model(
            env, {"executor_bindings": [{"model_profile_id": "foreign-profile"}]}
        )
    (tmp_path / "model-gateway.conf").write_text(configured.proxy_configuration + "# drift\n")
    with pytest.raises(common.ProductionError, match="signed graph binding"):
        model_config.validate_provisioned_model(env, context)


@pytest.mark.parametrize("url", [
    "http://model.example.test/v1", "https://user:password@model.example.test/v1",
    "https://model.example.test/v1?key=secret", "https://model.example.test/v1#fragment",
    "https://model.example.test/v1/", "https://model.example.test/$variable/v1",
    "https://model.example.test/../v1", "https://model.example.test:443/v1",
])
def test_noncanonical_or_injectable_endpoint_is_rejected(url):
    with pytest.raises(common.ProductionError):
        model_config.proxy_configuration(url)


def test_model_or_credentials_cannot_be_guessed(tmp_path):
    source = tmp_path / "bad.env"
    source.write_text("LITELLM_DEFAULT_MODEL=other-model\n")
    with pytest.raises(common.ProductionError, match="existing qwen"):
        model_config.load_model_configuration(source)
    source.write_text("LITELLM_DEFAULT_MODEL=qwen3.8-flash\n")
    with pytest.raises(common.ProductionError, match="credential"):
        model_config.load_model_configuration(source)


def test_model_egress_is_confined_to_one_proxy_and_keeps_python_private():
    compose = yaml.safe_load((ROOT / "infra/compose/production-runtime-uat.yml").read_text())
    services = compose["services"]
    proxy = services["model-gateway"]
    assert set(proxy["networks"]) == {"python-egress", "model-egress"}
    assert not proxy.get("environment")
    assert not proxy.get("ports")
    assert len(proxy["volumes"]) == 1
    assert proxy["volumes"][0] == {
        "type": "bind", "source": "${PRODUCTION_RUNTIME_PUBLIC_DIR:?}/model-gateway.conf",
        "target": "/etc/nginx/conf.d/default.conf", "read_only": True,
    }
    assert {name for name, service in services.items() if "model-egress" in service.get("networks", [])} == {"model-gateway"}
    assert set(services["python-agent-service"]["networks"]) == {"python-egress"}
    assert compose["networks"]["python-egress"]["internal"] is True
    assert services["python-agent-service"]["environment"]["LITELLM_BASE_URL"] == "http://model-gateway:4000"
    assert compose["x-java-environment"]["PRODUCTION_RUNTIME_INTAKE_EXECUTION_PROVIDER_ID"] == "litellm"
