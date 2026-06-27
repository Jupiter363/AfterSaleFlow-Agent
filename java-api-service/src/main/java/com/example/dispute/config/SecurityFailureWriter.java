package com.example.dispute.config;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.trace.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class SecurityFailureWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SecurityFailureWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode,
            String message)
            throws IOException {
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> body =
                ApiResponse.failure(
                        errorCode,
                        message,
                        Map.of(),
                        correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE, "REQ_"),
                        correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE, "TRACE_"),
                        Instant.now(clock));
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static String correlationId(
            HttpServletRequest request, String attribute, String prefix) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
