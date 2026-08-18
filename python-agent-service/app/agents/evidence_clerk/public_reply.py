"""Deterministic public-output policy for one Evidence Clerk model call."""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
import hashlib
import re

from app.agents.evidence_clerk.assessment_policy import (
    recover_parsed_text_fact_coordinates,
)
from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.errors import GraphRuntimeError
from app.harness.localization_policy import localize_internal_text
from app.schemas import (
    EvidenceContentAuthorityV1,
    EvidenceItemAssessment,
    PublicEvidenceEpistemicStatus,
    PublicEvidenceObservationProposalV1,
    PublicEvidenceObservationCoordinateProposalV1,
    PublicEvidenceObservationV1,
)
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
_PROVISIONAL_REQUEST_OBJECT_FRAME_ZH = re.compile(
    r"^本轮待核验补充要求\d{1,2}为“(?P<object>[^”]{1,1000})”"
    r"的材料核对[。！？!?]$"
)
_ASSESSMENT_OBSERVATION_FRAME_ZH = re.compile(
    r"^已验收评估记录的待复核观察为“(?P<object>[^”]{1,1000})”"
    r"的材料内容核对[。！？!?]$"
)
_SUBMISSION_PROVISIONAL_OBSERVATION_FRAME_ZH = re.compile(
    r"^本轮正在对材料所载“(?P<object>[^”]{1,1000})”进行核验。$"
)
_TYPED_PUBLIC_OBSERVATION_FRAME_ZH = re.compile(
    r"^材料(?:记载“(?P<recorded>[^”]{1,240})”，仍待后续核验|"
    r"所载“(?P<provisional>[^”]{1,240})”，可供后续核对)。$"
)
_UNSAFE_ASSERTION_OR_DIRECTIVE_ZH = re.compile(
    r"(?:责任|担责|过错|归责|造假|伪造|属实|真实有效|证据充分|"
    r"(?:应当|应该|必须|需要|建议|要求|责令|决定|支持|同意|拒绝)"
    r".{0,24}(?:退(?:款|货|费)|返款|赔偿|补偿|承担|负责))"
)
_ISO_CALENDAR_DATE = re.compile(r"(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)")
_MARKDOWN_OBSERVATION_PREFIX = re.compile(
    r"^(?:#{1,6}[ \t]+|[-*+][ \t]+|\d{1,3}[.)、][ \t]+)"
)
_MAX_SUBMISSION_SOURCE_OBSERVATIONS = 12
_MAX_SUBMISSION_SOURCE_OBSERVATION_CHARS = 240
_MAX_SUBMISSION_SOURCE_AUTHORITY_CHARS = 1200
_MAX_TYPED_PUBLIC_SOURCE_QUOTE_CHARS = 200


@dataclass(frozen=True)
class PublicEvidenceObservationAuthorityCoordinate:
    """Private request-bound coordinate used to resolve provider proposals."""

    coordinate_id: str
    evidence_id: str
    parsed_content_sha256: str
    source_quote: str
    source_start_byte: int
    source_end_byte: int
    quote_sha256: str
    attachment_order: int
    fact_ids: tuple[str, ...]

    def prompt_payload(self) -> dict[str, object]:
        """Expose the private mapping only inside the governed model prompt."""

        return {
            "coordinate_id": self.coordinate_id,
            "evidence_id": self.evidence_id,
            "parsed_content_sha256": self.parsed_content_sha256,
            "source_quote": self.source_quote,
            "source_start_byte": self.source_start_byte,
            "source_end_byte": self.source_end_byte,
            "quote_sha256": self.quote_sha256,
            "attachment_order": self.attachment_order,
            "fact_ids": list(self.fact_ids),
        }


class EvidencePublicOutputPolicyError(RuntimeError):
    """The Evidence public stream violated its explicit output policy."""


class EvidencePublicOutputMismatch(
    GraphRuntimeError,
    EvidencePublicOutputPolicyError,
):
    """The live Evidence preview does not equal the guarded terminal reply."""

    code = "EVIDENCE_PUBLIC_OUTPUT_MISMATCH"


class EvidencePublicObservationAuthorityError(
    GraphRuntimeError,
    EvidencePublicOutputPolicyError,
):
    """A typed public observation lacks exact current parsed authority."""

    code = "EVIDENCE_PUBLIC_OBSERVATION_AUTHORITY_INVALID"


