package com.example.dispute.workflow.infrastructure.agent;

/**
 * Unforgeable transport provenance produced by one of the bounded Graph transport factories.
 *
 * <p>The public unverified singleton is intentionally the only proof available to arbitrary
 * callers. Mutual TLS and local-plaintext proofs have private constructors owned by their
 * respective factories.
 */
public sealed interface GraphTransportSecurityProof
        permits GraphTransportSecurityProof.UnverifiedProof,
                TrustedGraphTransportFactory.MutualTlsProof,
                LocalGraphTransportFactory.LocalPlaintextProof {

    Mode mode();

    String protocol();

    String bundleId();

    default boolean trustedMutualTls() {
        return mode() == Mode.MUTUAL_TLS && "TLSv1.3".equals(protocol());
    }

    static GraphTransportSecurityProof unverified() {
        return UnverifiedProof.INSTANCE;
    }

    enum Mode {
        UNVERIFIED,
        MUTUAL_TLS,
        LOCAL_PLAINTEXT
    }

    final class UnverifiedProof implements GraphTransportSecurityProof {

        private static final UnverifiedProof INSTANCE = new UnverifiedProof();

        private UnverifiedProof() {}

        @Override
        public Mode mode() {
            return Mode.UNVERIFIED;
        }

        @Override
        public String protocol() {
            return "UNVERIFIED";
        }

        @Override
        public String bundleId() {
            return "unverified";
        }
    }
}
