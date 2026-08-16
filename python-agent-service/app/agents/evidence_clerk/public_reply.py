"""Deterministic public-output policy for one Evidence Clerk model call."""

from __future__ import annotations

from collections.abc import Iterable, Mapping
import re

from app.harness.localization_policy import localize_internal_text
from app.streaming import STREAM_MAX_VISIBLE_OUTPUT_CHARS


EVIDENCE_PUBLIC_NODE = "evidence_turn"
EVIDENCE_PUBLIC_FIELD = "room_utterance"
EVIDENCE_CANONICAL_OPENING = "我会先核验本轮材料与案情的关联。"
_RESPONSIBILITY_DISCLAIMER = "本轮只做证据核验，不判断责任或最终方案。"
_WITHHELD_CONCLUSION_SENTENCE = "相关事项仍需后续程序核验。"
_CONCLUSIVE_OR_REMEDY_ZH = re.compile(
    r"(?:责(?:任|令)|归责|担责|过错|承担|负责|应负|应由|"
    r"对错|有错|无错|错在|"
    r"退(?:款|货|费|钱|给|回)|返(?:款|还|钱|给|回)|"
    r"赔(?:付|偿|钱|给)|补偿|换货|维修|"
    r"(?:款项|货款|钱|费用).{0,8}(?:退|返|还)(?:给|回)?|"
    r"支持|驳回|采纳|准许|不予|同意|拒绝|"
    r"判定|认定|判决|裁定|裁判|裁决|胜诉|败诉|胜负|输赢|"
    r"欺诈|欺骗|诈骗|造假|伪造|假冒|假证|伪证|虚构|篡改|"
    r"证明|证实|确认|保证|承诺|属实|真实|真伪|确凿|"
    r"可信|不可信|证据充分|"
    r"成立|不成立|有效|无效|可采|不可采|"
    r"最终|结论|处理方案|处置方案)"
)
_SAFE_LIVE_EVIDENCE_PROGRESS_ZH = re.compile(
    r"^(?:(?:我|我们)(?:会|将)?(?:先|继续)?|"
    r"本轮(?:将|先|继续|正在)?|"
    r"当前(?:将|先|继续|正在)?|"
    r"现阶段(?:将|先|继续|正在)?)"
    r"(?:核验|核对|检查|比对|梳理|整理|查看|读取)"
    r"|^(?:(?:本轮|当前)?(?:材料|证据|附件|记录)(?:已|正在|将))"
    r"(?:进入|接受|等待)?(?:核验|核对|检查|比对|梳理|整理)"
    r"|^(?:已收到|正在接收)(?:材料|证据|附件|记录)"
)
_PUBLIC_TERMINAL_PUNCTUATION = frozenset(
    " \t\r\n，。！？!?、；：…“”‘’（）《》【】％%"
)
_SAFE_LIVE_SCOPE_END_ZH = re.compile(
    r"(?:材料|证据|附件|记录|来源|来源链|时间|时间线|完整性|可读性|"
    r"一致性|关联|关联性|核验进展|提交情况|核验|核对|检查|比对|"
    r"梳理|整理)[。！？!?](?:[”’」』）)]*)$"
)
_SENTENCE_END = re.compile(r"[。！？!?](?:[”’」』）)]*)")
_PROVISIONAL_FACT_OBJECT_FRAME_ZH = re.compile(
    r"^(?:本轮材料已纳入对|本轮正在对|本轮核验对象为|待核验事项(?:为|包括)?)"
    r"“(?P<object>[^”]{1,1000})”"
    r"(?:的)?(?:关联性核对|核验|核对)(?:范围)?[。！？!?]$"
)
_ASSESSMENT_OBSERVATION_FRAME_ZH = re.compile(
    r"^已验收评估记录的待复核观察为“(?P<object>[^”]{1,1000})”"
    r"的材料内容核对[。！？!?]$"
)
_SUBMISSION_PROVISIONAL_OBSERVATION_FRAME_ZH = re.compile(
    r"^本轮正在对材料所载“(?P<object>[^”]{1,1000})”进行核验。$"
)
_UNSAFE_ASSERTION_OR_DIRECTIVE_ZH = re.compile(
    r"(?:责任|担责|过错|归责|造假|伪造|属实|真实有效|证据充分|"
    r"(?:应当|应该|必须|需要|建议|要求|责令|决定|支持|同意|拒绝)"
    r".{0,24}(?:退(?:款|货|费)|返款|赔偿|补偿|承担|负责))"
)
_ISO_CALENDAR_DATE = re.compile(r"(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)")


