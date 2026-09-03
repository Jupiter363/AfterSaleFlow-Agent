package com.example.dispute.workflow.application.command;

import com.example.dispute.common.trace.W3cTraceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

final class TraceparentBridge {

    private static final Pattern TRACEPARENT =
            Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    private TraceparentBridge() {}

    static String resolve(String incoming, String traceId, String requestId) {
        var current = W3cTraceContext.currentTraceparent();
        if (current.isPresent()) {
            return current.orElseThrow();
        }
        if (incoming != null && !incoming.isBlank()) {
            var matcher = TRACEPARENT.matcher(incoming);
            if (!matcher.matches()
                    || isAllZero(matcher.group(1))
                    || isAllZero(matcher.group(2))) {
                throw new IllegalArgumentException("traceparent is invalid");
            }
            return incoming;
        }
        String trace = digest("trace:" + required(traceId, "traceId")).substring(0, 32);
        String span =
                digest(
                                "span:"
                                        + required(traceId, "traceId")
                                        + ":"
                                        + required(requestId, "requestId"))
                        .substring(0, 16);
        return "00-" + trace + "-" + span + "-01";
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static boolean isAllZero(String value) {
        return value.chars().allMatch(character -> character == '0');
    }
}
