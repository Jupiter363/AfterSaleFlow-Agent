package com.example.dispute.evidence.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceVerificationService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceVerificationRepository verificationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvidenceVerificationService(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceVerificationRepository verificationRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.verificationRepository = verificationRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public EvidenceVerificationView verify(
            String caseId,
            String evidenceId,
            EvidenceVerificationCommand command,
            AuthenticatedActor actor,
            String traceId) {
        if (actor.role() != ActorRole.SYSTEM
                && actor.role() != ActorRole.PLATFORM_REVIEWER
                && actor.role() != ActorRole.ADMIN) {
            throw new SecurityException("evidence verification requires a trusted actor");
        }
        caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
        EvidenceItemEntity evidence =
                evidenceRepository.findById(evidenceId)
                        .filter(item -> item.getCaseId().equals(caseId))
                        .orElseThrow(() -> new IllegalArgumentException("evidence not found"));
        int version =
                verificationRepository
                                .findTopByEvidenceIdOrderByVerificationVersionDesc(evidence.getId())
                                .map(item -> item.getVerificationVersion() + 1)
                                .orElse(1);
        EvidenceVerificationStatus status = status(command);
        var reasons = reasons(command);
        EvidenceVerificationEntity saved =
                verificationRepository.save(
                        EvidenceVerificationEntity.create(
                                "VERIFY_" + compactUuid(),
                                caseId,
                                evidenceId,
                                version,
                                status,
                                checksJson(command),
                                command.agentFindingsJson(),
                                json(reasons),
                                status == EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW,
                                clock.instant(),
                                actor.actorId(),
                                traceId));
        return view(saved);
    }

    private static EvidenceVerificationStatus status(EvidenceVerificationCommand command) {
        if (!command.signatureValid() || !command.mimeValid() || !command.sizeAllowed()) {
            return EvidenceVerificationStatus.REJECTED;
        }
        if (command.agentFlagsConflict()) {
            return EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW;
        }
        if (!command.hashValid() || command.duplicate()) {
            return EvidenceVerificationStatus.SUSPICIOUS;
        }
        return command.sourceTrusted()
                ? EvidenceVerificationStatus.VERIFIED
                : EvidenceVerificationStatus.PLAUSIBLE;
    }

    private static java.util.List<String> reasons(EvidenceVerificationCommand command) {
        var reasons = new ArrayList<String>();
        if (!command.hashValid()) reasons.add("HASH_MISMATCH");
        if (!command.signatureValid()) reasons.add("SIGNATURE_INVALID");
        if (!command.mimeValid()) reasons.add("MIME_MISMATCH");
        if (!command.sizeAllowed()) reasons.add("FILE_SIZE_INVALID");
        if (!command.sourceTrusted()) reasons.add("SOURCE_NOT_PROVEN");
        if (command.duplicate()) reasons.add("DUPLICATE_EVIDENCE");
        if (command.agentFlagsConflict()) reasons.add("AGENT_CONSISTENCY_CONFLICT");
        return reasons;
    }

    private String checksJson(EvidenceVerificationCommand command) {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("hash_valid", command.hashValid());
        checks.put("signature_valid", command.signatureValid());
        checks.put("source_trusted", command.sourceTrusted());
        checks.put("mime_valid", command.mimeValid());
        checks.put("size_allowed", command.sizeAllowed());
        checks.put("duplicate", command.duplicate());
        return json(checks);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize verification", exception);
        }
    }

    private static EvidenceVerificationView view(EvidenceVerificationEntity entity) {
        boolean include =
                entity.getVerificationStatus() != EvidenceVerificationStatus.REJECTED;
        return new EvidenceVerificationView(
                entity.getId(),
                entity.getEvidenceId(),
                entity.getVerificationVersion(),
                entity.getVerificationStatus(),
                entity.isRequiresHumanReview(),
                include,
                true,
                entity.getVerifiedAt());
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
