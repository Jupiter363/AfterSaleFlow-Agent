package com.example.dispute.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String REQUEST_HEADER = "X-Request-Id";
    public static final String TRACE_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    public static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".requestId";
    public static final String MDC_TRACE_KEY = "trace_id";
    public static final String MDC_REQUEST_KEY = "request_id";

    private static final Pattern SAFE_CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = correlationId(request.getHeader(TRACE_HEADER), "TRACE_");
        String requestId = correlationId(request.getHeader(REQUEST_HEADER), "REQ_");

        request.setAttribute(TRACE_ATTRIBUTE, traceId);
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        response.setHeader(TRACE_HEADER, traceId);
        response.setHeader(REQUEST_HEADER, requestId);
        MDC.put(MDC_TRACE_KEY, traceId);
        MDC.put(MDC_REQUEST_KEY, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_KEY);
            MDC.remove(MDC_REQUEST_KEY);
        }
    }

    private static String correlationId(String candidate, String prefix) {
        if (candidate != null && SAFE_CORRELATION_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
