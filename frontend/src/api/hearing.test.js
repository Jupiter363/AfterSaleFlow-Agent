// 文件作用：自动化测试文件，验证 hearing.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { afterEach, describe, expect, it, vi } from "vitest";
import { hearingApi } from "./hearing";

const actor = { id: "user-local", role: "USER" };

afterEach(() => {
  vi.restoreAllMocks();
});

// 业务位置：【前端 API/SSE 适配】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
describe("hearing API", () => {
  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("submits one natural-language statement for the authenticated party", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          issue_set_id: "ISSUE_SET_1",
          participant_role: "USER",
          submission_status: "SUBMITTED",
        },
      }),
    });

    await hearingApi.submitStatement(actor, "CASE_1", {
      schema_version: "hearing_party_statement.v1",
      issue_set_id: "ISSUE_SET_1",
      statement_text: "我方未实际收到商品，并已核对门口、前台和监控记录。",
      source_message_ids: [],
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/hearing/statements",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          schema_version: "hearing_party_statement.v1",
          issue_set_id: "ISSUE_SET_1",
          statement_text: "我方未实际收到商品，并已核对门口、前台和监控记录。",
          source_message_ids: [],
        }),
        headers: expect.objectContaining({
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
  });

  it("keeps the legacy answer endpoint available for compatible callers", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({ success: true, data: { submission_status: "SUBMITTED" } }),
    });

    await hearingApi.submitAnswers(actor, "CASE_1", {
      schema_version: "hearing_answer_bundle.v1",
      question_set_id: "QUESTION_SET_1",
      answers: [],
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/hearing/answers",
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("submits one evidence batch after parallel file uploads finish", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({ success: true, data: { submission_status: "SUBMITTED" } }),
    });

    await hearingApi.submitEvidenceBatch(actor, "CASE_1", {
      schema_version: "hearing_evidence_batch.v1",
      request_set_id: "REQUEST_SET_1",
      request_ids: ["REQUEST_1"],
      evidence_ids: ["EVIDENCE_1", "EVIDENCE_2"],
      batch_note: "订单及物流材料",
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/hearing/evidence-batches",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          schema_version: "hearing_evidence_batch.v1",
          request_set_id: "REQUEST_SET_1",
          request_ids: ["REQUEST_1"],
          evidence_ids: ["EVIDENCE_1", "EVIDENCE_2"],
          batch_note: "订单及物流材料",
        }),
      }),
    );
  });
});
