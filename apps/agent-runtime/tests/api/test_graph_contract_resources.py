from pathlib import Path

import pytest

import app.api.graph_lifecycle as lifecycle
from app.contracts.v1.codec import ContractCodec
from app.contracts.v1.resources import resolve_contract_root


ROOT = Path(__file__).resolve().parents[4]
CONTRACTS = ROOT / "contracts/agent-platform/v1"


def test_source_checkout_loads_the_complete_existing_contract_inventory(monkeypatch):
    monkeypatch.delenv("AGENT_CONTRACT_ROOT", raising=False)
    lifecycle._contract_codec.cache_clear()
    try:
        assert resolve_contract_root(lifecycle.__file__, None) == CONTRACTS
        assert isinstance(lifecycle._contract_codec(), ContractCodec)
    finally:
        lifecycle._contract_codec.cache_clear()


def test_shallow_container_layout_uses_explicit_mount_before_parent_indexing(monkeypatch):
    monkeypatch.setattr(lifecycle, "__file__", "/app/app/api/graph_lifecycle.py")
    monkeypatch.setenv("AGENT_CONTRACT_ROOT", str(CONTRACTS))
    lifecycle._contract_codec.cache_clear()
    try:
        assert isinstance(lifecycle._contract_codec(), ContractCodec)
        assert lifecycle._contract_codec() is lifecycle._contract_codec()
    finally:
        lifecycle._contract_codec.cache_clear()


@pytest.mark.parametrize("configured", ["", "contracts/agent-platform/v1"])
def test_explicit_invalid_mount_does_not_fall_back_to_repository(configured):
    with pytest.raises(ValueError, match="absolute"):
        resolve_contract_root(lifecycle.__file__, configured)


def test_missing_inventory_and_unconfigured_container_fail_closed(tmp_path):
    with pytest.raises(ValueError, match="inventory is missing"):
        resolve_contract_root(lifecycle.__file__, str(tmp_path))
    with pytest.raises(ValueError, match="required outside"):
        resolve_contract_root("/app/app/api/graph_lifecycle.py", None)
