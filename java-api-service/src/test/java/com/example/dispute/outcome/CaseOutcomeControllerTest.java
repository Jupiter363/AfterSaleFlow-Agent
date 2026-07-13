/*
 * 所属模块：裁决结果查询。
 * 文件职责：验证案件结果，覆盖 「returnsTheHumanConfirmedDecisionAndExecutionReceipts」、「reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint」、「reviewerModifiesTheOutcomeDraftThroughCaseEndpoint」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；聚合人工终审、非最终草案、补救执行和案件时间线形成角色可见结果页。
 * 关键边界：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
 */
package com.example.dispute.outcome;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.outcome.api.CaseOutcomeController;
import com.example.dispute.outcome.application.AdjudicationDraftView;
import com.example.dispute.outcome.application.CaseOutcomeService;
import com.example.dispute.outcome.application.CaseOutcomeView;
import com.example.dispute.outcome.application.FinalDecisionView;
import com.example.dispute.review.application.ReviewDecisionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 所属模块：【裁决结果查询 / 自动化测试层】类型「CaseOutcomeControllerTest」。
// 类型职责：集中验证案件结果的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「returnsTheHumanConfirmedDecisionAndExecutionReceipts」、「reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint」、「reviewerModifiesTheOutcomeDraftThroughCaseEndpoint」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@WebMvcTest(CaseOutcomeController.class)
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
class CaseOutcomeControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CaseOutcomeService service;

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeControllerTest.returnsTheHumanConfirmedDecisionAndExecutionReceipts()」。
    // 具体功能：「CaseOutcomeControllerTest.returnsTheHumanConfirmedDecisionAndExecutionReceipts()」：复现“返回正确投影（场景方法「returnsTheHumanConfirmedDecisionAndExecutionReceipts」）”场景：驱动 「OffsetDateTime.parse」、「objectMapper.readTree」、「mockMvc.perform」、「when」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_outcome」、「签收未收到争议」、「2026-07-03T05:20:00Z」、「支持用户退款请求」。
    // 上游调用：「CaseOutcomeControllerTest.returnsTheHumanConfirmedDecisionAndExecutionReceipts()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseOutcomeControllerTest.returnsTheHumanConfirmedDecisionAndExecutionReceipts()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseOutcomeControllerTest.returnsTheHumanConfirmedDecisionAndExecutionReceipts()」守住「裁决结果查询」的可执行规格，尤其防止 「CASE_outcome」、「签收未收到争议」、「2026-07-03T05:20:00Z」、「支持用户退款请求」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void returnsTheHumanConfirmedDecisionAndExecutionReceipts() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        when(service.get(eq("CASE_outcome"), any()))
                .thenReturn(
                        new CaseOutcomeView(
                                "CASE_outcome",
                                "签收未收到争议",
                                CaseStatus.CLOSED,
                                OffsetDateTime.parse("2026-07-03T05:20:00Z"),
                                new FinalDecisionView(
                                        "支持用户退款请求",
                                        "商家举证不足。",
                                        "审核员确认证据链完整。",
                                        "HUMAN_REVIEW",
                                        true,
                                        objectMapper.readTree(
                                                "{\"actions\":[{\"type\":\"REFUND\"}]}")),
                                new AdjudicationDraftView(
                                        "DRAFT_1",
                                        2,
                                        "支持用户退款请求",
                                        new BigDecimal("0.9200"),
                                        "AI 法官形成非最终裁决草案。",
                                        "READY",
                                        objectMapper.readTree(
                                                "[{\"fact\":\"物流记录显示已签收\"}]"),
                                        objectMapper.readTree(
                                                "[{\"assessment\":\"商家证据不足以证明用户本人签收\"}]"),
                                        objectMapper.readTree(
                                                "[{\"rule\":\"签收争议举证责任\"}]"),
                                        objectMapper.readTree("[\"核验签收人身份\"]"),
                                        objectMapper.readTree(
                                                "{\"id\":\"PLAN_1\",\"actions\":[{\"action_type\":\"REFUND\",\"amount\":199}]}")),
                                "REVIEW_1",
                                "APPROVED",
                                List.of()));

        mockMvc.perform(
                        get("/api/disputes/CASE_outcome/outcome")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "user-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.final_decision.conclusion")
                                .value("支持用户退款请求"))
                .andExpect(
                        jsonPath("$.data.final_decision.human_confirmed")
                                .value(true))
                .andExpect(jsonPath("$.data.adjudication_draft.id").value("DRAFT_1"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.fact_findings[0].fact")
                                .value("物流记录显示已签收"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.evidence_assessment[0].assessment")
                                .value("商家证据不足以证明用户本人签收"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.policy_application[0].rule")
                                .value("签收争议举证责任"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.reviewer_attention[0]")
                                .value("核验签收人身份"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.approved_plan.id")
                                .value("PLAN_1"))
                .andExpect(
                        jsonPath("$.data.adjudication_draft.approved_plan.actions[0].action_type")
                                .value("REFUND"))
                .andExpect(jsonPath("$.data.actions").isArray());
    }

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeControllerTest.reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint()」。
    // 具体功能：「CaseOutcomeControllerTest.reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint()」：复现“核对完整业务行为（场景方法「reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint」）”场景：驱动 「service.confirmDraft」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_outcome」、「outcome-confirm-1」、「APPROVAL_1」、「REVIEW_1」。
    // 上游调用：「CaseOutcomeControllerTest.reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseOutcomeControllerTest.reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseOutcomeControllerTest.reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint()」守住「裁决结果查询」的可执行规格，尤其防止 「CASE_outcome」、「outcome-confirm-1」、「APPROVAL_1」、「REVIEW_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint() throws Exception {
        when(service.confirmDraft(
                        eq("CASE_outcome"),
                        eq("confirmed by reviewer"),
                        eq("outcome-confirm-1"),
                        any()))
                .thenReturn(
                        new ReviewDecisionView(
                                "APPROVAL_1",
                                "REVIEW_1",
                                "CASE_outcome",
                                "APPROVE",
                                "APPROVED",
                                "APPROVED_FOR_EXECUTION",
                                true));

        mockMvc.perform(
                        post("/api/disputes/CASE_outcome/outcome/review/confirm")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "reviewer-local")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER")
                                .header("Idempotency-Key", "outcome-confirm-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"confirmed by reviewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").value("REVIEW_1"))
                .andExpect(jsonPath("$.data.decision").value("APPROVE"))
                .andExpect(jsonPath("$.data.execution_allowed").value(true));
    }

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeControllerTest.reviewerModifiesTheOutcomeDraftThroughCaseEndpoint()」。
    // 具体功能：「CaseOutcomeControllerTest.reviewerModifiesTheOutcomeDraftThroughCaseEndpoint()」：复现“核对完整业务行为（场景方法「reviewerModifiesTheOutcomeDraftThroughCaseEndpoint」）”场景：驱动 「service.modifyDraft」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_outcome」、「outcome-modify-1」、「APPROVAL_2」、「REVIEW_2」。
    // 上游调用：「CaseOutcomeControllerTest.reviewerModifiesTheOutcomeDraftThroughCaseEndpoint()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseOutcomeControllerTest.reviewerModifiesTheOutcomeDraftThroughCaseEndpoint()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseOutcomeControllerTest.reviewerModifiesTheOutcomeDraftThroughCaseEndpoint()」守住「裁决结果查询」的可执行规格，尤其防止 「CASE_outcome」、「outcome-modify-1」、「APPROVAL_2」、「REVIEW_2」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reviewerModifiesTheOutcomeDraftThroughCaseEndpoint() throws Exception {
        when(service.modifyDraft(
                        eq("CASE_outcome"),
                        eq("refund amount adjusted"),
                        any(),
                        eq("outcome-modify-1"),
                        any()))
                .thenReturn(
                        new ReviewDecisionView(
                                "APPROVAL_2",
                                "REVIEW_2",
                                "CASE_outcome",
                                "MODIFY_AND_APPROVE",
                                "APPROVED",
                                "APPROVED_FOR_EXECUTION",
                                true));

        mockMvc.perform(
                        post("/api/disputes/CASE_outcome/outcome/review/modify")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "reviewer-local")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER")
                                .header("Idempotency-Key", "outcome-modify-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "refund amount adjusted",
                                          "approved_plan": {
                                            "id": "PLAN_2",
                                            "actions": [
                                              {
                                                "action_type": "REFUND",
                                                "amount": 199
                                              }
                                            ]
                                          }
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").value("REVIEW_2"))
                .andExpect(jsonPath("$.data.decision").value("MODIFY_AND_APPROVE"))
                .andExpect(jsonPath("$.data.execution_allowed").value(true));
    }
}
