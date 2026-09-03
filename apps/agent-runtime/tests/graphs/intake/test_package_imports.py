from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys


_SERVICE_ROOT = Path(__file__).resolve().parents[3]


def _run_fresh_interpreter(source: str) -> None:
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    completed = subprocess.run(
        [sys.executable, "-c", source],
        cwd=_SERVICE_ROOT,
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr


def test_dossier_first_import_succeeds_in_a_fresh_interpreter() -> None:
    _run_fresh_interpreter(
        """
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CaseDetailDossierSkill,
)
from app.agents.dispute_intake_officer.case_fact_matrix import (
    finalize_case_fact_matrix,
)

assert isinstance(CaseDetailDossierSkill, type)
assert callable(finalize_case_fact_matrix)
"""
    )


def test_leaf_contract_import_does_not_initialize_runtime_modules() -> None:
    _run_fresh_interpreter(
        """
import sys

from app.graphs.intake.contracts import IntakeTurnProposal

assert isinstance(IntakeTurnProposal, type)
for module_name in (
    "app.graphs.intake.graph",
    "app.graphs.intake.lcel",
    "app.graphs.intake.runtime",
):
    assert module_name not in sys.modules, module_name
"""
    )


def test_public_exports_resolve_lazily_to_canonical_symbols() -> None:
    _run_fresh_interpreter(
        """
import app.graphs.intake as intake

assert "app.graphs.intake.graph" not in __import__("sys").modules

from app.graph_runtime.intake_binding import build_governed_intake_runtime
from app.graphs.intake.graph import build_intake_v2_graph, compile_intake_v2_graph
from app.graphs.intake.runtime import IntakeRuntimeBundle

assert intake.build_intake_v2_graph is build_intake_v2_graph
assert intake.compile_intake_v2_graph is compile_intake_v2_graph
assert intake.build_governed_intake_runtime is build_governed_intake_runtime
assert intake.GovernedIntakeRuntime is IntakeRuntimeBundle
assert intake.IntakeRuntimeBundle is IntakeRuntimeBundle
"""
    )
