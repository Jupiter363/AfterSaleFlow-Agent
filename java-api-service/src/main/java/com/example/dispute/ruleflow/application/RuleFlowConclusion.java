package com.example.dispute.ruleflow.application;

import java.util.List;

public record RuleFlowConclusion(
        String conclusionCode, String summary, List<String> recommendedActions) {}
