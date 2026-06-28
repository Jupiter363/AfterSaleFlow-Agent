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
