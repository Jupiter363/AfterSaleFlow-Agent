from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import re
import shutil
import subprocess
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable, Sequence

try:
    from scripts import generate_phase4_candidate_evidence as trusted
    from scripts import run_phase5_candidate_checkpoint as runner
    from scripts.generate_phase3_candidate_evidence import (
        EvidenceError,
        JUnitReport,
        TestCase,
        _assert_candidate,
        _assert_timestamp,
        _change_summary,
        _sha256,
        _totals,
        _write_json,
        parse_junit,
        write_junit,
    )
except (ImportError, ModuleNotFoundError):  # Direct execution puts scripts/ on sys.path.
    import generate_phase4_candidate_evidence as trusted  # type: ignore[no-redef]
    import run_phase5_candidate_checkpoint as runner  # type: ignore[no-redef]
    from generate_phase3_candidate_evidence import (  # type: ignore[no-redef]
        EvidenceError,
        JUnitReport,
        TestCase,
        _assert_candidate,
        _assert_timestamp,
        _change_summary,
        _sha256,
        _totals,
        _write_json,
        parse_junit,
        write_junit,
    )


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = runner.MATRIX_PATH
DERIVED_REPORTS = {
    "P5-BATCH-0": "batch-0-junit.xml",
    "P5-BATCH-1": "batch-1-junit.xml",
    "P5-BATCH-2": "batch-2-junit.xml",
    "P5-BATCH-3": "batch-3-junit.xml",
}
CONTRACT_FILES = {
    "phase-metrics.json",
    "baseline-id-coverage.json",
    "check-id-coverage.json",
    "failure-classification.json",
    "external-gates.json",
    "candidate-commit.txt",
    *runner.SOURCE_REPORTS.values(),
    *DERIVED_REPORTS.values(),
}
HASH_INDEX_NAME = "artifact-sha256.json"
HASH_INDEX_SCHEMA = "phase5-candidate-artifact-index.v1"
EXPECTED_FILES = {runner.MANIFEST_NAME, HASH_INDEX_NAME, *CONTRACT_FILES}
EVIDENCE_SCHEMA = "temporal-first-phase-metrics.v1"
BASELINE_SCHEMA = "temporal-first-baseline-id-coverage.v1"
CHECK_SCHEMA = "temporal-first-check-id-coverage.v1"
FAILURE_SCHEMA = "temporal-first-failure-classification.v1"
EXTERNAL_SCHEMA = "temporal-first-external-gates.v1"
PASS_STATUS = {
    "engineering_checkpoint": "PASS",
    "promotion_gate": "PENDING",
    "next_phase_permission": "PHASE_6_ENGINEERING_ONLY",
    "MIG-004": "PENDING_PROMOTION",
    "MIG-005": "PENDING_PROMOTION",
}

PYTHON = "python_phase_5_deduplicated"
JAVA = "java_phase_5_deduplicated"
FRONTEND = "frontend_phase_5_deduplicated"
STATIC = "static_phase_5_deduplicated"

# Each preserved behavior ID names at least one concrete suite selector. The generator
# resolves every selector against the candidate-bound JUnit cases before claiming PASS.
BASELINE_SELECTORS: dict[str, tuple[tuple[str, str], ...]] = {
    "EVD-001": ((JAVA, "*EvidenceAgentTurnServiceTest#rejectsEvidenceReferenceThatIsNotVisibleToTheCurrentActor"),),
    "EVD-002": ((JAVA, "*EvidenceSubmissionServiceTest#submitsHearingSupplementEvidenceToTheHearingRoom"),),
    "EVD-003": (
        (JAVA, "*EvidenceSubmissionServiceTest#deletesOnlyPendingEvidenceOwnedByCurrentActor"),
        (JAVA, "*EvidenceSubmissionServiceTest#refusesToDeleteSubmittedEvidence"),
    ),
    "EVD-004": ((JAVA, "*EvidenceSubmissionServiceTest#submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk"),),
    "EVD-005": ((JAVA, "*EvidenceAgentTurnServiceTest#partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply"),),
    "EVD-006": ((JAVA, "*EvidenceApiIntegrationTest#uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown"),),
    "EVD-007": ((JAVA, "*EvidenceGraphResultFinalizerTest#lowRelevanceDoesNotBecomeSuspectedForgery"),),
    "EVD-008": ((FRONTEND, "src/views/disputes/EvidenceRoomView.test.js#*keeps completion available for low confidence evidence"),),
    "EVD-009": ((FRONTEND, "src/views/disputes/EvidenceRoomView.test.js#*shows every party human-review item to the platform reviewer"),),
    "EVD-010": ((JAVA, "*EvidenceCompletionServiceTest#repeatedCompletionByTheSameParticipantIdUsesTheExistingPhaseConfirmation"),),
    "EVD-011": (
        (JAVA, "*EvidenceCompletionServiceTest#respondentCanCompleteIndependentlyBeforeInitiatorSubmitsEvidence"),
        (JAVA, "*EvidenceCompletionServiceTest#initiatorCannotCompleteEvidenceWithoutSubmittedEvidence"),
    ),
    "EVD-012": ((JAVA, "*EvidenceRoomIntegrationTest#bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded"),),
    "EVD-013": ((JAVA, "*EvidenceWindowWorkflowTest#bothPartiesCompleteBeforeTheWarningWithoutSchedulingSideEffects"),),
    "EVD-014": (
        (JAVA, "*EvidenceRoomIntegrationTest#deadlineExpiryWithOnePartySealsAndOpensHearing"),
        (JAVA, "*EvidenceRoomIntegrationTest#deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing"),
    ),
    "EVD-015": ((JAVA, "*EvidenceWindowWorkflowTest#warningFiresAtTheSharedDeadlineAndBothPartiesCanStillComplete"),),
    "UI-001": ((FRONTEND, "src/views/disputes/EvidenceRoomView.test.js#*renders an intake-like fixed two-panel evidence room"),),
    "UI-003": ((FRONTEND, "src/views/disputes/EvidenceRoomView.test.js#*moves focus into the evidence gate, traps Tab, closes on Escape, and restores the completion trigger"),),
    "UI-004": ((FRONTEND, "src/views/disputes/EvidenceRoomView.test.js#*keeps a 200-character filename inspectable without rendering feedback inside the compact card"),),
    "UI-005": ((FRONTEND, "src/views/disputes/EvidenceRoomView.test.js#*keeps the board fixed with horizontal evidence rails and a vertical human-review queue"),),
    "CORE-001": ((PYTHON, "tests.test_streaming#test_invalid_streamed_schema_fails_closed_without_second_model_call"),),
    "CORE-002": ((PYTHON, "tests.test_streaming#test_observer_reorders_events_constructed_by_parallel_model_threads"),),
    "CORE-003": ((FRONTEND, "src/api/agentStream.test.js#*uses authenticated fetch, resumes at Last-Event-ID and stops at final"),),
    "CORE-004": ((FRONTEND, "src/api/agentStream.test.js#*discovers active room runs after refresh with actor isolation headers"),),
    "CORE-005": ((FRONTEND, "src/views/disputes/EvidenceRoomView.test.js#*does not write a late user message response into the merchant private thread"),),
    "CORE-006": ((PYTHON, "tests.test_streaming_v2#test_v2_model_rejects_unknown_payload_and_unbound_final"),),
    "CORE-007": ((PYTHON, "tests.test_streaming#test_real_provider_stream_projects_answer_and_ignores_reasoning_channel"),),
    "CORE-008": ((PYTHON, "tests.test_streaming_v2#test_v1_adapter_preserves_identity_and_drops_raw_final_response"),),
    "CORE-009": ((FRONTEND, "src/stores/agentStream.test.js#*clears the aborted attempt before revealing replacement attempt text"),),
    "CORE-010": ((FRONTEND, "src/views/disputes/EvidenceRoomView.test.js#*history*"),),
    "SEC-001": ((JAVA, "*EvidenceRoomControllerTest#exposesOnlyTheActorScopedProcessProjectionWithPrivateNoStoreHeaders"),),
    "SEC-002": ((PYTHON, "tests.agents.test_evidence_clerk_turn#test_evidence_envelope_rejects_cross_actor_private_history"),),
    "SEC-003": ((JAVA, "*EvidenceRoomControllerTest#completesEvidenceWithoutAcceptingAClientDossierVersion"),),
    "SEC-004": ((PYTHON, "tests.agents.test_evidence_clerk_turn#test_evidence_turn_api_requires_service_secret_and_fails_without_model"),),
    "SEC-005": ((PYTHON, "tests.agents.test_evidence_clerk_turn#test_assessment_policy_blocks_fact_ids_outside_intake_dossier_allowlist"),),
    "SEC-006": ((JAVA, "*EvidenceNoFormalSinkGuardTest#closedAssemblyCannotReachApplicationServiceWriterOrExecutableCallback"),),
}

