package com.example.dispute.hearing.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Party-authored answers for the active hearing question set. */
public record HearingAnswerBundleRequest(
        @NotBlank @Pattern(regexp = "hearing_answer_bundle\\.v4") String schemaVersion,
        @NotBlank @Size(max = 128) String questionSetId,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String questionSetHash,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String formalIssueCatalogHash,
        @Size(min = 1, max = 5) List<@Valid Answer> answers,
        @Size(max = 100) List<@NotBlank @Size(max = 128) String> sourceMessageIds) {

    public HearingAnswerBundleRequest {
        answers = answers == null ? List.of() : List.copyOf(answers);
        sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
    }

    public record Answer(
            @NotBlank @Size(max = 128) String questionId,
            @NotBlank @Size(max = 128) String issueId,
            @NotBlank @Size(max = 2_000) String answerText) {}
}
