// 文件作用：验证数字人管理中心的角色切换、配置编辑、治理锁定与调试交互。

import { mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import AgentConsoleView from "./AgentConsoleView.vue";

const messageMocks = vi.hoisted(() => ({
  error: vi.fn(),
  info: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
}));

vi.mock("element-plus", () => ({ ElMessage: messageMocks }));

afterEach(() => {
  document.body.innerHTML = "";
  vi.clearAllMocks();
});

describe("AgentConsoleView", () => {
  it("renders the governed five-agent operations workspace with accurate metric boundaries", async () => {
    const wrapper = mount(AgentConsoleView);

    expect(wrapper.get("[data-agent-console]").exists()).toBe(true);
    expect(wrapper.findAll("[data-agent-role]")).toHaveLength(5);
    expect(wrapper.text()).toContain("数字人管理中心");
    expect(wrapper.text()).toContain("脱敏示例数据");
    expect(wrapper.get("[data-info-panel]").text()).toContain("服务定位");
    expect(wrapper.get("[data-info-panel]").text()).toContain("治理状态");
    expect(wrapper.find(".agent-workbench > .agent-detail-header").exists()).toBe(false);
    expect(wrapper.findAll("[data-agent-tab]")).toHaveLength(6);

    await wrapper.get('[data-agent-tab="overview"]').trigger("click");
    expect(wrapper.text()).toContain("阶段任务完成");
    expect(wrapper.text()).toContain("运行完成率");
    expect(wrapper.text()).toContain("异常转人工");
    expect(wrapper.text()).toContain("不代表案件闭环或平台最终裁决");

    const mobileToggle = wrapper.get(".mobile-agent-toggle");
    expect(mobileToggle.attributes("aria-expanded")).toBe("false");
    await mobileToggle.trigger("click");
    expect(mobileToggle.attributes("aria-expanded")).toBe("true");
  });

  it("switches agents and prompt nodes without changing the route", async () => {
    const wrapper = mount(AgentConsoleView);

    await wrapper.get('[data-agent-id="evidence_clerk"]').trigger("click");
    expect(wrapper.get(".agent-detail-header h2").text()).toContain("证据书记官");
    expect(wrapper.get(".agent-detail-header h2").text()).toContain("小册");
    expect(wrapper.text()).toContain("evidence-turn-v6");

    await wrapper.get('[data-agent-tab="prompt"]').trigger("click");
    expect(wrapper.get("[data-prompt-panel]").exists()).toBe(true);
    expect(wrapper.get("[data-prompt-editor]").element.value).toContain(
      "你是平台小法庭的证据书记官",
    );

    await wrapper.get(".prompt-selector select").setValue("merchant");
    expect(wrapper.get("[data-prompt-editor]").element.value).toContain(
      "当前参与方为商家",
    );
  });

  it("tracks prompt edits and saves them as an explicit prototype draft", async () => {
    const wrapper = mount(AgentConsoleView);
    await wrapper.get('[data-agent-tab="prompt"]').trigger("click");

    const editor = wrapper.get("[data-prompt-editor]");
    await editor.setValue(`${editor.element.value}\n\n新增语气约束。`);
    expect(wrapper.get(".editing-state").text()).toContain("有未保存修改");

    expect(wrapper.get("[data-publish-config]").attributes("disabled")).toBeDefined();

    await wrapper.get("[data-save-draft]").trigger("click");
    expect(wrapper.get(".editing-state").text()).toContain("草稿");
    expect(messageMocks.success).toHaveBeenCalledWith(
      "原型草稿已更新，尚未写入配置服务",
    );
  });

  it("allows ordinary feature changes but keeps governance policies locked", async () => {
    const wrapper = mount(AgentConsoleView);
    await wrapper.get('[data-agent-tab="strategy"]').trigger("click");

    const citationSwitch = wrapper.get('[data-capability-id="source_citation"]');
    expect(citationSwitch.attributes("aria-checked")).toBe("true");
    await citationSwitch.trigger("click");
    expect(citationSwitch.attributes("aria-checked")).toBe("false");
    expect(wrapper.get(".editing-state").text()).toContain("有未保存修改");

    const thinkingMode = wrapper.get('[data-capability-id="deep_thinking"]');
    expect(thinkingMode.findAll('[role="radio"]')).toHaveLength(3);
    expect(thinkingMode.get('[data-thinking-mode="adaptive"]').attributes("aria-checked")).toBe("true");
    await thinkingMode.get('[data-thinking-mode="deep"]').trigger("click");
    expect(thinkingMode.get('[data-thinking-mode="deep"]').attributes("aria-checked")).toBe("true");

    const handoffSwitch = wrapper.get('[data-capability-id="handoff"]');
    expect(handoffSwitch.attributes("disabled")).toBeDefined();
    expect(handoffSwitch.attributes("aria-checked")).toBe("true");
    expect(wrapper.text()).toContain("始终进入平台人工终审");
    expect(wrapper.text()).toContain("Profile 权限包络");
  });

  it("changes the digital-human appearance from the information tab", async () => {
    const wrapper = mount(AgentConsoleView, {
      global: { stubs: { Teleport: true } },
    });
    const initialAvatar = wrapper.get(".agent-detail-header__portrait img").attributes("src");

    await wrapper.get("[data-change-avatar]").trigger("click");
    expect(wrapper.get(".avatar-dialog").text()).toContain("更换 争议接待官 形象");
    expect(wrapper.get("[data-confirm-avatar]").attributes("disabled")).toBeDefined();

    await wrapper.get('[data-avatar-option="judge"]').trigger("click");
    expect(wrapper.get("[data-confirm-avatar]").attributes("disabled")).toBeUndefined();
    await wrapper.get("[data-confirm-avatar]").trigger("click");

    expect(wrapper.find(".avatar-dialog").exists()).toBe(false);
    expect(wrapper.get(".agent-detail-header__portrait img").attributes("src")).not.toBe(initialAvatar);
    expect(wrapper.get("[data-publish-config]").attributes("disabled")).toBeDefined();
    expect(messageMocks.success).toHaveBeenCalledWith("数字人形象已更新到配置草稿");
  });

  it("keeps each management view on fixed cards and expands long content on demand", async () => {
    const wrapper = mount(AgentConsoleView, {
      global: { stubs: { Teleport: true } },
    });

    expect(wrapper.findAll("[data-fixed-card]")).toHaveLength(4);
    expect(wrapper.get("[data-info-panel]").text()).toContain("数字人信息");
    expect(wrapper.findAll(".agent-info-card")).toHaveLength(3);

    await wrapper.get('[data-agent-tab="overview"]').trigger("click");
    expect(wrapper.findAll("[data-fixed-card]")).toHaveLength(4);
    await wrapper.get("[data-open-run-details]").trigger("click");
    expect(wrapper.get("[data-detail-dialog]").text()).toContain("全部异常与转人工记录");
    expect(wrapper.get("[data-detail-dialog]").text()).toContain("RUN-7F21");
    await wrapper.get('[aria-label="关闭详情"]').trigger("click");

    await wrapper.get('[data-agent-tab="prompt"]').trigger("click");
    expect(wrapper.findAll("[data-fixed-card]")).toHaveLength(4);
    await wrapper.get("[data-expand-prompt]").trigger("click");
    const expandedEditor = wrapper.get("[data-expanded-prompt-editor]");
    await expandedEditor.setValue(`${expandedEditor.element.value}\n\n展开编辑补充。`);
    expect(wrapper.get(".editing-state").text()).toContain("有未保存修改");
    await wrapper.get('[aria-label="关闭详情"]').trigger("click");

    await wrapper.get('[data-agent-tab="strategy"]').trigger("click");
    expect(wrapper.findAll("[data-fixed-card]")).toHaveLength(6);
    await wrapper.get("[data-open-profile-details]").trigger("click");
    expect(wrapper.get("[data-detail-dialog]").text()).toContain("Profile 权限包络");
    expect(wrapper.get("[data-detail-dialog]").text()).toContain("明确禁止");
    await wrapper.get('[aria-label="关闭详情"]').trigger("click");

    await wrapper.get('[data-agent-tab="debug"]').trigger("click");
    expect(wrapper.findAll("[data-fixed-card]")).toHaveLength(2);
    await wrapper.get("[data-run-debug]").trigger("click");
    await wrapper.get("[data-open-debug-details]").trigger("click");
    expect(wrapper.get("[data-detail-dialog]").text()).toContain("完整调试结果");
    await wrapper.get('[aria-label="关闭详情"]').trigger("click");

    await wrapper.get('[data-agent-tab="versions"]').trigger("click");
    expect(wrapper.findAll("[data-fixed-card]")).toHaveLength(2);
    const versionButtons = wrapper.findAll("[data-open-version-details]");
    expect(versionButtons).toHaveLength(3);
    await versionButtons[0].trigger("click");
    expect(wrapper.get("[data-detail-dialog]").text()).toContain("变更摘要");
  });

  it("runs a sandbox preview and exposes response governance metadata", async () => {
    const wrapper = mount(AgentConsoleView);
    await wrapper.get('[data-agent-id="review_copilot"]').trigger("click");
    await wrapper.get('[data-agent-tab="debug"]').trigger("click");
    await wrapper.get("[data-debug-input]").setValue("请解释当前审核包里的证据缺口。");
    await wrapper.get("[data-run-debug]").trigger("click");

    expect(wrapper.get("[data-debug-response]").text()).toContain("小译");
    expect(wrapper.get("[data-debug-response]").text()).toContain("结论：");
    expect(wrapper.text()).toContain("结构校验");
    expect(wrapper.text()).toContain("不可信输入隔离通过");
  });

  it("requires a release note before simulating a governed publish", async () => {
    const wrapper = mount(AgentConsoleView, {
      global: { stubs: { Teleport: true } },
    });

    await wrapper.get("[data-publish-config]").trigger("click");
    expect(wrapper.get('[role="dialog"]').text()).toContain("当前是交互原型");
    expect(wrapper.get("[data-confirm-publish]").attributes("disabled")).toBeDefined();

    await wrapper.get(".publish-dialog textarea").setValue("完成语气与安全边界回归检查。");
    await wrapper.get("[data-confirm-publish]").trigger("click");
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    expect(messageMocks.success).toHaveBeenCalledWith(
      "已模拟发布 争议接待官 配置，未影响运行服务",
    );
  });
});
