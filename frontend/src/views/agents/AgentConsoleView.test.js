import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import AgentConsoleView from "./AgentConsoleView.vue";

describe("AgentConsoleView", () => {
  it("preserves the dispute park visual language while marking the module as later-phase", () => {
    const wrapper = mount(AgentConsoleView);

    expect(wrapper.text()).toContain("数字人管理中心");
    expect(wrapper.text()).toContain("给每个数字人安排自己的小屋、技能和出场规则");
    expect(wrapper.text()).toContain("AI 评审团");
    expect(wrapper.text()).toContain("默认最终介入");
    expect(wrapper.text()).toContain("80 分门禁");
    expect(wrapper.text()).toContain("后期接入真实配置后端");
    expect(wrapper.find("[data-agent-console]").exists()).toBe(true);
    expect(wrapper.findAll("[data-agent-role]")).toHaveLength(5);
    expect(wrapper.find("[data-jury-strategy='FINAL_ONLY']").exists()).toBe(true);
  });
});
