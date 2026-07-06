package com.example.dispute.evidence.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceCatalogService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceVerificationRepository verificationRepository;

    public EvidenceCatalogService(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceVerificationRepository verificationRepository) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.verificationRepository = verificationRepository;
    }

    @Transactional(readOnly = true)
    public RoleScopedEvidenceView catalog(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository.findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertCanAccess(dispute, actor);
        var items =
                evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(caseId)
                        .stream()
                        .filter(item -> canSeeCatalogItem(item, actor))
                        .map(item -> project(caseId, item, actor))
                        .toList();
        return new RoleScopedEvidenceView(caseId, items);
    }

    private static boolean canSeeCatalogItem(EvidenceItemEntity item, AuthenticatedActor actor) {
        if ("PARTIES".equals(item.getVisibility())) {
            return true;
        }
        if (actor.role().name().equals(item.getSubmittedByRole())) {
            return true;
        }
        return isPrivilegedEvidenceViewer(actor.role())
                || "PLATFORM".equals(item.getVisibility())
                        && actor.role() == ActorRole.CUSTOMER_SERVICE;
    }

    private RoleScopedEvidenceView.Item project(
            String caseId, EvidenceItemEntity item, AuthenticatedActor actor) {
        boolean privileged = isPrivilegedEvidenceViewer(actor.role());
        boolean owns = actor.role().name().equals(item.getSubmittedByRole());
        boolean visible =
                privileged
                        || "PARTIES".equals(item.getVisibility())
                        || owns
                        || "PLATFORM".equals(item.getVisibility())
                                && actor.role() == ActorRole.CUSTOMER_SERVICE;
        String contentUrl =
                visible
                        ? "/api/disputes/" + caseId + "/evidence/" + item.getId() + "/content"
                        : null;
        var status =
                verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(item.getId())
                        .map(itemVerification -> itemVerification.getVerificationStatus())
                        .orElse(null);
        return new RoleScopedEvidenceView.Item(
                item.getId(),
                item.getEvidenceType(),
                item.getSubmittedByRole(),
                item.getVisibility(),
                contentUrl,
                !visible,
                status);
    }

    private static boolean isPrivilegedEvidenceViewer(ActorRole role) {
        return role == ActorRole.PLATFORM_REVIEWER
                || role == ActorRole.ADMIN
                || role == ActorRole.SYSTEM;
    }

    private static void assertCanAccess(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) throw new ForbiddenException("actor cannot access evidence catalog");
    }
}
