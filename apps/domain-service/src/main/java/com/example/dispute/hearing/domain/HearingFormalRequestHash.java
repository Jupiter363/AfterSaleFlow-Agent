package com.example.dispute.hearing.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Stable length-prefixed request hash for a formal Hearing mutation. */
public final class HearingFormalRequestHash {

    private HearingFormalRequestHash() {}

    public static String compute(
            String commandType,
            HearingAuthorityExpectation authority,
            Object... semanticComponents) {
        Objects.requireNonNull(authority, "authority");
        MessageDigest digest = sha256Digest();
        append(digest, commandType);
        append(digest, authority.tenantSurrogate());
        append(digest, authority.caseId());
        append(digest, authority.flowInstanceId());
        append(digest, authority.epochId());
        append(digest, authority.roomEpoch());
        append(digest, authority.writerMode());
        append(digest, authority.stage());
        append(digest, authority.stageSequence());
        append(digest, authority.processRevision());
        append(digest, authority.roomRevision());
        append(digest, authority.fencingToken());
        for (Object component : semanticComponents) {
            append(digest, component);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void require(
            HearingAuthorityCommit commit,
            String commandType,
            Object... semanticComponents) {
        String expected = compute(commandType, commit.authority(), semanticComponents);
        if (!expected.equals(commit.requestHash())) {
            throw new IllegalArgumentException(
                    "requestHash does not bind the complete formal Hearing command");
        }
    }

    /** Exact v2 handoff key: the durable Hearing epoch and Judge V2 artifact share authority. */
    public static String handoffOperationKey(
            String tenantSurrogate,
            String caseId,
            String epochId,
            long roomEpoch,
            String judgeV2Id,
            String judgeV2Hash) {
        String exactTenant = keyComponent(tenantSurrogate, "tenantSurrogate");
        String exactCase = keyComponent(caseId, "caseId");
        String exactEpoch = keyComponent(epochId, "epochId");
        String exactJudgeV2 = keyComponent(judgeV2Id, "judgeV2Id");
        if (roomEpoch < 0) {
            throw new IllegalArgumentException("roomEpoch must be non-negative");
        }
        if (judgeV2Hash == null || !judgeV2Hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("judgeV2Hash must be lowercase SHA-256");
        }
        MessageDigest parentDigest = sha256Digest();
        parentDigest.update((exactEpoch + ':' + exactJudgeV2).getBytes(StandardCharsets.UTF_8));
        String exactParent = HexFormat.of().formatHex(parentDigest.digest());
        return "hearing.handoff:" + exactTenant + ':' + exactCase + ':' + roomEpoch + ':'
                + exactParent + ':' + judgeV2Hash;
    }

    private static void append(MessageDigest digest, Object value) {
        if (value instanceof HearingFormalTransition transition) {
            append(digest, "hearing-formal-transition.v1");
            append(digest, transition.sourceStageId());
            append(digest, transition.resultStage());
            append(digest, transition.resultStageSequence());
            append(digest, transition.sharedDeadlineAt());
            append(digest, transition.targetStageId());
            append(digest, transition.targetInputJson());
            append(digest, transition.sourceOutputJson());
            append(digest, transition.actorId());
            return;
        }
        String text = switch (value) {
            case null -> "<null>";
            case Enum<?> enumeration -> enumeration.name();
            case Instant instant -> instant.toString();
            default -> value.toString();
        };
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String keyComponent(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(
                    field + " must be a bounded operation-key component");
        }
        return value;
    }
}
