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

export function createAppRouter(history = createWebHistory()) {
  const router = createRouter({ history, routes });
  router.beforeEach((to) => routeAccessDecision(to, actor));
  return router;
}

export default createAppRouter();
