// 文件作用：自动化测试文件，验证 review.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  REVIEW_DECISIONS,
  normalizeReviewPacket,
  reviewApi,
  toReviewTaskSummary,
} from "./review";

// 业务位置：【前端 API/SSE 适配】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
describe("reviewApi", () => {
  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ success: true, data: [{ id: "REVIEW_1" }] }),
      }),
    );
    vi.stubGlobal("crypto", { randomUUID: () => "idem-review-1" });
  });

  it("exposes only approve, bounded modification, and manual escalation", () => {
    expect(REVIEW_DECISIONS).toEqual([
      "APPROVE",
      "MODIFY_AND_APPROVE",
      "ESCALATE_MANUAL",
    ]);
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("sends reviewer identity and a purpose-scoped idempotency key", async () => {
    const actor = { id: "reviewer-1", role: "PLATFORM_REVIEWER" };
    await reviewApi.decide(actor, "REVIEW_1", {
      decision: "APPROVE",
      reason: "verified",
    });

    expect(fetch).toHaveBeenCalledWith(
      "/api/reviews/REVIEW_1/decision",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-User-Id": "reviewer-1",
          "X-Role": "PLATFORM_REVIEWER",
          "Idempotency-Key": "review-idem-review-1",
          "Content-Type": "application/json",
        }),
      }),
    );
    const request = fetch.mock.calls[0][1];
    expect(JSON.parse(request.body)).toEqual({
      decision: "APPROVE",
      reason: "verified",
      approved_plan: null,
      confirmed: true,
    });
  });

  it.each(REVIEW_DECISIONS)(
    "accepts the closed reviewer decision vocabulary: %s",
    async (decision) => {
      await reviewApi.decide(
        { id: "reviewer-1", role: "PLATFORM_REVIEWER" },
        "REVIEW_1",
        {
          decision,
          reason: "reviewed",
          approved_plan:
            decision === "MODIFY_AND_APPROVE" ? { actions: [{ type: "REFUND" }] } : null,
          operation_id: "browser-must-not-forward",
          fencing_token: 99,
        },
      );

      const body = JSON.parse(fetch.mock.calls.at(-1)[1].body);
      expect(body.decision).toBe(decision);
      expect(body.confirmed).toBe(true);
      expect(body).not.toHaveProperty("operation_id");
      expect(body).not.toHaveProperty("fencing_token");
    },
  );

  it("normalizes structured packet fields while preserving legacy readers", () => {
    const normalized = normalizeReviewPacket({
      review_packet: {
        packetId: "PACKET_2",
        packetVersion: 4,
        contentHash: "a".repeat(64),
        actionHash: "ACTION_HASH",
        status: "FROZEN",
        reviewDeadline: "2026-08-01T10:00:00Z",
        draft: { conclusion: "legacy-readable" },
      },
      reviewTaskStatus: "IN_REVIEW",
      assignedReviewerId: "reviewer-1",
    });

    expect(normalized).toMatchObject({
      id: "PACKET_2",
      packet_version: 4,
      content_hash: "a".repeat(64),
      action_hash: "ACTION_HASH",
      status: "FROZEN",
      expires_at: "2026-08-01T10:00:00Z",
      review_deadline: "2026-08-01T10:00:00Z",
      review_task_status: "IN_REVIEW",
      assigned_reviewer_id: "reviewer-1",
      draft: { conclusion: "legacy-readable" },
    });
  });

  it("reduces queue entries to a non-sensitive task summary", () => {
    const summary = toReviewTaskSummary({
      task: {
        taskId: "REVIEW_2",
        caseId: "CASE_2",
        status: "IN_REVIEW",
        assignedReviewerId: "reviewer-1",
      },
      review_packet: { secret: "frozen body" },
      proposal: { action: "REFUND" },
    });

    expect(summary).toEqual(expect.objectContaining({
      id: "REVIEW_2",
      case_id: "CASE_2",
      status: "IN_REVIEW",
      assigned_reviewer_id: "reviewer-1",
    }));
    expect(summary).not.toHaveProperty("review_packet");
    expect(summary).not.toHaveProperty("proposal");
  });

  it("starts terminal review through the durable room-transition endpoint", async () => {
    const reviewer = { id: "reviewer-local", role: "PLATFORM_REVIEWER" };

    await reviewApi.start(reviewer, "REVIEW_1");

    expect(fetch).toHaveBeenCalledWith(
      "/api/reviews/REVIEW_1/start",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-User-Id": "reviewer-local",
          "X-Role": "PLATFORM_REVIEWER",
        }),
      }),
    );
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("shows a readable fallback when the backend omits an error message", async () => {
    fetch.mockResolvedValueOnce({
      ok: false,
      json: async () => ({ success: false }),
    });

    await expect(
      reviewApi.list({ id: "reviewer-1", role: "PLATFORM_REVIEWER" }),
    ).rejects.toThrow("请求失败，请稍后重试");
  });
});
