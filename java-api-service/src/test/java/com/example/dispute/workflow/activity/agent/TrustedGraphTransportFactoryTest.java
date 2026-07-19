package com.example.dispute.workflow.activity.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.infrastructure.agent.GraphTlsClientMaterial;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import com.example.dispute.workflow.infrastructure.agent.JdkGraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.JdkGraphReconciliationHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.LocalGraphTransportFactory;
import com.example.dispute.workflow.infrastructure.agent.TrustedGraphTransportFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustedGraphTransportFactoryTest {

    private static final char[] KEY_PASSWORD = "changeit".toCharArray();
    private static final char[] TRUST_PASSWORD = "trust-password".toCharArray();

    // Ephemeral test-only EC identity. It is not used by any deployed environment.
    private static final String CLIENT_KEY_STORE_BASE64 = String.join(
            "",
            "MIIETAIBAzCCA/YGCSqGSIb3DQEHAaCCA+cEggPjMIID3zCCATYGCSqGSIb3DQEHAaCCAScE",
            "ggEjMIIBHzCCARsGCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3",
            "DQEFDDArBBS08UqhSd89BKGdVjfjJTDb9YdD4wICJxACASAwDAYIKoZIhvcNAgkFADAdBglg",
            "hkgBZQMEASoEECLLDJlhH1ReWzWYPYzVgcQEUOQG5kNbhBjmhhItLAwJoYGimSYg422uNGCT",
            "gpIPCA8qv7GKzbGSzUP+epZA/GD0daNumAykxiQu/bmuMB0SnXIljG9vU+6dC2La2NwkZfS8",
            "MUwwJwYJKoZIhvcNAQkUMRoeGABnAHIAYQBwAGgALQBjAGwAaQBlAG4AdDAhBgkqhkiG9w0B",
            "CRUxFAQSVGltZSAxNzg0NDkzMDA5NDQzMIICoQYJKoZIhvcNAQcGoIICkjCCAo4CAQAwggKH",
            "BgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFMMnV7c6ESX3D+wU",
            "yBDch9Cz5fKCAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQZNfyzyxPDQRt",
            "3SqNoUuQg4CCAhDHrRrghyN5tnjXo69U4ni9f9G4yllIdKkIOQncI3ShNhGu7kqo68C81y0",
            "+Iuo8JgglYCdWnI0iJEfr/PqGllsPaiABN23/95yBv38jCGFwFBTcPSlce7MqgnVCQEUpUAn",
            "d6BoHKl6XUjcPrs4YYkq1kBtTWM6BfTj80XsHosMrnje3iEcHj8LXNy1C93xMZJ2JxV9aZs",
            "nDZMDbUCHvvMuxtwOMPw/U0lwEJIu+vAGZd8O4ONsgoo7ggrcwtohCPuiqqcRyBqTR8HORut",
            "jqhVqhJajYgyc1rmfKNUO5CgTSvP8UFRaR1kfCxp4U68GLcU3l7P2EUh9FnBanYbRwsVhF4",
            "NWNwcyw1mXWyb391ZjS1RTPnRTJBxLVIR1SD4j3aPqT1rThXS7P2gn80BpRDT8YICR2AKx2",
            "RO6Z84Veac+9limplm+9mzrJN5JOdcLc0k4WnbkUg9fbVKuA/Ac8a24vMqckS5tOlvj7mV",
            "JSPtF7C9oW3opffYyjmoT56l4iuIschJ0rjBhGrXTkZ74284bGbTRRrj/yGq+nyuZ/W+7/S",
            "Xm7YUX+xYE5PSIUEMigGlEM6AsUEtEndDISZtG5nwLIPBkDR7OH6EzUlhN+oLt8hQS7J+xS",
            "5dKZmNK382EGhA1dM5anHFUKAg84qi9M5Jifat+WjAghD9nQg70Mc+gX9b53u/drLRKnjiA",
            "z4ArWGtQwTTAxMA0GCWCGSAFlAwQCAQUABCAQOLGoW9Nk/NIMup8FUgChExAXiUVzQ7YQmj",
            "5ovznSfgQUwhj8FSAGRagafNCyosCjv+OTbm0CAicQ");

    private static final String CLIENT_CERTIFICATE_BASE64 = String.join(
            "",
            "MIIBhDCCASqgAwIBAgIJAIDfbGYAVnHSMAoGCCqGSM49BAMCMBsxGTAXBgNVBAMTEGphdmEt",
            "YXBpLXNlcnZpY2UwHhcNMjYwNzE5MjAzMDA5WhcNMzYwNzE2MjAzMDA5WjAbMRkwFwYDVQQD",
            "ExBqYXZhLWFwaS1zZXJ2aWNlMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERjsByFD68Kve",
            "dB6eAuNrGbjElAUaxLb7z0tLsz4nHK0UF0SX1L2IKxyli99czGpousAsvYoO8/hcEPHQBbn+",
            "h6NXMFUwHQYDVR0OBBYEFKLuF0ok5ragWNm3uuWdD6vvbDnmMDQGA1UdEQQtMCuGKXNwaWZm",
            "ZTovL2FmdGVyLXNhbGUtZmxvdy9qYXZhLWFwaS1zZXJ2aWNlMAoGCCqGSM49BAMCA0gAMEUC",
            "IQDTX1h92+mCjAlVqbQ3TRC54aYaBN3/fy/4RaWD/bzTQQIgMdSDM/GykGTMCZmwCrQG48Pb",
            "haPdBRFs5sgiDnVaHnw=");

    @TempDir
    Path temporaryDirectory;

    @Test
    void trustedFactoryBuildsBothTransportsFromOneTls13Proof() throws Exception {
        Path keyStore = writeClientKeyStore();
        Path trustStore = writeTrustStore();
        try (GraphTlsClientMaterial material = material(keyStore, KEY_PASSWORD, trustStore, TRUST_PASSWORD)) {
            GraphTransportBundle bundle =
                    TrustedGraphTransportFactory.create(material, Duration.ofSeconds(2));

            assertThat(bundle.transportProof().mode())
                    .isEqualTo(GraphTransportSecurityProof.Mode.MUTUAL_TLS);
            assertThat(bundle.transportProof().protocol()).isEqualTo("TLSv1.3");
            assertThat(bundle.transportProof().trustedMutualTls()).isTrue();
            assertThat(bundle.commandTransport())
                    .isInstanceOf(JdkGraphCommandHttpTransport.class);
            assertThat(bundle.reconciliationTransport())
                    .isInstanceOf(JdkGraphReconciliationHttpTransport.class);
            assertThat(bundle.commandTransport().transportProof())
                    .isSameAs(bundle.transportProof());
            assertThat(bundle.reconciliationTransport().transportProof())
                    .isSameAs(bundle.transportProof());
        }
    }

    @Test
    void localPlaintextBundleCannotMasqueradeAsMutualTls() {
        GraphTransportBundle bundle = LocalGraphTransportFactory.create(
                LocalGraphTransportFactory.Profile.TEST, Duration.ofSeconds(1));

        assertThat(bundle.transportProof().mode())
                .isEqualTo(GraphTransportSecurityProof.Mode.LOCAL_PLAINTEXT);
        assertThat(bundle.transportProof().protocol()).isEqualTo("PLAINTEXT");
        assertThat(bundle.transportProof().trustedMutualTls()).isFalse();
        assertThat(bundle.commandTransport().transportProof())
                .isSameAs(bundle.reconciliationTransport().transportProof())
                .isSameAs(bundle.transportProof());
    }

    @Test
    void rejectsEmptyOrOversizedPasswordsAndPaths() {
        Path keyStore = temporaryDirectory.resolve("client.p12");
        Path trustStore = temporaryDirectory.resolve("trust.p12");

        assertThatThrownBy(() -> material(keyStore, new char[0], trustStore, TRUST_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyStorePassword");
        assertThatThrownBy(() -> material(
                        keyStore, "x".repeat(1_025).toCharArray(), trustStore, TRUST_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyStorePassword");
        assertThatThrownBy(() -> material(keyStore, KEY_PASSWORD, trustStore, new char[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trustStorePassword");
        assertThatThrownBy(() -> material(
                        Path.of("relative-client.p12"),
                        KEY_PASSWORD,
                        trustStore,
                        TRUST_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyStorePath");
        assertThatThrownBy(() -> material(
                        temporaryDirectory.resolve("x".repeat(4_100)),
                        KEY_PASSWORD,
                        trustStore,
                        TRUST_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyStorePath");
        assertThatThrownBy(() -> material(keyStore, KEY_PASSWORD, keyStore, TRUST_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct");
    }

    @Test
    void wrongPasswordAndMalformedKeyStoreFailClosed() throws Exception {
        Path keyStore = writeClientKeyStore();
        Path trustStore = writeTrustStore();
        try (GraphTlsClientMaterial wrongPassword =
                material(keyStore, "wrong-password".toCharArray(), trustStore, TRUST_PASSWORD)) {
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            wrongPassword, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rejected");
        }

        Path malformed = temporaryDirectory.resolve("malformed-client.p12");
        Files.writeString(malformed, "not a PKCS12 key store");
        try (GraphTlsClientMaterial invalid =
                material(malformed, KEY_PASSWORD, trustStore, TRUST_PASSWORD)) {
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            invalid, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rejected");
        }
    }

    @Test
    void emptyTrustStoreAndDestroyedMaterialFailClosed() throws Exception {
        Path keyStore = writeClientKeyStore();
        Path emptyTrustStore = temporaryDirectory.resolve("empty-trust.p12");
        KeyStore empty = KeyStore.getInstance("PKCS12");
        empty.load(null, TRUST_PASSWORD);
        try (var output = Files.newOutputStream(emptyTrustStore)) {
            empty.store(output, TRUST_PASSWORD);
        }
        try (GraphTlsClientMaterial material =
                material(keyStore, KEY_PASSWORD, emptyTrustStore, TRUST_PASSWORD)) {
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            material, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rejected");
        }

        Path keyBearingTrustStore = temporaryDirectory.resolve("key-bearing-trust.p12");
        Files.copy(keyStore, keyBearingTrustStore);
        try (GraphTlsClientMaterial material =
                material(keyStore, KEY_PASSWORD, keyBearingTrustStore, KEY_PASSWORD)) {
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            material, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rejected");
        }

        Path trustStore = writeTrustStore();
        GraphTlsClientMaterial destroyed = material(keyStore, KEY_PASSWORD, trustStore, TRUST_PASSWORD);
        destroyed.close();
        assertThat(destroyed.destroyed()).isTrue();
        assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                        destroyed, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destroyed");
    }

    @Test
    void factoriesRejectUnboundedConnectTimeoutsBeforeOpeningMaterial() {
        Path keyStore = temporaryDirectory.resolve("missing-client.p12");
        Path trustStore = temporaryDirectory.resolve("missing-trust.p12");
        try (GraphTlsClientMaterial material = material(keyStore, KEY_PASSWORD, trustStore, TRUST_PASSWORD)) {
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            material, Duration.ofMillis(99)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connect timeout");
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            material, Duration.ofSeconds(31)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connect timeout");
        }
        assertThatThrownBy(() -> LocalGraphTransportFactory.create(
                        LocalGraphTransportFactory.Profile.LOCAL, Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect timeout");
    }

    private GraphTlsClientMaterial material(
            Path keyStore,
            char[] keyPassword,
            Path trustStore,
            char[] trustPassword) {
        return new GraphTlsClientMaterial(keyStore, keyPassword, trustStore, trustPassword);
    }

    private Path writeClientKeyStore() throws Exception {
        Path path = temporaryDirectory.resolve("client-" + System.nanoTime() + ".p12");
        Files.write(path, Base64.getDecoder().decode(CLIENT_KEY_STORE_BASE64));
        return path;
    }

    private Path writeTrustStore() throws Exception {
        Certificate certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                        Base64.getDecoder().decode(CLIENT_CERTIFICATE_BASE64)));
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, TRUST_PASSWORD);
        trustStore.setCertificateEntry("graph-test-ca", certificate);
        Path path = temporaryDirectory.resolve("trust-" + System.nanoTime() + ".p12");
        try (var output = Files.newOutputStream(path)) {
            trustStore.store(output, TRUST_PASSWORD);
        }
        return path;
    }
}