CHECK_SELECTORS: dict[str, tuple[tuple[str, str], ...]] = {
    "ROOM-EVIDENCE-001": (
        (PYTHON, "tests.agents.test_evidence_clerk_turn#test_existing_evidence_links_are_filtered_by_fact_and_visibility"),
        (JAVA, "*EvidenceRoomControllerTest#exposesOnlyTheActorScopedProcessProjectionWithPrivateNoStoreHeaders"),
    ),
    "ROOM-EVIDENCE-002": (
        (PYTHON, "tests.graphs.evidence.test_graph#test_graph_processes_closed_synthetic_counts_in_deterministic_waves*"),
        (JAVA, "*EvidenceGraphResultFinalizerTest#validatesCompleteCoverageAndCommitsOnlyAZeroWriteSyntheticReceipt"),
    ),
    "ROOM-EVIDENCE-003": (
        (PYTHON, "tests.graphs.evidence.test_reducers#test_keyed_reduction_is_associative_order_independent_and_replay_idempotent*"),
    ),
    "ROOM-EVIDENCE-004": (
        (PYTHON, "tests.agents.test_evidence_clerk_turn#test_evidence_asset_loader_fetches_authorized_image_and_builds_data_url"),
        (JAVA, "*EvidenceAssetAuthorizationTest#verifiesDirectManifestSignatureAndBindsAnActualImmutableLoadReceipt"),
    ),
    "ROOM-EVIDENCE-005": (
        (JAVA, "*EvidenceRoomWorkflowTest#warningFiresExactlyThirtyMinutesBeforeTheImmutableDeadline"),
    ),
    "ROOM-EVIDENCE-006": (
        (JAVA, "*EvidenceGraphResultFinalizerTest#validatesCompleteCoverageAndCommitsOnlyAZeroWriteSyntheticReceipt"),
        (JAVA, "*EvidenceDossierFreezerTest#frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix"),
    ),
    "GRAPH-009": ((JAVA, "*EvidenceGraphResultFinalizerTest#actualLoadReceiptHashOrVersionCannotBeSubstituted"),),
    "GRAPH-016": (
        (PYTHON, "tests.graph_runtime.integration.test_graph_postgres_runtime#test_real_durable_fanout_permit_scope_renew_release_and_retry"),
        (PYTHON, "tests.graph_runtime.unit.test_bulkhead#test_room_tenant_and_global_limits_are_granted_atomically"),
        (PYTHON, "tests.graph_runtime.unit.test_bulkhead#test_bounded_queues_reject_without_starting_untracked_work"),
    ),
    "GRAPH-017": ((PYTHON, "tests.graphs.evidence.test_reducers#test_conflicting_value_hash_or_key_fails_closed*"),),
    "GRAPH-018": ((PYTHON, "tests.graphs.evidence.test_recovery#test_random_completion_order_produces_one_stable_terminal_hash*"),),
    "GRAPH-019": ((PYTHON, "tests.graphs.evidence.test_recovery#test_recovery_rejects_another_graph_lease_fence_on_the_same_java_manifest*"),),
    "TEMP-020": ((JAVA, "*EvidenceRoomWorkflowTest#warningFiresExactlyThirtyMinutesBeforeTheImmutableDeadline"),),
    "TEMP-021": ((JAVA, "*EvidenceRoomWorkflowTest#firstPartyCompletionDoesNotResetWarningOrExpiry"),),
    "TEMP-022": ((JAVA, "*EvidenceRoomWorkflowReplayTest#warningDuplicateAndCompletionHistoryReplaysDeterministically"),),
    "TEMP-023": ((JAVA, "*EvidenceRoomWorkflowTest#completionAcceptedBeforeDeadlineWinsWithoutAnExpiryCommand"),),
    "TEMP-024": ((JAVA, "*EvidenceRoomWorkflowWorkerRecoveryTest#lostActivityResponseRecoversOnlyFromAnExplicitCommittedReceipt"),),
    "LCEL-009": (
        (PYTHON, "tests.agents.test_evidence_clerk_turn#test_evidence_asset_loader_fetches_authorized_image_and_builds_data_url"),
        (JAVA, "*EvidenceAssetAuthorizationTest#verifiesDirectManifestSignatureAndBindsAnActualImmutableLoadReceipt"),
    ),
    "ENV-014": (
        (PYTHON, "tests.graphs.evidence.test_graph#test_graph_never_has_more_than_eight_active_item_assessments*"),
        (PYTHON, "tests.graphs.evidence.test_graph#test_manifest_scheduler_rejects_count_above_one_hundred*"),
    ),
    "MIG-005": (
        (JAVA, "*EvidenceNoFormalSinkGuardTest#closedAssemblyCannotReachApplicationServiceWriterOrExecutableCallback"),
        (STATIC, "tests.static.test_phase5_evidence_no_sink_bulkhead#test_closed_assembly_has_no_caller_executable_or_application_dependency_slot"),
    ),
}