class EvidencePublicOutputPolicyError(RuntimeError):
    """The Evidence public stream violated its explicit output policy."""


class EvidencePublicOutputMismatch(EvidencePublicOutputPolicyError):
    """The live Evidence preview does not equal the guarded terminal reply."""


def sanitize_evidence_public_prefix(text: str) -> str:
    """Localize text and replace every conclusive sentence as one safe unit."""

    localized = localize_internal_text(text)
    return "".join(
        _guard_terminal_sentence(sentence)
        for sentence, _complete in _sentence_segments(localized)
    )


def guard_evidence_public_reply(text: str) -> str:
    """Return the canonical Evidence room reply used by preview and terminal."""

    guarded = sanitize_evidence_public_prefix(text).strip()
    # The bootstrap is current-run canonical content. Remove every exact model
    # repetition before restoring one authoritative copy at the beginning.
    guarded = guarded.replace(EVIDENCE_CANONICAL_OPENING, "").strip()
    if not guarded:
        guarded = _WITHHELD_CONCLUSION_SENTENCE
    guarded = EVIDENCE_CANONICAL_OPENING + guarded
    if not guarded.endswith(_RESPONSIBILITY_DISCLAIMER):
        if guarded[-1] not in "。！？!?":
            guarded += "。"
        guarded += _RESPONSIBILITY_DISCLAIMER
    return guarded


def compose_evidence_opening_public_reply(
    source_reply: str,
    *,
    fact_targets: Iterable[Mapping[str, object]],
    evidence_requests: Iterable[object],
) -> str:
    """Bind an Evidence opening reply to frozen facts and accepted requests."""

    guarded_source = guard_evidence_public_reply(source_reply)
    source_body = guarded_source
    if source_body.endswith(_RESPONSIBILITY_DISCLAIMER):
        source_body = source_body[: -len(_RESPONSIBILITY_DISCLAIMER)]

    targets = tuple(fact_targets)
    core_targets = tuple(
        target
        for target in targets
        if str(target.get("materiality") or "") == "CORE"
    )
    if len(core_targets) > 100:
        raise EvidencePublicOutputPolicyError(
            "Evidence opening fact authority exceeds the governed limit"
        )
    facts = _opening_items(
        (target.get("fact") for target in core_targets),
        limit=100,
    )
    questions = _opening_items(
        (
            item.get("question")
            if isinstance(item, Mapping)
            else getattr(item, "question", None)
            for item in evidence_requests
        ),
        limit=3,
    )
    if not questions:
        questions = tuple(
            f"请提交可核对「{fact}」的原始材料，并说明形成时间和来源路径"
            for fact in facts
        )
    if not questions:
        questions = (
            "请提交与本案待核验事实相关的原始材料，并说明形成时间和来源路径",
        )

    fact_sentences = "".join(
        f"待核验事项为“{fact}”的关联性核对。" for fact in facts
    )
    request_sentences = "".join(
        f"具体补充要求{index}：{_as_sentence(question)}"
        for index, question in enumerate(questions, start=1)
    )
    composed = (
        source_body
        + fact_sentences
        + request_sentences
        + _RESPONSIBILITY_DISCLAIMER
    )
    return _guard_composed_reply(composed)


