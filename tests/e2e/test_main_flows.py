import json
import os
import urllib.error
import urllib.request
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
BASE_URL = os.getenv("ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080")


def request(method: str, path: str, *, payload: dict | None = None, headers: dict | None = None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
            "X-User-Id": "smoke-user",
            "X-Role": "USER",
            **(headers or {}),
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8")
        return error.code, json.loads(body) if body else {}


def require_gateway() -> None:
    try:
        with urllib.request.urlopen(BASE_URL + "/healthz", timeout=3) as response:
            if response.status != 200:
                raise OSError(f"unexpected status {response.status}")
    except OSError as exc:
        pytest.skip(f"local acceptance gateway is not running: {exc}")


def test_seeded_disputes_are_listed_and_enterable_through_nginx() -> None:
    require_gateway()
    status, response = request("GET", "/api/disputes?page=0&size=20")
    assert status == 200, response
    items = response["data"]["items"]
    assert items
    assert all(item["case_type"] == "DISPUTE" for item in items)

    for item in items:
        case_id = item["id"]
        status, response = request("GET", f"/api/disputes/{case_id}")
        assert status == 200, response
        assert response["data"]["id"] == case_id


def test_repository_e2e_flow_coverage_is_not_only_happy_path() -> None:
    java_tests = "\n".join(
        [
            (ROOT / "java-api-service/src/test/java/com/example/dispute/evidence/EvidenceRoomIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/hearing/HearingCollaborationIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/review/ReviewApplicationServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/executor/ToolExecutorServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/evaluation/CaseClosureServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/workflow/DisputeHearingWorkflowTest.java").read_text(encoding="utf-8"),
        ]
    )

    for required in (
        "BOTH_PARTIES_COMPLETED",
        "DEADLINE_EXPIRED",
        "SETTLEMENT_CONFIRMED",
        "PLATFORM_REVIEWER",
        "unapproved",
        "closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation",
    ):
        assert required in java_tests
