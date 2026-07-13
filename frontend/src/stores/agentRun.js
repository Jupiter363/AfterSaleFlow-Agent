// 文件作用：前端状态管理文件，维护页面共享状态、缓存和业务动作。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { reactive } from "vue";
import { hearingApi } from "../api/hearing";
import { createResourceState, loadResource } from "./resource";

export const agentRunStore = reactive({
  runs: createResourceState([]),
  expandedRunId: null,
});

// 业务位置：【前端状态仓库】loadAgentRuns：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function loadAgentRuns(actor, caseId) {
  return loadResource(
    agentRunStore.runs,
    () => hearingApi.agentRuns(actor, caseId),
    [],
  );
}
