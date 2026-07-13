// 文件作用：自动化测试文件，验证 access.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { describe, expect, it } from "vitest";
import { routeAccessDecision } from "./access";

// 业务位置：【案件路由】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 案件状态、准入结论和风险信息 正确进入 下一处理房间或工作流路径。上游：案件状态、准入结论和风险信息。下游：下一处理房间或工作流路径。边界：路由只决定流程，不作责任认定。
describe("route access", () => {
  const reviewRoute = { meta: { roles: ["PLATFORM_REVIEWER"] } };

  // 业务位置：【案件路由】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 案件状态、准入结论和风险信息 正确进入 下一处理房间或工作流路径。上游：案件状态、准入结论和风险信息。下游：下一处理房间或工作流路径。边界：路由只决定流程，不作责任认定。
  it("allows a listed role", () => {
    expect(
      routeAccessDecision(reviewRoute, { role: "PLATFORM_REVIEWER" }),
    ).toBe(true);
  });

  // 业务位置：【案件路由】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 案件状态、准入结论和风险信息 正确进入 下一处理房间或工作流路径。上游：案件状态、准入结论和风险信息。下游：下一处理房间或工作流路径。边界：路由只决定流程，不作责任认定。
  it("redirects parties away from reviewer-only routes", () => {
    expect(routeAccessDecision(reviewRoute, { role: "USER" })).toEqual({
      path: "/disputes",
      query: { access: "reviewer-only" },
    });
  });
});