def compose_evidence_submission_public_reply(
    *,
    fact_targets: Iterable[Mapping[str, object]],
    evidence_assessments: Iterable[object],
    human_review_tasks: Iterable[Mapping[str, object]],
    source_reply: str | None = None,
) -> str:
    """Derive a submission reply only from accepted assessment authority."""

    targets = tuple(fact_targets)
    assessments = tuple(evidence_assessments)
    review_tasks = tuple(human_review_tasks)
    fact_by_id = {
        str(target.get("fact_id") or ""): target.get("fact")
        or target.get("fact_target")
        or target.get("match_text")
        for target in targets
        if isinstance(target, Mapping)
    }
    linked_fact_ids = tuple(
        dict.fromkeys(
            fact_id
            for assessment in assessments
            for link in _authority_items(assessment, "fact_links")
            if (fact_id := str(_authority_value(link, "fact_id") or ""))
        )
    )
    linked_facts = _opening_items(
        (fact_by_id.get(fact_id) for fact_id in linked_fact_ids),
        limit=2,
    )
    if not linked_facts:
        linked_facts = _opening_items(fact_by_id.values(), limit=2)
    subject = "、".join(linked_facts) or "本案待核验事项"

    relations = {
        str(_authority_value(link, "relation") or "")
        for assessment in assessments
        for link in _authority_items(assessment, "fact_links")
    }
    has_unsupported_scope = any(
        _authority_items(assessment, "unsupported_claims")
        for assessment in assessments
    )
    text_only = bool(assessments) and all(
        str(_authority_value(assessment, "analysis_method") or "") == "TEXT_ONLY"
        for assessment in assessments
    )
    review_required = bool(review_tasks) or any(
        bool(_authority_value(_authority_value(assessment, "human_review"), "required"))
        for assessment in assessments
    )
    observations = _assessment_observations(assessments)
    live_observations = _submission_live_observations(source_reply or "")
    observation_set = set(observations)
    if any(observation not in observation_set for observation in live_observations):
        raise EvidencePublicOutputMismatch(
            "Evidence submission live observation is absent from accepted authority"
        )
    live_set = set(live_observations)
    live_observation_sentences = "".join(
        _submission_observation_frame(observation)
        for observation in live_observations
    )
    missing_observation_sentences = "".join(
        f"已验收评估记录的待复核观察为“{observation}”的材料内容核对。"
        for observation in observations
        if observation not in live_set
    )

    coverage_sentence = (
        "现有内容覆盖范围有限，尚不足以单独还原完整事实经过。"
        if "INCONCLUSIVE" in relations or has_unsupported_scope or not relations
        else "现有内容仅反映本轮可读取范围，仍需与其他来源交叉核对。"
    )
    capability_sentence = (
        "本轮仅核对了可读取文本，未直接查看原始图像内容或完整文件信息。"
        if text_only
        else "本轮核对范围仅限已授权载入的材料内容，未覆盖平台外来源路径。"
    )
    action_sentence = (
        "下一步需由人工结合清晰原件、完整上下文和形成时间继续复核。"
        if review_required
        else "请补充清晰原件、形成时间和来源路径，以便继续核对。"
    )
    return _guard_composed_reply(
        "".join(
            (
                EVIDENCE_CANONICAL_OPENING,
                live_observation_sentences,
                f"本轮材料已纳入对“{subject}”的关联性核对。",
                missing_observation_sentences,
                "当前材料可用于核对相关记录内容和时间信息。",
                coverage_sentence,
                "材料来源路径、形成时间和原始载体的一致性仍需按程序复核。",
                capability_sentence,
                action_sentence,
                _RESPONSIBILITY_DISCLAIMER,
            )
        )
    )


