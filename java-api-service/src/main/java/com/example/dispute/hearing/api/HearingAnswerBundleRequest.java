package com.example.dispute.hearing.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Party-authored answers for the active hearing question set. */
public record HearingAnswerBundleRequest(
        @NotBlank @Pattern(regexp = "(?:hearing_answer_bundle|hearing_party_statement)\\.v1")
                String schemaVersion,
        @Size(max = 128) String questionSetId,
        @Size(max = 5) List<@Valid Answer> answers,
        @Size(max = 100) List<@NotBlank @Size(max = 128) String> sourceMessageIds,
        @Size(max = 128) String issueSetId,
        @Size(max = 20_000) String statementText) {

    public HearingAnswerBundleRequest(
            String schemaVersion,
            String questionSetId,
            List<Answer> answers,
            List<String> sourceMessageIds) {
        this(schemaVersion, questionSetId, answers, sourceMessageIds, null, null);
    }

    public HearingAnswerBundleRequest {
        answers = answers == null ? List.of() : List.copyOf(answers);
        sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
    }

    @AssertTrue(message = "request fields do not match schema_version")
    public boolean isSchemaShapeValid() {
        if ("hearing_answer_bundle.v1".equals(schemaVersion)) {
            return nonBlank(questionSetId) && !nonBlank(issueSetId) && !nonBlank(statementText);
        }
        if ("hearing_party_statement.v1".equals(schemaVersion)) {
            return nonBlank(activeIssueSetId()) && nonBlank(statementText) && answers.isEmpty();
        }
        return false;
    }

    public boolean isPartyStatement() {
        return "hearing_party_statement.v1".equals(schemaVersion);
    }

    public HearingPartyStatementRequest toPartyStatement() {
        if (!isPartyStatement()) {
            throw new IllegalStateException("request is not a party statement");
        }
        return new HearingPartyStatementRequest(
                schemaVersion, activeIssueSetId(), statementText, sourceMessageIds);
    }

    private String activeIssueSetId() {
        return nonBlank(issueSetId) ? issueSetId : questionSetId;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    public record Answer(
            @NotBlank @Size(max = 128) String questionId,
            @NotBlank @Size(max = 20_000) String answerText,
            @Size(max = 50) List<@NotBlank @Size(max = 128) String> attachmentRefs) {

        public Answer {
            attachmentRefs =
                    attachmentRefs == null ? List.of() : List.copyOf(attachmentRefs);
        }
    }
}
