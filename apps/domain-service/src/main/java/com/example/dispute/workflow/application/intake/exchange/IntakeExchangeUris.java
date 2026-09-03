package com.example.dispute.workflow.application.intake.exchange;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Canonical immutable URI policy shared by both sides of the Intake exchange. */
public final class IntakeExchangeUris {

    private IntakeExchangeUris() {}

    public static String requireCanonical(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) {
            throw invalid();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Intake object URI is invalid", failure);
        }
        String scheme = uri.getScheme();
        String path = uri.getPath();
        String rawPath = uri.getRawPath();
        if (scheme == null
                || !("s3".equals(scheme.toLowerCase(Locale.ROOT))
                        || "minio".equals(scheme.toLowerCase(Locale.ROOT)))
                || !scheme.equals(scheme.toLowerCase(Locale.ROOT))
                || uri.getRawAuthority() == null
                || uri.getRawAuthority().isBlank()
                || uri.getRawUserInfo() != null
                || uri.getPort() != -1
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || path == null
                || rawPath == null
                || !path.equals(rawPath)
                || !path.startsWith("/")
                || path.endsWith("/")
                || path.contains("\\")
                || path.contains("//")) {
            throw invalid();
        }
        String[] parts = path.substring(1).split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw invalid();
            }
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Intake object URI must be canonical s3/minio");
    }
}
