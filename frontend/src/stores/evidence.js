import { reactive } from "vue";
import { evidenceApi } from "../api/evidence";
import { createResourceState, loadResource } from "./resource";

export const evidenceStore = reactive({
  dossier: createResourceState(null),
  catalog: createResourceState([]),
  selectedEvidenceId: null,
});

export async function loadEvidenceWorkspace(actor, caseId) {
  await Promise.all([
    loadResource(evidenceStore.dossier, () => evidenceApi.dossier(actor, caseId)),
    loadResource(evidenceStore.catalog, () => evidenceApi.catalog(actor, caseId), []),
  ]);
}
