from __future__ import annotations

import hashlib
import json
import re
import uuid
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse

import pytest


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = ROOT / "contracts/agent-platform/v1"
FIXTURE_ROOT = CONTRACT_ROOT / "fixtures"
EXPECTED_SCHEMAS = {
    "agent-execution-manifest.schema.json",
    "agent-stream-event.schema.json",
    "artifact-ref.schema.json",
    "case-command-ref.schema.json",
    "process-projection.schema.json",
    "room-graph-command.schema.json",
    "room-graph-result.schema.json",
}
SUPPORTED_SCHEMA_KEYWORDS = {
    "$schema",
    "$id",
    "$defs",
    "$ref",
    "title",
    "description",
    "type",
    "const",
    "enum",
    "required",
    "properties",
    "additionalProperties",
    "items",
    "minItems",
    "maxItems",
    "uniqueItems",
    "minLength",
    "maxLength",
    "pattern",
    "format",
    "minimum",
    "maximum",
    "minProperties",
    "maxProperties",
    "oneOf",
    "anyOf",
    "allOf",
    "not",
}


class ContractValidationError(ValueError):
    pass


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _schema_paths() -> list[Path]:
    return sorted(CONTRACT_ROOT.glob("*.schema.json"))


def _resolve_ref(root_schema: dict, reference: str) -> dict:
    if not reference.startswith("#/"):
        raise AssertionError(
            f"P0.1 fixture validator only permits local refs: {reference}"
        )
    current: object = root_schema
    for raw_part in reference[2:].split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or part not in current:
            raise AssertionError(f"unresolved schema ref: {reference}")
        current = current[part]
    assert isinstance(current, dict)
    return current


def _matches_type(instance: object, expected: str) -> bool:
    if expected == "object":
        return isinstance(instance, dict)
    if expected == "array":
        return isinstance(instance, list)
    if expected == "string":
        return isinstance(instance, str)
    if expected == "integer":
        return isinstance(instance, int) and not isinstance(instance, bool)
    if expected == "number":
        return isinstance(instance, (int, float)) and not isinstance(instance, bool)
    if expected == "boolean":
        return isinstance(instance, bool)
    if expected == "null":
        return instance is None
    raise AssertionError(f"unsupported JSON Schema type: {expected}")


def _validate_format(value: str, format_name: str, path: str) -> None:
    if format_name == "date-time":
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ContractValidationError(f"{path}: invalid date-time") from exc
        if parsed.tzinfo is None:
            raise ContractValidationError(f"{path}: date-time must include an offset")
        return
    if format_name == "uuid":
        try:
            uuid.UUID(value)
        except ValueError as exc:
            raise ContractValidationError(f"{path}: invalid uuid") from exc
        return
    if format_name == "uri":
        parsed = urlparse(value)
        if not parsed.scheme:
            raise ContractValidationError(f"{path}: invalid uri")
        return
    raise AssertionError(f"unsupported JSON Schema format: {format_name}")


def _validate(
    instance: object, schema: dict, root_schema: dict, path: str = "$"
) -> None:
    if "$ref" in schema:
        _validate(
            instance, _resolve_ref(root_schema, schema["$ref"]), root_schema, path
        )
        return

    if "allOf" in schema:
        for child in schema["allOf"]:
            _validate(instance, child, root_schema, path)
    if "anyOf" in schema:
        matches = 0
        for child in schema["anyOf"]:
            try:
                _validate(instance, child, root_schema, path)
                matches += 1
            except ContractValidationError:
                pass
        if matches == 0:
            raise ContractValidationError(f"{path}: no anyOf branch matched")
    if "oneOf" in schema:
        matches = 0
        for child in schema["oneOf"]:
            try:
                _validate(instance, child, root_schema, path)
                matches += 1
            except ContractValidationError:
                pass
        if matches != 1:
            raise ContractValidationError(
                f"{path}: expected one oneOf branch, got {matches}"
            )
    if "not" in schema:
        forbidden_matched = True
        try:
            _validate(instance, schema["not"], root_schema, path)
        except ContractValidationError:
            forbidden_matched = False
        if forbidden_matched:
            raise ContractValidationError(f"{path}: forbidden schema matched")

    if "const" in schema and instance != schema["const"]:
        raise ContractValidationError(f"{path}: value does not match const")
    if "enum" in schema and instance not in schema["enum"]:
        raise ContractValidationError(f"{path}: value is not in enum")

    expected_type = schema.get("type")
    if expected_type is not None and not _matches_type(instance, expected_type):
        raise ContractValidationError(f"{path}: expected {expected_type}")

    if isinstance(instance, dict):
        required = schema.get("required", [])
        missing = [name for name in required if name not in instance]
        if missing:
            raise ContractValidationError(f"{path}: missing required fields {missing}")
        properties = schema.get("properties", {})
        additional = schema.get("additionalProperties", True)
        for name, value in instance.items():
            if name in properties:
                _validate(value, properties[name], root_schema, f"{path}.{name}")
            elif additional is False:
                raise ContractValidationError(f"{path}: unexpected field {name}")
            elif isinstance(additional, dict):
                _validate(value, additional, root_schema, f"{path}.{name}")
        if len(instance) < schema.get("minProperties", 0):
            raise ContractValidationError(f"{path}: too few properties")
        if len(instance) > schema.get("maxProperties", len(instance)):
            raise ContractValidationError(f"{path}: too many properties")

    if isinstance(instance, list):
        if len(instance) < schema.get("minItems", 0):
            raise ContractValidationError(f"{path}: too few items")
        if len(instance) > schema.get("maxItems", len(instance)):
            raise ContractValidationError(f"{path}: too many items")
        if schema.get("uniqueItems"):
            encoded = [
                json.dumps(item, sort_keys=True, ensure_ascii=False)
                for item in instance
            ]
            if len(encoded) != len(set(encoded)):
                raise ContractValidationError(f"{path}: duplicate array item")
        if "items" in schema:
            for index, value in enumerate(instance):
                _validate(value, schema["items"], root_schema, f"{path}[{index}]")

    if isinstance(instance, str):
        if len(instance) < schema.get("minLength", 0):
            raise ContractValidationError(f"{path}: string is too short")
        if len(instance) > schema.get("maxLength", len(instance)):
            raise ContractValidationError(f"{path}: string is too long")
        if "pattern" in schema and re.search(schema["pattern"], instance) is None:
            raise ContractValidationError(f"{path}: string does not match pattern")
        if "format" in schema:
            _validate_format(instance, schema["format"], path)

    if isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if instance < schema.get("minimum", instance):
            raise ContractValidationError(f"{path}: number is below minimum")
        if instance > schema.get("maximum", instance):
            raise ContractValidationError(f"{path}: number is above maximum")


