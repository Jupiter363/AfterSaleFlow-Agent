// 文件作用：前端状态管理文件，维护页面共享状态、缓存和业务动作。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { reactive } from "vue";
import { reviewApi } from "../api/review";
import { createResourceState, loadResource } from "./resource";

export const reviewStore = reactive({
  queue: createResourceState([]),
  packet: createResourceState(null),
  decisionPending: false,
});

// 业务位置：【前端状态仓库】loadReviews：读取 人工审核关注点和陪审团提示，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function loadReviews(actor, status = "PENDING") {
  return loadResource(reviewStore.queue, () => reviewApi.list(actor, status));
}

// 业务位置：【前端状态仓库】loadReviewPacket：读取 人工审核关注点和陪审团提示，并依据当前案件、角色和会话权限裁剪成可用输入。上游：API 响应、SSE 增量和用户操作。下游：跨组件一致的案件/房间/证据状态。边界：本地状态不能替代服务端事实。
export function loadReviewPacket(actor, reviewId) {
  return loadResource(reviewStore.packet, () => reviewApi.packet(actor, reviewId));
}
