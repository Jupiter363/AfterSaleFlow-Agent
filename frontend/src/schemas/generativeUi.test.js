import { describe, expect, it } from "vitest";
import { parseGenerativeUi } from "./generativeUi";

describe("safe Generative UI schema", () => {
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
