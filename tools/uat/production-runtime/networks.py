"""Allocate only the exact host-locked networks, without using Docker's default pools."""
from __future__ import annotations

import ipaddress
import json
import platform
from pathlib import Path
from typing import Any

import common
import local_config


def host_routes() -> list[str]:
    if platform.system() == "Windows":
        result = common.run_command([
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "$ErrorActionPreference='Stop'; Get-NetRoute -AddressFamily IPv4 | "
            "Select-Object -ExpandProperty DestinationPrefix | ConvertTo-Json -Compress",
        ])
        routes = json.loads(result.stdout)
        return [routes] if isinstance(routes, str) else routes
    if platform.system() == "Linux":
        result = common.run_command(["ip", "-json", "-4", "route", "show", "table", "all"])
        return [item["dst"] for item in json.loads(result.stdout) if item.get("dst") != "default"]
    raise common.ProductionError("host route inventory is unsupported on this platform")


def inventory() -> list[dict[str, Any]]:
    identifiers = common.run_command(["docker", "network", "ls", "--quiet"]).stdout.split()
    return json.loads(common.run_command(["docker", "network", "inspect", *identifiers]).stdout) if identifiers else []


def validate_network(actual: dict, plan: dict) -> None:
    subnets = [item.get("Subnet") for item in (actual.get("IPAM", {}).get("Config") or [])]
    if (actual.get("Name") != plan["name"] or actual.get("Driver") != "bridge"
            or actual.get("Internal") != plan["internal"] or subnets != [plan["subnet"]]
            or actual.get("EnableIPv6", False)
            or any((actual.get("Labels") or {}).get(k) != v for k, v in plan["labels"].items())):
        raise common.ProductionError(f"existing network ownership/topology drift: {plan['name']}")


def plan_networks(config: dict, lock: dict, existing: list[dict], routes: list[str], settings: dict) -> list[dict]:
    declared = config.get("networks", {})
    if sorted(n.get("name", "") for n in declared.values()) != sorted(lock["resources"]["networks"]):
        raise common.ProductionError("UAT network inventory differs from the host lock")
    labels = {
        "production-runtime.after-sale-flow.dev/run-id": lock["run_id"],
        "production-runtime.after-sale-flow.dev/project": lock["project_name"],
        "production-runtime.after-sale-flow.dev/lock-nonce": lock["lock_nonce"],
        "production-runtime.after-sale-flow.dev/image-lock-hash": lock["image_lock_hash"],
    }
    pool = ipaddress.ip_network(settings["network_pool"])
    subnets = list(pool.subnets(new_prefix=settings["network_prefix_length"]))
    if len(declared) > len(subnets):
        raise common.ProductionError("UAT address pool is too small")
    plans = []
    for (key, network), subnet in zip(sorted(declared.items()), subnets):
        if network.get("labels") != labels or network.get("external", False):
            raise common.ProductionError("UAT network labels or external authority drifted")
        plans.append({
            "name": network["name"], "subnet": str(subnet),
            "internal": network.get("internal", False),
            "labels": {**labels, "com.docker.compose.project": lock["project_name"],
                       "com.docker.compose.network": key},
        })
    by_name = {p["name"]: p for p in plans}
    owned_subnets = set()
    owned_host_routes = set()
    occupied = []
    for network in existing:
        if network["Name"] in by_name:
            validate_network(network, by_name[network["Name"]])
            owned_subnets.add(by_name[network["Name"]]["subnet"])
            # Linux's local route table includes each owned bridge's gateway and
            # directed-broadcast /32 routes, not just the connected subnet.
            for item in network["IPAM"]["Config"]:
                subnet = ipaddress.ip_network(item["Subnet"])
                owned_host_routes.update((f"{subnet.network_address}/32", f"{subnet.broadcast_address}/32"))
                if item.get("Gateway"):
                    owned_host_routes.add(f"{item['Gateway']}/32")
        else:
            occupied.extend(item["Subnet"] for item in (network.get("IPAM", {}).get("Config") or []) if item.get("Subnet"))
    # Repeated startup may see host routes installed by these exact, validated networks.
    # Only exact subnet routes are excluded; a broader host/VPN route still blocks startup.
    occupied.extend(route for route in routes if route != "0.0.0.0/0"
                    and route not in owned_subnets and route not in owned_host_routes)
    for value in occupied:
        other = ipaddress.ip_network(value, strict=False)
        if other.version == pool.version and pool.overlaps(other):
            raise common.ProductionError(f"UAT address pool conflicts with an existing network/host route: {value}")
    return plans


def ensure_networks(env_file: Path, lock: dict) -> None:
    # During provisioning lock.state is PROVISIONING; caller has already reserved it and
    # proved zero old resources. During up it comes from validate_env_lock (ACTIVE).
    rendered = json.loads(common.run_command(common.compose_argv(
        env_file, "config", "--format", "json", profile="evidence"
    )).stdout)
    existing = inventory()
    plans = plan_networks(rendered, lock, existing, host_routes(), local_config.load())
    existing_names = {network["Name"] for network in existing}
    for plan in plans:
        if plan["name"] in existing_names:
            continue
        argv = ["docker", "network", "create", "--driver", "bridge", "--subnet", plan["subnet"]]
        if plan["internal"]:
            argv.append("--internal")
        for key, value in sorted(plan["labels"].items()):
            argv.extend(["--label", f"{key}={value}"])
        argv.append(plan["name"])
        identifier = common.run_command(argv).stdout.strip()
        actual = json.loads(common.run_command(["docker", "network", "inspect", identifier]).stdout)[0]
        validate_network(actual, plan)
    common.write_json(env_file.parent / "evidence" / "network-allocation.json", {
        "schema_version": "production-runtime-network-allocation.v1",
        "run_id": lock["run_id"], "lock_nonce": lock["lock_nonce"], "networks": plans,
        "old_networks_removed": False,
    })
