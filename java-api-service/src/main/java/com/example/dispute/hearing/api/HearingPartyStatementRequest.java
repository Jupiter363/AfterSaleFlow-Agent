package com.example.dispute.hearing.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** One free-form party statement for the active shared hearing issue set. */
public record HearingPartyStatementRequest(
        @NotBlank @Pattern(regexp = "hearing_party_statement\\.v1") String schemaVersion,
        @NotBlank @Size(max = 128) @JsonAlias("question_set_id") String issueSetId,
        @NotBlank @Size(max = 20_000) String statementText,
        @Size(max = 100) List<@NotBlank @Size(max = 128) String> sourceMessageIds) {

    public HearingPartyStatementRequest {
        sourceMessageIds =
                sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
    }
}
