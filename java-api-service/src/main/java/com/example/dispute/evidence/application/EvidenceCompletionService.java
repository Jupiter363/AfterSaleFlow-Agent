package com.example.dispute.evidence.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidencePartyCompletionEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidencePartyCompletionRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceCompletionService {

    private static final Duration HEARING_WINDOW = Duration.ofHours(3);

    private final FulfillmentCaseRepository caseRepository;
    private final EvidencePartyCompletionRepository completionRepository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository clockRepository;
    private final Clock clock;

    public EvidenceCompletionService(
            FulfillmentCaseRepository caseRepository,
            EvidencePartyCompletionRepository completionRepository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository clockRepository,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.completionRepository = completionRepository;
        this.roomRepository = roomRepository;
        this.clockRepository = clockRepository;
        this.clock = clock;
    }

    @Transactional
    public EvidenceCompletionView complete(
            String caseId,
            int dossierVersion,
            AuthenticatedActor actor,
            String idempotencyKey) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertParty(dispute, actor);
        Optional<EvidencePartyCompletionEntity> existing =
                completionRepository.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey);
        if (existing.isEmpty()) {
            completionRepository.save(
                    EvidencePartyCompletionEntity.completed(
                            "EVIDENCE_COMPLETE_" + compactUuid(),
                            caseId,
                            dossierVersion,
                            actor.role(),
                            actor.actorId(),
                            idempotencyKey,
                            clock.instant()));
        }
        long completed =
                completionRepository.countByCaseIdAndDossierVersionAndCompletionStatus(
                        caseId, dossierVersion, "COMPLETED");
        if (completed < 2) {
            return new EvidenceCompletionView(
                    caseId, dossierVersion, actor.role(), false, "EVIDENCE",
                    dispute.getCurrentDeadlineAt());
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        sealEvidenceAndOpenHearing(dispute, now, actor.actorId());
        return new EvidenceCompletionView(
                caseId,
                dossierVersion,
                actor.role(),
                true,
                "HEARING",
                dispute.getCurrentDeadlineAt());
    }

    private void sealEvidenceAndOpenHearing(
            FulfillmentCaseEntity dispute, OffsetDateTime now, String actorId) {
        CaseRoomEntity evidenceRoom =
                roomRepository
                        .findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE)
                        .orElseThrow(() -> new IllegalArgumentException("evidence room not found"));
        evidenceRoom.seal(now, actorId);
        roomRepository.save(evidenceRoom);
        CasePhaseClockEntity evidenceClock =
                clockRepository
                        .findByCaseIdAndClockType(
                                dispute.getId(), PhaseClockType.EVIDENCE_SUBMISSION)
                        .orElseThrow(() -> new IllegalArgumentException("evidence clock not found"));
        evidenceClock.completeEarly(now, actorId);
        clockRepository.save(evidenceClock);

        CaseRoomEntity hearingRoom =
                CaseRoomEntity.open(
                        "ROOM_" + compactUuid(),
                        dispute.getId(),
                        RoomType.HEARING,
                        now,
                        actorId);
        roomRepository.save(hearingRoom);
        OffsetDateTime hearingDeadline = now.plus(HEARING_WINDOW);
        clockRepository.save(
                CasePhaseClockEntity.running(
                        "CLOCK_" + compactUuid(),
                        dispute.getId(),
                        hearingRoom.getId(),
                        PhaseClockType.HEARING,
                        now,
                        hearingDeadline,
                        "hearing-window-" + dispute.getId(),
                        actorId));
        dispute.openHearing(hearingDeadline, actorId);
        caseRepository.save(dispute);
    }

    private static void assertParty(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                actor.role() == ActorRole.USER
                                && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        if (!allowed) throw new SecurityException("only case parties can complete evidence");
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
