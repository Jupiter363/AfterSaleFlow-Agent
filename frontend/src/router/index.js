// 文件作用：前端路由文件，定义页面访问路径、权限和导航规则。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { createRouter, createWebHistory } from "vue-router";
import { actor } from "../state/actor";
import { routeAccessDecision } from "./access";

export const routes = [
  { path: "/", redirect: "/disputes" },
  {
    path: "/disputes",
    name: "dispute-overview",
    component: () => import("../views/disputes/DisputeOverviewView.vue"),
    meta: { title: "争议办理总览", section: "disputes" },
  },
  {
    path: "/disputes/:caseId/intake",
    name: "intake-room",
    component: () => import("../views/disputes/IntakeRoomView.vue"),
    meta: { title: "争议接待室", section: "rooms" },
  },
  {
    path: "/disputes/:caseId/evidence",
    name: "evidence-room",
    component: () => import("../views/disputes/EvidenceRoomView.vue"),
    meta: { title: "证据书记官室", section: "rooms" },
  },
  {
    path: "/disputes/:caseId/hearing",
    name: "hearing-court",
    component: () => import("../views/disputes/HearingCourtView.vue"),
    meta: { title: "小法庭", section: "rooms" },
  },
  {
    path: "/disputes/:caseId/outcome",
    name: "dispute-outcome",
    component: () => import("../views/disputes/OutcomeView.vue"),
    meta: { title: "裁决与执行结果", section: "outcome" },
  },
  {
    path: "/reviews",
    name: "review-queue",
    component: () => import("../views/reviews/ReviewQueueView.vue"),
    meta: {
      title: "平台终审队列",
      section: "reviews",
      roles: ["PLATFORM_REVIEWER"],
    },
  },
  {
    path: "/reviews/:reviewId",
    name: "review-workbench",
    component: () => import("../views/reviews/ReviewWorkbenchView.vue"),
    meta: {
      title: "平台终审",
      section: "reviews",
      roles: ["PLATFORM_REVIEWER"],
    },
  },
  {
    path: "/agents",
    name: "agent-console",
    component: () => import("../views/agents/AgentConsoleView.vue"),
    meta: {
      title: "数字人管理中心",
      section: "agents",
      roles: ["PLATFORM_REVIEWER"],
    },
  },
  { path: "/:pathMatch(.*)*", redirect: "/disputes" },
];

// 业务位置：【案件路由】createAppRouter：把 案件状态、准入结论和风险信息 组装为本块需要的 当前阶段业务数据，供 下一处理房间或工作流路径 使用。上游：案件状态、准入结论和风险信息。下游：下一处理房间或工作流路径。边界：路由只决定流程，不作责任认定。
export function createAppRouter(history = createWebHistory()) {
  const router = createRouter({ history, routes });
  router.beforeEach((to) => routeAccessDecision(to, actor));
  return router;
}

export default createAppRouter();
