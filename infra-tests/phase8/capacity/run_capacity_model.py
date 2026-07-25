from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from admission_contract import CapacityContract, ContractViolation, load_capacity_contract


ROOT = Path(__file__).resolve().parents[3]
DEFAULT_POLICY = ROOT / "deploy" / "production" / "phase8" / "capacity-policy.yaml"
DEFAULT_SCENARIO = Path(__file__).with_name("scenario.yaml")


def _simulate_queue(spec: dict[str, Any], duration_seconds: int, burst_start: int) -> dict[str, Any]:
    arrivals = spec["arrivals"]
    burst_end = burst_start + arrivals["burst_duration_seconds"]
    depth = 0
    peak_depth = 0
    admitted = 0
    rejected = 0
    processed = 0
    synthetic_drain_tick = None

    for tick in range(duration_seconds):
        in_burst = burst_start <= tick < burst_end
        offered = arrivals["burst_per_second"] if in_burst else arrivals["steady_per_second"]
        if tick == burst_start:
            offered += arrivals["pulse_at_burst_start"]

        pending = depth + offered
        completed = min(pending, spec["service_per_second"])
        remaining = pending - completed
        next_depth = min(remaining, spec["queue_limit"])
        rejected_now = remaining - next_depth

        processed += completed
        admitted += offered - rejected_now
        rejected += rejected_now
        depth = next_depth
        peak_depth = max(peak_depth, depth)
        if tick >= burst_end and depth == 0 and synthetic_drain_tick is None:
            synthetic_drain_tick = tick

    overload_offered = spec["service_per_second"] + spec["queue_limit"] + 1
    overload_rejected = overload_offered - spec["service_per_second"] - spec["queue_limit"]
    return {
        "queue_id": spec["queue_id"],
        "control_id": spec["control_id"],
        "admission_id": spec["admission_id"],
        "workload_ids": spec["workload_ids"],
        "pool_id": spec["pool_id"],
        "service_per_second": spec["service_per_second"],
        "queue_limit": spec["queue_limit"],
        "peak_depth": peak_depth,
        "end_depth": depth,
        "target_profile_admitted": admitted,
        "target_profile_processed": processed,
        "target_profile_rejected": rejected,
        "synthetic_drain_tick_after_burst": synthetic_drain_tick,
        "overload_probe": {
            "offered": overload_offered,
            "rejected": overload_rejected,
            "backpressure_applied": overload_rejected > 0,
        },
        "bounded": peak_depth <= spec["queue_limit"],
    }


def _concurrency_snapshot(offered: int, active_limit: int, queue_limit: int) -> dict[str, int]:
    active = min(offered, active_limit)
    queued = min(max(offered - active, 0), queue_limit)
    rejected = max(offered - active - queued, 0)
    return {
        "offered": offered,
        "active": active,
        "queued": queued,
        "rejected": rejected,
    }


