package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.agentstream.application.AgentStreamProtocolException;
import com.example.dispute.agentstream.infrastructure.AgentNdjsonStreamClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandTransportException;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded NDJSON command client for the isolated target-E2E proposal lane. */
public final class HttpTargetE2EGraphProposalClient implements TargetE2EGraphProposalClient {

  public static final String PATH = "internal/graphs/target-e2e/commands/stream";
  private static final Logger LOG = LoggerFactory.getLogger(HttpTargetE2EGraphProposalClient.class);
  private static final String DIAGNOSTIC_UNAVAILABLE = "UNAVAILABLE";
  private static final String DIAGNOSTIC_NOT_APPLICABLE = "NOT_APPLICABLE";

  private final GraphCommandHttpTransport transport;
  private final HttpTargetE2EGraphReconciliationClient reconciliationClient;
  private final ObjectMapper mapper;
  private final URI endpoint;
  private final Duration timeout;

  public HttpTargetE2EGraphProposalClient(
      GraphTransportBundle transportBundle,
      HttpTargetE2EGraphReconciliationClient reconciliationClient,
      ObjectMapper objectMapper,
      URI baseUri,
      Duration timeout) {
    TargetE2EGraphTransportPolicy.VerifiedBundle verified =
        TargetE2EGraphTransportPolicy.requireVerified(transportBundle);
    this.transport = verified.commandTransport();
    this.reconciliationClient =
        Objects.requireNonNull(reconciliationClient, "reconciliationClient");
    URI trustedBaseUri = TargetE2EGraphTransportPolicy.requireTrustedBaseUri(baseUri);
    if (!trustedBaseUri.equals(verified.boundBaseUri())) {
      throw new IllegalArgumentException(
          "target Graph base URI does not match the factory-bound mTLS endpoint");
    }
    if (reconciliationClient.transportBundle() != transportBundle
        || reconciliationClient.transportProof() != verified.proof()) {
      throw new IllegalArgumentException(
          "target Graph clients must share one factory-issued transport bundle and TLS identity");
    }
    if (!reconciliationClient.baseUri().equals(trustedBaseUri)) {
      throw new IllegalArgumentException(
          "target Graph command and reconciliation clients must share one trusted base URI");
    }
    this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.endpoint = trustedBaseUri.resolve(PATH);
    this.timeout = HttpTargetE2EGraphReconciliationClient.requireTimeout(timeout);
  }

  @Override
  public TargetE2EGraphResultEnvelope execute(
      TargetE2ESealedGraphCommand sealed,
      Map<String, Set<String>> visibleFieldsByNode,
      Consumer<AgentStreamEvent> eventSink,
      AgentRunCancellationToken cancellationToken) {
    Objects.requireNonNull(sealed, "sealed");
    Objects.requireNonNull(visibleFieldsByNode, "visibleFieldsByNode");
    Objects.requireNonNull(eventSink, "eventSink");
    Objects.requireNonNull(cancellationToken, "cancellationToken").throwIfCancellationRequested();
    TargetE2EGraphCommandEnvelope envelope = sealed.envelope();
    var protocolState =
        new AgentNdjsonStreamClient.V2ProtocolState(
            envelope.command().logicalRunId(),
            envelope.command().attemptId(),
            envelope.command().actorScope().audience(),
            visibleFieldsByNode);
    StreamSession session = new StreamSession(envelope, protocolState, eventSink);
    GraphCommandHttpTransport.Request request =
        new GraphCommandHttpTransport.Request(
            endpoint,
            requestHeaders(sealed),
            sealed.body(),
            timeout,
            GraphCommandHttpTransport.MAXIMUM_LINE_BYTES,
            GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES);
    try {
      transport.stream(request, cancellationToken, session);
      cancellationToken.throwIfCancellationRequested();
      session.requireComplete();
    } catch (TargetE2EGraphClientException exception) {
      if ("TARGET_E2E_GRAPH_PROTOCOL_REJECTED".equals(exception.errorCode())) {
        session.logProtocolRejection();
      }
      throw exception;
    } catch (GraphCommandTransportException exception) {
      cancellationToken.throwIfCancellationRequested();
      if (exception.protocolViolation()) {
        throw protocol(session,
            "target Graph command transport violated the protocol", exception);
      }
      throw TargetE2EGraphClientException.transport(
          "target Graph command transport failed", exception);
    } catch (AgentStreamProtocolException | IllegalArgumentException exception) {
      throw protocol(session,
          "target Graph command stream is invalid", exception);
    } catch (RuntimeException exception) {
      cancellationToken.throwIfCancellationRequested();
      throw TargetE2EGraphClientException.transport(
          "target Graph command transport failed", exception);
    }

    AgentStreamEvent terminal = session.terminal();
    if (terminal.eventType() == StreamEventType.ERROR) {
      Boolean retryable = terminal.payload().retryable();
      String code = terminal.payload().errorCode();
      if (retryable == null || code == null || code.isBlank()) {
        throw protocol(session,
            "target Graph error terminal is incomplete", null);
      }
      throw TargetE2EGraphClientException.remote(
          code, retryable, "Python rejected target Graph execution");
    }
    if (terminal.eventType() == StreamEventType.ATTEMPT_ABORTED) {
      String reasonCode = terminal.payload().reasonCode();
      if (reasonCode == null || reasonCode.isBlank()) {
        throw protocol(session,
            "target Graph attempt-aborted terminal is incomplete", null);
      }
      throw TargetE2EGraphClientException.attemptAborted(reasonCode);
    }
    if (terminal.eventType() != StreamEventType.FINAL) {
      throw protocol(session,
          "target Graph command did not produce a final proposal", null);
    }
    return reconciliationClient.reconcile(
        sealed,
        terminal.payload().finalResultRef(),
        terminal.payload().finalResultHash(),
        cancellationToken);
  }

