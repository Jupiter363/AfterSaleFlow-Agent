/*
 * 所属模块：确定性补救规划。
 * 文件职责：验证补救，覆盖 「returnsApprovalGatedPlanDto」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；把已认定事实和非最终建议转换为退款、补发等结构化候选动作。
 * 关键边界：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
 */
package com.example.dispute.remedy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.remedy.api.RemedyController;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.remedy.application.RemedyPlanView;
import com.example.dispute.remedy.domain.PlannedRemedyAction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 所属模块：【确定性补救规划 / 自动化测试层】类型「RemedyControllerTest」。
// 类型职责：集中验证补救的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「returnsApprovalGatedPlanDto」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@WebMvcTest(RemedyController.class)
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
class RemedyControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RemedyApplicationService service;

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyControllerTest.returnsApprovalGatedPlanDto()」。
    // 具体功能：「RemedyControllerTest.returnsApprovalGatedPlanDto()」：复现“返回正确投影（场景方法「returnsApprovalGatedPlanDto」）”场景：驱动 「OffsetDateTime.parse」、「mockMvc.perform」、「when」、「eq」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_remedy」、「REMEDY_1」、「PENDING_APPROVAL」、「0.00」。
    // 上游调用：「RemedyControllerTest.returnsApprovalGatedPlanDto()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RemedyControllerTest.returnsApprovalGatedPlanDto()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RemedyControllerTest.returnsApprovalGatedPlanDto()」守住「确定性补救规划」的可执行规格，尤其防止 「CASE_remedy」、「REMEDY_1」、「PENDING_APPROVAL」、「0.00」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void returnsApprovalGatedPlanDto() throws Exception {
        when(service.get(eq("CASE_remedy"), any()))
                .thenReturn(
                        new RemedyPlanView(
                                "REMEDY_1",
                                "CASE_remedy",
                                null,
                                1,
                                RouteType.SIMPLE_HEARING,
                                "PENDING_APPROVAL",
                                RiskLevel.HIGH,
                                new BigDecimal("0.00"),
                                "CNY",
                                List.of(
                                        new PlannedRemedyAction(
                                                "REFUND",
                                                Map.of(),
                                                "REMEDY:CASE_remedy:1:0:REFUND",
                                                List.of(
                                                        "PLATFORM_REVIEW_APPROVED"),
                                                RiskLevel.HIGH,
                                                true)),
                                List.of("PLATFORM_REVIEW_APPROVED"),
                                List.of("NOTIFY_USER_AFTER_EXECUTION"),
                                true,
                                OffsetDateTime.parse(
                                        "2026-06-28T00:00:00Z")));

        mockMvc.perform(
                        get("/api/disputes/CASE_remedy/remedy-plan")
                                .header(
                                        HeaderAuthenticationFilter.USER_ID_HEADER,
                                        "user-remedy")
                                .header(
                                        HeaderAuthenticationFilter.ROLE_HEADER,
                                        "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan_status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.actions[0].action_type").value("REFUND"))
                .andExpect(jsonPath("$.data.actions[0].requires_approval").value(true))
                .andExpect(jsonPath("$.data.requires_human_review").value(true))
                .andExpect(jsonPath("$.data.actions_json").doesNotExist());
    }
}
