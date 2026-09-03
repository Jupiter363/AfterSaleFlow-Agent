package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;

/** Protocol-aware exclusive replay cursor used by HTTP, SSE, and PostgreSQL catch-up. */
public record AgentRunStreamCursor(
        AgentRunProtocol protocol, String attemptId, long sequence) {

    private static final String V2_PREFIX = "v2:";
    private static final String V3_PREFIX = "v3:";
    private static final String V4_PREFIX = "v4:";

    public AgentRunStreamCursor {
        if (protocol == null || sequence < -1) {
            throw new IllegalArgumentException("stream cursor protocol or sequence is invalid");
        }
        if (protocol == AgentRunProtocol.V1 && attemptId != null) {
            throw new IllegalArgumentException("V1 cursor cannot contain an attemptId");
        }
        if ((protocol == AgentRunProtocol.V2
                        || protocol == AgentRunProtocol.V3
                        || protocol == AgentRunProtocol.V4)
                && sequence >= 0
                && (attemptId == null || attemptId.isBlank())) {
            throw new IllegalArgumentException("attempt cursor sequence requires an attemptId");
        }
    }

    public static AgentRunStreamCursor initial(AgentRunProtocol protocol) {
        return new AgentRunStreamCursor(protocol, null, -1);
    }

    public static AgentRunStreamCursor parse(String raw, AgentRunProtocol protocol) {
        if (raw == null || raw.isBlank() || "-1".equals(raw)) {
            return initial(protocol);
        }
        if (protocol == AgentRunProtocol.V1) {
            return new AgentRunStreamCursor(protocol, null, parseSequence(raw));
        }
        String prefix = prefix(protocol);
        if (!raw.startsWith(prefix)) {
            throw new IllegalArgumentException("attempt cursor must bind an attemptId");
        }
        int separator = raw.lastIndexOf(':');
        if (separator <= prefix.length() || separator == raw.length() - 1) {
            throw new IllegalArgumentException("attempt cursor is malformed");
        }
        String attemptId = raw.substring(prefix.length(), separator);
        if (!attemptId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("attempt cursor attemptId is invalid");
        }
        return new AgentRunStreamCursor(
                protocol, attemptId, parseSequence(raw.substring(separator + 1)));
    }

    public String wireValue() {
        return protocol == AgentRunProtocol.V1
                ? Long.toString(sequence)
                : attemptId == null
                        ? "-1"
                        : prefix(protocol) + attemptId + ':' + sequence;
    }

    private static String prefix(AgentRunProtocol protocol) {
        return switch (protocol) {
            case V2 -> V2_PREFIX;
            case V3 -> V3_PREFIX;
            case V4 -> V4_PREFIX;
            case V1 -> throw new IllegalArgumentException("V1 cursor has no attempt prefix");
        };
    }

    private static long parseSequence(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < -1) {
                throw new IllegalArgumentException("stream cursor sequence must be at least -1");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("stream cursor sequence is invalid", exception);
        }
    }
}
