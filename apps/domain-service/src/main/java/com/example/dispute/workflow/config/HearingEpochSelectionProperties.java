package com.example.dispute.workflow.config;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Fail-closed engineering controls for new Hearing epoch selection. */
@ConfigurationProperties(prefix = "app.orchestration.hearing-epoch-selection")
public record HearingEpochSelectionProperties(
        @DefaultValue("LEGACY") WriterMode mode,
        @DefaultValue("0") int syntheticShadowCohortBasisPoints,
        String cohortPolicyVersion,
        @DefaultValue("false") boolean signedSyntheticShadowEnabled) {

    public static final int BASIS_POINTS = 10_000;

    public HearingEpochSelectionProperties {
        mode = mode == null ? WriterMode.LEGACY : mode;
        cohortPolicyVersion = normalize(cohortPolicyVersion);
        if (syntheticShadowCohortBasisPoints < 0
                || syntheticShadowCohortBasisPoints > BASIS_POINTS) {
            throw new IllegalArgumentException(
                    "syntheticShadowCohortBasisPoints must be between 0 and 10000");
        }
        if (mode == WriterMode.TEMPORAL) {
            throw new IllegalArgumentException(
                    "TEMPORAL Hearing epoch selection is forbidden under the current gate");
        }
        if (cohortPolicyVersion != null
                && !cohortPolicyVersion.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(
                    "cohortPolicyVersion must be a bounded version identifier");
        }
    }

    /** Partial configuration deliberately has no shadow admission path. */
    public boolean signedSyntheticSelectionConfigured() {
        return mode == WriterMode.SHADOW
                && signedSyntheticShadowEnabled
                && syntheticShadowCohortBasisPoints > 0
                && cohortPolicyVersion != null;
    }

    public void requireSignedSyntheticSelectionConfigured() {
        if (!signedSyntheticSelectionConfigured()) {
            throw new IllegalStateException(
                    "Hearing signed synthetic shadow requires mode, cohort, policy, and enablement locks");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
