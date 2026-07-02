from typing import Any

import pytest
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.harness.profile import AgentProfile
from app.harness.skills import SkillDefinition, SkillRegistry
from app.harness.tool_gateway import (
    ToolAuthorizationError,
    ToolDefinition,
    ToolGateway,
    ToolRequest,
)
from tests.harness.test_profile import profile_payload


class EvidenceReadInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    evidence_id: str = Field(pattern=r"^EV-[0-9]+$")


def profile() -> AgentProfile:
    return AgentProfile.model_validate(profile_payload())


def request(tool_name: str, arguments: dict[str, Any]) -> ToolRequest:
    return ToolRequest(
        tool_name=tool_name,
        case_id="CASE-1",
        case_state="DOSSIER_BUILDING",
        agent_run_id="RUN-1",
        arguments=arguments,
        reason="build the evidence timeline",
        requested_fields=["evidence_id", "summary"],
    )


def test_gateway_enforces_profile_state_schema_redaction_and_audit() -> None:
    audits: list[dict[str, Any]] = []
    gateway = ToolGateway(audit_sink=audits.append)
    gateway.register(
        ToolDefinition(
            name="evidence.read",
            version="1",
            input_model=EvidenceReadInput,
            allowed_case_states={"DOSSIER_BUILDING"},
            output_fields={"evidence_id", "summary"},
            sensitive_output_fields={"raw_identity"},
            handler=lambda value: {
                "evidence_id": value.evidence_id,
                "summary": "parcel delivered",
                "raw_identity": "secret",
            },
        )
    )

    result = gateway.execute(
        profile(), request("evidence.read", {"evidence_id": "EV-7"})
    )

    assert result.status == "SUCCESS"
    assert result.data == {
        "evidence_id": "EV-7",
        "summary": "parcel delivered",
    }
    assert result.redactions == ("raw_identity",)
    assert result.audit_id.startswith("TOOL-AUDIT-")
    assert audits[0]["tool_name"] == "evidence.read"
    assert audits[0]["agent_run_id"] == "RUN-1"


def test_gateway_rejects_forbidden_unknown_wrong_state_and_invalid_input() -> None:
    gateway = ToolGateway()
    gateway.register(
        ToolDefinition(
            name="evidence.read",
            version="1",
            input_model=EvidenceReadInput,
            allowed_case_states={"DOSSIER_BUILDING"},
            output_fields={"evidence_id"},
            handler=lambda value: {"evidence_id": value.evidence_id},
        )
    )

    with pytest.raises(ToolAuthorizationError):
        gateway.execute(
            profile(), request("refund.execute", {"evidence_id": "EV-7"})
        )
    with pytest.raises(ToolAuthorizationError):
        gateway.execute(
            profile(), request("unknown.read", {"evidence_id": "EV-7"})
        )

    wrong_state = request("evidence.read", {"evidence_id": "EV-7"})
    wrong_state = wrong_state.model_copy(update={"case_state": "CLOSED"})
    with pytest.raises(ToolAuthorizationError):
        gateway.execute(profile(), wrong_state)

    with pytest.raises(ValidationError):
        invalid = request("evidence.read", {"evidence_id": "bad"})
        invalid = invalid.model_copy(
            update={"requested_fields": ("evidence_id",)}
        )
        gateway.execute(
            profile(), invalid
        )


def test_skill_registry_cannot_expand_profile_authority() -> None:
    registry = SkillRegistry()
    registry.register(
        SkillDefinition(
            code="timeline.build",
            version="1",
            allowed_agents={"evidence-clerk"},
            required_tools={"evidence.read"},
            input_schema="TimelineInput",
            output_schema="TimelineOutput",
        )
    )

    skill = registry.resolve(profile(), "timeline.build")
    assert skill.version == "1"

    unauthorized_payload = profile_payload()
    unauthorized_payload["allowed_tools"] = ["case.read"]
    unauthorized_profile = AgentProfile.model_validate(unauthorized_payload)
    with pytest.raises(ToolAuthorizationError):
        registry.resolve(unauthorized_profile, "timeline.build")