def _json_lf_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def _write_json_lf(path: Path, value: Any) -> None:
    path.write_bytes(_json_lf_bytes(value))


def load_matrix(path: Path = MATRIX_PATH) -> dict[str, Any]:
    matrix = runner.load_matrix(path)
    required = set(
        matrix["batches"][runner.BATCH_ID].get("evidence", {}).get("required_files", [])
    )
    if required != CONTRACT_FILES:
        raise EvidenceError(f"{path}: required Phase 5 candidate evidence file set drifted")
    required_status = matrix["batches"][runner.BATCH_ID]["evidence"].get(
        "required_status_output"
    )
    if required_status != {
        "engineering_checkpoint": "PASS_or_FAIL",
        "promotion_gate": "PENDING",
        "next_phase_permission": "PHASE_6_ENGINEERING_ONLY_or_BLOCKED",
        "MIG-005": "PENDING_PROMOTION",
    }:
        raise EvidenceError(f"{path}: Phase 5 status output contract drifted")
    return matrix


def _dedupe(values: Sequence[str], *, path_containment: bool = False) -> list[str]:
    result: list[str] = []
    for value in values:
        normalized = value.replace("\\", "/").rstrip("/")
        if path_containment:
            if any(normalized == current or normalized.startswith(f"{current}/") for current in result):
                continue
            covered = [current for current in result if current.startswith(f"{normalized}/")]
            if covered:
                insert_at = min(result.index(current) for current in covered)
                result[:] = [current for current in result if current not in covered]
                result.insert(insert_at, normalized)
                continue
        if normalized not in result:
            result.append(normalized)
    return result


def _source_scope(
    command_id: str, report: JUnitReport, matrix: dict[str, Any]
) -> None:
    argv = runner.focused_commands(matrix)[command_id]["argv"]
    if command_id in {
        "python_phase_5_deduplicated",
        "static_phase_5_deduplicated",
    }:
        selectors = _dedupe(runner._pytest_selectors(argv), path_containment=True)
        trusted._select_path_cases(report, selectors, context=command_id)
    elif command_id == "frontend_phase_5_deduplicated":
        selectors = _dedupe(runner._frontend_selectors(argv), path_containment=True)
        trusted._select_path_cases(
            report, selectors, context=command_id, frontend=True
        )
    else:
        classes = _dedupe(runner._java_classes(argv))
        trusted._select_java_cases(report, classes, context=command_id)


def consume_source_reports(
    *,
    source_dir: Path,
    output_dir: Path,
    candidate_commit: str,
    matrix: dict[str, Any],
    source_payloads: dict[str, bytes] | None = None,
) -> dict[str, JUnitReport]:
    if source_dir.resolve() == output_dir.resolve():
        raise EvidenceError("Phase 5 source and evidence directories must differ")
    if not source_dir.is_dir():
        raise EvidenceError(f"Phase 5 source report directory does not exist: {source_dir}")
    actual = {path.name for path in source_dir.iterdir() if path.is_file()}
    expected = set(runner.SOURCE_REPORTS.values())
    if actual != expected:
        raise EvidenceError(
            f"Phase 5 source report set drifted: missing={sorted(expected - actual)}, "
            f"unexpected={sorted(actual - expected)}"
        )
    output_dir.mkdir(parents=True, exist_ok=True)
    reports: dict[str, JUnitReport] = {}
    for command_id, filename in runner.SOURCE_REPORTS.items():
        source = source_dir / filename
        captured = (source_payloads or {}).get(filename)
        parse_path = source
        temporary = output_dir / f".{filename}.captured"
        if captured is not None:
            temporary.write_bytes(captured)
            parse_path = temporary
        report = parse_junit(parse_path, source=filename)
        if report.candidate_commit != candidate_commit or report.command_id != command_id:
            raise EvidenceError(f"{filename}: candidate or command binding drifted")
        totals = report.totals
        if totals["failures"] or totals["errors"] or totals["skipped"]:
            raise EvidenceError(f"{filename}: source report is not all-pass zero-skip")
        _source_scope(command_id, report, matrix)
        reports[command_id] = write_junit(
            output_dir / filename,
            name=filename.removesuffix(".xml"),
            cases=[trusted._without_output_nodes(case) for case in report.cases],
            candidate_commit=candidate_commit,
            command_id=command_id,
        )
        temporary.unlink(missing_ok=True)
    identities = [
        case.identity for command_id in runner.COMMAND_ORDER for case in reports[command_id].cases
    ]
    if len(identities) != len(set(identities)):
        raise EvidenceError("Phase 5 source reports contain duplicate cross-source testcase identities")
    return reports


def _select_paths(
    report: JUnitReport,
    selectors: Sequence[str],
    *,
    context: str,
    frontend: bool = False,
) -> tuple[TestCase, ...]:
    return trusted._select_path_cases(
        report,
        _dedupe(selectors, path_containment=True),
        context=context,
        frontend=frontend,
    )


