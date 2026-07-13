<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { disputeApi } from "../../api/disputes";
import { reviewApi } from "../../api/review";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import { humanizeDossierText } from "../../utils/displayText";

const props = defineProps({
  initialOutcome: { type: Object, default: null },
  viewerRole: { type: String, default: "" },
  startReviewAction: { type: Function, default: null },
});

const route = useRoute();
const router = useRouter();
const mountedCaseId = String(route.params.caseId || "");
const outcome = ref(props.initialOutcome);
const loading = ref(props.initialOutcome === null);
const error = ref("");
const enteringReview = ref(false);
let reviewEntryGeneration = 0;

const caseId = computed(() => String(outcome.value?.case_id || mountedCaseId));
const role = computed(() => props.viewerRole || actor.role);
const historyMode = computed(() => route.query.view === "history");
const draft = computed(
  () => outcome.value?.adjudication_draft || outcome.value?.adjudicationDraft || null,
);
const reviewTaskId = computed(
  () => outcome.value?.review_task_id || outcome.value?.reviewTaskId || "",
);
const reviewTaskStatus = computed(
  () => outcome.value?.review_task_status || outcome.value?.reviewTaskStatus || "",
);
const canEnterReview = computed(
  () =>
    !historyMode.value &&
    role.value === "PLATFORM_REVIEWER" &&
    Boolean(reviewTaskId.value) &&
    ["PENDING", "ASSIGNED", "IN_REVIEW"].includes(reviewTaskStatus.value),
);
const draftVersion = computed(() => draft.value?.draft_version || draft.value?.draftVersion || 1);
const draftStatusLabel = computed(() =>
  reviewTaskStatus.value === "IN_REVIEW" ? "终审进行中" : "待进入平台终审",
);
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
const recommendation = computed(() =>
  readable(draft.value?.recommended_decision || draft.value?.recommendedDecision || "待终审确认"),
);
const draftText = computed(() =>
  readable(draft.value?.draft_text || draft.value?.draftText || "暂无草案正文。"),
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
      };
    }
    return {
      id: identifier(item.issue_id || item.issueId) || `争议项 ${String(index + 1).padStart(2, "0")}`,
      finding: readable(
        item.suggested_finding || item.suggestedFinding || item.finding || item.neutral_analysis,
      ),
      evidenceBasis: identifiers(item.evidence_basis || item.evidenceBasis || item.supported_by),
      policyBasis: identifiers(item.policy_basis || item.policyBasis || item.rule_code),
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
        };
      }
      return {
        id: identifier(item.issue_id || item.issueId) || `核验 ${String(index + 1).padStart(2, "0")}`,
        analysis: readable(item.neutral_analysis || item.neutralAnalysis || item.assessment || item.finding),
        supportedBy: identifiers(item.supported_by || item.supportedBy),
        contradictedBy: identifiers(item.contradicted_by || item.contradictedBy),
        missingEvidence:
          typeof (item.missing_evidence ?? item.missingEvidence) === "boolean"
            ? item.missing_evidence ?? item.missingEvidence
            : null,
        confidence: score(item.confidence),
      };
    },
  ),
);
const policyApplications = computed(() =>
  rawList(draft.value?.policy_application || draft.value?.policyApplication).map((item, index) => {
    if (!isRecord(item)) {
      return {
        issueId: `规则 ${String(index + 1).padStart(2, "0")}`,
        rule: "",
        rationale: readable(item),
        applicable: null,
        limitations: [],
      };
    }
    const code = identifier(item.rule_code || item.ruleCode || item.rule);
    const version = readable(item.rule_version || item.ruleVersion);
    return {
      issueId: identifier(item.issue_id || item.issueId) || `规则 ${String(index + 1).padStart(2, "0")}`,
      rule: [code, version ? `V${version}` : ""].filter(Boolean).join(" · "),
      rationale: readable(item.rationale || item.application || item.description),
      applicable: typeof item.applicable === "boolean" ? item.applicable : null,
      limitations: list(item.limitations),
    };
  }),
);
const reviewFocus = computed(() =>
  list(draft.value?.reviewer_attention || draft.value?.reviewerAttention),
);

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

