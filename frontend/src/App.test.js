// 文件作用：自动化测试文件，验证 App.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { flushPromises, mount } from "@vue/test-utils";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App.vue";
import { disputeApi } from "./api/disputes";
import { actor } from "./state/actor";
import { disputeStore, loadDisputes } from "./stores/dispute";

vi.mock("./api/disputes", () => ({
  disputeApi: {
    get: vi.fn(() => Promise.resolve({ id: "CASE_1" })),
  },
}));

vi.mock("./stores/dispute", () => ({
  disputeStore: {
    list: { data: [], status: "idle", error: null, updatedAt: null },
    current: { data: null, status: "idle", error: null, updatedAt: null },
  },
  loadDisputes: vi.fn(() => Promise.resolve([])),
}));

vi.mock("./stores/notification", () => ({
  notificationStore: {
    items: {
      data: [],
      error: null,
      status: "idle",
    },
  },
  loadNotifications: vi.fn(() => Promise.resolve()),
  markAllNotificationsRead: vi.fn(() => Promise.resolve()),
  markNotificationRead: vi.fn(() => Promise.resolve()),
  deleteNotification: vi.fn(() => Promise.resolve()),
}));

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
  disputeApi.get.mockResolvedValue({ id: "CASE_1" });
  disputeStore.current.data = null;
  disputeStore.current.status = "idle";
});

// 业务位置：【前端应用】mountApp：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
async function mountApp() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/disputes",
        component: { template: "<section data-testid=\"overview\" />" },
        meta: { title: "争议办理总览" },
      },
      {
        path: "/disputes/:caseId/intake",
        component: { template: "<section data-testid=\"intake\" />" },
      },
      { path: "/reviews", component: { template: "<section />" } },
      { path: "/agents", component: { template: "<section />" } },
    ],
  });
  await router.push("/disputes");
  await router.isReady();

  return mount(App, {
    global: {
      plugins: [router],
      stubs: {
        SummonsMailbox: {
          template: "<button type=\"button\">传票信箱</button>",
        },
      },
    },
  });
}

// 业务位置：【前端应用】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
describe("App shell", () => {
  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("only exposes reviewer navigation entries to the platform reviewer", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    const partyWrapper = await mountApp();

    const partyHrefs = partyWrapper
      .findAll(".app-nav a")
      .map((link) => link.attributes("href"));
    expect(partyWrapper.get(".app-nav").text()).toBe("总览");
    expect(partyHrefs).toContain("/disputes");
    expect(partyHrefs).not.toContain("/reviews");
    expect(partyHrefs).not.toContain("/agents");
    partyWrapper.unmount();

    actor.id = "reviewer-local";
    actor.role = "PLATFORM_REVIEWER";
    const reviewerWrapper = await mountApp();

    const reviewerHrefs = reviewerWrapper
      .findAll(".app-nav a")
      .map((link) => link.attributes("href"));
    expect(reviewerHrefs).toContain("/disputes");
    expect(reviewerHrefs).toContain("/reviews");
    expect(reviewerHrefs).toContain("/agents");
    expect(reviewerWrapper.get(".app-nav").text()).toContain("总览");
    expect(reviewerWrapper.get(".app-nav").text()).toContain("平台终审");
    expect(reviewerWrapper.get(".app-nav").text()).toContain("数字人管理中心");
    expect(
      reviewerWrapper.get(".app-tools").element.firstElementChild,
    ).toBe(reviewerWrapper.get(".app-nav").element);
    expect(
      reviewerWrapper.get(".app-nav").element.nextElementSibling,
    ).toBe(reviewerWrapper.get(".actor-switcher").element);
  });

  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("keeps global context copy out of the fixed top navigation and separate rows", async () => {
    const wrapper = await mountApp();

    expect(wrapper.find(".page-context").exists()).toBe(false);
    expect(wrapper.find("[data-page-context-inline]").exists()).toBe(false);
    expect(wrapper.get(".app-header").exists()).toBe(true);
    expect(wrapper.text()).not.toContain("AI 建议非最终");
  });

  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("uses fixed demo actor ids and returns to the dispute overview when switching identity", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    const wrapper = await mountApp();
    const router = wrapper.vm.$router;
    await router.push("/disputes/CASE_1/intake");
    await router.isReady();

    const idInput = wrapper.get("[aria-label='身份 ID']");
    expect(idInput.element.value).toBe("user-local");
    expect(idInput.attributes("readonly")).toBeDefined();

    await wrapper.get("[aria-label='体验角色']").setValue("MERCHANT");
    await flushPromises();

    expect(actor.id).toBe("merchant-local");
    expect(actor.role).toBe("MERCHANT");
    expect(idInput.element.value).toBe("merchant-local");
    expect(router.currentRoute.value.path).toBe("/disputes");
  });

  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("redirects every actor away from a case removed by the reviewer", async () => {
    vi.useFakeTimers();
    actor.id = "user-local";
    actor.role = "USER";
    const wrapper = await mountApp();
    const router = wrapper.vm.$router;
    await flushPromises();
    await router.push("/disputes/CASE_1/intake");
    await router.isReady();
    disputeStore.current.data = { id: "CASE_1" };
    disputeStore.current.status = "ready";
    const notFound = new Error("case was not found");
    notFound.code = "CASE_NOT_FOUND";
    disputeApi.get.mockRejectedValueOnce(notFound);

    await vi.advanceTimersByTimeAsync(3_000);
    await flushPromises();

    expect(loadDisputes).toHaveBeenCalled();
    expect(router.currentRoute.value.path).toBe("/disputes");
    expect(disputeStore.current.data).toBeNull();
    expect(disputeStore.current.status).toBe("empty");
    wrapper.unmount();
  });
});
