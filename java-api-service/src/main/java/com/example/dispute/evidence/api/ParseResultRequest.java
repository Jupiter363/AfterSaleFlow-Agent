package com.example.dispute.evidence.api;

import com.example.dispute.evidence.application.ParseResultCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record ParseResultRequest(
        @NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String status,
        @Size(max = 2_000_000) String text,
        Map<String, Object> metadata,
        @Size(max = 64) String errorCode) {

    ParseResultCommand toCommand() {
        return new ParseResultCommand(status, text, metadata, errorCode);
    }
}
