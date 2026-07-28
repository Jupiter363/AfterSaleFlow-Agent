from __future__ import annotations

import hashlib
import inspect
from datetime import datetime, timedelta, timezone

import pytest

from app.contracts.v1.codec import canonicalize
from app.contracts.v1.models import SnapshotRef
from app.graph_runtime.intake_executor import CompiledIntakeGraphShadowExecutor
from app.graph_runtime.production_bindings import _build_target_e2e_room_providers
from app.graph_runtime.target_e2e_room_adapters import (
    TargetE2EOutcomeGraphProvider,
    TargetE2EIntakeProvider,
    TargetE2EObjectEvidenceAssetLoader,
)
from app.graph_runtime.target_e2e_fixture_transport import (
    TARGET_E2E_FIXTURE_MODEL,
    TARGET_E2E_FIXTURE_PROVIDER,
    TargetE2EDeterministicFixtureTransport,
)
from app.graphs.intake.contracts import IntakeCognitionDraft
from app.llm import GovernedProviderRequest
from app.model_runtime.transports import ModelTransportRequest


class _ObjectStore:
    def __init__(self, payload: bytes) -> None:
        self.payload = payload
        self.references: list[SnapshotRef] = []

    async def load(self, reference: SnapshotRef) -> bytes:
        self.references.append(reference)
        return self.payload

    async def put(self, **kwargs):
        raise AssertionError(kwargs)

    async def put_content_addressed(self, **kwargs):
        raise AssertionError(kwargs)


@pytest.mark.asyncio
async def test_evidence_asset_loader_reads_only_the_manifest_bound_parse_reference() -> None:
    document = {
        "schema_version": "target-e2e-evidence-asset.v1",
        "content": "inspected fixture",
        "source_refs": ["SOURCE_1"],
        "inspected_modalities": ["TEXT"],
        "receipt_ref": "RECEIPT_1",
        "receipt_hash": "1" * 64,
    }
    payload = canonicalize(document)
    store = _ObjectStore(payload)
    loader = TargetE2EObjectEvidenceAssetLoader(store)

    asset = await loader.load(
        {
            "evidence_id": "EVIDENCE_1",
            "parse_ref": "urn:synthetic-evidence-parse:fixture.json",
            "parse_hash": hashlib.sha256(payload).hexdigest(),
        }
    )

    assert asset.content == "inspected fixture"
    assert store.references[0].uri == "urn:synthetic-evidence-parse:fixture.json"
    assert store.references[0].sha256 == hashlib.sha256(payload).hexdigest()


def test_default_target_composite_requires_a_specialized_room_factory() -> None:
    source = inspect.getsource(_build_target_e2e_room_providers)

    assert "build_target_e2e_intake_provider" in source
    assert "TARGET_E2E_SPECIALIZED_ROOM_RUNTIME_REQUIRED" in source
    assert "specialized_provider_factory(kernel)" in source
    assert "for room_type in RoomType" not in source


def test_target_intake_uses_the_governed_executor_and_real_stored_object_uri() -> None:
    provider_source = inspect.getsource(TargetE2EIntakeProvider)
    executor_source = inspect.getsource(CompiledIntakeGraphShadowExecutor._target_proposal_source)

    assert "CompiledIntakeGraphShadowExecutor" in provider_source
    assert "proposal_id=f\"target-proposal.{stored.sha256[:32]}\"" in executor_source
    assert "payload_ref=f\"urn:target-e2e:proposal:intake:{stored.sha256}\"" in executor_source


def test_review_target_result_projects_the_exchange_persisted_proposal_as_a_patch() -> None:
    source = inspect.getsource(TargetE2EOutcomeGraphProvider.stream)

    assert 'operation="PROPOSE_PATCH"' in source
    assert "artifact_id=proposal.proposal_id" in source
    assert "schema_version=proposal.payload_schema_version" in source
    assert "uri=proposal.payload_ref" in source
    assert "sha256=proposal.payload_hash" in source


def test_target_fixture_transport_emits_a_valid_intake_cognition_draft_without_network() -> None:
    now = datetime.now(timezone.utc)
    transport = TargetE2EDeterministicFixtureTransport(
        activation_id="p9act.v1." + "a" * 32,
        fixture_set_id="fixture-set-1",
        fixture_set_hash="b" * 64,
        binding_hash="c" * 64,
        candidate_sha="d" * 40,
    )
    request = ModelTransportRequest(
        node_name="intake_lcel",
        messages=(),
        output_type=IntakeCognitionDraft,
        governed_request=GovernedProviderRequest(
            provider=TARGET_E2E_FIXTURE_PROVIDER,
            model=TARGET_E2E_FIXTURE_MODEL,
            temperature=0,
            max_output_tokens=1024,
            response_format="STRICT_JSON_SCHEMA",
            tool_allowlist=(),
            deadline_at=now + timedelta(minutes=1),
            provider_attempts_remaining=1,
            repairs_remaining=0,
        ),
    )

    result = transport.generate(request)

    assert result.model == TARGET_E2E_FIXTURE_MODEL
    assert IntakeCognitionDraft.model_validate_json(result.json_document).recommendation == "NEED_MORE_INFO"
    assert transport.fixture_binding_hash == transport.fixture_binding_hash
