// 文件作用：自动化测试文件，验证 ExpandableText.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

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

// 业务位置：【前端业务组件】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面传入的案件、消息、证据或审核数据 正确进入 可复用的房间交互和展示事件。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
describe("ExpandableText", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // 业务位置：【前端业务组件】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面传入的案件、消息、证据或审核数据 正确进入 可复用的房间交互和展示事件。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
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

  // 业务位置：【前端业务组件】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面传入的案件、消息、证据或审核数据 正确进入 可复用的房间交互和展示事件。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
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
