from __future__ import annotations

import copy
import importlib
import ipaddress
import json
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/uat/production-runtime"))
common = importlib.import_module("common")
networks = importlib.import_module("networks")
startup = importlib.import_module("start")
local_config = importlib.import_module("local_config")


def test_image_builder_decodes_utf8_diagnostics_independently_of_windows_locale(monkeypatch):
    builder = importlib.import_module("build_image_lock")
    def run(arguments, **options):
        assert options["encoding"] == "utf-8"
        assert options["shell"] is False
        return subprocess.CompletedProcess(arguments, 0, "构建完成 ✓", "")
    monkeypatch.setattr(builder.subprocess, "run", run)
    assert builder._run(["docker", "version"]).stdout == "构建完成 ✓"


def fixture():
    lock = {"run_id": "p9-network-test", "project_name": "aflow-production-runtime-p9-network-test",
            "lock_nonce": "a" * 64, "image_lock_hash": "b" * 64,
            "resources": common.expected_resource_names("p9-network-test")}
    labels = {f"production-runtime.after-sale-flow.dev/{key}": lock[field] for key, field in
              [("run-id", "run_id"), ("project", "project_name"), ("lock-nonce", "lock_nonce"),
               ("image-lock-hash", "image_lock_hash")]}
    config = {"networks": {suffix.replace("_", "-"): {
        "name": name, "internal": suffix not in {"edge", "model_egress"}, "labels": labels.copy(),
    } for suffix, name in zip(common.NETWORK_SUFFIXES, lock["resources"]["networks"])}}
    return config, lock, local_config.load()


def materialize(plan):
    return {"Name": plan["name"], "Driver": "bridge", "Internal": plan["internal"],
            "Labels": plan["labels"].copy(), "IPAM": {"Config": [{"Subnet": plan["subnet"],
            "Gateway": str(ipaddress.ip_network(plan["subnet"])[1])}]}}


def test_network_plan_is_exact_idempotent_and_keeps_all_isolation_boundaries():
    config, lock, settings = fixture()
    plans = networks.plan_networks(config, lock, [], ["0.0.0.0/0", "192.168.100.0/24"], settings)
    assert len(plans) == 14
    assert len({p["subnet"] for p in plans}) == 14
    assert sum(p["internal"] for p in plans) == 12
    # Existing exact owned subnets and their host routes do not make a retry conflict.
    assert networks.plan_networks(config, lock, [materialize(p) for p in plans],
                                  [p["subnet"] for p in plans], settings) == plans
    local_routes = [f"{ipaddress.ip_network(p['subnet'])[1]}/32" for p in plans]
    assert networks.plan_networks(config, lock, [materialize(p) for p in plans], local_routes, settings) == plans


@pytest.mark.parametrize("drift", ["nonce", "internal", "subnet", "foreign", "route", "inventory"])
def test_network_plan_rejects_drift_or_overlap_before_create(drift):
    config, lock, settings = fixture()
    plans = networks.plan_networks(config, lock, [], [], settings)
    existing = [materialize(plans[0])]
    routes = []
    if drift == "nonce":
        existing[0]["Labels"]["production-runtime.after-sale-flow.dev/lock-nonce"] = "foreign"
    elif drift == "internal":
        existing[0]["Internal"] = not existing[0]["Internal"]
    elif drift == "subnet":
        existing[0]["IPAM"]["Config"][0]["Subnet"] = "10.247.240.240/28"
    elif drift == "foreign":
        existing[0]["Name"] = "unrelated-existing-project"
    elif drift == "route":
        routes = ["10.247.0.0/16"]
    else:
        config["networks"].pop(next(iter(config["networks"])))
    with pytest.raises(common.ProductionError):
        networks.plan_networks(config, lock, existing, routes, settings)


