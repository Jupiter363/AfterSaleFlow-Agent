package com.example.dispute.tool;

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
import com.example.dispute.tool.api.InternalToolCatalogController;
import com.example.dispute.tool.application.ToolDefinition;
import com.example.dispute.tool.application.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalToolCatalogController.class)
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
class InternalToolCatalogControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ToolRegistry toolRegistry;

    @Test
    void systemActorCanReadExecutionToolCatalog() throws Exception {
        when(toolRegistry.definitions())
                .thenReturn(
                        List.of(
                                new ToolDefinition(
                                        "REFUND",
                                        "after_sale_tool",
                                        "refund",
                                        "模拟退款",
                                        "仅在平台审核通过后模拟退款动作，不直接调用真实支付下游。",
                                        RiskLevel.HIGH,
                                        true,
                                        true)));

        mvc.perform(
                        get("/internal/tools/execution")
                                .header(
                                        HeaderAuthenticationFilter.SERVICE_IDENTITY_HEADER,
                                        "python-agent-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action_type").value("REFUND"))
                .andExpect(jsonPath("$.data[0].tool_name").value("after_sale_tool"))
                .andExpect(jsonPath("$.data[0].operation").value("refund"))
                .andExpect(jsonPath("$.data[0].display_name").value("模拟退款"))
                .andExpect(jsonPath("$.data[0].risk_level").value("HIGH"))
                .andExpect(jsonPath("$.data[0].simulated").value(true))
                .andExpect(jsonPath("$.data[0].requires_approved_plan").value(true));
    }

    @Test
    void nonSystemActorsCannotReadExecutionToolCatalog() throws Exception {
        mvc.perform(
                        get("/internal/tools/execution")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "admin-1")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "ADMIN"))
                .andExpect(status().isForbidden());
    }
}