def build_submission_observation_catalog(
    *,
    evidence_content_authorities: Iterable[EvidenceContentAuthorityV1],
    visible_evidence: Iterable[object],
    attachment_refs: Iterable[str],
    allowed_fact_targets: Iterable[Mapping[str, object]],
    case_id: str,
    actor_id: str,
    actor_role: str,
) -> tuple[PublicEvidenceObservationAuthorityCoordinate, ...]:
    """Build deterministic opaque coordinates before the provider is invoked.

    The provider receives only these coordinate identifiers.  All source quotes,
    byte spans, hashes, and evidence/fact bindings remain server-owned and are
    resolved again by the incremental prefix validator.
    """

    fact_targets = tuple(allowed_fact_targets)
    authority_by_id, _visible, attachment_positions, _allowed_fact_ids = (
        _public_observation_context(
            evidence_content_authorities=evidence_content_authorities,
            visible_evidence=visible_evidence,
            attachment_refs=attachment_refs,
            allowed_fact_targets=fact_targets,
            case_id=case_id,
            actor_id=actor_id,
            actor_role=actor_role,
        )
    )
    targets = {
        str(target.get("fact_id") or ""): target
        for target in fact_targets
        if isinstance(target, Mapping) and str(target.get("fact_id") or "")
    }
    coordinates: list[PublicEvidenceObservationAuthorityCoordinate] = []
    for evidence_id, authority in authority_by_id.items():
        if authority.content_type not in {"text/plain", "text/markdown"}:
            continue
        fact_ids = recover_parsed_text_fact_coordinates(
            authority.parsed_text,
            tuple(targets.values()),
        )
        for fact_id in fact_ids:
            fact_target = targets.get(fact_id, {})
            quote = _derive_safe_coordinate_quote(
                authority.parsed_text,
                fact_target,
            )
            if quote is None:
                raise EvidencePublicObservationAuthorityError(
                    "Evidence public observation authority has no safe unique coordinate"
                )
            start_character = authority.parsed_text.index(quote)
            start_byte = len(
                authority.parsed_text[:start_character].encode("utf-8")
            )
            end_byte = start_byte + len(quote.encode("utf-8"))
            quote_sha256 = hashlib.sha256(quote.encode("utf-8")).hexdigest()
            coordinate_id = "ECOORD_" + canonical_sha256(
                {
                    "case_id": case_id,
                    "evidence_id": evidence_id,
                    "parsed_content_sha256": authority.parsed_content_sha256,
                    "fact_id": fact_id,
                    "fact_target": fact_target,
                    "source_start_byte": start_byte,
                    "source_end_byte": end_byte,
                    "quote_sha256": quote_sha256,
                    "attachment_order": attachment_positions[evidence_id],
                }
            )[:28].upper()
            coordinates.append(
                PublicEvidenceObservationAuthorityCoordinate(
                    coordinate_id=coordinate_id,
                    evidence_id=evidence_id,
                    parsed_content_sha256=authority.parsed_content_sha256,
                    source_quote=quote,
                    source_start_byte=start_byte,
                    source_end_byte=end_byte,
                    quote_sha256=quote_sha256,
                    attachment_order=attachment_positions[evidence_id],
                    fact_ids=(fact_id,),
                )
            )
    return tuple(
        sorted(
            coordinates,
            key=lambda item: (
                item.attachment_order,
                item.source_start_byte,
                item.source_end_byte,
                item.coordinate_id,
            ),
        )
    )


def submission_observation_catalog_prompt_payload(
    coordinates: Iterable[PublicEvidenceObservationAuthorityCoordinate],
) -> dict[str, object]:
    """Return stable private prompt data without exposing it in public output."""

    entries = tuple(item.prompt_payload() for item in coordinates)
    return {
        "schema_version": "public_evidence_observation_authority_catalog.v1",
        "catalog_hash": canonical_sha256(entries),
        "coordinates": list(entries),
    }


def _derive_safe_coordinate_quote(
    parsed_text: str,
    fact_target: Mapping[str, object],
) -> str | None:
    target_text = " ".join(
        str(fact_target.get(key) or "")
        for key in ("fact", "category", "match_text")
        if str(fact_target.get(key) or "")
    )
    target_grams = _coordinate_bigrams(target_text)
    candidates: list[tuple[float, int, int, str]] = []
    for raw_line in re.split(r"\r?\n+", parsed_text):
        line = re.sub(r"^(?:#{1,6}\s+|[-*+]\s+|\d{1,3}[.)、]\s+)", "", raw_line)
        for raw_segment in re.split(r"(?<=[。！？!?；;])", line):
            candidate = raw_segment.strip().strip("。！？!?；;")
            if (
                not candidate
                or len(candidate) > _MAX_TYPED_PUBLIC_SOURCE_QUOTE_CHARS
                or any(mark in candidate for mark in "\r\n\"'“”‘’")
                or any(mark in candidate for mark in "。！？!?；;")
                or parsed_text.count(candidate) != 1
                or _sentence_violates_public_boundary(
                    _typed_public_observation_sentence(
                        epistemic_status=PublicEvidenceEpistemicStatus.PROVISIONAL,
                        text=candidate,
                    )
                )
            ):
                continue
            grams = _coordinate_bigrams(candidate)
            score = len(grams & target_grams) / max(1, len(target_grams))
            if score <= 0:
                continue
            start = parsed_text.index(candidate)
            candidates.append((score, -len(candidate), start, candidate))
    if not candidates:
        return None
    candidates.sort(key=lambda item: (-item[0], item[1], item[2], item[3]))
    return candidates[0][3]


