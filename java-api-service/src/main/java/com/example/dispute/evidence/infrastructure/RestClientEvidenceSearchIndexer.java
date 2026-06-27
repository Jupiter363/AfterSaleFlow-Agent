package com.example.dispute.evidence.infrastructure;

import com.example.dispute.evidence.application.EvidenceSearchIndexer;
import com.example.dispute.evidence.application.EvidenceView;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientEvidenceSearchIndexer implements EvidenceSearchIndexer {

    private final RestClient restClient;

    public RestClientEvidenceSearchIndexer(
            @Qualifier("evidenceSearchRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void indexMetadata(EvidenceView evidence) {
        restClient
                .put()
                .uri("/evidence_index/_doc/{id}", evidence.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        Map.of(
                                "evidence_id", evidence.id(),
                                "case_id", evidence.caseId(),
                                "evidence_type", evidence.evidenceType(),
                                "source_type", evidence.sourceType(),
                                "file_hash", evidence.fileHash(),
                                "parse_status", evidence.parseStatus(),
                                "desensitized", evidence.desensitized()))
                .retrieve()
                .toBodilessEntity();
    }
}
