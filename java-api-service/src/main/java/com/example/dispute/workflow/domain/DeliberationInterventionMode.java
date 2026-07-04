package com.example.dispute.workflow.domain;

import java.util.Locale;

public enum DeliberationInterventionMode {
    DISABLED,
    FINAL_ONLY,
    EVERY_ROUND;

    public static DeliberationInterventionMode from(String value) {
        if (value == null || value.isBlank()) {
            return FINAL_ONLY;
        }
        return DeliberationInterventionMode.valueOf(
                value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
