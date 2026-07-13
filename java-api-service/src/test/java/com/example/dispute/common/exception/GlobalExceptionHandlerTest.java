/*
 * 所属模块：后端公共边界。
 * 文件职责：验证Global处理器，覆盖 「mapsBusinessExceptionToUnifiedFailureResponse」、「hidesUnexpectedExceptionDetails」、「mapsSecurityExceptionToForbidden」、「mapsBeanValidationFailureToInvalidArgument」、「treatsDisconnectedSseClientsAsCompletedStreams」、「mapsAnUnknownApiRouteToResourceNotFound」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.trace.TraceIdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// 所属模块：【后端公共边界 / 自动化测试层】类型「GlobalExceptionHandlerTest」。
// 类型职责：集中验证Global处理器的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「mapsBusinessExceptionToUnifiedFailureResponse」、「hidesUnexpectedExceptionDetails」、「mapsSecurityExceptionToForbidden」、「mapsBeanValidationFailureToInvalidArgument」、「treatsDisconnectedSseClientsAsCompletedStreams」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private GlobalExceptionHandler handler;

    // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.setUp()」。
    // 具体功能：「GlobalExceptionHandlerTest.setUp()」：在每个测试场景运行前创建「Clock.fixed」、「Instant.parse」、「MockMvcBuilders.standaloneSetup」、「addFilters」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「GlobalExceptionHandlerTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「GlobalExceptionHandlerTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「GlobalExceptionHandlerTest.setUp()」守住「后端公共边界」的可执行规格，尤其防止 「2026-06-28T10:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC);
        handler = new GlobalExceptionHandler(clock);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new FailingController())
                        .setControllerAdvice(handler)
                        .addFilters(new TraceIdFilter())
                        .build();
    }

    // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.mapsBusinessExceptionToUnifiedFailureResponse()」。
    // 具体功能：「GlobalExceptionHandlerTest.mapsBusinessExceptionToUnifiedFailureResponse()」：复现“核对完整业务行为（场景方法「mapsBusinessExceptionToUnifiedFailureResponse」）”场景：驱动 「mockMvc.perform」、「status」、「jsonPath」、「andExpect」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「TRACE_TEST」、「REQ_TEST」、「$.success」、「$.code」。
    // 上游调用：「GlobalExceptionHandlerTest.mapsBusinessExceptionToUnifiedFailureResponse()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「GlobalExceptionHandlerTest.mapsBusinessExceptionToUnifiedFailureResponse()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「GlobalExceptionHandlerTest.mapsBusinessExceptionToUnifiedFailureResponse()」守住「后端公共边界」的可执行规格，尤其防止 「TRACE_TEST」、「REQ_TEST」、「$.success」、「$.code」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void mapsBusinessExceptionToUnifiedFailureResponse() throws Exception {
        mockMvc.perform(
                        get("/test/missing")
                                .header(TraceIdFilter.TRACE_HEADER, "TRACE_TEST")
                                .header(TraceIdFilter.REQUEST_HEADER, "REQ_TEST"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(TraceIdFilter.TRACE_HEADER, "TRACE_TEST"))
                .andExpect(header().string(TraceIdFilter.REQUEST_HEADER, "REQ_TEST"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("case not found"))
                .andExpect(jsonPath("$.details.case_id").value("CASE_404"))
                .andExpect(jsonPath("$.request_id").value("REQ_TEST"))
                .andExpect(jsonPath("$.trace_id").value("TRACE_TEST"))
                .andExpect(jsonPath("$.timestamp").value("2026-06-28T10:00:00Z"));
    }

    // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.hidesUnexpectedExceptionDetails()」。
    // 具体功能：「GlobalExceptionHandlerTest.hidesUnexpectedExceptionDetails()」：复现“核对完整业务行为（场景方法「hidesUnexpectedExceptionDetails」）”场景：驱动 「appender.start」、「logger.addAppender」、「mockMvc.perform」、「logger.detachAppender」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「$.code」、「INTERNAL_ERROR」、「$.message」、「$.details」。
    // 上游调用：「GlobalExceptionHandlerTest.hidesUnexpectedExceptionDetails()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「GlobalExceptionHandlerTest.hidesUnexpectedExceptionDetails()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「GlobalExceptionHandlerTest.hidesUnexpectedExceptionDetails()」守住「后端公共边界」的可执行规格，尤其防止 「$.code」、「INTERNAL_ERROR」、「$.message」、「$.details」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(get("/test/unexpected"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("internal server error"))
                    .andExpect(jsonPath("$.details").isEmpty())
                    .andExpect(
                            jsonPath("$.request_id")
                                    .value(org.hamcrest.Matchers.startsWith("REQ_")))
                    .andExpect(
                            jsonPath("$.trace_id")
                                    .value(org.hamcrest.Matchers.startsWith("TRACE_")));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .allSatisfy(
                        event -> {
                            assertThat(event.getFormattedMessage()).doesNotContain("password");
                            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.mapsSecurityExceptionToForbidden()」。
    // 具体功能：「GlobalExceptionHandlerTest.mapsSecurityExceptionToForbidden()」：复现“核对完整业务行为（场景方法「mapsSecurityExceptionToForbidden」）”场景：驱动 「mockMvc.perform」、「status」、「jsonPath」、「andExpect」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「$.code」、「FORBIDDEN」、「$.message」、「$.details」。
    // 上游调用：「GlobalExceptionHandlerTest.mapsSecurityExceptionToForbidden()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「GlobalExceptionHandlerTest.mapsSecurityExceptionToForbidden()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「GlobalExceptionHandlerTest.mapsSecurityExceptionToForbidden()」守住「后端公共边界」的可执行规格，尤其防止 「$.code」、「FORBIDDEN」、「$.message」、「$.details」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void mapsSecurityExceptionToForbidden() throws Exception {
        mockMvc.perform(get("/test/security"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("access denied"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.mapsBeanValidationFailureToInvalidArgument()」。
    // 具体功能：「GlobalExceptionHandlerTest.mapsBeanValidationFailureToInvalidArgument()」：复现“核对完整业务行为（场景方法「mapsBeanValidationFailureToInvalidArgument」）”场景：驱动 「mockMvc.perform」、「post」、「status」、「jsonPath」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「{\"order_id\":\"\"}」、「$.code」、「INVALID_ARGUMENT」、「$.details.fields.orderId」。
    // 上游调用：「GlobalExceptionHandlerTest.mapsBeanValidationFailureToInvalidArgument()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「GlobalExceptionHandlerTest.mapsBeanValidationFailureToInvalidArgument()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「GlobalExceptionHandlerTest.mapsBeanValidationFailureToInvalidArgument()」守住「后端公共边界」的可执行规格，尤其防止 「{\"order_id\":\"\"}」、「$.code」、「INVALID_ARGUMENT」、「$.details.fields.orderId」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void mapsBeanValidationFailureToInvalidArgument() throws Exception {
        mockMvc.perform(
                        post("/test/validate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"order_id\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.details.fields.orderId").value("must not be blank"));
    }

    // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.treatsDisconnectedSseClientsAsCompletedStreams()」。
    // 具体功能：「GlobalExceptionHandlerTest.treatsDisconnectedSseClientsAsCompletedStreams()」：复现“核对完整业务行为（场景方法「treatsDisconnectedSseClientsAsCompletedStreams」）”场景：驱动 「appender.start」、「logger.addAppender」、「mockMvc.perform」、「result.getResponse」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ERROR」。
    // 上游调用：「GlobalExceptionHandlerTest.treatsDisconnectedSseClientsAsCompletedStreams()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「GlobalExceptionHandlerTest.treatsDisconnectedSseClientsAsCompletedStreams()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「GlobalExceptionHandlerTest.treatsDisconnectedSseClientsAsCompletedStreams()」守住「后端公共边界」的可执行规格，尤其防止 「ERROR」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void treatsDisconnectedSseClientsAsCompletedStreams() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            var result =
                    mockMvc.perform(
                                    get("/test/sse-disconnected")
                                            .accept(MediaType.TEXT_EVENT_STREAM))
                            .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
            assertThat(appender.list)
                    .noneSatisfy(
                            event ->
                                    assertThat(event.getLevel().toString())
                                            .isEqualTo("ERROR"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.mapsAnUnknownApiRouteToResourceNotFound()」。
    // 具体功能：「GlobalExceptionHandlerTest.mapsAnUnknownApiRouteToResourceNotFound()」：复现“核对完整业务行为（场景方法「mapsAnUnknownApiRouteToResourceNotFound」）”场景：驱动 「handler.handleNoResourceFound」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「RESOURCE_NOT_FOUND」。
    // 上游调用：「GlobalExceptionHandlerTest.mapsAnUnknownApiRouteToResourceNotFound()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「GlobalExceptionHandlerTest.mapsAnUnknownApiRouteToResourceNotFound()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「GlobalExceptionHandlerTest.mapsAnUnknownApiRouteToResourceNotFound()」守住「后端公共边界」的可执行规格，尤其防止 「RESOURCE_NOT_FOUND」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void mapsAnUnknownApiRouteToResourceNotFound() {
        var response =
                handler.handleNoResourceFound(
                        new NoResourceFoundException(
                                HttpMethod.GET,
                                "/api/v1/cases"),
                        new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    // 所属模块：【后端公共边界 / 自动化测试层】类型「FailingController」。
    // 类型职责：把Failing能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「missing」、「unexpected」、「security」、「validate」、「sseDisconnected」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：公共组件不得暗含具体案件裁决规则
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @RestController
    static class FailingController {

        // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.FailingController.missing()」。
        // 具体功能：「GlobalExceptionHandlerTest.FailingController.missing()」：作为测试辅助方法为“核对完整业务行为（场景方法「missing」）”组装或读取「NotFoundException」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「GlobalExceptionHandlerTest.FailingController.missing()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「GlobalExceptionHandlerTest.FailingController.missing()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「GlobalExceptionHandlerTest.FailingController.missing()」守住「后端公共边界」的可执行规格，尤其防止 「case_id」、「CASE_404」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        @GetMapping("/test/missing")
        void missing() {
            throw new NotFoundException(
                    ErrorCode.CASE_NOT_FOUND,
                    "case not found",
                    Map.of("case_id", "CASE_404"));
        }

        // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.FailingController.unexpected()」。
        // 具体功能：「GlobalExceptionHandlerTest.FailingController.unexpected()」：作为测试辅助方法为“核对完整业务行为（场景方法「unexpected」）”组装或读取「IllegalStateException」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「GlobalExceptionHandlerTest.FailingController.unexpected()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「GlobalExceptionHandlerTest.FailingController.unexpected()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「GlobalExceptionHandlerTest.FailingController.unexpected()」守住「后端公共边界」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("database password should never leak");
        }

        // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.FailingController.security()」。
        // 具体功能：「GlobalExceptionHandlerTest.FailingController.security()」：作为测试辅助方法为“核对完整业务行为（场景方法「security」）”组装或读取「SecurityException」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「GlobalExceptionHandlerTest.FailingController.security()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「GlobalExceptionHandlerTest.FailingController.security()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「GlobalExceptionHandlerTest.FailingController.security()」守住「后端公共边界」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @GetMapping("/test/security")
        void security() {
            throw new SecurityException("intake confirmation requires a case party");
        }

        // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.FailingController.validate(ValidationRequest)」。
        // 具体功能：「GlobalExceptionHandlerTest.FailingController.validate(ValidationRequest)」：作为测试辅助方法为“核对完整业务行为（场景方法「validate」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
        // 上游调用：「GlobalExceptionHandlerTest.FailingController.validate(ValidationRequest)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「GlobalExceptionHandlerTest.FailingController.validate(ValidationRequest)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「GlobalExceptionHandlerTest.FailingController.validate(ValidationRequest)」守住「后端公共边界」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody ValidationRequest request) {}

        // 所属模块：【后端公共边界 / 自动化测试层】「GlobalExceptionHandlerTest.FailingController.sseDisconnected()」。
        // 具体功能：「GlobalExceptionHandlerTest.FailingController.sseDisconnected()」：作为测试辅助方法为“核对完整业务行为（场景方法「sseDisconnected」）”组装或读取「AsyncRequestNotUsableException」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「GlobalExceptionHandlerTest.FailingController.sseDisconnected()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「GlobalExceptionHandlerTest.FailingController.sseDisconnected()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「GlobalExceptionHandlerTest.FailingController.sseDisconnected()」守住「后端公共边界」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @GetMapping(value = "/test/sse-disconnected", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void sseDisconnected() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to write: Broken pipe");
        }
    }

    // 所属模块：【后端公共边界 / 自动化测试层】类型「ValidationRequest」。
    // 类型职责：定义Validation跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：公共组件不得暗含具体案件裁决规则
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    record ValidationRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("order_id")
                    @NotBlank
                    String orderId) {}
}