  private static TargetE2EGraphClientException protocol(
      StreamSession session, String message, Throwable cause) {
    session.logProtocolRejection();
    return TargetE2EGraphClientException.protocol(message, cause);
  }

  private Map<String, String> requestHeaders(TargetE2ESealedGraphCommand sealed) {
    Map<String, String> headers =
        Map.of(
            "Authorization", "Bearer " + sealed.credential().compactJws(),
            "Accept", "application/x-ndjson",
            "Content-Type", "application/json; charset=utf-8",
            "Content-Encoding", "identity",
            "Cache-Control", "no-store",
            "X-Agent-Run-Id", sealed.envelope().command().logicalRunId(),
            "traceparent", sealed.envelope().command().traceparent());
    if (headers.keySet().stream()
        .anyMatch(
            name ->
                HttpTargetE2EGraphReconciliationClient.ACTIVATION_HEADER.equalsIgnoreCase(name))) {
      throw new IllegalStateException("activation JWS header must never reach Graph");
    }
    return headers;
  }

  private final class StreamSession implements GraphCommandHttpTransport.Listener {

    private final TargetE2EGraphCommandEnvelope envelope;
    private final AgentNdjsonStreamClient.V2ProtocolState protocolState;
    private final Consumer<AgentStreamEvent> sink;
    private boolean responseReceived;
    private AgentStreamEvent terminal;
    private long lastAcceptedSequence = -1;
    private ProtocolLineMetadata lastLineMetadata = ProtocolLineMetadata.unavailable();

    private StreamSession(
        TargetE2EGraphCommandEnvelope envelope,
        AgentNdjsonStreamClient.V2ProtocolState protocolState,
        Consumer<AgentStreamEvent> sink) {
      this.envelope = envelope;
      this.protocolState = protocolState;
      this.sink = sink;
    }

    @Override
    public void onResponse(GraphCommandHttpTransport.ResponseHead response) {
      if (responseReceived) {
        throw TargetE2EGraphClientException.protocol(
            "target Graph command received duplicate response metadata", null);
      }
      responseReceived = true;
      if (response.statusCode() >= 300 && response.statusCode() <= 399) {
        throw TargetE2EGraphClientException.protocol(
            "target Graph command redirect is forbidden", null);
      }
      if (response.statusCode() != 200) {
        boolean retryable = Set.of(409, 429, 503).contains(response.statusCode());
        throw TargetE2EGraphClientException.remote(
            "TARGET_E2E_GRAPH_HTTP_" + response.statusCode(),
            retryable,
            "Python rejected target Graph command");
      }
      requireResponseMetadata(response, envelope);
    }

    @Override
    public void onLine(String line) {
      lastLineMetadata = safeProtocolLineMetadata(line);
      if (!responseReceived) {
        throw TargetE2EGraphClientException.protocol(
            "target Graph command emitted data before response metadata", null);
      }
      AgentStreamEvent event =
          AgentNdjsonStreamClient.parseV2Line(
              mapper, Objects.requireNonNull(line, "line"), protocolState);
      if (terminal != null) {
        throw TargetE2EGraphClientException.protocol(
            "target Graph command emitted data after terminal", null);
      }
      lastAcceptedSequence = event.sequenceNo();
      sink.accept(event);
      if (event.eventType() == StreamEventType.FINAL
          || event.eventType() == StreamEventType.ERROR
          || event.eventType() == StreamEventType.ATTEMPT_ABORTED) {
        terminal = event;
      }
    }

    private void requireComplete() {
      if (!responseReceived || terminal == null) {
        throw TargetE2EGraphClientException.protocol(
            "target Graph command stream ended without a terminal event", null);
      }
      protocolState.assertComplete();
    }

    private AgentStreamEvent terminal() {
      return terminal;
    }