def test_allocator_creates_exact_networks_once_without_cleanup(tmp_path, monkeypatch):
    config, lock, _settings = fixture()
    existing = []
    calls = []
    plans = networks.plan_networks(config, lock, [], [], local_config.load())

    def run(argv, **kwargs):
        calls.append(argv)
        if "compose" in argv:
            output = json.dumps(config)
        elif "create" in argv:
            plan = next(p for p in plans if p["name"] == argv[-1])
            assert argv[argv.index("--subnet") + 1] == plan["subnet"]
            assert ("--internal" in argv) == plan["internal"]
            existing.append(materialize(plan))
            output = plan["name"]
        else:
            assert argv[:3] == ["docker", "network", "inspect"]
            output = json.dumps([next(n for n in existing if n["Name"] == argv[-1])])
        return subprocess.CompletedProcess(argv, 0, output, "")

    monkeypatch.setattr(networks, "inventory", lambda: copy.deepcopy(existing))
    monkeypatch.setattr(networks, "host_routes", lambda: [])
    monkeypatch.setattr(common, "run_command", run)
    (tmp_path / "evidence").mkdir()
    networks.ensure_networks(tmp_path / "run.env", lock)
    networks.ensure_networks(tmp_path / "run.env", lock)
    assert sum("create" in call for call in calls) == 14
    assert not any("rm" in call or "prune" in call for call in calls)


def startup_harness(tmp_path, monkeypatch):
    stages = []
    candidate = "c" * 40
    env_file = tmp_path / "p9-run" / "production-runtime.env"
    monkeypatch.setattr(startup, "announce", lambda stage: stages.append(stage))
    monkeypatch.setattr(common, "run_command", lambda argv, **kw: subprocess.CompletedProcess(argv, 0, candidate, ""))
    monkeypatch.setattr(startup.build_image_lock, "_repository_state", lambda _candidate: stages.append("clean"))
    monkeypatch.setattr(startup.model_config, "load_model_configuration", lambda path: SimpleNamespace(profile_id="exact-model"))
    monkeypatch.setattr(startup.build_image_lock, "build_lock", lambda **kw:
                        (stages.append("build") or tmp_path / "lock.json", tmp_path / "attestation.json"))

    def provision(*args):
        stages.append("provision")
        env_file.parent.mkdir()
        env_file.touch()
        return env_file

    monkeypatch.setattr(startup.provision, "provision", provision)
    monkeypatch.setattr(startup.up, "start_services", lambda env, timeout:
                        stages.append("ready") or {"status": "INFRASTRUCTURE_READY_ONLY"})
    monkeypatch.setattr(common, "validate_env_lock", lambda path:
                        ({"PRODUCTION_RUNTIME_GRAPH_MODEL_PROFILE_ID": "exact-model"},
                         {"runtime_root": str(tmp_path), "candidate_sha": candidate}))
    return stages


def test_unified_start_orders_all_gates_and_reuses_same_run(tmp_path, monkeypatch):
    stages = startup_harness(tmp_path, monkeypatch)
    first = startup.start(tmp_path, ROOT / ".env")
    second = startup.start(tmp_path, ROOT / ".env")
    assert stages.count("build") == stages.count("provision") == 1
    assert stages.count("ready") == 2
    assert stages.index("clean") < stages.index("build") < stages.index("provision") < stages.index("ready")
    assert first["env_file"] == second["env_file"]
    assert not second["business_e2e_passed"]


def test_unified_start_rejects_candidate_or_model_drift_without_new_build(tmp_path, monkeypatch):
    stages = startup_harness(tmp_path, monkeypatch)
    startup.start(tmp_path, ROOT / ".env")
    monkeypatch.setattr(startup.model_config, "load_model_configuration", lambda path: SimpleNamespace(profile_id="changed"))
    with pytest.raises(common.ProductionError, match="model configuration changed"):
        startup.start(tmp_path, ROOT / ".env")
    pointer = common.load_json(tmp_path / "current-run.json")
    pointer["candidate_sha"] = "d" * 40
    common.write_json(tmp_path / "current-run.json", pointer)
    with pytest.raises(common.ProductionError, match="different source/configuration"):
        startup.start(tmp_path, ROOT / ".env")
    assert stages.count("build") == stages.count("ready") == 1


def test_unified_start_does_not_report_success_after_startup_failure(tmp_path, monkeypatch):
    startup_harness(tmp_path, monkeypatch)
    def fail(*args):
        raise common.ProductionError("worker readiness failed")
    monkeypatch.setattr(startup.up, "start_services", fail)
    with pytest.raises(common.ProductionError, match="worker readiness failed"):
        startup.start(tmp_path, ROOT / ".env")
    assert (tmp_path / "current-run.json").is_file()  # exact failed run retained for diagnosis/retry
    assert not (tmp_path / "p9-run" / "startup-receipt.json").exists()
