"""Instruction layering that keeps untrusted case data below policy."""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, ConfigDict


class InstructionLayer(StrEnum):
    PLATFORM_POLICY = "PLATFORM_POLICY"
    AGENT_IDENTITY = "AGENT_IDENTITY"
    WORKFLOW = "WORKFLOW"
    TASK = "TASK"
    CASE_DATA = "CASE_DATA"
    USER = "USER"


class InstructionSegment(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    layer: InstructionLayer
    content: str
    trusted_instruction: bool


class InstructionBundle(BaseModel):
    """Ordered instructions plus data boundaries for one Agent call."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    platform_policy: str
    agent_identity: str
    workflow_instruction: str
    task_instruction: str
    case_data: str
    user_instruction: str

    def layers(self) -> tuple[InstructionSegment, ...]:
        return (
            InstructionSegment(
                layer=InstructionLayer.PLATFORM_POLICY,
                content=self.platform_policy,
                trusted_instruction=True,
            ),
            InstructionSegment(
                layer=InstructionLayer.AGENT_IDENTITY,
                content=self.agent_identity,
                trusted_instruction=True,
            ),
            InstructionSegment(
                layer=InstructionLayer.WORKFLOW,
                content=self.workflow_instruction,
                trusted_instruction=True,
            ),
            InstructionSegment(
                layer=InstructionLayer.TASK,
                content=self.task_instruction,
                trusted_instruction=True,
            ),
            InstructionSegment(
                layer=InstructionLayer.CASE_DATA,
                content=self.case_data,
                trusted_instruction=False,
            ),
            InstructionSegment(
                layer=InstructionLayer.USER,
                content=self.user_instruction,
                trusted_instruction=False,
            ),
        )

    def render(self) -> str:
        rendered: list[str] = []
        for segment in self.layers():
            if segment.layer is InstructionLayer.CASE_DATA:
                rendered.append(
                    "<UNTRUSTED_CASE_DATA>\n"
                    + segment.content
                    + "\n</UNTRUSTED_CASE_DATA>"
                )
            elif segment.layer is InstructionLayer.USER:
                rendered.append(
                    "<UNTRUSTED_USER_CONTENT>\n"
                    + segment.content
                    + "\n</UNTRUSTED_USER_CONTENT>"
                )
            else:
                rendered.append(
                    f"<{segment.layer.value}>\n"
                    f"{segment.content}\n"
                    f"</{segment.layer.value}>"
                )
        return "\n\n".join(rendered)
