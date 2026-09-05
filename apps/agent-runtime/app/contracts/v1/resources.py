"""Locate the same contract inventory in a source checkout or an explicit deployment mount."""

from pathlib import Path


def resolve_contract_root(module_file: str, configured_root: str | None) -> Path:
    if configured_root is not None:
        root = Path(configured_root)
        if not configured_root.strip() or not root.is_absolute():
            raise ValueError("AGENT_CONTRACT_ROOT must be an absolute contract directory")
    else:
        parents = Path(module_file).resolve().parents
        if len(parents) <= 4:
            raise ValueError("AGENT_CONTRACT_ROOT is required outside the source checkout")
        root = parents[4] / "contracts" / "agent-platform" / "v1"
    if not root.is_dir() or not (root / "compatibility-matrix.yaml").is_file():
        raise ValueError("agent protocol contract inventory is missing")
    return root.resolve()
