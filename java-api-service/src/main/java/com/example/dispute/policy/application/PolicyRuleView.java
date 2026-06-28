package com.example.dispute.policy.application;

import java.time.OffsetDateTime;
import java.util.Map;

public record PolicyRuleView(
        String id,
        String ruleCode,
        int ruleVersion,
        String ruleName,
        String ruleScope,
        String ruleStatus,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        int priority,
        Map<String, Object> conditions,
        Map<String, Object> outcome,
        Map<String, Object> sourceDocument) {}
