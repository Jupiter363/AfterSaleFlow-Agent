package com.example.dispute.workflow.application.intake;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Exact object-store authority and prefix policy for immutable Intake proposal reads. */
public final class IntakeProposalUriAllowlist {

    private final List<Rule> rules;

    public IntakeProposalUriAllowlist(List<Rule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (this.rules.isEmpty()) {
            throw new IllegalArgumentException("at least one proposal URI rule is required");
        }
    }

    public void requireAllowed(IntakeProposalReference reference) {
        Objects.requireNonNull(reference, "reference");
        URI uri;
        try {
            uri = URI.create(reference.uri());
        } catch (IllegalArgumentException failure) {
            throw rejected("proposal URI is invalid", failure);
        }
        if (uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null
                || uri.getPort() != -1
                || rules.stream().noneMatch(rule -> rule.allows(uri))) {
            throw rejected("proposal URI authority or prefix is not allowed", null);
        }
    }

    public record Rule(String scheme, String authority, String prefix) {

        public Rule {
            scheme = bounded(scheme, 16, "scheme").toLowerCase(Locale.ROOT);
            prefix = bounded(prefix, 512, "prefix");
            if ("urn".equals(scheme)) {
                if (authority != null && !authority.isBlank()) {
                    throw new IllegalArgumentException("URN proposal rules cannot have an authority");
                }
                authority = null;
                if (!prefix.endsWith(":")) {
                    throw new IllegalArgumentException("URN proposal prefix must end with a colon");
                }
            } else if ("s3".equals(scheme) || "minio".equals(scheme)) {
                authority = bounded(authority, 255, "authority");
                if (!prefix.startsWith("/") || !prefix.endsWith("/")) {
                    throw new IllegalArgumentException(
                            "object-store proposal prefix must start and end with a slash");
                }
            } else {
                throw new IllegalArgumentException("proposal URI rule scheme is unsupported");
            }
        }

        private boolean allows(URI uri) {
            if (!scheme.equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            if ("urn".equals(scheme)) {
                String value = uri.getRawSchemeSpecificPart();
                return uri.getRawAuthority() == null
                        && value != null
                        && value.startsWith(prefix)
                        && !containsTraversal(value);
            }
            String path = uri.getRawPath();
            return authority.equals(uri.getRawAuthority())
                    && path != null
                    && path.startsWith(prefix)
                    && path.length() > prefix.length()
                    && !containsTraversal(path);
        }

        private static boolean containsTraversal(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            return lower.contains("/../")
                    || lower.endsWith("/..")
                    || lower.contains("%2e%2e")
                    || lower.contains("\\");
        }

        private static String bounded(String value, int maximum, String field) {
            if (value == null
                    || value.isBlank()
                    || value.length() > maximum
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(field + " must be bounded non-control text");
            }
            return value;
        }
    }

    private static IntakeFinalizationRejectedException rejected(
            String message, Throwable cause) {
        return cause == null
                ? new IntakeFinalizationRejectedException(
                        "INTAKE_PROPOSAL_URI_FORBIDDEN", message)
                : new IntakeFinalizationRejectedException(
                        "INTAKE_PROPOSAL_URI_FORBIDDEN", message, cause);
    }
}
