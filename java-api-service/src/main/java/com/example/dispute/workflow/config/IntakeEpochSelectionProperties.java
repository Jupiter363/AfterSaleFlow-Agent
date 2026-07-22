package com.example.dispute.workflow.config;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Fail-closed controls for selecting new Intake epochs. */
@ConfigurationProperties(prefix = "app.orchestration.intake-epoch-selection")
public record IntakeEpochSelectionProperties(
        @DefaultValue("LEGACY") WriterMode mode,
        @DefaultValue("0") int shadowCohortBasisPoints,
        String cohortPolicyVersion,
        @DefaultValue("false") boolean signedSyntheticShadowEnabled) {

    public static final int BASIS_POINTS = 10_000;

    public IntakeEpochSelectionProperties {
        mode = mode == null ? WriterMode.LEGACY : mode;
        cohortPolicyVersion = normalize(cohortPolicyVersion);
        if (shadowCohortBasisPoints < 0 || shadowCohortBasisPoints > BASIS_POINTS) {
            throw new IllegalArgumentException(
                    "shadowCohortBasisPoints must be between 0 and 10000");
        }
        if (mode == WriterMode.TEMPORAL) {
            throw new IllegalArgumentException(
                    "TEMPORAL Intake epoch selection is forbidden under the current gate");
        }
        if (cohortPolicyVersion != null
                && !cohortPolicyVersion.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(
                    "cohortPolicyVersion must be a bounded version identifier");
        }
    }

    /** Partial configuration deliberately behaves as LEGACY instead of opening a shadow path. */
    public boolean shadowSelectionConfigured() {
        return mode == WriterMode.SHADOW
                && signedSyntheticShadowEnabled
                && shadowCohortBasisPoints > 0
                && cohortPolicyVersion != null;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
