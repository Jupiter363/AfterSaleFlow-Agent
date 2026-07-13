// 文件作用：自动化测试文件，验证 ReviewWorkbenchView.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import { reviewApi } from "../api/review";
import { actor } from "../state/actor";
import ReviewWorkbenchView from "./ReviewWorkbenchView.vue";

vi.mock("../api/review", () => ({
  reviewApi: {
    list: vi.fn(),
    packet: vi.fn(),
    decide: vi.fn(),
  },
}));

// 业务位置：【前端案件页面】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
describe("ReviewWorkbenchView", () => {
  beforeEach(() => {
    actor.id = "reviewer-1";
    actor.role = "PLATFORM_REVIEWER";
    reviewApi.list.mockReset().mockResolvedValue([
      {
        id: "REVIEW_1",
        case_id: "CASE_1",
        priority: "HIGH",
        status: "PENDING",
        required_role: "PLATFORM_REVIEWER",
      },
    ]);
    reviewApi.packet.mockReset().mockResolvedValue({
      case_id: "CASE_1",
      packet_version: 1,
      case_summary: { title: "签收争议", risk_level: "HIGH" },
      claims: [],
      issues: [],
      evidence_matrix: [],
      draft: {},
      remedy: { actions: [] },
      risk_flags: ["HIGH_RISK"],
    });
    reviewApi.decide.mockReset().mockResolvedValue({});
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm");
  });

  // 业务位置：【前端案件页面】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
  it("requires explicit confirmation before submitting a reviewer decision", async () => {
    const wrapper = mount(ReviewWorkbenchView, {
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();
    await wrapper.get(".task-card").trigger("click");
    await flushPromises();
    await wrapper
      .get('textarea[placeholder^="审核理由"]')
      .setValue("证据链与规则适用均已核验");
    const approve = wrapper
      .findAll("button")
      .find((button) => button.text().includes("确认批准执行"));
    await approve.trigger("click");
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalledOnce();
    expect(reviewApi.decide).toHaveBeenCalledWith(
      actor,
      "REVIEW_1",
      expect.objectContaining({
        decision: "APPROVE",
        reason: "证据链与规则适用均已核验",
      }),
    );
  });
});
