package com.example.dispute.workflow.shadow.intake.admission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Loads only SubjectPublicKeyInfo P-256 PEMs from the dedicated admission trust mount. */
public final class MountedPemIntakeSyntheticAdmissionTrustSet {

    private static final String PUBLIC_SUFFIX = ".public.pem";
    private static final int MAXIMUM_PEM_BYTES = 16 * 1024;

    private MountedPemIntakeSyntheticAdmissionTrustSet() {}

    public static IntakeSyntheticAdmissionTrustSet load(Path keyDirectory) {
        Objects.requireNonNull(keyDirectory, "keyDirectory must not be null");
        try {
            Path directory = keyDirectory.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("admission trust path is not a directory");
            }
            rejectPrivateMaterial(directory);
            Map<String, ECPublicKey> keys = new HashMap<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + PUBLIC_SUFFIX)) {
                for (Path candidate : stream) {
                    Path path = containedFile(directory, candidate);
                    String fileName = path.getFileName().toString();
                    String keyId = IntakeSyntheticAdmissionTrustSet.requireKeyId(
                            fileName.substring(0, fileName.length() - PUBLIC_SUFFIX.length()));
                    if (keys.put(keyId, readPublicKey(path)) != null) {
                        throw new IllegalArgumentException("duplicate admission verification key ID");
                    }
                }
            }
            return new IntakeSyntheticAdmissionTrustSet(keys);
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("admission public trust material is invalid", exception);
        }
    }

    private static void rejectPrivateMaterial(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.private.pem")) {
            if (stream.iterator().hasNext()) {
                throw new IllegalArgumentException(
                        "admission trust path must not contain private key material");
            }
        }
    }

    private static Path containedFile(Path directory, Path candidate) throws IOException {
        Path path = candidate.toRealPath();
        if (!path.startsWith(directory) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("admission public key escapes its trust mount");
        }
        long size = Files.size(path);
        if (size < 1 || size > MAXIMUM_PEM_BYTES) {
            throw new IllegalArgumentException("admission public key PEM size is invalid");
        }
        return path;
    }

    private static ECPublicKey readPublicKey(Path path)
            throws IOException, GeneralSecurityException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII).trim();
        String begin = "-----BEGIN PUBLIC KEY-----";
        String end = "-----END PUBLIC KEY-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) {
            throw new IllegalArgumentException("admission public key PEM envelope is invalid");
        }
        String payload = pem.substring(begin.length(), pem.length() - end.length())
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(payload);
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (ClassCastException exception) {
            throw new IllegalArgumentException("admission verification key must be EC", exception);
        }
    }
}
