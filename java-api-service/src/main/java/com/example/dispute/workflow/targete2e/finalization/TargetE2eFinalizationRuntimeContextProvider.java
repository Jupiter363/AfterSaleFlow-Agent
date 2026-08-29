package com.example.dispute.workflow.targete2e.finalization;

/** Supplies the actual Temporal execution identity of the Finalize Activity. */
@FunctionalInterface
public interface TargetE2eFinalizationRuntimeContextProvider {

    RuntimeContext current();

    record RuntimeContext(
            String workflowId,
            String workflowRunId,
            String workflowBuildId,
            String activationId,
            String activationManifestHash,
            String isolatedDomainDbBindingHash) {
        public RuntimeContext {
            required(workflowId, "workflowId");
            required(workflowRunId, "workflowRunId");
            required(workflowBuildId, "workflowBuildId");
            if (activationId == null || !activationId.matches("p9act[.]v1[.][0-9a-f]{32}")) {
                throw new IllegalArgumentException("activationId is invalid");
            }
            sha256(activationManifestHash, "activationManifestHash");
            sha256(isolatedDomainDbBindingHash, "isolatedDomainDbBindingHash");
        }

        private static void required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
        }

        private static void sha256(String value, String field) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
            }
        }
    }
}
