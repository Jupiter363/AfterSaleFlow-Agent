package com.example.dispute.common.trace;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    @Test
    void propagatesValidTraceAndRequestIdsThroughHeadersAttributesAndMdc()
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_HEADER, "TRACE_CLIENT_001");
        request.addHeader(TraceIdFilter.REQUEST_HEADER, "REQ_CLIENT_001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceInChain = new AtomicReference<>();
        AtomicReference<String> requestInChain = new AtomicReference<>();

        new TraceIdFilter()
                .doFilter(
                        request,
                        response,
                        (servletRequest, servletResponse) -> {
                            traceInChain.set(MDC.get(TraceIdFilter.MDC_TRACE_KEY));
                            requestInChain.set(MDC.get(TraceIdFilter.MDC_REQUEST_KEY));
                            assertThat(
                                            servletRequest.getAttribute(
                                                    TraceIdFilter.TRACE_ATTRIBUTE))
                                    .isEqualTo("TRACE_CLIENT_001");
                            assertThat(
                                            servletRequest.getAttribute(
                                                    TraceIdFilter.REQUEST_ATTRIBUTE))
                                    .isEqualTo("REQ_CLIENT_001");
                        });

        assertThat(traceInChain).hasValue("TRACE_CLIENT_001");
        assertThat(requestInChain).hasValue("REQ_CLIENT_001");
        assertThat(response.getHeader(TraceIdFilter.TRACE_HEADER)).isEqualTo("TRACE_CLIENT_001");
        assertThat(response.getHeader(TraceIdFilter.REQUEST_HEADER)).isEqualTo("REQ_CLIENT_001");
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_KEY)).isNull();
        assertThat(MDC.get(TraceIdFilter.MDC_REQUEST_KEY)).isNull();
    }

    @Test
    void replacesMissingOrUnsafeCorrelationIds() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_HEADER, "unsafe trace with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdFilter().doFilter(request, response, (ignoredRequest, ignoredResponse) -> {});

        assertThat(response.getHeader(TraceIdFilter.TRACE_HEADER)).matches("TRACE_[0-9a-f]{32}");
        assertThat(response.getHeader(TraceIdFilter.REQUEST_HEADER)).matches("REQ_[0-9a-f]{32}");
    }
}