def _assert_supported_schema(schema: dict) -> None:
    unknown = set(schema) - SUPPORTED_SCHEMA_KEYWORDS
    assert not unknown, f"schema uses untested keywords: {sorted(unknown)}"
    for child in schema.get("$defs", {}).values():
        _assert_supported_schema(child)
    for child in schema.get("properties", {}).values():
        _assert_supported_schema(child)
    additional = schema.get("additionalProperties")
    if isinstance(additional, dict):
        _assert_supported_schema(additional)
    if isinstance(schema.get("items"), dict):
        _assert_supported_schema(schema["items"])
    for keyword in ("oneOf", "anyOf", "allOf"):
        for child in schema.get(keyword, []):
            _assert_supported_schema(child)
    if isinstance(schema.get("not"), dict):
        _assert_supported_schema(schema["not"])


def _restricted_jcs(value: object) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        raise AssertionError(
            "P0.1 canonical fixtures intentionally exclude IEEE-754 edge cases"
        )
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(_restricted_jcs(item) for item in value) + "]"
    if isinstance(value, dict):
        assert all(isinstance(key, str) and key.isascii() for key in value)
        return (
            "{"
            + ",".join(
                f"{_restricted_jcs(key)}:{_restricted_jcs(value[key])}"
                for key in sorted(value)
            )
            + "}"
        )
    raise AssertionError(f"unsupported canonical fixture value: {type(value)}")


def test_contract_schema_set_and_root_boundaries() -> None:
    paths = _schema_paths()
    assert {path.name for path in paths} == EXPECTED_SCHEMAS
    for path in paths:
        schema = _load(path)
        assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
        assert schema["$id"].endswith(f"/agent-platform/v1/{path.name}")
        assert schema["type"] == "object"
        assert schema["additionalProperties"] is False
        assert schema["required"]
        assert schema["properties"]
        _assert_supported_schema(schema)


def test_valid_and_invalid_contract_fixtures() -> None:
    schemas = {path.name: _load(path) for path in _schema_paths()}
    valid_paths = sorted((FIXTURE_ROOT / "valid").glob("*.json"))
    invalid_paths = sorted((FIXTURE_ROOT / "invalid").glob("*.json"))
    assert valid_paths
    assert invalid_paths

    valid_coverage: set[str] = set()
    invalid_coverage: set[str] = set()
    for path in valid_paths:
        fixture = _load(path)
        assert set(fixture) == {"case", "schema", "instance"}
        schema_name = fixture["schema"]
        _validate(fixture["instance"], schemas[schema_name], schemas[schema_name])
        valid_coverage.add(schema_name)

    for path in invalid_paths:
        fixture = _load(path)
        assert set(fixture) == {"case", "schema", "instance", "expected_error"}
        schema_name = fixture["schema"]
        with pytest.raises(ContractValidationError):
            _validate(fixture["instance"], schemas[schema_name], schemas[schema_name])
        invalid_coverage.add(schema_name)

    assert valid_coverage == EXPECTED_SCHEMAS
    assert invalid_coverage == EXPECTED_SCHEMAS


def test_compatibility_matrix_covers_every_contract() -> None:
    matrix = _load(CONTRACT_ROOT / "compatibility-matrix.yaml")
    assert matrix["schema_version"] == "agent-platform-compatibility-matrix.v1"
    assert matrix["unknown_required_version"] == "REJECT"
    assert matrix["unknown_fields"] == "REJECT"
    rows = matrix["contracts"]
    assert {row["schema_file"] for row in rows} == EXPECTED_SCHEMAS
    valid_fixtures = {
        fixture["schema"]: fixture
        for fixture in (
            _load(path) for path in sorted((FIXTURE_ROOT / "valid").glob("*.json"))
        )
    }
    for row in rows:
        assert row["current_reader"] == "ACCEPT"
        assert row["future_reader"] == "REJECT"
        assert row["writer"] == "CURRENT_ONLY"
        assert row["additive_optional_fields"] == "REQUIRES_NEW_SCHEMA_VERSION"
        assert isinstance(row["max_serialized_bytes"], int)
        assert row["max_serialized_bytes"] > 0
        serialized = json.dumps(
            valid_fixtures[row["schema_file"]]["instance"],
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        assert len(serialized) <= row["max_serialized_bytes"]


def test_restricted_rfc8785_canonical_hash_vectors_are_fixed() -> None:
    paths = sorted((FIXTURE_ROOT / "canonical-hash").glob("*.json"))
    assert paths
    for path in paths:
        fixture = _load(path)
        canonical = _restricted_jcs(fixture["input"])
        assert canonical == fixture["canonical_utf8"]
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        assert digest == fixture["sha256"]