function identifier(value) {
  if (value == null) return "";
  return String(value).trim();
}

function score(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "";
  return `${Math.round((number <= 1 ? number * 100 : number))}/100`;
}

function readable(value) {
  if (value == null) return "";
  if (typeof value === "string") return humanizeDossierText(value, { fallback: "" });
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
    eyebrow="ADJUDICATION CHAMBER"
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
                <p>AI 法官依据已封存庭审记录形成</p>
              </div>

              <section class="draft-scroll__summary" data-draft-summary>
                <div class="draft-scroll__recommendation">
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
                    <dd>非最终裁决</dd>
                  </div>
                  <div>
                    <dt>草案版本</dt>
                    <dd>第 {{ draftVersion }} 版</dd>
                  </div>
                </dl>
              </section>

              <strong class="draft-scroll__seal" data-draft-seal>草案<br />待审</strong>
            </header>

            <div class="draft-scroll__overview">
              <section class="draft-scroll__body" data-draft-reasoning>
                <header class="draft-scroll__module-heading">
                  <span>法官裁判理由</span>
                  <h3>庭审结论摘要</h3>
                </header>
                <div class="draft-scroll__module-content">
                  <p>{{ draftText }}</p>
                </div>
              </section>

              <section class="draft-scroll__focus" data-draft-section="attention">
                <header class="draft-scroll__module-heading">
                  <span>人工终审复核</span>
                  <h3>重点关注事项</h3>
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
            </div>

            <div class="draft-scroll__analysis-board">
              <section class="draft-scroll__issues" data-draft-section="facts">
                <header class="draft-scroll__section-heading">
                  <span>壹</span>
                  <div>
                    <small>ISSUE FINDINGS</small>
                    <h3>争议项认定与依据映射</h3>
                  </div>
                </header>
                <div class="draft-scroll__module-content">
                  <p v-if="!issueFindings.length" class="draft-scroll__empty">暂无结构化争议项认定。</p>
                  <ol v-else class="draft-scroll__issue-list">
                    <li v-for="(item, index) in issueFindings" :key="`${item.id}-${index}`">
                      <header>
                        <span>{{ String(index + 1).padStart(2, "0") }}</span>
                        <strong>{{ item.id }}</strong>
                      </header>
                      <div class="draft-scroll__finding">
                        <small>建议认定</small>
                        <p>{{ item.finding || "暂无建议认定。" }}</p>
                      </div>
                      <div class="draft-scroll__basis">
                        <div>
                          <small>证据依据</small>
                          <p>{{ item.evidenceBasis.length ? item.evidenceBasis.join("、") : "暂无明确证据编号" }}</p>
                        </div>
                        <div>
                          <small>规则依据</small>
                          <p>{{ item.policyBasis.length ? item.policyBasis.join("、") : "暂无明确规则编号" }}</p>
                        </div>
                      </div>
                    </li>
                  </ol>
                </div>
              </section>

              <section data-draft-section="evidence">
                <header class="draft-scroll__section-heading">
                  <span>贰</span>
                  <div>
                    <small>EVIDENCE CROSS-CHECK</small>
                    <h3>证据交叉核验</h3>
                  </div>
                </header>
                <div class="draft-scroll__module-content">
                  <p v-if="!evidenceAssessments.length" class="draft-scroll__empty">暂无结构化证据核验意见。</p>
                  <ol v-else class="draft-scroll__analysis-list">
                    <li v-for="(item, index) in evidenceAssessments" :key="`${item.id}-${index}`">
                      <header>
                        <strong>{{ item.id }}</strong>
                        <span v-if="item.confidence">可信分 {{ item.confidence }}</span>
                      </header>
                      <p>{{ item.analysis || "暂无核验说明。" }}</p>
                      <dl>
                        <div v-if="item.supportedBy.length">
                          <dt>支持证据</dt>
                          <dd>{{ item.supportedBy.join("、") }}</dd>
                        </div>
                        <div v-if="item.contradictedBy.length">
                          <dt>相反证据</dt>
                          <dd>{{ item.contradictedBy.join("、") }}</dd>
                        </div>
                        <div v-if="item.missingEvidence !== null">
                          <dt>证据缺口</dt>
                          <dd>{{ item.missingEvidence ? "仍有缺失" : "未发现" }}</dd>
                        </div>
                      </dl>
                    </li>
                  </ol>
                </div>
              </section>

              <section data-draft-section="policy">
                <header class="draft-scroll__section-heading">
                  <span>叁</span>
                  <div>
                    <small>RULE APPLICATION</small>
                    <h3>规则适用论证</h3>
                  </div>
                </header>
                <div class="draft-scroll__module-content">
                  <p v-if="!policyApplications.length" class="draft-scroll__empty">暂无结构化规则适用说明。</p>
                  <ol v-else class="draft-scroll__analysis-list">
                    <li v-for="(item, index) in policyApplications" :key="`${item.issueId}-${index}`">
                      <header>
                        <strong>{{ item.issueId }}</strong>
                        <span v-if="item.applicable !== null">{{ item.applicable ? "规则适用" : "暂不适用" }}</span>
                      </header>
                      <b v-if="item.rule" class="draft-scroll__rule">{{ item.rule }}</b>
                      <p>{{ item.rationale || "暂无适用理由。" }}</p>
                      <dl v-if="item.limitations.length">
                        <div>
                          <dt>适用限制</dt>
                          <dd>{{ item.limitations.join("、") }}</dd>
                        </div>
                      </dl>
                    </li>
                  </ol>
                </div>
              </section>
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
  grid-template-rows: minmax(0, 1fr) auto auto;
  gap: 10px;
  height: var(--draft-panel-height);
  width: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  color: #24394a;
}
.draft-room__document {
  display: grid;
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
  --draft-overview-height: 136px;
  --draft-notice-height: 42px;
  position: relative;
  display: grid;
  box-sizing: border-box;
  grid-template-rows:
    var(--draft-masthead-height)
    var(--draft-overview-height)
    minmax(220px, 1fr)
    var(--draft-notice-height);
  min-width: 0;
  min-height: 0;
  margin: 0 14px;
  padding: 24px 40px 0;
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
.draft-scroll__masthead { display: grid; box-sizing: border-box; grid-template-columns: minmax(250px, .8fr) minmax(480px, 1.8fr) 62px; align-items: center; min-height: 0; gap: 32px; padding-bottom: 14px; border-bottom: 2px solid #24394a; }
.draft-scroll__title { min-width: 0; }
.draft-scroll__title h2 { margin: 6px 0 3px; color: #182d38; font-size: 27px; line-height: 1.2; letter-spacing: 0; }
.draft-scroll__title p { margin: 0; color: #6b6e67; font-size: 13px; }
.draft-scroll__seal { display: grid; width: 62px; height: 62px; place-content: center; border: 3px double #b9434d; border-radius: 50%; color: #b9434d; font-size: 13px; line-height: 1.25; text-align: center; transform: rotate(-7deg); }
.draft-scroll__summary { display: grid; grid-template-columns: minmax(220px, 1fr) auto; align-items: end; min-width: 0; gap: 26px; padding-left: 32px; border-left: 1px solid #d8ccb2; }
.draft-scroll__recommendation { min-width: 0; }
.draft-scroll__recommendation h3 { margin: 7px 0 0; color: #8f303a; font-size: 21px; line-height: 1.35; letter-spacing: 0; overflow-wrap: anywhere; }
.draft-scroll__summary dl { display: grid; grid-template-columns: repeat(3, auto); margin: 0; gap: 18px; }
.draft-scroll__summary dl div { display: grid; gap: 3px; }
.draft-scroll__summary dt { color: #797970; font-size: 11px; }
.draft-scroll__summary dd { margin: 0; font-size: 13px; font-weight: 900; white-space: nowrap; }
.draft-scroll__overview { display: grid; grid-template-columns: minmax(0, 2fr) minmax(300px, 1fr); min-height: 0; border-bottom: 1px solid #d8ccb2; }
.draft-scroll__body, .draft-scroll__focus { display: grid; grid-template-rows: auto minmax(0, 1fr); min-width: 0; min-height: 0; padding: 14px 0; }
.draft-scroll__body { padding-right: 34px; }
.draft-scroll__focus { padding-left: 34px; border-left: 1px solid #d8ccb2; }
.draft-scroll__module-heading > span { color: #237a72; font-size: 10px; font-weight: 900; }
.draft-scroll__module-heading h3 { margin: 5px 0 0; font-size: 17px; letter-spacing: 0; }
.draft-scroll__module-content { min-width: 0; min-height: 0; padding-right: 5px; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; scrollbar-width: thin; }
.draft-scroll__body .draft-scroll__module-content p, .draft-scroll__focus .draft-scroll__module-content > p { margin: 9px 0 0; color: #33464e; font-size: 14px; line-height: 1.65; white-space: pre-wrap; }
.draft-scroll__focus ol { display: grid; margin: 9px 0 0; padding: 0; gap: 8px; list-style: none; }
.draft-scroll__focus li { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 10px; align-items: start; font-size: 13px; line-height: 1.55; }
.draft-scroll__focus li b { color: #b9434d; font-size: 11px; }
.draft-scroll__section-heading { display: flex; align-items: center; gap: 12px; }
.draft-scroll__section-heading > span { display: grid; width: 30px; height: 30px; flex: 0 0 30px; place-content: center; border: 1px solid #237a72; border-radius: 50%; color: #237a72; font-size: 12px; font-weight: 900; }
.draft-scroll__section-heading small { color: #8d7b63; font-size: 9px; font-weight: 900; }
.draft-scroll__section-heading h3 { margin: 2px 0 0; font-size: 17px; letter-spacing: 0; }
.draft-scroll__analysis-board { display: grid; grid-template-columns: minmax(360px, 1.25fr) minmax(280px, 1fr) minmax(280px, 1fr); min-height: 0; border-bottom: 1px solid #d8ccb2; }
.draft-scroll__analysis-board > section { display: grid; grid-template-rows: auto minmax(0, 1fr); min-width: 0; min-height: 0; padding: 16px 26px; }
.draft-scroll__analysis-board > section:first-child { padding-left: 0; }
.draft-scroll__analysis-board > section:last-child { padding-right: 0; }
.draft-scroll__analysis-board > section + section { border-left: 1px solid #d8ccb2; }
.draft-scroll__issue-list, .draft-scroll__analysis-list { display: grid; margin: 10px 0 0; padding: 0; list-style: none; }
.draft-scroll__issue-list > li { display: grid; min-width: 0; padding: 13px 0; border-top: 1px solid #e3d9c4; }
.draft-scroll__issue-list > li:last-child { border-bottom: 1px solid #e3d9c4; }
.draft-scroll__issue-list > li > header, .draft-scroll__finding, .draft-scroll__basis { min-width: 0; }
.draft-scroll__issue-list > li > header { display: flex; align-items: baseline; gap: 8px; }
.draft-scroll__issue-list > li > header span { color: #b9434d; font-size: 10px; }
.draft-scroll__issue-list > li > header strong { font-size: 12px; overflow-wrap: anywhere; }
.draft-scroll__finding { margin-top: 9px; }
.draft-scroll__issue-list small, .draft-scroll__basis small { color: #8d7b63; font-size: 10px; font-weight: 900; }
.draft-scroll__issue-list p { margin: 5px 0 0; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.draft-scroll__basis { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin-top: 10px; padding-top: 10px; gap: 16px; border-top: 1px dashed #e3d9c4; }
.draft-scroll__analysis-list { gap: 0; }
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
  .draft-scroll { display: block; overflow-y: auto; scrollbar-gutter: stable; }
  .draft-scroll__masthead { grid-template-columns: minmax(230px, .8fr) minmax(0, 1.7fr) 58px; gap: 22px; }
  .draft-scroll__summary { grid-template-columns: 1fr; align-items: start; gap: 12px; padding-left: 22px; }
  .draft-scroll__summary dl { justify-content: start; }
  .draft-scroll__overview { min-height: var(--draft-overview-height); }
  .draft-scroll__analysis-board { grid-template-columns: 1fr 1fr; min-height: 400px; }
  .draft-scroll__analysis-board > section:first-child { grid-column: 1 / -1; padding-right: 0; }
  .draft-scroll__analysis-board > section:nth-child(2) { padding-left: 0; border-left: 0; border-top: 1px solid #d8ccb2; }
  .draft-scroll__analysis-board > section:nth-child(3) { border-top: 1px solid #d8ccb2; }
  .draft-scroll__issue-list > li { grid-template-columns: 150px minmax(0, 1fr) minmax(280px, 1fr); padding: 0; }
  .draft-scroll__issue-list > li > header, .draft-scroll__finding, .draft-scroll__basis { padding: 13px 15px; }
  .draft-scroll__issue-list > li > header { display: grid; align-content: start; gap: 5px; padding-left: 0; }
  .draft-scroll__finding { margin-top: 0; border-left: 1px solid #e3d9c4; border-right: 1px solid #e3d9c4; }
  .draft-scroll__basis { margin-top: 0; padding-top: 13px; border-top: 0; }
  .draft-scroll__notice { margin-top: 0; }
}
@media (max-width: 700px) {
  :deep(.room-shell__header) { align-items: stretch; }
  .draft-room__stage { width: 100%; justify-items: start; }
  .draft-scroll-frame { width: 100%; }
  .draft-scroll-frame__rod { grid-template-columns: 22px minmax(0, 1fr) 22px; }
  .draft-scroll-frame__rod i { width: 22px; }
  .draft-scroll { margin-inline: 7px; padding: 24px 22px 0; }
  .draft-scroll__title h2 { font-size: 25px; }
  .draft-scroll__masthead { grid-template-columns: minmax(0, 1fr) 54px; align-items: center; }
  .draft-scroll__summary { grid-column: 1 / -1; grid-row: 2; padding: 14px 0 0; border-top: 1px solid #d8ccb2; border-left: 0; }
  .draft-scroll__seal { grid-column: 2; grid-row: 1; width: 54px; height: 54px; font-size: 12px; }
  .draft-scroll__overview, .draft-scroll__analysis-board { grid-template-columns: 1fr; }
  .draft-scroll__summary { align-items: start; gap: 14px; }
  .draft-scroll__summary dl { grid-template-columns: 1fr; gap: 7px; }
  .draft-scroll__summary dl div { grid-template-columns: 72px minmax(0, 1fr); }
  .draft-scroll__body { padding-right: 0; }
  .draft-scroll__focus { padding-left: 0; border-top: 1px solid #d8ccb2; border-left: 0; }
  .draft-scroll__analysis-board { min-height: 0; }
  .draft-scroll__analysis-board > section, .draft-scroll__analysis-board > section:first-child, .draft-scroll__analysis-board > section:last-child { grid-column: auto; min-height: 180px; padding: 18px 0; }
  .draft-scroll__analysis-board > section + section { border-top: 1px solid #d8ccb2; border-left: 0; }
  .draft-scroll__issue-list > li { grid-template-columns: 1fr; }
  .draft-scroll__issue-list > li > header, .draft-scroll__finding, .draft-scroll__basis { padding: 12px 0; }
  .draft-scroll__finding { border-top: 1px solid #e3d9c4; border-right: 0; border-bottom: 1px solid #e3d9c4; border-left: 0; }
  .draft-scroll__basis { grid-template-columns: 1fr; gap: 10px; }
  .draft-scroll__notice { display: grid; gap: 5px; }
  .draft-room__actions { display: grid; }
  .draft-room__actions button { width: 100%; }
}
</style>
