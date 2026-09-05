"""Explicit, deployment-bound model access for an isolated browser UAT run."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

import common


MODEL = "qwen3.8-flash"


@dataclass(frozen=True)
class ModelConfiguration:
    mode: str
    proxy_configuration: str
    api_key: str = field(repr=False)

    @property
    def profile_id(self) -> str:
        if self.mode == "DISABLED":
            return "production-runtime.contract-blocked"
        digest = hashlib.sha256(self.proxy_configuration.encode("ascii")).hexdigest()
        return f"qwen3.8-flash.uat.{digest}.v1"


def load_model_configuration(env_file: Path | None) -> ModelConfiguration:
    if env_file is None:
        return ModelConfiguration("DISABLED", proxy_configuration(None), "")
    common.assert_regular_single_link(env_file, "existing model environment file")
    values = common.parse_env_file(env_file)
    if values.get("LITELLM_DEFAULT_MODEL") != MODEL:
        raise common.ProductionError("UAT requires the existing qwen3.8-flash model")
    endpoint = values.get("DEFAULT_LLM_API_BASE", "")
    key = values.get("DASHSCOPE_API_KEY", "")
    if len(key) < 16 or len(key) > 4096 or any(char.isspace() for char in key):
        raise common.ProductionError("existing model API credential is missing or malformed")
    return ModelConfiguration(
        "EXISTING_OPENAI_COMPATIBLE", proxy_configuration(endpoint), key
    )


def proxy_configuration(endpoint: str | None) -> str:
    location = "location / { return 503; }"
    if endpoint is not None:
        parsed = urlsplit(endpoint)
        if (
            parsed.scheme != "https"
            or not re.fullmatch(r"[a-z0-9][a-z0-9.-]*[a-z0-9]", parsed.netloc)
            or "." not in parsed.netloc
            or not re.fullmatch(r"(?:/[A-Za-z0-9_-]+)*/v1", parsed.path)
            or parsed.query
            or parsed.fragment
            or endpoint != f"https://{parsed.netloc}{parsed.path}"
        ):
            raise common.ProductionError("model endpoint must be a canonical HTTPS /v1 URL")
        location = f"""location = /v1/chat/completions {{
        if ($request_method != POST) {{ return 405; }}
        proxy_http_version 1.1;
        proxy_set_header Host {parsed.netloc};
        proxy_set_header Connection "";
        proxy_ssl_server_name on;
        proxy_ssl_name {parsed.netloc};
        proxy_ssl_verify on;
        proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates.crt;
        proxy_connect_timeout 10s;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
        proxy_buffering off;
        proxy_next_upstream off;
        proxy_pass {endpoint}/chat/completions;
    }}
    location / {{ return 404; }}"""
    return f"""server {{
    listen 4000;
    server_tokens off;
    access_log off;
    client_max_body_size 2m;
    location = /healthz {{ return 200 'ok'; }}
    {location}
}}
"""


def validate_provisioned_model(env: dict[str, str], run_context: dict) -> None:
    path = Path(env["PRODUCTION_RUNTIME_PUBLIC_DIR"]) / "model-gateway.conf"
    common.assert_regular_single_link(path, "model gateway configuration")
    if path.stat().st_size > 16_384:
        raise common.ProductionError("model gateway configuration is oversized")
    configuration = path.read_text(encoding="ascii")
    mode = env.get("PRODUCTION_RUNTIME_MODEL_MODE")
    if mode == "DISABLED":
        expected = proxy_configuration(None)
    elif mode == "EXISTING_OPENAI_COMPATIBLE":
        matches = re.findall(r"proxy_pass (https://[^;]+?)/chat/completions;", configuration)
        if len(matches) != 1:
            raise common.ProductionError("model gateway has ambiguous upstream authority")
        expected = proxy_configuration(matches[0])
    else:
        raise common.ProductionError("model mode must be explicitly configured")
    profile = ModelConfiguration(mode, expected, "").profile_id
    if (
        configuration != expected
        or env.get("PRODUCTION_RUNTIME_MODEL") != MODEL
        or env.get("PRODUCTION_RUNTIME_GRAPH_MODEL_PROFILE_ID") != profile
        or run_context["executor_bindings"][0]["model_profile_id"] != profile
    ):
        raise common.ProductionError("model gateway differs from the signed graph binding")
