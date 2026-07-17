package com.example.dispute.workflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AppProperties.Temporal.PayloadProtection.Mode;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.payload.codec.PayloadCodecException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AesGcmTemporalPayloadCodecTest {

    private static final String OLD_KEY = key(1);
    private static final String NEW_KEY = key(33);

    @Test
    void encryptsPayloadBytesAndAuthenticatesTheRoundTrip() {
        var codec = codec("payload-key-v1", OLD_KEY, Map.of());
        Payload plaintext = plaintext("private-evidence-reference");

        Payload first = codec.encode(java.util.List.of(plaintext)).getFirst();
        Payload second = codec.encode(java.util.List.of(plaintext)).getFirst();

        assertThat(first.getMetadataOrThrow("encoding").toStringUtf8())
                .isEqualTo(AesGcmTemporalPayloadCodec.ENCODING);
        assertThat(first.getMetadataOrThrow("encryption-key-id").toStringUtf8())
                .isEqualTo("payload-key-v1");
        assertThat(first.getData()).isNotEqualTo(second.getData());
        assertThat(first.toString()).doesNotContain("private-evidence-reference");
        assertThat(codec.decode(java.util.List.of(first)).getFirst()).isEqualTo(plaintext);
    }

    @Test
    void newWriterCanReadPayloadsEncryptedByThePreviousKey() {
        Payload encrypted =
                codec("payload-key-v1", OLD_KEY, Map.of())
                        .encode(java.util.List.of(plaintext("rotating-reference")))
                        .getFirst();
        var rotated =
                codec(
                        "payload-key-v2",
                        NEW_KEY,
                        Map.of("payload-key-v1", OLD_KEY));

        assertThat(rotated.decode(java.util.List.of(encrypted)).getFirst())
                .isEqualTo(plaintext("rotating-reference"));
    }

    @Test
    void unknownKeyAndCiphertextTamperingFailClosed() {
        Payload encrypted =
                codec("payload-key-v1", OLD_KEY, Map.of())
                        .encode(java.util.List.of(plaintext("protected")))
                        .getFirst();

        assertThatThrownBy(
                        () ->
                                codec("payload-key-v2", NEW_KEY, Map.of())
                                        .decode(java.util.List.of(encrypted)))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessageContaining("unavailable key");

        byte[] corrupted = encrypted.getData().toByteArray();
        corrupted[0] ^= 0x01;
        Payload tampered =
                encrypted.toBuilder().setData(ByteString.copyFrom(corrupted)).build();
        assertThatThrownBy(
                        () ->
                                codec("payload-key-v1", OLD_KEY, Map.of())
                                        .decode(java.util.List.of(tampered)))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessageContaining("authentication failed");
    }

    @Test
    void legacyPlaintextRemainsReadableAndInvalidKeysAreRejectedAtStartup() {
        Payload legacy = plaintext("legacy-history");
        assertThat(
                        codec("payload-key-v1", OLD_KEY, Map.of())
                                .decode(java.util.List.of(legacy))
                                .getFirst())
                .isEqualTo(legacy);

        assertThatThrownBy(
                        () ->
                                codec(
                                        "payload-key-v1",
                                        Base64.getEncoder().encodeToString(new byte[16]),
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 bits");
    }

    private static AesGcmTemporalPayloadCodec codec(
            String activeKeyId,
            String activeKey,
            Map<String, String> readableKeys) {
        return AesGcmTemporalPayloadCodec.from(
                new AppProperties.Temporal.PayloadProtection(
                        Mode.ENCRYPT, activeKeyId, activeKey, readableKeys));
    }

    private static Payload plaintext(String value) {
        return Payload.newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                .setData(ByteString.copyFrom(value, StandardCharsets.UTF_8))
                .build();
    }

    private static String key(int firstByte) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (firstByte + index);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
