package com.example.dispute.workflow.infrastructure.agent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Objects;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/** Builds both production Graph transports from one validated TLSv1.3 client identity. */
public final class TrustedGraphTransportFactory {

    private static final int MAXIMUM_STORE_BYTES = 1024 * 1024;
    private static final Duration MINIMUM_CONNECT_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAXIMUM_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final byte[] KEY_PAIR_PROBE =
            "after-sale-flow-graph-client-key-proof".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";

    private TrustedGraphTransportFactory() {}

    public static GraphTransportBundle create(
            GraphTlsClientMaterial material, Duration connectTimeout) {
        Objects.requireNonNull(material, "material");
        Duration boundedTimeout = requireConnectTimeout(connectTimeout);
        char[] keyPassword = material.copyKeyStorePassword();
        char[] trustPassword = null;
        try {
            trustPassword = material.copyTrustStorePassword();
            KeyStore keyStore = loadStore(material.keyStorePath(), keyPassword, "client key store");
            KeyStore trustStore = loadStore(material.trustStorePath(), trustPassword, "trust store");
            requireClientIdentity(keyStore, keyPassword);
            requireTrustAnchors(trustStore);

            KeyManagerFactory keyManagers =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, keyPassword);
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
            sslContext.init(
                    keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), new SecureRandom());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setProtocols(new String[] {"TLSv1.3"});
            sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(boundedTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .sslContext(sslContext)
                    .sslParameters(sslParameters)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            MutualTlsProof proof = new MutualTlsProof(UUID.randomUUID().toString());
            return new GraphTransportBundle(
                    new JdkGraphCommandHttpTransport(httpClient, proof),
                    new JdkGraphReconciliationHttpTransport(httpClient, proof),
                    proof);
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalArgumentException("Graph TLS client material was rejected", exception);
        } finally {
            Arrays.fill(keyPassword, '\0');
            if (trustPassword != null) {
                Arrays.fill(trustPassword, '\0');
            }
        }
    }

    private static Duration requireConnectTimeout(Duration value) {
        Duration timeout = Objects.requireNonNull(value, "connectTimeout");
        if (timeout.compareTo(MINIMUM_CONNECT_TIMEOUT) < 0
                || timeout.compareTo(MAXIMUM_CONNECT_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Graph transport connect timeout must be between 100ms and 30s");
        }
        return timeout;
    }

    private static KeyStore loadStore(Path path, char[] password, String label)
            throws GeneralSecurityException, IOException {
        byte[] encoded = readBoundedStore(path, label);
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(encoded), password);
            return store;
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static byte[] readBoundedStore(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a regular non-symbolic file");
        }
        byte[] encoded = null;
        byte[] overflowProbe = new byte[1];
        boolean complete = false;
        try (SeekableByteChannel channel = Files.newByteChannel(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 1 || size > MAXIMUM_STORE_BYTES) {
                throw new IOException(label + " size is invalid");
            }
            encoded = new byte[(int) size];
            ByteBuffer target = ByteBuffer.wrap(encoded);
            while (target.hasRemaining()) {
                int read = channel.read(target);
                if (read <= 0) {
                    throw new IOException(label + " could not be read completely");
                }
            }
            if (channel.read(ByteBuffer.wrap(overflowProbe)) >= 0) {
                throw new IOException(label + " changed size while being read");
            }
            complete = true;
            return encoded;
        } finally {
            Arrays.fill(overflowProbe, (byte) 0);
            if (!complete && encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    private static void requireClientIdentity(KeyStore keyStore, char[] password)
            throws GeneralSecurityException {
        Enumeration<String> aliases = keyStore.aliases();
        int privateKeyEntries = 0;
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (!keyStore.isKeyEntry(alias)) {
                continue;
            }
            privateKeyEntries++;
            if (privateKeyEntries > 1) {
                throw new GeneralSecurityException(
                        "Graph client key store must contain exactly one private-key identity");
            }
            Key key = keyStore.getKey(alias, password);
            Certificate[] chain = keyStore.getCertificateChain(alias);
            if (!(key instanceof PrivateKey privateKey)
                    || chain == null
                    || chain.length == 0
                    || !(chain[0] instanceof X509Certificate certificate)) {
                throw new GeneralSecurityException(
                        "Graph client key store has an invalid private-key identity");
            }
            for (Certificate chainCertificate : chain) {
                if (!(chainCertificate instanceof X509Certificate x509Certificate)) {
                    throw new GeneralSecurityException(
                            "Graph client certificate chain must contain only X.509 certificates");
                }
                x509Certificate.checkValidity();
            }
            requireClientUsage(certificate);
            requireMatchingKeyPair(privateKey, certificate);
        }
        if (privateKeyEntries != 1) {
            throw new GeneralSecurityException(
                    "Graph client key store must contain exactly one private-key identity");
        }
    }

    private static void requireClientUsage(X509Certificate certificate)
            throws GeneralSecurityException {
        boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage != null && (keyUsage.length == 0 || !keyUsage[0])) {
            throw new GeneralSecurityException(
                    "Graph client certificate does not permit digital signatures");
        }
        java.util.List<String> extendedUsage = certificate.getExtendedKeyUsage();
        if (extendedUsage != null && !extendedUsage.contains(CLIENT_AUTH_EKU)) {
            throw new GeneralSecurityException(
                    "Graph client certificate does not permit TLS client authentication");
        }
    }

    private static void requireMatchingKeyPair(
            PrivateKey privateKey, X509Certificate certificate) throws GeneralSecurityException {
        String algorithm = switch (privateKey.getAlgorithm()) {
            case "EC" -> "SHA256withECDSA";
            case "RSA" -> "SHA256withRSA";
            default -> throw new GeneralSecurityException(
                    "Graph client private key algorithm is unsupported");
        };
        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(privateKey);
        signer.update(KEY_PAIR_PROBE);
        byte[] signature = signer.sign();
        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(KEY_PAIR_PROBE);
            if (!verifier.verify(signature)) {
                throw new GeneralSecurityException(
                        "Graph client private key does not match its certificate");
            }
        } finally {
            Arrays.fill(signature, (byte) 0);
        }
    }

    private static void requireTrustAnchors(KeyStore trustStore)
            throws GeneralSecurityException {
        Enumeration<String> aliases = trustStore.aliases();
        int trustAnchors = 0;
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (trustStore.isKeyEntry(alias)) {
                throw new GeneralSecurityException(
                        "Graph trust store must not contain private or secret keys");
            }
            if (!trustStore.isCertificateEntry(alias)
                    || !(trustStore.getCertificate(alias) instanceof X509Certificate certificate)) {
                throw new GeneralSecurityException(
                        "Graph trust store contains a non-X.509 entry");
            }
            certificate.checkValidity();
            trustAnchors++;
        }
        if (trustAnchors == 0) {
            throw new GeneralSecurityException("Graph trust store has no X.509 trust anchor");
        }
    }

    static final class MutualTlsProof implements GraphTransportSecurityProof {

        private final String bundleId;

        private MutualTlsProof(String bundleId) {
            this.bundleId = bundleId;
        }

        @Override
        public Mode mode() {
            return Mode.MUTUAL_TLS;
        }

        @Override
        public String protocol() {
            return "TLSv1.3";
        }

        @Override
        public String bundleId() {
            return bundleId;
        }
    }
}