def _batch_cases(
    batch_id: str, matrix: dict[str, Any], reports: dict[str, JUnitReport]
) -> tuple[TestCase, ...]:
    if batch_id == runner.BATCH_ID:
        cases = [
            case
            for command_id in runner.COMMAND_ORDER
            for case in reports[command_id].cases
        ]
    else:
        batch = matrix["batches"][batch_id]
        cases: list[TestCase] = []
        if batch_id == "P5-BATCH-0":
            suites = batch["baseline_suites"]
            cases.extend(
                _select_paths(
                    reports["python_phase_5_deduplicated"],
                    suites["python"],
                    context=f"{batch_id} Python baseline",
                )
            )
            cases.extend(
                trusted._select_java_cases(
                    reports["java_phase_5_deduplicated"],
                    suites["java"],
                    context=f"{batch_id} Java baseline",
                )
            )
            cases.extend(
                _select_paths(
                    reports["frontend_phase_5_deduplicated"],
                    suites["frontend"],
                    context=f"{batch_id} frontend baseline",
                    frontend=True,
                )
            )
            static_tests = batch["static_tests"]
        else:
            python_tests = batch.get("planned_python_tests", [])
            if batch_id == "P5-BATCH-2":
                python_tests = [
                    *python_tests,
                    "python-agent-service/tests/graph_runtime/integration/test_graph_postgres_runtime.py",
                ]
            if python_tests:
                cases.extend(
                    _select_paths(
                        reports["python_phase_5_deduplicated"],
                        python_tests,
                        context=f"{batch_id} Python tests",
                    )
                )
            if batch.get("planned_java_test_classes"):
                cases.extend(
                    trusted._select_java_cases(
                        reports["java_phase_5_deduplicated"],
                        batch["planned_java_test_classes"],
                        context=f"{batch_id} Java tests",
                    )
                )
            if batch.get("frontend_tests"):
                cases.extend(
                    _select_paths(
                        reports["frontend_phase_5_deduplicated"],
                        batch["frontend_tests"],
                        context=f"{batch_id} frontend tests",
                        frontend=True,
                    )
                )
            static_tests = batch.get("static_tests", batch.get("planned_static_tests", []))
        if static_tests:
            cases.extend(
                _select_paths(
                    reports["static_phase_5_deduplicated"],
                    static_tests,
                    context=f"{batch_id} static tests",
                )
            )
    unique: dict[tuple[str, str, str], TestCase] = {}
    for case in cases:
        unique[case.identity] = case
    return tuple(unique.values())


def write_derived_reports(
    *,
    matrix: dict[str, Any],
    reports: dict[str, JUnitReport],
    output_dir: Path,
    candidate_commit: str,
) -> dict[str, JUnitReport]:
    source_hashes = {report.path.name: _sha256(report.path) for report in reports.values()}
    derived = {}
    for batch_id, filename in DERIVED_REPORTS.items():
        cases = _batch_cases(batch_id, matrix, reports)
        if not cases:
            raise EvidenceError(f"{batch_id}: derived report is empty")
        derived[batch_id] = write_junit(
            output_dir / filename,
            name=filename.removesuffix(".xml"),
            cases=cases,
            candidate_commit=candidate_commit,
            source_hashes=source_hashes,
        )
    return derived


def _flatten_ids(groups: dict[str, Sequence[str]], context: str) -> set[str]:
    values = [item for group in groups.values() for item in group]
    if not values or len(values) != len(set(values)):
        raise EvidenceError(f"{context}: IDs are empty or duplicated")
    return set(values)


def _selector_matches(command_id: str, case: TestCase, selector: str) -> bool:
    class_pattern, separator, name_pattern = selector.partition("#")
    if not separator or not class_pattern or not name_pattern:
        raise EvidenceError(f"invalid Phase 5 evidence selector {selector!r}")
    classname = case.classname.replace("\\", "/")
    if command_id == FRONTEND:
        classname = classname.removeprefix("frontend/")
    class_match = fnmatch.fnmatch(classname, class_pattern) or fnmatch.fnmatch(
        classname.rsplit(".", 1)[-1], class_pattern
    )
    name_match = fnmatch.fnmatch(case.name, name_pattern)
    if command_id == FRONTEND and not name_match:
        name_match = fnmatch.fnmatch(case.name, f"* > {name_pattern}")
    if not name_match and not any(value in name_pattern for value in "*?[]"):
        name_match = case.name.startswith(f"{name_pattern}[") and case.name.endswith("]")
    return class_match and name_match


def _resolve_mapping(
    *,
    item_id: str,
    mapping: Sequence[tuple[str, str]],
    reports: dict[str, JUnitReport],
) -> list[dict[str, Any]]:
    if not mapping:
        raise EvidenceError(f"{item_id}: explicit selectors are required")
    bindings = []
    for command_id, selector in mapping:
        if command_id not in reports:
            raise EvidenceError(f"{item_id}: selector names an unknown source report")
        report = reports[command_id]
        matches = [case.node_id for case in report.cases if _selector_matches(command_id, case, selector)]
        if not matches:
            raise EvidenceError(f"{item_id}: evidence selector did not run: {selector}")
        bindings.append(
            {
                "selector": selector,
                "report": report.path.name,
                "report_sha256": _sha256(report.path),
                "testcases": matches,
            }
        )
    return bindings


def _summary(rows: Iterable[dict[str, Any]]) -> dict[str, int]:
    values = tuple(rows)
    counts = Counter(row["status"] for row in values)
    return {**{key: counts[key] for key in sorted(counts)}, "total": len(values)}


def build_baseline_coverage(
    *, matrix: dict[str, Any], candidate_commit: str, reports: dict[str, JUnitReport]
) -> dict[str, Any]:
    ids = _flatten_ids(matrix["baseline_ids"], "Phase 5 baseline IDs")
    if set(BASELINE_SELECTORS) != ids:
        raise EvidenceError(
            "Phase 5 baseline selector map drifted: "
            f"missing={sorted(ids - set(BASELINE_SELECTORS))}, "
            f"unexpected={sorted(set(BASELINE_SELECTORS) - ids)}"
        )
    rows = []
    for baseline_id in sorted(ids):
        rows.append(
            {
                "id": baseline_id,
                "status": "PASS_ENGINEERING",
                "bindings": _resolve_mapping(
                    item_id=baseline_id,
                    mapping=BASELINE_SELECTORS[baseline_id],
                    reports=reports,
                ),
            }
        )
    return {
        "schema_version": BASELINE_SCHEMA,
        "phase": 5,
        "candidate_commit": candidate_commit,
        "scope": "PHASE_5_PRESERVED_ROOM_BEHAVIOR",
        "all_required_ids_mapped": set(item["id"] for item in rows) == ids,
        "summary": _summary(rows),
        "baselines": rows,
    }


