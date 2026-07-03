package com.example.dispute.evidence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.example.dispute.evidence.api.EvidenceController;
import com.example.dispute.evidence.application.EvidenceApplicationService;
import com.example.dispute.evidence.application.EvidenceCatalogService;
import com.example.dispute.evidence.application.EvidenceCompletionService;
import com.example.dispute.evidence.application.EvidenceCompletionStatusView;
import com.example.dispute.evidence.application.EvidenceCompletionView;
import com.example.dispute.evidence.application.EvidenceDossierQueryService;
import com.example.dispute.evidence.application.EvidenceVerificationService;
import com.example.dispute.evidence.application.RoleScopedEvidenceView;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EvidenceController.class)
@Import({
    CommonConfiguration.class,
    TraceIdFilter.class,
    HeaderAuthenticationFilter.class,
    SecurityConfiguration.class,
    SecurityFailureWriter.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    GlobalExceptionHandler.class
})
class EvidenceRoomControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private EvidenceApplicationService applicationService;
    @MockitoBean private EvidenceCatalogService catalogService;
    @MockitoBean private EvidenceVerificationService verificationService;
    @MockitoBean private EvidenceCompletionService completionService;
    @MockitoBean private EvidenceDossierQueryService dossierQueryService;

    @Test
    void exposesTheRoleScopedCatalogOnTheFinalUnversionedApi() throws Exception {
        when(catalogService.catalog(eq("CASE_evidence"), any()))
                .thenReturn(
                        new RoleScopedEvidenceView(
                                "CASE_evidence",
                                List.of(
                                        new RoleScopedEvidenceView.Item(
                                                "EVIDENCE_1",
                                                "LOGISTICS_PROOF",
                                                "MERCHANT",
                                                "PRIVATE",
                                                null,
                                                true,
                                                null))));

        mockMvc.perform(
                        get("/api/disputes/CASE_evidence/evidence")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].evidence_id").value("EVIDENCE_1"))
                .andExpect(jsonPath("$.data.items[0].redacted").value(true));
    }

    @Test
    void completesEvidenceWithoutAcceptingAClientDossierVersion() throws Exception {
        when(completionService.complete(
                        eq("CASE_evidence"), any(), eq("complete-user-1")))
                .thenReturn(
                        new EvidenceCompletionView(
                                "CASE_evidence",
                                2,
                                ActorRole.USER,
                                false,
                                "EVIDENCE",
                                OffsetDateTime.parse("2026-07-03T03:00:00Z")));

        mockMvc.perform(
                        post("/api/disputes/CASE_evidence/evidence/complete")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "complete-user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dossier_version").value(2))
                .andExpect(jsonPath("$.data.all_parties_completed").value(false));
    }

    @Test
    void returnsTheSharedCompletionProjection() throws Exception {
        when(completionService.status(eq("CASE_evidence"), any()))
                .thenReturn(
                        new EvidenceCompletionStatusView(
                                "CASE_evidence",
                                2,
                                true,
                                false,
                                false,
                                "EVIDENCE",
                                OffsetDateTime.parse("2026-07-03T03:00:00Z")));

        mockMvc.perform(
                        get("/api/disputes/CASE_evidence/evidence/completion")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_completed").value(true))
                .andExpect(jsonPath("$.data.merchant_completed").value(false));
    }
}
