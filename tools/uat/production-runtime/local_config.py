"""Versioned, non-secret local UAT defaults; private run state is generated elsewhere."""
from __future__ import annotations

import ipaddress
import json

import common

DIRECTORY = common.ROOT / "infra/environments/production-runtime-uat"
CONFIG_FILE = DIRECTORY / "local-start.json"
BASE_IMAGES_FILE = DIRECTORY / "base-images.json"
KEYS = {
    "schema_version", "gateway_port", "network_pool", "network_prefix_length",
    "repository_prefix", "wait_timeout_seconds",
}


def load() -> dict:
    # Reuse the strict duplicate-member boundary of the image input, not last-key-wins JSON.
    from build_image_lock import _strict_object, REPOSITORY_PREFIX

    config = json.loads(CONFIG_FILE.read_text(encoding="utf-8"), object_pairs_hook=_strict_object)
    if set(config) != KEYS or config["schema_version"] != "production-runtime-local-start.v1":
        raise common.ProductionError("local startup configuration fields drifted")
    try:
        pool = ipaddress.ip_network(config["network_pool"], strict=True)
        private = any(pool.subnet_of(ipaddress.ip_network(block)) for block in
                      ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
        prefix = config["network_prefix_length"]
        if not private or type(prefix) is not int or not pool.prefixlen <= prefix <= 28:
            raise ValueError("pool/prefix")
        if 2 ** (prefix - pool.prefixlen) < len(common.NETWORK_SUFFIXES):
            raise ValueError("pool capacity")
    except (ValueError, TypeError) as error:
        raise common.ProductionError("local UAT network pool is invalid or too small") from error
    if (type(config["gateway_port"]) is not int or not 25180 <= config["gateway_port"] <= 25999
            or type(config["wait_timeout_seconds"]) is not int
            or not 120 <= config["wait_timeout_seconds"] <= 1200
            or not isinstance(config["repository_prefix"], str)
            or not REPOSITORY_PREFIX.fullmatch(config["repository_prefix"])):
        raise common.ProductionError("local startup port, deadline or registry is invalid")
    return config
