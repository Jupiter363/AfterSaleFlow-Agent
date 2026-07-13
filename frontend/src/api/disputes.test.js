// 文件作用：自动化测试文件，验证 disputes.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { afterEach, describe, expect, it, vi } from "vitest";
import { disputeApi } from "./disputes";

const actor = { id: "user-local", role: "USER" };

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

// 业务位置：【前端 API/SSE 适配】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
describe("dispute API", () => {
  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("loads the aggregated final outcome from the case endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          case_id: "CASE_outcome",
          final_decision: { human_confirmed: true },
          actions: [],
        },
      }),
    });

    const outcome = await disputeApi.outcome(
      actor,
      "CASE_outcome",
    );

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_outcome/outcome",
      expect.objectContaining({
        headers: expect.objectContaining({
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
    expect(outcome.final_decision.human_confirmed).toBe(true);
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("confirms an outcome draft through the case-scoped reviewer endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          task_id: "REVIEW_1",
          decision: "APPROVE",
          execution_allowed: true,
        },
      }),
    });

    const result = await disputeApi.confirmOutcomeDraft(
      { id: "reviewer-local", role: "PLATFORM_REVIEWER" },
      "CASE_outcome",
      "审核员确认 AI 裁决草案。",
    );

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_outcome/outcome/review/confirm",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ reason: "审核员确认 AI 裁决草案。" }),
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "Idempotency-Key": expect.stringMatching(/^outcome-confirm-/),
          "X-Role": "PLATFORM_REVIEWER",
          "X-User-Id": "reviewer-local",
        }),
      }),
    );
    expect(result.execution_allowed).toBe(true);
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("modifies an outcome draft through the case-scoped reviewer endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          task_id: "REVIEW_2",
          decision: "MODIFY_AND_APPROVE",
          execution_allowed: true,
        },
      }),
    });
    const approvedPlan = {
      id: "PLAN_2",
      actions: [{ action_type: "REFUND", amount: 199 }],
    };

    const result = await disputeApi.modifyOutcomeDraft(
      { id: "reviewer-local", role: "PLATFORM_REVIEWER" },
      "CASE_outcome",
      "审核员调整退款金额。",
      approvedPlan,
    );

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_outcome/outcome/review/modify",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          reason: "审核员调整退款金额。",
          approved_plan: approvedPlan,
        }),
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "Idempotency-Key": expect.stringMatching(/^outcome-modify-/),
          "X-Role": "PLATFORM_REVIEWER",
          "X-User-Id": "reviewer-local",
        }),
      }),
    );
    expect(result.decision).toBe("MODIFY_AND_APPROVE");
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("cancels intake when the issue is resolved before admission", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          case_id: "CASE_cancel",
          case_status: "CANCELLED",
          current_room: null,
        },
      }),
    });

    const result = await disputeApi.cancelIntake(
      actor,
      "CASE_cancel",
      "Issue resolved by negotiation.",
    );

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_cancel/intake/cancel",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ reason: "Issue resolved by negotiation." }),
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
    expect(result.case_status).toBe("CANCELLED");
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("simulates external dispute imports through the public demo adapter endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          items: [
            {
              case_id: "CASE_simulated",
              source_type: "EXTERNAL_IMPORT",
              initiator_role: "USER",
            },
          ],
        },
      }),
    });

    const result = await disputeApi.simulateExternalImport(actor, {
      count: 1,
      scenario: "手表售后争议",
      risk_level_hint: "MEDIUM",
      initiator_role_hint: "USER",
      current_actor_id: "user-local",
      counterparty_actor_id: "merchant-local",
    });

    const [, requestOptions] = fetchMock.mock.calls[0];
    const requestBody = JSON.parse(requestOptions.body);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/import/simulate",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "Idempotency-Key": expect.stringMatching(/^external-import-/),
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
    expect(requestBody).toEqual(
      expect.objectContaining({
        count: 1,
        scenario: "手表售后争议",
        risk_level_hint: "MEDIUM",
        initiator_role_hint: "USER",
        current_actor_id: "user-local",
        counterparty_actor_id: "merchant-local",
        simulation_batch_id: expect.stringMatching(/^external-import-/),
      }),
    );
    expect(requestBody.simulation_batch_id).toBe(
      requestOptions.headers["Idempotency-Key"],
    );
    expect(result.items[0].case_id).toBe("CASE_simulated");
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("times out simulated external imports after 15 seconds without leaking timeoutMs to fetch", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          if (!options.signal) {
            reject(new Error("fetch did not receive an AbortSignal"));
            return;
          }
          options.signal.addEventListener(
            "abort",
            () => reject(new DOMException("The request was aborted", "AbortError")),
            { once: true },
          );
        }),
    );

    const requestError = disputeApi
      .simulateExternalImport(actor, { count: 1 })
      .catch((error) => error);
    const [, requestOptions] = fetchMock.mock.calls[0];

    expect(requestOptions).not.toHaveProperty("timeoutMs");
    expect(requestOptions.signal?.aborted).toBe(false);

    await vi.advanceTimersByTimeAsync(14_999);
    expect(requestOptions.signal?.aborted).toBe(false);

    await vi.advanceTimersByTimeAsync(1);

    expect(await requestError).toMatchObject({ code: "REQUEST_TIMEOUT" });
    expect(requestOptions.signal.aborted).toBe(true);
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("deletes an imported simulation through the reviewer-only case endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: { case_id: "CASE_template_01", deleted: true },
      }),
    });

    const reviewer = { id: "reviewer-local", role: "PLATFORM_REVIEWER" };
    const result = await disputeApi.deleteSimulatedCase(
      reviewer,
      "CASE_template_01",
    );

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_template_01",
      expect.objectContaining({
        method: "DELETE",
        headers: expect.objectContaining({
          "X-Role": "PLATFORM_REVIEWER",
          "X-User-Id": "reviewer-local",
        }),
      }),
    );
    expect(result).toEqual({ case_id: "CASE_template_01", deleted: true });
  });
});
