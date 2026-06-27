package com.example.dispute.evidence.infrastructure;

import com.example.dispute.evidence.application.OcrTaskClient;
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
                .uri("/ocr-api/v1/parse-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(task)
                .retrieve()
                .toBodilessEntity();
    }
}
