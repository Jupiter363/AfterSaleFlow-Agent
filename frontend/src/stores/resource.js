import { reactive } from "vue";

export function createResourceState(initialValue) {
  return reactive({
    status: "idle",
    data: initialValue,
    error: null,
    updatedAt: null,
  });
}

export async function loadResource(resource, loader, fallback) {
  resource.status = "loading";
  resource.error = null;
  try {
    const data = await loader();
    resource.data = data;
    resource.status =
      data == null || (Array.isArray(data) && data.length === 0) ? "empty" : "ready";
    resource.updatedAt = new Date().toISOString();
    return data;
  } catch (error) {
    resource.error = error;
    resource.status = fallback === undefined ? "error" : "degraded";
    if (fallback !== undefined) resource.data = fallback;
    return null;
  }
}