def _check_owners(matrix: dict[str, Any], ids: set[str]) -> dict[str, list[str]]:
    owners = {
        check_id: sorted(
            owner
            for owner, definition in matrix["owners"].items()
            if check_id in definition.get("primary_check_ids", [])
        )
        for check_id in ids
    }
    # ENV-014 is the candidate capacity aggregate jointly closed by E's GRAPH-016
    # implementation and R's immutable checkpoint; the matrix intentionally does not
    # duplicate it in either owner's primary implementation list.
    if "ENV-014" in owners and not owners["ENV-014"]:
        owners["ENV-014"] = ["E", "R"]
    missing = sorted(check_id for check_id, values in owners.items() if not values)
    if missing:
        raise EvidenceError("Phase 5 check IDs have no owners: " + ", ".join(missing))
    return owners


def build_check_coverage(
    *, matrix: dict[str, Any], candidate_commit: str, reports: dict[str, JUnitReport]
) -> dict[str, Any]:
    ids = _flatten_ids(matrix["primary_check_ids"], "Phase 5 primary check IDs")
    owners = _check_owners(matrix, ids)
    if set(CHECK_SELECTORS) != ids:
        raise EvidenceError(
            "Phase 5 check selector map drifted: "
            f"missing={sorted(ids - set(CHECK_SELECTORS))}, "
            f"unexpected={sorted(set(CHECK_SELECTORS) - ids)}"
        )
    engineering_required = set(matrix["claim_policy"]["engineering_pass_required"])
    if not engineering_required < ids:
        raise EvidenceError("Phase 5 engineering-pass check set drifted")
    rows = []
    for check_id in sorted(ids):
        status = "PASS_ENGINEERING"
        note = None
        if check_id == "ENV-014":
            status = "PASS_ENGINEERING_CAPACITY_ONLY"
            note = "Synthetic 100-item capacity evidence does not approve the public 100-file contract."
        elif check_id == "MIG-005":
            status = "PENDING_PROMOTION"
            note = "Real shadow, formal-writer cutover, canary, and production approval remain external."
        row = {
            "id": check_id,
            "owners": owners[check_id],
            "status": status,
            "bindings": _resolve_mapping(
                item_id=check_id,
                mapping=CHECK_SELECTORS[check_id],
                reports=reports,
            ),
        }
        if note:
            row["note"] = note
        rows.append(row)
    status_by_id = {row["id"]: row["status"] for row in rows}
    if any(status_by_id[item] != "PASS_ENGINEERING" for item in engineering_required):
        raise EvidenceError("Phase 5 engineering-required check did not pass")
    return {
        "schema_version": CHECK_SCHEMA,
        "phase": 5,
        "candidate_commit": candidate_commit,
        "scope": "SIGNED_SYNTHETIC_ENGINEERING_ONLY",
        "all_required_ids_mapped": set(status_by_id) == ids,
        "summary": _summary(rows),
        "checks": rows,
    }


def build_external_gates(
    *, matrix: dict[str, Any], candidate_commit: str
) -> dict[str, Any]:
    external = matrix["gate"].get("external_promotion_gates")
    expected_external = {
        "MIG-004": "PENDING_PROMOTION",
        "evidence_100_file_product_api_ui_approval": "PENDING_EXTERNAL_APPROVAL",
        "production_asset_authorization": "PENDING_EXTERNAL_APPROVAL",
        "real_shadow_and_canary": "FORBIDDEN",
    }
    if external != expected_external:
        raise EvidenceError("Phase 5 external promotion gates drifted")
    traffic = matrix["gate"].get("traffic_constraints")
    expected_false = (
        "formal_evidence_graph_sink_allowed",
        "temporal_evidence_allocation_allowed",
        "real_case_shadow_allowed",
        "production_traffic_allowed",
        "canary_allowed",
        "promotion_allowed",
    )
    if any(traffic.get(key) is not False for key in expected_false):
        raise EvidenceError("Phase 5 runtime or promotion traffic restriction was relaxed")
    unified = matrix["batches"].get("P5-UNIFIED-CHECKPOINT", {})
    if unified.get("tier") != "T3" or unified.get("automatic") is not False:
        raise EvidenceError("Phase 5 T3 unified checkpoint policy drifted")
    return {
        "schema_version": EXTERNAL_SCHEMA,
        "phase": 5,
        "candidate_commit": candidate_commit,
        "promotion_gate": "PENDING",
        "promotion_gates": {
            "MIG-004": "PENDING_PROMOTION",
            "MIG-005": "PENDING_PROMOTION",
        },
        "external_gates": external,
        "runtime_restrictions": runner.RUNTIME_RESTRICTIONS,
        "traffic_constraints": traffic,
        "unified_checkpoint": {
            "tier": "T3",
            "automatic": False,
            "executed": False,
            "classification": "EXTERNAL_GATE",
        },
    }


def build_failure_classification(
    *, matrix: dict[str, Any], candidate_commit: str, manifest: dict[str, Any]
) -> dict[str, Any]:
    policy = matrix.get("failure_classification", {})
    required = ["PRODUCT", "FIXTURE", "INFRA", "EXTERNAL_GATE"]
    if (
        policy.get("required_values") != required
        or policy.get("classify_before_rerun") is not True
        or policy.get("exactly_one_classification_per_failure") is not True
    ):
        raise EvidenceError("Phase 5 failure-classification policy drifted")
    classifications = {
        name: policy[name] for name in required if isinstance(policy.get(name), dict)
    }
    if set(classifications) != set(required):
        raise EvidenceError("Phase 5 failure-classification actions are incomplete")
    quarantined = [
        {
            key: attempt[key]
            for key in (
                "id",
                "candidate_commit",
                "started_at",
                "finished_at",
                "duration_seconds",
                "exit_code",
                "failure_classification",
                "stdout_path",
                "stdout_sha256",
                "stderr_path",
                "stderr_sha256",
                "raw_reports",
            )
        }
        for attempt in manifest["quarantined_attempts"]
    ]
    return {
        "schema_version": FAILURE_SCHEMA,
        "phase": 5,
        "candidate_commit": candidate_commit,
        "classifications": classifications,
        "classify_before_rerun": True,
        "bounded_same_sha_infra_reruns_per_source": runner.MAX_INFRA_RERUNS_PER_SOURCE,
        "accepted_source_suite_failures": [],
        "quarantined_source_attempts": quarantined,
        "open_product_failures": [],
        "quarantined_attempts_reused": False,
        "decision": PASS_STATUS,
    }


