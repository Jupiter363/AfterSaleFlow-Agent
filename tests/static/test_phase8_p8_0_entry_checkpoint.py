from __future__ import annotations

import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

from scripts import generate_phase8_entry_evidence as evidence_generator
from scripts import run_phase8_entry_checkpoint as entry_runner


ROOT = Path(__file__).resolve().parents[2]
A7 = "e3acedc64d161f0342c8db3d5c313c2f404ea462"
C8 = "74f4cb6bc2ac78f17aacdb36378e72ff650d60b6"
C8_TREE = "63c3e8259cd5fdc2bd0efd656a87d55f03ea87c7"
E8 = "3463e0cd774f80e452294fe32cf243bfa826eef0"
E8_TREE = "91de2f750a126e52747f39256bd54b02e1514477"
RELEASE_ID = "phase-8-entry-20260725-74f4cb6bc2ac"
EVIDENCE_PREFIX = f"test-reports/temporal-first/{RELEASE_ID}/phase-8-entry"
CHECKPOINT_PATH = "docs/runbooks/temporal-first/phase-8-p8.0-entry-checkpoint.md"
TEST_PATH = "tests/static/test_phase8_p8_0_entry_checkpoint.py"
SUPERSEDED_C8 = "6d4f9946ab357a7d3193ea1680473fe923322eb0"
SUPERSEDED_E8 = "4dc398d359806ab41ea702df54112956d17920ae"
SUPERSEDED_A8 = "7e3cbace3d206aef5eb23a03d36878a00634c9a9"
SUPERSEDED_A8_REF = "refs/tags/phase8-superseded-a8-7e3cbace"
SUPERSEDED_A8_TAG = "bd72fe8cc86e0383c645d069a04874a7eabc16ca"
ATTEMPT_LEDGER_SHA256 = (
    "86b6b3a4b1c5fe93b2a71d7295e5b81c58f08f7be9b5238df2bf1de9f57be61d"
)
MANIFEST_SELF_SEAL_SHA256 = (
    "311365dfb17e5006dc67278fa1a2ac5d06bcd7d055844e967442717c159f5975"
)
COMMAND_ARGV_SHA256 = "e911ba1f46fb67970a4e83141f0b2ffe2c239547456326b4f985b9d3cb38c055"

