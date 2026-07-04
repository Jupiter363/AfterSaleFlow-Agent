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

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private GlobalExceptionHandler handler;

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

    @Test
    void mapsSecurityExceptionToForbidden() throws Exception {
        mockMvc.perform(get("/test/security"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("access denied"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

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

    @RestController
    static class FailingController {

        @GetMapping("/test/missing")
        void missing() {
            throw new NotFoundException(
                    ErrorCode.CASE_NOT_FOUND,
                    "case not found",
                    Map.of("case_id", "CASE_404"));
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("database password should never leak");
        }

        @GetMapping("/test/security")
        void security() {
            throw new SecurityException("intake confirmation requires a case party");
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody ValidationRequest request) {}

        @GetMapping(value = "/test/sse-disconnected", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void sseDisconnected() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to write: Broken pipe");
        }
    }

    record ValidationRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("order_id")
                    @NotBlank
                    String orderId) {}
}
