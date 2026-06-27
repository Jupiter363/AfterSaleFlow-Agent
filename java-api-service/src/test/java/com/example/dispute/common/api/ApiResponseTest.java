package com.example.dispute.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void successCarriesCorrelationIdentifiersAndPayload() {
        ApiResponse<Map<String, String>> response =
                ApiResponse.success(
                        Map.of("case_id", "CASE_001"),
                        "REQ_001",
                        "TRACE_001",
                        Instant.parse("2026-06-28T10:00:00Z"));

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data()).containsEntry("case_id", "CASE_001");
        assertThat(response.requestId()).isEqualTo("REQ_001");
        assertThat(response.traceId()).isEqualTo("TRACE_001");
        assertThat(response.timestamp()).isEqualTo(Instant.parse("2026-06-28T10:00:00Z"));
    }

    @Test
    void failureCarriesStableErrorCodeAndDetails() {
        ApiResponse<Void> response =
                ApiResponse.failure(
                        ErrorCode.CASE_NOT_FOUND,
                        "case not found",
                        Map.of("case_id", "CASE_404"),
                        "REQ_404",
                        "TRACE_404",
                        Instant.parse("2026-06-28T10:00:00Z"));

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("CASE_NOT_FOUND");
        assertThat(response.message()).isEqualTo("case not found");
        assertThat(response.data()).isNull();
        assertThat(response.details()).containsEntry("case_id", "CASE_404");
    }
}
