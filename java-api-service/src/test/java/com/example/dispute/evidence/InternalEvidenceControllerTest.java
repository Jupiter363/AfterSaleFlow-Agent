package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.api.InternalEvidenceController;
import com.example.dispute.evidence.application.EvidenceApplicationService;
import com.example.dispute.evidence.application.EvidenceContentView;
import com.example.dispute.evidence.application.EvidenceParseResultService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class InternalEvidenceControllerTest {

    @Mock private EvidenceParseResultService parseResultService;
    @Mock private EvidenceApplicationService evidenceService;
    @Mock private AppProperties properties;
    @Mock private AppProperties.Security security;
    @Mock private Authentication authentication;

    private InternalEvidenceController controller;
    private AuthenticatedActor systemActor;

    @BeforeEach
    void setUp() {
        when(properties.security()).thenReturn(security);
        when(security.serviceSecret()).thenReturn("java-service-secret");
        systemActor = new AuthenticatedActor("python-agent-service", ActorRole.SYSTEM);
        controller =
                new InternalEvidenceController(
                        parseResultService,
                        evidenceService,
                        properties,
                        Clock.systemUTC());
    }

    @Test
    void systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret() {
        byte[] bytes = "image-bytes".getBytes(StandardCharsets.UTF_8);
        when(authentication.getPrincipal()).thenReturn(systemActor);
        when(evidenceService.contentForModel("CASE_1", "EVIDENCE_1", systemActor))
                .thenReturn(new EvidenceContentView("proof.jpg", "image/jpeg", bytes));

        var response =
                controller.content(
                        "CASE_1",
                        "EVIDENCE_1",
                        "java-service-secret",
                        authentication);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getBody()).isEqualTo(bytes);
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("proof.jpg");
    }

    @Test
    void rejectsWrongJavaServiceSecretBeforeReadingEvidence() {
        assertThatThrownBy(
                        () ->
                                controller.content(
                                        "CASE_1",
                                        "EVIDENCE_1",
                                        "wrong-secret",
                                        authentication))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Java service credential");

        verify(evidenceService, never())
                .contentForModel(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }
}
