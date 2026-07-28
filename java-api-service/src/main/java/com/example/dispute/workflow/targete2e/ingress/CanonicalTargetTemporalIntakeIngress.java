package com.example.dispute.workflow.targete2e.ingress;

import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.time.Duration;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeMaterializer;
import org.springframework.transaction.annotation.Transactional;

/** Target-only adapter. It is deliberately not component-scanned; target assembly must opt in. */
public class CanonicalTargetTemporalIntakeIngress implements TargetTemporalIntakeIngress {

    private static final Duration COMMAND_DEADLINE = Duration.ofHours(1);

    private final CaseCommandService commandService;
    private final TargetIntakeMaterializer materializer;

    public CanonicalTargetTemporalIntakeIngress(
            CaseCommandService commandService,
            TargetIntakeMaterializer materializer) {
        this.commandService = commandService;
        this.materializer = materializer;
    }

    @Override
    @Transactional
    public TargetIntakeIngressReceipt accept(TargetIntakeMessageRequest request) {
        TargetIntakeMaterializer.MaterializedIntake material = materializer.materialize(request);
        CaseCommandAcceptance acceptance =
                commandService.accept(
                        request.caseId(),
                        material.commandId(),
                        new AcceptCaseCommand(
                                CommandType.INTAKE_MESSAGE,
                                RoomType.INTAKE,
                                request.activation().roomEpoch(),
                                new PayloadRef(
                                        material.eventPayload().schemaVersion(),
                                        material.eventPayload().uri(),
                                        material.eventPayload().sha256(),
                                        material.eventPayload().sizeBytes()),
                                request.activation().processRevision(),
                                request.createdAt().plus(COMMAND_DEADLINE)),
                        request.actor(),
                        request.traceId(),
                        request.idempotencyKey(),
                        null);
        return new TargetIntakeIngressReceipt(
                acceptance.command().commandId(),
                material.eventPayload().sha256(),
                acceptance.commandStatus(),
                acceptance.idempotentReplay(),
                material.admittedAt());
    }
}
