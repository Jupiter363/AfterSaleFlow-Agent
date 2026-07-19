package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.infrastructure.security.GraphJwkSetProvider;
import com.example.dispute.workflow.infrastructure.security.GraphJwkSetProvider.PublicJwk;
import com.example.dispute.workflow.infrastructure.security.MountedPemGraphEnvelopeKeySet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MountedPemGraphEnvelopeKeySetTest {

    @TempDir
    Path directory;

    @Test
    void loadsCurrentAndRetainedKeysSignsByExactKidAndPublishesStableJwks()
            throws Exception {
        KeyPair oldKey = keyPair("secp256r1");
        KeyPair currentKey = keyPair("secp256r1");
        writePair("graph-key-2026-06", oldKey);
        writePair("graph-key-2026-07", currentKey);

        MountedPemGraphEnvelopeKeySet keys =
                MountedPemGraphEnvelopeKeySet.load(directory);

        byte[] body = "header.payload".getBytes(StandardCharsets.US_ASCII);
        GraphEnvelopeSigningKey resolved = keys.resolve("graph-key-2026-06");
        byte[] signature = resolved.signSha256(body);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(oldKey.getPublic());
        verifier.update(body);

        assertThat(resolved.keyId()).isEqualTo("graph-key-2026-06");
        assertThat(signature).hasSize(64);
        assertThat(verifier.verify(signature)).isTrue();
        assertThat(keys.jwkSet().keys())
                .extracting(PublicJwk::kid)
                .containsExactly("graph-key-2026-06", "graph-key-2026-07");
        assertThat(keys.jwkSet().keys()).allSatisfy(jwk -> {
            assertThat(jwk.kty()).isEqualTo("EC");
            assertThat(jwk.use()).isEqualTo("sig");
            assertThat(jwk.alg()).isEqualTo("ES256");
            assertThat(jwk.crv()).isEqualTo("P-256");
            assertThat(Base64.getUrlDecoder().decode(jwk.x())).hasSize(32);
            assertThat(Base64.getUrlDecoder().decode(jwk.y())).hasSize(32);
        });
        assertThatThrownBy(() -> keys.resolve("graph-key-missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void rejectsMissingPrivateKeyWrongCurveAndMismatchedPair() throws Exception {
        KeyPair p256 = keyPair("secp256r1");
        writePublic("missing-private", p256);
        assertThatThrownBy(() -> MountedPemGraphEnvelopeKeySet.load(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");

        deleteFiles();
        writePair("wrong-curve", keyPair("secp384r1"));
        assertThatThrownBy(() -> MountedPemGraphEnvelopeKeySet.load(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P-256");

        deleteFiles();
        KeyPair first = keyPair("secp256r1");
        KeyPair second = keyPair("secp256r1");
        writePublic("mismatch", first);
        writePrivate("mismatch", second);
        assertThatThrownBy(() -> MountedPemGraphEnvelopeKeySet.load(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsMalformedPemAndAnEmptyKeyDirectory() throws Exception {
        assertThatThrownBy(() -> MountedPemGraphEnvelopeKeySet.load(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count");

        Files.writeString(
                directory.resolve("broken.public.pem"),
                "-----BEGIN PUBLIC KEY-----\nnot-base64\n-----END PUBLIC KEY-----\n",
                StandardCharsets.US_ASCII);
        Files.writeString(
                directory.resolve("broken.private.pem"),
                "-----BEGIN PRIVATE KEY-----\nnot-base64\n-----END PRIVATE KEY-----\n",
                StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> MountedPemGraphEnvelopeKeySet.load(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void publicOnlyProjectionDoesNotRequireOrExposePrivateMaterial() throws Exception {
        writePublic("graph-key-public", keyPair("secp256r1"));

        GraphJwkSetProvider provider =
                MountedPemGraphEnvelopeKeySet.loadPublicOnly(directory);

        assertThat(provider.jwkSet().keys())
                .extracting(PublicJwk::kid)
                .containsExactly("graph-key-public");
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.endsWith(".private.pem"));
        }
    }

    @Test
    void publicOnlyProjectionRejectsAccidentallyMountedPrivateMaterial() throws Exception {
        writePair("graph-key-private-leak", keyPair("secp256r1"));

        assertThatThrownBy(() -> MountedPemGraphEnvelopeKeySet.loadPublicOnly(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private key material");
    }

    @Test
    void signingProjectionRejectsOrphanPrivateMaterial() throws Exception {
        writePublic("graph-key-public", keyPair("secp256r1"));
        writePrivate("graph-key-orphan", keyPair("secp256r1"));

        assertThatThrownBy(() -> MountedPemGraphEnvelopeKeySet.load(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orphan private key");
    }

    private static KeyPair keyPair(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    private void writePair(String kid, KeyPair pair) throws Exception {
        writePublic(kid, pair);
        writePrivate(kid, pair);
    }

    private void writePublic(String kid, KeyPair pair) throws Exception {
        writePem(kid + ".public.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
    }

    private void writePrivate(String kid, KeyPair pair) throws Exception {
        writePem(kid + ".private.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    private void writePem(String fileName, String type, byte[] encoded) throws Exception {
        String payload = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        Files.writeString(
                directory.resolve(fileName),
                "-----BEGIN " + type + "-----\n"
                        + payload
                        + "\n-----END " + type + "-----\n",
                StandardCharsets.US_ASCII);
    }

    private void deleteFiles() throws Exception {
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }
}
