"""Compatibility import for the dispute intake officer room workflow.

The actual digital-human workflow belongs to the agent package:
``app.agents.dispute_intake_officer.workflow``.
"""

from app.agents.dispute_intake_officer.workflow import (  # noqa: F401
    IntakeTurnGraphState,
    IntakeTurnWorkflow,
    build_intake_turn_graph,
)
