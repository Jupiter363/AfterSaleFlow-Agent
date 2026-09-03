// 文件作用：前端状态管理文件，维护页面共享状态、缓存和业务动作。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { reactive } from "vue";
import { hearingApi } from "../api/hearing";
import { HEARING_FLOW_STAGES } from "../utils/hearingFlow";
import { createResourceState } from "./resource";

export const hearingStore = reactive({
  hearing: createResourceState(null),
  activeStage: "COURT_PREPARING",
  scopeKey: null,
  requestGeneration: 0,
});

export const HEARING_STAGES = HEARING_FLOW_STAGES.map(({ code, label }) => [
  code,
  label,
]);

// 业务位置：【前端状态仓库】loadHearing：读取 庭审轮次和法官发言，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function loadHearing(actor, caseId) {
  const scopeKey = hearingScopeKey(actor, caseId);
  if (hearingStore.scopeKey !== scopeKey) {
    resetHearingStore(scopeKey);
  }
  const generation = ++hearingStore.requestGeneration;
  hearingStore.hearing.status = "loading";
  hearingStore.hearing.error = null;
  return hearingApi.hearing(actor, caseId).then(
    (data) => {
      if (generation !== hearingStore.requestGeneration || scopeKey !== hearingStore.scopeKey) {
        return null;
      }
      hearingStore.hearing.data = data;
      hearingStore.hearing.status = data == null ? "empty" : "ready";
      hearingStore.hearing.updatedAt = new Date().toISOString();
      return data;
    },
    (error) => {
      if (generation !== hearingStore.requestGeneration || scopeKey !== hearingStore.scopeKey) {
        return null;
      }
      hearingStore.hearing.error = error;
      hearingStore.hearing.status = "error";
      return null;
    },
  );
}

export function resetHearingStore(scopeKey = null) {
  hearingStore.requestGeneration += 1;
  hearingStore.scopeKey = scopeKey;
  hearingStore.hearing.status = "idle";
  hearingStore.hearing.data = null;
  hearingStore.hearing.error = null;
  hearingStore.hearing.updatedAt = null;
  hearingStore.activeStage = "COURT_PREPARING";
}

function hearingScopeKey(actor, caseId) {
  const actorId = String(actor?.id || "").trim();
  const role = String(actor?.role || "").trim().toUpperCase();
  const requiredCaseId = String(caseId || "").trim();
  if (!actorId || !role || !requiredCaseId) {
    throw new TypeError("hearing store requires actor id, role, and case id");
  }
  return `${role}:${actorId}:${requiredCaseId}`;
}
