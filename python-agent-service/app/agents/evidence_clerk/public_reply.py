"""Deterministic public-output policy for one Evidence Clerk model call."""

from __future__ import annotations

import re

from app.harness.localization_policy import localize_internal_text


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


class EvidencePublicOutputPolicy:
    """Release only complete guarded Evidence sentences from one JSON field."""

    def __init__(self) -> None:
        self._source_text = ""
        self._visible_text = ""
        self._examined_sentence_count = 0
        self._live_release_blocked = False
        self._bootstrapped = False

    @property
    def source_observed(self) -> bool:
        return bool(self._source_text)

    @property
    def visible_text(self) -> str:
        return self._visible_text

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
            if guard_evidence_public_reply(self._source_text) != final_text:
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
    return bool(
        _CONCLUSIVE_OR_REMEDY_ZH.search(sentence) is not None
        or _contains_non_public_language_or_machine_syntax(sentence)
    )


def _contains_non_public_language_or_machine_syntax(sentence: str) -> bool:
    for character in sentence:
        if (
            "\u4e00" <= character <= "\u9fff"
            or "0" <= character <= "9"
            or "０" <= character <= "９"
            or character in _PUBLIC_TERMINAL_PUNCTUATION
        ):
            continue
        return True
    return False
