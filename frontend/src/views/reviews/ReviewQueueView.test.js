import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
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
    status: "PENDING",
    priority: "MEDIUM",
    required_role: "PLATFORM_REVIEWER",
    due_at: "2026-07-03T16:00:00+08:00",
  },
];

describe("ReviewQueueView", () => {
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
});
