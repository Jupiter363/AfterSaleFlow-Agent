package com.example.dispute.workflow.targete2e.graph;

import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.ACTIVATION_ID;
import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.MAPPER;
import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.REGISTRY_BINDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationHttpTransport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpTargetE2EGraphProposalClientTest {

  private static final URI COMMAND_ENDPOINT =
      URI.create("https://python-agent.internal/base/internal/graphs/target-e2e/commands/stream");
  private static final URI RECONCILE_ENDPOINT =
      URI.create(
          "https://python-agent.internal/base/internal/graphs/target-e2e/commands/reconcile");
  private static final String COMPACT_JWS =
      "e30.e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);

  @Test
  void usesTargetOnlyPathsAndReusesExactSealedBytesAndJwsForRetryAndReconciliation()
      throws Exception {
    var codec = TargetE2EGraphTestFixtures.codec();
    AtomicInteger signatures = new AtomicInteger();
    TargetE2ESealedGraphCommand sealed =
        codec.sealCommand(
            ACTIVATION_ID,
            7L,
            TargetE2EGraphTestFixtures.command(),
            REGISTRY_BINDING,
            (envelope, binding) -> {
              signatures.incrementAndGet();
              return credential();
            });
    var resultEnvelope =
        codec.wrapResult(
            sealed.envelope(),
            TargetE2EGraphTestFixtures.result(),
            TargetE2EGraphTestFixtures.proposalSource());
    byte[] resultBody =
        codec.encodeResult(
            resultEnvelope, sealed.envelope(), TargetE2EGraphTestFixtures.proposalSource());
    FakeReconciliationTransport reconciliationTransport =
        new FakeReconciliationTransport(resultBody);
    var reconciliationClient =
        new HttpTargetE2EGraphReconciliationClient(
            reconciliationTransport,
            codec,
            (resultRef, expectedProposalHash) -> TargetE2EGraphTestFixtures.proposalSourceBytes(),
            MAPPER,
            URI.create("https://python-agent.internal/base/"),
            Duration.ofSeconds(8));
    FakeCommandTransport commandTransport = new FakeCommandTransport(resultEnvelope.resultHash());
    var client =
        new HttpTargetE2EGraphProposalClient(
            commandTransport,
            reconciliationClient,
            MAPPER,
            URI.create("https://python-agent.internal/base/"),
            Duration.ofSeconds(8));
    List<AgentStreamEvent> events = new ArrayList<>();

    assertThat(client.execute(sealed, Map.of(), events::add, new AgentRunCancellationToken()))
        .isEqualTo(resultEnvelope);
    assertThat(client.execute(sealed, Map.of(), events::add, new AgentRunCancellationToken()))
        .isEqualTo(resultEnvelope);

    assertThat(signatures).hasValue(1);
    assertThat(commandTransport.requests).hasSize(2);
    assertThat(reconciliationTransport.requests).hasSize(2);
    assertThat(commandTransport.requests)
        .allSatisfy(
            request ->
                assertSealedRequest(
                    request.uri(), request.headers(), request.body(), sealed, COMMAND_ENDPOINT));
    assertThat(reconciliationTransport.requests)
        .allSatisfy(
            request ->
                assertSealedRequest(
                    request.uri(), request.headers(), request.body(), sealed, RECONCILE_ENDPOINT));
    assertThat(commandTransport.requests.getFirst().body())
        .isEqualTo(commandTransport.requests.getLast().body())
        .isEqualTo(reconciliationTransport.requests.getFirst().body());
    assertThat(commandTransport.requests.getFirst().headers().get("Authorization"))
        .isEqualTo(reconciliationTransport.requests.getFirst().headers().get("Authorization"));
    assertThat(commandTransport.requests.getFirst().maximumLineBytes())
        .isEqualTo(GraphCommandHttpTransport.MAXIMUM_LINE_BYTES);
    assertThat(commandTransport.requests.getFirst().maximumResponseBytes())
        .isEqualTo(GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES);
    assertThat(reconciliationTransport.requests.getFirst().maximumResponseBytes())
        .isEqualTo(131_072);
    assertThat(events).hasSize(4);
  }

  @Test
  void rejectsRedirectOrWrongTargetResponseBindingBeforePublishingEvents() {
    var codec = TargetE2EGraphTestFixtures.codec();
    TargetE2ESealedGraphCommand sealed =
        codec.sealCommand(
            ACTIVATION_ID,
            7L,
            TargetE2EGraphTestFixtures.command(),
            REGISTRY_BINDING,
            (envelope, binding) -> credential());
    TargetE2EGraphReconciliationClient noReconciliation =
        (command, ref, hash, token) -> {
          throw new AssertionError("must not reconcile");
        };
    List<AgentStreamEvent> events = new ArrayList<>();
    GraphCommandHttpTransport redirect =
        new FakeCommandTransport("0".repeat(64)) {
          @Override
          public void stream(
              Request request, AgentRunCancellationToken cancellationToken, Listener listener) {
            listener.onResponse(new ResponseHead(307, request.uri(), Map.of()));
          }
        };
    var redirectClient =
        new HttpTargetE2EGraphProposalClient(
            redirect,
            noReconciliation,
            MAPPER,
            URI.create("https://python-agent.internal/base/"),
            Duration.ofSeconds(8));

    assertThatThrownBy(
            () ->
                redirectClient.execute(
                    sealed, Map.of(), events::add, new AgentRunCancellationToken()))
        .isInstanceOf(TargetE2EGraphClientException.class)
        .extracting(exception -> ((TargetE2EGraphClientException) exception).recoveryAction())
        .isEqualTo(TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN);
    assertThat(events).isEmpty();

    GraphCommandHttpTransport wrongBinding =
        new FakeCommandTransport("0".repeat(64)) {
          @Override
          public void stream(
              Request request, AgentRunCancellationToken cancellationToken, Listener listener) {
            listener.onResponse(successHead(request.uri(), sealed, "p9act.v1." + "b".repeat(32)));
          }
        };
    var wrongBindingClient =
        new HttpTargetE2EGraphProposalClient(
            wrongBinding,
            noReconciliation,
            MAPPER,
            URI.create("https://python-agent.internal/base/"),
            Duration.ofSeconds(8));
    assertThatThrownBy(
            () ->
                wrongBindingClient.execute(
                    sealed, Map.of(), events::add, new AgentRunCancellationToken()))
        .isInstanceOf(TargetE2EGraphClientException.class);
    assertThat(events).isEmpty();
  }

  @Test
  void reconciliationRejectsRedirectsAndNeverLoadsProposalBytes() {
    var codec = TargetE2EGraphTestFixtures.codec();
    TargetE2ESealedGraphCommand sealed =
        codec.sealCommand(
            ACTIVATION_ID,
            7L,
            TargetE2EGraphTestFixtures.command(),
            REGISTRY_BINDING,
            (envelope, binding) -> credential());
    AtomicInteger proposalLoads = new AtomicInteger();
    GraphReconciliationHttpTransport redirect =
        (request, cancellationToken) ->
            new GraphReconciliationHttpTransport.Response(
                302,
                Map.of(
                    "Content-Type", List.of("application/json"),
                    "Cache-Control", List.of("no-store")),
                "{}".getBytes(StandardCharsets.UTF_8));
    var client =
        new HttpTargetE2EGraphReconciliationClient(
            redirect,
            codec,
            (resultRef, proposalHash) -> {
              proposalLoads.incrementAndGet();
              return TargetE2EGraphTestFixtures.proposalSourceBytes();
            },
            MAPPER,
            URI.create("https://python-agent.internal/base/"),
            Duration.ofSeconds(8));

    assertThatThrownBy(
            () ->
                client.reconcile(
                    sealed,
                    "urn:graph-result:1",
                    TargetE2EGraphTestFixtures.result().outputHash(),
                    new AgentRunCancellationToken()))
        .isInstanceOf(TargetE2EGraphClientException.class)
        .extracting(exception -> ((TargetE2EGraphClientException) exception).recoveryAction())
        .isEqualTo(TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN);
    assertThat(proposalLoads).hasValue(0);
  }

  private static void assertSealedRequest(
      URI uri,
      Map<String, String> headers,
      byte[] body,
      TargetE2ESealedGraphCommand sealed,
      URI expectedUri) {
    assertThat(uri).isEqualTo(expectedUri);
    assertThat(body).isEqualTo(sealed.body());
    assertThat(headers).containsEntry("Authorization", "Bearer " + COMPACT_JWS);
    assertThat(headers.keySet())
        .noneMatch(
            name ->
                HttpTargetE2EGraphReconciliationClient.ACTIVATION_HEADER.equalsIgnoreCase(name));
    assertThat(new String(body, StandardCharsets.UTF_8))
        .doesNotContain("target-e2e-activation+jwt");
  }

  private static TargetE2EGraphEnvelopeSigner.SignedEnvelope credential() {
    return new TargetE2EGraphEnvelopeSigner.SignedEnvelope(
        COMPACT_JWS,
        "java-invocation-es256-1",
        "target-command-jti-001",
        Instant.parse("2026-07-27T08:00:00Z"),
        Instant.parse("2026-07-27T08:00:45Z"));
  }

  private static GraphCommandHttpTransport.ResponseHead successHead(
      URI uri, TargetE2ESealedGraphCommand sealed, String activationId) {
    return new GraphCommandHttpTransport.ResponseHead(
        200,
        uri,
        Map.of(
            "Content-Type", List.of("application/x-ndjson; charset=utf-8"),
            "Cache-Control", List.of("no-store, no-transform"),
            "X-Agent-Run-Id", List.of(sealed.envelope().command().logicalRunId()),
            "X-Graph-Execution-Lane", List.of(sealed.envelope().executionLane()),
            "X-Graph-Activation-Id", List.of(activationId)));
  }

  private static String event(
      TargetE2ESealedGraphCommand sealed, long sequence, String eventType, String payload) {
    return "{"
        + "\"schema_version\":\"agent-stream.v2\","
        + "\"run_id\":\""
        + sealed.envelope().command().logicalRunId()
        + "\","
        + "\"attempt_id\":\""
        + sealed.envelope().command().attemptId()
        + "\","
        + "\"sequence_no\":"
        + sequence
        + ","
        + "\"event_type\":\""
        + eventType
        + "\","
        + "\"audience\":\"USER\","
        + "\"occurred_at\":\"2026-07-27T08:00:01Z\","
        + "\"payload\":"
        + payload
        + "}";
  }

  private static class FakeCommandTransport implements GraphCommandHttpTransport {

    private final String resultHash;
    private final List<Request> requests = new ArrayList<>();

    private FakeCommandTransport(String resultHash) {
      this.resultHash = resultHash;
    }

    @Override
    public void stream(
        Request request, AgentRunCancellationToken cancellationToken, Listener listener) {
      requests.add(request);
      var codec = TargetE2EGraphTestFixtures.codec();
      var decoded = codec.decodeCommand(request.body());
      TargetE2ESealedGraphCommand sealed =
          new TargetE2ESealedGraphCommand(decoded, request.body(), credential());
      listener.onResponse(successHead(request.uri(), sealed, sealed.envelope().activationId()));
      listener.onLine(event(sealed, 0, "attempt_started", "{\"node\":\"intake.reason\"}"));
      listener.onLine(
          event(
              sealed,
              1,
              "final",
              "{\"final_result_ref\":\"urn:graph-result:1\","
                  + "\"final_result_hash\":\""
                  + resultHash
                  + "\"}"));
    }
  }

  private static final class FakeReconciliationTransport
      implements GraphReconciliationHttpTransport {

    private final byte[] resultBody;
    private final List<Request> requests = new ArrayList<>();

    private FakeReconciliationTransport(byte[] resultBody) {
      this.resultBody = resultBody.clone();
    }

    @Override
    public Response exchange(Request request, AgentRunCancellationToken cancellationToken) {
      requests.add(request);
      return new Response(
          200,
          Map.of(
              "Content-Type", List.of("application/json; charset=utf-8"),
              "Cache-Control", List.of("no-store")),
          resultBody);
    }
  }
}
