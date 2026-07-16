/*
 * 所属模块：案件核心与导入。
 * 文件职责：验证争议，覆盖 「createsDisputeThroughTheUnversionedFinalApi」、「readsAndListsDisputesThroughFinalPaths」、「importsExternalDisputesThroughTheInternalServiceBoundary」、「rejectsExternalImportsForNonDemoPartyIds」、「simulatesExternalImportThroughTheInternalServiceBoundary」、「rejectsSimulatedImportCountsAboveOne」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.casecore.api.DisputeController;
import com.example.dispute.casecore.api.DisputeImportSimulationController;
import com.example.dispute.casecore.api.InternalDisputeImportController;
import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ImportedDisputeView;
import com.example.dispute.casecore.application.SimulatedImportResultView;
import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.caseintake.application.CaseApplicationService;
import com.example.dispute.caseintake.application.CasePageView;
import com.example.dispute.caseintake.application.CaseView;
import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.room.api.IntakeRoomController;
import com.example.dispute.room.application.IntakeConfirmationView;
import com.example.dispute.room.application.IntakeProgressService;
import com.example.dispute.room.application.IntakeRoomService;
import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 所属模块：【案件核心与导入 / 自动化测试层】类型「DisputeControllerTest」。
// 类型职责：集中验证争议的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「createsDisputeThroughTheUnversionedFinalApi」、「readsAndListsDisputesThroughFinalPaths」、「importsExternalDisputesThroughTheInternalServiceBoundary」、「rejectsExternalImportsForNonDemoPartyIds」、「simulatesExternalImportThroughTheInternalServiceBoundary」、「rejectsSimulatedImportCountsAboveOne」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@WebMvcTest({
    DisputeController.class,
    DisputeImportSimulationController.class,
    InternalDisputeImportController.class,
    IntakeRoomController.class
})
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
class DisputeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private CaseApplicationService service;
    @MockitoBean private DisputeImportService importService;
    @MockitoBean private IntakeRoomService intakeRoomService;
    @MockitoBean private IntakeProgressService intakeProgressService;

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.createsDisputeThroughTheUnversionedFinalApi()」。
    // 具体功能：「DisputeControllerTest.createsDisputeThroughTheUnversionedFinalApi()」：复现“创建并持久化（场景方法「createsDisputeThroughTheUnversionedFinalApi」）”场景：驱动 「service.create」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「idem-dispute-1」、「user-1」、「USER」、「Idempotency-Key」。
    // 上游调用：「DisputeControllerTest.createsDisputeThroughTheUnversionedFinalApi()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.createsDisputeThroughTheUnversionedFinalApi()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeControllerTest.createsDisputeThroughTheUnversionedFinalApi()」守住「案件核心与导入」的可执行规格，尤其防止 「idem-dispute-1」、「user-1」、「USER」、「Idempotency-Key」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void createsDisputeThroughTheUnversionedFinalApi() throws Exception {
        when(service.create(any(), any(), eq("idem-dispute-1"), any(), any()))
                .thenReturn(disputeView());

        mockMvc.perform(
                        post("/api/disputes")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "idem-dispute-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                java.util.Map.of(
                                                        "initiator_role",
                                                        "USER",
                                                        "order_reference",
                                                        "order-1",
                                                        "user_id",
                                                        "user-1",
                                                        "merchant_id",
                                                        "merchant-1",
                                                        "description",
                                                        "物流显示签收，但本人没有收到货。",
                                                        "attachment_ids",
                                                        List.of()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("CASE_test"))
                .andExpect(jsonPath("$.data.initiator_id").value("user-1"))
                .andExpect(jsonPath("$.data.initiator_role").value("USER"))
                .andExpect(jsonPath("$.data.respondent_id").value("merchant-1"))
                .andExpect(jsonPath("$.data.respondent_role").value("MERCHANT"))
                .andExpect(jsonPath("$.data.party_position").value("INITIATOR"))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andExpect(jsonPath("$.trace_id").isNotEmpty());
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.readsAndListsDisputesThroughFinalPaths()」。
    // 具体功能：「DisputeControllerTest.readsAndListsDisputesThroughFinalPaths()」：复现“核对完整业务行为（场景方法「readsAndListsDisputesThroughFinalPaths」）”场景：驱动 「service.list」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_test」、「user-1」、「USER」、「$.data.id」。
    // 上游调用：「DisputeControllerTest.readsAndListsDisputesThroughFinalPaths()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.readsAndListsDisputesThroughFinalPaths()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeControllerTest.readsAndListsDisputesThroughFinalPaths()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_test」、「user-1」、「USER」、「$.data.id」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void readsAndListsDisputesThroughFinalPaths() throws Exception {
        when(service.get(eq("CASE_test"), any())).thenReturn(disputeView());
        when(service.list(eq(null), eq(null), eq(0), eq(20), any()))
                .thenReturn(new CasePageView(List.of(disputeView()), 0, 20, 1, 1));

        mockMvc.perform(
                        get("/api/disputes/CASE_test")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("CASE_test"));

        mockMvc.perform(
                        get("/api/disputes")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("CASE_test"))
                .andExpect(jsonPath("$.data.items[0].source_type").value("INTAKE_CREATED"))
                .andExpect(jsonPath("$.data.items[0].current_room").value("INTAKE"))
                .andExpect(jsonPath("$.data.items[0].deadline_at").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].pending_action").value("COMPLETE_INTAKE"));
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.importsExternalDisputesThroughTheInternalServiceBoundary()」。
    // 具体功能：「DisputeControllerTest.importsExternalDisputesThroughTheInternalServiceBoundary()」：复现“核对完整业务行为（场景方法「importsExternalDisputesThroughTheInternalServiceBoundary」）”场景：驱动 「importService.importDispute」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「import-ext-1001」、「CASE_imported」、「ORDER-1001」、「AFTER-1001」。
    // 上游调用：「DisputeControllerTest.importsExternalDisputesThroughTheInternalServiceBoundary()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.importsExternalDisputesThroughTheInternalServiceBoundary()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeControllerTest.importsExternalDisputesThroughTheInternalServiceBoundary()」守住「案件核心与导入」的可执行规格，尤其防止 「import-ext-1001」、「CASE_imported」、「ORDER-1001」、「AFTER-1001」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void importsExternalDisputesThroughTheInternalServiceBoundary() throws Exception {
        when(importService.importDispute(any(), any(), eq("import-ext-1001"), any(), any()))
                .thenReturn(
                        new ImportedDisputeView(
                                "CASE_imported",
                                "ORDER-1001",
                                "AFTER-1001",
                                "LOG-1001",
                                "user-local",
                                "merchant-local",
                                "SIGNED_NOT_RECEIVED",
                                "EXTERNAL_IMPORT",
                                "OMS",
                                "EXT-1001",
                                RiskLevel.HIGH,
                                "签收未收到",
                                "用户表示未收到已签收包裹",
                                CaseStatus.INTAKE_PENDING,
                                "INTAKE",
                                null,
                                "COMPLETE_INTAKE",
                                "USER"));
        mockMvc.perform(
                        post("/internal/disputes/import")
                                .header("X-Service-Identity", "external-dispute-adapter")
                                .header("Idempotency-Key", "import-ext-1001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "source_system": "OMS",
                                          "external_case_reference": "EXT-1001",
                                          "order_reference": "ORDER-1001",
                                          "after_sales_reference": "AFTER-1001",
                                          "logistics_reference": "LOG-1001",
                                          "user_id": "user-local",
                                          "merchant_id": "merchant-local",
                                          "initiator_role": "USER",
                                          "dispute_type": "SIGNED_NOT_RECEIVED",
                                          "title": "签收未收到",
                                          "description": "用户表示未收到已签收包裹",
                                          "risk_level": "HIGH"
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.source_type").value("EXTERNAL_IMPORT"))
                .andExpect(jsonPath("$.data.external_case_reference").value("EXT-1001"))
                .andExpect(jsonPath("$.data.initiator_role").value("USER"));
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.rejectsExternalImportsForNonDemoPartyIds()」。
    // 具体功能：「DisputeControllerTest.rejectsExternalImportsForNonDemoPartyIds()」：复现“拒绝非法输入或越权操作（场景方法「rejectsExternalImportsForNonDemoPartyIds」）”场景：驱动 「mockMvc.perform」、「post」、「status」、「jsonPath」，再用 「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「X-Service-Identity」、「external-dispute-adapter」、「Idempotency-Key」、「import-wrong-party」。
    // 上游调用：「DisputeControllerTest.rejectsExternalImportsForNonDemoPartyIds()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.rejectsExternalImportsForNonDemoPartyIds()」的下游是被测服务、仓储或外部客户端替身；「verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeControllerTest.rejectsExternalImportsForNonDemoPartyIds()」守住「案件核心与导入」的可执行规格，尤其防止 「X-Service-Identity」、「external-dispute-adapter」、「Idempotency-Key」、「import-wrong-party」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsExternalImportsForNonDemoPartyIds() throws Exception {
        mockMvc.perform(
                        post("/internal/disputes/import")
                                .header("X-Service-Identity", "external-dispute-adapter")
                                .header("Idempotency-Key", "import-wrong-party")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "source_system": "OMS",
                                          "external_case_reference": "EXT-WRONG-PARTY",
                                          "order_reference": "ORDER-WRONG-PARTY",
                                          "user_id": "user-1",
                                          "merchant_id": "merchant-local",
                                          "initiator_role": "USER",
                                          "dispute_type": "SIGNED_NOT_RECEIVED",
                                          "title": "Imported dispute",
                                          "description": "Imported dispute description",
                                          "risk_level": "MEDIUM"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.details.reason")
                                .value("userId must be user-local"));
        verifyNoInteractions(importService);
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.simulatesExternalImportThroughTheInternalServiceBoundary()」。
    // 具体功能：「DisputeControllerTest.simulatesExternalImportThroughTheInternalServiceBoundary()」：复现“核对完整业务行为（场景方法「simulatesExternalImportThroughTheInternalServiceBoundary」）”场景：驱动 「importService.simulateExternalImport」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「simulate-import-001」、「CASE_simulated」、「ORDER-20260706-4201」、「AS-20260706-4201」。
    // 上游调用：「DisputeControllerTest.simulatesExternalImportThroughTheInternalServiceBoundary()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.simulatesExternalImportThroughTheInternalServiceBoundary()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeControllerTest.simulatesExternalImportThroughTheInternalServiceBoundary()」守住「案件核心与导入」的可执行规格，尤其防止 「simulate-import-001」、「CASE_simulated」、「ORDER-20260706-4201」、「AS-20260706-4201」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void simulatesExternalImportThroughTheInternalServiceBoundary() throws Exception {
        when(importService.simulateExternalImport(
                        any(),
                        any(),
                        eq("simulate-import-001"),
                        any(),
                        any()))
                .thenReturn(
                        new SimulatedImportResultView(
                                List.of(
                                        new ImportedDisputeView(
                                                "CASE_simulated",
                                                "ORDER-20260706-4201",
                                                "AS-20260706-4201",
                                                "SF-20260706-4201",
                                                "user-local",
                                                "merchant-local",
                                                "QUALITY_DISPUTE",
                                                "EXTERNAL_IMPORT",
                                                "LLM_SIMULATED_OMS",
                                                "EXT-20260706-001",
                                                RiskLevel.MEDIUM,
                                                "商家发起手表故障争议",
                                                "商家认为用户提交的故障视频与售后检测结果不一致，需要平台受理。",
                                                CaseStatus.INTAKE_PENDING,
                                                "INTAKE",
                                                null,
                                                "COMPLETE_INTAKE",
                                                "MERCHANT"))));

        mockMvc.perform(
                        post("/internal/disputes/import/simulate")
                                .header("X-Service-Identity", "external-dispute-adapter")
                                .header("Idempotency-Key", "simulate-import-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "count": 1,
                                          "scenario": "手表售后争议",
                                          "risk_level_hint": "MEDIUM",
                                          "initiator_role_hint": "MERCHANT",
                                          "current_actor_id": "merchant-local",
                                          "counterparty_actor_id": "user-local"
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[0].source_type").value("EXTERNAL_IMPORT"))
                .andExpect(jsonPath("$.data.items[0].source_system").value("LLM_SIMULATED_OMS"))
                .andExpect(jsonPath("$.data.items[0].initiator_role").value("MERCHANT"));
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.rejectsSimulatedImportCountsAboveOne()」。
    // 具体功能：「DisputeControllerTest.rejectsSimulatedImportCountsAboveOne()」：复现“拒绝非法输入或越权操作（场景方法「rejectsSimulatedImportCountsAboveOne」）”场景：驱动 「mockMvc.perform」、「post」、「status」、「andExpect」，再用 「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「X-Service-Identity」、「external-dispute-adapter」、「Idempotency-Key」、「simulate-import-too-many」。
    // 上游调用：「DisputeControllerTest.rejectsSimulatedImportCountsAboveOne()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.rejectsSimulatedImportCountsAboveOne()」的下游是被测服务、仓储或外部客户端替身；「verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeControllerTest.rejectsSimulatedImportCountsAboveOne()」守住「案件核心与导入」的可执行规格，尤其防止 「X-Service-Identity」、「external-dispute-adapter」、「Idempotency-Key」、「simulate-import-too-many」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsSimulatedImportCountsAboveOne() throws Exception {
        mockMvc.perform(
                        post("/internal/disputes/import/simulate")
                                .header("X-Service-Identity", "external-dispute-adapter")
                                .header("Idempotency-Key", "simulate-import-too-many")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "count": 2,
                                          "scenario": "watch dispute",
                                          "risk_level_hint": "MEDIUM",
                                          "initiator_role_hint": "USER",
                                          "current_actor_id": "user-local",
                                          "counterparty_actor_id": "merchant-local"
                                        }
                                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(importService);
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.simulatesExternalImportFromThePublicDemoExperience()」。
    // 具体功能：「DisputeControllerTest.simulatesExternalImportFromThePublicDemoExperience()」：复现“核对完整业务行为（场景方法「simulatesExternalImportFromThePublicDemoExperience」）”场景：驱动 「importService.simulateExternalImport」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「simulate-import-public-001」、「CASE_public_simulated」、「ORDER-20260706-4202」、「AS-20260706-4202」。
    // 上游调用：「DisputeControllerTest.simulatesExternalImportFromThePublicDemoExperience()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.simulatesExternalImportFromThePublicDemoExperience()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeControllerTest.simulatesExternalImportFromThePublicDemoExperience()」守住「案件核心与导入」的可执行规格，尤其防止 「simulate-import-public-001」、「CASE_public_simulated」、「ORDER-20260706-4202」、「AS-20260706-4202」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void simulatesExternalImportFromThePublicDemoExperience() throws Exception {
        when(importService.simulateExternalImport(
                        any(),
                        any(),
                        eq("simulate-import-public-001"),
                        any(),
                        any()))
                .thenReturn(
                        new SimulatedImportResultView(
                                List.of(
                                        new ImportedDisputeView(
                                                "CASE_public_simulated",
                                                "ORDER-20260706-4202",
                                                "AS-20260706-4202",
                                                "SF-20260706-4202",
                                                "user-local",
                                                "merchant-local",
                                                "QUALITY_DISPUTE",
                                                "EXTERNAL_IMPORT",
                                                "LLM_SIMULATED_OMS",
                                                "EXT-PUBLIC-20260706-001",
                                                RiskLevel.MEDIUM,
                                                "高价值手表售后责任争议",
                                                "商家认为检测照片、维修记录和使用说明存在冲突，需要平台先核实争议。",
                                                CaseStatus.INTAKE_PENDING,
                                                "INTAKE",
                                                null,
                                                "COMPLETE_INTAKE",
                                                "MERCHANT"))));

        mockMvc.perform(
                        post("/api/disputes/import/simulate")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT")
                                .header("Idempotency-Key", "simulate-import-public-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "count": 1,
                                          "scenario": "手表售后争议",
                                          "risk_level_hint": "MEDIUM",
                                          "initiator_role_hint": "MERCHANT",
                                          "current_actor_id": "merchant-local",
                                          "counterparty_actor_id": "user-local"
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[0].id").value("CASE_public_simulated"))
                .andExpect(jsonPath("$.data.items[0].source_type").value("EXTERNAL_IMPORT"))
                .andExpect(jsonPath("$.data.items[0].initiator_role").value("MERCHANT"));
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.publicSimulationRejectsNonDemoCounterpartyIds()」。
    // 具体功能：「DisputeControllerTest.publicSimulationRejectsNonDemoCounterpartyIds()」：复现“核对完整业务行为（场景方法「publicSimulationRejectsNonDemoCounterpartyIds」）”场景：驱动 「mockMvc.perform」、「post」、「status」、「jsonPath」，再用 「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「USER」、「Idempotency-Key」、「simulate-wrong-counterparty」。
    // 上游调用：「DisputeControllerTest.publicSimulationRejectsNonDemoCounterpartyIds()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.publicSimulationRejectsNonDemoCounterpartyIds()」的下游是被测服务、仓储或外部客户端替身；「verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeControllerTest.publicSimulationRejectsNonDemoCounterpartyIds()」守住「案件核心与导入」的可执行规格，尤其防止 「user-local」、「USER」、「Idempotency-Key」、「simulate-wrong-counterparty」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void publicSimulationRejectsNonDemoCounterpartyIds() throws Exception {
        mockMvc.perform(
                        post("/api/disputes/import/simulate")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .header("Idempotency-Key", "simulate-wrong-counterparty")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "count": 1,
                                          "scenario": "watch dispute",
                                          "risk_level_hint": "MEDIUM",
                                          "initiator_role_hint": "USER",
                                          "current_actor_id": "user-local",
                                          "counterparty_actor_id": "merchant-1"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.details.reason")
                                .value("counterpartyActorId must be merchant-local"));
        verifyNoInteractions(importService);
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.rejectsPublicExternalImportWhenTheActorDoesNotMatchTheRequestedInitiator()」。
    // 具体功能：「DisputeControllerTest.rejectsPublicExternalImportWhenTheActorDoesNotMatchTheRequestedInitiator()」：复现“拒绝非法输入或越权操作（场景方法「rejectsPublicExternalImportWhenTheActorDoesNotMatchTheRequestedInitiator」）”场景：驱动 「mockMvc.perform」、「post」、「status」、「andExpect」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「merchant-local」、「MERCHANT」、「Idempotency-Key」、「simulate-import-public-forbidden」。
    // 上游调用：「DisputeControllerTest.rejectsPublicExternalImportWhenTheActorDoesNotMatchTheRequestedInitiator()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.rejectsPublicExternalImportWhenTheActorDoesNotMatchTheRequestedInitiator()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeControllerTest.rejectsPublicExternalImportWhenTheActorDoesNotMatchTheRequestedInitiator()」守住「案件核心与导入」的可执行规格，尤其防止 「merchant-local」、「MERCHANT」、「Idempotency-Key」、「simulate-import-public-forbidden」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsPublicExternalImportWhenTheActorDoesNotMatchTheRequestedInitiator() throws Exception {
        mockMvc.perform(
                        post("/api/disputes/import/simulate")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT")
                                .header("Idempotency-Key", "simulate-import-public-forbidden")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "count": 1,
                                          "scenario": "手表售后争议",
                                          "risk_level_hint": "MEDIUM",
                                          "initiator_role_hint": "USER",
                                          "current_actor_id": "user-local",
                                          "counterparty_actor_id": "merchant-local"
                                        }
                                        """))
                .andExpect(status().isForbidden());
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.confirmsTheIntakeDecisionThroughTheRoomBasedApi()」。
    // 具体功能：「DisputeControllerTest.confirmsTheIntakeDecisionThroughTheRoomBasedApi()」：复现“核对完整业务行为（场景方法「confirmsTheIntakeDecisionThroughTheRoomBasedApi」）”场景：驱动 「intakeRoomService.confirm」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_test」、「2026-07-03T02:00:00Z」、「user-1」、「USER」。
    // 上游调用：「DisputeControllerTest.confirmsTheIntakeDecisionThroughTheRoomBasedApi()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.confirmsTheIntakeDecisionThroughTheRoomBasedApi()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeControllerTest.confirmsTheIntakeDecisionThroughTheRoomBasedApi()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_test」、「2026-07-03T02:00:00Z」、「user-1」、「USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void confirmsTheIntakeDecisionThroughTheRoomBasedApi() throws Exception {
        when(intakeRoomService.confirm(eq("CASE_test"), any(), any()))
                .thenReturn(
                        new IntakeConfirmationView(
                                "CASE_test",
                                CaseStatus.EVIDENCE_OPEN,
                                RoomType.EVIDENCE,
                                OffsetDateTime.parse("2026-07-03T02:00:00Z")));
        mockMvc.perform(
                        post("/api/disputes/CASE_test/intake/confirm")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "admissible": true,
                                          "dispute_type": "SIGNED_NOT_RECEIVED",
                                          "risk_level": "HIGH",
                                          "confirmation_note": "确认信息无误，同意发起争议审理"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case_status").value("EVIDENCE_OPEN"))
                .andExpect(jsonPath("$.data.current_room").value("EVIDENCE"))
                .andExpect(jsonPath("$.data.deadline_at").isNotEmpty());
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.cancelsTheIntakeWhenTheIssueIsResolvedBeforeAdmission()」。
    // 具体功能：「DisputeControllerTest.cancelsTheIntakeWhenTheIssueIsResolvedBeforeAdmission()」：复现“核对完整业务行为（场景方法「cancelsTheIntakeWhenTheIssueIsResolvedBeforeAdmission」）”场景：驱动 「intakeRoomService.cancel」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_test」、「user-1」、「USER」、「$.data.case_status」。
    // 上游调用：「DisputeControllerTest.cancelsTheIntakeWhenTheIssueIsResolvedBeforeAdmission()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeControllerTest.cancelsTheIntakeWhenTheIssueIsResolvedBeforeAdmission()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeControllerTest.cancelsTheIntakeWhenTheIssueIsResolvedBeforeAdmission()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_test」、「user-1」、「USER」、「$.data.case_status」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void cancelsTheIntakeWhenTheIssueIsResolvedBeforeAdmission() throws Exception {
        when(intakeRoomService.cancel(eq("CASE_test"), any(), eq("resolved before admission")))
                .thenReturn(
                        new IntakeConfirmationView(
                                "CASE_test",
                                CaseStatus.CANCELLED,
                                null,
                                null));
        mockMvc.perform(
                        post("/api/disputes/CASE_test/intake/cancel")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "resolved before admission"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case_status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.current_room").doesNotExist())
                .andExpect(jsonPath("$.data.deadline_at").doesNotExist());
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeControllerTest.disputeView()」。
    // 具体功能：「DisputeControllerTest.disputeView()」：作为测试辅助方法为“核对完整业务行为（场景方法「disputeView」）”组装或读取「CaseView」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「DisputeControllerTest.disputeView()」由本测试类中的 「DisputeControllerTest.createsDisputeThroughTheUnversionedFinalApi」、「DisputeControllerTest.readsAndListsDisputesThroughFinalPaths」 调用。
    // 下游影响：「DisputeControllerTest.disputeView()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeControllerTest.disputeView()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_test」、「order-1」、「user-1」、「merchant-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseView disputeView() {
        return new CaseView(
                "CASE_test",
                "order-1",
                null,
                null,
                "user-1",
                "merchant-1",
                "DISPUTE",
                "NON_RECEIPT",
                CaseStatus.INTAKE_COMPLETED,
                null,
                RiskLevel.HIGH,
                "签收未收到争端",
                "物流显示签收，但本人没有收到货。",
                true,
                List.of(),
                false,
                CaseSourceType.INTAKE_CREATED,
                null,
                null,
                "INTAKE",
                OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                "COMPLETE_INTAKE",
                OffsetDateTime.parse("2026-07-02T00:00:00Z"),
                OffsetDateTime.parse("2026-07-02T00:00:00Z"),
                null,
                com.example.dispute.config.ActorRole.USER,
                "user-1",
                com.example.dispute.config.ActorRole.MERCHANT,
                "merchant-1",
                com.example.dispute.casecore.domain.CasePartyPosition.INITIATOR);
    }
}
