package com.example.dispute.review.domain;

public record ReviewPacketVersions(
        long caseVersion,
        int dossierVersion,
        int issueVersion,
        int adjudicationDraftVersion,
        int deliberationReportVersion,
        int remedyPlanVersion,
        String rulesetVersion,
        String promptVersion,
        String skillVersion,
        String profileVersion) {

    public ReviewPacketVersions {
        if (caseVersion < 1
                || dossierVersion < 1
                || issueVersion < 1
                || adjudicationDraftVersion < 1
                || deliberationReportVersion < 0
                || remedyPlanVersion < 1) {
            throw new IllegalArgumentException(
                    "review packet versions must identify frozen artifacts");
        }
        requireText(rulesetVersion, "rulesetVersion");
        requireText(promptVersion, "promptVersion");
        requireText(skillVersion, "skillVersion");
        requireText(profileVersion, "profileVersion");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
