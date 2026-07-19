from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Mapping

import rfc8785
from jsonschema import Draft202012Validator, FormatChecker
from pydantic import BaseModel

from app.contracts.v1.models import MODEL_BY_SCHEMA, StrictContractModel


class ContractCodec:
    def __init__(self, contract_root: Path) -> None:
        self._root = contract_root.resolve()
        matrix = self._load_json(self._root / "compatibility-matrix.yaml")
        rows = matrix.get("contracts")
        if not isinstance(rows, list):
            raise ValueError("contract compatibility matrix has no contracts list")

        self._limits: dict[str, int] = {}
        self._validators: dict[str, Draft202012Validator] = {}
        for row in rows:
            schema_file = row["schema_file"]
            if schema_file not in MODEL_BY_SCHEMA:
                raise ValueError(f"unknown contract schema in matrix: {schema_file}")
            limit = row["max_serialized_bytes"]
            if not isinstance(limit, int) or limit <= 0:
                raise ValueError(f"invalid max_serialized_bytes for {schema_file}")
            schema = self._load_json(self._root / schema_file)
            Draft202012Validator.check_schema(schema)
            self._limits[schema_file] = limit
            self._validators[schema_file] = Draft202012Validator(
                schema,
                format_checker=FormatChecker(),
            )

        if set(self._validators) != set(MODEL_BY_SCHEMA):
            raise ValueError("compatibility matrix and model registry differ")

    def decode(
        self,
        schema_file: str,
        payload: bytes | bytearray | str | Mapping[str, Any],
    ) -> StrictContractModel:
        model_type, instance = self._prepare(schema_file, payload)
        return model_type.model_validate(instance)

    def encode(self, schema_file: str, model: BaseModel) -> dict[str, Any]:
        model_type = self._model_type(schema_file)
        if not isinstance(model, model_type):
            raise ValueError(f"{schema_file} requires {model_type.__name__}")
        instance = model.model_dump(mode="json", exclude_none=True)
        _, validated = self._prepare(schema_file, instance)
        return validated

    def _prepare(
        self,
        schema_file: str,
        payload: bytes | bytearray | str | Mapping[str, Any],
    ) -> tuple[type[StrictContractModel], dict[str, Any]]:
        model_type = self._model_type(schema_file)
        if isinstance(payload, (bytes, bytearray)):
            raw = bytes(payload)
            if len(raw) > self._limits[schema_file]:
                raise ValueError(f"{schema_file} exceeds max_serialized_bytes")
            instance = json.loads(raw)
        elif isinstance(payload, str):
            raw = payload.encode("utf-8")
            if len(raw) > self._limits[schema_file]:
                raise ValueError(f"{schema_file} exceeds max_serialized_bytes")
            instance = json.loads(payload)
        else:
            instance = dict(payload)
            raw = json.dumps(
                instance,
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
            if len(raw) > self._limits[schema_file]:
                raise ValueError(f"{schema_file} exceeds max_serialized_bytes")

        if not isinstance(instance, dict):
            raise ValueError(f"{schema_file} payload must be an object")
        errors = sorted(
            self._validators[schema_file].iter_errors(instance),
            key=lambda error: tuple(str(part) for part in error.absolute_path),
        )
        if errors:
            first = errors[0]
            location = ".".join(str(part) for part in first.absolute_path) or "$"
            raise ValueError(f"{schema_file} invalid at {location}: {first.message}")
        return model_type, instance

    def _model_type(self, schema_file: str) -> type[StrictContractModel]:
        model_type = MODEL_BY_SCHEMA.get(schema_file)
        if model_type is None or schema_file not in self._validators:
            raise ValueError(f"unknown contract schema: {schema_file}")
        return model_type

    @staticmethod
    def _load_json(path: Path) -> dict[str, Any]:
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError(f"contract document must be an object: {path}")
        return value


def canonicalize(value: Any) -> bytes:
    return rfc8785.dumps(value)


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonicalize(value)).hexdigest()


def canonical_sha256_omitting(value: BaseModel | Mapping[str, Any], member: str) -> str:
    """Hash a contract object after omitting one top-level self-hash member."""

    if isinstance(value, BaseModel):
        instance = value.model_dump(mode="json", exclude_none=True)
    else:
        instance = dict(value)
    if member not in instance:
        raise ValueError(f"self-hash member is missing: {member}")
    del instance[member]
    return canonical_sha256(instance)
