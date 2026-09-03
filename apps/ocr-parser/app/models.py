# 文件作用：OCR 解析服务代码文件，负责证据文件解析、外部调用、数据模型或接口处理。

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class ParseTaskCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    evidence_id: str = Field(pattern=r"^EVIDENCE_[A-Za-z0-9_-]{1,119}$")
    case_id: str = Field(pattern=r"^CASE_[A-Za-z0-9_-]{1,119}$")
    bucket: str = Field(min_length=3, max_length=128)
    object_key: str = Field(min_length=3, max_length=512)
    content_type: str = Field(min_length=3, max_length=128)
    callback_url: str | None = Field(
        default=None,
        min_length=8,
        max_length=512,
        pattern=r"^https?://",
    )


class ParsedDocument(BaseModel):
    text: str
    metadata: dict[str, Any] = Field(default_factory=dict)


class ParseTaskView(BaseModel):
    task_id: str
    evidence_id: str
    case_id: str
    status: Literal["PENDING", "PROCESSING", "SUCCEEDED", "FAILED"]
    text: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    error_code: str | None = None
    error_message: str | None = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class TaskRecord(BaseModel):
    view: ParseTaskView
    request: ParseTaskCreate
