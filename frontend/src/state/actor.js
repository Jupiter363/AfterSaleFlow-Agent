import { reactive, watch } from "vue";

export const roleLabels = {
  USER: "用户",
  MERCHANT: "商家",
  CUSTOMER_SERVICE: "平台客服",
  PLATFORM_REVIEWER: "平台审核员",
  ADMIN: "管理员",
};

function storedActor() {
  try {
    return JSON.parse(localStorage.getItem("dispute-actor") || "null");
  } catch {
    localStorage.removeItem("dispute-actor");
    return null;
  }
}

export const actor = reactive(
  storedActor() || { id: "reviewer-local", role: "PLATFORM_REVIEWER" },
);

watch(
  actor,
  (value) => localStorage.setItem("dispute-actor", JSON.stringify(value)),
  { deep: true },
);
