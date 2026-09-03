package com.example.dispute.evidence.domain;

import java.util.Locale;

/** Canonical binding vocabulary shared by formal Evidence projections and frozen dossiers. */
public final class FactEvidenceRelationCanonicalizer {

    private FactEvidenceRelationCanonicalizer() {}

    public static String canonicalize(String relation) {
        String normalized =
                relation == null ? "" : relation.strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CONTENT_SUPPORTS", "SUPPORTS", "SUPPORTS_CLAIM" -> "CONTENT_SUPPORTS";
            case "CONTENT_CONTRADICTS", "CONTRADICTS", "OPPOSES", "OPPOSES_CLAIM" ->
                    "CONTENT_CONTRADICTS";
            case "CONTEXT_ONLY", "CONTEXTUALIZES" -> "CONTEXT_ONLY";
            case "INCONCLUSIVE" -> "INCONCLUSIVE";
            default -> "INCONCLUSIVE";
        };
    }
}
