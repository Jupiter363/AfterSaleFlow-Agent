// 文件作用：自动化测试文件，验证 PhaseCountdown.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import PhaseCountdown from "./PhaseCountdown.vue";

// 业务位置：【Java 房间协作】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
describe("PhaseCountdown", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-03T10:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("derives display time from the latest server clock", async () => {
    const wrapper = mount(PhaseCountdown, {
      props: {
        deadlineAt: "2026-07-03T12:00:00Z",
        serverNow: "2026-07-03T10:00:00Z",
        label: "举证剩余时间",
      },
    });

    await vi.advanceTimersByTimeAsync(1000);
    expect(wrapper.text()).toContain("01:59:59");

    await wrapper.setProps({ serverNow: "2026-07-03T10:00:10Z" });
    expect(wrapper.text()).toContain("01:59:50");
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("shows zero without initiating a business transition", async () => {
    const wrapper = mount(PhaseCountdown, {
      props: {
        deadlineAt: "2026-07-03T10:00:00Z",
        serverNow: "2026-07-03T10:00:10Z",
      },
    });

    await vi.advanceTimersByTimeAsync(1000);

    expect(wrapper.text()).toContain("00:00:00");
    expect(wrapper.emitted("expired")).toBeUndefined();
    expect(wrapper.attributes("data-awaiting-server")).toBe("true");
  });

  it("does not invent a local deadline while the server deadline is unavailable", () => {
    const wrapper = mount(PhaseCountdown, {
      props: {
        deadlineAt: "",
        serverNow: "2026-07-03T10:00:00Z",
        label: "举证窗口",
      },
    });

    expect(wrapper.text()).toContain("00:00:00");
    expect(wrapper.text()).toContain("等待服务端确认下一阶段");
    expect(wrapper.attributes("data-awaiting-server")).toBe("true");
  });
});