def build_phase_metrics(
    *,
    release_id: str,
    base_commit: str,
    candidate_commit: str,
    engineering_started_at: str,
    matrix: dict[str, Any],
    reports: dict[str, JUnitReport],
    derived: dict[str, JUnitReport],
    manifest: dict[str, Any],
    manifest_path: Path,
) -> dict[str, Any]:
    commands = runner.focused_commands(matrix)
    records = {item["id"]: item for item in manifest["commands"]}
    all_cases = [
        case for command_id in runner.COMMAND_ORDER for case in reports[command_id].cases
    ]
    totals = _totals(all_cases)
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        raise EvidenceError("Phase 5 PASS metrics cannot contain non-green source results")
    started_text = _assert_timestamp(
        manifest["verification_started_at"], "verification_started_at"
    )
    finished_text = _assert_timestamp(
        manifest["verification_finished_at"], "verification_finished_at"
    )
    started = datetime.fromisoformat(started_text.replace("Z", "+00:00"))
    finished = datetime.fromisoformat(finished_text.replace("Z", "+00:00"))
    if finished < started:
        raise EvidenceError("Phase 5 verification finish predates its start")
    source_entries = []
    for command_id in runner.COMMAND_ORDER:
        report = reports[command_id]
        record = records[command_id]
        source_entries.append(
            {
                "name": report.path.name,
                "command_id": command_id,
                "matrix_command_sha256": hashlib.sha256(
                    commands[command_id]["command"].encode("utf-8")
                ).hexdigest(),
                **{
                    field: report.totals[field]
                    for field in ("tests", "failures", "errors", "skipped")
                },
                "sha256": _sha256(report.path),
                "execution_source_sha256": record["report_sha256"],
                "started_at": record["started_at"],
                "finished_at": record["finished_at"],
                "duration_seconds": record["duration_seconds"],
                "exit_code": record["exit_code"],
            }
        )
    batch_entries = [
        {
            "id": batch_id,
            "report": report.path.name,
            "sha256": _sha256(report.path),
            **{
                field: report.totals[field]
                for field in ("tests", "failures", "errors", "skipped")
            },
        }
        for batch_id, report in derived.items()
    ]
    return {
        "schema_version": EVIDENCE_SCHEMA,
        "release_id": release_id,
        "phase": 5,
        "name": "evidence-pilot-engineering-shadow",
        "scope": "SIGNED_SYNTHETIC_ENGINEERING_ONLY",
        "base_commit": _assert_candidate(base_commit, "base commit"),
        "candidate_commit": candidate_commit,
        "engineering_started_at": _assert_timestamp(
            engineering_started_at, "engineering_started_at"
        ),
        "verification_started_at": started_text,
        "verification_finished_at": finished_text,
        "verification_wall_clock_seconds": round((finished - started).total_seconds(), 3),
        "change_summary": _change_summary(base_commit, candidate_commit),
        "candidate_verification": {
            "source_execution_mode": "RECORDED_CANDIDATE_BOUND_SOURCE_RUNNER",
            "deduplicated_execution": True,
            "runner_execution": "sequential",
            "mixed_candidate_results": False,
            "quarantined_attempts_reused": False,
            "unconditional_rerun": False,
            "same_sha_infra_reruns_per_source": runner.MAX_INFRA_RERUNS_PER_SOURCE,
            "distinct_tests": totals["tests"],
            "failures": totals["failures"],
            "errors": totals["errors"],
            "skipped": totals["skipped"],
        },
        "environment": manifest["environment"],
        "commands": manifest["commands"],
        "quarantined_attempts": manifest["quarantined_attempts"],
        "source_execution_manifest": {
            "name": runner.MANIFEST_NAME,
            "sha256": _sha256(manifest_path),
            "manifest_sha256": manifest["manifest_sha256"],
        },
        "source_reports": source_entries,
        "batch_views": batch_entries,
        "runtime_restrictions": runner.RUNTIME_RESTRICTIONS,
        "status": PASS_STATUS,
    }


def _git_hash_object(payload: bytes, *, logical_path: str | None = None) -> str:
    command = ["git", "hash-object"]
    command.append("--no-filters" if logical_path is None else f"--path={logical_path}")
    command.append("--stdin")
    process = subprocess.run(command, cwd=ROOT, input=payload, check=False, capture_output=True)
    output = process.stdout.decode("ascii", errors="replace").strip()
    if process.returncode or not re.fullmatch(r"[0-9a-f]{40,64}", output):
        detail = process.stderr.decode("utf-8", errors="replace").strip()
        raise EvidenceError(f"cannot apply Git clean filter to Phase 5 evidence: {detail or output}")
    return output


def _assert_git_clean_filter_stable(path: Path, *, release_id: str) -> None:
    payload = path.read_bytes()
    if b"\r" in payload:
        raise EvidenceError(f"Phase 5 evidence artifact {path.name} contains non-LF line endings")
    logical = (
        Path("test-reports") / "temporal-first" / release_id / "phase-5" / path.name
    ).as_posix()
    if _git_hash_object(payload) != _git_hash_object(payload, logical_path=logical):
        raise EvidenceError(f"Phase 5 evidence artifact {path.name} changes under Git clean filters")


