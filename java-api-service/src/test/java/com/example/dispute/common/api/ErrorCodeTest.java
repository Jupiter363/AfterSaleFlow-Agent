package com.example.dispute.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

    @Test
    void requiredCodesHaveStableHttpStatusMappings() {
        Map<ErrorCode, HttpStatus> expected =
                Map.ofEntries(
                        Map.entry(ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST),
                        Map.entry(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
                        Map.entry(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
                        Map.entry(ErrorCode.CASE_NOT_FOUND, HttpStatus.NOT_FOUND),
                        Map.entry(ErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND),
                        Map.entry(ErrorCode.EVIDENCE_NOT_FOUND, HttpStatus.NOT_FOUND),
                        Map.entry(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND),
                        Map.entry(ErrorCode.CASE_STATUS_INVALID, HttpStatus.CONFLICT),
                        Map.entry(ErrorCode.CASE_DUPLICATED, HttpStatus.CONFLICT),
                        Map.entry(ErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT),
                        Map.entry(
                                ErrorCode.EVIDENCE_UPLOAD_FAILED,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(
                                ErrorCode.EVIDENCE_PARSE_FAILED,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(
                                ErrorCode.AGENT_SERVICE_UNAVAILABLE,
                                HttpStatus.SERVICE_UNAVAILABLE),
                        Map.entry(
                                ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(
                                ErrorCode.WORKFLOW_START_FAILED,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(
                                ErrorCode.WORKFLOW_SIGNAL_FAILED,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(ErrorCode.APPROVAL_REQUIRED, HttpStatus.CONFLICT),
                        Map.entry(ErrorCode.TOOL_EXECUTION_DENIED, HttpStatus.FORBIDDEN),
                        Map.entry(
                                ErrorCode.TOOL_EXECUTION_FAILED,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(
                                ErrorCode.EXTERNAL_SERVICE_TIMEOUT,
                                HttpStatus.GATEWAY_TIMEOUT),
                        Map.entry(ErrorCode.DATABASE_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(ErrorCode.values()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach(
                (errorCode, status) -> assertThat(errorCode.httpStatus()).isEqualTo(status));
    }
}
