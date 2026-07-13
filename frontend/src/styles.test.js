// 文件作用：自动化测试文件，验证 styles.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

// 业务位置：【前端应用】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
describe("global responsive width", () => {
  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("does not impose a 320px minimum width on root scrolling elements", () => {
    const source = readFileSync("src/styles.css", "utf8");
    expect(source).not.toMatch(/html\s*\{[^}]*min-width:\s*320px/);
    expect(source).not.toMatch(/body\s*\{[^}]*min-width:\s*320px/);
  });
});