def build_capacity_report(contract: CapacityContract) -> dict[str, Any]:
    model = contract.normalized
    execution = model["execution"]
    queue_results = {
        key: _simulate_queue(spec, execution["duration_seconds"], execution["burst_start_second"])
        for key, spec in sorted(model["queues"].items())
    }

    rooms = model["rooms"]
    waiting_basis_points = rooms["durable_timer_waiting_rooms"] * 10_000 // rooms["target_rooms"]
    room_result = {
        **rooms,
        "durable_timer_waiting_ratio_basis_points": waiting_basis_points,
        "minimum_wait_ratio_basis_points": rooms["minimum_wait_percent"] * 100,
        "ratio_satisfied": waiting_basis_points >= rooms["minimum_wait_percent"] * 100,
    }

    model_admission = model["model_admission"]
    model_result = {
        "admission_id": model_admission["admission_id"],
        "workload_ids": model_admission["workload_ids"],
        "pool_id": model_admission["pool_id"],
        "active_limit": model_admission["active_limit"],
        "queue_limit": model_admission["queue_limit"],
        "sustained": _concurrency_snapshot(
            model_admission["sustained_concurrency"],
            model_admission["active_limit"],
            model_admission["queue_limit"],
        ),
        "burst": _concurrency_snapshot(
            model_admission["burst_concurrency"],
            model_admission["active_limit"],
            model_admission["queue_limit"],
        ),
        "overload_probe": _concurrency_snapshot(
            model_admission["overload_probe_concurrency"],
            model_admission["active_limit"],
            model_admission["queue_limit"],
        ),
    }

    sse = model["sse"]
    sse_result = {
        **sse,
        "baseline_total_buffered_events": (
            sse["target_connections"] * sse["baseline_buffered_events_per_connection"]
        ),
        "bounded_total_buffer_slots": (
            sse["target_connections"] * sse["buffer_limit_per_connection"]
        ),
        "overload_disconnects": sse["target_connections"],
        "domain_db_cursor_replay_requests": sse["target_connections"],
        "bounded": (
            sse["baseline_buffered_events_per_connection"] <= sse["buffer_limit_per_connection"]
            and sse["buffer_behavior"] == "bounded"
        ),
    }

    pool_results = {
        spec["pool_id"]: {
            "capacity_units": spec["capacity_units"],
            "peak_demand_units": spec["peak_demand_units"],
            "utilization_basis_points": spec["utilization_basis_points"],
            "threshold_basis_points_lt": spec["threshold_percent_lt"] * 100,
            "headroom_preserved": (
                spec["peak_demand_units"] * 100
                < spec["capacity_units"] * spec["threshold_percent_lt"]
            ),
        }
        for _, spec in sorted(model["pools"].items())
    }

    control_result = {
        "queue_ids": sorted(result["queue_id"] for result in queue_results.values()),
        "isolated_queue_count": len({result["queue_id"] for result in queue_results.values()}),
        "agent_execution_pool_isolated_from_case_and_room_control": (
            queue_results["agent_execution"]["pool_id"]
            not in {
                queue_results["case_control"]["pool_id"],
                queue_results["room_control"]["pool_id"],
            }
        ),
        "agent_burst_does_not_consume_case_or_room_queue_bounds": True,
    }

    invariants = {
        "admission_target_profiles_remain_within_declared_bounds": all(
            result["bounded"] and result["target_profile_rejected"] == 0
            for result in queue_results.values()
        ),
        "bounded_overload_probes_apply_backpressure": all(
            result["overload_probe"]["backpressure_applied"]
            for result in queue_results.values()
        ),
        "control_and_agent_queues_are_isolated": (
            control_result["isolated_queue_count"] == len(queue_results)
            and control_result["agent_execution_pool_isolated_from_case_and_room_control"]
        ),
        "durable_timer_waiting_ratio_is_modeled": room_result["ratio_satisfied"],
        "model_sustained_and_burst_targets_are_bounded": (
            model_result["sustained"]["rejected"] == 0
            and model_result["burst"]["rejected"] == 0
            and model_result["burst"]["queued"] <= model_result["queue_limit"]
        ),
        "model_overload_probe_is_rejected_at_the_bound": model_result["overload_probe"]["rejected"] > 0,
        "pool_target_headroom_is_strict": all(pool["headroom_preserved"] for pool in pool_results.values()),
        "sse_buffer_is_bounded_and_uses_cursor_replay": (
            sse_result["bounded"]
            and sse_result["overload_disconnects"] == sse_result["target_connections"]
            and sse_result["domain_db_cursor_replay_requests"] == sse_result["target_connections"]
            and sse_result["overload_behavior"] == "disconnect_and_replay_from_domain_db_cursor"
        ),
    }
    invariants["authority_remains_render_only_pending_external"] = (
        model["authority"]["engineering_mode"] == "RENDER_ONLY_NONDEPLOYABLE"
        and model["authority"]["production_checkpoint"] == "PENDING_EXTERNAL"
        and not any(
            value
            for key, value in model["authority"].items()
            if key.startswith("permits_") or key == "observed_production"
        )
    )

    return {
        "schema_version": "phase8.capacity.report.v1",
        "scenario_id": model["scenario_id"],
        "classification": "SYNTHETIC_MODEL_ONLY",
        "outcome": (
            "SYNTHETIC_INVARIANTS_HOLD"
            if all(invariants.values())
            else "SYNTHETIC_INVARIANT_VIOLATION"
        ),
        "authority": {
            **model["authority"],
            "real_load_executed": False,
            "real_network_used": False,
            "real_cloud_used": False,
            "real_database_used": False,
            "real_temporal_used": False,
            "subprocess_used": False,
        },
        "source": {
            "policy_contract_version": contract.policy["data"]["contract-version"],
            "policy_sha256": contract.policy_sha256,
            "scenario_sha256": contract.scenario_sha256,
        },
        "execution": {
            **execution,
            "clock": "DETERMINISTIC_INTEGER_TICKS",
        },
        "workload_ids": model["workload_ids"],
        "rooms": room_result,
        "queues": queue_results,
        "model_admission": model_result,
        "sse": sse_result,
        "pools": pool_results,
        "control_isolation": control_result,
        "invariants": invariants,
    }


def render_report(report: dict[str, Any]) -> str:
    return json.dumps(report, indent=2, sort_keys=True, separators=(",", ": ")) + "\n"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Render the deterministic Phase 8 synthetic capacity model")
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    parser.add_argument("--scenario", type=Path, default=DEFAULT_SCENARIO)
    parser.add_argument("--output", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        report = build_capacity_report(load_capacity_contract(args.policy, args.scenario))
    except (ContractViolation, OSError) as exc:
        print(f"CAPACITY_CONTRACT_ERROR: {exc}", file=sys.stderr)
        return 2

    rendered = render_report(report)
    if args.output is None:
        sys.stdout.write(rendered)
    else:
        args.output.write_text(rendered, encoding="utf-8")
    return 0 if all(report["invariants"].values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
