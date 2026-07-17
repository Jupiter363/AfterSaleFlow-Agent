package com.example.dispute.workflow.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;

public final class ContractJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private ContractJson() {}

    public static byte[] canonicalize(JsonNode value) {
        try {
            String json = MAPPER.writeValueAsString(value);
            return new JsonCanonicalizer(json).getEncodedUTF8();
        } catch (IOException exception) {
            throw new IllegalArgumentException("contract value is not valid JSON", exception);
        }
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
