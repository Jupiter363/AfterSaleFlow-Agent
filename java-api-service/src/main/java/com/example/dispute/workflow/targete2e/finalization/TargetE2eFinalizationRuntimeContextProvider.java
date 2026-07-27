package com.example.dispute.workflow.targete2e.finalization;

/** Supplies the actual Temporal execution identity of the Finalize Activity. */
@FunctionalInterface
public interface TargetE2eFinalizationRuntimeContextProvider {

    RuntimeContext current();

    record RuntimeContext(String workflowId, String workflowRunId, String workflowBuildId) {
        public RuntimeContext {
            required(workflowId, "workflowId");
            required(workflowRunId, "workflowRunId");
            required(workflowBuildId, "workflowBuildId");
        }

        private static void required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
        }
    }
}