def _capture_authenticated_run(
    execution_manifest_path: Path, candidate_commit: str
) -> tuple[dict[str, Any], bytes, dict[str, bytes]]:
    path = execution_manifest_path.resolve()
    run_root = path.parent
    trusted.assert_candidate_run_directory(run_root)
    manifest_payload = path.read_bytes()
    if b"\r" in manifest_payload:
        raise EvidenceError("Phase 5 execution manifest contains non-LF line endings")
    try:
        manifest = json.loads(manifest_payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"cannot decode Phase 5 execution manifest: {exception}") from exception
    runner._validate_manifest(manifest, run_root, candidate_commit, require_pass=True)
    records = {record["id"]: record for record in manifest["commands"]}
    source_payloads = {}
    for command_id, filename in runner.SOURCE_REPORTS.items():
        source_path = run_root / "source" / filename
        payload = source_path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != records[command_id]["report_sha256"]:
            raise EvidenceError(f"{command_id}: captured source JUnit SHA-256 drifted")
        source_payloads[filename] = payload
    if path.read_bytes() != manifest_payload or any(
        (run_root / "source" / filename).read_bytes() != payload
        for filename, payload in source_payloads.items()
    ):
        raise EvidenceError("Phase 5 run artifacts changed while their snapshot was authenticated")
    return manifest, manifest_payload, source_payloads


def _assert_run_snapshot_unchanged(
    execution_manifest_path: Path,
    manifest_payload: bytes,
    source_payloads: dict[str, bytes],
) -> None:
    run_root = execution_manifest_path.resolve().parent
    if execution_manifest_path.resolve().read_bytes() != manifest_payload:
        raise EvidenceError("Phase 5 execution manifest changed during evidence assembly")
    for filename, payload in source_payloads.items():
        if (run_root / "source" / filename).read_bytes() != payload:
            raise EvidenceError(f"Phase 5 source report {filename} changed during evidence assembly")


def _validate_staged_documents(
    *,
    output_dir: Path,
    candidate_commit: str,
    manifest_payload: bytes,
    documents: dict[str, dict[str, Any]],
    reports: dict[str, JUnitReport],
    derived: dict[str, JUnitReport],
) -> None:
    if (output_dir / runner.MANIFEST_NAME).read_bytes() != manifest_payload:
        raise EvidenceError("archived Phase 5 execution manifest bytes drifted")
    if (output_dir / "candidate-commit.txt").read_bytes() != (
        candidate_commit + "\n"
    ).encode("ascii"):
        raise EvidenceError("staged Phase 5 candidate commit drifted")
    for filename, document in documents.items():
        if (output_dir / filename).read_bytes() != _json_lf_bytes(document):
            raise EvidenceError(f"staged Phase 5 document {filename} drifted")
    metrics = documents["phase-metrics.json"]
    source_entries = {entry["command_id"]: entry for entry in metrics["source_reports"]}
    batch_entries = {entry["id"]: entry for entry in metrics["batch_views"]}
    if set(source_entries) != set(runner.COMMAND_ORDER) or set(batch_entries) != set(
        DERIVED_REPORTS
    ):
        raise EvidenceError("Phase 5 metrics report inventory drifted")
    for command_id, report in reports.items():
        entry = source_entries[command_id]
        path = output_dir / runner.SOURCE_REPORTS[command_id]
        parsed = parse_junit(path)
        if (
            entry["sha256"] != _sha256(path)
            or parsed.candidate_commit != candidate_commit
            or parsed.command_id != command_id
            or parsed.totals != report.totals
        ):
            raise EvidenceError(f"Phase 5 metrics/source cross-hash drifted for {command_id}")
    for batch_id, report in derived.items():
        entry = batch_entries[batch_id]
        path = output_dir / DERIVED_REPORTS[batch_id]
        parsed = parse_junit(path)
        if (
            entry["sha256"] != _sha256(path)
            or parsed.candidate_commit != candidate_commit
            or parsed.totals != report.totals
        ):
            raise EvidenceError(f"Phase 5 metrics/batch cross-hash drifted for {batch_id}")


def _validate_bundle(
    *,
    output_dir: Path,
    release_id: str,
    candidate_commit: str,
    manifest_payload: bytes,
    documents: dict[str, dict[str, Any]],
    reports: dict[str, JUnitReport],
    derived: dict[str, JUnitReport],
) -> None:
    actual = {path.name for path in output_dir.iterdir() if path.is_file()}
    if actual != EXPECTED_FILES:
        raise EvidenceError(
            f"Phase 5 evidence file set mismatch: missing={sorted(EXPECTED_FILES - actual)}, "
            f"unexpected={sorted(actual - EXPECTED_FILES)}"
        )
    _validate_staged_documents(
        output_dir=output_dir,
        candidate_commit=candidate_commit,
        manifest_payload=manifest_payload,
        documents=documents,
        reports=reports,
        derived=derived,
    )
    candidate_path = output_dir / "candidate-commit.txt"
    if candidate_path.read_bytes() != (candidate_commit + "\n").encode("ascii"):
        raise EvidenceError("Phase 5 candidate commit artifact drifted")
    try:
        archived_manifest = json.loads(
            (output_dir / runner.MANIFEST_NAME).read_text(encoding="utf-8")
        )
        index = json.loads((output_dir / HASH_INDEX_NAME).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"cannot reload Phase 5 authentication artifacts: {exception}") from exception
    runner._assert_manifest_seal(archived_manifest)
    if archived_manifest.get("candidate_commit") != candidate_commit:
        raise EvidenceError("archived Phase 5 execution manifest candidate drifted")
    if (
        index.get("schema_version") != HASH_INDEX_SCHEMA
        or index.get("candidate_commit") != candidate_commit
    ):
        raise EvidenceError("Phase 5 artifact index identity drifted")
    artifacts = index.get("artifacts")
    indexed_names = EXPECTED_FILES - {HASH_INDEX_NAME}
    if (
        not isinstance(artifacts, list)
        or {item.get("path") for item in artifacts if isinstance(item, dict)} != indexed_names
        or len(artifacts) != len(indexed_names)
    ):
        raise EvidenceError("Phase 5 artifact index file set drifted")
    for item in artifacts:
        path = output_dir / item["path"]
        if item.get("sha256") != _sha256(path) or item.get("bytes") != path.stat().st_size:
            raise EvidenceError(f"Phase 5 artifact index drifted for {path.name}")
    for path in output_dir.iterdir():
        if path.is_file():
            _assert_git_clean_filter_stable(path, release_id=release_id)


