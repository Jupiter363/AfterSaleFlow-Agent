package com.example.dispute.workflow.contract.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Pure deterministic identity contract shared by Workflow code and SDK adapters. */
public final class AgentRunWorkflowIds {

    private static final String PREFIX = "agent-run-v2:";
    private static final int MAX_LENGTH = 128;
    private static final Pattern IDENTIFIER =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    private AgentRunWorkflowIds() {}

    public static String forLogicalRun(String logicalRunId) {
        if (logicalRunId == null || logicalRunId.isBlank()) {
            throw new IllegalArgumentException("logicalRunId is required");
        }
        String readable = PREFIX + logicalRunId;
        if (readable.length() <= MAX_LENGTH && IDENTIFIER.matcher(readable).matches()) {
            return readable;
        }
        return PREFIX + sha256(logicalRunId);
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