def _coordinate_bigrams(value: str) -> set[str]:
    normalized = re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", value.casefold())
    return {
        normalized[index : index + 2]
        for index in range(max(0, len(normalized) - 1))
    }


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
        f"本轮待核验补充要求{index}为“{question}”的材料核对。"
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
    public_observations: Iterable[PublicEvidenceObservationV1],
    evidence_assessments: Iterable[object],
    human_review_tasks: Iterable[Mapping[str, object]],
) -> str:
    """Compose one submission reply from canonical accepted observations only."""

    targets = tuple(fact_targets)
    observations = tuple(public_observations)
    assessments = tuple(evidence_assessments)
    review_tasks = tuple(human_review_tasks)
    if any(not isinstance(item, PublicEvidenceObservationV1) for item in observations):
        raise EvidencePublicObservationAuthorityError(
            "Evidence submission observations are not canonical"
        )
    fact_by_id = {
        str(target.get("fact_id") or ""): target.get("fact")
        or target.get("fact_target")
        or target.get("match_text")
        for target in targets
        if isinstance(target, Mapping)
    }
    observation_fact_ids = tuple(
        dict.fromkeys(observation.fact_id for observation in observations)
    )
    linked_facts = _opening_items(
        (fact_by_id.get(fact_id) for fact_id in observation_fact_ids),
        limit=2,
    )
    subject = "、".join(linked_facts) or "本轮材料"

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
    observation_sentences = "".join(
        _canonical_public_observation_text(observation)
        for observation in observations
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
                observation_sentences,
                f"本轮材料已纳入对“{subject}”的关联性核对。",
                "当前材料可用于核对相关记录内容和时间信息。",
                coverage_sentence,
                "材料来源路径、形成时间和原始载体的一致性仍需按程序复核。",
                capability_sentence,
                action_sentence,
                _RESPONSIBILITY_DISCLAIMER,
            )
        )
    )