def assemble_evidence(
    *,
    matrix: dict[str, Any],
    output_dir: Path,
    release_id: str,
    base_commit: str,
    candidate_commit: str,
    engineering_started_at: str,
    execution_manifest_path: Path,
) -> dict[str, Any]:
    candidate = _assert_candidate(candidate_commit)
    manifest, manifest_payload, source_payloads = _capture_authenticated_run(
        execution_manifest_path, candidate
    )
    source_dir = execution_manifest_path.resolve().parent / "source"
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / runner.MANIFEST_NAME).write_bytes(manifest_payload)
    archived_manifest = json.loads(
        (output_dir / runner.MANIFEST_NAME).read_text(encoding="utf-8")
    )
    runner._assert_manifest_seal(archived_manifest)
    if archived_manifest != manifest:
        raise EvidenceError("archived Phase 5 execution manifest differs from validated content")
    reports = consume_source_reports(
        source_dir=source_dir,
        output_dir=output_dir,
        candidate_commit=candidate,
        matrix=matrix,
        source_payloads=source_payloads,
    )
    derived = write_derived_reports(
        matrix=matrix,
        reports=reports,
        output_dir=output_dir,
        candidate_commit=candidate,
    )
    baseline = build_baseline_coverage(
        matrix=matrix, candidate_commit=candidate, reports=reports
    )
    checks = build_check_coverage(
        matrix=matrix, candidate_commit=candidate, reports=reports
    )
    failures = build_failure_classification(
        matrix=matrix, candidate_commit=candidate, manifest=manifest
    )
    gates = build_external_gates(matrix=matrix, candidate_commit=candidate)
    metrics = build_phase_metrics(
        release_id=release_id,
        base_commit=base_commit,
        candidate_commit=candidate,
        engineering_started_at=engineering_started_at,
        matrix=matrix,
        reports=reports,
        derived=derived,
        manifest=manifest,
        manifest_path=execution_manifest_path,
    )
    _write_json_lf(output_dir / "phase-metrics.json", metrics)
    _write_json_lf(output_dir / "baseline-id-coverage.json", baseline)
    _write_json_lf(output_dir / "check-id-coverage.json", checks)
    _write_json_lf(output_dir / "failure-classification.json", failures)
    _write_json_lf(output_dir / "external-gates.json", gates)
    (output_dir / "candidate-commit.txt").write_bytes((candidate + "\n").encode("ascii"))
    documents = {
        "phase-metrics.json": metrics,
        "baseline-id-coverage.json": baseline,
        "check-id-coverage.json": checks,
        "failure-classification.json": failures,
        "external-gates.json": gates,
    }
    before_index = {path.name for path in output_dir.iterdir() if path.is_file()}
    expected_before_index = EXPECTED_FILES - {HASH_INDEX_NAME}
    if before_index != expected_before_index:
        raise EvidenceError(
            "Phase 5 pre-index evidence file set mismatch: "
            f"missing={sorted(expected_before_index - before_index)}, "
            f"unexpected={sorted(before_index - expected_before_index)}"
        )
    _validate_staged_documents(
        output_dir=output_dir,
        candidate_commit=candidate,
        manifest_payload=manifest_payload,
        documents=documents,
        reports=reports,
        derived=derived,
    )
    index = {
        "schema_version": HASH_INDEX_SCHEMA,
        "candidate_commit": candidate,
        "artifacts": [
            {
                "path": name,
                "sha256": _sha256(output_dir / name),
                "bytes": (output_dir / name).stat().st_size,
            }
            for name in sorted(expected_before_index)
        ],
    }
    _write_json_lf(output_dir / HASH_INDEX_NAME, index)
    _validate_bundle(
        output_dir=output_dir,
        release_id=release_id,
        candidate_commit=candidate,
        manifest_payload=manifest_payload,
        documents=documents,
        reports=reports,
        derived=derived,
    )
    _assert_run_snapshot_unchanged(
        execution_manifest_path, manifest_payload, source_payloads
    )
    return metrics


def generate_evidence(
    *,
    release_id: str,
    candidate_commit: str,
    base_commit: str,
    engineering_started_at: str,
    execution_manifest_path: Path,
    output_dir: Path,
) -> dict[str, Any]:
    candidate = _assert_candidate(candidate_commit)
    manifest_path = execution_manifest_path.resolve()
    run_root = manifest_path.parent
    output = output_dir.resolve()
    staging = output.with_name(f".{output.name}.assembling")
    trusted.assert_candidate_run_directory(run_root)
    trusted.assert_clean_detached_candidate(candidate, allowed_untracked_roots=(run_root,))
    trusted.assert_base_ancestor(base_commit, candidate)
    if output.exists() or staging.exists():
        raise EvidenceError(f"Phase 5 evidence output or staging path already exists: {output}")
    try:
        metrics = assemble_evidence(
            matrix=load_matrix(),
            output_dir=staging,
            release_id=release_id,
            base_commit=base_commit,
            candidate_commit=candidate,
            engineering_started_at=engineering_started_at,
            execution_manifest_path=manifest_path,
        )
        trusted.assert_clean_detached_candidate(
            candidate, allowed_untracked_roots=(run_root, staging)
        )
        staging.rename(output)
        return metrics
    except Exception:
        if staging.exists():
            shutil.rmtree(staging)
        raise


def _release_id(value: str) -> str:
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,79}", value):
        raise argparse.ArgumentTypeError(
            "release ID must be 3-80 lowercase letters, digits, dots, underscores, or hyphens"
        )
    return value


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Atomically assemble the exact Phase 5 Batch 3 candidate evidence bundle."
    )
    parser.add_argument("--release-id", required=True, type=_release_id)
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--engineering-started-at", required=True)
    parser.add_argument("--execution-manifest", required=True, type=Path)
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="Defaults to test-reports/temporal-first/<release-id>/phase-5.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    output_dir = (
        arguments.output_dir
        or ROOT / "test-reports" / "temporal-first" / arguments.release_id / "phase-5"
    )
    try:
        metrics = generate_evidence(
            release_id=arguments.release_id,
            candidate_commit=arguments.candidate_commit.strip().lower(),
            base_commit=arguments.base_commit.strip().lower(),
            engineering_started_at=arguments.engineering_started_at,
            execution_manifest_path=arguments.execution_manifest,
            output_dir=output_dir,
        )
    except (EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 5 candidate evidence rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": metrics["candidate_commit"],
                "evidence_dir": str(output_dir.resolve()),
                **PASS_STATUS,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