    private void logProtocolRejection() {
      LOG.warn(
          "target_e2e_graph_protocol_rejected run_id={} attempt_id={} last_accepted_sequence={} line_bytes={} event_type={} field={}",
          envelope.command().logicalRunId(),
          envelope.command().attemptId(),
          lastAcceptedSequence,
          lastLineMetadata.byteSize(),
          lastLineMetadata.eventType(),
          lastLineMetadata.field());
    }

    private ProtocolLineMetadata safeProtocolLineMetadata(String line) {
      if (line == null) {
        return ProtocolLineMetadata.unavailable();
      }
      int byteSize = line.getBytes(StandardCharsets.UTF_8).length;
      try {
        JsonNode root = mapper.readTree(line);
        if (root == null || !root.isObject()) {
          return new ProtocolLineMetadata(byteSize, DIAGNOSTIC_UNAVAILABLE, DIAGNOSTIC_UNAVAILABLE);
        }
        String eventType = safeEventType(root.path("event_type"));
        if (!"visible_delta".equals(eventType)) {
          return new ProtocolLineMetadata(byteSize, eventType, DIAGNOSTIC_NOT_APPLICABLE);
        }
        JsonNode payload = root.path("payload");
        return new ProtocolLineMetadata(
            byteSize, eventType, safeAllowedVisibleField(payload));
      } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
        return new ProtocolLineMetadata(byteSize, DIAGNOSTIC_UNAVAILABLE, DIAGNOSTIC_UNAVAILABLE);
      }
    }

    private static String safeEventType(JsonNode value) {
      if (!value.isTextual()) {
        return DIAGNOSTIC_UNAVAILABLE;
      }
      try {
        return StreamEventType.fromWire(value.asText()).wireValue();
      } catch (IllegalArgumentException ignored) {
        return DIAGNOSTIC_UNAVAILABLE;
      }
    }

    private static String safeIdentifier(JsonNode value) {
      if (!value.isTextual()) {
        return DIAGNOSTIC_UNAVAILABLE;
      }
      String identifier = value.asText();
      return identifier.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
          ? identifier
          : DIAGNOSTIC_UNAVAILABLE;
    }

    private String safeAllowedVisibleField(JsonNode payload) {
      String node = safeIdentifier(payload.path("node"));
      String field = safeIdentifier(payload.path("field"));
      if (DIAGNOSTIC_UNAVAILABLE.equals(node)
          || DIAGNOSTIC_UNAVAILABLE.equals(field)
          || !protocolState.allowsVisibleField(node, field)) {
        return DIAGNOSTIC_UNAVAILABLE;
      }
      return field;
    }
  }

  private record ProtocolLineMetadata(int byteSize, String eventType, String field) {
    private static ProtocolLineMetadata unavailable() {
      return new ProtocolLineMetadata(-1, DIAGNOSTIC_UNAVAILABLE, DIAGNOSTIC_UNAVAILABLE);
    }
  }

  private void requireResponseMetadata(
      GraphCommandHttpTransport.ResponseHead response, TargetE2EGraphCommandEnvelope envelope) {
    List<String> contentType = headerValues(response.headers(), "content-type");
    List<String> contentEncoding = headerValues(response.headers(), "content-encoding");
    List<String> runId = headerValues(response.headers(), "x-agent-run-id");
    List<String> lanes = headerValues(response.headers(), "x-graph-execution-lane");
    List<String> activationIds = headerValues(response.headers(), "x-graph-activation-id");
    Set<String> cacheDirectives =
        headerValues(response.headers(), "cache-control").stream()
            .flatMap(value -> List.of(value.split(",", -1)).stream())
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    boolean contentTypeValid = contentType.size() == 1 && ndjsonUtf8(contentType.getFirst());
    if (!endpoint.equals(response.uri())
        || !contentTypeValid
        || contentEncoding.size() > 1
        || (contentEncoding.size() == 1
            && !"identity".equalsIgnoreCase(contentEncoding.getFirst().trim()))
        || !cacheDirectives.contains("no-store")
        || !runId.equals(List.of(envelope.command().logicalRunId()))
        || !lanes.equals(List.of(envelope.executionLane()))
        || !activationIds.equals(List.of(envelope.activationId()))) {
      throw TargetE2EGraphClientException.protocol(
          "target Graph command response metadata is invalid", null);
    }
  }

  private static List<String> headerValues(Map<String, List<String>> headers, String expectedName) {
    List<String> values = new ArrayList<>();
    headers.forEach(
        (name, candidates) -> {
          if (name.equalsIgnoreCase(expectedName)) {
            values.addAll(candidates);
          }
        });
    return List.copyOf(values);
  }

  private static boolean ndjsonUtf8(String value) {
    String[] parts = value.split(";", -1);
    if (!"application/x-ndjson".equalsIgnoreCase(parts[0].trim())) {
      return false;
    }
    return parts.length == 1
        || (parts.length == 2 && "charset=utf-8".equalsIgnoreCase(parts[1].trim()));
  }
}
