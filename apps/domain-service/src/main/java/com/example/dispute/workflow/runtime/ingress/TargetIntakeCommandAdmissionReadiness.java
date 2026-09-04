package com.example.dispute.workflow.runtime.ingress;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads the formal command reservation for the active Target Intake process revision.
 *
 * <p>This is an admission hint ahead of command materialization. The Java command ledger remains
 * the authoritative concurrent reservation check.
 */
@Component
public final class TargetIntakeCommandAdmissionReadiness {

    public static final String REASON_PRODUCTION_RUNTIME_INTAKE_PROJECTION_PENDING =
            "PRODUCTION_RUNTIME_INTAKE_PROJECTION_PENDING";

    private static final Set<CommandStatus> RESERVING_STATUSES =
            Set.of(
                    CommandStatus.PENDING_ORCHESTRATION,
                    CommandStatus.ORCHESTRATION_ACCEPTED);

    private final CaseCommandRepository commands;

    public TargetIntakeCommandAdmissionReadiness(CaseCommandRepository commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    public CommandAdmissionState state(String caseId, long processRevision) {
        requireCaseAndRevision(caseId, processRevision);
        return commands.existsByCaseIdAndExpectedProcessRevisionAndCommandStatusIn(
                        caseId, processRevision, RESERVING_STATUSES)
                ? CommandAdmissionState.PENDING
                : CommandAdmissionState.READY;
    }

    /**
     * Rejects only a new command while the active revision is formally reserved. An existing exact
     * command id must reach the normal ledger replay/hash validation path.
     */
    public void assertDispatchAllowed(TargetIntakeActivationGrant activation, String commandId) {
        Objects.requireNonNull(activation, "activation");
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        if (state(activation.caseId(), activation.processRevision()) != CommandAdmissionState.PENDING) {
            return;
        }
        if (commands
                .findByTenantSurrogateAndCommandId(activation.tenantSurrogate(), commandId)
                .isPresent()) {
            return;
        }
        throw new BusinessException(
                ErrorCode.CASE_STATUS_INVALID,
                "target Intake process projection is pending formal application",
                Map.of(
                        "reason_code", REASON_PRODUCTION_RUNTIME_INTAKE_PROJECTION_PENDING,
                        "case_id", activation.caseId(),
                        "process_revision", activation.processRevision(),
                        "command_admission_state", CommandAdmissionState.PENDING.name()));
    }

    private static void requireCaseAndRevision(String caseId, long processRevision) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        if (processRevision < 0) {
            throw new IllegalArgumentException("processRevision must not be negative");
        }
    }

    public enum CommandAdmissionState {
        READY,
        PENDING
    }
}
