<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { disputeApi } from "../../api/disputes";
import {
  ACTIVE_REVIEW_STATUSES,
  normalizeReviewTask,
  reviewApi,
} from "../../api/review";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import {
  domainCodeLabel,
  humanizeDossierText,
} from "../../utils/displayText";

const props = defineProps({
  initialOutcome: { type: Object, default: null },
  viewerRole: { type: String, default: "" },
  startReviewAction: { type: Function, default: null },
  serverNow: { type: String, default: "" },
});

const route = useRoute();
const router = useRouter();
const mountedCaseId = String(route.params.caseId || "");
const outcome = ref(props.initialOutcome);
const loading = ref(props.initialOutcome === null);
const error = ref("");
const enteringReview = ref(false);
const selectedFactKey = ref("");
let reviewEntryGeneration = 0;

const DECISION_LABELS = {
  CANCEL_ORDER: "取消订单",
  RETURN_AND_REFUND: "退货退款",
  REFUND_ONLY: "仅退款",
  RESHIP: "补发商品",
  REPLACE: "更换商品",
  REPAIR: "维修商品",
  COMPENSATE: "补偿",
  CONTINUE_FULFILLMENT: "继续履约",
  REJECT_CLAIM: "驳回诉求",
  MANUAL_REVIEW_REQUIRED: "转人工复核",
  APPROVE_REFUND: "建议退款",
  FULL_REFUND: "建议全额退款",
  PARTIAL_REFUND: "建议部分退款",
  REPLACEMENT: "建议换新或补发",
  REJECT_CLAIM: "建议驳回诉求",
};
const RULE_LABELS = {
  DELIVERY_PROOF: "签收争议举证规则",
  UNSHIPPED_CANCEL: "未发货订单取消规则",
  MERCHANT_APPROVED_REFUND: "商家同意退款规则",
};
const caseId = computed(() => String(outcome.value?.case_id || mountedCaseId));
const caseTitle = computed(() => readable(outcome.value?.title) || "履约争端");
const role = computed(() => props.viewerRole || actor.role);
const historyMode = computed(() => route.query.view === "history");
const draft = computed(
  () => outcome.value?.adjudication_draft || outcome.value?.adjudicationDraft || null,
);
const reviewTask = computed(() =>
  normalizeReviewTask(
    outcome.value?.review_task ||
      outcome.value?.reviewTask || {
        id: outcome.value?.review_task_id || outcome.value?.reviewTaskId,
        status:
          outcome.value?.review_task_status || outcome.value?.reviewTaskStatus,
        due_at:
          outcome.value?.review_deadline || outcome.value?.reviewDeadline,
        packet_status:
          outcome.value?.review_packet_status || outcome.value?.reviewPacketStatus,
      },
  ),
);
const reviewTaskId = computed(() => reviewTask.value.id);
const reviewTaskStatus = computed(() => reviewTask.value.status);
const reviewPacketFrozen = computed(() => {
  const explicitStatus = String(
    reviewTask.value.packet_status ||
      reviewTask.value.packetStatus ||
      outcome.value?.review_packet_status ||
      outcome.value?.reviewPacketStatus ||
      "",
  )
    .trim()
    .toUpperCase();
  // Legacy outcome projections only exposed an active task after packet freeze.
  return explicitStatus ? explicitStatus === "FROZEN" : true;
});
const reviewTaskExpired = computed(() => {
  const deadline = Date.parse(reviewTask.value.due_at || "");
  const now = Date.parse(props.serverNow || "") || Date.now();
  return Number.isFinite(deadline) && deadline <= now;
});
const canEnterReview = computed(
  () =>
    !historyMode.value &&
    role.value === "PLATFORM_REVIEWER" &&
    Boolean(reviewTaskId.value) &&
    ACTIVE_REVIEW_STATUSES.includes(reviewTaskStatus.value) &&
    reviewPacketFrozen.value &&
    !reviewTaskExpired.value,
);
const draftVersion = computed(() => draft.value?.draft_version || draft.value?.draftVersion || 1);
const draftId = computed(() => identifier(draft.value?.id));
const draftReferenceLabel = computed(() =>
  draftId.value ? `裁决草案第 ${draftVersion.value} 版 · 编号已记录` : "",
);
const draftStatus = computed(() => draft.value?.draft_status || draft.value?.draftStatus || "");
const factReferenceLabels = computed(() => {
  const labels = new Map();
  const append = (value) => {
    for (const candidate of rawList(value)) {
      const id = identifier(candidate);
      if (!/^FACT_[A-Za-z0-9]+$/u.test(id) || labels.has(id)) continue;
      labels.set(id, `事实 ${String(labels.size + 1).padStart(2, "0")}`);
    }
  };
  for (const item of rawList(draft.value?.fact_findings || draft.value?.factFindings)) {
    if (!isRecord(item)) continue;
    append(item.fact_id || item.factId);
    append(item.fact_ids || item.factIds);
  }
  for (const item of rawList(draft.value?.evidence_assessment || draft.value?.evidenceAssessment)) {
    if (!isRecord(item)) continue;
    append(item.fact_id || item.factId);
    append(item.fact_ids || item.factIds);
  }
  for (const item of rawList(draft.value?.policy_application || draft.value?.policyApplication)) {
    if (isRecord(item)) append(item.fact_ids || item.factIds);
  }
  return labels;
});
const evidenceReferenceLabels = computed(() => {
  const labels = new Map();
  const append = (value) => {
    for (const candidate of rawList(value)) {
      const id = identifier(candidate);
      if (!/^EVIDENCE_[A-Za-z0-9_-]+$/u.test(id) || labels.has(id)) continue;
      labels.set(id, `证据材料 ${String(labels.size + 1).padStart(2, "0")}`);
    }
  };
  for (const item of rawList(draft.value?.fact_findings || draft.value?.factFindings)) {
    if (isRecord(item)) append(item.evidence_ids || item.evidenceIds || item.supported_by);
  }
  for (const item of rawList(draft.value?.evidence_assessment || draft.value?.evidenceAssessment)) {
    if (!isRecord(item)) continue;
    append(item.evidence_id || item.evidenceId);
    append(item.evidence_ids || item.evidenceIds || item.supported_by || item.supportedBy);
    append(item.contradicted_by || item.contradictedBy);
  }
  const structuredText = [
    draft.value?.decision_reasoning || draft.value?.decisionReasoning,
    ...rawList(draft.value?.remedy_orders || draft.value?.remedyOrders).map((item) =>
      isRecord(item) ? item.order_text || item.orderText : item,
    ),
  ].join(" ");
  append(structuredText.match(/\bEVIDENCE_[A-Za-z0-9_-]+\b/gu) || []);
  return labels;
});
const draftStatusLabel = computed(() => {
  if (reviewTaskStatus.value === "IN_REVIEW") return "终审进行中";
  if (reviewTaskStatus.value === "APPROVED") return "平台终审已完成";
  if (reviewTaskStatus.value === "REJECTED") return "平台终审已退回";
  if (draftStatus.value === "PENDING_HUMAN_REVIEW") return "待进入平台终审";
  return "非最终草案";
});
const judgeState = computed(() => {
  if (loading.value) return "THINKING";
  if (!draft.value) return "LISTENING";
  return reviewTaskStatus.value === "IN_REVIEW" ? "HANDOFF" : "COMPLETED";
});
const judgeMessage = computed(() => {
  if (loading.value) return "我正在展开已经封存的庭审草案。";
  if (!draft.value) return "庭审草案尚未生成，我会继续等待封存结果。";
  if (reviewTaskStatus.value === "IN_REVIEW") {
    return "庭审草案已经封存并移交平台终审，本页继续保留原始草案供各方查阅。";
  }
  return "庭审草案已经封存。本页只展示草案，不在这里作出终审决定。";
});
const recommendationSource = computed(
  () => draft.value?.recommended_decision || draft.value?.recommendedDecision || "待终审确认",
);
const recommendation = computed(() => decisionLabel(recommendationSource.value));
const decisionReasoning = computed(() =>
  readable(
    draft.value?.decision_reasoning ||
      draft.value?.decisionReasoning ||
      "暂无裁决理由。",
  ),
);
const remedyOrders = computed(() =>
  rawList(draft.value?.remedy_orders || draft.value?.remedyOrders).map((item, index) => {
    if (!isRecord(item)) {
      return {
        label: `处理事项 ${index + 1}`,
        text: readable(item),
      };
    }
    const code = identifier(item.remedy_type || item.remedyType || item.action_type || item.actionType);
    return {
      label: DECISION_LABELS[code] || domainCodeLabel(code, `处理事项 ${index + 1}`),
      text: readable(item.order_text || item.orderText || item.description),
    };
  }),
);
const juryReviewExchanges = computed(() =>
  rawList(
    draft.value?.jury_review_exchanges || draft.value?.juryReviewExchanges,
  ).map((item, index) => {
    if (!isRecord(item)) {
      return {
        reference: `陪审意见 ${String(index + 1).padStart(2, "0")}`,
        label: `陪审意见 ${String(index + 1).padStart(2, "0")}`,
        opinion: readable(item),
        basis: [],
        severity: "",
        disposition: "",
        response: "",
      };
    }
    const reference = identifier(item.review_item_ref || item.reviewItemRef);
    const itemType = identifier(item.item_type || item.itemType);
    const dimension = identifier(item.dimension);
    const referenceLabel = readable(reference) || `陪审意见 ${String(index + 1).padStart(2, "0")}`;
    return {
      reference,
      label:
        itemType === "MANDATORY_REVISION"
          ? referenceLabel
          : domainCodeLabel(dimension, referenceLabel),
      opinion: readable(item.jury_opinion || item.juryOpinion),
      basis: list(item.basis),
      severity: domainCodeLabel(item.severity, ""),
      disposition: domainCodeLabel(item.disposition, ""),
      response: readable(item.judge_response || item.judgeResponse),
    };
  }),
);
const confidence = computed(() => {
  const value = Number(draft.value?.confidence);
  if (!Number.isFinite(value)) return "待评分";
  return `${Math.round((value <= 1 ? value * 100 : value))}/100`;
});
const issueFindings = computed(() =>
  rawList(draft.value?.fact_findings || draft.value?.factFindings).map((item, index) => {
    if (!isRecord(item)) {
      return {
        id: `争议项 ${String(index + 1).padStart(2, "0")}`,
        finding: readable(item),
        evidenceBasis: [],
        policyBasis: [],
        evidenceGap: "",
        confidence: "",
      };
    }
    const factId = identifier(item.fact_id || item.factId || item.issue_id || item.issueId);
    const explicitPolicyBasis = identifiers(
      item.policy_basis || item.policyBasis || item.rule_code,
    );
    return {
      id: factReferenceLabel(factId, index),
      referenceId: factId,
      finding: readable(
        item.suggested_finding || item.suggestedFinding || item.finding || item.neutral_analysis,
      ),
      evidenceBasis: evidenceIdentifiers(
        item.evidence_ids || item.evidenceIds || item.evidence_basis || item.evidenceBasis || item.supported_by,
      ),
      policyBasis: explicitPolicyBasis.length
        ? explicitPolicyBasis.map(
            (code) => RULE_LABELS[code] || domainCodeLabel(code, "规则依据"),
          )
        : policyRefsForFact(factId),
      evidenceGap: readable(item.evidence_gap || item.evidenceGap),
      confidence: score(item.confidence),
    };
  }),
);
const evidenceAssessments = computed(() =>
  rawList(draft.value?.evidence_assessment || draft.value?.evidenceAssessment).map(
    (item, index) => {
      if (!isRecord(item)) {
        return {
          id: `核验 ${String(index + 1).padStart(2, "0")}`,
          analysis: readable(item),
          supportedBy: [],
          contradictedBy: [],
          missingEvidence: null,
          confidence: "",
          factIds: [],
          factReferenceIds: [],
          weight: "",
          limitations: [],
        };
      }
      const assessmentType = item.assessment_type || item.assessmentType;
      return {
        id:
          evidenceReferenceLabel(
            identifier(item.evidence_id || item.evidenceId || item.issue_id || item.issueId),
            index,
          ) ||
          `${assessmentType === "EVIDENCE_GAP" ? "证据缺口" : "核验"} ${String(index + 1).padStart(2, "0")}`,
        analysis: readable(item.assessment || item.neutral_analysis || item.neutralAnalysis || item.finding),
        supportedBy: evidenceIdentifiers(item.supported_by || item.supportedBy),
        contradictedBy: evidenceIdentifiers(item.contradicted_by || item.contradictedBy),
        missingEvidence:
          assessmentType === "EVIDENCE_GAP"
            ? true
            : typeof (item.missing_evidence ?? item.missingEvidence) === "boolean"
              ? item.missing_evidence ?? item.missingEvidence
              : null,
        confidence: score(item.confidence),
        factIds: factIdentifiers(item.fact_ids || item.factIds || item.fact_id || item.factId),
        factReferenceIds: identifiers(
          item.fact_ids || item.factIds || item.fact_id || item.factId,
        ),
        weight: evidenceWeight(item.weight),
        limitations: list(item.limitations),
      };
    },
  ),
);
const factEvidenceRows = computed(() => {
  const rows = issueFindings.value.map((item) => ({
    ...item,
    assessments: [],
    unmatchedEvidence: false,
  }));
  const rowsByFactId = new Map(
    rows.filter((item) => item.referenceId).map((item) => [item.referenceId, item]),
  );
  const unmatched = [];

  evidenceAssessments.value.forEach((assessment) => {
    const factRow = assessment.factReferenceIds
      .map((factId) => rowsByFactId.get(factId))
      .find(Boolean);
    const evidenceRow = factRow || rows.find(
      (item) => item.evidenceBasis.includes(assessment.id),
    );

    if (evidenceRow) {
      evidenceRow.assessments.push(assessment);
      return;
    }
    unmatched.push(assessment);
  });

  if (unmatched.length) {
    rows.push({
      id: "其他证据核验",
      referenceId: "unmatched-evidence",
      finding: "",
      evidenceBasis: [],
      policyBasis: [],
      evidenceGap: "",
      confidence: "",
      assessments: unmatched,
      unmatchedEvidence: true,
    });
  }

  return rows.map((row) => {
    const assessments = row.assessments.map((assessment) => {
      const additionalFactIds = row.unmatchedEvidence
        ? assessment.factIds
        : assessment.factIds.filter((factId) => factId !== row.id);
      const repeatsFinding = Boolean(
        assessment.analysis && row.finding && assessment.analysis.trim() === row.finding.trim(),
      );
      const hasIndependentDetails = Boolean(
        assessment.supportedBy.length ||
        assessment.contradictedBy.length ||
        assessment.missingEvidence !== null ||
        assessment.weight ||
        assessment.limitations.length ||
        additionalFactIds.length,
      );
      return {
        ...assessment,
        additionalFactIds,
        showDetails: row.unmatchedEvidence || !repeatsFinding || hasIndependentDetails,
      };
    });
    return {
      ...row,
      assessments,
      detailedAssessments: assessments.filter((assessment) => assessment.showDetails),
      displayConfidence:
        row.confidence || assessments.find((assessment) => assessment.confidence)?.confidence || "",
    };
  });
});
function factRowKey(row) {
  return row?.referenceId || row?.id || "";
}
const selectedFactRow = computed(() => {
  const rows = factEvidenceRows.value;
  return rows.find((row) => factRowKey(row) === selectedFactKey.value) || rows[0] || null;
});
watch(
  factEvidenceRows,
  (rows) => {
    if (!rows.length) {
      selectedFactKey.value = "";
      return;
    }
    if (!rows.some((row) => factRowKey(row) === selectedFactKey.value)) {
      selectedFactKey.value = factRowKey(rows[0]);
    }
  },
  { immediate: true },
);
function selectFactRow(row) {
  selectedFactKey.value = factRowKey(row);
}
const policyApplications = computed(() =>
  rawList(draft.value?.policy_application || draft.value?.policyApplication).map((item, index) => {
    if (!isRecord(item)) {
      return {
        issueId: `规则 ${String(index + 1).padStart(2, "0")}`,
        rule: "",
        rationale: readable(item),
        applicable: null,
        limitations: [],
        factIds: [],
      };
    }
    const code = identifier(item.rule_code || item.ruleCode || item.rule);
    const version = readable(item.rule_version || item.ruleVersion);
    const name = readable(item.rule_name || item.ruleName || item.title);
    return {
      issueId: identifier(item.issue_id || item.issueId) || `规则 ${String(index + 1).padStart(2, "0")}`,
      ruleCode: code,
      rule: [
        RULE_LABELS[code] || name || domainCodeLabel(code, "规则待确认"),
        version ? `V${version}` : "",
      ]
        .filter(Boolean)
        .join(" · "),
      rationale: readable(item.rationale || item.application || item.description),
      applicable: typeof item.applicable === "boolean" ? item.applicable : null,
      limitations: list(item.limitations),
      factIds: factIdentifiers(item.fact_ids || item.factIds),
    };
  }),
);
const reviewFocus = computed(() =>
  list(draft.value?.reviewer_attention || draft.value?.reviewerAttention),
);
const contentItemCount = computed(() => {
  return (
    issueFindings.value.length +
    evidenceAssessments.value.length +
    policyApplications.value.length +
    reviewFocus.value.length +
    juryReviewExchanges.value.length
  );
});
function rawList(value) {
  if (value == null) return [];
  return Array.isArray(value) ? value : [value];
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function list(value) {
  return rawList(value).map(readable).filter(Boolean);
}

function identifiers(value) {
  return rawList(value).map(identifier).filter(Boolean);
}

function factReferenceLabel(value, index = 0) {
  const id = identifier(value);
  if (!id) return `争议项 ${String(index + 1).padStart(2, "0")}`;
  return factReferenceLabels.value.get(id) ||
    `争议项 ${String(index + 1).padStart(2, "0")}`;
}

function evidenceReferenceLabel(value, index = 0) {
  const id = identifier(value);
  if (!id) return "";
  return evidenceReferenceLabels.value.get(id) ||
    (/^EVIDENCE_/u.test(id)
      ? `证据材料 ${String(index + 1).padStart(2, "0")}`
      : "");
}

function factIdentifiers(value) {
  return identifiers(value).map((id, index) => factReferenceLabel(id, index));
}

function evidenceIdentifiers(value) {
  return identifiers(value)
    .map((id, index) => evidenceReferenceLabel(id, index))
    .filter(Boolean);
}

function replaceDraftReferences(value) {
  return String(value || "")
    .replace(
      /\bFACT_[A-Za-z0-9]+\b/gu,
      (id) => factReferenceLabels.value.get(id) || "关联事实",
    )
    .replace(
      /\bEVIDENCE_[A-Za-z0-9_-]+\b/gu,
      (id) => evidenceReferenceLabels.value.get(id) || "证据材料",
    );
}

function identifier(value) {
  if (value == null) return "";
  return String(value).trim();
}

function score(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "";
  return `${Math.round((number <= 1 ? number * 100 : number))}/100`;
}

function evidenceWeight(value) {
  return (
    { NONE: "未形成", LOW: "低", MEDIUM: "中", HIGH: "高" }[
      String(value || "").toUpperCase()
    ] || readable(value)
  );
}

function decisionLabel(value) {
  const code = identifier(value);
  return DECISION_LABELS[code] || readable(value) || "待终审确认";
}

function policyRefsForFact(factId) {
  if (!factId) return [];
  return rawList(draft.value?.policy_application || draft.value?.policyApplication)
    .filter(
      (item) =>
        isRecord(item) &&
        item.applicable !== false &&
        identifiers(item.fact_ids || item.factIds).includes(factId),
    )
    .map((item) => {
      const code = identifier(item.rule_code || item.ruleCode || item.rule);
      const version = readable(item.rule_version || item.ruleVersion);
      return [RULE_LABELS[code] || domainCodeLabel(code, "规则待确认"), version ? `V${version}` : ""]
        .filter(Boolean)
        .join(" · ");
    })
    .filter(Boolean);
}

function readable(value) {
  if (value == null) return "";
  if (typeof value === "string") {
    return replaceDraftReferences(humanizeDossierText(value, { fallback: "" }));
  }
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) return value.map(readable).filter(Boolean).join("；");
  if (typeof value === "object") {
    return Object.values(value).map(readable).filter(Boolean).join("；");
  }
  return String(value);
}

