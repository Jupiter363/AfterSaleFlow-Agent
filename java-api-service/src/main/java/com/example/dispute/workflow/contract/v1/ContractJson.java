package com.example.dispute.workflow.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import org.erdtman.jcs.JsonCanonicalizer;

public final class ContractJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final byte[] VALUE_ENVELOPE_PREFIX =
            "{\"value\":".getBytes(StandardCharsets.UTF_8);

    private ContractJson() {}

    public static byte[] canonicalize(JsonNode value) {
        try {
            // The JCS library accepts an object root, while RFC 8785 values used by
            // Frame projections may be strings, numbers, booleans, arrays, or null.
            // Canonicalize every value inside a one-member object and remove only
            // that deterministic envelope. Nested canonicalization is identical to
            // canonicalizing an object root, so existing contract hashes stay stable.
            var envelope = MAPPER.createObjectNode();
            envelope.set("value", Objects.requireNonNull(value, "value"));
            byte[] canonicalEnvelope = new JsonCanonicalizer(
                            MAPPER.writeValueAsString(envelope))
                    .getEncodedUTF8();
            int suffixIndex = canonicalEnvelope.length - 1;
            if (suffixIndex <= VALUE_ENVELOPE_PREFIX.length
                    || canonicalEnvelope[suffixIndex] != '}'
                    || !startsWith(canonicalEnvelope, VALUE_ENVELOPE_PREFIX)) {
                throw new IllegalArgumentException("contract value envelope is invalid");
            }
            return Arrays.copyOfRange(
                    canonicalEnvelope, VALUE_ENVELOPE_PREFIX.length, suffixIndex);
        } catch (IOException exception) {
            throw new IllegalArgumentException("contract value is not valid JSON", exception);
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    public static String sha256Hex(JsonNode value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalize(value));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String canonicalString(JsonNode value) {
        return new String(canonicalize(value), StandardCharsets.UTF_8);
    }
}
