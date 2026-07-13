// 文件作用：前端工程代码文件，支撑售后争议系统的页面、交互、样式或构建配置。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { reactive, watch } from "vue";

export const roleLabels = {
  USER: "用户",
  MERCHANT: "商家",
  CUSTOMER_SERVICE: "平台客服",
  PLATFORM_REVIEWER: "平台审核员",
  ADMIN: "管理员",
};

export const demoActors = [
  { id: "user-local", role: "USER", label: "用户" },
  { id: "merchant-local", role: "MERCHANT", label: "商家" },
  { id: "reviewer-local", role: "PLATFORM_REVIEWER", label: "平台审核员" },
];

const demoActorByRole = Object.fromEntries(
  demoActors.map((demoActor) => [demoActor.role, demoActor]),
);

// 业务位置：【前端应用】storedActor：将 当前阶段业务数据 持久化或合并到案件快照，使 售后纠纷处理界面 读取到可追溯版本。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
function storedActor() {
  try {
    const parsed = JSON.parse(localStorage.getItem("dispute-actor") || "null");
    if (!parsed) return null;
    const demoActor = demoActorByRole[parsed.role];
    if (!demoActor) return null;
    if (demoActor.id !== parsed.id) return demoActor;
    return parsed;
  } catch {
    localStorage.removeItem("dispute-actor");
    return null;
  }
}

export const actor = reactive(
  storedActor() || demoActors[0],
);

// 业务位置：【前端应用】switchDemoActor：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
export function switchDemoActor(role) {
  const demoActor = demoActorByRole[role] || demoActors[0];
  actor.id = demoActor.id;
  actor.role = demoActor.role;
}

watch(
  actor,
  (value) => localStorage.setItem("dispute-actor", JSON.stringify(value)),
  { deep: true },
);
