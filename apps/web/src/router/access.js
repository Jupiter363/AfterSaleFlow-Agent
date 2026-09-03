// 文件作用：前端路由文件，定义页面访问路径、权限和导航规则。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

// 业务位置：【案件路由】routeAccessDecision：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 案件状态、准入结论和风险信息 正确进入 下一处理房间或工作流路径。上游：案件状态、准入结论和风险信息。下游：下一处理房间或工作流路径。边界：路由只决定流程，不作责任认定。
export function routeAccessDecision(route, currentActor) {
  const roles = route.meta?.roles;
  if (!roles?.length || roles.includes(currentActor.role)) return true;
  return {
    path: "/disputes",
    query: { access: "reviewer-only" },
  };
}
