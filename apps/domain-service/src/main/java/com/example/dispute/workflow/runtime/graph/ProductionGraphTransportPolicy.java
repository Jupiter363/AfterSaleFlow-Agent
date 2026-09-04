package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import com.example.dispute.workflow.infrastructure.agent.TrustedGraphTransportFactory;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Accepts only the factory-issued TLS 1.3 mutual-authentication transport bundle. */
final class ProductionGraphTransportPolicy {

  private ProductionGraphTransportPolicy() {}

  static VerifiedBundle requireVerified(GraphTransportBundle candidate) {
    GraphTransportBundle bundle = Objects.requireNonNull(candidate, "transportBundle");
    GraphTransportSecurityProof proof =
        Objects.requireNonNull(bundle.transportProof(), "transportProof");
    GraphCommandHttpTransport command =
        Objects.requireNonNull(bundle.commandTransport(), "commandTransport");
    GraphReconciliationHttpTransport reconciliation =
        Objects.requireNonNull(bundle.reconciliationTransport(), "reconciliationTransport");
    if (!proof.trustedMutualTls()
        || proof.mode() != GraphTransportSecurityProof.Mode.MUTUAL_TLS
        || !"TLSv1.3".equals(proof.protocol())
        || proof.getClass().getEnclosingClass() != TrustedGraphTransportFactory.class
        || proof.bundleId() == null
        || proof.bundleId().isBlank()
        || command.transportProof() != proof
        || reconciliation.transportProof() != proof) {
      throw new IllegalArgumentException(
          "target Graph transport must be one factory-issued TLSv1.3 mutual-TLS bundle");
    }
    URI boundBaseUri =
        proof
            .boundBaseUri()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "target Graph transport proof is not bound to an HTTPS base URI"));
    return new VerifiedBundle(
        command, reconciliation, proof, requireTrustedBaseUri(boundBaseUri));
  }

  static URI requireTrustedBaseUri(URI candidate) {
    URI baseUri = Objects.requireNonNull(candidate, "baseUri");
    String scheme = baseUri.getScheme();
    String rawPath = baseUri.getRawPath();
    String lowerPath = rawPath == null ? "" : rawPath.toLowerCase(Locale.ROOT);
    if (!baseUri.isAbsolute()
        || baseUri.isOpaque()
        || baseUri.getHost() == null
        || baseUri.getHost().isBlank()
        || baseUri.getUserInfo() != null
        || baseUri.getQuery() != null
        || baseUri.getFragment() != null
        || (baseUri.getPort() != -1 && (baseUri.getPort() < 1 || baseUri.getPort() > 65_535))
        || !"https".equalsIgnoreCase(scheme)
        || !baseUri.normalize().equals(baseUri)
        || lowerPath.contains("%2e")
        || lowerPath.contains("%2f")
        || lowerPath.contains("%5c")) {
      throw new IllegalArgumentException("target Graph base URI is not trusted");
    }
    String ascii = baseUri.toASCIIString();
    return URI.create(ascii.endsWith("/") ? ascii : ascii + "/");
  }

  record VerifiedBundle(
      GraphCommandHttpTransport commandTransport,
      GraphReconciliationHttpTransport reconciliationTransport,
      GraphTransportSecurityProof proof,
      URI boundBaseUri) {}
}
