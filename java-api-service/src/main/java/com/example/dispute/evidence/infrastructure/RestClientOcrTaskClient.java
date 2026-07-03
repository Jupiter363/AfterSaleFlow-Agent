package com.example.dispute.evidence.infrastructure;

import com.example.dispute.evidence.application.OcrTaskClient;
import com.example.dispute.common.trace.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientOcrTaskClient implements OcrTaskClient {

    private final RestClient restClient;

    public RestClientOcrTaskClient(@Qualifier("ocrRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void createParseTask(ParseTask task) {
        restClient
                .post()
                .uri("/internal/evidence/parse-tasks")
                .headers(
                        headers -> {
                            setCorrelationHeader(
                                    headers,
                                    TraceIdFilter.TRACE_HEADER,
                                    TraceIdFilter.MDC_TRACE_KEY);
                            setCorrelationHeader(
                                    headers,
                                    TraceIdFilter.REQUEST_HEADER,
                                    TraceIdFilter.MDC_REQUEST_KEY);
                        })
                .contentType(MediaType.APPLICATION_JSON)
                .body(task)
                .retrieve()
                .toBodilessEntity();
    }

    private static void setCorrelationHeader(
            org.springframework.http.HttpHeaders headers,
            String headerName,
            String mdcKey) {
        String value = MDC.get(mdcKey);
        if (value != null && !value.isBlank()) {
            headers.set(headerName, value);
        }
    }
}