class EvidencePublicOutputPolicy:
    """Release only complete guarded Evidence sentences from one JSON field."""

    def __init__(self, *, submission_observation_only: bool = False) -> None:
        self._source_text = ""
        self._visible_text = ""
        self._examined_sentence_count = 0
        self._live_release_blocked = False
        self._bootstrapped = False
        self._authorized_terminal_text: str | None = None
        self._submission_observation_only = submission_observation_only

    @property
    def source_observed(self) -> bool:
        return bool(self._source_text)

    @property
    def visible_text(self) -> str:
        return self._visible_text

    @property
    def guarded_source_reply(self) -> str:
        if not self.source_observed:
            raise EvidencePublicOutputMismatch(
                "Evidence live public output was not observed"
            )
        return guard_evidence_public_reply(self._source_text)

    def authorize_terminal_extension(
        self,
        *,
        guarded_source_reply: str,
        final_text: str,
    ) -> None:
        """Authorize one executor-verified terminal suffix without changing live bytes."""

        if self.guarded_source_reply != guarded_source_reply:
            raise EvidencePublicOutputMismatch(
                "Evidence terminal extension source is not the observed reply"
            )
        if guard_evidence_public_reply(final_text) != final_text:
            raise EvidencePublicOutputMismatch(
                "Evidence terminal extension is not guarded"
            )
        if not final_text.startswith(self._visible_text):
            raise EvidencePublicOutputMismatch(
                "Evidence terminal extension changed an emitted prefix"
            )
        self._authorized_terminal_text = final_text

    def allows_node(self, operation: str, node_name: str) -> bool:
        return operation == EVIDENCE_PUBLIC_NODE and node_name == EVIDENCE_PUBLIC_NODE

    def begin(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
    ) -> tuple[str, ...]:
        """Begin one fresh run with its deterministic canonical public prefix."""

        self._require_field(operation, node_name, field_name)
        if self._bootstrapped:
            return ()
        self._bootstrapped = True
        self._visible_text = EVIDENCE_CANONICAL_OPENING
        return (EVIDENCE_CANONICAL_OPENING,)

    def accept(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
        delta: str,
    ) -> tuple[str, ...]:
        self._require_field(operation, node_name, field_name)
        if not isinstance(delta, str) or not delta:
            raise EvidencePublicOutputPolicyError(
                "Evidence public output received an invalid delta"
            )
        self._source_text += delta
        completed_sentences = tuple(
            sentence
            for sentence, complete in _sentence_segments(self._source_text)
            if complete
        )
        public_deltas: list[str] = []
        for sentence in completed_sentences[self._examined_sentence_count :]:
            self._examined_sentence_count += 1
            localized_sentence = localize_internal_text(sentence)
            if (
                self._bootstrapped
                and localized_sentence.strip() == EVIDENCE_CANONICAL_OPENING
            ):
                continue
            if self._submission_observation_only:
                frame = _SUBMISSION_PROVISIONAL_OBSERVATION_FRAME_ZH.fullmatch(
                    localized_sentence.strip()
                )
                if frame is None or _sentence_violates_public_boundary(
                    localized_sentence.strip()
                ):
                    continue
                self._visible_text += localized_sentence
                public_deltas.append(localized_sentence)
                continue
            if self._live_release_blocked or not _is_independently_safe_sentence(
                localized_sentence
            ):
                self._live_release_blocked = True
                continue
            self._visible_text += localized_sentence
            public_deltas.append(localized_sentence)
        return tuple(public_deltas)

    def finalize(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
        final_text: str,
        allow_canonical_fallback: bool = False,
    ) -> tuple[str, ...]:
        self._require_field(operation, node_name, field_name)
        if not isinstance(final_text, str) or not final_text:
            raise EvidencePublicOutputMismatch(
                "Evidence terminal public output is unavailable"
            )
        if guard_evidence_public_reply(final_text) != final_text:
            raise EvidencePublicOutputMismatch(
                "Evidence terminal public output is not guarded"
            )
        if self.source_observed:
            if not self._bootstrapped:
                raise EvidencePublicOutputMismatch(
                    "Evidence live public output bootstrap is unavailable"
                )
            if (
                guard_evidence_public_reply(self._source_text) != final_text
                and self._authorized_terminal_text != final_text
            ):
                raise EvidencePublicOutputMismatch(
                    "Evidence live and terminal public output differ"
                )
        elif not allow_canonical_fallback:
            raise EvidencePublicOutputMismatch(
                "Evidence live public output was not observed"
            )
        if not final_text.startswith(self._visible_text):
            raise EvidencePublicOutputMismatch(
                "Evidence terminal output changed an emitted prefix"
            )
        public_delta = final_text[len(self._visible_text) :]
        self._visible_text = final_text
        return (public_delta,) if public_delta else ()

    @staticmethod
    def _require_field(operation: str, node_name: str, field_name: str) -> None:
        if (
            operation != EVIDENCE_PUBLIC_NODE
            or node_name != EVIDENCE_PUBLIC_NODE
            or field_name != EVIDENCE_PUBLIC_FIELD
        ):
            raise EvidencePublicOutputPolicyError(
                "Evidence public output field is not authorized"
            )


def _sentence_segments(text: str) -> tuple[tuple[str, bool], ...]:
    segments: list[tuple[str, bool]] = []
    cursor = 0
    for match in _SENTENCE_END.finditer(text):
        end = match.end()
        segments.append((text[cursor:end], True))
        cursor = end
    if cursor < len(text):
        segments.append((text[cursor:], False))
    return tuple(segments)


def _opening_items(values: Iterable[object], *, limit: int) -> tuple[str, ...]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if not isinstance(value, str):
            continue
        localized = localize_internal_text(value)
        normalized = " ".join(localized.split()).strip("。！？!?；; ")[:500]
        key = normalized.casefold()
        if not normalized or key in seen:
            continue
        result.append(normalized)
        seen.add(key)
        if len(result) >= limit:
            break
    return tuple(result)


def _authority_value(value: object, field: str) -> object | None:
    if isinstance(value, Mapping):
        return value.get(field)
    return getattr(value, field, None)


def _authority_items(value: object, field: str) -> tuple[object, ...]:
    items = _authority_value(value, field)
    if isinstance(items, (list, tuple)):
        return tuple(items)
    return ()


