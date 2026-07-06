package com.example.dispute.evidence.infrastructure;

import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.evidence.application.OcrTaskClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientOcrTaskClient implements OcrTaskClient {

    private final RestClient restClient;
    private final String callbackBaseUrl;

    public RestClientOcrTaskClient(
            @Qualifier("ocrRestClient") RestClient restClient,
            @Value("${app.ocr.callback-base-url:${JAVA_API_SERVICE_URL:http://java-api-service:8080}}")
                    String callbackBaseUrl) {
        this.restClient = restClient;
        this.callbackBaseUrl = normalizeBaseUrl(callbackBaseUrl);
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
                .body(taskBody(task))
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> taskBody(ParseTask task) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("evidence_id", task.evidenceId());
        body.put("case_id", task.caseId());
        body.put("bucket", task.bucket());
        body.put("object_key", task.objectKey());
        body.put("content_type", task.contentType());
        if (!callbackBaseUrl.isBlank()) {
            body.put(
                    "callback_url",
                    callbackBaseUrl
                            + "/internal/evidence/"
                            + task.evidenceId()
                            + "/parse-result");
        }
        return body;
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

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
