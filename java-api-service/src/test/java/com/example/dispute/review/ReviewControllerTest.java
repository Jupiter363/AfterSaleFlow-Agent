/*
 * 所属模块：平台人工终审。
 * 文件职责：验证审核，覆盖 「reviewerCanListAndSubmitAuditedDecision」、「rejectsAnotherPlatformReviewerDecisionWithForbidden」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.*;
import com.example.dispute.review.api.ReviewController;
import com.example.dispute.review.application.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 所属模块：【平台人工终审 / 自动化测试层】类型「ReviewControllerTest」。
// 类型职责：集中验证审核的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「reviewerCanListAndSubmitAuditedDecision」、「rejectsAnotherPlatformReviewerDecisionWithForbidden」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@WebMvcTest(ReviewController.class)
@Import({CommonConfiguration.class,TraceIdFilter.class,HeaderAuthenticationFilter.class,SecurityConfiguration.class,
        SecurityFailureWriter.class,JsonAuthenticationEntryPoint.class,JsonAccessDeniedHandler.class,GlobalExceptionHandler.class})
class ReviewControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReviewApplicationService service;
    @MockitoBean ReviewCopilotStreamService copilotStreamService;
    // 所属模块：【平台人工终审 / 自动化测试层】「ReviewControllerTest.reviewerCanListAndSubmitAuditedDecision()」。
    // 具体功能：「ReviewControllerTest.reviewerCanListAndSubmitAuditedDecision()」：复现“核对完整业务行为（场景方法「reviewerCanListAndSubmitAuditedDecision」）”场景：驱动 「service.list」、「service.decide」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REVIEW_1」、「CASE_1」、「REMEDY_1」、「PACKET_1」。
    // 上游调用：「ReviewControllerTest.reviewerCanListAndSubmitAuditedDecision()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ReviewControllerTest.reviewerCanListAndSubmitAuditedDecision()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ReviewControllerTest.reviewerCanListAndSubmitAuditedDecision()」守住「平台人工终审」的可执行规格，尤其防止 「REVIEW_1」、「CASE_1」、「REMEDY_1」、「PACKET_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test void reviewerCanListAndSubmitAuditedDecision() throws Exception{
        when(service.list(any(),any())).thenReturn(List.of(new ReviewTaskView("REVIEW_1","CASE_1","REMEDY_1","PACKET_1","PENDING","URGENT","PLATFORM_REVIEWER",null,null,null)));
        when(service.decide(eq("REVIEW_1"),any(),any())).thenReturn(new ReviewDecisionView("APPROVAL_1","REVIEW_1","CASE_1","APPROVE","APPROVED","APPROVED_FOR_EXECUTION",true));
        mvc.perform(get("/api/reviews").header(HeaderAuthenticationFilter.USER_ID_HEADER,"reviewer-local").header(HeaderAuthenticationFilter.ROLE_HEADER,"PLATFORM_REVIEWER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].priority").value("URGENT"));
        mvc.perform(post("/api/reviews/REVIEW_1/decision").header(HeaderAuthenticationFilter.USER_ID_HEADER,"reviewer-local")
                        .header(HeaderAuthenticationFilter.ROLE_HEADER,"PLATFORM_REVIEWER").header("Idempotency-Key","decision-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\",\"reason\":\"verified\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.execution_allowed").value(true));
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「ReviewControllerTest.rejectsAnotherPlatformReviewerDecisionWithForbidden()」。
    // 具体功能：「ReviewControllerTest.rejectsAnotherPlatformReviewerDecisionWithForbidden()」：复现“拒绝非法输入或越权操作（场景方法「rejectsAnotherPlatformReviewerDecisionWithForbidden」）”场景：驱动 「service.decide」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REVIEW_2」、「reviewer-1」、「PLATFORM_REVIEWER」、「Idempotency-Key」。
    // 上游调用：「ReviewControllerTest.rejectsAnotherPlatformReviewerDecisionWithForbidden()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ReviewControllerTest.rejectsAnotherPlatformReviewerDecisionWithForbidden()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ReviewControllerTest.rejectsAnotherPlatformReviewerDecisionWithForbidden()」守住「平台人工终审」的可执行规格，尤其防止 「REVIEW_2」、「reviewer-1」、「PLATFORM_REVIEWER」、「Idempotency-Key」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsAnotherPlatformReviewerDecisionWithForbidden() throws Exception {
        when(service.decide(eq("REVIEW_2"), any(), any()))
                .thenThrow(new ForbiddenException("only the system platform reviewer can decide"));

        mvc.perform(
                        post("/api/reviews/REVIEW_2/decision")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "reviewer-1")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "PLATFORM_REVIEWER")
                                .header("Idempotency-Key", "decision-2")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"decision\":\"APPROVE\",\"reason\":\"verified\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        ArgumentCaptor<AuthenticatedActor> actor =
                ArgumentCaptor.forClass(AuthenticatedActor.class);
        verify(service).decide(eq("REVIEW_2"), any(), actor.capture());
        assertThat(actor.getValue())
                .isEqualTo(
                        new AuthenticatedActor(
                                "reviewer-1",
                                ActorRole.PLATFORM_REVIEWER));
    }
}
