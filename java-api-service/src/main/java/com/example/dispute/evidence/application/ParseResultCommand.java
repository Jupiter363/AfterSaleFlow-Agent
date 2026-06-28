package com.example.dispute.evidence.application;

import java.util.Map;

public record ParseResultCommand(
        String status, String text, Map<String, Object> metadata, String errorCode) {

    public ParseResultCommand {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