def _assessment_observations(assessments: tuple[object, ...]) -> tuple[str, ...]:
    values: list[object] = []
    for assessment in assessments:
        values.append(_authority_value(assessment, "formation_time_assessment"))
        values.extend(
            _authority_value(finding, "description")
            for finding in _authority_items(assessment, "findings")
        )
        values.extend(_authority_items(assessment, "source_basis"))
        values.extend(_authority_items(assessment, "limitations"))
        values.append(_authority_value(assessment, "summary"))
    observations: list[str] = []
    seen: set[str] = set()
    for value in values:
        if (
            not isinstance(value, str)
            or not value
            or value != value.strip()
            or "\n" in value
            or "\r" in value
            or any(quote in value for quote in "\"'“”‘’")
            or any(mark in value for mark in "。！？!?")
            or len(value) > 1000
            or value in seen
        ):
            continue
        observations.append(value)
        seen.add(value)
        if len(observations) >= 12:
            break
    return tuple(observations)


def _submission_live_observations(source_reply: str) -> tuple[str, ...]:
    observations: list[str] = []
    for sentence, complete in _sentence_segments(localize_internal_text(source_reply)):
        if not complete:
            continue
        frame = _SUBMISSION_PROVISIONAL_OBSERVATION_FRAME_ZH.fullmatch(
            sentence.strip()
        )
        if frame is None or _sentence_violates_public_boundary(sentence.strip()):
            continue
        observation = frame.group("object")
        if observation in observations:
            raise EvidencePublicOutputMismatch(
                "Evidence submission live observation is duplicated"
            )
        observations.append(observation)
    return tuple(observations)


def _submission_observation_frame(observation: str) -> str:
    return f"本轮正在对材料所载“{observation}”进行核验。"


def _guard_composed_reply(text: str) -> str:
    guarded = guard_evidence_public_reply(text)
    if len(guarded) > STREAM_MAX_VISIBLE_OUTPUT_CHARS:
        raise EvidencePublicOutputPolicyError(
            "Evidence composed public output exceeds the governed limit"
        )
    return guarded


def _as_sentence(text: str) -> str:
    return text if text[-1] in "。！？!?" else text + "。"


def _is_independently_safe_sentence(sentence: str) -> bool:
    candidate = sentence.strip()
    if (
        not candidate
        or _SENTENCE_END.fullmatch(candidate) is not None
        or _sentence_violates_public_boundary(candidate)
        or any(separator in candidate for separator in "，；：;:")
    ):
        return False
    sentence_end = _SENTENCE_END.search(candidate)
    return bool(
        sentence_end is not None
        and sentence_end.end() == len(candidate)
        and _SAFE_LIVE_EVIDENCE_PROGRESS_ZH.search(candidate) is not None
        and _SAFE_LIVE_SCOPE_END_ZH.search(candidate) is not None
    )


def _guard_terminal_sentence(sentence: str) -> str:
    stripped = sentence.strip()
    if stripped == _RESPONSIBILITY_DISCLAIMER:
        return sentence
    if not _sentence_violates_public_boundary(stripped):
        return sentence
    leading = sentence[: len(sentence) - len(sentence.lstrip())]
    return leading + _WITHHELD_CONCLUSION_SENTENCE


def _sentence_violates_public_boundary(sentence: str) -> bool:
    conclusion_scan = sentence
    framed_object = _PROVISIONAL_FACT_OBJECT_FRAME_ZH.fullmatch(sentence)
    if framed_object is None:
        framed_object = _ASSESSMENT_OBSERVATION_FRAME_ZH.fullmatch(sentence)
    if framed_object is None:
        framed_object = _SUBMISSION_PROVISIONAL_OBSERVATION_FRAME_ZH.fullmatch(
            sentence
        )
    if (
        framed_object is not None
        and _UNSAFE_ASSERTION_OR_DIRECTIVE_ZH.search(framed_object.group("object"))
        is None
    ):
        conclusion_scan = sentence.replace(
            framed_object.group("object"),
            "待核验事实对象",
            1,
        )
    return bool(
        _CONCLUSIVE_OR_REMEDY_ZH.search(conclusion_scan) is not None
        or _contains_non_public_language_or_machine_syntax(sentence)
    )


def _contains_non_public_language_or_machine_syntax(sentence: str) -> bool:
    normalized = _ISO_CALENDAR_DATE.sub("日期", sentence)
    for character in normalized:
        if (
            "\u4e00" <= character <= "\u9fff"
            or "0" <= character <= "9"
            or "０" <= character <= "９"
            or character in _PUBLIC_TERMINAL_PUNCTUATION
        ):
            continue
        return True
    return False
