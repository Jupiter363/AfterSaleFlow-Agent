package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationTransportException;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Authenticated exact-body retrieval of the proposal source referenced by a target result. */
public final class HttpProductionGraphProposalSourceClient
    implements ProductionGraphProposalPayloadSource {

  public static final String PATH = "internal/graphs/production-runtime/commands/proposal-source";
  public static final String RESULT_REF_HEADER = "X-Graph-Result-Ref";
  public static final String PROPOSAL_HASH_HEADER = "X-Graph-Proposal-Hash";
  private static final int MAXIMUM_RESPONSE_BYTES = 65_536;

  private final GraphTransportBundle transportBundle;
  private final GraphReconciliationHttpTransport transport;
  private final GraphTransportSecurityProof transportProof;
  private final ProductionGraphEnvelopeCodec codec;
  private final URI baseUri;
  private final URI endpoint;
  private final Duration timeout;

  public HttpProductionGraphProposalSourceClient(
      GraphTransportBundle transportBundle,
      ProductionGraphEnvelopeCodec codec,
      URI baseUri,
      Duration timeout) {
    ProductionGraphTransportPolicy.VerifiedBundle verified =
        ProductionGraphTransportPolicy.requireVerified(transportBundle);
    this.transportBundle = transportBundle;
    this.transport = verified.reconciliationTransport();
    this.transportProof = verified.proof();
    this.codec = Objects.requireNonNull(codec, "codec");
    this.baseUri = ProductionGraphTransportPolicy.requireTrustedBaseUri(baseUri);
    if (!this.baseUri.equals(verified.boundBaseUri())) {
      throw new IllegalArgumentException(
          "target Graph base URI does not match the factory-bound mTLS endpoint");
    }
    this.endpoint = this.baseUri.resolve(PATH);
    this.timeout = HttpProductionGraphReconciliationClient.requireTimeout(timeout);
  }

  @Override
  public byte[] loadSchemaValidatedProposalSource(
      ProductionSealedGraphCommand sealed,
      String resultRef,
      String expectedProposalHash,
      AgentRunCancellationToken cancellationToken) {
    Objects.requireNonNull(sealed, "sealed");
    Objects.requireNonNull(cancellationToken, "cancellationToken").throwIfCancellationRequested();
    String boundedResultRef = requireHeaderValue(resultRef, 512, "resultRef");
    ProductionGraphCommandEnvelope.requirePattern(
        expectedProposalHash, ProductionGraphCommandEnvelope.SHA256, "expectedProposalHash");
    GraphReconciliationHttpTransport.Request request =
        new GraphReconciliationHttpTransport.Request(
            endpoint,
            requestHeaders(sealed, boundedResultRef, expectedProposalHash),
            sealed.body(),
            timeout,
            MAXIMUM_RESPONSE_BYTES);
    GraphReconciliationHttpTransport.Response response;
    try {
      response = transport.exchange(request, cancellationToken);
    } catch (GraphReconciliationTransportException exception) {
      cancellationToken.throwIfCancellationRequested();
      if (exception.protocolViolation()) {
        throw ProductionGraphClientException.protocol(
            "target Graph proposal-source transport violated the protocol", exception);
      }
      throw ProductionGraphClientException.transport(
          "target Graph proposal-source transport failed", exception);
    } catch (RuntimeException exception) {
      cancellationToken.throwIfCancellationRequested();
      throw ProductionGraphClientException.transport(
          "target Graph proposal-source transport failed", exception);
    }
    cancellationToken.throwIfCancellationRequested();
    if (response.statusCode() >= 300 && response.statusCode() <= 399) {
      throw ProductionGraphClientException.protocol(
          "target Graph proposal-source redirect is forbidden", null);
    }
    if (response.statusCode() != 200) {
      boolean retryable = Set.of(409, 429, 503).contains(response.statusCode());
      throw ProductionGraphClientException.remote(
          "PRODUCTION_RUNTIME_GRAPH_PROPOSAL_SOURCE_HTTP_" + response.statusCode(),
          retryable,
          "Python rejected target Graph proposal-source retrieval");
    }
    requireResponseMetadata(response, boundedResultRef, expectedProposalHash);
    try {
      return codec.validateProposalSource(
          response.body(), sealed.envelope().command(), expectedProposalHash);
    } catch (IllegalArgumentException exception) {
      throw ProductionGraphClientException.protocol(
          "target Graph proposal-source response is invalid", exception);
    }
  }

  private Map<String, String> requestHeaders(
      ProductionSealedGraphCommand sealed, String resultRef, String proposalHash) {
    Map<String, String> headers =
        Map.of(
            "Authorization", "Bearer " + sealed.credential().compactJws(),
            "Accept", "application/json",
            "Content-Type", "application/json; charset=utf-8",
            "Content-Encoding", "identity",
            "Cache-Control", "no-store",
            RESULT_REF_HEADER, resultRef,
            PROPOSAL_HASH_HEADER, proposalHash,
            "traceparent", sealed.envelope().command().traceparent());
    if (headers.keySet().stream()
        .anyMatch(HttpProductionGraphReconciliationClient.ACTIVATION_HEADER::equalsIgnoreCase)) {
      throw new IllegalStateException("activation JWS header must never reach Graph");
    }
    return headers;
  }

  private static void requireResponseMetadata(
      GraphReconciliationHttpTransport.Response response,
      String expectedResultRef,
      String expectedProposalHash) {
    Objects.requireNonNull(response, "response");
    List<String> contentType =
        HttpProductionGraphReconciliationClient.headerValues(response.headers(), "content-type");
    List<String> contentEncoding =
        HttpProductionGraphReconciliationClient.headerValues(
            response.headers(), "content-encoding");
    List<String> resultRefs =
        HttpProductionGraphReconciliationClient.headerValues(
            response.headers(), RESULT_REF_HEADER);
    List<String> proposalHashes =
        HttpProductionGraphReconciliationClient.headerValues(
            response.headers(), PROPOSAL_HASH_HEADER);
    Set<String> cacheDirectives =
        HttpProductionGraphReconciliationClient.headerValues(
                response.headers(), "cache-control")
            .stream()
            .flatMap(value -> List.of(value.split(",", -1)).stream())
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    boolean exact =
        contentType.size() == 1
            && HttpProductionGraphReconciliationClient.jsonUtf8(contentType.getFirst())
            && contentEncoding.size() <= 1
            && (contentEncoding.isEmpty()
                || "identity".equalsIgnoreCase(contentEncoding.getFirst().trim()))
            && cacheDirectives.contains("no-store")
            && resultRefs.equals(List.of(expectedResultRef))
            && proposalHashes.size() == 1
            && ProductionGraphEnvelopeCodec.constantTimeEquals(
                expectedProposalHash, proposalHashes.getFirst());
    if (!exact) {
      throw ProductionGraphClientException.protocol(
          "target Graph proposal-source response metadata is invalid", null);
    }
  }

  private static String requireHeaderValue(String value, int maximumLength, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > maximumLength
        || value.getBytes(StandardCharsets.US_ASCII).length != value.length()
        || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
      throw ProductionGraphClientException.protocol(
          "target Graph proposal-source " + field + " is not a bounded header value", null);
    }
    return value;
  }

  GraphTransportBundle transportBundle() {
    return transportBundle;
  }

  GraphTransportSecurityProof transportProof() {
    return transportProof;
  }

  URI baseUri() {
    return baseUri;
  }
}
