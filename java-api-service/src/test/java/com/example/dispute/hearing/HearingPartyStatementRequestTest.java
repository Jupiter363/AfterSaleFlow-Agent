package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.hearing.api.HearingAnswerBundleRequest;
import com.example.dispute.hearing.api.HearingPartyStatementRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

class HearingPartyStatementRequestTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validatesTheNaturalLanguageStatementContract() {
        HearingPartyStatementRequest valid =
                new HearingPartyStatementRequest(
                        "hearing_party_statement.v1",
                        "ISSUE_SET_1",
                        "This is my complete statement about the disputed issues.",
                        List.of("MESSAGE_1"));
        HearingPartyStatementRequest blank =
                new HearingPartyStatementRequest(
                        "hearing_party_statement.v1", "ISSUE_SET_1", " ", List.of());

        assertThat(validator.validate(valid)).isEmpty();
        assertThat(validator.validate(blank)).isNotEmpty();
    }

    @Test
    void answerEndpointAcceptsOnlyTheBoundV4BundleRequest() {
        HearingAnswerBundleRequest request =
                new HearingAnswerBundleRequest(
                        "hearing_answer_bundle.v4",
                        "HEARING_QUESTION_SET_1",
                        "a".repeat(64),
                        "b".repeat(64),
                        List.of(new HearingAnswerBundleRequest.Answer(
                                "HEARING_QUESTION_1",
                                "HEARING_ISSUE_1",
                                "本轮针对该争议点的完整回答。")),
                        List.of("MESSAGE_1"));

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.questionSetId()).isEqualTo("HEARING_QUESTION_SET_1");
    }

    @Test
    void deserializesTheExactV4QuestionAndIssueBindings() throws Exception {
        ObjectMapper objectMapper =
                new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        HearingAnswerBundleRequest request =
                objectMapper.readValue(
                        """
                        {
                          "schema_version": "hearing_answer_bundle.v4",
                          "question_set_id": "HEARING_QUESTION_SET_1",
                          "question_set_hash": "%s",
                          "formal_issue_catalog_hash": "%s",
                          "answers": [{
                            "question_id": "HEARING_QUESTION_1",
                            "issue_id": "HEARING_ISSUE_1",
                            "answer_text": "Current answer."
                          }],
                          "source_message_ids": ["MESSAGE_1"]
                        }
                        """.formatted("a".repeat(64), "b".repeat(64)),
                        HearingAnswerBundleRequest.class);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.answers()).singleElement().satisfies(answer -> {
            assertThat(answer.questionId()).isEqualTo("HEARING_QUESTION_1");
            assertThat(answer.issueId()).isEqualTo("HEARING_ISSUE_1");
        });
    }

    @Test
    void publishesTheCanonicalIssueSetPropertyWithoutGlobalNamingConfiguration()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        HearingPartyStatementRequest request =
                objectMapper.readValue(
                        """
                        {
                          "schemaVersion": "hearing_party_statement.v1",
                          "issue_set_id": "ISSUE_SET_1",
                          "statementText": "A free-form statement.",
                          "sourceMessageIds": []
                        }
                        """,
                        HearingPartyStatementRequest.class);

        assertThat(request.issueSetId()).isEqualTo("ISSUE_SET_1");
        assertThat(objectMapper.writeValueAsString(request))
                .contains("\"issue_set_id\":\"ISSUE_SET_1\"")
                .doesNotContain("\"issueSetId\"");
    }
}
