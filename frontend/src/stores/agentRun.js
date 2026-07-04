import { reactive } from "vue";
import { hearingApi } from "../api/hearing";
import { createResourceState, loadResource } from "./resource";

export const agentRunStore = reactive({
  runs: createResourceState([]),
  expandedRunId: null,
});

export function loadAgentRuns(actor, caseId) {
  return loadResource(
    agentRunStore.runs,
    () => hearingApi.agentRuns(actor, caseId),
    [],
  );
}
