// 文件作用：自动化测试文件，验证 generativeUi.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { describe, expect, it } from "vitest";
import { parseGenerativeUi } from "./generativeUi";

// 业务位置：【前端应用】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
describe("safe Generative UI schema", () => {
  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("accepts only the reviewed component and navigation action whitelist", () => {
    expect(
      parseGenerativeUi({
        version: 1,
        blocks: [
          {
            type: "finding",
            title: "签收事实",
            body: "物流轨迹存在冲突。",
            citations: [{ sourceId: "EVIDENCE_1", label: "物流轨迹" }],
          },
          {
            type: "action",
            title: "查看证据",
            action: { type: "navigate", target: "evidence" },
          },
        ],
      }).blocks,
    ).toHaveLength(2);
  });

  it.each([
    [{ version: 1, blocks: [{ type: "html", body: "<script>alert(1)</script>" }] }],
    [
      {
        version: 1,
        blocks: [
          {
            type: "action",
            title: "批准",
            action: { type: "approve", target: "/api/reviews/1/decision" },
          },
        ],
      },
    ],
    [
      {
        version: 1,
        blocks: [
          {
            type: "action",
            title: "打开外站",
            action: { type: "navigate", target: "https://evil.example" },
          },
        ],
      },
    ],
    [{ version: 1, blocks: [{ type: "finding", title: "x", body: "<iframe />" }] }],
  ])("rejects unsafe model-generated UI %#", (payload) => {
    expect(() => parseGenerativeUi(payload)).toThrow();
  });
});
