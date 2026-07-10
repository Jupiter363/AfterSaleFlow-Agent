import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ExpandableText from "./ExpandableText.vue";

let resizeCallback;

class ResizeObserverMock {
  constructor(callback) {
    resizeCallback = callback;
  }

  observe() {}

  disconnect() {}
}

describe("ExpandableText", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows a full-text trigger only when the preview really overflows", async () => {
    const wrapper = mount(ExpandableText, {
      props: { text: "完整案情摘要", label: "案情摘要", lines: 4 },
    });
    const content = wrapper.get("[data-expandable-content]").element;
    Object.defineProperty(content, "clientHeight", {
      value: 72,
      configurable: true,
    });
    Object.defineProperty(content, "scrollHeight", {
      value: 120,
      configurable: true,
    });
    resizeCallback();
    await nextTick();
    expect(wrapper.get("[data-expandable-trigger]").text()).toBe("查看全文");
  });

  it("emits the complete text and exposes dialog state to assistive technology", async () => {
    const wrapper = mount(ExpandableText, {
      props: {
        text: "完整原始陈述",
        label: "原始陈述",
        lines: 4,
        expanded: true,
      },
    });
    const content = wrapper.get("[data-expandable-content]").element;
    Object.defineProperty(content, "clientHeight", {
      value: 72,
      configurable: true,
    });
    Object.defineProperty(content, "scrollHeight", {
      value: 120,
      configurable: true,
    });
    resizeCallback();
    await nextTick();
    await wrapper.get("[data-expandable-trigger]").trigger("click");
    expect(wrapper.emitted("open")[0]).toEqual([
      { label: "原始陈述", text: "完整原始陈述" },
    ]);
    expect(
      wrapper.get("[data-expandable-trigger]").attributes("aria-expanded"),
    ).toBe("true");
  });
});
