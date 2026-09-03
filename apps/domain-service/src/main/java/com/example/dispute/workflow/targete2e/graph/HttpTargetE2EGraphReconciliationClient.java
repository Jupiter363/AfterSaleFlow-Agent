package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationTransportException;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Exact-body result reconciliation client for target-E2E candidate commands. */
public final class HttpTargetE2EGraphReconciliationClient
    implements TargetE2EGraphReconciliationClient {

  public static final String PATH = "internal/graphs/target-e2e/commands/reconcile";
  public static final String ACTIVATION_HEADER = "X-AfterSaleFlow-Target-E2E-Activation";
  private static final int MAXIMUM_RESPONSE_BYTES = 131_072;
  private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
  private static final Set<String> ERROR_FIELDS = Set.of("code", "retryable");
  private static final Set<Integer> RETRY_REQUIRED_STATUSES = Set.of(429, 503);

  private final GraphTransportBundle transportBundle;
  private final GraphReconciliationHttpTransport transport;
  private final GraphTransportSecurityProof transportProof;
  private final TargetE2EGraphEnvelopeCodec codec;
  private final TargetE2EGraphProposalPayloadSource proposalSource;
  private final ObjectMapper mapper;
  private final URI baseUri;
  private final URI endpoint;
  private final Duration timeout;

  public HttpTargetE2EGraphReconciliationClient(
      GraphTransportBundle transportBundle,
      TargetE2EGraphEnvelopeCodec codec,
      TargetE2EGraphProposalPayloadSource proposalSource,
      ObjectMapper objectMapper,
      URI baseUri,
      Duration timeout) {
    TargetE2EGraphTransportPolicy.VerifiedBundle verified =
        TargetE2EGraphTransportPolicy.requireVerified(transportBundle);
    this.transportBundle = transportBundle;
    this.transport = verified.reconciliationTransport();
    this.transportProof = verified.proof();
    this.codec = Objects.requireNonNull(codec, "codec");
    this.proposalSource = Objects.requireNonNull(proposalSource, "proposalSource");
    this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.timeout = requireTimeout(timeout);
    this.baseUri = TargetE2EGraphTransportPolicy.requireTrustedBaseUri(baseUri);
    if (!this.baseUri.equals(verified.boundBaseUri())) {
      throw new IllegalArgumentException(
          "target Graph base URI does not match the factory-bound mTLS endpoint");
    }
    this.endpoint = this.baseUri.resolve(PATH);
  }

  @Override
  public TargetE2EGraphResultEnvelope reconcile(
      TargetE2ESealedGraphCommand sealed,
      String resultRef,
      String resultHash,
      AgentRunCancellationToken cancellationToken) {
    return reconcileAvailable(sealed, resultRef, resultHash, cancellationToken).envelope();
  }

  /** Reconciles a durable result when no Java stream terminal survived the worker restart. */
  public ReconciledResult reconcileAvailable(
      TargetE2ESealedGraphCommand sealed, AgentRunCancellationToken cancellationToken) {
    return reconcileAvailable(sealed, null, null, cancellationToken);
  }

  private ReconciledResult reconcileAvailable(
      TargetE2ESealedGraphCommand sealed,
      String expectedResultRef,
      String expectedResultHash,
      AgentRunCancellationToken cancellationToken) {
    Objects.requireNonNull(sealed, "sealed");
    Objects.requireNonNull(cancellationToken, "cancellationToken").throwIfCancellationRequested();
    TargetE2EGraphCommandEnvelope command = sealed.envelope();
    if (expectedResultHash != null) {
      TargetE2EGraphCommandEnvelope.requirePattern(
          expectedResultHash, TargetE2EGraphCommandEnvelope.SHA256, "resultHash");
    }
    if (expectedResultRef != null && !validResultReference(expectedResultRef)) {
      throw TargetE2EGraphClientException.protocol(
          "target Graph reconciliation result reference is invalid", null);
    }
    GraphReconciliationHttpTransport.Request request =
        new GraphReconciliationHttpTransport.Request(
            endpoint, requestHeaders(sealed), sealed.body(), timeout, MAXIMUM_RESPONSE_BYTES);
    GraphReconciliationHttpTransport.Response response;
    try {
      response = transport.exchange(request, cancellationToken);
    } catch (TargetE2EGraphClientException exception) {
      throw exception;
    } catch (GraphReconciliationTransportException exception) {
      cancellationToken.throwIfCancellationRequested();
      if (exception.protocolViolation()) {
        throw TargetE2EGraphClientException.protocol(
            "target Graph reconciliation transport violated the protocol", exception);
      }
      throw TargetE2EGraphClientException.transport(
          "target Graph reconciliation transport failed", exception);
    } catch (RuntimeException exception) {
      cancellationToken.throwIfCancellationRequested();
      throw TargetE2EGraphClientException.transport(
          "target Graph reconciliation transport failed", exception);
    }
    cancellationToken.throwIfCancellationRequested();
    requireResponseMetadata(response);
    if (response.statusCode() == 200) {
      try {
        ResultSelector selector = requireResultSelector(response);
        if ((expectedResultRef != null && !expectedResultRef.equals(selector.resultRef()))
            || (expectedResultHash != null
                && !TargetE2EGraphEnvelopeCodec.constantTimeEquals(
                    expectedResultHash, selector.resultHash()))) {
          throw new IllegalArgumentException(
              "target Graph result selector differs from the observed stream final");
        }
        byte[] body = response.body();
        String proposalHash = codec.declaredProposalHash(body);
        if (!TargetE2EGraphEnvelopeCodec.constantTimeEquals(
            proposalHash, selector.proposalHash())) {
          throw new IllegalArgumentException(
              "target Graph proposal hash differs from reconciliation metadata");
        }
        byte[] proposal =
            Objects.requireNonNull(
                proposalSource.loadSchemaValidatedProposalSource(
                    sealed, selector.resultRef(), proposalHash, cancellationToken),
                "proposal source returned no source document");
        TargetE2EGraphResultEnvelope result = codec.decodeResult(body, command, proposal);
        if (!TargetE2EGraphEnvelopeCodec.constantTimeEquals(
            selector.resultHash(), result.resultHash())) {
          throw new IllegalArgumentException("stream final hash differs from reconciled result");
        }
        return new ReconciledResult(result, selector.resultRef());
      } catch (IllegalArgumentException | NullPointerException exception) {
        throw TargetE2EGraphClientException.protocol(
            "target Graph reconciliation result is invalid", exception);
      }
    }
    if (response.statusCode() >= 400 && response.statusCode() <= 599) {
      throw decodeRemoteError(response.statusCode(), response.body());
    }
    throw TargetE2EGraphClientException.protocol(
        "target Graph reconciliation returned an unsupported HTTP status", null);
  }

  private Map<String, String> requestHeaders(TargetE2ESealedGraphCommand sealed) {
    Map<String, String> headers =
        Map.of(
            "Authorization", "Bearer " + sealed.credential().compactJws(),
            "Accept", "application/json",
            "Content-Type", "application/json; charset=utf-8",
            "Content-Encoding", "identity",
            "Cache-Control", "no-store",
            "traceparent", sealed.envelope().command().traceparent());
    if (headers.keySet().stream().anyMatch(ACTIVATION_HEADER::equalsIgnoreCase)) {
      throw new IllegalStateException("activation JWS header must never reach Graph");
    }
    return headers;
  }

  private TargetE2EGraphClientException decodeRemoteError(int status, byte[] body) {
    try {
      ObjectNode node = readObject(body);
      Set<String> fields =
          node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
      if (!fields.equals(ERROR_FIELDS)
          || !node.required("code").isTextual()
          || !node.required("retryable").isBoolean()) {
        throw new IllegalArgumentException("remote error envelope is invalid");
      }
      String code = node.required("code").asText();
      boolean retryable = node.required("retryable").asBoolean();
      if (!ERROR_CODE.matcher(code).matches() || !retryIdentityMatchesStatus(status, retryable)) {
        throw new IllegalArgumentException(
            "remote error retry identity conflicts with HTTP status");
      }
      return TargetE2EGraphClientException.remote(
          code, retryable, "Python rejected target Graph reconciliation");
    } catch (IllegalArgumentException exception) {
      return TargetE2EGraphClientException.protocol(
          "target Graph reconciliation error body is invalid", exception);
    }
  }

  private static boolean retryIdentityMatchesStatus(int status, boolean retryable) {
    return status == 409 || retryable == RETRY_REQUIRED_STATUSES.contains(status);
  }

  private ObjectNode readObject(byte[] body) {
    if (body == null || body.length == 0 || body.length > MAXIMUM_RESPONSE_BYTES) {
      throw new IllegalArgumentException("response body size is invalid");
    }
    try {
      JsonNode node =
          mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
      if (!(node instanceof ObjectNode object)) {
        throw new IllegalArgumentException("response body must be an object");
      }
      return object;
    } catch (IOException exception) {
      throw new IllegalArgumentException("response body is invalid JSON", exception);
    }
  }

  private static boolean validResultReference(String value) {
    if (value == null || value.isBlank() || value.length() > 512) {
      return false;
    }
    try {
      URI reference = URI.create(value);
      return reference.isAbsolute()
          && Set.of("s3", "minio", "urn").contains(reference.getScheme())
          && reference.getRawSchemeSpecificPart() != null
          && !reference.getRawSchemeSpecificPart().isBlank();
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static void requireResponseMetadata(GraphReconciliationHttpTransport.Response response) {
    Objects.requireNonNull(response, "response");
    List<String> contentType = headerValues(response.headers(), "content-type");
    List<String> contentEncoding = headerValues(response.headers(), "content-encoding");
    Set<String> cacheDirectives =
        headerValues(response.headers(), "cache-control").stream()
            .flatMap(value -> List.of(value.split(",", -1)).stream())
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    if (contentType.size() != 1
        || !jsonUtf8(contentType.getFirst())
        || contentEncoding.size() > 1
        || (contentEncoding.size() == 1
            && !"identity".equalsIgnoreCase(contentEncoding.getFirst().trim()))
        || !cacheDirectives.contains("no-store")) {
      throw TargetE2EGraphClientException.protocol(
          "target Graph reconciliation response metadata is invalid", null);
    }
  }

  private static ResultSelector requireResultSelector(
      GraphReconciliationHttpTransport.Response response) {
    List<String> resultRefs =
        headerValues(
            response.headers(), HttpTargetE2EGraphProposalSourceClient.RESULT_REF_HEADER);
    List<String> resultHashes = headerValues(response.headers(), "X-Graph-Result-Hash");
    List<String> proposalHashes =
        headerValues(
            response.headers(), HttpTargetE2EGraphProposalSourceClient.PROPOSAL_HASH_HEADER);
    if (resultRefs.size() != 1
        || !validResultReference(resultRefs.getFirst())
        || resultHashes.size() != 1
        || proposalHashes.size() != 1) {
      throw new IllegalArgumentException(
          "target Graph reconciliation result selector metadata is invalid");
    }
    TargetE2EGraphCommandEnvelope.requirePattern(
        resultHashes.getFirst(), TargetE2EGraphCommandEnvelope.SHA256, "resultHash");
    TargetE2EGraphCommandEnvelope.requirePattern(
        proposalHashes.getFirst(), TargetE2EGraphCommandEnvelope.SHA256, "proposalHash");
    return new ResultSelector(
        resultRefs.getFirst(), resultHashes.getFirst(), proposalHashes.getFirst());
  }

  static List<String> headerValues(Map<String, List<String>> headers, String expectedName) {
    List<String> values = new ArrayList<>();
    headers.forEach(
        (name, candidates) -> {
          if (name.equalsIgnoreCase(expectedName)) {
            values.addAll(candidates);
          }
        });
    return List.copyOf(values);
  }

  static boolean jsonUtf8(String value) {
    String[] parts = value.split(";", -1);
    if (!"application/json".equalsIgnoreCase(parts[0].trim())) {
      return false;
    }
    return parts.length == 1
        || (parts.length == 2 && "charset=utf-8".equalsIgnoreCase(parts[1].trim()));
  }

  static Duration requireTimeout(Duration timeout) {
    Duration value = Objects.requireNonNull(timeout, "timeout");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException("target Graph timeout must be positive");
    }
    return value;
  }

  GraphTransportSecurityProof transportProof() {
    return transportProof;
  }

  GraphTransportBundle transportBundle() {
    return transportBundle;
  }

  URI baseUri() {
    return baseUri;
  }

  public record ReconciledResult(TargetE2EGraphResultEnvelope envelope, String resultRef) {
    public ReconciledResult {
      Objects.requireNonNull(envelope, "envelope");
      if (!validResultReference(resultRef)) {
        throw new IllegalArgumentException("target Graph result reference is invalid");
      }
    }
  }

  private record ResultSelector(String resultRef, String resultHash, String proposalHash) {}
}
