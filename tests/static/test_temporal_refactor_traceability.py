from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHECKLIST = (
    ROOT / "docs/acceptance/temporal-first-agent-platform-verification-checklist.md"
)
BASELINE = ROOT / "docs/acceptance/current-room-function-baseline.md"
CHECK_MANIFEST = ROOT / "tests/acceptance/temporal-first-check-manifest.yaml"
BASELINE_MANIFEST = ROOT / "tests/baseline/current-room-baseline.yaml"

CHECK_RE = re.compile(
    r"^- \[ \] `(?P<id>[^`]+)` \*\*(?P<priority>P[0-2])\*\* (?P<description>.+)$",
    re.MULTILINE,
)
BASELINE_RE = re.compile(
    r"^- `\[(?P<id>(?:OVR|SEC|CORE|UI|INT|EVD|HRG|DRF|REV|OUT)-\d{3})\]`",
    re.MULTILINE,
)
BASELINE_ENTRY_RE = re.compile(
    r"^- `\[(?P<id>(?:OVR|SEC|CORE|UI|INT|EVD|HRG|DRF|REV|OUT)-\d{3})\]` "
    r"(?P<first>[^\n]+)(?P<continuations>(?:\n  [^\n]+)*)",
    re.MULTILINE,
)
GAP_RE = re.compile(
    r"^\| `(?P<id>GAP-\d{3})` \| (?P<claim>[^|]+) \| (?P<fact>[^|]+) \|$",
    re.MULTILINE,
)

EXPECTED_GAP_DISPOSITIONS = {
    **{f"GAP-{number:03d}": "PRESERVE" for number in (1, 2, 3, 4, 5, 6, 11, 12)},
    **{f"GAP-{number:03d}": "FIX_WITH_DECISION" for number in (7, 8, 9, 10)},
}


