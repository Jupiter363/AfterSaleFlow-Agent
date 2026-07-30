package com.example.dispute.casecore.application;

import com.example.dispute.room.application.IntakeLobbySeed;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Produces the immutable request binding used by external-import replay checks. */
public final class ImportDisputeRequestFingerprint {

    private ImportDisputeRequestFingerprint() {}

    public static String of(ImportDisputeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream canonical = new DataOutputStream(bytes)) {
                write(canonical, "import-dispute-v1");
                write(canonical, command.sourceSystem());
                write(canonical, command.externalCaseReference());
                write(canonical, command.orderReference());
                writeOptional(canonical, command.afterSalesReference());
                writeOptional(canonical, command.logisticsReference());
                write(canonical, command.userId());
                write(canonical, command.merchantId());
                write(canonical, command.initiatorRole());
                write(canonical, command.disputeType());
                write(canonical, command.title());
                write(canonical, command.description());
                write(canonical, command.riskLevel().name());
                writeOptional(canonical, command.requestedOutcomeHint());
                write(canonical, command.claimResolutionSeed());
                write(canonical, command.respondentAttitudeSeed());
            }
            return HexFormat.of().formatHex(sha256(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("failed to canonicalize import request", impossible);
        }
    }

    private static void write(
            DataOutputStream canonical,
            IntakeLobbySeed.ClaimResolutionSeed seed) throws IOException {
        if (seed == null) {
            canonical.writeBoolean(false);
            return;
        }
        canonical.writeBoolean(true);
        writeOptional(canonical, seed.initiatorRole());
        writeOptional(canonical, seed.requestedResolution());
        write(canonical, canonicalDecimal(seed.requestedAmount()));
        writeOptional(canonical, seed.requestedItems());
        writeOptional(canonical, seed.requestReason());
        writeOptional(canonical, seed.originalStatement());
    }

    private static void write(
            DataOutputStream canonical,
            IntakeLobbySeed.RespondentAttitudeSeed seed) throws IOException {
        if (seed == null) {
            canonical.writeBoolean(false);
            return;
        }
        canonical.writeBoolean(true);
        writeOptional(canonical, seed.respondentRole());
        writeOptional(canonical, seed.attitude());
        writeOptional(canonical, seed.position());
        writeOptional(canonical, seed.source());
        write(canonical, seed.confidence() == null ? null : Double.toString(seed.confidence()));
    }

    private static String canonicalDecimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static void writeOptional(DataOutputStream canonical, String value)
            throws IOException {
        write(canonical, value == null || value.isBlank() ? null : value);
    }

    private static void write(DataOutputStream canonical, String value) throws IOException {
        if (value == null) {
            canonical.writeInt(-1);
            return;
        }
        byte[] valueBytes = StrictUtf8.encode(value);
        canonical.writeInt(valueBytes.length);
        canonical.write(valueBytes);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
