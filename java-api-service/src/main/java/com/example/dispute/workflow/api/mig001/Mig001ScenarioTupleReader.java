package com.example.dispute.workflow.api.mig001;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.RoomEpochBootstrapOutboxEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.RoomEpochBootstrapOutboxRepository;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mig001-driver")
@ConditionalOnProperty(
        prefix = "app.orchestration",
        name = "mig001-driver-enabled",
        havingValue = "true")
public class Mig001ScenarioTupleReader {

    private static final String SOURCE_SYSTEM = "MIG001_SYNTHETIC";
    private static final String EXTERNAL_PREFIX = "mig001-";

    private final FulfillmentCaseRepository caseRepository;
    private final CaseProcessProjectionRepository projectionRepository;
    private final CaseRoomEpochRepository epochRepository;
    private final RoomEpochBootstrapOutboxRepository bootstrapRepository;

    public Mig001ScenarioTupleReader(
            FulfillmentCaseRepository caseRepository,
            CaseProcessProjectionRepository projectionRepository,
            CaseRoomEpochRepository epochRepository,
            RoomEpochBootstrapOutboxRepository bootstrapRepository) {
        this.caseRepository = caseRepository;
        this.projectionRepository = projectionRepository;
        this.epochRepository = epochRepository;
        this.bootstrapRepository = bootstrapRepository;
    }

    @Transactional(readOnly = true)
    public Mig001ScenarioView status(String caseId, AuthenticatedActor actor) {
        requireSystem(actor);
        FulfillmentCaseEntity dispute =
                caseRepository.findById(caseId)
                        .filter(candidate -> SOURCE_SYSTEM.equals(candidate.getSourceSystem()))
                        .filter(candidate -> candidate.getExternalCaseRef() != null
                                && candidate.getExternalCaseRef().startsWith(EXTERNAL_PREFIX))
                        .orElseThrow(() -> notFound("MIG-001 synthetic case not found"));
        String scenarioId = dispute.getExternalCaseRef().substring(EXTERNAL_PREFIX.length());
        boolean bindingMatches = caseRepository
                .findByCreationIdempotencyKey("mig001-scenario:" + scenarioId)
                .map(candidate -> caseId.equals(candidate.getId()))
                .orElse(false);
        if (!bindingMatches) {
            throw notFound("MIG-001 synthetic idempotency binding not found");
        }

        CaseProcessProjectionEntity projection = projectionRepository.findById(caseId)
                .orElseThrow(() -> notFound("MIG-001 process projection not found"));
        CaseRoomEpochEntity epoch = epochRepository
                .findByCaseIdAndRoomTypeAndRoomEpoch(caseId, RoomType.EVIDENCE, projection.getRoomEpoch())
                .orElseThrow(() -> notFound("MIG-001 room epoch not found"));
        RoomEpochBootstrapOutboxEntity bootstrap = bootstrapRepository.findByEpochId(epoch.getId())
                .orElseThrow(() -> notFound("MIG-001 bootstrap outbox not found"));

        if (!tupleMatches(caseId, projection, epoch, bootstrap)) {
            throw notFound("MIG-001 SHADOW tuple is inconsistent");
        }
        return view(scenarioId, dispute, epoch, projection, bootstrap);
    }

    private static boolean tupleMatches(
            String caseId,
            CaseProcessProjectionEntity projection,
            CaseRoomEpochEntity epoch,
            RoomEpochBootstrapOutboxEntity bootstrap) {
        return caseId.equals(projection.getCaseId())
                && caseId.equals(epoch.getCaseId())
                && caseId.equals(bootstrap.getCaseId())
                && epoch.getWriterMode() == WriterMode.SHADOW
                && projection.getWriterMode() == WriterMode.SHADOW
                && bootstrap.getWriterMode() == WriterMode.SHADOW
                && epoch.getRoomType() == RoomType.EVIDENCE
                && bootstrap.getRoomType() == RoomType.EVIDENCE
                && epoch.getRoomEpoch() == projection.getRoomEpoch()
                && epoch.getRoomEpoch() == bootstrap.getRoomEpoch()
                && epoch.getProcessRevision() == projection.getProcessRevision()
                && epoch.getFencingToken() == projection.getFencingToken()
                && epoch.getFencingToken() == bootstrap.getFencingToken()
                && Objects.equals(epoch.getTenantSurrogate(), projection.getTenantSurrogate())
                && Objects.equals(epoch.getTenantSurrogate(), bootstrap.getTenantSurrogate())
                && Objects.equals(epoch.getId(), bootstrap.getEpochId())
                && Objects.equals(epoch.getTemporalWorkflowId(), bootstrap.getCaseWorkflowId())
                && Objects.equals(epoch.getRoomTemporalWorkflowId(), bootstrap.getRoomWorkflowId())
                && Objects.equals(projection.getTemporalWorkflowId(), bootstrap.getCaseWorkflowId());
    }

    private static Mig001ScenarioView view(
            String scenarioId,
            FulfillmentCaseEntity dispute,
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            RoomEpochBootstrapOutboxEntity bootstrap) {
        return new Mig001ScenarioView(
                scenarioId, epoch.getCaseId(), SOURCE_SYSTEM, dispute.getExternalCaseRef(), epoch.getId(),
                epoch.getTenantSurrogate(), epoch.getRoomId(), epoch.getRoomType().name(), epoch.getRoomEpoch(),
                epoch.getProcessRevision(), epoch.getRoomRevision(), epoch.getFencingToken(),
                epoch.getWriterMode().name(), epoch.getLifecycleStatus().name(), epoch.getProvisioningStatus().name(),
                projection.getWriterMode().name(), projection.getWriterActivationStatus().name(),
                bootstrap.getCaseWorkflowId(), bootstrap.getRoomWorkflowId(), bootstrap.getUpdateId(),
                bootstrap.getOutboxStatus().name(), bootstrap.getCaseTemporalRunId(), bootstrap.getRoomTemporalRunId());
    }

    private static void requireSystem(AuthenticatedActor actor) {
        if (actor == null || actor.role() != ActorRole.SYSTEM) {
            throw new SecurityException("MIG-001 scenario driver requires SYSTEM identity");
        }
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }
}
