package com.example.dispute.workflow.infrastructure.security;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phase 3 secret-volume adapter for current and retained ES256 signing keys.
 *
 * <p>Each key uses two files named {@code <kid>.private.pem} (PKCS#8) and
 * {@code <kid>.public.pem} (SubjectPublicKeyInfo). Rotation adds the new pair while old pairs stay
 * mounted until their durable command references have expired. Production KMS/HSM integrations
 * implement the same resolver/provider boundaries without changing envelope code.
 */
public final class MountedPemGraphEnvelopeKeySet
        implements GraphEnvelopeSigningKeyResolver, GraphJwkSetProvider {

    private static final Pattern KEY_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final String PUBLIC_SUFFIX = ".public.pem";
    private static final String PRIVATE_SUFFIX = ".private.pem";
    private static final int MAXIMUM_KEYS = 16;
    private static final long MAXIMUM_PEM_BYTES = 16 * 1024;
    private static final byte[] PAIR_PROBE =
            "after-sale-flow-graph-key-pair-probe".getBytes(StandardCharsets.US_ASCII);
    private static final Base64.Encoder BASE64_URL =
            Base64.getUrlEncoder().withoutPadding();

    private final Map<String, PemSigningKey> signingKeys;
    private final JwkSet jwkSet;

    private MountedPemGraphEnvelopeKeySet(
            Map<String, PemSigningKey> signingKeys,
            List<PublicJwk> publicKeys) {
        this.signingKeys = Map.copyOf(signingKeys);
        this.jwkSet = new JwkSet(publicKeys);
    }

    public static MountedPemGraphEnvelopeKeySet load(Path keyDirectory) {
        Objects.requireNonNull(keyDirectory, "keyDirectory");
        try {
            Path directory = keyDirectory.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("Graph signing key path is not a directory");
            }
            List<PublicMaterial> materials = publicMaterials(directory);
            Map<String, Path> privateFiles = privateFiles(directory, materials);
            Map<String, PemSigningKey> signingKeys = new HashMap<>();
            List<PublicJwk> publicKeys = new ArrayList<>();
            for (PublicMaterial material : materials) {
                Path privateFile = privateFiles.get(material.keyId());
                if (privateFile == null) {
                    throw new IllegalArgumentException(
                            "Graph signing key material is invalid: missing private key");
                }
                ECPrivateKey privateKey = readPrivateKey(privateFile);
                requireP256(privateKey.getParams());
                requireMatchingPair(privateKey, material.publicKey());
                PemSigningKey previous = signingKeys.put(
                        material.keyId(),
                        new PemSigningKey(material.keyId(), privateKey));
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate Graph signing key ID");
                }
                publicKeys.add(material.jwk());
            }
            return new MountedPemGraphEnvelopeKeySet(signingKeys, publicKeys);
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("Graph signing key material is invalid", exception);
        }
    }

    public static GraphJwkSetProvider loadPublicOnly(Path keyDirectory) {
        Objects.requireNonNull(keyDirectory, "keyDirectory");
        try {
            Path directory = keyDirectory.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("Graph JWKS key path is not a directory");
            }
            if (!keyFiles(directory, PRIVATE_SUFFIX).isEmpty()) {
                throw new IllegalArgumentException(
                        "Graph JWKS key path must not contain private key material");
            }
            JwkSet keys = new JwkSet(
                    publicMaterials(directory).stream().map(PublicMaterial::jwk).toList());
            return () -> keys;
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("Graph JWKS key material is invalid", exception);
        }
    }

    @Override
    public GraphEnvelopeSigningKey resolve(String keyId) {
        requireKeyId(keyId);
        GraphEnvelopeSigningKey key = signingKeys.get(keyId);
        if (key == null) {
            throw new IllegalArgumentException("Graph signing key ID is unavailable");
        }
        return key;
    }

    @Override
    public JwkSet jwkSet() {
        return jwkSet;
    }

    private static List<KeyFile> keyFiles(Path directory, String suffix) throws IOException {
        List<KeyFile> files = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(directory, "*" + suffix)) {
            for (Path candidate : stream) {
                String fileName = candidate.getFileName().toString();
                String keyId = fileName.substring(0, fileName.length() - suffix.length());
                requireKeyId(keyId);
                files.add(new KeyFile(keyId, containedRealPath(directory, candidate)));
            }
        }
        files.sort(Comparator.comparing(KeyFile::keyId));
        return List.copyOf(files);
    }

    private static List<PublicMaterial> publicMaterials(Path directory)
            throws IOException, GeneralSecurityException {
        List<KeyFile> publicFiles = keyFiles(directory, PUBLIC_SUFFIX);
        if (publicFiles.isEmpty() || publicFiles.size() > MAXIMUM_KEYS) {
            throw new IllegalArgumentException("Graph public key count must be inside 1..16");
        }
        List<PublicMaterial> materials = new ArrayList<>();
        for (KeyFile publicFile : publicFiles) {
            ECPublicKey publicKey = readPublicKey(publicFile.path());
            requireP256(publicKey.getParams());
            materials.add(new PublicMaterial(
                    publicFile.keyId(),
                    publicKey,
                    toPublicJwk(publicFile.keyId(), publicKey)));
        }
        materials.sort(Comparator.comparing(PublicMaterial::keyId));
        return List.copyOf(materials);
    }

    private static Map<String, Path> privateFiles(
            Path directory, List<PublicMaterial> publicMaterials) throws IOException {
        List<KeyFile> privateFiles = keyFiles(directory, PRIVATE_SUFFIX);
        if (privateFiles.size() > MAXIMUM_KEYS) {
            throw new IllegalArgumentException("Graph private key count must be inside 0..16");
        }
        Set<String> publicKeyIds = new HashSet<>();
        for (PublicMaterial material : publicMaterials) {
            publicKeyIds.add(material.keyId());
        }
        Map<String, Path> indexed = new HashMap<>();
        for (KeyFile privateFile : privateFiles) {
            if (!publicKeyIds.contains(privateFile.keyId())) {
                throw new IllegalArgumentException(
                        "Graph signing key directory contains an orphan private key");
            }
            if (indexed.put(privateFile.keyId(), privateFile.path()) != null) {
                throw new IllegalArgumentException("Duplicate Graph private key ID");
            }
        }
        return Map.copyOf(indexed);
    }

    private static Path containedRealPath(Path directory, Path candidate) throws IOException {
        Path real = candidate.toRealPath();
        if (!real.startsWith(directory) || !Files.isRegularFile(real)) {
            throw new IllegalArgumentException("Graph signing key file escapes its secret volume");
        }
        long size = Files.size(real);
        if (size < 1 || size > MAXIMUM_PEM_BYTES) {
            throw new IllegalArgumentException("Graph signing key PEM size is invalid");
        }
        return real;
    }

    private static ECPublicKey readPublicKey(Path path)
            throws IOException, GeneralSecurityException {
        byte[] source = readBoundedBytes(path);
        byte[] encoded = null;
        try {
            encoded = decodePem(source, "PUBLIC KEY");
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (ClassCastException exception) {
            throw new IllegalArgumentException("Graph public key must be EC", exception);
        } finally {
            Arrays.fill(source, (byte) 0);
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    private static ECPrivateKey readPrivateKey(Path path)
            throws IOException, GeneralSecurityException {
        byte[] source = readBoundedBytes(path);
        byte[] encoded = null;
        try {
            encoded = decodePem(source, "PRIVATE KEY");
            PrivateKey key = KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
            return (ECPrivateKey) key;
        } catch (ClassCastException exception) {
            throw new IllegalArgumentException("Graph private key must be EC", exception);
        } finally {
            Arrays.fill(source, (byte) 0);
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    private static byte[] readBoundedBytes(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] content = input.readNBytes((int) MAXIMUM_PEM_BYTES + 1);
            if (content.length < 1 || content.length > MAXIMUM_PEM_BYTES) {
                Arrays.fill(content, (byte) 0);
                throw new IllegalArgumentException("Graph signing key PEM size is invalid");
            }
            return content;
        }
    }

    private static byte[] decodePem(byte[] pem, String type) {
        byte[] begin = ("-----BEGIN " + type + "-----")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] end = ("-----END " + type + "-----")
                .getBytes(StandardCharsets.US_ASCII);
        int start = 0;
        while (start < pem.length && asciiWhitespace(pem[start])) {
            start++;
        }
        int finish = pem.length;
        while (finish > start && asciiWhitespace(pem[finish - 1])) {
            finish--;
        }
        int payloadStart = lineAfter(pem, start, finish, begin);
        int endStart = finish - end.length;
        int payloadEnd = lineBefore(pem, payloadStart, endStart, end);
        if (payloadStart < 0 || payloadEnd < payloadStart) {
            throw new IllegalArgumentException("Graph signing key PEM envelope is invalid");
        }
        int payloadLength = 0;
        for (int index = payloadStart; index < payloadEnd; index++) {
            if (!asciiWhitespace(pem[index])) {
                payloadLength++;
            }
        }
        byte[] payload = new byte[payloadLength];
        int target = 0;
        for (int index = payloadStart; index < payloadEnd; index++) {
            if (!asciiWhitespace(pem[index])) {
                payload[target++] = pem[index];
            }
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length == 0) {
                throw new IllegalArgumentException("Graph signing key PEM payload is empty");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Graph signing key PEM payload is invalid", exception);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private static int lineAfter(
            byte[] value, int start, int finish, byte[] marker) {
        int markerEnd = start + marker.length;
        if (markerEnd >= finish || !matches(value, start, marker)) {
            return -1;
        }
        if (value[markerEnd] == '\n') {
            return markerEnd + 1;
        }
        if (markerEnd + 1 < finish
                && value[markerEnd] == '\r'
                && value[markerEnd + 1] == '\n') {
            return markerEnd + 2;
        }
        return -1;
    }

    private static int lineBefore(
            byte[] value, int payloadStart, int markerStart, byte[] marker) {
        if (markerStart <= payloadStart || !matches(value, markerStart, marker)) {
            return -1;
        }
        int lineEnd = markerStart - 1;
        if (value[lineEnd] != '\n') {
            return -1;
        }
        return lineEnd > payloadStart && value[lineEnd - 1] == '\r'
                ? lineEnd - 1
                : lineEnd;
    }

    private static boolean matches(byte[] value, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > value.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (value[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiWhitespace(byte value) {
        return Byte.toUnsignedInt(value) <= 0x20;
    }

    private static void requireP256(ECParameterSpec actual) throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
        if (actual == null
                || !actual.getCurve().equals(expected.getCurve())
                || !actual.getGenerator().equals(expected.getGenerator())
                || !actual.getOrder().equals(expected.getOrder())
                || actual.getCofactor() != expected.getCofactor()) {
            throw new IllegalArgumentException("Graph signing keys must use P-256");
        }
    }

    private static void requireMatchingPair(ECPrivateKey privateKey, ECPublicKey publicKey)
            throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(privateKey);
        signer.update(PAIR_PROBE);
        byte[] signature = signer.sign();
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(publicKey);
            verifier.update(PAIR_PROBE);
            if (signature.length != 64 || !verifier.verify(signature)) {
                throw new IllegalArgumentException("Graph signing key pair does not match");
            }
        } finally {
            Arrays.fill(signature, (byte) 0);
        }
    }

    private static PublicJwk toPublicJwk(String keyId, ECPublicKey publicKey) {
        return new PublicJwk(
                "EC",
                "sig",
                "ES256",
                "P-256",
                keyId,
                BASE64_URL.encodeToString(unsignedCoordinate(publicKey.getW().getAffineX())),
                BASE64_URL.encodeToString(unsignedCoordinate(publicKey.getW().getAffineY())));
    }

    private static byte[] unsignedCoordinate(BigInteger value) {
        byte[] signed = value.toByteArray();
        int offset = signed.length == 33 && signed[0] == 0 ? 1 : 0;
        int length = signed.length - offset;
        if (length < 1 || length > 32) {
            throw new IllegalArgumentException("Graph P-256 coordinate is invalid");
        }
        byte[] fixed = new byte[32];
        System.arraycopy(signed, offset, fixed, fixed.length - length, length);
        return fixed;
    }

    private static String requireKeyId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Graph signing key ID is invalid");
        }
        return keyId;
    }

    private record PemSigningKey(String keyId, ECPrivateKey privateKey)
            implements GraphEnvelopeSigningKey {

        private PemSigningKey {
            requireKeyId(keyId);
            Objects.requireNonNull(privateKey, "privateKey");
        }

        @Override
        public byte[] signSha256(byte[] signingInput) {
            Objects.requireNonNull(signingInput, "signingInput");
            try {
                Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
                signature.initSign(privateKey);
                signature.update(signingInput.clone());
                byte[] value = signature.sign();
                if (value.length != 64) {
                    throw new IllegalStateException("Graph ES256 signature is not 64 bytes");
                }
                return value;
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("Graph signing operation failed", exception);
            }
        }
    }

    private record PublicMaterial(String keyId, ECPublicKey publicKey, PublicJwk jwk) {}

    private record KeyFile(String keyId, Path path) {}
}
