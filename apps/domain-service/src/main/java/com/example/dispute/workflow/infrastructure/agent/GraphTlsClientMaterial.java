package com.example.dispute.workflow.infrastructure.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** Explicit, bounded PKCS12 client identity and trust material for the Graph mTLS client. */
public final class GraphTlsClientMaterial implements AutoCloseable {

    private static final int MAXIMUM_PATH_BYTES = 4_096;
    private static final int MAXIMUM_PASSWORD_CHARACTERS = 1_024;

    private final Path keyStorePath;
    private final Path trustStorePath;
    private char[] keyStorePassword;
    private char[] trustStorePassword;
    private boolean destroyed;

    public GraphTlsClientMaterial(
            Path keyStorePath,
            char[] keyStorePassword,
            Path trustStorePath,
            char[] trustStorePassword) {
        this.keyStorePath = requirePath(keyStorePath, "keyStorePath");
        this.trustStorePath = requirePath(trustStorePath, "trustStorePath");
        if (this.keyStorePath.equals(this.trustStorePath)) {
            throw new IllegalArgumentException(
                    "Graph client key store and trust store must be distinct files");
        }
        char[] copiedKeyPassword = requirePassword(keyStorePassword, "keyStorePassword");
        try {
            this.trustStorePassword = requirePassword(trustStorePassword, "trustStorePassword");
            this.keyStorePassword = copiedKeyPassword;
        } catch (RuntimeException failure) {
            Arrays.fill(copiedKeyPassword, '\0');
            throw failure;
        }
    }

    public Path keyStorePath() {
        return keyStorePath;
    }

    public Path trustStorePath() {
        return trustStorePath;
    }

    synchronized char[] copyKeyStorePassword() {
        requireActive();
        return keyStorePassword.clone();
    }

    synchronized char[] copyTrustStorePassword() {
        requireActive();
        return trustStorePassword.clone();
    }

    public synchronized boolean destroyed() {
        return destroyed;
    }

    @Override
    public synchronized void close() {
        if (destroyed) {
            return;
        }
        Arrays.fill(keyStorePassword, '\0');
        Arrays.fill(trustStorePassword, '\0');
        destroyed = true;
    }

    private void requireActive() {
        if (destroyed) {
            throw new IllegalStateException("Graph TLS client material has been destroyed");
        }
    }

    private static Path requirePath(Path value, String field) {
        Path path = Objects.requireNonNull(value, field).normalize();
        String encoded = path.toString();
        if (!path.isAbsolute()
                || encoded.isBlank()
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_PATH_BYTES
                || encoded.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be a bounded absolute path");
        }
        return path;
    }

    private static char[] requirePassword(char[] value, String field) {
        Objects.requireNonNull(value, field);
        if (value.length == 0
                || value.length > MAXIMUM_PASSWORD_CHARACTERS
                || allWhitespace(value)) {
            throw new IllegalArgumentException(field + " must contain 1..1024 characters");
        }
        return value.clone();
    }

    private static boolean allWhitespace(char[] value) {
        for (char character : value) {
            if (!Character.isWhitespace(character)) {
                return false;
            }
        }
        return true;
    }
}
