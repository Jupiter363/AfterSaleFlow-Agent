// 文件作用：自动化测试文件，验证 router.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const routerSource = readFileSync(
  resolve(process.cwd(), "src/router/index.js"),
  "utf8",
);

// 业务位置：【案件路由】declaredPaths：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 案件状态、准入结论和风险信息 正确进入 下一处理房间或工作流路径。上游：案件状态、准入结论和风险信息。下游：下一处理房间或工作流路径。边界：路由只决定流程，不作责任认定。
function declaredPaths() {
  return [...routerSource.matchAll(/path:\s*"([^"]+)"/g)].map((match) => match[1]);
}

// 业务位置：【案件路由】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 案件状态、准入结论和风险信息 正确进入 下一处理房间或工作流路径。上游：案件状态、准入结论和风险信息。下游：下一处理房间或工作流路径。边界：路由只决定流程，不作责任认定。
describe("final room-based routes", () => {
  // 业务位置：【案件路由】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 案件状态、准入结论和风险信息 正确进入 下一处理房间或工作流路径。上游：案件状态、准入结论和风险信息。下游：下一处理房间或工作流路径。边界：路由只决定流程，不作责任认定。
  it("exposes the overview, three rooms, outcome and reviewer workspace", () => {
    expect(declaredPaths()).toEqual(
      expect.arrayContaining([
        "/disputes",
        "/disputes/:caseId/intake",
        "/disputes/:caseId/evidence",
        "/disputes/:caseId/hearing",
        "/disputes/:caseId/outcome",
        "/reviews",
        "/reviews/:reviewId",
        "/agents",
      ]),
    );
  });

  // 业务位置：【案件路由】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 案件状态、准入结论和风险信息 正确进入 下一处理房间或工作流路径。上游：案件状态、准入结论和风险信息。下游：下一处理房间或工作流路径。边界：路由只决定流程，不作责任认定。
  it("does not expose legacy cases, generic workspace or deliberation pages", () => {
    expect(declaredPaths()).not.toEqual(
      expect.arrayContaining([
        "/cases",
        "/disputes/:caseId",
        "/disputes/:caseId/deliberation",
      ]),
    );
  });

  // 业务位置：【案件路由】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 案件状态、准入结论和风险信息 正确进入 下一处理房间或工作流路径。上游：案件状态、准入结论和风险信息。下游：下一处理房间或工作流路径。边界：路由只决定流程，不作责任认定。
  it("declares reviewer and agent operation routes as PLATFORM_REVIEWER only", () => {
    const reviewerRoleDeclarations =
      routerSource.match(/roles:\s*\["PLATFORM_REVIEWER"\]/g) || [];
    expect(reviewerRoleDeclarations).toHaveLength(3);
  });
});
