import { reactive } from "vue";
import { disputeApi } from "../api/disputes";
import { createResourceState, loadResource } from "./resource";

export const disputeStore = reactive({
  list: createResourceState([]),
  current: createResourceState(null),
  filters: { status: "", dispute_type: "", page: 0, size: 20 },
});

export async function loadDisputes(actor) {
  return loadResource(disputeStore.list, async () => {
    const page = await disputeApi.list(actor, disputeStore.filters);
    return page.items || page.content || [];
  });
}

export function loadDispute(actor, caseId) {
  return loadResource(disputeStore.current, () => disputeApi.get(actor, caseId));
}
