/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据房间，覆盖 「exposesTheRoleScopedCatalogOnTheFinalUnversionedApi」、「completesEvidenceWithoutAcceptingAClientDossierVersion」、「returnsTheSharedCompletionProjection」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.example.dispute.room.application.IntakeProgressService;
import com.example.dispute.evidence.application.EvidenceApplicationService;
import com.example.dispute.evidence.application.EvidenceCatalogService;
import com.example.dispute.evidence.application.EvidenceCompletionService;
import com.example.dispute.evidence.application.EvidenceCompletionStatusView;
import com.example.dispute.evidence.application.EvidenceCompletionView;
import com.example.dispute.evidence.application.EvidenceDossierQueryService;
import com.example.dispute.evidence.application.EvidenceVerificationService;
import com.example.dispute.evidence.application.RoleScopedEvidenceView;
import com.example.dispute.evidence.application.EvidenceSubmissionService;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionQuery;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceRoomControllerTest」。
// 类型职责：集中验证证据房间的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「exposesTheRoleScopedCatalogOnTheFinalUnversionedApi」、「completesEvidenceWithoutAcceptingAClientDossierVersion」、「returnsTheSharedCompletionProjection」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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
    @MockitoBean private IntakeProgressService intakeProgressService;
    @MockitoBean private EvidenceApplicationService applicationService;
    @MockitoBean private EvidenceCatalogService catalogService;
    @MockitoBean private EvidenceVerificationService verificationService;
    @MockitoBean private EvidenceCompletionService completionService;
    @MockitoBean private EvidenceDossierQueryService dossierQueryService;
    @MockitoBean private EvidenceSubmissionService submissionService;
    @MockitoBean private EvidenceProcessProjectionQuery processProjectionQuery;

    @Test
    void exposesOnlyTheActorScopedProcessProjectionWithPrivateNoStoreHeaders() throws Exception {
        EvidenceProcessProjectionView projection = new EvidenceProcessProjectionView(
                        EvidenceProcessProjectionView.SCHEMA_VERSION,
                        "0".repeat(64),
                        EvidenceProcessProjectionView.UNAVAILABLE,
                        "TENANT_legacy",
                        "CASE_evidence",
                        null,
                        0,
                        0,
                        "LEGACY",
                        "DISABLED",
                        false,
                        false,
                        false,
                        "user-local",
                        "USER",
                        "1".repeat(64),
                        "USER",
                        "OPEN",
                        null,
                        "NONE",
                        null,
                        OffsetDateTime.parse("2026-07-23T00:00:00Z"),
                        false,
                        null,
                        EvidenceProcessProjectionView.PartyCompletion.pending(),
                        EvidenceProcessProjectionView.AssessmentCounts.empty(),
                        null,
                        false,
                        0,
                        null,
                        null,
                        EvidenceProcessProjectionView.Recovery.none(),
                        EvidenceProcessProjectionView.VersionPins.legacy(),
                        0,
                        0,
                        OffsetDateTime.parse("2026-07-23T00:00:00Z"))
                .withComputedHash();
        when(processProjectionQuery.read(eq("CASE_evidence"), any(), eq(false)))
                .thenReturn(Optional.of(projection));

        mockMvc.perform(
                        get("/api/disputes/CASE_evidence/evidence/process-projection")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.data.schema_version")
                        .value("evidence-process-projection.v1"))
                .andExpect(jsonPath("$.data.viewer_actor_id").value("user-local"))
                .andExpect(jsonPath("$.data.formal_sink_allowed").value(false))
                .andExpect(jsonPath("$.data.temporal_evidence_allocation_allowed").value(false));

        verify(intakeProgressService).assertEvidenceReadAccess(eq("CASE_evidence"), any());
        verify(processProjectionQuery).read(eq("CASE_evidence"), any(), eq(false));
    }

    @Test
    void exposesTheP9TargetTemporalSyntheticProjectionContract() throws Exception {
        String caseId = "CASE_P9_SYNTHETIC_0001";
        EvidenceProcessProjectionView projection = new EvidenceProcessProjectionView(
                        EvidenceProcessProjectionView.SCHEMA_VERSION,
                        "0".repeat(64),
                        EvidenceProcessProjectionView.AVAILABLE,
                        "tenant-run001",
                        caseId,
                        "ROOM_P9_EVIDENCE_1",
                        4,
                        9,
                        "TEMPORAL",
                        "PRODUCTION",
                        false,
                        true,
                        false,
                        "user-local",
                        "USER",
                        "1".repeat(64),
                        "USER",
                        "WAITING_PARTIES",
                        null,
                        "WAITING_TIMER",
                        null,
                        OffsetDateTime.parse("2026-07-31T00:00:00Z"),
                        false,
                        null,
                        EvidenceProcessProjectionView.PartyCompletion.pending(),
                        EvidenceProcessProjectionView.AssessmentCounts.empty(),
                        null,
                        false,
                        0,
                        null,
                        null,
                        EvidenceProcessProjectionView.Recovery.none(),
                        EvidenceProcessProjectionView.VersionPins.target(
                                "p9-control-build",
                                "production-runtime-graph.2026-08-18.1",
                                "production-runtime-checkpoint.v2",
                                "all-rooms-prompt.production-runtime.v2",
                                "production-runtime.contract-blocked",
                                "all-rooms-policy.production-runtime.v1",
                                "all-rooms-guardrail.production-runtime.v1",
                                "tools.none.v1"),
                        12,
                        7,
                        OffsetDateTime.parse("2026-07-30T00:00:00Z"))
                .withComputedHash();
        when(processProjectionQuery.read(eq(caseId), any(), eq(false)))
                .thenReturn(Optional.of(projection));

        mockMvc.perform(
                        get("/api/disputes/{caseId}/evidence/process-projection", caseId)
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant_surrogate")
                        .value("tenant-run001"))
                .andExpect(jsonPath("$.data.case_id").value(caseId))
                .andExpect(jsonPath("$.data.writer_mode").value("TEMPORAL"))
                .andExpect(jsonPath("$.data.graph_runtime_mode")
                        .value("PRODUCTION"))
                .andExpect(jsonPath("$.data.formal_sink_allowed").value(false))
                .andExpect(jsonPath("$.data.temporal_evidence_allocation_allowed").value(true))
                .andExpect(jsonPath("$.data.real_case_shadow_allowed").value(false));

        verify(intakeProgressService).assertEvidenceReadAccess(eq(caseId), any());
        verify(processProjectionQuery).read(eq(caseId), any(), eq(false));
    }

    @Test
    void mapsAnUninitializedTargetTemporalProjectionToARetryableConflict() throws Exception {
        String caseId = "CASE_P9_SYNTHETIC_0002";
        when(processProjectionQuery.read(eq(caseId), any(), eq(false)))
                .thenReturn(Optional.empty());

        mockMvc.perform(
                        get("/api/disputes/{caseId}/evidence/process-projection", caseId)
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_STATUS_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("evidence process projection is not initialized"))
                .andExpect(jsonPath("$.details.case_id").value(caseId));

        verify(intakeProgressService).assertEvidenceReadAccess(eq(caseId), any());
        verify(processProjectionQuery).read(eq(caseId), any(), eq(false));
    }

    @Test
    void keepsAnInvalidProjectionViewAsBadRequest() throws Exception {
        String caseId = "CASE_P9_SYNTHETIC_0003";

        mockMvc.perform(
                        get("/api/disputes/{caseId}/evidence/process-projection", caseId)
                                .queryParam("view", "latest")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("request validation failed"));
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi()」。
    // 具体功能：「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi()」：复现“核对完整业务行为（场景方法「exposesTheRoleScopedCatalogOnTheFinalUnversionedApi」）”场景：驱动 「catalogService.catalog」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「EVIDENCE_1」、「LOGISTICS_PROOF」、「MERCHANT」。
    // 上游调用：「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceRoomControllerTest.exposesTheRoleScopedCatalogOnTheFinalUnversionedApi()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「EVIDENCE_1」、「LOGISTICS_PROOF」、「MERCHANT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "SUBMITTED",
                                                null,
                                                null))));

        mockMvc.perform(
                        get("/api/disputes/CASE_evidence/evidence")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].evidence_id").value("EVIDENCE_1"))
                .andExpect(jsonPath("$.data.items[0].redacted").value(true));

        verify(intakeProgressService)
                .assertEvidenceReadAccess(eq("CASE_evidence"), any());
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomControllerTest.completesEvidenceWithoutAcceptingAClientDossierVersion()」。
    // 具体功能：「EvidenceRoomControllerTest.completesEvidenceWithoutAcceptingAClientDossierVersion()」：复现“核对完整业务行为（场景方法「completesEvidenceWithoutAcceptingAClientDossierVersion」）”场景：驱动 「completionService.complete」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「complete-user-1」、「EVIDENCE」、「2026-07-03T03:00:00Z」。
    // 上游调用：「EvidenceRoomControllerTest.completesEvidenceWithoutAcceptingAClientDossierVersion()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceRoomControllerTest.completesEvidenceWithoutAcceptingAClientDossierVersion()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceRoomControllerTest.completesEvidenceWithoutAcceptingAClientDossierVersion()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「complete-user-1」、「EVIDENCE」、「2026-07-03T03:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

        verify(intakeProgressService).assertEvidenceAccess(eq("CASE_evidence"), any());
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceRoomControllerTest.returnsTheSharedCompletionProjection()」。
    // 具体功能：「EvidenceRoomControllerTest.returnsTheSharedCompletionProjection()」：复现“返回正确投影（场景方法「returnsTheSharedCompletionProjection」）”场景：驱动 「completionService.status」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「EVIDENCE」、「2026-07-03T03:00:00Z」、「user-local」。
    // 上游调用：「EvidenceRoomControllerTest.returnsTheSharedCompletionProjection()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceRoomControllerTest.returnsTheSharedCompletionProjection()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceRoomControllerTest.returnsTheSharedCompletionProjection()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「EVIDENCE」、「2026-07-03T03:00:00Z」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