C8_ALLOWLIST = {
    "plans/temporal-langgraph-room-refactor.md": "M",
    "plans/phase-8-production-hardening-execution.md": "A",
    "plans/phase-8-production-hardening-test-batches.yaml": "A",
    "plans/phase-8-owner-briefs.yaml": "A",
    "docs/runbooks/temporal-first/phase-8-p8.0-baseline-inventory.md": "A",
    "docs/runbooks/temporal-first/phase-8-p8.0-contract-pack.md": "A",
    "docs/runbooks/temporal-first/phase-8-p8.0-review-closure.md": "A",
    "tests/static/test_phase8_production_hardening_plan.py": "A",
    "scripts/run_phase8_entry_checkpoint.py": "A",
    "scripts/generate_phase8_entry_evidence.py": "A",
    "tests/static/test_phase8_entry_runner.py": "A",
    "tests/static/test_phase8_entry_evidence.py": "A",
}
EVIDENCE_NAMES = {
    ".gitattributes",
    "artifact-sha256.json",
    "candidate.txt",
    "phase8-entry-execution-manifest.json",
    "static-phase8-entry.xml",
    "source-tree-environment.json",
    "p0-review-disposition.json",
    "phase8-entry-decision.json",
    "provenance-manifest.json",
    "p/00-stdout.log",
    "p/01-stderr.log",
    "p/02-junit.xml",
}
EVIDENCE_GIT_BLOBS = {
    ".gitattributes": "fd38c7f3a405823634fae4895a9fc49f6c7f952a",
    "artifact-sha256.json": "677203e8117651fa9bd5f0702f055426ea8c53c4",
    "candidate.txt": "b52b037ab247a03724663b18c0741e65b0084369",
    "p/00-stdout.log": "5bb59053b2dd1365a8c02b787a2f6b3e7a27c4b3",
    "p/01-stderr.log": "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391",
    "p/02-junit.xml": "aaec11e6f9a0393fb8cf25d4718f3923aa26241c",
    "p0-review-disposition.json": "d6b09985af02112c6ea242d24a0842e892a1fe7b",
    "phase8-entry-decision.json": "fdc193e8413b13074907e501322571cce8a379f5",
    "phase8-entry-execution-manifest.json": (
        "a68d308216de50e92713652c33b45037536c81f3"
    ),
    "provenance-manifest.json": "b93074d4c879938417494de7df06787a019699a5",
    "source-tree-environment.json": "9e90ed9b76ae3ae422a0df80bd042aa768c0fe69",
    "static-phase8-entry.xml": "55ece14b5088b3c23da13c8e9cc7b24908a7688d",
}
EVIDENCE_SHA256 = {
    ".gitattributes": (
        "3e5e82fc72e044ea9af807a2030b73dbb94800d2cd1775302063b2eee761ba1e"
    ),
    "artifact-sha256.json": (
        "f77f781a480617e8567ef57a11348fce88a9539a558bffa6af5730a4168fcc8d"
    ),
    "candidate.txt": (
        "702a264c1ba33d16c63b3726760d8de4bc814a1f1927562cba4a87a69baf163d"
    ),
    "p/00-stdout.log": (
        "ee7f59fe61df9c3dd8cb2c442811cdf5604e0b17ae3bb7d7de32d9642af8b67e"
    ),
    "p/01-stderr.log": (
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    ),
    "p/02-junit.xml": (
        "45e1430a77cf6bf5ed58e27ecd4426b52cfdd2cf1c60e6f69aef06f94b7441aa"
    ),
    "p0-review-disposition.json": (
        "bfa6c3f72e150385efb0c9e9830e0c1888faeb0d3d58a609c8f4576230fc10a4"
    ),
    "phase8-entry-decision.json": (
        "43f8a4eb6e11930d3282a67c365f1752ead54996c197da638622ad2332c96be3"
    ),
    "phase8-entry-execution-manifest.json": (
        "7f4a939dbfb051d53b5e47266c7b567bb66dd44750eb07cdc89a71f0d8e73626"
    ),
    "provenance-manifest.json": (
        "201025d2bfb66abdc5f6ff5bf84951d588053bb74a82680ab4255ad10e3ce494"
    ),
    "source-tree-environment.json": (
        "b45eaf9f6450fda4dc0dac2f3dc5b8e457c10c0816d9140e89a959ac67250fd5"
    ),
    "static-phase8-entry.xml": (
        "992918edf2da7c11674c7df83c10dc2a716098f55a723bb77a46ccf6114fe340"
    ),
}
P0_TOPICS = [
    "P0-P8-HANDOFF-001",
    "P0-P8-ENTRY-TOPOLOGY-002",
    "P0-P8-BASELINE-003",
    "P0-P8-REFERENCE-004",
    "P0-P8-SCHEDULER-005",
    "P0-P8-V046-006",
    "P0-P8-V047-007",
    "P0-P8-RELEASE-008",
    "P0-P8-RECOVERY-009",
    "P0-P8-PRIVACY-010",
    "P0-P8-TEAM-011",
    "P0-P8-TEST-LIMITS-012",
    "P0-P8-AUTHORITY-013",
]
RUNTIME_RESTRICTIONS = {
    "canary",
    "formal_business_authority",
    "implementation_authorized",
    "production_access",
    "production_apply_or_switch",
    "production_chaos",
    "production_dr",
    "production_load",
    "production_pitr",
    "production_rotation",
    "production_soak",
    "production_traffic",
    "promotion",
    "real_case_or_party_data",
    "real_tool_effect",
    "scheduler_off_activation",
    "temporal_outcome_allocation",
    "v047_authoring",
}


def _git(*arguments: str) -> str:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return completed.stdout.strip()


def _git_bytes(*arguments: str) -> bytes:
    return subprocess.run(
        ["git", *arguments], cwd=ROOT, check=True, capture_output=True
    ).stdout


def _blob(relative: str) -> bytes:
    return _git_bytes("show", f"{E8}:{EVIDENCE_PREFIX}/{relative}")


def _json(relative: str) -> dict:
    document = json.loads(_blob(relative))
    assert isinstance(document, dict)
    return document


