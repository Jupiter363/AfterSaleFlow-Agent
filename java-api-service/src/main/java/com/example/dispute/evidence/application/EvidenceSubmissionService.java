package com.example.dispute.evidence.application;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceSubmissionBatchEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceSubmissionBatchRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceSubmissionService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceSubmissionBatchRepository batchRepository;
    private final RoomMessageService roomMessageService;
    private final ObjectMapper objectMapper;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public EvidenceSubmissionService(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceSubmissionBatchRepository batchRepository,
            RoomMessageService roomMessageService,
            ObjectMapper objectMapper,
            AuditRecorder auditRecorder,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.batchRepository = batchRepository;
        this.roomMessageService = roomMessageService;
        this.objectMapper = objectMapper;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    @Transactional
    public EvidenceSubmissionView submit(
            String caseId,
            EvidenceSubmissionCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertParty(dispute, actor);
        return batchRepository
                .findByCaseIdAndIdempotencyKey(caseId, idempotencyKey)
                .map(this::viewWithoutMessage)
                .orElseGet(
                        () ->
                                createSubmission(
                                        dispute,
                                        command,
                                        actor,
                                        idempotencyKey,
                                        traceId));
    }

    @Transactional
    public void deletePending(
            String caseId,
            String evidenceId,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertParty(dispute, actor);
        EvidenceItemEntity item =
                evidenceRepository
                        .findById(evidenceId)
                        .filter(evidence -> evidence.getCaseId().equals(caseId))
                        .orElseThrow(() -> new IllegalArgumentException("evidence not found"));
        assertOwner(item, actor);
        item.deletePending(OffsetDateTime.now(clock), actor.actorId());
        auditRecorder.record(
                actor,
                "EVIDENCE_PENDING_DELETED",
                "EVIDENCE_ITEM",
                evidenceId,
                caseId,
                Map.of("submission_status", "PENDING_SUBMISSION"),
                Map.of("submission_status", "VOIDED"));
    }

    private EvidenceSubmissionView createSubmission(
            FulfillmentCaseEntity dispute,
            EvidenceSubmissionCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId) {
        List<String> evidenceIds = normalizedEvidenceIds(command.evidenceIds());
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("evidence_ids must not be empty");
        }
        List<EvidenceItemEntity> evidences = evidenceRepository.findAllById(evidenceIds);
        if (evidences.size() != evidenceIds.size()) {
            throw new IllegalArgumentException("some evidence items were not found");
        }
        for (EvidenceItemEntity item : evidences) {
            if (!item.getCaseId().equals(dispute.getId())) {
                throw new IllegalArgumentException("evidence belongs to a different case");
            }
            assertOwner(item, actor);
            if (item.getSubmissionStatus() != EvidenceSubmissionStatus.PENDING_SUBMISSION) {
                throw new IllegalStateException("only pending evidence can be submitted");
            }
        }
        Instant submittedAt = clock.instant();
        EvidenceSubmissionBatchEntity batch =
                EvidenceSubmissionBatchEntity.submitted(
                        "EVIDENCE_BATCH_" + compactUuid(),
                        dispute.getId(),
                        actor.role().name(),
                        actor.actorId(),
                        json(evidenceIds),
                        command.batchNote(),
                        idempotencyKey,
                        submittedAt);
        batchRepository.save(batch);
        OffsetDateTime submittedOffset = OffsetDateTime.ofInstant(submittedAt, ZoneOffset.UTC);
        for (EvidenceItemEntity item : evidences) {
            item.markSubmittedForParties(batch.getId(), submittedOffset, actor.actorId());
        }
        RoomMessageView message =
                roomMessageService.post(
                        dispute.getId(),
                        submissionRoom(dispute),
                        new RoomMessageCommand(
                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                submissionMessage(actor, evidenceIds, command.batchNote()),
                                evidenceIds),
                        actor,
                        "evidence-batch-message:" + idempotencyKey,
                        traceId);
        batch.attachRoomMessage(message.id());
        auditRecorder.record(
                actor,
                "EVIDENCE_BATCH_SUBMITTED",
                "EVIDENCE_SUBMISSION_BATCH",
                batch.getId(),
                dispute.getId(),
                Map.of(),
                Map.of(
                        "evidence_count", evidenceIds.size(),
                        "room_message_id", message.id()));
        return view(batch, message);
    }

    private static RoomType submissionRoom(FulfillmentCaseEntity dispute) {
        if (RoomType.HEARING.name().equals(dispute.getCurrentRoom())) {
            return RoomType.HEARING;
        }
        return RoomType.EVIDENCE;
    }

    private static void assertParty(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                actor.role() == ActorRole.USER && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        if (!allowed) {
            throw new ForbiddenException("only case parties can submit evidence");
        }
    }

    private static void assertOwner(EvidenceItemEntity item, AuthenticatedActor actor) {
        if (!actor.role().name().equals(item.getSubmittedByRole())
                || !actor.actorId().equals(item.getSubmittedById())) {
            throw new ForbiddenException("actor cannot mutate counterparty evidence");
        }
    }

    private static List<String> normalizedEvidenceIds(List<String> evidenceIds) {
        return new LinkedHashSet<>(evidenceIds).stream().toList();
    }

    private static String submissionMessage(
            AuthenticatedActor actor, List<String> evidenceIds, String batchNote) {
        String party = actor.role() == ActorRole.MERCHANT ? "商家" : "用户";
        String note = batchNote == null || batchNote.isBlank() ? "" : "备注：" + batchNote;
        return party
                + "提交了 "
                + evidenceIds.size()
                + " 份证据材料，请证据书记官围绕来源、形成时间、真实性、完整性和案情关联性进行核验。"
                + note;
    }

    private EvidenceSubmissionView viewWithoutMessage(EvidenceSubmissionBatchEntity batch) {
        return view(batch, null);
    }

    private EvidenceSubmissionView view(
            EvidenceSubmissionBatchEntity batch, RoomMessageView message) {
        return new EvidenceSubmissionView(
                batch.getId(),
                batch.getCaseId(),
                batch.getActorRole(),
                batch.getActorId(),
                readEvidenceIds(batch.getEvidenceIdsJson()),
                batch.getBatchNote(),
                batch.getSubmitStatus(),
                batch.getSubmittedAt(),
                message);
    }

    private List<String> readEvidenceIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid evidence batch ids", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence batch", exception);
        }
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
