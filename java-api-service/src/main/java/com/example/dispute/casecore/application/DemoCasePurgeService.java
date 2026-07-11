package com.example.dispute.casecore.application;

import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoCasePurgeService {

    private static final Set<String> PURGEABLE_SOURCE_SYSTEMS =
            Set.of(
                    SimulatedExternalDisputeTemplateCatalog.SOURCE_SYSTEM,
                    "LLM_SIMULATED_OMS");

    private final FulfillmentCaseRepository caseRepository;
    private final DemoCasePurgeStore purgeStore;

    public DemoCasePurgeService(
            FulfillmentCaseRepository caseRepository,
            DemoCasePurgeStore purgeStore) {
        this.caseRepository = caseRepository;
        this.purgeStore = purgeStore;
    }

    @Transactional
    public DemoCasePurgeView purge(String caseId, AuthenticatedActor actor) {
        if (actor.role() != ActorRole.PLATFORM_REVIEWER) {
            throw new ForbiddenException(
                    "only the platform reviewer can delete simulated cases");
        }

        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case was not found",
                                                Map.of("case_id", caseId)));

        if (disputeCase.getSourceType() != CaseSourceType.EXTERNAL_IMPORT
                || !PURGEABLE_SOURCE_SYSTEMS.contains(
                        disputeCase.getSourceSystem())) {
            throw new ForbiddenException(
                    "only simulated imported cases can be deleted");
        }

        purgeStore.purge(caseId, actor.actorId(), actor.role().name());
        return new DemoCasePurgeView(caseId, true);
    }
}
