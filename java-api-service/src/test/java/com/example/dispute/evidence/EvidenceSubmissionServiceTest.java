package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceSubmissionCommand;
import com.example.dispute.evidence.application.EvidenceSubmissionService;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceSubmissionServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceSubmissionBatchRepository batchRepository;
    @Mock private RoomMessageService roomMessageService;
    @Mock private AuditRecorder auditRecorder;

    private EvidenceSubmissionService service;

    @BeforeEach
    void setUp() {
        service =
                new EvidenceSubmissionService(
                        caseRepository,
                        evidenceRepository,
                        batchRepository,
                        roomMessageService,
                        new ObjectMapper(),
                        auditRecorder,
                        Clock.fixed(
                                Instant.parse("2026-07-06T08:00:00Z"),
                                ZoneOffset.UTC));
    }

    @Test
    void submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity one = evidence("EVIDENCE_ONE");
        EvidenceItemEntity two = evidence("EVIDENCE_TWO");
        when(caseRepository.findByIdForUpdate(dispute.getId())).thenReturn(Optional.of(dispute));
        when(batchRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), "submit-1"))
                .thenReturn(Optional.empty());
        when(evidenceRepository.findAllById(List.of("EVIDENCE_ONE", "EVIDENCE_TWO")))
                .thenReturn(List.of(one, two));
        when(batchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMessageService.post(eq(dispute.getId()), eq(RoomType.EVIDENCE), any(), any(), any(), eq("TRACE_1")))
                .thenReturn(
                        new RoomMessageView(
                                "MESSAGE_BATCH",
                                dispute.getId(),
                                "ROOM_EVIDENCE",
                                4,
                                "USER",
                                "user-local",
                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                "submitted",
                                List.of("EVIDENCE_ONE", "EVIDENCE_TWO"),
                                Instant.parse("2026-07-06T08:00:00Z")));

        var result =
                service.submit(
                        dispute.getId(),
                        new EvidenceSubmissionCommand(
                                List.of("EVIDENCE_ONE", "EVIDENCE_TWO"),
                                "本批为物流签收争议材料"),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "submit-1",
                        "TRACE_1");

        assertThat(result.batchId()).startsWith("EVIDENCE_BATCH_");
        assertThat(result.evidenceIds()).containsExactly("EVIDENCE_ONE", "EVIDENCE_TWO");
        assertThat(result.roomMessage().attachmentRefs())
                .containsExactly("EVIDENCE_ONE", "EVIDENCE_TWO");
        assertThat(one.getSubmissionStatus().name()).isEqualTo("SUBMITTED");
        assertThat(two.getSubmissionStatus().name()).isEqualTo("SUBMITTED");

        ArgumentCaptor<RoomMessageCommand> commandCaptor =
                ArgumentCaptor.forClass(RoomMessageCommand.class);
        verify(roomMessageService)
                .post(
                        eq(dispute.getId()),
                        eq(RoomType.EVIDENCE),
                        commandCaptor.capture(),
                        any(),
                        eq("evidence-batch-message:submit-1"),
                        eq("TRACE_1"));
        assertThat(commandCaptor.getValue().messageType())
                .isEqualTo(MessageType.PARTY_EVIDENCE_REFERENCE);
        assertThat(commandCaptor.getValue().attachmentRefs())
                .containsExactly("EVIDENCE_ONE", "EVIDENCE_TWO");
    }

    @Test
    void deletesOnlyPendingEvidenceOwnedByCurrentActor() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity pending = evidence("EVIDENCE_PENDING");
        when(caseRepository.findByIdForUpdate(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        service.deletePending(
                dispute.getId(),
                pending.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(pending.getDeletedAt()).isNotNull();
    }

    @Test
    void refusesToDeleteSubmittedEvidence() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity submitted = evidence("EVIDENCE_SUBMITTED");
        submitted.markSubmitted(
                "BATCH_1",
                OffsetDateTime.parse("2026-07-06T08:00:00Z"),
                "user-local");
        when(caseRepository.findByIdForUpdate(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));

        assertThatThrownBy(
                        () ->
                                service.deletePending(
                                        dispute.getId(),
                                        submitted.getId(),
                                        new AuthenticatedActor("user-local", ActorRole.USER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submitted evidence cannot be deleted");
    }

    private static EvidenceItemEntity evidence(String id) {
        return EvidenceItemEntity.uploaded(
                id,
                "CASE_EVIDENCE_ROOM",
                "DOSSIER_1",
                "DOCUMENT",
                "USER_UPLOAD",
                "USER",
                "user-local",
                "evidence-original",
                "case/" + id,
                "hash-" + id,
                id + ".md",
                "text/markdown",
                128,
                "PRIVATE",
                OffsetDateTime.parse("2026-07-06T07:30:00Z"));
    }

    private static FulfillmentCaseEntity evidenceCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_EVIDENCE_ROOM",
                "ORDER-EVIDENCE",
                null,
                "LOG-EVIDENCE",
                "user-local",
                "merchant-local",
                "idem-evidence",
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "证据室已开放",
                RiskLevel.HIGH,
                CaseStatus.EVIDENCE_OPEN,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-06T10:00:00Z"),
                "OMS",
                "EXT-EVIDENCE",
                "external-adapter");
    }
}