def validate_public_observation_prefix(
    *,
    prior_accepted: Iterable[PublicEvidenceObservationV1],
    candidate: PublicEvidenceObservationProposalV1 | Mapping[str, object],
    evidence_content_authorities: Iterable[EvidenceContentAuthorityV1],
    visible_evidence: Iterable[object],
    attachment_refs: Iterable[str],
    allowed_fact_targets: Iterable[Mapping[str, object]],
    case_id: str,
    actor_id: str,
    actor_role: str,
    authority_catalog: Iterable[PublicEvidenceObservationAuthorityCoordinate]
    | None = None,
) -> PublicEvidenceObservationV1:
    """Accept one complete provider item against its request-bound prefix."""

    if isinstance(candidate, PublicEvidenceObservationV1):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation candidate must be a provider proposal"
        )
    if authority_catalog is not None:
        if not isinstance(candidate, PublicEvidenceObservationCoordinateProposalV1):
            raise EvidencePublicObservationAuthorityError(
                "Evidence public observation provider proposal shape is invalid"
            )
        accepted = tuple(prior_accepted)
        if any(not isinstance(item, PublicEvidenceObservationV1) for item in accepted):
            raise EvidencePublicObservationAuthorityError(
                "Evidence public observation prefix is not canonical"
            )
        context = _public_observation_context(
            evidence_content_authorities=evidence_content_authorities,
            visible_evidence=visible_evidence,
            attachment_refs=attachment_refs,
            allowed_fact_targets=allowed_fact_targets,
            case_id=case_id,
            actor_id=actor_id,
            actor_role=actor_role,
        )
        seen_spans: set[tuple[str, int, int]] = set()
        last_order: tuple[int, int, int] | None = None
        total_chars = 0
        for index, item in enumerate(accepted, start=1):
            normalized, order_key = _validate_canonical_public_observation(
                item,
                expected_slot_id=f"OBS_{index:02d}",
                context=context,
            )
            span_key = (
                normalized.evidence_id,
                normalized.source_start_byte,
                normalized.source_end_byte,
            )
            if span_key in seen_spans or (
                last_order is not None and order_key <= last_order
            ):
                raise EvidencePublicObservationAuthorityError(
                    "Evidence public observation order or source span is invalid"
                )
            seen_spans.add(span_key)
            last_order = order_key
            total_chars += len(normalized.public_text)
        canonical = _derive_coordinate_public_observation(
            candidate,
            expected_slot_id=f"OBS_{len(accepted) + 1:02d}",
            context=context,
            authority_catalog=tuple(authority_catalog),
        )
        order_key = (
            context[2][canonical.evidence_id],
            canonical.source_start_byte,
            canonical.source_end_byte,
        )
        span_key = (
            canonical.evidence_id,
            canonical.source_start_byte,
            canonical.source_end_byte,
        )
        if span_key in seen_spans or (
            last_order is not None and order_key <= last_order
        ):
            raise EvidencePublicObservationAuthorityError(
                "Evidence public observation order or source span is invalid"
            )
        if (
            len(accepted) + 1 > _MAX_SUBMISSION_SOURCE_OBSERVATIONS
            or total_chars + len(canonical.public_text)
            > _MAX_SUBMISSION_SOURCE_AUTHORITY_CHARS
        ):
            raise EvidencePublicObservationAuthorityError(
                "Evidence public observation budget is exceeded"
            )
        return canonical
    raw_candidate = (
        candidate
        if isinstance(candidate, PublicEvidenceObservationProposalV1)
        else PublicEvidenceObservationProposalV1.model_validate(candidate)
    )
    accepted = tuple(prior_accepted)
    if any(not isinstance(item, PublicEvidenceObservationV1) for item in accepted):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation prefix is not canonical"
        )
    context = _public_observation_context(
        evidence_content_authorities=evidence_content_authorities,
        visible_evidence=visible_evidence,
        attachment_refs=attachment_refs,
        allowed_fact_targets=allowed_fact_targets,
        case_id=case_id,
        actor_id=actor_id,
        actor_role=actor_role,
    )
    canonical: list[PublicEvidenceObservationV1] = []
    seen_spans: set[tuple[str, int, int]] = set()
    last_order: tuple[int, int, int] | None = None
    total_chars = 0
    for index, item in enumerate(accepted, start=1):
        normalized, order_key = _validate_canonical_public_observation(
            item,
            expected_slot_id=f"OBS_{index:02d}",
            context=context,
        )
        span_key = (
            normalized.evidence_id,
            normalized.source_start_byte,
            normalized.source_end_byte,
        )
        if span_key in seen_spans or (last_order is not None and order_key <= last_order):
            raise EvidencePublicObservationAuthorityError(
                "Evidence public observation order or source span is invalid"
            )
        seen_spans.add(span_key)
        last_order = order_key
        total_chars += len(normalized.public_text)
        if index > _MAX_SUBMISSION_SOURCE_OBSERVATIONS or total_chars > _MAX_SUBMISSION_SOURCE_AUTHORITY_CHARS:
            raise EvidencePublicObservationAuthorityError(
                "Evidence public observation budget is exceeded"
            )
        canonical.append(normalized)
    normalized, order_key = _derive_canonical_public_observation(
        raw_candidate,
        expected_slot_id=f"OBS_{len(canonical) + 1:02d}",
        context=context,
    )
    span_key = (
        normalized.evidence_id,
        normalized.source_start_byte,
        normalized.source_end_byte,
    )
    if span_key in seen_spans or (last_order is not None and order_key <= last_order):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation order or source span is invalid"
        )
    if (
        len(canonical) + 1 > _MAX_SUBMISSION_SOURCE_OBSERVATIONS
        or total_chars + len(normalized.public_text)
        > _MAX_SUBMISSION_SOURCE_AUTHORITY_CHARS
    ):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation budget is exceeded"
        )
    return normalized


def validate_submission_public_observations(
    *,
    observations: Iterable[object],
    evidence_content_authorities: Iterable[EvidenceContentAuthorityV1],
    visible_evidence: Iterable[object],
    attachment_refs: Iterable[str],
    allowed_fact_targets: Iterable[Mapping[str, object]],
    case_id: str,
    actor_id: str,
    actor_role: str,
    authority_catalog: Iterable[PublicEvidenceObservationAuthorityCoordinate]
    | None = None,
) -> tuple[PublicEvidenceObservationV1, ...]:
    """Atomically fold the same prefix validator over one completed array."""

    accepted: tuple[PublicEvidenceObservationV1, ...] = ()
    for candidate in observations:
        accepted = (
            *accepted,
            validate_public_observation_prefix(
                prior_accepted=accepted,
                candidate=candidate,
                evidence_content_authorities=evidence_content_authorities,
                visible_evidence=visible_evidence,
                attachment_refs=attachment_refs,
                allowed_fact_targets=allowed_fact_targets,
                case_id=case_id,
                actor_id=actor_id,
                actor_role=actor_role,
                authority_catalog=authority_catalog,
            ),
        )
    return accepted


