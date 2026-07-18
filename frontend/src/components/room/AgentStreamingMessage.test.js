import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import AgentStreamingMessage from "./AgentStreamingMessage.vue";

describe("AgentStreamingMessage", () => {
  it("exposes the active V2 attempt and reset count without rendering discarded text", async () => {
    const run = {
      runId: "AGENT_RUN_RESET",
      status: "STREAMING",
      senderRole: "INTAKE_OFFICER",
      agentLabel: "数字人",
      content: "新文本",
      activeCardKey: "default",
      currentAttemptId: "ATTEMPT_2",
      pendingAttemptId: "",
      resetCount: 1,
    };
    const wrapper = mount(AgentStreamingMessage, { props: { run } });

    expect(wrapper.attributes("data-agent-attempt-id")).toBe("ATTEMPT_2");
    expect(wrapper.attributes("data-agent-reset-count")).toBe("1");
    expect(wrapper.text()).toContain("新文本");
    expect(wrapper.text()).not.toContain("旧文本");

    await wrapper.setProps({
      run: { ...run, pendingAttemptId: "ATTEMPT_3" },
    });
    expect(wrapper.text()).toContain("正在切换重试");
  });
});
