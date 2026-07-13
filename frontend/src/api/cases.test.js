// 文件作用：自动化测试文件，验证 cases.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { beforeEach, describe, expect, it, vi } from "vitest";
import { caseApi } from "./cases";

const actor = { id: "user-1", role: "USER" };

// 业务位置：【前端 API/SSE 适配】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
describe("caseApi", () => {
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
  it("builds encoded role-scoped list filters", async () => {
    await caseApi.list(actor, {
      status: "WAITING_HUMAN_REVIEW",
      case_type: "DISPUTE",
      page: 0,
      size: 20,
    });

    expect(fetch).toHaveBeenCalledWith(
      "/api/disputes?status=WAITING_HUMAN_REVIEW&page=0&size=20",
      expect.objectContaining({
        headers: expect.objectContaining({
          "X-User-Id": "user-1",
          "X-Role": "USER",
        }),
      }),
    );
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("leaves multipart content type to the browser", async () => {
    const file = new File(["proof"], "proof.pdf", {
      type: "application/pdf",
    });
    await caseApi.uploadEvidence(actor, "CASE_1", file, {
      evidenceType: "LOGISTICS_PROOF",
      sourceType: "USER_UPLOAD",
      visibility: "PARTIES",
    });

    const [, options] = fetch.mock.calls[0];
    expect(options.body).toBeInstanceOf(FormData);
    expect(options.headers["Content-Type"]).toBeUndefined();
    expect(options.body.get("file")).toBe(file);
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("submits party evidence with an idempotency key", async () => {
    await caseApi.submitEvidence(actor, "CASE_1", "user", {
      submission_text: "签收照片不是本人",
      evidence_ids: ["EVIDENCE_1"],
    });

    expect(fetch).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/rooms/EVIDENCE/messages",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Idempotency-Key": "message-user-uuid-1",
        }),
      }),
    );
  });
});
