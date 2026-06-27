package com.example.dispute.evidence.application;

import com.fasterxml.jackson.annotation.JsonProperty;

public interface OcrTaskClient {

    void createParseTask(ParseTask task);

    record ParseTask(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("case_id") String caseId,
            String bucket,
            @JsonProperty("object_key") String objectKey,
            @JsonProperty("content_type") String contentType) {}
}
