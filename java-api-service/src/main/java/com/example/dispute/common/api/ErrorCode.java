package com.example.dispute.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    CASE_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    EVIDENCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    CASE_STATUS_INVALID(HttpStatus.CONFLICT),
    CASE_DUPLICATED(HttpStatus.CONFLICT),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    EVIDENCE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    EVIDENCE_PARSE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    AGENT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    AGENT_OUTPUT_SCHEMA_INVALID(HttpStatus.INTERNAL_SERVER_ERROR),
    WORKFLOW_START_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    WORKFLOW_SIGNAL_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    APPROVAL_REQUIRED(HttpStatus.CONFLICT),
    TOOL_EXECUTION_DENIED(HttpStatus.FORBIDDEN),
    TOOL_EXECUTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    EXTERNAL_SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    DATABASE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
