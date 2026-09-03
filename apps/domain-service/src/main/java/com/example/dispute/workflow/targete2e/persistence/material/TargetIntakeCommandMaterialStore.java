package com.example.dispute.workflow.targete2e.persistence.material;

import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import java.time.Instant;
import java.util.Optional;

/** Immutable target-lane execution material, bound to one admitted Intake command. */
public interface TargetIntakeCommandMaterialStore {

    AppendResult append(CommandAdmission admission, IntakeCommandExecutionContext context);

    Optional<MaterialSnapshot> read(CommandAdmission admission);

    Optional<MaterialSnapshot> readByRoute(CommandLookup lookup);

    enum AppendDisposition {
        STORED,
        ATTACHED_IDENTICAL
    }

    record AppendResult(
            AppendDisposition disposition,
            String admissionId,
            Instant admittedAt,
            String contextSha256) {}

    record MaterialSnapshot(
            String admissionId,
            CommandAdmission admission,
            IntakeCommandExecutionContext context,
            String contextSha256,
            Instant storedAt) {}

    record CommandLookup(
            String tenantSurrogate,
            String caseId,
            String commandId,
            long roomEpoch,
            long roomFencingToken) {
        public CommandLookup {
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            requireText(commandId, "commandId");
            if (roomEpoch < 0 || roomFencingToken < 1) {
                throw new IllegalArgumentException("room epoch/fencing token is invalid");
            }
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }
}