def _canonical_json_bytes(document: object) -> bytes:
    return (
        json.dumps(
            document,
            ensure_ascii=True,
            indent=2,
            sort_keys=True,
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _changed(commit: str) -> dict[str, str]:
    records: dict[str, str] = {}
    for line in _git(
        "diff-tree",
        "--no-commit-id",
        "--name-status",
        "-r",
        "--no-renames",
        commit,
    ).splitlines():
        status, path = line.split("\t", 1)
        records[path.replace("\\", "/")] = status
    return records


def _tree_entries(commit: str, prefix: str) -> dict[str, tuple[str, str, str]]:
    entries: dict[str, tuple[str, str, str]] = {}
    for record in _git_bytes("ls-tree", "-r", "-z", commit, "--", prefix).split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, object_type, object_id = metadata.decode("ascii").split(" ", 2)
        entries[raw_path.decode("utf-8").replace("\\", "/")] = (
            mode,
            object_type,
            object_id,
        )
    return entries


def _acceptance_commit() -> str:
    commits = _git(
        "log", "--diff-filter=A", "--format=%H", "--", CHECKPOINT_PATH
    ).splitlines()
    assert len(commits) == 1
    return commits[0]


def test_c8_and_e8_exact_chain_and_scopes_are_immutable() -> None:
    assert _git("rev-list", "--parents", "-n", "1", C8).split() == [C8, A7]
    assert _git("show", "-s", "--format=%T", C8) == C8_TREE
    assert _changed(C8) == C8_ALLOWLIST
    for path in C8_ALLOWLIST:
        mode, object_type, _object_id = _git("ls-tree", C8, "--", path).split(None, 2)
        assert (mode, object_type) == ("100644", "blob")

    assert _git("rev-list", "--parents", "-n", "1", E8).split() == [E8, C8]
    assert _git("show", "-s", "--format=%T", E8) == E8_TREE
    assert _changed(E8) == {f"{EVIDENCE_PREFIX}/{name}": "A" for name in EVIDENCE_NAMES}


def test_a8_is_checkpoint_only_and_exact_chain_is_immutable() -> None:
    acceptance = _acceptance_commit()
    assert _git("rev-list", "--parents", "-n", "1", acceptance).split() == [
        acceptance,
        E8,
    ]
    assert _changed(acceptance) == {CHECKPOINT_PATH: "A", TEST_PATH: "A"}
    for path in (CHECKPOINT_PATH, TEST_PATH):
        mode, object_type, _object_id = _git("ls-tree", acceptance, "--", path).split(
            None, 2
        )
        assert (mode, object_type) == ("100644", "blob")


def test_superseded_a8_chain_remains_reachable_but_has_historical_authority_only() -> (
    None
):
    assert _git("cat-file", "-t", SUPERSEDED_A8_REF) == "tag"
    assert _git("rev-parse", SUPERSEDED_A8_REF) == SUPERSEDED_A8_TAG
    assert _git("rev-parse", f"{SUPERSEDED_A8_REF}^{{commit}}") == SUPERSEDED_A8
    assert _git("rev-list", "--parents", "-n", "1", SUPERSEDED_C8).split() == [
        SUPERSEDED_C8,
        A7,
    ]
    assert _git("rev-list", "--parents", "-n", "1", SUPERSEDED_E8).split() == [
        SUPERSEDED_E8,
        SUPERSEDED_C8,
    ]
    assert _git("rev-list", "--parents", "-n", "1", SUPERSEDED_A8).split() == [
        SUPERSEDED_A8,
        SUPERSEDED_E8,
    ]
    assert _changed(SUPERSEDED_A8) == {CHECKPOINT_PATH: "A", TEST_PATH: "A"}

    text = (ROOT / CHECKPOINT_PATH).read_text(encoding="utf-8")
    for claim in (
        f"superseded_historical_C8: {SUPERSEDED_C8}",
        f"superseded_historical_E8: {SUPERSEDED_E8}",
        f"superseded_historical_A8: {SUPERSEDED_A8}",
        f"superseded_historical_ref: {SUPERSEDED_A8_REF}",
        "superseded_historical_ref_must_not_move: true",
        "superseded_historical_chain_authority: HISTORICAL_OLD_CONTRACT_ONLY",
        (
            "superseded_historical_chain_authorizes_replacement_contract_or_"
            "implementation: false"
        ),
    ):
        assert claim in text


def test_e8_has_exact_regular_bundle_and_index_binds_other_eleven() -> None:
    expected_paths = {f"{EVIDENCE_PREFIX}/{name}" for name in EVIDENCE_NAMES}
    entries = _tree_entries(E8, EVIDENCE_PREFIX)
    assert set(entries) == expected_paths
    assert entries == {
        f"{EVIDENCE_PREFIX}/{name}": ("100644", "blob", EVIDENCE_GIT_BLOBS[name])
        for name in EVIDENCE_NAMES
    }
    assert set(EVIDENCE_GIT_BLOBS) == set(EVIDENCE_SHA256) == EVIDENCE_NAMES
    for name, expected_sha256 in EVIDENCE_SHA256.items():
        assert hashlib.sha256(_blob(name)).hexdigest() == expected_sha256

    index = _json("artifact-sha256.json")
    assert set(index) == {"artifacts", "candidate_commit", "schema_version"}
    assert index["schema_version"] == "phase8-entry-artifact-index.v1"
    assert index["candidate_commit"] == C8
    artifacts = index["artifacts"]
    assert isinstance(artifacts, list) and len(artifacts) == 11
    indexed_names = [item["path"] for item in artifacts]
    assert len(indexed_names) == len(set(indexed_names))
    assert set(indexed_names) == EVIDENCE_NAMES - {"artifact-sha256.json"}
    for artifact in artifacts:
        assert set(artifact) == {"bytes", "path", "sha256"}
        payload = _blob(artifact["path"])
        assert artifact["bytes"] == len(payload)
        assert artifact["sha256"] == hashlib.sha256(payload).hexdigest()
        assert artifact["sha256"] == EVIDENCE_SHA256[artifact["path"]]


def test_e8_manifest_attempt_and_junit_are_exact_candidate_all_green() -> None:
    verified = evidence_generator.verify_evidence_commit(
        evidence_commit=E8,
        candidate_commit=C8,
        release_id=RELEASE_ID,
    )
    assert verified == {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "candidate_commit": C8,
        "decision_ceiling": "PASS_AWAITING_CHECKPOINT_A8",
        "evidence_commit": E8,
        "next_phase_permission": "PENDING_A8_CHECKPOINT",
        "sole_parent_verified": True,
        "status": "E8_VERIFIED_AWAITING_A8_CHECKPOINT",
    }

    manifest = _json("phase8-entry-execution-manifest.json")
    entry_runner._assert_manifest_seal(manifest)
    assert manifest["candidate_sha"] == manifest["candidate_commit"] == C8
    assert manifest["candidate_parent"] == A7
    assert manifest["candidate_tree_sha"] == C8_TREE
    assert manifest["release"] == RELEASE_ID
    assert manifest["accepted_phase_7_checkpoint_A7"] == A7
    assert manifest["status"] == entry_runner.GREEN_STATUS
    assert manifest["contract_gate"] == "P8.0_NOT_RUN"
    assert manifest["p8_0_contract_gate"] == "REMAINS_NOT_RUN_UNTIL_A8"
    assert manifest["implementation_authorized"] is False
    assert manifest["retry_count"] == 0
    assert manifest["resume_used"] is False
    assert manifest["report_reuse_used"] is False
    assert manifest["quarantine_used"] is False
    assert manifest["attempt_ledger"]["candidate_sha"] == C8
    assert manifest["attempt_ledger"]["attempt_number"] == 1
    assert manifest["attempt_ledger"]["sha256"] == ATTEMPT_LEDGER_SHA256
    assert manifest["manifest_sha256"] == MANIFEST_SELF_SEAL_SHA256
    assert set(manifest["production_capabilities"]) == set(
        entry_runner.PRODUCTION_CAPABILITY_KEYS
    )
    assert all(value is False for value in manifest["production_capabilities"].values())

    assert len(manifest["commands"]) == 1
    command = manifest["commands"][0]
    assert command["id"] == "static_phase8_entry"
    assert command["candidate_sha_before"] == command["candidate_sha_after"] == C8
    assert command["shell"] is False
    assert command["accepted"] is True
    assert command["exit_code"] == 0
    assert command["argv_sha256"] == COMMAND_ARGV_SHA256
    assert (
        command["normalized_report_sha256"]
        == EVIDENCE_SHA256["static-phase8-entry.xml"]
    )
    assert command["raw_report_sha256"] == EVIDENCE_SHA256["p/02-junit.xml"]
    assert {
        key: command[key] for key in ("tests", "failures", "errors", "skipped")
    } == {
        "tests": 113,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
    }

    report = ET.fromstring(_blob("static-phase8-entry.xml"))
    assert report.tag == "testsuites"
    assert report.attrib["candidate_commit"] == C8
    assert report.attrib["source_command_id"] == "static_phase8_entry"
    assert {
        key: int(report.attrib[key])
        for key in ("tests", "failures", "errors", "skipped")
    } == {
        "tests": 113,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
    }


def test_e8_p0_disposition_binds_three_independent_review_lanes() -> None:
    review_blob = _blob("p0-review-disposition.json")
    review = json.loads(review_blob)
    assert review["schema_version"] == "phase8-entry-p0-review-disposition.v1"
    assert review["candidate_commit"] == C8
    assert review["candidate_tree_sha"] == C8_TREE
    assert review["status"] == "ALL_P0_CLOSED"
    assert review["review_scope"] == "CONSOLIDATED_POST_INTEGRATION_P0_ONLY"
    assert review["independent_disposition"] is True
    assert review["self_approved"] is False
    assert review["open_p0_count"] == 0
    assert review["reviewed_topics"] == review["closed_finding_ids"] == P0_TOPICS

    reviewed_paths = review["reviewed_path_blobs"]
    assert [item["path"] for item in reviewed_paths] == list(C8_ALLOWLIST)
    for item in reviewed_paths:
        candidate_payload = _git_bytes("show", f"{C8}:{item['path']}")
        assert item["sha256"] == hashlib.sha256(candidate_payload).hexdigest()

    expected_sources = {
        name: hashlib.sha256(_blob(name)).hexdigest()
        for name in (
            "phase8-entry-execution-manifest.json",
            "static-phase8-entry.xml",
            "source-tree-environment.json",
            "p/00-stdout.log",
            "p/01-stderr.log",
            "p/02-junit.xml",
        )
    }
    assert review["source_artifact_sha256"] == expected_sources

    lanes = review["review_lanes"]
    assert [lane["lane"] for lane in lanes] == [
        "authority",
        "data_migration",
        "security_privacy",
    ]
    reviewers = [lane["reviewer_id"] for lane in lanes]
    assert len(set(reviewers)) == 3
    assert review["disposition_author_id"] not in reviewers
    for lane in lanes:
        assert lane["disposition"] == "ALL_P0_CLOSED"
        assert lane["open_p0_count"] == 0
        assert lane["self_approved"] is False
        receipt = lane["receipt"]
        assert receipt["candidate_commit"] == C8
        assert receipt["candidate_tree_sha"] == C8_TREE
        assert receipt["lane"] == lane["lane"]
        assert receipt["reviewer_id"] == lane["reviewer_id"]
        assert receipt["disposition"] == "ALL_P0_CLOSED"
        assert receipt["open_p0_count"] == 0
        assert receipt["self_approved"] is False
        assert (
            lane["review_receipt_sha256"]
            == hashlib.sha256(_canonical_json_bytes(receipt)).hexdigest()
        )


def test_e8_provenance_and_decision_preserve_pre_a8_authority_ceiling() -> None:
    provenance = _json("provenance-manifest.json")
    assert set(provenance) == {"artifacts", "candidate_commit", "schema_version"}
    assert provenance["schema_version"] == "phase8-entry-provenance-manifest.v1"
    assert provenance["candidate_commit"] == C8
    assert len(provenance["artifacts"]) == 6
    for artifact in provenance["artifacts"]:
        payload = _blob(artifact["archive_path"])
        digest = hashlib.sha256(payload).hexdigest()
        assert artifact["source_path"] == artifact["archive_path"]
        assert artifact["byte_identical"] is True
        assert artifact["source_bytes"] == artifact["archive_bytes"] == len(payload)
        assert artifact["source_sha256"] == artifact["archive_sha256"] == digest

    decision = _json("phase8-entry-decision.json")
    assert decision["schema_version"] == "phase8-entry-decision.v1"
    assert decision["candidate_commit"] == C8
    assert decision["release_id"] == RELEASE_ID
    assert (
        decision["result"]
        == decision["decision_ceiling"]
        == ("PASS_AWAITING_CHECKPOINT_A8")
    )
    assert decision["next_phase_permission"] == "PENDING_A8_CHECKPOINT"
    assert decision["p8_0_contract_gate"] == "PENDING_A8_CHECKPOINT"
    assert decision["implementation_authorized"] is False
    assert decision["production_checkpoint"] == "PENDING_EXTERNAL"
    assert decision["promotion_gate"] == "PENDING"
    assert decision["totals"]["tests"] == 113
    assert all(
        decision["totals"][key] == 0 for key in ("failures", "errors", "skipped")
    )
    assert set(decision["runtime_restrictions"]) == RUNTIME_RESTRICTIONS
    assert all(value is False for value in decision["runtime_restrictions"].values())
    for migration in ("MIG-006", "MIG-007", "MIG-008"):
        assert decision[migration] == "PENDING_PROMOTION"
    for name, digest in decision["artifacts"].items():
        assert digest == hashlib.sha256(_blob(name)).hexdigest()


def test_a8_records_engineering_entry_only_and_keeps_production_forbidden() -> None:
    text = (ROOT / CHECKPOINT_PATH).read_text(encoding="utf-8")
    normalized_text = " ".join(text.split())
    for claim in (
        C8,
        E8,
        RELEASE_ID,
        f"candidate_tree_sha: {C8_TREE}",
        f"attempt_ledger_sha256: {ATTEMPT_LEDGER_SHA256}",
        (
            "manifest_file_sha256: "
            f"{EVIDENCE_SHA256['phase8-entry-execution-manifest.json']}"
        ),
        f"manifest_self_seal_sha256: {MANIFEST_SELF_SEAL_SHA256}",
        (f"environment_file_sha256: {EVIDENCE_SHA256['source-tree-environment.json']}"),
        f"normalized_junit_sha256: {EVIDENCE_SHA256['static-phase8-entry.xml']}",
        f"raw_junit_sha256: {EVIDENCE_SHA256['p/02-junit.xml']}",
        f"command_argv_sha256: {COMMAND_ARGV_SHA256}",
        f"artifact_sha256_index_sha256: {EVIDENCE_SHA256['artifact-sha256.json']}",
        (
            "p0_review_disposition_sha256: "
            f"{EVIDENCE_SHA256['p0-review-disposition.json']}"
        ),
        (
            "phase8_entry_decision_sha256: "
            f"{EVIDENCE_SHA256['phase8-entry-decision.json']}"
        ),
        (f"provenance_manifest_sha256: {EVIDENCE_SHA256['provenance-manifest.json']}"),
        "P8.0: PASS",
        "contract_gate: P8.0 PASS",
        "entry_effect: P8_0_ENGINEERING_ENTRY_PASS",
        "engineering_execution: ALLOWED_WITHIN_PHASE_8_ENGINEERING_LANE",
        "implementation: ALLOWED_WITHIN_PHASE_8_ENGINEERING_LANE",
        "next_phase_permission: PHASE_8_ENGINEERING_ONLY",
        "production_checkpoint: PENDING_EXTERNAL",
        "production_authorization: FORBIDDEN",
        "promotion_gate: PENDING",
        "E8_regular_blob_count: 12",
        "artifact_sha256_index_entry_count: 11",
        "ALL_P0_CLOSED",
        "open_p0_count: 0",
        "PASS_AWAITING_CHECKPOINT_A8",
        "next_phase_permission: PENDING_A8_CHECKPOINT",
    ):
        assert claim in text
    for migration in range(3, 9):
        assert f"MIG-00{migration}: PENDING_PROMOTION" in text
    for forbidden in (
        "scheduler_OFF: FORBIDDEN",
        "production_V046_apply_or_switch: FORBIDDEN",
        "V047: FORBIDDEN",
        "real_case_or_party_data: FORBIDDEN",
        "production_load: FORBIDDEN",
        "production_chaos: FORBIDDEN",
        "production_PITR: FORBIDDEN",
        "production_DR: FORBIDDEN",
        "production_rotation: FORBIDDEN",
        "production_soak: FORBIDDEN",
        "production_traffic: FORBIDDEN",
        "canary: FORBIDDEN",
        "promotion: FORBIDDEN",
    ):
        assert forbidden in text
    assert "| **Total** | **113** | **0** | **0** | **0** | **0** |" in text
    assert (
        "It is not a production-readiness, release, migration, canary, or promotion decision."
        in normalized_text
    )
