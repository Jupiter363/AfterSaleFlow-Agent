// 文件作用：自动化测试文件，验证 RoomShell.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import RoomShell from "./RoomShell.vue";

// 业务位置：【Java 房间协作】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
describe("RoomShell", () => {
  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("keeps room context, server status and human-final boundary visible", () => {
    const wrapper = mount(RoomShell, {
      props: {
        eyebrow: "EVIDENCE ROOM",
        title: "证据书记官室",
        caseId: "CASE_1",
        connectionState: "connected",
      },
      slots: {
        agent: "<div>证据书记官</div>",
        clock: "<div>01:22:18</div>",
        default: "<div>证据书架</div>",
      },
    });

    expect(wrapper.text()).toContain("CASE_1");
    expect(wrapper.text()).toContain("证据书记官室");
    expect(wrapper.text()).toContain("实时连接");
    expect(wrapper.text()).toContain("AI 只提供非最终建议");
    expect(wrapper.text()).toContain("证据书架");
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("shortens long case identifiers in the header while preserving the full title", () => {
    const wrapper = mount(RoomShell, {
      props: {
        title: "履约争端小法庭",
        caseId: "CASE_adcb56b853f248cd8c0cbfed4a2daf8a",
      },
    });

    expect(wrapper.get("[data-room-case-id]").text()).toBe("CASE_adcb…");
    expect(wrapper.get("[data-room-case-id]").attributes("title")).toBe(
      "CASE_adcb56b853f248cd8c0cbfed4a2daf8a",
    );
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("exposes the workspace as an inline-size container for room layouts", () => {
    const source = readFileSync("src/components/room/RoomShell.vue", "utf8");

    expect(source).toContain("container: room-workspace / inline-size;");
  });
});