def _derive_coordinate_public_observation(
    observation: PublicEvidenceObservationCoordinateProposalV1,
    *,
    expected_slot_id: str,
    context: tuple[
        dict[str, EvidenceContentAuthorityV1],
        dict[str, object],
        dict[str, int],
        set[str],
    ],
    authority_catalog: tuple[PublicEvidenceObservationAuthorityCoordinate, ...],
) -> PublicEvidenceObservationV1:
    if observation.provider_slot_id != expected_slot_id:
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation slot order is invalid"
        )
    by_id = {item.coordinate_id: item for item in authority_catalog}
    coordinate = by_id.get(observation.coordinate_id)
    if coordinate is None or not coordinate.fact_ids:
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation coordinate is unauthorized"
        )
    legacy = PublicEvidenceObservationProposalV1(
        schema_version="public_evidence_observation.v1",
        provider_slot_id=observation.provider_slot_id,
        evidence_id=coordinate.evidence_id,
        fact_id=coordinate.fact_ids[0],
        observation_kind=observation.observation_kind,
        epistemic_status=observation.epistemic_status,
        parsed_content_sha256=coordinate.parsed_content_sha256,
        source_quote=coordinate.source_quote,
    )
    canonical, _order_key = _derive_canonical_public_observation(
        legacy,
        expected_slot_id=expected_slot_id,
        context=context,
    )
    if (
        canonical.source_start_byte != coordinate.source_start_byte
        or canonical.source_end_byte != coordinate.source_end_byte
        or canonical.quote_sha256 != coordinate.quote_sha256
    ):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation coordinate binding is invalid"
        )
    return canonical


def require_relevant_parsed_observation_coverage(
    *,
    canonical_observations: Iterable[PublicEvidenceObservationV1],
    evidence_assessments: Iterable[EvidenceItemAssessment],
    evidence_content_authorities: Iterable[EvidenceContentAuthorityV1],
    allowed_fact_targets: Iterable[Mapping[str, object]],
) -> None:
    """Require exact coverage for each parsed authority with a server coordinate."""

    observations = tuple(canonical_observations)
    assessments = tuple(evidence_assessments)
    fact_targets = tuple(allowed_fact_targets)
    required_coordinates = {
        authority.evidence_id: fact_ids
        for authority in evidence_content_authorities
        if isinstance(authority, EvidenceContentAuthorityV1)
        and authority.status == "SUCCEEDED"
        and authority.content_type in {"text/plain", "text/markdown"}
        and (
            fact_ids := recover_parsed_text_fact_coordinates(
                authority.parsed_text,
                fact_targets,
            )
        )
    }
    for evidence_id, fact_ids in required_coordinates.items():
        validated_fact_ids = frozenset(fact_ids)
        candidates = tuple(
            observation
            for observation in observations
            if isinstance(observation, PublicEvidenceObservationV1)
            and observation.evidence_id == evidence_id
            and observation.fact_id in validated_fact_ids
        )
        assessment_bound = any(
            isinstance(assessment, EvidenceItemAssessment)
            and assessment.evidence_id == evidence_id
            and any(
                observation.fact_id
                in {link.fact_id for link in assessment.fact_links}
                and (
                    observation.provider_slot_id
                    in set(assessment.public_observation_slots)
                    or observation.observation_id
                    in set(assessment.public_observation_ids)
                )
                for observation in candidates
            )
            for assessment in assessments
        )
        if not candidates or not assessment_bound:
            raise EvidencePublicObservationAuthorityError(
                "relevant parsed evidence requires public observation authority"
            )


def reconcile_accepted_public_observations(
    *,
    canonical_observations: Iterable[PublicEvidenceObservationV1],
    evidence_assessments: Iterable[EvidenceItemAssessment],
) -> tuple[
    tuple[PublicEvidenceObservationV1, ...],
    tuple[EvidenceItemAssessment, ...],
]:
    """Bind accepted assessment slots to canonical public observation IDs."""

    observations = tuple(canonical_observations)
    assessments = tuple(evidence_assessments)
    if any(not isinstance(item, PublicEvidenceObservationV1) for item in observations):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observations are not canonical"
        )
    if any(not isinstance(item, EvidenceItemAssessment) for item in assessments):
        raise EvidencePublicObservationAuthorityError(
            "Evidence assessments are not accepted models"
        )
    by_slot = {item.provider_slot_id: item for item in observations}
    if len(by_slot) != len(observations):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation slots are duplicated"
        )
    seen_slots: set[str] = set()
    reconciled: list[EvidenceItemAssessment] = []
    for assessment in assessments:
        if assessment.public_observation_ids:
            raise EvidencePublicObservationAuthorityError(
                "Provider cannot author canonical public observation IDs"
            )
        slots = tuple(assessment.public_observation_slots)
        if len(slots) != len(set(slots)):
            raise EvidencePublicObservationAuthorityError(
                "Evidence assessment public observation slots are duplicated"
            )
        fact_ids = {link.fact_id for link in assessment.fact_links}
        derived_ids: list[str] = []
        for slot in slots:
            observation = by_slot.get(slot)
            if (
                observation is None
                or slot in seen_slots
                or observation.evidence_id != assessment.evidence_id
                or observation.fact_id not in fact_ids
                or observation.observation_id is None
            ):
                raise EvidencePublicObservationAuthorityError(
                    "Evidence assessment public observation reconciliation is invalid"
                )
            seen_slots.add(slot)
            derived_ids.append(observation.observation_id)
        reconciled.append(
            assessment.model_copy(
                update={
                    "public_observation_slots": [],
                    "public_observation_ids": derived_ids,
                }
            )
        )
    if seen_slots != set(by_slot):
        raise EvidencePublicObservationAuthorityError(
            "Every public observation must be accepted by one assessment"
        )
    return observations, tuple(reconciled)


