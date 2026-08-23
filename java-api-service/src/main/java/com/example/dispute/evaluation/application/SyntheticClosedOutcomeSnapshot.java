package com.example.dispute.evaluation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable non-case snapshot used only by the isolated engineering evaluation path. */
public record SyntheticClosedOutcomeSnapshot(
        String schemaVersion,
        String snapshotRef,
        String snapshotHash,
        String state,
        long epoch,
        long revision,
        long fence,
        Instant closedAt,
        Map<String, String> projection,
        boolean syntheticOnly,
        boolean containsRealCaseOrPartyData,
        boolean formalBusinessWriteCreated,
        boolean projectionOnly) {

    public SyntheticClosedOutcomeSnapshot {
        if (!"outcome-synthetic-closed-snapshot.v1".equals(schemaVersion)
                || snapshotRef == null
                || !snapshotRef.startsWith("synthetic/")
                || snapshotRef.contains("://")
                || snapshotHash == null
                || !snapshotHash.matches("[0-9a-f]{64}")
                || !"CLOSED".equals(state)
                || epoch < 0
                || revision < 0
                || fence < 1
                || !syntheticOnly
                || containsRealCaseOrPartyData
                || formalBusinessWriteCreated
                || !projectionOnly) {
            throw new IllegalArgumentException("invalid immutable synthetic CLOSED snapshot");
        }
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        projection = Map.copyOf(projection);
        projection.forEach(
                (key, value) -> {
                    if (key == null
                            || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
                            || value == null
                            || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                        throw new IllegalArgumentException("synthetic snapshot projection is invalid");
                    }
                });
        if (!snapshotHash.equals(
                hashOf(snapshotRef, state, epoch, revision, fence, closedAt, projection))) {
            throw new IllegalArgumentException("synthetic snapshot hash does not match content");
        }
    }

    public static SyntheticClosedOutcomeSnapshot create(
            String snapshotRef,
            long epoch,
            long revision,
            long fence,
            Instant closedAt,
            Map<String, String> projection) {
        String hash = hashOf(snapshotRef, "CLOSED", epoch, revision, fence, closedAt, projection);
        return new SyntheticClosedOutcomeSnapshot(
                "outcome-synthetic-closed-snapshot.v1",
                snapshotRef,
                hash,
                "CLOSED",
                epoch,
                revision,
                fence,
                closedAt,
                projection,
                true,
                false,
                false,
                true);
    }

    private static String hashOf(
            String snapshotRef,
            String state,
            long epoch,
            long revision,
            long fence,
            Instant closedAt,
            Map<String, String> projection) {
        StringBuilder preimage =
                new StringBuilder()
                        .append(snapshotRef)
                        .append('\n')
                        .append(state)
                        .append('\n')
                        .append(epoch)
                        .append('\n')
                        .append(revision)
                        .append('\n')
                        .append(fence)
                        .append('\n')
                        .append(closedAt)
                        .append('\n');
        new TreeMap<>(projection)
                .forEach(
                        (key, value) ->
                                preimage.append(key).append('=').append(value).append('\n'));
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(preimage.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
