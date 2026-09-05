"""Additive live-profile read contract; frozen v1 remains independently valid."""
import copy
import json
from pathlib import Path

import jsonschema
import pytest

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "contracts/agent-platform/evidence"


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def current_projection():
    value = load(EVIDENCE / "v2/fixtures/valid/evidence-process-projection-target-temporal-valid.json")
    value["schema_version"] = "evidence-process-projection.v2"
    value["version_pins"]["model_profile_id"] = "qwen3.8-flash.uat." + "a" * 64 + ".v1"
    value["version_pins"]["graph_version"] = "production-runtime-graph.2026-08-18.3"
    value["active_graph_run"]["graph_version"] = "production-runtime-graph.2026-08-18.3"
    return value


def test_live_projection_is_explicit_v2_not_a_relaxed_v1():
    old = load(EVIDENCE / "v2/evidence-process-projection.schema.json")
    new = load(EVIDENCE / "projection/v2/evidence-process-projection.schema.json")
    jsonschema.Draft202012Validator.check_schema(new)
    legacy = load(EVIDENCE / "v2/fixtures/valid/evidence-process-projection-target-temporal-valid.json")
    jsonschema.validate(legacy, old)
    live = current_projection()
    jsonschema.validate(live, new)
    replay = copy.deepcopy(live)
    jsonschema.validate(replay, new)
    assert replay == live
    live["schema_version"] = "evidence-process-projection.v1"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(live, old)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(live, new)


@pytest.mark.parametrize("field,value", [
    ("schema_version", "evidence-process-projection.v99"),
    ("writer_mode", "SHADOW"), ("formal_sink_allowed", True),
    ("graph_runtime_mode", "DISABLED"),
])
def test_v2_rejects_wrong_authority(field, value):
    projection = current_projection()
    projection[field] = value
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(projection, load(EVIDENCE / "projection/v2/evidence-process-projection.schema.json"))


@pytest.mark.parametrize("profile", [None, "", "model/name", "model name", "a" * 129])
def test_v2_rejects_invalid_profile(profile):
    projection = current_projection()
    projection["version_pins"]["model_profile_id"] = profile
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(projection, load(EVIDENCE / "projection/v2/evidence-process-projection.schema.json"))
