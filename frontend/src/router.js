import { createRouter, createWebHistory } from "vue-router";
import CaseDetailView from "./views/CaseDetailView.vue";
import CaseListView from "./views/CaseListView.vue";
import ReviewWorkbenchView from "./views/ReviewWorkbenchView.vue";
import SubmissionView from "./views/SubmissionView.vue";

export const routes = [
  { path: "/", redirect: "/cases" },
  {
    path: "/cases",
    component: CaseListView,
    meta: { title: "案件中心" },
  },
  {
    path: "/cases/:caseId",
    component: CaseDetailView,
    meta: { title: "案件详情" },
  },
  {
    path: "/cases/:caseId/submissions/user",
    component: SubmissionView,
    props: { party: "user" },
    meta: { title: "用户补证" },
  },
  {
    path: "/cases/:caseId/submissions/merchant",
    component: SubmissionView,
    props: { party: "merchant" },
    meta: { title: "商家补证" },
  },
  {
    path: "/review",
    component: ReviewWorkbenchView,
    meta: { title: "平台审核台" },
  },
  { path: "/:pathMatch(.*)*", redirect: "/cases" },
];

export default createRouter({
  history: createWebHistory(),
  routes,
});
