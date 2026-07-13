// 文件作用：自动化测试文件，验证 actor.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { beforeEach, describe, expect, it, vi } from "vitest";

// 业务位置：【前端应用】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
describe("actor state", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.resetModules();
  });

  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("defaults the local demo experience to the user-side journey", async () => {
    const { actor, demoActors } = await import("./actor");

    expect(actor.id).toBe("user-local");
    expect(actor.role).toBe("USER");
    expect(demoActors).toEqual([
      { id: "user-local", role: "USER", label: "用户" },
      { id: "merchant-local", role: "MERCHANT", label: "商家" },
      { id: "reviewer-local", role: "PLATFORM_REVIEWER", label: "平台审核员" },
    ]);
  });

  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("keeps a stored reviewer demo account instead of collapsing it into the user actor", async () => {
    localStorage.setItem(
      "dispute-actor",
      JSON.stringify({ id: "reviewer-local", role: "PLATFORM_REVIEWER" }),
    );

    const { actor } = await import("./actor");

    expect(actor.id).toBe("reviewer-local");
    expect(actor.role).toBe("PLATFORM_REVIEWER");
  });

  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("switches demo actors by role using the fixed actor id", async () => {
    const { actor, switchDemoActor } = await import("./actor");

    switchDemoActor("MERCHANT");

    expect(actor.id).toBe("merchant-local");
    expect(actor.role).toBe("MERCHANT");
  });
});
