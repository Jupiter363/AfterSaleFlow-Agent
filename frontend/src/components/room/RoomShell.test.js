import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import RoomShell from "./RoomShell.vue";

describe("RoomShell", () => {
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
});
