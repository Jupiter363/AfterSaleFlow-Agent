package com.example.dispute.evidence.application;

import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceDossierItemEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceDossierFreezer {

    private final EvidenceDossierRepository dossierRepository;
    private final EvidenceDossierItemRepository dossierItemRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceVerificationRepository verificationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvidenceDossierFreezer(
            EvidenceDossierRepository dossierRepository,
            EvidenceDossierItemRepository dossierItemRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceVerificationRepository verificationRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.dossierRepository = dossierRepository;
        this.dossierItemRepository = dossierItemRepository;
        this.evidenceRepository = evidenceRepository;
        this.verificationRepository = verificationRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public int targetVersion(String caseId) {
        return dossierRepository
                        .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                        .map(dossier -> dossier.getDossierVersion() + 1)
                        .orElse(1);
    }

    @Transactional(readOnly = true)
    public int latestVersion(String caseId) {
        return dossierRepository
                .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                .map(EvidenceDossierEntity::getDossierVersion)
                .orElseThrow(() -> new IllegalArgumentException("dossier not found"));
    }

    @Transactional
    public EvidenceDossierEntity freeze(
            String caseId, int targetVersion, String actorId) {
        return dossierRepository
                .findByCaseIdAndDossierVersion(caseId, targetVersion)
                .orElseGet(() -> createFrozen(caseId, targetVersion, actorId));
    }

    private EvidenceDossierEntity createFrozen(
            String caseId, int targetVersion, String actorId) {
        List<IncludedEvidence> included =
                evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                caseId)
                        .stream()
                        .map(this::withLatestStatus)
                        .filter(
                                item ->
                                        item.status()
                                                != EvidenceVerificationStatus.REJECTED)
                        .toList();

        List<Map<String, Object>> timeline = new ArrayList<>();
        List<Map<String, Object>> matrix = new ArrayList<>();
        for (IncludedEvidence item : included) {
            EvidenceItemEntity evidence = item.evidence();
            Map<String, Object> timelineEntry = new LinkedHashMap<>();
            timelineEntry.put("evidence_id", evidence.getId());
            timelineEntry.put("evidence_type", evidence.getEvidenceType());
            timelineEntry.put(
                    "occurred_at",
                    evidence.getOccurredAt() == null
                            ? evidence.getCreatedAt()
                            : evidence.getOccurredAt());
            timelineEntry.put("verification_status", statusName(item.status()));
            timeline.add(timelineEntry);

            Map<String, Object> matrixEntry = new LinkedHashMap<>();
            matrixEntry.put("evidence_id", evidence.getId());
            matrixEntry.put("relation_type", "UNMAPPED");
            matrixEntry.put("verification_status", statusName(item.status()));
            matrix.add(matrixEntry);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("evidence_count", included.size());
        summary.put(
                "verification_statuses",
                included.stream().map(item -> statusName(item.status())).toList());
        summary.put("frozen", true);

        EvidenceDossierEntity dossier =
                dossierRepository.save(
                        EvidenceDossierEntity.frozen(
                                "DOSSIER_" + compactUuid(),
                                caseId,
                                targetVersion,
                                actorId,
                                json(summary),
                                json(timeline),
                                json(matrix)));

        int sequence = 1;
        List<EvidenceDossierItemEntity> snapshots = new ArrayList<>();
        for (IncludedEvidence item : included) {
            EvidenceItemEntity evidence = item.evidence();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("evidence_type", evidence.getEvidenceType());
            snapshot.put("source_type", evidence.getSourceType());
            snapshot.put("file_hash", evidence.getFileHash());
            snapshot.put("visibility", evidence.getVisibility());
            snapshot.put("submitted_by_role", evidence.getSubmittedByRole());
            snapshot.put("verification_status", statusName(item.status()));
            snapshots.add(
                    EvidenceDossierItemEntity.snapshot(
                            "DOSSIER_ITEM_" + compactUuid(),
                            caseId,
                            dossier.getId(),
                            evidence.getId(),
                            sequence++,
                            json(snapshot),
                            clock.instant(),
                            actorId));
        }
        dossierItemRepository.saveAll(snapshots);
        return dossier;
    }

    private IncludedEvidence withLatestStatus(EvidenceItemEntity evidence) {
        EvidenceVerificationStatus status =
                verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(evidence.getId())
                        .map(verification -> verification.getVerificationStatus())
                        .orElse(null);
        return new IncludedEvidence(evidence, status);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize frozen evidence dossier", exception);
        }
    }

    private static String statusName(EvidenceVerificationStatus status) {
        return status == null ? "UNVERIFIED" : status.name();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record IncludedEvidence(
            EvidenceItemEntity evidence, EvidenceVerificationStatus status) {}
}