def _load_json_compatible_yaml(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _assert_unique(values: list[str], label: str) -> None:
    duplicates = sorted(value for value, count in Counter(values).items() if count > 1)
    assert not duplicates, f"duplicate {label}: {duplicates}"


def test_architecture_check_manifest_is_complete_and_release_ready() -> None:
    source_text = CHECKLIST.read_text(encoding="utf-8")
    source = [match.groupdict() for match in CHECK_RE.finditer(source_text)]
    assert len(source) == 279

    document = _load_json_compatible_yaml(CHECK_MANIFEST)
    assert document["schema_version"] == "temporal-first-check-manifest.v1"
    assert document["source"] == CHECKLIST.relative_to(ROOT).as_posix()
    checks = document["checks"]
    ids = [item["id"] for item in checks]
    source_ids = [item["id"] for item in source]

    _assert_unique(ids, "Check ID")
    assert ids == source_ids
    assert len(checks) == 279

    priorities = {item["id"]: item["priority"] for item in source}
    descriptions = {item["id"]: item["description"].strip() for item in source}
    for item in checks:
        check_id = item["id"]
        assert set(item) == {
            "id",
            "priority",
            "phase",
            "owner_role",
            "description",
            "source",
            "status",
            "evidence_template",
        }
        assert item["priority"] == priorities[check_id]
        assert item["description"] == descriptions[check_id]
        assert re.fullmatch(r"P[0-8](?:-P[0-8])?(?:/P[0-8])?", item["phase"])
        assert item["owner_role"].strip()
        assert item["description"].strip()
        assert item["source"] == CHECKLIST.relative_to(ROOT).as_posix()
        assert item["status"] == "TODO"
        assert item["evidence_template"] == (
            f"test-reports/temporal-first/{{release-id}}/checks/{check_id}/"
        )


def test_current_room_baseline_and_gap_manifests_are_complete() -> None:
    source_text = BASELINE.read_text(encoding="utf-8")
    source_ids = [match.group("id") for match in BASELINE_RE.finditer(source_text)]
    source_entries = list(BASELINE_ENTRY_RE.finditer(source_text))
    gap_entries = list(GAP_RE.finditer(source_text))
    gap_ids = [match.group("id") for match in gap_entries]
    assert len(source_ids) == 99
    assert len(source_entries) == 99
    assert len(gap_ids) == 12

    document = _load_json_compatible_yaml(BASELINE_MANIFEST)
    assert document["schema_version"] == "current-room-baseline-manifest.v1"
    assert document["source"] == BASELINE.relative_to(ROOT).as_posix()

    behaviors = document["behaviors"]
    behavior_ids = [item["id"] for item in behaviors]
    _assert_unique(behavior_ids, "Baseline ID")
    assert behavior_ids == source_ids
    assert len(behaviors) == 99
    source_invariants = {}
    for match in source_entries:
        continuation = [
            line.strip()
            for line in match.group("continuations").splitlines()
            if line.strip()
        ]
        source_invariants[match.group("id")] = " ".join(
            [match.group("first").strip(), *continuation]
        )

    for item in behaviors:
        baseline_id = item["id"]
        assert set(item) == {
            "id",
            "phase",
            "owner_role",
            "current_invariant",
            "new_path",
            "automated_test",
            "manual_scenario",
            "result",
            "evidence_template",
            "behavior_change_approval",
        }
        for field in (
            "phase",
            "owner_role",
            "current_invariant",
            "new_path",
            "automated_test",
            "manual_scenario",
        ):
            assert item[field].strip(), f"{baseline_id}.{field} is empty"
        assert item["current_invariant"] == source_invariants[baseline_id]
        assert item["result"] == "TODO"
        assert item["evidence_template"] == (
            f"test-reports/temporal-first/{{release-id}}/baseline/{baseline_id}/"
        )
        expected_approval = (
            "APPROVED_2026-07-17_PROJECT_OWNER"
            if baseline_id == "EVD-004"
            else "NOT_REQUIRED_PRESERVE"
        )
        assert item["behavior_change_approval"] == expected_approval

    gaps = document["gaps"]
    manifest_gap_ids = [item["id"] for item in gaps]
    _assert_unique(manifest_gap_ids, "GAP ID")
    assert manifest_gap_ids == gap_ids
    assert len(gaps) == 12
    source_gap_facts = {
        match.group("id"): match.group("fact").strip() for match in gap_entries
    }
    for item in gaps:
        assert set(item) == {
            "id",
            "disposition",
            "phase",
            "owner_role",
            "current_fact",
            "decision",
            "status",
        }
        assert item["disposition"] == EXPECTED_GAP_DISPOSITIONS[item["id"]]
        assert item["phase"].strip()
        assert item["owner_role"].strip()
        assert item["current_fact"].strip()
        assert item["current_fact"] == source_gap_facts[item["id"]]
        assert item["decision"].strip()
        assert item["status"] in {"PRESERVED", "DECISION_ACCEPTED"}


def test_phase_zero_decisions_are_accepted_without_legacy_authority() -> None:
    adr_dir = ROOT / "docs/architecture/adr"
    adr_paths = sorted(adr_dir.glob("000[1-6]-*.md"))
    assert len(adr_paths) == 6
    texts = [path.read_text(encoding="utf-8") for path in adr_paths]
    combined = "\n".join(texts)

    for path, text in zip(adr_paths, texts, strict=True):
        assert "- Status: ACCEPTED" in text, f"{path.name} is not accepted"
        assert "- Approved by:" in text, f"{path.name} has no approval reference"
    forbidden_authority = "archive" + "/legacy-docs"
    assert forbidden_authority not in combined
    for number in range(1, 10):
        decision_id = f"D-{number:02d}"
        headings = re.findall(
            rf"^### {re.escape(decision_id)}\b", combined, re.MULTILINE
        )
        assert len(headings) == 1, f"{decision_id} must have one authoritative heading"
        section = re.search(
            rf"^### {re.escape(decision_id)}\b(?P<body>.*?)(?=^### |^## |\Z)",
            combined,
            re.MULTILINE | re.DOTALL,
        )
        assert section is not None
        assert "- Decision: ACCEPTED" in section.group("body")
        assert "Accountable role" in section.group("body")
        assert "Approval reference:" in section.group("body")
