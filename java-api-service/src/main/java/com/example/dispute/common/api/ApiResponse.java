package com.example.dispute.common.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        Map<String, Object> details,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("trace_id") String traceId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {

    public static <T> ApiResponse<T> success(
            T data, String requestId, String traceId, Instant timestamp) {
        return new ApiResponse<>(
                true, "SUCCESS", "success", data, null, requestId, traceId, timestamp);
    }

    public static <T> ApiResponse<T> failure(
            ErrorCode errorCode,
            String message,
            Map<String, Object> details,
            String requestId,
            String traceId,
            Instant timestamp) {
        return new ApiResponse<>(
                false,
                errorCode.name(),
                message,
                null,
                Map.copyOf(details),
                requestId,
                traceId,
                timestamp);
    }
}
