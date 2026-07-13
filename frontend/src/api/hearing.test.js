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
  it("submits a party hearing round without using the trusted complete endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          round_id: "ROUND_1",
          round_no: 1,
          status: "WAITING",
          submitted_roles: ["USER"],
        },
      }),
    });

    await hearingApi.submitRound(actor, "CASE_1", {
      dossier_version: 2,
      statement_json: '{"text":"本轮陈述"}',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/disputes/CASE_1/hearing/rounds/current/submissions",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          dossier_version: 2,
          statement_json: '{"text":"本轮陈述"}',
        }),
        headers: expect.objectContaining({
          "X-Role": "USER",
          "X-User-Id": "user-local",
        }),
      }),
    );
  });
});
