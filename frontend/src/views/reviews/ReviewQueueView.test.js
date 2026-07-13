// 文件作用：自动化测试文件，验证 ReviewQueueView.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { reviewApi } from "../../api/review";
import ReviewQueueView from "./ReviewQueueView.vue";

const tasks = [
  {
    id: "REVIEW_UI_DEMO_20260704170511",
    case_id: "CASE_adcb56b853f248cd8c0cbfed4a2daf8a",
    status: "PENDING",
    priority: "URGENT",
    required_role: "PLATFORM_REVIEWER",
    due_at: "2026-07-03T13:00:00+08:00",
  },
  {
    id: "REVIEW_2",
    case_id: "CASE_NORMAL_2",
    status: "IN_REVIEW",
    priority: "MEDIUM",
    required_role: "PLATFORM_REVIEWER",
    due_at: "2026-07-03T16:00:00+08:00",
  },
];

// 业务位置：【前端审核工作台】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
describe("ReviewQueueView", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // 业务位置：【前端审核工作台】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 冻结审核包、Agent 建议和履约动作 正确进入 审核员批准、修改、补证或人工交接。上游：冻结审核包、Agent 建议和履约动作。下游：审核员批准、修改、补证或人工交接。边界：决定必须显式由有权限审核员提交。
  it("turns frozen review tasks into a lightweight reviewer launchpad", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/reviews", component: { template: "<div />" } },
        { path: "/reviews/:reviewId", component: { template: "<div />" } },
      ],
    });
    await router.push("/reviews");
    await router.isReady();
    const wrapper = mount(ReviewQueueView, {
      props: { initialTasks: tasks },
      global: { plugins: [router] },
    });

    expect(wrapper.text()).toContain("平台终审队列");
    expect(wrapper.text()).toContain("急件");
    expect(wrapper.text()).toContain("中优先");
    expect(wrapper.text()).toContain("待审核");
    expect(wrapper.text()).toContain("审核中");
    expect(wrapper.text()).not.toContain("URGENT");
    expect(wrapper.text()).not.toContain("MEDIUM");
    expect(wrapper.text()).not.toContain("PENDING");
    expect(wrapper.text()).toContain("CASE_adcb…");
    expect(wrapper.text()).not.toContain("2026-07-03T13:00:00");
    expect(wrapper.get("[data-review-due]").text()).toContain("7月3日");
    expect(wrapper.get("[data-review-case-id]").attributes("title")).toBe(
      "CASE_adcb56b853f248cd8c0cbfed4a2daf8a",
    );
    expect(wrapper.findAll("[data-review-task]")).toHaveLength(2);
    await wrapper.get("[data-review-task]").trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe(
      "/reviews/REVIEW_UI_DEMO_20260704170511",
    );
  });

  it("loads pending and in-review tasks so a reviewer can re-enter active work", async () => {
    const list = vi.spyOn(reviewApi, "list").mockImplementation((_actor, status) =>
      Promise.resolve(status === "PENDING" ? [tasks[0]] : [tasks[1]]),
    );
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/reviews", component: { template: "<div />" } },
        { path: "/reviews/:reviewId", component: { template: "<div />" } },
      ],
    });
    await router.push("/reviews");
    await router.isReady();

    const wrapper = mount(ReviewQueueView, {
      global: { plugins: [router] },
    });
    await flushPromises();

    expect(list.mock.calls.map(([, status]) => status)).toEqual([
      "PENDING",
      "IN_REVIEW",
    ]);
    expect(wrapper.findAll("[data-review-task]")).toHaveLength(2);
    expect(wrapper.text()).toContain("待审核");
    expect(wrapper.text()).toContain("审核中");
  });
});
