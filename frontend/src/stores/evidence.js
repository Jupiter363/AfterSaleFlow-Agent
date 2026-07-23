// 文件作用：前端状态管理文件，维护页面共享状态、缓存和业务动作。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { reactive } from "vue";
import { evidenceApi } from "../api/evidence";
import { createResourceState, loadResource } from "./resource";

export const evidenceStore = reactive({
  dossier: createResourceState(null),
  catalog: createResourceState([]),
  processProjection: createResourceState(null),
  selectedEvidenceId: null,
});

let processProjectionGeneration = 0;
let processProjectionScope = null;

function projectionScopeKey(actor, caseId) {
  return JSON.stringify([
    String(actor?.id ?? ""),
    String(actor?.role ?? ""),
    String(caseId ?? ""),
  ]);
}

async function loadProcessProjection(actor, caseId) {
  const resource = evidenceStore.processProjection;
  const scope = projectionScopeKey(actor, caseId);
  const generation = ++processProjectionGeneration;

  if (scope !== processProjectionScope) {
    processProjectionScope = scope;
    resource.data = null;
    resource.updatedAt = null;
  }
  resource.status = "loading";
  resource.error = null;

  try {
    const data = await evidenceApi.processProjection(actor, caseId);
    if (generation !== processProjectionGeneration || scope !== processProjectionScope) {
      return data;
    }
    resource.data = data;
    resource.status = data == null ? "empty" : "ready";
    resource.updatedAt = new Date().toISOString();
    return data;
  } catch (error) {
    if (generation === processProjectionGeneration && scope === processProjectionScope) {
      resource.error = error;
      resource.status = "degraded";
      resource.data = null;
    }
    return null;
  }
}

// 业务位置：【前端状态仓库】loadEvidenceWorkspace：读取 当前可见证据和附件，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export async function loadEvidenceWorkspace(actor, caseId) {
  await Promise.all([
    loadResource(evidenceStore.dossier, () => evidenceApi.dossier(actor, caseId)),
    loadResource(evidenceStore.catalog, () => evidenceApi.catalog(actor, caseId), []),
    loadProcessProjection(actor, caseId),
  ]);
}
