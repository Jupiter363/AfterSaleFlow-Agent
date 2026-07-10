import { flushPromises, mount } from "@vue/test-utils";
import {
  createMemoryHistory,
  createRouter,
} from "vue-router";
import { describe, expect, it, vi } from "vitest";
import App from "./App.vue";
import { actor } from "./state/actor";

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
}));

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

describe("App shell", () => {
  it("only exposes reviewer navigation entries to the platform reviewer", async () => {
    actor.id = "user-local";
    actor.role = "USER";
    const partyWrapper = await mountApp();

    const partyHrefs = partyWrapper
      .findAll(".app-nav a")
      .map((link) => link.attributes("href"));
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
  });

  it("keeps global context copy out of the fixed top navigation and separate rows", async () => {
    const wrapper = await mountApp();

    expect(wrapper.find(".page-context").exists()).toBe(false);
    expect(wrapper.find("[data-page-context-inline]").exists()).toBe(false);
    expect(wrapper.get(".app-header").exists()).toBe(true);
    expect(wrapper.text()).not.toContain("AI 建议非最终");
  });

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
});
