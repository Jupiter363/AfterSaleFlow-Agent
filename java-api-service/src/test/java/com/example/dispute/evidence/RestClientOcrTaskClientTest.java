package com.example.dispute.evidence;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dispute.evidence.application.OcrTaskClient;
import com.example.dispute.evidence.infrastructure.RestClientOcrTaskClient;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientOcrTaskClientTest {

    @Test
    void sendsStructuredParseTaskWithServiceCredential() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientOcrTaskClient client =
                new RestClientOcrTaskClient(
                        builder.baseUrl("http://ocr:8010")
                                .defaultHeader("X-Service-Secret", "ocr-secret")
                                .build());
        server.expect(requestTo("http://ocr:8010/internal/evidence/parse-tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Secret", "ocr-secret"))
                .andExpect(header("X-Trace-Id", "TRACE_ocr_test"))
                .andExpect(header("X-Request-Id", "REQ_ocr_test"))
                .andExpect(jsonPath("$.evidence_id").value("EVIDENCE_ocr"))
                .andExpect(jsonPath("$.object_key").value("case/evidence/proof.pdf"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        try {
            MDC.put("trace_id", "TRACE_ocr_test");
            MDC.put("request_id", "REQ_ocr_test");
            client.createParseTask(
                    new OcrTaskClient.ParseTask(
                            "EVIDENCE_ocr",
                            "CASE_ocr",
                            "evidence-original",
                            "case/evidence/proof.pdf",
                            "application/pdf"));
        } finally {
            MDC.clear();
        }

        server.verify();
    }
}