async function load() {
  if (outcome.value !== null) return;
  loading.value = true;
  error.value = "";
  try {
    outcome.value = await disputeApi.outcome(actor, mountedCaseId);
  } catch (failure) {
    error.value = failure.message;
  } finally {
    loading.value = false;
  }
}

async function enterReviewRoom() {
  if (historyMode.value || !canEnterReview.value || enteringReview.value) return;
  const generation = ++reviewEntryGeneration;
  enteringReview.value = true;
  error.value = "";
  try {
    if (reviewTaskStatus.value !== "IN_REVIEW") {
      if (props.startReviewAction) {
        await props.startReviewAction(reviewTaskId.value);
      } else {
        await reviewApi.start(actor, reviewTaskId.value);
      }
    }
    if (historyMode.value || generation !== reviewEntryGeneration) return;
    await router.push(`/reviews/${encodeURIComponent(reviewTaskId.value)}`);
  } catch (failure) {
    if (generation !== reviewEntryGeneration) return;
    error.value = failure.message;
  } finally {
    if (generation === reviewEntryGeneration) enteringReview.value = false;
  }
}

watch(historyMode, (historical) => {
  if (!historical) return;
  reviewEntryGeneration += 1;
  enteringReview.value = false;
});

onMounted(load);
</script>

<template>
  <RoomShell
    eyebrow="裁决草案审阅"
    title="裁决草案室"
    subtitle="裁决草案"
    subtitle-description="这里只展示庭审最后输出的草案，不在本页作出终审决定。"
    :case-id="caseId"
    :show-case-id="false"
    :show-connection="false"
    :show-boundary="false"
    :history-mode="historyMode"
    history-description="这是庭审结束时封存的裁决草案，只能浏览原始内容，不能再次发起或进入终审。"
  >
    <template #clock>
      <div class="draft-room__stage" data-draft-stage>
        <small>第 {{ draftVersion }} 版</small>
        <strong>{{ draftStatusLabel }}</strong>
      </div>
    </template>

    <template #agent>
      <DigitalHuman
        :state="judgeState"
        name="小正"
        role="AI 法官"
        :message="judgeMessage"
      />
    </template>

    <main class="draft-room" data-adjudication-draft-room>
      <div class="draft-room__document">
        <section v-if="loading" class="draft-scroll-state" data-draft-loading>
          <span aria-hidden="true" />
          <strong>正在展开裁决草案卷轴</strong>
        </section>

        <section v-else-if="!draft" class="draft-scroll-state" data-draft-empty>
          <strong>裁决草案尚未生成</strong>
          <p>庭审封存后，法官生成并校验通过的草案会展示在这里。</p>
        </section>

        <section v-else class="draft-scroll-frame" data-draft-scroll>
          <div class="draft-scroll-frame__rod draft-scroll-frame__rod--top" aria-hidden="true">
            <i /><span /><i />
          </div>
          <article class="draft-scroll">
            <header class="draft-scroll__masthead">
              <div class="draft-scroll__title">
                <span>庭审最终输出 · 第 {{ draftVersion }} 版</span>
                <h2>履约争端裁决草案</h2>
                <p>{{ caseTitle }}</p>
                <small v-if="draftId">{{ draftReferenceLabel }}</small>
              </div>

              <section class="draft-scroll__summary" data-draft-summary>
                <div
                  class="draft-scroll__recommendation"
                  :title="`建议结论：${recommendation}`"
                >
                  <span>法官建议结论</span>
                  <h3>{{ recommendation }}</h3>
                </div>
                <dl>
                  <div>
                    <dt>可信分</dt>
                    <dd>{{ confidence }}</dd>
                  </div>
                  <div>
                    <dt>文书状态</dt>
                    <dd>{{ draftStatusLabel }}</dd>
                  </div>
                  <div>
                    <dt>草案版本</dt>
                    <dd>第 {{ draftVersion }} 版</dd>
                  </div>
                  <div>
                    <dt>内容规模</dt>
                    <dd>{{ contentItemCount }} 项</dd>
                  </div>
                </dl>
              </section>

              <strong class="draft-scroll__seal" data-draft-seal>草案<br />待审</strong>
            </header>

            <div class="draft-scroll__decision-layout">
              <div class="draft-scroll__overview">
                <section class="draft-scroll__body" data-draft-reasoning>
                  <header class="draft-scroll__module-heading">
                    <h3>法官裁判理由</h3>
                  </header>
                  <div class="draft-scroll__module-content">
                    <p data-decision-reasoning>{{ decisionReasoning }}</p>
                    <section
                      v-if="remedyOrders.length"
                      class="draft-scroll__remedies"
                      data-remedy-orders
                    >
                      <h4>处理事项</h4>
                      <ol>
                        <li v-for="(item, index) in remedyOrders" :key="`remedy-${index}`">
                          <strong>{{ index + 1 }}. [{{ item.label }}]</strong>
                          <span>{{ item.text || "暂无具体处理说明。" }}</span>
                        </li>
                      </ol>
                    </section>
                  </div>
                </section>
              </div>

              <div class="draft-scroll__analysis-board">
              <section class="draft-scroll__issues" data-draft-section="facts">
                <header class="draft-scroll__section-heading">
                  <span>壹</span>
                  <div>
                    <small>事实与证据</small>
                    <h3>事实与证据认定</h3>
                  </div>
                  <em>{{ issueFindings.length }} 项事实 · {{ evidenceAssessments.length }} 项核验</em>
                </header>
                <div class="draft-scroll__module-content draft-scroll__module-content--facts">
                  <p v-if="!factEvidenceRows.length" class="draft-scroll__empty">暂无结构化事实与证据认定。</p>
                  <div v-else class="draft-scroll__fact-workspace">
                    <nav class="draft-scroll__fact-index" aria-label="事实索引">
                      <button
                        v-for="(item, index) in factEvidenceRows"
                        :key="`${item.referenceId || item.id}-${index}`"
                        type="button"
                        :class="{ 'is-active': factRowKey(item) === factRowKey(selectedFactRow) }"
                        :aria-current="factRowKey(item) === factRowKey(selectedFactRow) ? 'true' : undefined"
                        :title="item.id"
                        data-fact-index-item
                        @click="selectFactRow(item)"
                      >
                        <strong class="draft-scroll__fact-index-label">
                          <span>{{ item.unmatchedEvidence ? "补充核验" : "事实" }}</span>
                          <b>{{ item.unmatchedEvidence ? "附" : String(index + 1).padStart(2, "0") }}</b>
                        </strong>
                      </button>
                    </nav>

                    <article
                      v-if="selectedFactRow"
                      class="draft-scroll__fact-detail"
                      data-fact-evidence-unit
                    >
                      <header
                        class="draft-scroll__fact-detail-heading"
                        :class="{ 'is-unmatched': selectedFactRow.unmatchedEvidence }"
                      >
                        <div
                          v-if="selectedFactRow.unmatchedEvidence"
                          class="draft-scroll__fact-summary-item draft-scroll__fact-summary-item--identity"
                        >
                          <small>{{ selectedFactRow.unmatchedEvidence ? "补充核验" : "当前事实" }}</small>
                          <strong>{{ selectedFactRow.id }}</strong>
                        </div>
                        <template v-if="!selectedFactRow.unmatchedEvidence">
                          <div
                            class="draft-scroll__fact-summary-item draft-scroll__fact-summary-item--finding"
                            :title="selectedFactRow.finding || '暂无建议认定。'"
                          >
                            <small>事实认定</small>
                            <strong>{{ selectedFactRow.finding || "暂无建议认定。" }}</strong>
                          </div>
                          <div
                            class="draft-scroll__fact-summary-item draft-scroll__fact-summary-item--evidence"
                            :title="selectedFactRow.evidenceBasis.length ? selectedFactRow.evidenceBasis.join('、') : '暂无'"
                          >
                            <small>证据依据</small>
                            <strong>{{ selectedFactRow.evidenceBasis.length ? selectedFactRow.evidenceBasis.join("、") : "暂无" }}</strong>
                          </div>
                          <div
                            class="draft-scroll__fact-summary-item draft-scroll__fact-summary-item--policy"
                            :title="selectedFactRow.policyBasis.length ? selectedFactRow.policyBasis.join('、') : '暂无'"
                          >
                            <small>规则依据</small>
                            <strong>{{ selectedFactRow.policyBasis.length ? selectedFactRow.policyBasis.join("、") : "暂无" }}</strong>
                          </div>
                        </template>
                        <span v-if="selectedFactRow.displayConfidence" class="draft-scroll__fact-summary-score">
                          <small>综合可信分</small>
                          <strong>{{ selectedFactRow.displayConfidence }}</strong>
                        </span>
                      </header>
                      <div class="draft-scroll__fact-detail-content">
                        <div class="draft-scroll__fact-record">
                          <div v-if="selectedFactRow.detailedAssessments.length" class="draft-scroll__finding draft-scroll__fact-assessments">
                            <small>{{ selectedFactRow.unmatchedEvidence ? "未关联事实的证据核验" : "证据核验" }}</small>
                          <ol class="draft-scroll__analysis-list">
                              <li v-for="(assessment, assessmentIndex) in selectedFactRow.detailedAssessments" :key="`${assessment.id}-${assessmentIndex}`">
                            <header>
                              <strong>{{ assessment.id }}</strong>
                            </header>
                            <p>{{ assessment.analysis || "暂无核验说明。" }}</p>
                            <dl>
                              <div v-if="assessment.supportedBy.length">
                                <dt>支持证据</dt>
                                <dd>{{ assessment.supportedBy.join("、") }}</dd>
                              </div>
                              <div v-if="assessment.contradictedBy.length">
                                <dt>相反证据</dt>
                                <dd>{{ assessment.contradictedBy.join("、") }}</dd>
                              </div>
                              <div v-if="assessment.missingEvidence !== null">
                                <dt>证据缺口</dt>
                                <dd>{{ assessment.missingEvidence ? "仍有缺失" : "未发现" }}</dd>
                              </div>
                              <div v-if="assessment.additionalFactIds.length">
                                    <dt>{{ selectedFactRow.unmatchedEvidence ? "关联事实" : "同时关联" }}</dt>
                                <dd>{{ assessment.additionalFactIds.join("、") }}</dd>
                              </div>
                              <div v-if="assessment.weight">
                                <dt>证明权重</dt>
                                <dd>{{ assessment.weight }}</dd>
                              </div>
                              <div v-if="assessment.limitations.length">
                                <dt>采信限制</dt>
                                <dd>{{ assessment.limitations.join("、") }}</dd>
                              </div>
                            </dl>
                          </li>
                          </ol>
                        </div>
                          <div v-if="!selectedFactRow.unmatchedEvidence && selectedFactRow.evidenceGap" class="draft-scroll__finding draft-scroll__fact-gap">
                          <small>证据缺口</small>
                            <p>{{ selectedFactRow.evidenceGap }}</p>
                          </div>
                        </div>
                      </div>
                    </article>
                  </div>
                </div>
              </section>

              <section data-draft-section="policy">
                <header class="draft-scroll__section-heading">
                  <span>贰</span>
                  <div>
                    <small>规则适用</small>
                    <h3>规则适用论证</h3>
                  </div>
                  <em>{{ policyApplications.length }} 项</em>
                </header>
                <div class="draft-scroll__module-content">
                  <p v-if="!policyApplications.length" class="draft-scroll__empty">暂无结构化规则适用说明。</p>
                  <ol v-else class="draft-scroll__analysis-list">
                    <li v-for="(item, index) in policyApplications" :key="`${item.issueId}-${index}`">
                      <header>
                        <strong>{{ item.issueId }}</strong>
                        <span v-if="item.applicable !== null">{{ item.applicable ? "规则适用" : "暂不适用" }}</span>
                      </header>
                      <b
                        v-if="item.rule"
                        class="draft-scroll__rule"
                        :title="item.rule"
                      >
                        {{ item.rule }}
                      </b>
                      <p>{{ item.rationale || "暂无适用理由。" }}</p>
                      <dl v-if="item.factIds.length || item.limitations.length">
                        <div v-if="item.factIds.length">
                          <dt>关联事实</dt>
                          <dd>{{ item.factIds.join("、") }}</dd>
                        </div>
                        <div v-if="item.limitations.length">
                          <dt>适用限制</dt>
                          <dd>{{ item.limitations.join("、") }}</dd>
                        </div>
                      </dl>
                    </li>
                  </ol>
                </div>
              </section>

              <section class="draft-scroll__focus" data-draft-section="attention">
                <header class="draft-scroll__section-heading">
                  <span>叁</span>
                  <div>
                    <small>终审关注</small>
                    <h3>重点关注事项</h3>
                  </div>
                  <em>{{ reviewFocus.length }} 项</em>
                </header>
                <div class="draft-scroll__module-content">
                  <p v-if="!reviewFocus.length">暂无额外终审关注点。</p>
                  <ol v-else>
                    <li v-for="(item, index) in reviewFocus" :key="`focus-${index}`">
                      <b>{{ String(index + 1).padStart(2, "0") }}</b>
                      <span>{{ item }}</span>
                    </li>
                  </ol>
                </div>
              </section>
                <section class="draft-scroll__jury" data-draft-section="jury">
                  <header class="draft-scroll__section-heading">
                    <span>肆</span>
                    <div>
                      <small>陪审复核</small>
                      <h3>陪审团评审意见</h3>
                    </div>
                    <em>{{ juryReviewExchanges.length }} 项</em>
                  </header>
                  <div class="draft-scroll__module-content">
                    <p v-if="!juryReviewExchanges.length" class="draft-scroll__empty">
                      暂无陪审团评审记录。
                    </p>
                    <ol v-else class="draft-scroll__jury-list">
                      <li
                        v-for="(item, index) in juryReviewExchanges"
                        :key="item.reference || `jury-${index}`"
                      >
                        <header>
                          <strong>{{ item.label }}</strong>
                          <span v-if="item.severity">{{ item.severity }}</span>
                        </header>
                        <div class="draft-scroll__jury-opinion">
                          <small>陪审意见</small>
                          <p>{{ item.opinion || "暂无具体意见。" }}</p>
                          <ul v-if="item.basis.length">
                            <li v-for="(basis, basisIndex) in item.basis" :key="`basis-${basisIndex}`">
                              {{ basis }}
                            </li>
                          </ul>
                        </div>
                        <div class="draft-scroll__judge-response">
                          <small>
                            法官回复
                            <b v-if="item.disposition">{{ item.disposition }}</b>
                          </small>
                          <p>{{ item.response || "法官未单独回复本项。" }}</p>
                        </div>
                      </li>
                    </ol>
                  </div>
                </section>
              </div>
            </div>

            <footer class="draft-scroll__notice" data-draft-boundary>
              <strong>边界声明</strong>
              <p>本卷轴为庭审最后输出的非最终草案。平台终审完成前，不产生退款、赔付或其他执行效力。</p>
            </footer>
          </article>
          <div class="draft-scroll-frame__rod draft-scroll-frame__rod--bottom" aria-hidden="true">
            <i /><span /><i />
          </div>
        </section>
      </div>

      <nav class="draft-room__actions" aria-label="裁决草案操作">
        <button type="button" class="draft-room__back" @click="router.push('/disputes')">
          返回总览
        </button>
        <button
          v-if="canEnterReview"
          type="button"
          class="draft-room__review"
          data-enter-review-room
          :disabled="enteringReview"
          @click="enterReviewRoom"
        >
          {{ enteringReview ? "正在进入终审室" : "进入终审室" }}
        </button>
      </nav>
      <p v-if="error" class="draft-room__error" role="alert">{{ error }}</p>
    </main>
  </RoomShell>
