// 文件作用：前端状态管理文件，维护页面共享状态、缓存和业务动作。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { reactive } from "vue";
import { hearingApi } from "../api/hearing";
import { createResourceState, loadResource } from "./resource";

export const hearingStore = reactive({
  hearing: createResourceState(null),
  deliberation: createResourceState(null),
  activeStage: "C1_ISSUE_FRAMING",
});

export const HEARING_STAGES = [
  ["C1_ISSUE_FRAMING", "争点整理"],
  ["C2_EVIDENCE_GAP", "证据缺口"],
  ["C3_EVIDENCE_REQUEST", "补证请求"],
  ["C4_EVIDENCE_CROSS_CHECK", "证据交叉核验"],
  ["C5_RULE_APPLICATION", "规则适用"],
  ["C6_DRAFT_GENERATION", "裁判草案"],
];

// 业务位置：【前端状态仓库】loadHearing：读取 庭审轮次和法官发言，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function loadHearing(actor, caseId) {
  return loadResource(hearingStore.hearing, () => hearingApi.hearing(actor, caseId));
}

// 业务位置：【前端状态仓库】loadDeliberation：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function loadDeliberation(actor, caseId) {
  return loadResource(
    hearingStore.deliberation,
    () => hearingApi.deliberation(actor, caseId),
  );
}
