package com.example.dispute.workflow.api.mig001;

import com.example.dispute.casecore.application.DemoImportActors;
import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.casecore.application.ImportedDisputeView;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.workflow.config.OrchestrationCutoverProperties;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("mig001-driver")
@ConditionalOnProperty(
        prefix = "app.orchestration",
        name = "mig001-driver-enabled",
        havingValue = "true")
public class Mig001ScenarioService {

    static final String SOURCE_SYSTEM = "MIG001_SYNTHETIC";
    private static final String EXTERNAL_PREFIX = "mig001-";

    private final DisputeImportService importService;
    private final Mig001ScenarioTupleReader tupleReader;

    public Mig001ScenarioService(
            OrchestrationCutoverProperties cutoverProperties,
            DisputeImportService importService,
            Mig001ScenarioTupleReader tupleReader) {
        if (cutoverProperties.newEpochMode() != WriterMode.SHADOW) {
            throw new IllegalStateException(
                    "MIG-001 scenario driver requires app.orchestration.new-epoch-mode=SHADOW");
        }
        this.importService = importService;
        this.tupleReader = tupleReader;
    }

    public Mig001ScenarioView create(
            String scenarioId, AuthenticatedActor actor, String traceId, String requestId) {
        requireSystem(actor);
        if (scenarioId == null || !scenarioId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("scenarioId must be a 128-bit lowercase hex token");
        }
        String externalReference = EXTERNAL_PREFIX + scenarioId;
        ImportedDisputeView imported =
                importService.importDispute(
                        syntheticCommand(externalReference),
                        actor,
                        "mig001-scenario:" + scenarioId,
                        traceId,
                        requestId);
        return tupleReader.status(imported.id(), actor);
    }

    public Mig001ScenarioView status(String caseId, AuthenticatedActor actor) {
        return tupleReader.status(caseId, actor);
    }

    private static ImportDisputeCommand syntheticCommand(String externalReference) {
        return new ImportDisputeCommand(
                SOURCE_SYSTEM,
                externalReference,
                "ORDER-" + externalReference,
                "AFTERSALE-" + externalReference,
                "LOGISTICS-" + externalReference,
                DemoImportActors.USER_ID,
                DemoImportActors.MERCHANT_ID,
                ActorRole.USER.name(),
                "MIG001_REGRESSION",
                "MIG-001 synthetic regression scenario",
                "Synthetic non-PII scenario for orchestration migration verification.",
                RiskLevel.LOW,
                CaseStatus.EVIDENCE_OPEN,
                RoomType.EVIDENCE.name(),
                null);
    }

    private static void requireSystem(AuthenticatedActor actor) {
        if (actor == null || actor.role() != ActorRole.SYSTEM) {
            throw new SecurityException("MIG-001 scenario driver requires SYSTEM identity");
        }
    }
}
