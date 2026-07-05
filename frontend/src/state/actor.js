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
