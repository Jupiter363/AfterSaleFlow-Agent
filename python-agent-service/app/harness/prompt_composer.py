# 文件作用：按节点与角色 Profile 选择 Prompt 模板，并严格分离可信 system 指令、不可信案件数据和 Pydantic 输出 Schema。

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class PromptTemplateRef:
    """一个节点对应的提示词模板位置。"""

    agent_key: str
    filename: str


class PromptComposer:
    """提示词组装器。

    本项目的 system prompt 由两部分组成：
    - harness/prompts 下的通用规则：安全边界、JSON 输出、业务措辞等；
    - agents/prompts 下各 Agent 节点自己的专业提示词。

    user prompt 则由案件上下文和 required_output_schema 组成。
    这能让模型清楚知道“输入是什么、必须输出什么 JSON 结构”。
    """

    NODE_TEMPLATES: dict[str, PromptTemplateRef] = {
        # key 是 workflow 里使用的 node_name，value 是实际 Markdown 提示词文件。
        "intake_analyze": PromptTemplateRef(
            "dispute_intake_officer",
            "intake_analyze.md",
        ),
        "intake_turn_dialogue": PromptTemplateRef(
            "dispute_intake_officer",
            "intake_turn_dialogue.md",
        ),
        "intake_turn_case_detail": PromptTemplateRef(
            "dispute_intake_officer",
            "intake_turn_case_detail.md",
        ),
        "evidence_turn": PromptTemplateRef(
            "evidence_clerk",
            "evidence_turn.md",
        ),
        "evaluation_analyze": PromptTemplateRef(
            "evaluation_agent",
            "evaluation_analyze.md",
        ),
        "hearing_intake_questions": PromptTemplateRef(
            "dispute_intake_officer",
            "hearing_intake_questions.md",
        ),
        "hearing_intake_synthesis": PromptTemplateRef(
            "dispute_intake_officer",
            "hearing_intake_synthesis.md",
        ),
        "hearing_evidence_requests": PromptTemplateRef(
            "evidence_clerk",
            "hearing_evidence_requests.md",
        ),
        "hearing_evidence_file_assessment": PromptTemplateRef(
            "evidence_clerk",
            "hearing_evidence_file_assessment.md",
        ),
        "hearing_evidence_synthesis": PromptTemplateRef(
            "evidence_clerk",
            "hearing_evidence_synthesis.md",
        ),
        "hearing_judge_v1": PromptTemplateRef(
            "presiding_judge",
            "hearing_judge_v1.md",
        ),
        "hearing_jury_review": PromptTemplateRef(
            "deliberation_panel",
            "hearing_jury_review.md",
        ),
        "hearing_judge_v2": PromptTemplateRef(
            "presiding_judge",
            "hearing_judge_v2.md",
        ),
        "evidence_critic": PromptTemplateRef(
            "deliberation_panel",
            "evidence_critic.md",
        ),
        "rule_critic": PromptTemplateRef(
            "deliberation_panel",
            "rule_critic.md",
        ),
        "risk_critic": PromptTemplateRef(
            "deliberation_panel",
            "risk_critic.md",
        ),
        "remedy_critic": PromptTemplateRef(
            "deliberation_panel",
            "remedy_critic.md",
        ),
        "fairness_critic": PromptTemplateRef(
            "deliberation_panel",
            "fairness_critic.md",
        ),
        "review_copilot": PromptTemplateRef(
            "review_copilot",
            "review_copilot.md",
        ),
        "external_import_simulator": PromptTemplateRef(
            "external_import_simulator",
            "external_import_simulator.md",
        ),
    }

    COMMON_FRAGMENT_FILES: tuple[str, ...] = (
        "safety_boundary.md",
        "business_code_localization.md",
        "case_narration_rules.md",
        "json_output_rules.md",
    )

    # 所属模块：Agent Harness > Prompt 仓库 > 模板根目录初始化。
    # 具体功能：`__init__` 解析应用根目录，并允许测试注入 harness 通用规则目录和 Agent 专属模板目录；不读取任何案件数据。
    # 上下游：上游是服务启动依赖装配或测试夹具；下游是所有模板路径解析与 UTF-8 文件读取。
    # 系统意义：模板来源固定在服务端文件系统，案件文本不能指定任意路径或替换平台安全规则。
    def __init__(
        self,
        *,
        app_root: Path | None = None,
        harness_prompt_dir: Path | None = None,
        agent_prompt_root: Path | None = None,
    ) -> None:
        self._app_root = app_root or Path(__file__).resolve().parents[1]
        self._harness_prompt_dir = (
            harness_prompt_dir
            or self._app_root / "harness" / "prompts"
        )
        self._agent_prompt_root = (
            agent_prompt_root
            or self._app_root / "agents" / "prompts"
        )

    # 所属模块：Agent Harness > Prompt 仓库 > 双消息渲染总入口。
    # 具体功能：`render` 分别调用 system 与 user 渲染器，返回 `(system_prompt, user_prompt)`，不把两种信任级别拼成同一段文本。
    # 上下游：上游是 HarnessModelRunner 携带的 node_name、受控 case_data 和 Pydantic Schema；下游是 LangChain message 格式化或 LLM HTTP 请求。
    # 系统意义：可信规则与不可信案件材料保持消息级隔离，减少案件内容覆盖系统职责、工具边界和输出协议的机会。
    def render(
        self,
        node_name: str,
        case_data: dict[str, Any],
        output_schema: dict[str, Any],
        *,
        prompt_profile_id: str | None = None,
        allow_profile_fallback: bool = False,
        trusted_agent_context: dict[str, Any] | None = None,
    ) -> tuple[str, str]:
        """返回 (system_prompt, user_prompt)。

        tuple[str, str] 是 Python 类型提示，表示返回值是两个字符串组成的元组。
        """

        system_prompt = self.render_system_prompt(
            node_name,
            prompt_profile_id=prompt_profile_id,
            allow_profile_fallback=allow_profile_fallback,
            trusted_agent_context=trusted_agent_context,
        )
        user_prompt = self.render_user_prompt(case_data, output_schema)
        return system_prompt, user_prompt

    # 所属模块：Agent Harness > Prompt 仓库 > 可信 system_prompt 组装。
    # 具体功能：`render_system_prompt` 依次拼接全局安全/本地化/叙事/JSON 规则、白名单调用身份、节点基础模板及可选角色覆盖模板。
    # 上下游：上游是 `render` 和 `_trusted_agent_context_payload` 过滤后的身份字段；下游是模型 system message。
    # 系统意义：通用治理规则始终先于专业角色模板；Profile 模板是追加约束而非替换基础模板，不能删除公共安全边界。
    def render_system_prompt(
        self,
        node_name: str,
        *,
        prompt_profile_id: str | None = None,
        allow_profile_fallback: bool = False,
        trusted_agent_context: dict[str, Any] | None = None,
    ) -> str:
        """拼接系统提示词。

        trusted_agent_context 用 XML-like 标签包起来，提醒模型这是系统注入的可信上下文；
        案件材料则在 render_user_prompt 中放到 untrusted_case_data，避免权限语义混淆。
        """

        # 列表推导式会按 COMMON_FRAGMENT_FILES 的固定顺序读取每个通用规则文件。
        fragments = [
            self._read_required(self._harness_prompt_dir / filename)
            for filename in self.COMMON_FRAGMENT_FILES
        ]
        if trusted_agent_context:
            fragments.append(
                "<trusted_agent_context>\n"
                + json.dumps(
                    trusted_agent_context,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n</trusted_agent_context>"
            )
        base_template_path = self._base_template_path(node_name)
        selected_template_path = self._absolute_template_path(
            node_name,
            prompt_profile_id=prompt_profile_id,
            allow_profile_fallback=allow_profile_fallback,
        )
        fragments.append(self._read_required(base_template_path))
        if selected_template_path != base_template_path:
            fragments.append(self._read_required(selected_template_path))
        return "\n\n".join(fragment.strip() for fragment in fragments if fragment.strip())

    # 所属模块：Agent Harness > Prompt 仓库 > 不可信数据与输出合同渲染。
    # 具体功能：`render_user_prompt` 把案件/上下文放进 `untrusted_case_data`，并把 Pydantic JSON Schema 放进独立 `required_output_schema` 标签。
    # 上下游：上游是 ContextPack 裁剪后的 case_data 与 `output_type.model_json_schema()`；下游是模型 human/user message。
    # 系统意义：案件中出现的命令仍只是数据；Schema 明确要求字段、类型和枚举，为下游 Pydantic 再校验建立同一份合同。
    def render_user_prompt(
        self,
        case_data: dict[str, Any],
        output_schema: dict[str, Any],
    ) -> str:
        """拼接用户提示词：不可信案件数据 + 必须遵守的输出 Schema。"""

        # 权威 Schema 已通过 LiteLlmProxyClient 的严格 response_format 单独发送。
        # 不在用户消息中重复数 KB 的 Schema，避免浪费上下文并增加首字延迟。
        _ = output_schema
        return (
            "<untrusted_case_data>\n"
            + json.dumps(case_data, ensure_ascii=False, separators=(",", ":"))
            + "\n</untrusted_case_data>\n"
            + "<required_output_contract>\n"
            + "只返回一个与服务端提供的严格响应结构约束完全匹配的 JSON 对象。"
            + "\n</required_output_contract>"
        )

    # 所属模块：Agent Harness > Prompt 仓库 > 可审计模板路径查询。
    # 具体功能：`template_path` 复用真实选择逻辑取得模板，再转换成相对项目根目录的路径，便于测试、日志和 Prompt 版本审计。
    # 上下游：上游是诊断/测试代码；下游是 `_absolute_template_path`，不会读取或渲染模板正文。
    # 系统意义：观测到的路径与实际调用选择完全一致，避免审计记录声称使用 A 模板而模型实际使用 B 模板。
    def template_path(
        self,
        node_name: str,
        *,
        prompt_profile_id: str | None = None,
        allow_profile_fallback: bool = False,
    ) -> Path:
        return self._absolute_template_path(
            node_name,
            prompt_profile_id=prompt_profile_id,
            allow_profile_fallback=allow_profile_fallback,
        ).relative_to(self._app_root.parent)

    # 所属模块：Agent Harness > Prompt 仓库 > 节点/Profile 模板解析。
    # 具体功能：`_absolute_template_path` 先由 NODE_TEMPLATES 白名单定位基础模板；有 profile 时只接受约定命名的覆盖文件，并按显式参数决定缺失时回退还是报错。
    # 上下游：上游是 system_prompt 渲染和路径查询；下游是 `_profile_template_path` 与 `_read_required`。
    # 系统意义：默认严格缺失失败可防止用户/商家错用彼此角色模板；只有调用方明确允许时才回退到公共模板。
    def _absolute_template_path(
        self,
        node_name: str,
        *,
        prompt_profile_id: str | None = None,
        allow_profile_fallback: bool = False,
    ) -> Path:
        """解析某个节点实际使用的提示词路径，支持 profile 覆盖模板。"""

        ref = self.NODE_TEMPLATES.get(node_name)
        if ref is None:
            raise KeyError(f"unknown prompt node: {node_name}")
        base_path = self._agent_prompt_root / ref.agent_key / ref.filename
        if not prompt_profile_id:
            return base_path

        profile_path = self._profile_template_path(base_path, prompt_profile_id)
        if profile_path.exists():
            return profile_path
        if allow_profile_fallback:
            return base_path
        raise FileNotFoundError(f"profile prompt template not found: {profile_path}")

    # 所属模块：Agent Harness > Prompt 仓库 > Profile ID 到文件名映射。
    # 具体功能：`_profile_template_path` 从如 `prompt:user:v1` 的 ID 取第二段角色名，规范成小写后生成 `base.user.md` 形式的同目录文件名。
    # 上下游：上游是 `_absolute_template_path`；下游是文件存在性检查，不接受请求直接提供磁盘路径。
    # 系统意义：角色 Profile 只能映射到受控命名空间，不能利用 `../` 等内容跨目录读取任意文件。
    @staticmethod
    def _profile_template_path(base_path: Path, prompt_profile_id: str) -> Path:
        parts = prompt_profile_id.split(":")
        role_segment = parts[1] if len(parts) >= 2 else prompt_profile_id
        suffix = role_segment.strip().lower()
        return base_path.with_name(f"{base_path.stem}.{suffix}{base_path.suffix}")

    # 所属模块：Agent Harness > Prompt 仓库 > 节点基础模板白名单解析。
    # 具体功能：`_base_template_path` 把 node_name 映射为固定 agent_key/filename；未知节点立即抛错，不按字符串猜目录。
    # 上下游：上游是 `render_system_prompt`；下游是基础模板读取以及 Profile 覆盖路径比较。
    # 系统意义：LangGraph 节点名、输出 Schema 和专业 Prompt 必须一一对应，错配时不能继续调用模型。
    def _base_template_path(self, node_name: str) -> Path:
        ref = self.NODE_TEMPLATES.get(node_name)
        if ref is None:
            raise KeyError(f"unknown prompt node: {node_name}")
        return self._agent_prompt_root / ref.agent_key / ref.filename

    # 所属模块：Agent Harness > Prompt 仓库 > 必需模板 UTF-8 读取。
    # 具体功能：`_read_required` 读取并去除文件首尾空白；若文件不存在，保留原异常因果并补充完整路径。
    # 上下游：上游是通用片段、基础模板和 Profile 模板组装；下游是最终 system_prompt 字符串。
    # 系统意义：模板缺失属于部署配置错误，必须在模型调用前暴露，不能用空 Prompt 悄悄降级。
    @staticmethod
    def _read_required(path: Path) -> str:
        try:
            source = path.read_text(encoding="utf-8")
            return PromptComposer._markdown_to_plain_text(source)
        except FileNotFoundError as exception:
            raise FileNotFoundError(f"prompt template not found: {path}") from exception

    @staticmethod
    def _markdown_to_plain_text(source: str) -> str:
        """把提示词模板中的 Markdown 展示语法转换成结构清晰的纯文本。

        只处理模板自身的展示符号，不处理运行时案件 JSON、XML 边界标签、
        snake_case 字段名或枚举值。代码围栏只删除围栏行，围栏内合同原样保留。
        """

        text = source.replace("\r\n", "\n").replace("\r", "\n")
        plain_lines: list[str] = []
        in_fenced_block = False

        for original_line in text.split("\n"):
            line = original_line
            if re.match(r"^\s{0,3}(`{3,}|~{3,})", line):
                in_fenced_block = not in_fenced_block
                continue

            if not in_fenced_block:
                # 删除标题、引用和列表的展示标记，同时保留原有层次与正文。
                line = re.sub(r"^\s{0,3}#{1,6}\s+", "", line)
                line = re.sub(r"^\s{0,3}(?:>\s*)+", "", line)
                line = re.sub(r"^(\s*)[-+*]\s+(?:\[[ xX]\]\s+)?", r"\1", line)
                line = re.sub(
                    r"^(\s*)(\d+)[.)]\s+",
                    lambda match: f"{match.group(1)}第{match.group(2)}项：",
                    line,
                )

                # 删除纯展示用分隔线和表格分隔行。
                if re.fullmatch(r"\s*(?:[-*_]\s*){3,}", line):
                    continue
                if re.fullmatch(
                    r"\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*",
                    line,
                ):
                    continue

                # 链接保留可见文字；图片保留替代文字；自动链接保留地址。
                line = re.sub(r"!\[([^\]]*)\]\([^\n)]*\)", r"\1", line)
                line = re.sub(r"\[([^\]]+)\]\([^\n)]*\)", r"\1", line)
                line = re.sub(r"<((?:https?://|mailto:)[^>]+)>", r"\1", line)

                # 只删除成对的行内样式，避免破坏 snake_case 和 JSON 内容。
                line = re.sub(r"`([^`\n]+)`", r"\1", line)
                line = re.sub(r"\*\*([^*\n]+)\*\*", r"\1", line)
                line = re.sub(r"__([^_\n]+)__", r"\1", line)
                line = re.sub(r"~~([^~\n]+)~~", r"\1", line)
                line = re.sub(
                    r"(?<!\*)\*(?=\S)([^*\n]*?\S)\*(?!\*)",
                    r"\1",
                    line,
                )
                line = re.sub(
                    r"(?<![\w_])_(?=\S)([^_\n]*?\S)_(?![\w_])",
                    r"\1",
                    line,
                )

                # 将 Markdown 表格正文转为普通分隔文本；非表格中的竖线不处理。
                if line.strip().startswith("|") and line.strip().endswith("|"):
                    cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
                    line = "；".join(cell for cell in cells if cell)

            plain_lines.append(line.rstrip())

        # 连续空行最多保留一行，避免多个模板拼接后产生无意义空白。
        result = "\n".join(plain_lines).strip()
        return re.sub(r"\n{3,}", "\n\n", result)


class PromptRepository(PromptComposer):
    """Backward-compatible name for the shared prompt composer."""
