package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.application.EvidenceParseResultService;
import com.example.dispute.evidence.application.ParseResultCommand;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceParseResultServiceTest {

    @Mock private EvidenceItemRepository repository;
    @Mock private AuditRecorder auditRecorder;

    @Test
    void persistsSuccessfulTextAndExtractionMetadata() {
        EvidenceItemEntity entity = evidence();
        when(repository.findById("EVIDENCE_result")).thenReturn(Optional.of(entity));
        EvidenceParseResultService service =
                new EvidenceParseResultService(
                        repository,
                        new ObjectMapper().findAndRegisterModules(),
                        auditRecorder);

        service.apply(
                "EVIDENCE_result",
                new ParseResultCommand(
                        "SUCCEEDED",
                        "签收证明文字",
                        Map.of("engine", "paddleocr"),
                        null),
                new AuthenticatedActor("ocr-parser-service", ActorRole.SYSTEM));

        assertThat(entity.getParseStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(entity.getParsedText()).isEqualTo("签收证明文字");
        assertThat(entity.getExtractionJson()).contains("paddleocr");
        verify(auditRecorder)
                .record(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("EVIDENCE_PARSED"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static EvidenceItemEntity evidence() {
        return EvidenceItemEntity.uploaded(
                "EVIDENCE_result",
                "CASE_result",
                "DOSSIER_result",
                "LOGISTICS_PROOF",
                "USER_UPLOAD",
                "USER",
                "user-result",
                "evidence-original",
                "CASE_result/EVIDENCE_result/proof.png",
                "hash-result",
                "proof.png",
                "image/png",
                8,
                "PARTIES",
                null);
    }
}
