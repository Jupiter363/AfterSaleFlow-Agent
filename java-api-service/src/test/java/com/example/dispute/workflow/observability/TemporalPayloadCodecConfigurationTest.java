package com.example.dispute.workflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AppProperties.Temporal.PayloadProtection.Mode;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemporalPayloadCodecConfigurationTest {

    private final TemporalPayloadCodecConfiguration configuration =
            new TemporalPayloadCodecConfiguration();

    @Test
    void disabledModePreservesTheLegacyDefaultConverter() {
        assertThat(configuration.temporalDataConverter(properties(Mode.DISABLED)))
                .isInstanceOf(DefaultDataConverter.class);
    }

    @Test
    void encryptModeEncryptsPayloadsAndFailureAttributes() {
        var converter = configuration.temporalDataConverter(properties(Mode.ENCRYPT));

        assertThat(converter).isInstanceOf(CodecDataConverter.class);
        var payload = converter.toPayload("sensitive-reference").orElseThrow();
        assertThat(payload.getMetadataOrThrow("encoding").toStringUtf8())
                .isEqualTo(AesGcmTemporalPayloadCodec.ENCODING);
        assertThat(converter.fromPayload(payload, String.class, String.class))
                .isEqualTo("sensitive-reference");

        var failure =
                converter.exceptionToFailure(
                        new IllegalStateException("private-provider-error"));
        assertThat(failure.toString()).doesNotContain("private-provider-error");
        assertThat(failure.hasEncodedAttributes()).isTrue();
        assertThat(
                        failure.getEncodedAttributes()
                                .getMetadataOrThrow("encoding")
                                .toStringUtf8())
                .isEqualTo(AesGcmTemporalPayloadCodec.ENCODING);
        assertThat(converter.failureToException(failure).getMessage())
                .contains("private-provider-error");
    }

    @Test
    void decryptOnlyModeExpandsReadersBeforeEncryptedWritesBegin() {
        var encrypting = configuration.temporalDataConverter(properties(Mode.ENCRYPT));
        var decryptOnly =
                configuration.temporalDataConverter(properties(Mode.DECRYPT_ONLY));
        var encrypted = encrypting.toPayload("rotation-reference").orElseThrow();
        var plaintextWrite = decryptOnly.toPayload("still-plaintext").orElseThrow();

        assertThat(decryptOnly.fromPayload(encrypted, String.class, String.class))
                .isEqualTo("rotation-reference");
        assertThat(plaintextWrite.getMetadataOrThrow("encoding").toStringUtf8())
                .isNotEqualTo(AesGcmTemporalPayloadCodec.ENCODING);
    }

    private static AppProperties properties(Mode mode) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        var protection =
                mode != Mode.DISABLED
                        ? new AppProperties.Temporal.PayloadProtection(
                                mode,
                                "payload-key-v1",
                                Base64.getEncoder().encodeToString(key),
                                Map.of())
                        : AppProperties.Temporal.PayloadProtection.disabled();
        return new AppProperties(
                "test",
                new AppProperties.Security("secret"),
                new AppProperties.Integration("http://agent", "secret", 1000),
                new AppProperties.Integration("http://ocr", "secret", 1000),
                new AppProperties.Temporal(
                        "localhost:7233",
                        "default",
                        "legacy-queue",
                        AppProperties.Temporal.Observability.defaults(),
                        protection),
                new AppProperties.Minio("http://minio", "access", "secret", "a", "b"),
                new AppProperties.Elasticsearch("http://elasticsearch"),
                new AppProperties.Feature(true, true, true, true, true, true, true),
                new AppProperties.Logging(true, true));
    }
}
