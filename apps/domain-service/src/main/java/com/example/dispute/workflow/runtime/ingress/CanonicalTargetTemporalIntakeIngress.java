package com.example.dispute.workflow.runtime.ingress;

import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.runtime.ingress.materialization.TargetIntakeMaterializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** Production-only adapter. It is deliberately not component-scanned; production assembly must opt in. */
public class CanonicalTargetTemporalIntakeIngress implements TargetTemporalIntakeIngress {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(CanonicalTargetTemporalIntakeIngress.class);

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
        long startedAt = System.nanoTime();
        TargetIntakeMaterializer.MaterializedIntake material = materializer.materialize(request);
        long materializedAt = System.nanoTime();
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
                                material.deadlineAt()),
                        request.actor(),
                        request.traceId(),
                        request.idempotencyKey(),
                        null);
        long acceptedAt = System.nanoTime();
        LOGGER.info(
                "target_intake_ingress_timing run_id={} materialize_ms={} command_accept_ms={} total_ms={}",
                material.runId(),
                elapsedMillis(startedAt, materializedAt),
                elapsedMillis(materializedAt, acceptedAt),
                elapsedMillis(startedAt, acceptedAt));
        return new TargetIntakeIngressReceipt(
                acceptance.command().commandId(),
                material.runId(),
                material.eventPayload().sha256(),
                acceptance.commandStatus(),
                acceptance.idempotentReplay(),
                material.admittedAt());
    }

    private static double elapsedMillis(long startedAt, long completedAt) {
        return (completedAt - startedAt) / 1_000_000.0d;
    }
}
