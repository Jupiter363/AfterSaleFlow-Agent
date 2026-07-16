// 文件作用：自动化测试文件，验证 evidence.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { beforeEach, describe, expect, it, vi } from "vitest";
import { evidenceApi } from "./evidence";

const actor = { id: "user-local", role: "USER" };

// 业务位置：【前端 API/SSE 适配】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
describe("evidenceApi", () => {
  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ success: true, data: {} }),
      }),
    );
    vi.stubGlobal("crypto", { randomUUID: () => "uuid-1" });
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("submits a pending evidence batch with an idempotency key", async () => {
    await evidenceApi.submitBatch(
      actor,
      "CASE_1",
      { evidence_ids: ["EVIDENCE_1"], batch_note: "" },
      "batch-key-1",
    );

    expect(fetch).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/evidence/submissions",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-User-Id": "user-local",
          "X-Role": "USER",
          "Idempotency-Key": "batch-key-1",
          "Content-Type": "application/json",
        }),
        body: JSON.stringify({
          evidence_ids: ["EVIDENCE_1"],
          batch_note: "",
        }),
      }),
    );
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("deletes only a pending evidence item through the evidence endpoint", async () => {
    await evidenceApi.deletePending(actor, "CASE_1", "EVIDENCE_1");

    expect(fetch).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/evidence/EVIDENCE_1",
      expect.objectContaining({
        method: "DELETE",
        headers: expect.objectContaining({
          "X-User-Id": "user-local",
          "X-Role": "USER",
        }),
      }),
    );
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("sends explicit per-evidence multimodal processing authorization", async () => {
    const file = new File(["image"], "proof.png", { type: "image/png" });

    await evidenceApi.upload(actor, "CASE_1", {
      file,
      evidenceType: "IMAGE",
      sourceType: "USER_UPLOAD",
      visibility: "PRIVATE",
      modelProcessingAuthorized: true,
      claimedFact: "证明商品开箱时已经存在明显划痕。",
      truthAttested: true,
    });

    const request = fetch.mock.calls[0][1];
    expect(request.body).toBeInstanceOf(FormData);
    expect(request.body.get("model_processing_authorized")).toBe("true");
    expect(request.body.get("claimed_fact")).toBe("证明商品开箱时已经存在明显划痕。");
    expect(request.body.get("truth_attested")).toBe("true");
    expect(request.signal).toBeInstanceOf(AbortSignal);
  });
});