def _public_observation_context(
    *,
    evidence_content_authorities: Iterable[EvidenceContentAuthorityV1],
    visible_evidence: Iterable[object],
    attachment_refs: Iterable[str],
    allowed_fact_targets: Iterable[Mapping[str, object]],
    case_id: str,
    actor_id: str,
    actor_role: str,
) -> tuple[
    dict[str, EvidenceContentAuthorityV1],
    dict[str, object],
    dict[str, int],
    set[str],
]:
    references = tuple(attachment_refs)
    if not references or len(references) != len(set(references)):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation attachment authority is invalid"
        )
    attachment_positions = {
        evidence_id: index for index, evidence_id in enumerate(references)
    }
    visible_by_id: dict[str, object] = {}
    for item in visible_evidence:
        evidence_id = _authority_value(item, "evidence_id")
        if (
            not isinstance(evidence_id, str)
            or evidence_id in visible_by_id
        ):
            raise EvidencePublicObservationAuthorityError(
                "Evidence public observation visible scope is invalid"
            )
        visible_by_id[evidence_id] = item
    if any(reference not in visible_by_id for reference in references):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation visible attachments are incomplete"
        )
    if any(
        _authority_value(visible_by_id[reference], "submitted_by_id") != actor_id
        or _authority_value(visible_by_id[reference], "submitted_by_role") != actor_role
        for reference in references
    ):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation actor scope is invalid"
        )
    authority_by_id: dict[str, EvidenceContentAuthorityV1] = {}
    previous_position = -1
    for authority in evidence_content_authorities:
        if not isinstance(authority, EvidenceContentAuthorityV1):
            raise EvidencePublicObservationAuthorityError(
                "Evidence content authority type is invalid"
            )
        evidence_id = authority.evidence_id
        position = attachment_positions.get(evidence_id)
        visible = visible_by_id.get(evidence_id)
        if (
            authority.case_id != case_id
            or position is None
            or position <= previous_position
            or evidence_id in authority_by_id
            or visible is None
            or _authority_value(visible, "file_hash") != authority.file_sha256
            or _authority_value(visible, "content_type") != authority.content_type
            or authority.status != "SUCCEEDED"
            or not authority.parsed_text.strip()
        ):
            raise EvidencePublicObservationAuthorityError(
                "Evidence content authority scope is invalid"
            )
        previous_position = position
        authority_by_id[evidence_id] = authority
    supported_text_attachment_ids = {
        evidence_id
        for evidence_id in references
        if _authority_value(visible_by_id[evidence_id], "content_type")
        in {"text/plain", "text/markdown"}
    }
    if set(authority_by_id) != supported_text_attachment_ids:
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation supported text authority is incomplete"
        )
    allowed_fact_ids = {
        str(target.get("fact_id") or "")
        for target in allowed_fact_targets
        if isinstance(target, Mapping) and str(target.get("fact_id") or "")
    }
    if not allowed_fact_ids:
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation fact authority is empty"
        )
    return authority_by_id, visible_by_id, attachment_positions, allowed_fact_ids


