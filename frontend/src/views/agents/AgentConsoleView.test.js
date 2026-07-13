// 文件作用：自动化测试文件，验证 AgentConsoleView.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import AgentConsoleView from "./AgentConsoleView.vue";

// 业务位置：【前端案件页面】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
describe("AgentConsoleView", () => {
  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
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
