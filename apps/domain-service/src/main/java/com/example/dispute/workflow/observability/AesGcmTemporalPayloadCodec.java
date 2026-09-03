package com.example.dispute.workflow.observability;

import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AppProperties.Temporal.PayloadProtection.Mode;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.common.v1.Payload;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmTemporalPayloadCodec implements PayloadCodec {

    static final String ENCODING = "binary/encrypted";
    static final String CIPHER = "AES-256-GCM";
    static final String FORMAT_VERSION = "temporal-payload.v1";

    private static final String METADATA_ENCODING = "encoding";
    private static final String METADATA_CIPHER = "encryption-cipher";
    private static final String METADATA_KEY_ID = "encryption-key-id";
    private static final String METADATA_NONCE = "encryption-nonce";
    private static final String METADATA_VERSION = "encryption-version";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern KEY_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final String activeKeyId;
    private final SecretKey activeKey;
    private final Map<String, SecretKey> readableKeys;
    private final SecureRandom secureRandom;
    private final boolean encryptWrites;

    private AesGcmTemporalPayloadCodec(
            String activeKeyId,
            SecretKey activeKey,
            Map<String, SecretKey> readableKeys,
            SecureRandom secureRandom,
            boolean encryptWrites) {
        this.activeKeyId = activeKeyId;
        this.activeKey = activeKey;
        this.readableKeys = Map.copyOf(readableKeys);
        this.secureRandom = secureRandom;
        this.encryptWrites = encryptWrites;
    }

    public static AesGcmTemporalPayloadCodec from(
            AppProperties.Temporal.PayloadProtection properties) {
        if (properties == null || properties.mode() == Mode.DISABLED) {
            throw new IllegalArgumentException("Temporal payload protection is disabled");
        }
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        properties.decryptionKeys()
                .forEach(
                        (keyId, material) ->
                                keys.put(requireKeyId(keyId), decodeKey(keyId, material)));
        boolean hasActiveKeyId =
                properties.activeKeyId() != null && !properties.activeKeyId().isBlank();
        boolean hasActiveKeyMaterial =
                properties.activeKeyBase64() != null
                        && !properties.activeKeyBase64().isBlank();
        if (hasActiveKeyId != hasActiveKeyMaterial) {
            throw new IllegalArgumentException(
                    "Temporal active payload key id and material must be configured together");
        }
        String activeKeyId = null;
        SecretKey activeKey = null;
        if (hasActiveKeyId) {
            activeKeyId = requireKeyId(properties.activeKeyId());
            activeKey = decodeKey(activeKeyId, properties.activeKeyBase64());
            SecretKey duplicate = keys.put(activeKeyId, activeKey);
            if (duplicate != null
                    && !java.security.MessageDigest.isEqual(
                            duplicate.getEncoded(), activeKey.getEncoded())) {
                throw new IllegalArgumentException(
                        "active payload key conflicts with decryption key " + activeKeyId);
            }
        }
        if (properties.mode() == Mode.ENCRYPT && activeKey == null) {
            throw new IllegalArgumentException(
                    "ENCRYPT mode requires an active Temporal payload key");
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                    "Temporal payload protection requires at least one readable key");
        }
        return new AesGcmTemporalPayloadCodec(
                activeKeyId,
                activeKey,
                keys,
                new SecureRandom(),
                properties.mode() == Mode.ENCRYPT);
    }

    @Override
    public List<Payload> encode(List<Payload> payloads) {
        if (!encryptWrites) {
            return List.copyOf(payloads);
        }
        List<Payload> encoded = new ArrayList<>(payloads.size());
        payloads.forEach(payload -> encoded.add(encrypt(payload)));
        return List.copyOf(encoded);
    }

    @Override
    public List<Payload> decode(List<Payload> payloads) {
        List<Payload> decoded = new ArrayList<>(payloads.size());
        payloads.forEach(payload -> decoded.add(decryptIfEncoded(payload)));
        return List.copyOf(decoded);
    }

    private Payload encrypt(Payload payload) {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, activeKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(activeKeyId));
            byte[] ciphertext = cipher.doFinal(payload.toByteArray());
            return Payload.newBuilder()
                    .putMetadata(METADATA_ENCODING, utf8(ENCODING))
                    .putMetadata(METADATA_CIPHER, utf8(CIPHER))
                    .putMetadata(METADATA_KEY_ID, utf8(activeKeyId))
                    .putMetadata(METADATA_NONCE, ByteString.copyFrom(nonce))
                    .putMetadata(METADATA_VERSION, utf8(FORMAT_VERSION))
                    .setData(ByteString.copyFrom(ciphertext))
                    .build();
        } catch (GeneralSecurityException exception) {
            throw new PayloadCodecException("Temporal payload encryption failed", exception);
        }
    }

    private Payload decryptIfEncoded(Payload payload) {
        String encoding = metadata(payload, METADATA_ENCODING, false);
        if (!ENCODING.equals(encoding)) {
            // Existing histories were written before codec activation and remain replayable.
            return payload;
        }
        String version = metadata(payload, METADATA_VERSION, true);
        String cipherName = metadata(payload, METADATA_CIPHER, true);
        String keyId = metadata(payload, METADATA_KEY_ID, true);
        ByteString nonceValue = payload.getMetadataMap().get(METADATA_NONCE);
        if (!FORMAT_VERSION.equals(version)
                || !CIPHER.equals(cipherName)
                || nonceValue == null
                || nonceValue.size() != NONCE_BYTES) {
            throw new PayloadCodecException("Encrypted Temporal payload metadata is invalid");
        }
        SecretKey key = readableKeys.get(keyId);
        if (key == null) {
            throw new PayloadCodecException(
                    "Encrypted Temporal payload references an unavailable key");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, nonceValue.toByteArray()));
            cipher.updateAAD(aad(keyId));
            byte[] plaintext = cipher.doFinal(payload.getData().toByteArray());
            return Payload.parseFrom(plaintext);
        } catch (GeneralSecurityException | InvalidProtocolBufferException exception) {
            throw new PayloadCodecException(
                    "Encrypted Temporal payload authentication failed", exception);
        }
    }

    private static String metadata(Payload payload, String key, boolean required) {
        ByteString value = payload.getMetadataMap().get(key);
        if (value == null) {
            if (required) {
                throw new PayloadCodecException(
                        "Encrypted Temporal payload metadata is incomplete");
            }
            return "";
        }
        return value.toString(StandardCharsets.UTF_8);
    }

    private static SecretKey decodeKey(String keyId, String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException(
                    "Temporal payload key material is missing for " + keyId);
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Temporal payload key must be valid Base64 for " + keyId,
                    exception);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Temporal payload key must contain exactly 256 bits for " + keyId);
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static String requireKeyId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Temporal payload key id is invalid");
        }
        return keyId;
    }

    private static byte[] aad(String keyId) {
        return (FORMAT_VERSION + "\n" + CIPHER + "\n" + keyId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static ByteString utf8(String value) {
        return ByteString.copyFrom(value, StandardCharsets.UTF_8);
    }
}
