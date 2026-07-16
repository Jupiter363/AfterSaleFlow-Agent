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
    void answersCompatibilityEndpointAcceptsTheStatementSchema() {
        HearingAnswerBundleRequest compatibilityRequest =
                new HearingAnswerBundleRequest(
                        "hearing_party_statement.v1",
                        null,
                        List.of(),
                        List.of("MESSAGE_1"),
                        "ISSUE_SET_1",
                        "One free-form statement is mapped by the intake officer.");

        assertThat(validator.validate(compatibilityRequest)).isEmpty();
        assertThat(compatibilityRequest.toPartyStatement().issueSetId())
                .isEqualTo("ISSUE_SET_1");
    }

    @Test
    void deserializesBothIssueSetAndLegacyQuestionSetIdentifiers() throws Exception {
        ObjectMapper objectMapper =
                new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        HearingAnswerBundleRequest compatibility =
                objectMapper.readValue(
                        """
                        {
                          "schema_version": "hearing_party_statement.v1",
                          "issue_set_id": "ISSUE_SET_1",
                          "statement_text": "A free-form statement.",
                          "source_message_ids": []
                        }
                        """,
                        HearingAnswerBundleRequest.class);
        HearingPartyStatementRequest canonical =
                objectMapper.readValue(
                        """
                        {
                          "schema_version": "hearing_party_statement.v1",
                          "question_set_id": "LEGACY_QUESTION_SET_1",
                          "statement_text": "A statement against a persisted V1 question set.",
                          "source_message_ids": []
                        }
                        """,
                        HearingPartyStatementRequest.class);

        assertThat(validator.validate(compatibility)).isEmpty();
        assertThat(compatibility.issueSetId()).isEqualTo("ISSUE_SET_1");
        assertThat(validator.validate(canonical)).isEmpty();
        assertThat(canonical.issueSetId()).isEqualTo("LEGACY_QUESTION_SET_1");
    }
}
