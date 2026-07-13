// 文件作用：自动化测试文件，验证 viteProxyConfig.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

// @vitest-environment node

import { describe, expect, it } from "vitest";
import config from "../vite.config.js";

// 业务位置：【前端应用】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
describe("vite development proxy", () => {
  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("routes local API calls directly to the dev Java API by default", () => {
    expect(config.server.proxy["/api"].target).toBe("http://127.0.0.1:8080");
  });
});
