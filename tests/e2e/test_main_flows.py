import json
import os
import time
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


def test_three_main_intake_paths_are_accepted_through_nginx() -> None:
    require_gateway()
    fixtures = json.loads(
        (ROOT / "tests/fixtures/case_payloads.json").read_text(encoding="utf-8")
    )

    created_ids: list[str] = []
    for name, payload in fixtures.items():
        status, response = request(
            "POST",
            "/api/v1/cases",
            payload={
                **payload,
                "order_id": f"{payload['order_id']}_{int(time.time() * 1000)}",
            },
            headers={"Idempotency-Key": f"e2e-{name}-{int(time.time() * 1000)}"},
        )
        assert status == 201, response
        assert response["success"] is True
        assert response["data"]["id"].startswith("CASE_")
        created_ids.append(response["data"]["id"])

    for case_id in created_ids:
        status, response = request("GET", f"/api/v1/cases/{case_id}")
        assert status == 200, response
        assert response["data"]["id"] == case_id


def test_repository_e2e_flow_coverage_is_not_only_happy_path() -> None:
    java_tests = "\n".join(
        [
            (ROOT / "java-api-service/src/test/java/com/example/dispute/router/RouterApiIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/remedy/RemedyApplicationServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/review/ReviewApplicationServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/executor/ToolExecutorServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/evaluation/CaseClosureServiceIntegrationTest.java").read_text(encoding="utf-8"),
            (ROOT / "java-api-service/src/test/java/com/example/dispute/workflow/CaseFulfillmentDisputeWorkflowTest.java").read_text(encoding="utf-8"),
        ]
    )

    for required in (
        "REGULAR_FULFILLMENT",
        "RULE_BASED_RESOLUTION",
        "DISPUTE_HEARING",
        "requires_human_review",
        "unapproved",
        "closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation",
    ):
        assert required in java_tests