def _derive_canonical_public_observation(
    observation: PublicEvidenceObservationProposalV1,
    *,
    expected_slot_id: str,
    context: tuple[
        dict[str, EvidenceContentAuthorityV1],
        dict[str, object],
        dict[str, int],
        set[str],
    ],
) -> tuple[PublicEvidenceObservationV1, tuple[int, int, int]]:
    authority_by_id, _visible_by_id, attachment_positions, allowed_fact_ids = context
    if observation.provider_slot_id != expected_slot_id:
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation slot order is invalid"
        )
    authority = authority_by_id.get(observation.evidence_id)
    if authority is None or observation.fact_id not in allowed_fact_ids:
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation evidence or fact is unauthorized"
        )
    parsed_text = authority.parsed_text
    if (
        observation.parsed_content_sha256 != authority.parsed_content_sha256
        or hashlib.sha256(parsed_text.encode("utf-8")).hexdigest()
        != authority.parsed_content_sha256
    ):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation parsed authority is unavailable"
        )
    quote = observation.source_quote
    if (
        quote != quote.strip()
        or "\n" in quote
        or "\r" in quote
        or any(mark in quote for mark in "。！？!?")
        or any(character in quote for character in "\"'“”‘’")
        or len(quote) > _MAX_TYPED_PUBLIC_SOURCE_QUOTE_CHARS
        or parsed_text.count(quote) != 1
        or _sentence_violates_public_boundary(
            _typed_public_observation_sentence(
                epistemic_status=observation.epistemic_status,
                text=quote,
            )
        )
    ):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation source quote is invalid"
        )
    start_character = parsed_text.index(quote)
    start_byte = len(parsed_text[:start_character].encode("utf-8"))
    end_byte = start_byte + len(quote.encode("utf-8"))
    quote_sha256 = hashlib.sha256(quote.encode("utf-8")).hexdigest()
    public_text = _typed_public_observation_sentence(
        epistemic_status=observation.epistemic_status,
        text=quote,
    )
    observation_id = "PUBOBS_" + canonical_sha256(
        {
            "schema_version": observation.schema_version,
            "evidence_id": observation.evidence_id,
            "fact_id": observation.fact_id,
            "observation_kind": observation.observation_kind.value,
            "epistemic_status": observation.epistemic_status.value,
            "file_sha256": authority.file_sha256,
            "parsed_content_sha256": authority.parsed_content_sha256,
            "source_start_byte": start_byte,
            "source_end_byte": end_byte,
            "quote_sha256": quote_sha256,
        }
    )[:24].upper()
    return (
        PublicEvidenceObservationV1(
            schema_version=observation.schema_version,
            provider_slot_id=observation.provider_slot_id,
            observation_id=observation_id,
            evidence_id=observation.evidence_id,
            fact_id=observation.fact_id,
            observation_kind=observation.observation_kind,
            epistemic_status=observation.epistemic_status,
            parsed_content_sha256=observation.parsed_content_sha256,
            source_quote=quote,
            public_text=public_text,
            source_start_byte=start_byte,
            source_end_byte=end_byte,
            quote_sha256=quote_sha256,
        ),
        (attachment_positions[observation.evidence_id], start_byte, end_byte),
    )


def _validate_canonical_public_observation(
    observation: PublicEvidenceObservationV1,
    *,
    expected_slot_id: str,
    context: tuple[
        dict[str, EvidenceContentAuthorityV1],
        dict[str, object],
        dict[str, int],
        set[str],
    ],
) -> tuple[PublicEvidenceObservationV1, tuple[int, int, int]]:
    expected, order_key = _derive_canonical_public_observation(
        PublicEvidenceObservationProposalV1(
            schema_version=observation.schema_version,
            provider_slot_id=observation.provider_slot_id,
            evidence_id=observation.evidence_id,
            fact_id=observation.fact_id,
            observation_kind=observation.observation_kind,
            epistemic_status=observation.epistemic_status,
            parsed_content_sha256=observation.parsed_content_sha256,
            source_quote=observation.source_quote,
        ),
        expected_slot_id=expected_slot_id,
        context=context,
    )
    if observation.model_dump(mode="json") != expected.model_dump(mode="json"):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation derived fields are not canonical"
        )
    return expected, order_key


def _typed_public_observation_sentence(
    *,
    epistemic_status: PublicEvidenceEpistemicStatus,
    text: str,
) -> str:
    if not text:
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation text is unavailable"
        )
    if epistemic_status is PublicEvidenceEpistemicStatus.PENDING_VERIFICATION:
        return f"材料记载“{text}”，仍待后续核验。"
    if epistemic_status is PublicEvidenceEpistemicStatus.PROVISIONAL:
        return f"材料所载“{text}”，可供后续核对。"
    raise EvidencePublicObservationAuthorityError(
        "Evidence public observation status is invalid"
    )


def _canonical_public_observation_text(
    observation: PublicEvidenceObservationV1,
) -> str:
    public_text = observation.public_text
    if (
        len(public_text) > _MAX_SUBMISSION_SOURCE_OBSERVATION_CHARS
        or public_text
        != _typed_public_observation_sentence(
            epistemic_status=observation.epistemic_status,
            text=observation.source_quote,
        )
    ):
        raise EvidencePublicObservationAuthorityError(
            "Evidence public observation text is not canonical"
        )
    return public_text


