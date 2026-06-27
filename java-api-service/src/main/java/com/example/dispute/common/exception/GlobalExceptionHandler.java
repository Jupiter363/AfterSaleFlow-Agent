package com.example.dispute.common.exception;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.trace.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception, HttpServletRequest request) {
        ErrorCode errorCode = exception.errorCode();
        ApiResponse<Void> body =
                failure(
                        errorCode,
                        exception.getMessage(),
                        exception.details(),
                        request);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception
                .getBindingResult()
                .getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ErrorCode errorCode = ErrorCode.INVALID_ARGUMENT;
        ApiResponse<Void> body =
                failure(
                        errorCode,
                        "request validation failed",
                        Map.of("fields", fields),
                        request);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception, HttpServletRequest request) {
        LOGGER.error(
                "Unhandled request failure: exception_type={}",
                exception.getClass().getName());
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        ApiResponse<Void> body =
                failure(errorCode, "internal server error", Map.of(), request);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    private ApiResponse<Void> failure(
            ErrorCode errorCode,
            String message,
            Map<String, Object> details,
            HttpServletRequest request) {
        return ApiResponse.failure(
                errorCode,
                message,
                details,
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE, "REQ_"),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE, "TRACE_"),
                Instant.now(clock));
    }

    private static String correlationId(
            HttpServletRequest request, String attributeName, String prefix) {
        Object value = request.getAttribute(attributeName);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
