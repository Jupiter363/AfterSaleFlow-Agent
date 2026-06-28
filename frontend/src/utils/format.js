export function dateTime(value) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

export function percent(value) {
  if (value === null || value === undefined) return "—";
  return `${Math.round(Number(value) * 100)}%`;
}

export function statusType(status) {
  if (["FAILED", "REJECTED", "HIGH"].includes(status)) return "danger";
  if (["PENDING", "WAITING", "MEDIUM"].some((part) => status?.includes(part))) {
    return "warning";
  }
  if (["SUCCEEDED", "COMPLETED", "CLOSED", "LOW"].includes(status)) {
    return "success";
  }
  return "info";
}
