package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.BuildDossierResult;
import com.example.dispute.evidence.application.EvidenceApplicationService;
import com.example.dispute.evidence.application.EvidenceSearchIndexer;
import com.example.dispute.evidence.application.EvidenceStorage;
import com.example.dispute.evidence.application.EvidenceView;
import com.example.dispute.evidence.application.OcrTaskClient;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class EvidenceApplicationServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private EvidenceStorage storage;
    @Mock private OcrTaskClient ocrTaskClient;
    @Mock private EvidenceSearchIndexer searchIndexer;
    @Mock private AuditRecorder auditRecorder;

    private EvidenceApplicationService service;

    @BeforeEach
    void setUp() {
        service =
                new EvidenceApplicationService(
                        caseRepository,
                        evidenceRepository,
                        dossierRepository,
                        storage,
                        ocrTaskClient,
                        searchIndexer,
                        new ObjectMapper().findAndRegisterModules(),
                        auditRecorder);
    }

    @Test
    void uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails() throws Exception {
        FulfillmentCaseEntity disputeCase = caseEntity();
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(disputeCase));
        when(evidenceRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                        any(), any(), any()))
                .thenReturn(Optional.empty());
        when(evidenceRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.storeOriginal(any(), any(), any(), any(), any()))
                .thenReturn(
                        new EvidenceStorage.StoredObject(
                                "evidence-original",
                                "CASE_evidence/EVIDENCE_test/proof.png"));
        doThrow(new IllegalStateException("ocr unavailable"))
                .when(ocrTaskClient)
                .createParseTask(any());
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "绛炬敹璇佹槑.png",
                        "image/png",
                        new byte[] {
                            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
                        });

        EvidenceView result =
                service.upload(
                        "CASE_evidence",
                        file,
                        "LOGISTICS_PROOF",
                        "USER_UPLOAD",
                        "PARTIES",
                        true,
                        null,
                        actor());

        assertThat(result.fileHash()).matches("[0-9a-f]{64}");
        assertThat(result.fileBucket()).isEqualTo("evidence-original");
        assertThat(result.parseStatus()).isEqualTo("PENDING");
        assertThat(result.submissionStatus()).isEqualTo("PENDING_SUBMISSION");
        assertThat(result.desensitized()).isFalse();
        ArgumentCaptor<EvidenceItemEntity> storedEvidence =
                ArgumentCaptor.forClass(EvidenceItemEntity.class);
        verify(evidenceRepository).save(storedEvidence.capture());
        assertThat(storedEvidence.getValue().getMetadataJson())
                .contains("\"model_processing_authorized\":true")
                .contains("CURRENT_DISPUTE_EVIDENCE_REVIEW");
        verify(storage).storeOriginal(any(), any(), any(), any(), any());
        verify(searchIndexer).indexMetadata(any());
    }

    @Test
    void duplicateAuthorizationMergesWithExistingMetadata() {
        EvidenceItemEntity existing = evidenceEntity("EVIDENCE_existing");
        existing.authorizeModelProcessing(
                "{\"ocr_language\":\"zh-CN\",\"capture_source\":\"mobile\"}",
                "user-evidence");
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository
                        .findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                                any(), any(), any()))
                .thenReturn(Optional.of(existing));
        when(evidenceRepository.save(existing)).thenReturn(existing);
        MockMultipartFile file = pngFile();

        service.upload(
                "CASE_evidence",
                file,
                "LOGISTICS_PROOF",
                "USER_UPLOAD",
                "PARTIES",
                true,
                null,
                actor());

        assertThat(existing.getMetadataJson())
                .contains("\"ocr_language\":\"zh-CN\"")
                .contains("\"capture_source\":\"mobile\"")
                .contains("\"model_processing_authorized\":true")
                .contains("CURRENT_DISPUTE_EVIDENCE_REVIEW");
        verify(storage, never()).storeOriginal(any(), any(), any(), any(), any());
    }

    @Test
    void systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization() {
        EvidenceItemEntity item = evidenceEntity("EVIDENCE_private");
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findById("EVIDENCE_private"))
                .thenReturn(Optional.of(item));
        AuthenticatedActor system =
                new AuthenticatedActor("python-agent-service", ActorRole.SYSTEM);

        assertThatThrownBy(
                        () ->
                                service.contentForModel(
                                        "CASE_evidence", "EVIDENCE_private", system))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not authorized for model processing");

        verify(storage, never()).loadOriginal(any(), any());
    }

    @Test
    void systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization() {
        EvidenceItemEntity item = evidenceEntity("EVIDENCE_authorized");
        item.authorizeModelProcessing(
                "{\"model_processing_authorized\":true,\"authorization_scope\":\"CURRENT_DISPUTE_EVIDENCE_REVIEW\"}",
                "user-evidence");
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findById("EVIDENCE_authorized"))
                .thenReturn(Optional.of(item));
        when(storage.loadOriginal("evidence-original", "CASE_evidence/proof.png"))
                .thenReturn(new byte[] {1, 2, 3});
        AuthenticatedActor system =
                new AuthenticatedActor("python-agent-service", ActorRole.SYSTEM);

        var content =
                service.contentForModel(
                        "CASE_evidence", "EVIDENCE_authorized", system);

        assertThat(content.content()).containsExactly(1, 2, 3);
    }

    @Test
    void systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization() {
        EvidenceItemEntity item = mock(EvidenceItemEntity.class);
        when(item.getCaseId()).thenReturn("CASE_evidence");
        when(item.getDeletedAt()).thenReturn(null);
        when(item.isDesensitized()).thenReturn(true);
        when(item.getOriginalFilename()).thenReturn("redacted.png");
        when(item.getContentType()).thenReturn("image/png");
        when(item.getFileBucket()).thenReturn("evidence-redacted");
        when(item.getFileObjectKey()).thenReturn("CASE_evidence/redacted.png");
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findById("EVIDENCE_redacted"))
                .thenReturn(Optional.of(item));
        when(storage.loadOriginal("evidence-redacted", "CASE_evidence/redacted.png"))
                .thenReturn(new byte[] {4, 5, 6});

        var content =
                service.contentForModel(
                        "CASE_evidence",
                        "EVIDENCE_redacted",
                        new AuthenticatedActor("python-agent-service", ActorRole.SYSTEM));

        assertThat(content.content()).containsExactly(4, 5, 6);
    }

    @Test
    void uploadsMarkdownEvidenceAsTextParseableMaterial() throws Exception {
        FulfillmentCaseEntity disputeCase = caseEntity();
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(disputeCase));
        when(evidenceRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                        any(), any(), any()))
                .thenReturn(Optional.empty());
        when(evidenceRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.storeOriginal(any(), any(), any(), any(), any()))
                .thenReturn(
                        new EvidenceStorage.StoredObject(
                                "evidence-original",
                                "CASE_evidence/EVIDENCE_test/chat-record.md"));
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "chat-record.md",
                        "text/markdown",
                        """
                        # 娌熼€氳褰?                        鐢ㄦ埛绉扮鏀跺悗鍙戠幇琛ㄧ洏鍒掔棔銆?                        """
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        EvidenceView result =
                service.upload(
                        "CASE_evidence",
                        file,
                        "CHAT_SCREENSHOT",
                        "USER_UPLOAD",
                        "PARTIES",
                        null,
                        actor());

        assertThat(result.originalFilename()).isEqualTo("chat-record.md");
        assertThat(result.contentType()).isEqualTo("text/markdown");
        assertThat(result.parseStatus()).isEqualTo("PENDING");
        assertThat(result.submissionStatus()).isEqualTo("PENDING_SUBMISSION");
        verify(storage).storeOriginal(any(), any(), any(), any(), any());
        verify(ocrTaskClient).createParseTask(any());
    }

    @Test
    void reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence() throws Exception {
        FulfillmentCaseEntity disputeCase = caseEntity();
        EvidenceItemEntity voided =
                EvidenceItemEntity.uploaded(
                        "EVIDENCE_voided",
                        "CASE_evidence",
                        "DOSSIER_existing",
                        "DOCUMENT",
                        "USER_UPLOAD",
                        ActorRole.USER.name(),
                        "user-evidence",
                        "evidence-original",
                        "CASE_evidence/EVIDENCE_voided/proof.md",
                        "same-hash",
                        "proof.md",
                        "text/markdown",
                        8,
                        "PRIVATE",
                        null);
        voided.deletePending(java.time.OffsetDateTime.now(), "user-evidence");
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(disputeCase));
        when(evidenceRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                        any(), any(), any()))
                .thenReturn(Optional.empty());
        when(dossierRepository.findByCaseId("CASE_evidence"))
                .thenReturn(Optional.of(EvidenceDossierEntity.collecting(
                        "DOSSIER_existing", "CASE_evidence", "user-evidence")));
        when(evidenceRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.storeOriginal(any(), any(), any(), any(), any()))
                .thenReturn(
                        new EvidenceStorage.StoredObject(
                                "evidence-original",
                                "CASE_evidence/EVIDENCE_fresh/proof.md"));
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "proof.md",
                        "text/markdown",
                        "evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        EvidenceView result =
                service.upload(
                        "CASE_evidence",
                        file,
                        "DOCUMENT",
                        "USER_UPLOAD",
                        "PRIVATE",
                        null,
                        actor());

        assertThat(result.id()).isNotEqualTo("EVIDENCE_voided");
        assertThat(result.submissionStatus()).isEqualTo("PENDING_SUBMISSION");
        verify(storage).storeOriginal(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsExecutableContentBeforeCallingStorage() {
        when(caseRepository.findById("CASE_evidence"))
                .thenReturn(Optional.of(caseEntity()));
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "payload.exe",
                        "application/octet-stream",
                        new byte[] {77, 90});

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        "CASE_evidence",
                                        file,
                                        "OTHER",
                                        "USER_UPLOAD",
                                        "PRIVATE",
                                        null,
                                        actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content type");
    }

    @Test
    void rejectsExecutableBytesEvenWhenContentTypeClaimsPng() {
        when(caseRepository.findById("CASE_evidence"))
                .thenReturn(Optional.of(caseEntity()));
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "fake.png",
                        "image/png",
                        new byte[] {'M', 'Z', 0, 0});

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        "CASE_evidence",
                                        file,
                                        "OTHER",
                                        "USER_UPLOAD",
                                        "PRIVATE",
                                        null,
                                        actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void userCannotClaimThatUploadedEvidenceCameFromThePlatform() {
        when(caseRepository.findById("CASE_evidence"))
                .thenReturn(Optional.of(caseEntity()));
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "proof.png",
                        "image/png",
                        new byte[] {
                            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
                        });

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        "CASE_evidence",
                                        file,
                                        "OTHER",
                                        "PLATFORM_UPLOAD",
                                        "PARTIES",
                                        null,
                                        actor()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("source");
    }

    @Test
    void buildsVersionedTimelineWithoutResponsibilityOrDecisionFields() {
        FulfillmentCaseEntity disputeCase = caseEntity();
        when(caseRepository.findById("CASE_evidence"))
                .thenReturn(Optional.of(disputeCase));
        when(evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        "CASE_evidence"))
                .thenReturn(List.of());
        when(dossierRepository.findByCaseId("CASE_evidence"))
                .thenReturn(Optional.empty());
        when(dossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BuildDossierResult result =
                service.buildDossier("CASE_evidence", actor());

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.evidences()).isEmpty();
        assertThat(result.timeline()).isEmpty();
        assertThat(result.summary()).doesNotContainKeys("decision", "responsibility");
        assertThat(disputeCase.getCaseStatus()).isEqualTo(CaseStatus.DOSSIER_BUILT);
    }

    @Test
    void getsFrozenDossierWithObjectShapedEvidenceMatrix() {
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(dossierRepository.findByCaseId("CASE_evidence"))
                .thenReturn(Optional.of(frozenDossier()));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_evidence"))
                .thenReturn(List.of());

        BuildDossierResult result = service.getDossier("CASE_evidence", actor());

        assertThat(result.matrix()).hasSize(1);
        assertThat(result.matrix().get(0).get("fact")).isEqualTo("物流显示已签收");
    }

    private static AuthenticatedActor actor() {
        return new AuthenticatedActor("user-evidence", ActorRole.USER);
    }

    private static FulfillmentCaseEntity caseEntity() {
        FulfillmentCaseEntity entity =
                FulfillmentCaseEntity.create(
                        "CASE_evidence",
                        "order-evidence",
                        null,
                        "user-evidence",
                        "merchant-evidence",
                        "idem-evidence",
                        "DISPUTE",
                        "LOGISTICS_DISPUTE",
                        "signed status is disputed",
                        RiskLevel.HIGH,
                        "user-evidence");
        entity.completeIntake(
                "NON_RECEIPT",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                """
                {"potentialDispute":true,"missingSlots":[],"agentDegraded":false,\
"analyzedAt":"2026-06-28T00:00:00Z"}
                """,
                "user-evidence");
        return entity;
    }

    private static MockMultipartFile pngFile() {
        return new MockMultipartFile(
                "file",
                "proof.png",
                "image/png",
                new byte[] {
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
                });
    }

    private static EvidenceItemEntity evidenceEntity(String id) {
        return EvidenceItemEntity.uploaded(
                id,
                "CASE_evidence",
                "DOSSIER_existing",
                "LOGISTICS_PROOF",
                "USER_UPLOAD",
                ActorRole.USER.name(),
                "user-evidence",
                "evidence-original",
                "CASE_evidence/proof.png",
                "hash",
                "proof.png",
                "image/png",
                8,
                "PARTIES",
                null);
    }

    private static EvidenceDossierEntity frozenDossier() {
        return EvidenceDossierEntity.frozen(
                "DOSSIER_FROZEN",
                "CASE_evidence",
                2,
                "system",
                "{\"evidence_count\":1}",
                "[]",
                """
                {
                  "fact_evidence_matrix": [
                    {
                      "fact_id": "FACT_SIGNED",
                      "fact": "物流显示已签收",
                      "supporting_evidence": ["EVIDENCE_LOGISTICS"]
                    }
                  ],
                  "unmapped_evidence": []
                }
                """);
    }
}
