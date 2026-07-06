package com.example.dispute.hearing.application;

import java.util.List;

public record HearingCourtAgentResult(
        String speakerRole,
        String messageText,
        String roundSummary,
        List<String> questionsForUser,
        List<String> questionsForMerchant,
        String courtEventType,
        int roundNo,
        Integer nextRoundNo,
        boolean finalDraftRequired,
        String promptVersion,
        String model) {

    public HearingCourtAgentResult {
        speakerRole = blankToDefault(speakerRole, "JUDGE");
        messageText = blankToDefault(messageText, "本轮庭审材料已封存。");
        roundSummary = blankToDefault(roundSummary, "");
        questionsForUser = questionsForUser == null ? List.of() : List.copyOf(questionsForUser);
        questionsForMerchant =
                questionsForMerchant == null ? List.of() : List.copyOf(questionsForMerchant);
        courtEventType =
                blankToDefault(
                        courtEventType,
                        finalDraftRequired
                                ? "FINAL_DRAFT_REQUIRED"
                                : "JUDGE_NEXT_QUESTIONS_READY");
        promptVersion = blankToDefault(promptVersion, "hearing-round-turn-unknown");
        model = blankToDefault(model, "unknown");
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