</template>

<style scoped>
:deep(.room-shell) {
  gap: 14px;
  min-height: auto;
}
:deep(.room-shell__header),
:deep(.room-shell__header > div),
:deep(.room-shell__status),
:deep(.room-shell__workspace) {
  min-width: 0;
}
:deep(.room-shell__agent .digital-human) {
  border-color: #ecd9ad;
  background: linear-gradient(145deg, #fffaf0, #f6f8ff 56%, #eef8ff);
  box-shadow: 0 16px 38px #536c8b12;
}
.draft-room__stage {
  display: grid;
  min-width: 134px;
  justify-items: end;
  gap: 4px;
  padding: 10px 12px;
  color: #526178;
  background: #fffdf7;
  border: 1px solid #e7dcc2;
  border-radius: 8px;
}
.draft-room__stage small {
  color: #8d7b63;
  font-size: 10px;
}
.draft-room__stage strong {
  color: #6f4f3b;
  font-size: 13px;
}
.draft-room {
  --draft-panel-height: 740px;
  display: grid;
  box-sizing: border-box;
  grid-template-rows: var(--draft-panel-height) auto auto;
  gap: 10px;
  height: auto;
  width: 100%;
  min-width: 0;
  min-height: 0;
  overflow: visible;
  color: #24394a;
}
.draft-room__document {
  display: grid;
  height: var(--draft-panel-height);
  min-width: 0;
  min-height: 0;
  overflow: visible;
}
.draft-scroll__title span,
.draft-scroll__recommendation > span,
.draft-scroll__body > span,
.draft-scroll__focus > span {
  color: #237a72;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0;
}
.draft-scroll-state {
  display: grid;
  box-sizing: border-box;
  width: min(760px, calc(100% - 24px));
  height: 100%;
  min-height: 0;
  margin: 0 auto;
  place-content: center;
  justify-items: center;
  gap: 12px;
  border: 1px dashed #8ebcb5;
  border-radius: 8px;
  background: #f8fcfb;
  text-align: center;
}
.draft-scroll-state span { width: 58px; height: 8px; background: #237a72; }
.draft-scroll-state p { margin: 0; color: #607680; }
.draft-scroll-frame {
  display: grid;
  grid-template-rows: 24px minmax(0, 1fr) 24px;
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  margin: 0;
  overflow: visible;
}
.draft-scroll-frame__rod {
  position: relative;
  z-index: 2;
  display: grid;
  box-sizing: border-box;
  width: 100%;
  height: 24px;
  margin: 0;
  grid-template-columns: 30px minmax(0, 1fr) 30px;
  align-items: center;
}
.draft-scroll-frame__rod span { box-sizing: border-box; width: 100%; height: 14px; border: 2px solid #754834; background: #b56e43; }
.draft-scroll-frame__rod i { box-sizing: border-box; width: 30px; height: 22px; border: 2px solid #613b2c; border-radius: 5px; background: #d99358; }
.draft-scroll-frame__rod--top { transform: translateY(3px); }
.draft-scroll-frame__rod--bottom { transform: translateY(-3px); }
.draft-scroll {
  --draft-masthead-height: 104px;
  --draft-notice-height: 42px;
  --draft-overview-width: clamp(270px, 28%, 340px);
  position: relative;
  display: grid;
  box-sizing: border-box;
  grid-template-rows:
    var(--draft-masthead-height)
    minmax(0, 1fr)
    var(--draft-notice-height);
  min-width: 0;
  min-height: 0;
  margin: 0 14px;
  padding: 24px 28px 0 40px;
  overflow: hidden;
  border: 2px solid #d9c9a7;
  border-radius: 4px;
  background: #fffdf6;
  box-shadow: 0 12px 28px rgba(44, 75, 75, .12);
}
.draft-scroll::before,
.draft-scroll::after { content: ""; position: absolute; top: 0; bottom: 0; width: 5px; background: #f0e2c4; }
.draft-scroll::before { left: 8px; }
.draft-scroll::after { right: 8px; }
.draft-scroll__masthead { display: grid; box-sizing: border-box; grid-template-columns: var(--draft-overview-width) minmax(0, 1fr) 62px; align-items: center; min-height: 104px; gap: 0; padding-bottom: 14px; border-bottom: 2px solid #24394a; }
.draft-scroll__title { min-width: 0; }
.draft-scroll__title h2 { margin: 6px 0 3px; color: #182d38; font-size: 27px; line-height: 1.2; letter-spacing: 0; }
.draft-scroll__title p { margin: 0; color: #6b6e67; font-size: 13px; }
.draft-scroll__title > small { display: block; margin-top: 5px; color: #8d7b63; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 9px; overflow-wrap: anywhere; }
.draft-scroll__seal { display: grid; width: 62px; height: 62px; justify-self: end; place-content: center; border: 3px double #b9434d; border-radius: 50%; color: #b9434d; font-size: 13px; line-height: 1.25; text-align: center; transform: rotate(-7deg); }
.draft-scroll__summary { display: grid; grid-template-columns: minmax(220px, 1fr) auto; align-items: start; min-width: 0; gap: 18px; padding: 0 14px 0 20px; border-left: 1px solid #d8ccb2; }
.draft-scroll__recommendation { min-width: 0; }
.draft-scroll__recommendation h3 { margin: 4px 0 0; color: #8f303a; font-size: 34px; line-height: 1.1; letter-spacing: 0; overflow-wrap: anywhere; }
.draft-scroll__summary dl { display: grid; grid-template-columns: repeat(2, minmax(82px, auto)); margin: 0; gap: 10px 18px; }
.draft-scroll__summary dl div { display: grid; gap: 3px; }
.draft-scroll__summary dt { color: #797970; font-size: 11px; }
.draft-scroll__summary dd { margin: 0; font-size: 13px; font-weight: 900; white-space: nowrap; }
.draft-scroll__decision-layout { display: grid; grid-template-columns: var(--draft-overview-width) minmax(0, 1fr); min-width: 0; min-height: 0; overflow: hidden; scrollbar-width: none !important; -ms-overflow-style: none; }
.draft-scroll__overview { display: grid; min-width: 0; min-height: 0; overflow: hidden; border-right: 1px solid #d8ccb2; }
.draft-scroll__body { display: grid; grid-template-rows: auto minmax(0, 1fr); min-width: 0; min-height: 0; padding: 16px 20px 16px 0; }
.draft-scroll__module-heading > span { color: #237a72; font-size: 10px; font-weight: 900; }
.draft-scroll__module-heading h3 { margin: 5px 0 0; font-size: 17px; letter-spacing: 0; }
.draft-scroll__module-heading h3 small { margin-left: 6px; color: #8d7b63; font-size: 10px; font-weight: 700; }
.draft-scroll__module-content { min-width: 0; min-height: 0; padding-right: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: auto; scrollbar-width: none !important; -ms-overflow-style: none; }
.draft-scroll__body .draft-scroll__module-content p, .draft-scroll__focus .draft-scroll__module-content > p { margin: 9px 0 0; color: #33464e; font-size: 14px; line-height: 1.65; white-space: pre-wrap; }
.draft-scroll__remedies { margin-top: 12px; padding-top: 12px; border-top: 1px solid #e3d9c4; }
.draft-scroll__remedies h4 { margin: 0; color: #182d38; font-size: 14px; }
.draft-scroll__remedies ol { display: grid; margin: 8px 0 0; padding: 0; gap: 7px; list-style: none; }
.draft-scroll__remedies li { display: grid; gap: 3px; color: #33464e; font-size: 14px; line-height: 1.65; }
.draft-scroll__remedies li strong { color: #8f303a; }
.draft-scroll__remedies li span { display: block; }
.draft-scroll__focus ol { display: grid; margin: 9px 0 0; padding: 0; gap: 8px; list-style: none; }
.draft-scroll__focus li { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 10px; align-items: start; font-size: 13px; line-height: 1.55; }
.draft-scroll__focus li b { color: #b9434d; font-size: 11px; }
.draft-scroll__section-heading { display: flex; align-items: center; gap: 12px; }
.draft-scroll__section-heading > span { display: grid; width: 30px; height: 30px; flex: 0 0 30px; place-content: center; border: 1px solid #237a72; border-radius: 50%; color: #237a72; font-size: 12px; font-weight: 900; }
.draft-scroll__section-heading small { color: #8d7b63; font-size: 9px; font-weight: 900; }
.draft-scroll__section-heading h3 { margin: 2px 0 0; font-size: 15px; letter-spacing: 0; }
.draft-scroll__section-heading em { margin-left: auto; color: #8d7b63; font-size: 10px; font-style: normal; font-weight: 800; white-space: nowrap; }
.draft-scroll__analysis-board { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); grid-template-rows: repeat(2, minmax(0, 1fr)); min-width: 0; min-height: 0; overflow: hidden; }
.draft-scroll__analysis-board > section { display: grid; grid-template-rows: auto minmax(0, 1fr); min-width: 0; min-height: 0; padding: 14px 13px; }
.draft-scroll__analysis-board > section:nth-child(even) { border-left: 1px solid #d8ccb2; }
.draft-scroll__analysis-board > section:nth-child(n + 3) { border-top: 1px solid #d8ccb2; }
.draft-scroll__module-content--facts { padding-right: 0; overflow: hidden; }
.draft-scroll__fact-workspace { display: grid; box-sizing: border-box; grid-template-columns: minmax(88px, .24fr) minmax(0, 1fr); width: 100%; height: 100%; min-width: 0; min-height: 0; padding-top: 10px; }
.draft-scroll__fact-index { display: grid; align-content: start; min-width: 0; min-height: 0; padding-right: 6px; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: none !important; -ms-overflow-style: none; border-right: 1px solid #e3d9c4; }
.draft-scroll__fact-index button { display: grid; grid-template-columns: minmax(0, 1fr); align-items: center; min-width: 0; padding: 10px 7px; border: 0; border-bottom: 1px solid #e3d9c4; color: inherit; font: inherit; text-align: left; background: transparent; cursor: pointer; }
.draft-scroll__fact-index button.is-active { padding-left: 5px; border-left: 2px solid #237a72; }
.draft-scroll__fact-index button:focus-visible { outline: 1px solid #237a72; outline-offset: -2px; }
.draft-scroll__fact-index-label { display: flex; align-items: baseline; min-width: 0; gap: 5px; font-size: 11px; white-space: nowrap; }
.draft-scroll__fact-index-label span { min-width: 0; overflow: hidden; color: #263c46; text-overflow: ellipsis; }
.draft-scroll__fact-index-label b { flex: 0 0 auto; color: #b9434d; font-size: 10px; font-weight: 900; }
.draft-scroll__fact-detail { display: grid; grid-template-rows: auto minmax(0, 1fr); min-width: 0; min-height: 0; padding-left: 10px; }
.draft-scroll__fact-detail-heading { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); align-items: start; min-width: 0; padding: 0 0 8px; gap: 6px; overflow: visible; border-bottom: 1px solid #e3d9c4; }
.draft-scroll__fact-detail-heading.is-unmatched { grid-template-columns: minmax(0, 1fr) auto; }
.draft-scroll__fact-summary-item,
.draft-scroll__fact-summary-score { display: grid; min-width: 0; gap: 2px; }
.draft-scroll__fact-summary-item { justify-items: center; text-align: center; }
.draft-scroll__fact-detail-heading small { color: #8d7b63; font-size: 9px; font-weight: 900; white-space: nowrap; }
.draft-scroll__fact-detail-heading strong { min-width: 0; color: #263c46; font-size: 11px; line-height: 1.35; overflow-wrap: anywhere; }
.draft-scroll__fact-summary-item:first-child strong { font-size: 12px; }
.draft-scroll__fact-summary-score { justify-items: center; text-align: center; }
.draft-scroll__fact-summary-score small,
.draft-scroll__fact-summary-score strong { color: #237a72; }
.draft-scroll__fact-summary-score strong { white-space: nowrap; }
.draft-scroll__fact-detail-content { min-width: 0; min-height: 0; padding-right: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: auto; scrollbar-width: none !important; -ms-overflow-style: none; }
.draft-scroll__module-content::-webkit-scrollbar,
.draft-scroll__fact-index::-webkit-scrollbar,
.draft-scroll__fact-detail-content::-webkit-scrollbar,
.draft-scroll__decision-layout::-webkit-scrollbar { display: none !important; width: 0 !important; height: 0 !important; }
.draft-scroll__finding { min-width: 0; margin-top: 9px; }
.draft-scroll__finding small { color: #8d7b63; font-size: 10px; font-weight: 900; }
.draft-scroll__fact-record p { margin: 5px 0 0; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.draft-scroll__fact-record { display: grid; min-width: 0; margin-top: 8px; gap: 8px; }
.draft-scroll__fact-record > .draft-scroll__finding { margin-top: 0; }
.draft-scroll__fact-assessments .draft-scroll__analysis-list { margin-top: 0; }
.draft-scroll__fact-assessments .draft-scroll__analysis-list > li:first-child { padding-top: 6px; border-top: 0; }
.draft-scroll__analysis-list { display: grid; margin: 10px 0 0; padding: 0; gap: 0; list-style: none; }
.draft-scroll__analysis-list > li { padding: 14px 0; border-top: 1px solid #e3d9c4; }
.draft-scroll__analysis-list > li:last-child { padding-bottom: 0; }
.draft-scroll__analysis-list header { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; }
.draft-scroll__analysis-list header strong { color: #263c46; font-size: 12px; overflow-wrap: anywhere; }
.draft-scroll__analysis-list header span { flex: 0 0 auto; color: #237a72; font-size: 10px; font-weight: 900; }
.draft-scroll__analysis-list p { margin: 7px 0 0; color: #40535a; font-size: 13px; line-height: 1.65; overflow-wrap: anywhere; }
.draft-scroll__analysis-list dl { display: grid; margin: 10px 0 0; gap: 5px; }
.draft-scroll__analysis-list dl div { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 8px; font-size: 11px; line-height: 1.5; }
.draft-scroll__analysis-list dt { color: #8d7b63; font-weight: 900; }
.draft-scroll__analysis-list dd { margin: 0; overflow-wrap: anywhere; }
.draft-scroll__rule { display: block; margin-top: 7px; color: #8f303a; font-size: 12px; }
.draft-scroll__jury-list { display: grid; margin: 10px 0 0; padding: 0; list-style: none; }
.draft-scroll__jury-list > li { padding: 12px 0; border-top: 1px solid #e3d9c4; }
.draft-scroll__jury-list > li:first-child { padding-top: 4px; border-top: 0; }
.draft-scroll__jury-list > li > header { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.draft-scroll__jury-list > li > header strong { color: #263c46; font-size: 12px; }
.draft-scroll__jury-list > li > header span { color: #8f303a; font-size: 10px; font-weight: 900; }
.draft-scroll__jury-opinion,
.draft-scroll__judge-response { margin-top: 8px; padding-left: 10px; border-left: 2px solid #b9434d; }
.draft-scroll__judge-response { border-left-color: #237a72; }
.draft-scroll__jury-opinion small,
.draft-scroll__judge-response small { display: block; color: #8f303a; font-size: 10px; font-weight: 900; }
.draft-scroll__judge-response small { color: #237a72; }
.draft-scroll__judge-response small b { margin-left: 6px; font-size: 9px; }
.draft-scroll__jury-opinion p,
.draft-scroll__judge-response p { margin: 4px 0 0; color: #40535a; font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.draft-scroll__jury-opinion ul { display: grid; margin: 5px 0 0; padding-left: 16px; gap: 3px; color: #6f645b; font-size: 10px; line-height: 1.45; }
.draft-scroll__empty { margin: 10px 0 0; color: #72756f; font-size: 13px; }
.draft-scroll__notice { display: flex; box-sizing: border-box; align-items: center; align-self: end; justify-content: center; width: 100%; height: var(--draft-notice-height); gap: 10px; border-top: 1px solid #d8ccb2; text-align: center; }
.draft-scroll__notice strong { color: #b9434d; }
.draft-scroll__notice p { margin: 0; max-width: 760px; color: #6f645b; font-size: 12px; line-height: 1.6; }
.draft-room__actions { display: flex; width: 100%; margin: 0; justify-content: flex-end; gap: 10px; }
.draft-room__actions button { min-height: 44px; padding: 0 20px; border-radius: 6px; font-weight: 800; cursor: pointer; }
.draft-room__back { border: 1px solid #8eb3af; color: #315f5b; background: #f8fcfb; }
.draft-room__review { border: 1px solid #17675f; color: #fff; background: #237a72; }
.draft-room__review:disabled { cursor: progress; opacity: .65; }
.draft-room__error { width: 100%; margin: 0; color: #a32f3b; font-size: 12px; text-align: right; overflow-wrap: anywhere; }
@container room-workspace (max-width: 1120px) {
  .draft-scroll { --draft-masthead-height: 140px; --draft-overview-width: clamp(250px, 30%, 310px); padding-inline: 30px 24px; }
  .draft-scroll__masthead { grid-template-columns: var(--draft-overview-width) minmax(0, 1fr) 58px; }
  .draft-scroll__summary { grid-template-columns: 1fr; align-items: start; gap: 12px; padding-left: 22px; }
  .draft-scroll__summary dl { justify-content: start; }
  .draft-scroll__decision-layout { grid-template-columns: var(--draft-overview-width) minmax(0, 1fr); }
}
@media (max-width: 700px) {
  :deep(.room-shell__header) { align-items: stretch; }
  .draft-room__stage { width: 100%; justify-items: start; }
  .draft-scroll-frame { width: 100%; }
  .draft-scroll-frame__rod { grid-template-columns: 22px minmax(0, 1fr) 22px; }
  .draft-scroll-frame__rod i { width: 22px; }
  .draft-scroll { --draft-masthead-height: 230px; margin-inline: 7px; padding: 24px 22px 0; }
  .draft-scroll__title h2 { font-size: 25px; }
  .draft-scroll__masthead { grid-template-columns: minmax(0, 1fr) 54px; align-items: center; }
  .draft-scroll__summary { grid-column: 1 / -1; grid-row: 2; padding: 14px 0 0; border-top: 1px solid #d8ccb2; border-left: 0; }
  .draft-scroll__seal { grid-column: 2; grid-row: 1; width: 54px; height: 54px; font-size: 12px; }
  .draft-scroll__decision-layout { display: grid; grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(240px, .8fr) auto; overflow-y: auto; }
  .draft-scroll__overview { min-height: 240px; border-right: 0; border-bottom: 1px solid #d8ccb2; }
  .draft-scroll__body { padding-right: 0; }
  .draft-scroll__analysis-board { grid-template-columns: minmax(0, 1fr); grid-template-rows: repeat(4, minmax(300px, auto)); overflow: visible; }
  .draft-scroll__summary { align-items: start; gap: 14px; }
  .draft-scroll__summary dl { grid-template-columns: 1fr; gap: 7px; }
  .draft-scroll__summary dl div { grid-template-columns: 72px minmax(0, 1fr); }
  .draft-scroll__analysis-board > section { min-height: 300px; padding: 16px 0; }
  .draft-scroll__analysis-board > section:nth-child(even) { border-left: 0; }
  .draft-scroll__analysis-board > section:nth-child(n + 2) { border-top: 1px solid #d8ccb2; }
  .draft-scroll__fact-workspace { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); padding-top: 8px; }
  .draft-scroll__fact-index { display: flex; padding: 0 0 8px; overflow-x: auto; overflow-y: hidden; border-right: 0; border-bottom: 1px solid #e3d9c4; }
  .draft-scroll__fact-index button { flex: 0 0 132px; border-right: 1px solid #e3d9c4; border-bottom: 0; }
  .draft-scroll__fact-index button.is-active { padding-left: 7px; border-bottom: 2px solid #237a72; border-left: 0; }
  .draft-scroll__fact-detail { padding: 10px 0 0; }
  .draft-scroll__fact-detail-heading { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .draft-scroll__fact-summary-score { justify-items: start; text-align: left; }
  .draft-scroll__fact-record > .draft-scroll__finding { padding: 6px 0; }
  .draft-scroll__notice { display: grid; gap: 5px; }
  .draft-room__actions { display: grid; }
  .draft-room__actions button { width: 100%; }
}
</style>
