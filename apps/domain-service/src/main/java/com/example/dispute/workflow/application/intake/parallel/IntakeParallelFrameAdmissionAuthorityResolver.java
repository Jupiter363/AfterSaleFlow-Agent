package com.example.dispute.workflow.application.intake.parallel;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.EventAuthority;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;

/** Resolves the current V080 event authority used to admit one parallel Intake Frame set. */
@FunctionalInterface
public interface IntakeParallelFrameAdmissionAuthorityResolver {

    AdmissionAuthority resolve(ExecuteAgentRunRequest request);

    record AdmissionAuthority(
            long fencingToken,
            String actorScopeSha256,
            String agentSessionId,
            EventAuthority eventAuthority) {

        public AdmissionAuthority {
            if (fencingToken < 1) {
                throw new IllegalArgumentException("fencingToken must be positive");
            }
            if (actorScopeSha256 == null || !actorScopeSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("actorScopeSha256 must be lowercase SHA-256");
            }
            if (agentSessionId == null
                    || !agentSessionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("agentSessionId must be a bounded identifier");
            }
            eventAuthority = Objects.requireNonNull(eventAuthority, "eventAuthority");
        }
    }
}