class EvidencePublicOutputPolicy:
    """Release only complete guarded Evidence sentences from one JSON field."""

    @property
    def visible_field_name(self) -> str:
        return "room_utterance"

    def __init__(
        self,
        *,
        submission_observation_authority: Iterable[str] | None = None,
    ) -> None:
        self._source_text = ""
        self._visible_text = ""
        self._examined_sentence_count = 0
        self._live_release_blocked = False
        self._bootstrapped = False
        self._authorized_terminal_text: str | None = None
        self._submission_observation_authority = (
            None
            if submission_observation_authority is None
            else frozenset(submission_observation_authority)
        )

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
        # Direct Harness callers may feed a provider delta without invoking the
        # HTTP observer's explicit begin hook.  Mark that stream as bootstrapped
        # without inventing a prefix; production routes still call ``begin``
        # first and therefore retain the canonical opening bytes.
        if not self._bootstrapped:
            self._bootstrapped = True
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
            if self._submission_observation_authority is not None:
                frame = _SUBMISSION_PROVISIONAL_OBSERVATION_FRAME_ZH.fullmatch(
                    localized_sentence.strip()
                )
                if (
                    frame is None
                    or frame.group("object")
                    not in self._submission_observation_authority
                    or _sentence_violates_public_boundary(
                        localized_sentence.strip()
                    )
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


def derive_submission_observation_authority(
    *,
    visible_evidence: Iterable[object],
    attachment_refs: Iterable[str],
) -> tuple[str, ...]:
    """Derive bounded public observation authority from exact current attachments."""

    references = tuple(attachment_refs)
    if not references or len(set(references)) != len(references):
        raise EvidencePublicOutputPolicyError(
            "Evidence submission attachment authority is invalid"
        )
    evidence_by_id: dict[str, object] = {}
    for item in visible_evidence:
        evidence_id = _authority_value(item, "evidence_id")
        if not isinstance(evidence_id, str) or evidence_id in evidence_by_id:
            raise EvidencePublicOutputPolicyError(
                "Evidence submission visible authority is invalid"
            )
        evidence_by_id[evidence_id] = item
    if any(reference not in evidence_by_id for reference in references):
        raise EvidencePublicOutputPolicyError(
            "Evidence submission attachment authority is incomplete"
        )

    candidates: list[object] = []
    for reference in references:
        evidence = evidence_by_id[reference]
        metadata = _authority_value(evidence, "metadata")
        if isinstance(metadata, Mapping):
            candidates.append(
                metadata.get("claimed_fact", metadata.get("claimedFact"))
            )
        parsed_text = _authority_value(evidence, "parsed_text")
        if isinstance(parsed_text, str):
            candidates.extend(parsed_text.splitlines())

    observations: list[str] = []
    seen: set[str] = set()
    authority_chars = 0
    for candidate in candidates:
        observation = _normalize_submission_source_observation(candidate)
        if observation is None or observation in seen:
            continue
        next_chars = authority_chars + len(observation)
        if next_chars > _MAX_SUBMISSION_SOURCE_AUTHORITY_CHARS:
            break
        observations.append(observation)
        seen.add(observation)
        authority_chars = next_chars
        if len(observations) >= _MAX_SUBMISSION_SOURCE_OBSERVATIONS:
            break
    return tuple(observations)


def _normalize_submission_source_observation(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    localized = localize_internal_text(value).strip()
    localized = _MARKDOWN_OBSERVATION_PREFIX.sub("", localized, count=1).strip()
    localized = localized.rstrip("。！？!?").strip()
    if (
        not localized
        or len(localized) > _MAX_SUBMISSION_SOURCE_OBSERVATION_CHARS
        or any(quote in localized for quote in "\"'“”‘’")
        or "\n" in localized
        or "\r" in localized
        or _sentence_violates_public_boundary(
            _submission_observation_frame(localized)
        )
    ):
        return None
    return localized


def _assessment_observations(assessments: tuple[object, ...]) -> tuple[str, ...]:
    values: list[object] = []
    for assessment in assessments:
        values.append(_authority_value(assessment, "formation_time_assessment"))
        values.extend(
            _authority_value(finding, "description")
            for finding in _authority_items(assessment, "findings")
        )
        values.extend(_authority_items(assessment, "source_basis"))
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
            or _sentence_violates_public_boundary(
                _submission_observation_frame(value)
            )
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
        framed_object = _PROVISIONAL_REQUEST_OBJECT_FRAME_ZH.fullmatch(sentence)
    if framed_object is None:
        framed_object = _ASSESSMENT_OBSERVATION_FRAME_ZH.fullmatch(sentence)
    if framed_object is None:
        framed_object = _SUBMISSION_PROVISIONAL_OBSERVATION_FRAME_ZH.fullmatch(
            sentence
        )
    typed_match = (
        _TYPED_PUBLIC_OBSERVATION_FRAME_ZH.fullmatch(sentence)
        if framed_object is None
        else None
    )
    framed_text = (
        framed_object.group("object")
        if framed_object is not None
        else (
            typed_match.group("recorded") or typed_match.group("provisional")
            if typed_match is not None
            else None
        )
    )
    if (
        framed_text is not None
        and _UNSAFE_ASSERTION_OR_DIRECTIVE_ZH.search(framed_text) is None
    ):
        conclusion_scan = sentence.replace(
            framed_text,
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
