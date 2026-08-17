package com.example.dispute.workflow.targete2e.graph;

import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.ACTIVATION_ID;
import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.MAPPER;
import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.REGISTRY_BINDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandTransportException;
import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import com.example.dispute.workflow.infrastructure.agent.LocalGraphTransportFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class HttpTargetE2EGraphProposalClientTest {

  private static final URI COMMAND_ENDPOINT =
      URI.create("https://python-agent.internal/base/internal/graphs/target-e2e/commands/stream");
  private static final URI RECONCILE_ENDPOINT =
      URI.create(
          "https://python-agent.internal/base/internal/graphs/target-e2e/commands/reconcile");
  private static final String COMPACT_JWS =
      "e30.e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
  private static final String EXECUTION_PROVIDER = "target-e2e-composite";
  private static final String EXECUTION_MODEL = "room-provider-dispatch";

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
            TargetE2EGraphTestFixtures.proposalSource(),
            EXECUTION_PROVIDER,
            EXECUTION_MODEL);
    byte[] resultBody =
        codec.encodeResult(
            resultEnvelope, sealed.envelope(), TargetE2EGraphTestFixtures.proposalSource());
    GraphTransportSecurityProof proof = mutualTlsProof();
    FakeReconciliationTransport reconciliationTransport =
        new FakeReconciliationTransport(resultBody, proof);
    FakeCommandTransport commandTransport =
        new FakeCommandTransport(resultEnvelope.resultHash(), proof);
    GraphTransportBundle transportBundle = bundle(commandTransport, reconciliationTransport, proof);
    var reconciliationClient =
        new HttpTargetE2EGraphReconciliationClient(
            transportBundle,
            codec,
            (command, resultRef, expectedProposalHash, cancellationToken) ->
                TargetE2EGraphTestFixtures.proposalSourceBytes(),
            MAPPER,
            URI.create("https://python-agent.internal/base/"),
            Duration.ofSeconds(8));
    var client =
        new HttpTargetE2EGraphProposalClient(
            transportBundle,
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
  void reconciliationClientPreservesDeterministic409AndRejectsIncompatibleErrorEnvelopes() {
    var codec = TargetE2EGraphTestFixtures.codec();
    TargetE2ESealedGraphCommand sealed =
        codec.sealCommand(
            ACTIVATION_ID,
            7L,
            TargetE2EGraphTestFixtures.command(),
            REGISTRY_BINDING,
            (envelope, binding) -> credential());
    GraphTransportSecurityProof proof = mutualTlsProof();
    record ErrorScenario(
        int status,
        String body,
        String expectedCode,
        TargetE2EGraphClientException.RecoveryAction expectedAction) {}
    List<ErrorScenario> scenarios =
        List.of(
            new ErrorScenario(
                409,
                "{\"code\":\"GRAPH_TERMINAL_BINDING_CONFLICT\",\"retryable\":false}",
                "GRAPH_TERMINAL_BINDING_CONFLICT",
                TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN),
            new ErrorScenario(
                409,
                "{\"code\":\"GRAPH_LEASE_UNAVAILABLE\",\"retryable\":true}",
                "GRAPH_LEASE_UNAVAILABLE",
                TargetE2EGraphClientException.RecoveryAction.RETRY_SAME_SEALED_COMMAND),
            new ErrorScenario(
                400,
                "{\"code\":\"INVOCATION_AUTHORIZATION_REJECTED\",\"retryable\":false}",
                "INVOCATION_AUTHORIZATION_REJECTED",
                TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN),
            new ErrorScenario(
                502,
                "{\"code\":\"TARGET_E2E_RESULT_ENVELOPE_REJECTED\",\"retryable\":false}",
                "TARGET_E2E_RESULT_ENVELOPE_REJECTED",
                TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN),
            new ErrorScenario(
                429,
                "{\"code\":\"GRAPH_LEASE_UNAVAILABLE\",\"retryable\":false}",
                "TARGET_E2E_GRAPH_PROTOCOL_REJECTED",
                TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN),
            new ErrorScenario(
                503,
                "{\"code\":\"GRAPH_LEASE_UNAVAILABLE\",\"retryable\":false}",
                "TARGET_E2E_GRAPH_PROTOCOL_REJECTED",
                TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN),
            new ErrorScenario(
                409,
                "{\"code\":\"GRAPH_TERMINAL_BINDING_CONFLICT\",\"retryable\":false,\"detail\":\"forbidden\"}",
                "TARGET_E2E_GRAPH_PROTOCOL_REJECTED",
                TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN),
            new ErrorScenario(
                409,
                "{\"code\":7,\"retryable\":false}",
                "TARGET_E2E_GRAPH_PROTOCOL_REJECTED",
                TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN),
            new ErrorScenario(
                409,
                "{\"code\":\"GRAPH_TERMINAL_BINDING_CONFLICT\",\"retryable\":false} {}",
                "TARGET_E2E_GRAPH_PROTOCOL_REJECTED",
                TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN),
            new ErrorScenario(
                409,
                "{\"code\":\"GRAPH_TERMINAL_BINDING_CONFLICT\",\"code\":\"OTHER\",\"retryable\":false}",
                "TARGET_E2E_GRAPH_PROTOCOL_REJECTED",
                TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN));

    for (ErrorScenario scenario : scenarios) {
      AtomicInteger exchanges = new AtomicInteger();
      AtomicInteger proposalLoads = new AtomicInteger();
      GraphReconciliationHttpTransport reconciliation =
          errorReconciliationTransport(proof, scenario.status(), scenario.body(), exchanges);
      var client =
          new HttpTargetE2EGraphReconciliationClient(
              bundle(new FakeCommandTransport("0".repeat(64), proof), reconciliation, proof),
              codec,
              (ignoredSealed, resultRef, proposalHash, cancellationToken) -> {
                proposalLoads.incrementAndGet();
                return TargetE2EGraphTestFixtures.proposalSourceBytes();
              },
              MAPPER,
              URI.create("https://python-agent.internal/base/"),
              Duration.ofSeconds(8));

      assertThatThrownBy(
              () -> client.reconcileAvailable(sealed, new AgentRunCancellationToken()))
          .isInstanceOfSatisfying(
              TargetE2EGraphClientException.class,
              failure -> {
                assertThat(failure.errorCode()).isEqualTo(scenario.expectedCode());
                assertThat(failure.recoveryAction()).isEqualTo(scenario.expectedAction());
              });
      assertThat(exchanges).hasValue(1);
      assertThat(proposalLoads).hasValue(0);
    }
  }

  @Test
  void publishesAttemptAbortedTerminalBeforeRequiringTheNextAgentAttempt() {
    var codec = TargetE2EGraphTestFixtures.codec();
    TargetE2ESealedGraphCommand sealed =
        codec.sealCommand(
            ACTIVATION_ID,
            7L,
            TargetE2EGraphTestFixtures.command(),
            REGISTRY_BINDING,
            (envelope, binding) -> credential());
    GraphTransportSecurityProof proof = mutualTlsProof();
    GraphCommandHttpTransport aborted =
        new FakeCommandTransport("0".repeat(64), proof) {
          @Override
          public void stream(
              Request request, AgentRunCancellationToken cancellationToken, Listener listener) {
            listener.onResponse(
                successHead(request.uri(), sealed, sealed.envelope().activationId()));
            listener.onLine(
                event(sealed, 0, "attempt_started", "{\"node\":\"intake.reason\"}"));
            listener.onLine(
                event(
                    sealed,
                    1,
                    "attempt_aborted",
                    "{\"reason_code\":\"GRAPH_LEASE_LOST\"}"));
          }
        };
    FakeReconciliationTransport reconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), proof);
    var client = proposalClient(aborted, reconciliation, proof, codec);
    List<AgentStreamEvent> events = new ArrayList<>();

    assertThatThrownBy(
            () ->
                client.execute(
                    sealed, Map.of(), events::add, new AgentRunCancellationToken()))
        .isInstanceOfSatisfying(
            TargetE2EGraphClientException.class,
            failure -> {
              assertThat(failure.errorCode()).isEqualTo("GRAPH_LEASE_LOST");
              assertThat(failure.recoveryAction())
                  .isEqualTo(TargetE2EGraphClientException.RecoveryAction.CREATE_NEXT_ATTEMPT);
            });

    assertThat(events).extracting(AgentStreamEvent::eventType)
        .containsExactly(StreamEventType.ATTEMPT_STARTED, StreamEventType.ATTEMPT_ABORTED);
    assertThat(events.getLast().payload().reasonCode()).isEqualTo("GRAPH_LEASE_LOST");
    assertThat(reconciliation.requests).isEmpty();
  }

  @Test
  void nonRetryableGraphErrorEnvelopeClosesTheLogicalRunWithoutSealedCommandReplay() {
    var codec = TargetE2EGraphTestFixtures.codec();
    TargetE2ESealedGraphCommand sealed =
        codec.sealCommand(
            ACTIVATION_ID,
            7L,
            TargetE2EGraphTestFixtures.command(),
            REGISTRY_BINDING,
            (envelope, binding) -> credential());
    AtomicInteger invocations = new AtomicInteger();
    GraphTransportSecurityProof proof = mutualTlsProof();
    GraphCommandHttpTransport rejected =
        new FakeCommandTransport("0".repeat(64), proof) {
          @Override
          public void stream(
              Request request, AgentRunCancellationToken cancellationToken, Listener listener) {
            invocations.incrementAndGet();
            listener.onResponse(errorHead(409, request.uri()));
            listener.onLine(
                "{\"code\":\"GRAPH_RETRY_BUDGET_EXHAUSTED\",\"retryable\":false}");
          }
        };
    FakeReconciliationTransport reconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), proof);
    var client = proposalClient(rejected, reconciliation, proof, codec);
    List<AgentStreamEvent> events = new ArrayList<>();

    assertThatThrownBy(
            () ->
                client.execute(
                    sealed, Map.of(), events::add, new AgentRunCancellationToken()))
        .isInstanceOfSatisfying(
            TargetE2EGraphClientException.class,
            failure -> {
              assertThat(failure.errorCode()).isEqualTo("GRAPH_RETRY_BUDGET_EXHAUSTED");
              assertThat(failure.recoveryAction())
                  .isEqualTo(TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN);
            });
    assertThat(invocations).hasValue(1);
    assertThat(events).isEmpty();
    assertThat(reconciliation.requests).isEmpty();
  }

  @Test
  void retryableAndInvalidGraphErrorResponsesPreserveClosedRecoverySemantics() {
    var codec = TargetE2EGraphTestFixtures.codec();
    TargetE2ESealedGraphCommand sealed =
        codec.sealCommand(
            ACTIVATION_ID,
            7L,
            TargetE2EGraphTestFixtures.command(),
            REGISTRY_BINDING,
            (envelope, binding) -> credential());
    GraphTransportSecurityProof proof = mutualTlsProof();
    AtomicInteger retryInvocations = new AtomicInteger();
    GraphCommandHttpTransport retryable =
        errorTransport(
            proof,
            409,
            "application/json; charset=utf-8",
            List.of("{\"code\":\"GRAPH_GATEWAY_NOT_READY\",\"retryable\":true}"),
            retryInvocations);
    FakeReconciliationTransport retryReconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), proof);
    var retryClient = proposalClient(retryable, retryReconciliation, proof, codec);
    List<AgentStreamEvent> events = new ArrayList<>();

    assertThatThrownBy(
            () ->
                retryClient.execute(
                    sealed, Map.of(), events::add, new AgentRunCancellationToken()))
        .isInstanceOfSatisfying(
            TargetE2EGraphClientException.class,
            failure -> {
              assertThat(failure.errorCode()).isEqualTo("GRAPH_GATEWAY_NOT_READY");
              assertThat(failure.recoveryAction())
                  .isEqualTo(
                      TargetE2EGraphClientException.RecoveryAction.RETRY_SAME_SEALED_COMMAND);
            });
    assertThat(retryInvocations).hasValue(1);
    assertThat(events).isEmpty();
    assertThat(retryReconciliation.requests).isEmpty();

    AtomicInteger non409Invocations = new AtomicInteger();
    FakeReconciliationTransport non409Reconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), proof);
    var non409Client =
        proposalClient(
            errorTransport(
                proof,
                401,
                "application/json",
                List.of("{\"code\":\"INVOCATION_AUTHORIZATION_REJECTED\",\"retryable\":false}"),
                non409Invocations),
            non409Reconciliation,
            proof,
            codec);
    assertThatThrownBy(
            () ->
                non409Client.execute(
                    sealed, Map.of(), events::add, new AgentRunCancellationToken()))
        .isInstanceOfSatisfying(
            TargetE2EGraphClientException.class,
            failure -> {
              assertThat(failure.errorCode()).isEqualTo("INVOCATION_AUTHORIZATION_REJECTED");
              assertThat(failure.recoveryAction())
                  .isEqualTo(TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN);
            });
    assertThat(non409Invocations).hasValue(1);
    assertThat(events).isEmpty();
    assertThat(non409Reconciliation.requests).isEmpty();

    String privateBodyValue = "private-error-detail-must-not-be-logged";
    record InvalidErrorResponse(int status, String contentType, List<String> lines) {}
    List<InvalidErrorResponse> invalidResponses =
        List.of(
            new InvalidErrorResponse(409, "application/json", List.of("not-json")),
            new InvalidErrorResponse(
                409,
                "application/json",
                List.of(
                    "{\"code\":\"GRAPH_RETRY_BUDGET_EXHAUSTED\",\"retryable\":false,"
                        + "\"detail\":\""
                        + privateBodyValue
                        + "\"}")),
            new InvalidErrorResponse(
                409,
                "application/json",
                List.of("{\"code\":\"GRAPH_RETRY_BUDGET_EXHAUSTED\"}")),
            new InvalidErrorResponse(
                409,
                "application/json",
                List.of("{\"code\":7,\"retryable\":false}")),
            new InvalidErrorResponse(
                409,
                "application/json",
                List.of("{\"code\":\"invalid code\",\"retryable\":false}")),
            new InvalidErrorResponse(
                409,
                "application/json",
                List.of(
                    "{\"code\":\"GRAPH_RETRY_BUDGET_EXHAUSTED\",\"retryable\":false}"
                        + " {\"code\":\"GRAPH_GATEWAY_NOT_READY\",\"retryable\":true}")),
            new InvalidErrorResponse(
                409,
                "application/json",
                List.of(
                    "{\"code\":\"GRAPH_GATEWAY_NOT_READY\","
                        + "\"code\":\"GRAPH_RETRY_BUDGET_EXHAUSTED\","
                        + "\"retryable\":false}")),
            new InvalidErrorResponse(
                409,
                "application/json",
                List.of(
                    "{\"code\":\"GRAPH_GATEWAY_NOT_READY\","
                        + "\"retryable\":true,\"retryable\":false}")),
            new InvalidErrorResponse(
                409,
                "text/plain",
                List.of("{\"code\":\"GRAPH_RETRY_BUDGET_EXHAUSTED\",\"retryable\":false}")),
            new InvalidErrorResponse(
                204,
                "application/json",
                List.of("{\"code\":\"GRAPH_RETRY_BUDGET_EXHAUSTED\",\"retryable\":false}")),
            new InvalidErrorResponse(409, "application/json", List.of()),
            new InvalidErrorResponse(
                409,
                "application/json",
                List.of(
                    "{\"code\":\"GRAPH_GATEWAY_NOT_READY\",\"retryable\":true}",
                    "{\"code\":\"GRAPH_GATEWAY_NOT_READY\",\"retryable\":true}")));
    Logger logger = (Logger) LoggerFactory.getLogger(HttpTargetE2EGraphProposalClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      for (InvalidErrorResponse invalid : invalidResponses) {
        AtomicInteger invocations = new AtomicInteger();
        FakeReconciliationTransport reconciliation =
            new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), proof);
        var client =
            proposalClient(
                errorTransport(
                    proof,
                    invalid.status(),
                    invalid.contentType(),
                    invalid.lines(),
                    invocations),
                reconciliation,
                proof,
                codec);

        assertThatThrownBy(
                () ->
                    client.execute(
                        sealed, Map.of(), events::add, new AgentRunCancellationToken()))
            .isInstanceOfSatisfying(
                TargetE2EGraphClientException.class,
                failure -> {
                  assertThat(failure.errorCode()).isEqualTo("TARGET_E2E_GRAPH_PROTOCOL_REJECTED");
                  assertThat(failure.recoveryAction())
                      .isEqualTo(TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN);
                });
        assertThat(invocations).hasValue(1);
        assertThat(events).isEmpty();
        assertThat(reconciliation.requests).isEmpty();
      }

      AtomicInteger oversizedInvocations = new AtomicInteger();
      GraphCommandHttpTransport oversized =
          new FakeCommandTransport("0".repeat(64), proof) {
            @Override
            public void stream(
                Request request, AgentRunCancellationToken cancellationToken, Listener listener) {
              oversizedInvocations.incrementAndGet();
              throw GraphCommandTransportException.protocolViolation(
                  "Graph command response line exceeds its byte limit");
            }
          };
      FakeReconciliationTransport oversizedReconciliation =
          new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), proof);
      var oversizedClient =
          proposalClient(oversized, oversizedReconciliation, proof, codec);
      assertThatThrownBy(
              () ->
                  oversizedClient.execute(
                      sealed, Map.of(), events::add, new AgentRunCancellationToken()))
          .isInstanceOfSatisfying(
              TargetE2EGraphClientException.class,
              failure -> {
                assertThat(failure.errorCode()).isEqualTo("TARGET_E2E_GRAPH_PROTOCOL_REJECTED");
                assertThat(failure.recoveryAction())
                    .isEqualTo(TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN);
              });
      assertThat(oversizedInvocations).hasValue(1);
      assertThat(events).isEmpty();
      assertThat(oversizedReconciliation.requests).isEmpty();
      assertThat(appender.list)
          .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(privateBodyValue));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void logsBoundedProtocolDiagnosticsWithoutStreamingModelContent() {
    var codec = TargetE2EGraphTestFixtures.codec();
    TargetE2ESealedGraphCommand sealed =
        codec.sealCommand(
            ACTIVATION_ID,
            7L,
            TargetE2EGraphTestFixtures.command(),
            REGISTRY_BINDING,
            (envelope, binding) -> credential());
    GraphTransportSecurityProof proof = mutualTlsProof();
    String secretDelta = "model-content-must-not-reach-diagnostics";
    String rejectedLine =
        event(
            sealed,
            1,
            "visible_delta",
            "{\"node\":\"intake.reason\",\"field\":\"reasoning_content\",\"delta\":\""
                + secretDelta
                + "\"}");
    GraphCommandHttpTransport rejecting =
        new FakeCommandTransport("0".repeat(64), proof) {
          @Override
          public void stream(
              Request request, AgentRunCancellationToken cancellationToken, Listener listener) {
            listener.onResponse(
                successHead(request.uri(), sealed, sealed.envelope().activationId()));
            listener.onLine(
                event(sealed, 0, "attempt_started", "{\"node\":\"intake.reason\"}"));
            listener.onLine(rejectedLine);
          }
        };
    FakeReconciliationTransport reconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), proof);
    var client = proposalClient(rejecting, reconciliation, proof, codec);
    Logger logger = (Logger) LoggerFactory.getLogger(HttpTargetE2EGraphProposalClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThatThrownBy(
              () ->
                  client.execute(
                      sealed,
                      Map.of("intake.reason", Set.of("room_utterance")),
                      ignored -> {},
                      new AgentRunCancellationToken()))
          .isInstanceOfSatisfying(
              TargetE2EGraphClientException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo("TARGET_E2E_GRAPH_PROTOCOL_REJECTED"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(appender.list).hasSize(1);
    String diagnostic = appender.list.getFirst().getFormattedMessage();
    assertThat(diagnostic)
        .contains("target_e2e_graph_protocol_rejected")
        .contains("run_id=" + sealed.envelope().command().logicalRunId())
        .contains("attempt_id=" + sealed.envelope().command().attemptId())
        .contains("last_accepted_sequence=0")
        .contains("line_bytes=" + rejectedLine.getBytes(StandardCharsets.UTF_8).length)
        .contains("event_type=visible_delta")
        .contains("field=UNAVAILABLE")
        .doesNotContain(secretDelta)
        .doesNotContain("reasoning_content");
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
    List<AgentStreamEvent> events = new ArrayList<>();
    GraphTransportSecurityProof redirectProof = mutualTlsProof();
    GraphCommandHttpTransport redirect =
        new FakeCommandTransport("0".repeat(64), redirectProof) {
          @Override
          public void stream(
              Request request, AgentRunCancellationToken cancellationToken, Listener listener) {
            listener.onResponse(new ResponseHead(307, request.uri(), Map.of()));
          }
        };
    FakeReconciliationTransport redirectReconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), redirectProof);
    var redirectClient = proposalClient(redirect, redirectReconciliation, redirectProof, codec);

    assertThatThrownBy(
            () ->
                redirectClient.execute(
                    sealed, Map.of(), events::add, new AgentRunCancellationToken()))
        .isInstanceOf(TargetE2EGraphClientException.class)
        .extracting(exception -> ((TargetE2EGraphClientException) exception).recoveryAction())
        .isEqualTo(TargetE2EGraphClientException.RecoveryAction.FAIL_LOGICAL_RUN);
    assertThat(events).isEmpty();
    assertThat(redirectReconciliation.requests).isEmpty();

    GraphTransportSecurityProof wrongBindingProof = mutualTlsProof();
    GraphCommandHttpTransport wrongBinding =
        new FakeCommandTransport("0".repeat(64), wrongBindingProof) {
          @Override
          public void stream(
              Request request, AgentRunCancellationToken cancellationToken, Listener listener) {
            listener.onResponse(successHead(request.uri(), sealed, "p9act.v1." + "b".repeat(32)));
          }
        };
    FakeReconciliationTransport wrongBindingReconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), wrongBindingProof);
    var wrongBindingClient =
        proposalClient(wrongBinding, wrongBindingReconciliation, wrongBindingProof, codec);
    assertThatThrownBy(
            () ->
                wrongBindingClient.execute(
                    sealed, Map.of(), events::add, new AgentRunCancellationToken()))
        .isInstanceOf(TargetE2EGraphClientException.class);
    assertThat(events).isEmpty();
    assertThat(wrongBindingReconciliation.requests).isEmpty();
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
    GraphTransportSecurityProof proof = mutualTlsProof();
    FakeCommandTransport unusedCommand = new FakeCommandTransport("0".repeat(64), proof);
    GraphReconciliationHttpTransport redirect =
        new GraphReconciliationHttpTransport() {
          @Override
          public GraphTransportSecurityProof transportProof() {
            return proof;
          }

          @Override
          public Response exchange(Request request, AgentRunCancellationToken cancellationToken) {
            return new Response(
                302,
                Map.of(
                    "Content-Type", List.of("application/json"),
                    "Cache-Control", List.of("no-store")),
                "{}".getBytes(StandardCharsets.UTF_8));
          }
        };
    GraphTransportBundle transportBundle = bundle(unusedCommand, redirect, proof);
    var client =
        new HttpTargetE2EGraphReconciliationClient(
            transportBundle,
            codec,
            (ignoredSealed, resultRef, proposalHash, cancellationToken) -> {
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

  @Test
  void reconciliationSendsTheExpiredOriginalCredentialWithoutResigning() throws Exception {
    var codec = TargetE2EGraphTestFixtures.codec();
    TargetE2EGraphCommandEnvelope envelope =
        codec.wrapCommand(ACTIVATION_ID, 7L, TargetE2EGraphTestFixtures.command());
    TargetE2ESealedGraphCommand sealed =
        new TargetE2ESealedGraphCommand(
            envelope, codec.encodeCommand(envelope), expiredCredential());
    TargetE2EGraphResultEnvelope result =
        codec.wrapResult(
            envelope,
            TargetE2EGraphTestFixtures.result(),
            TargetE2EGraphTestFixtures.proposalSource(),
            EXECUTION_PROVIDER,
            EXECUTION_MODEL);
    byte[] resultBody =
        codec.encodeResult(result, envelope, TargetE2EGraphTestFixtures.proposalSource());
    GraphTransportSecurityProof proof = mutualTlsProof();
    FakeCommandTransport command = new FakeCommandTransport(result.resultHash(), proof);
    FakeReconciliationTransport reconciliation = new FakeReconciliationTransport(resultBody, proof);
    GraphTransportBundle transportBundle = bundle(command, reconciliation, proof);
    var client =
        new HttpTargetE2EGraphReconciliationClient(
            transportBundle,
            codec,
            (sealedCommand, resultRef, proposalHash, cancellationToken) ->
                TargetE2EGraphTestFixtures.proposalSourceBytes(),
            MAPPER,
            URI.create("https://python-agent.internal/base/"),
            Duration.ofSeconds(8));

    assertThat(
            client.reconcile(
                sealed, "urn:graph-result:1", result.resultHash(), new AgentRunCancellationToken()))
        .isEqualTo(result);

    assertThat(sealed.credential().expiresAt()).isBefore(Instant.now());
    assertThat(reconciliation.requests)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.body()).isEqualTo(sealed.body());
              assertThat(request.headers())
                  .containsEntry("Authorization", "Bearer " + sealed.credential().compactJws());
            });
  }

  @Test
  void rejectsUnverifiedOrPlaintextTransportsBeforeAnyRequestOrBearerLeavesJava() {
    assertThat(TargetE2EGraphTransportPolicy.class.getDeclaredMethods())
        .filteredOn(method -> method.getName().equals("requireVerified"))
        .singleElement()
        .satisfies(
            method ->
                assertThat(method.getParameterTypes())
                    .containsExactly(GraphTransportBundle.class));
    assertThat(HttpTargetE2EGraphProposalClient.class.getConstructors())
        .singleElement()
        .satisfies(
            constructor ->
                assertThat(constructor.getParameterTypes())
                    .contains(GraphTransportBundle.class)
                    .doesNotContain(
                        GraphCommandHttpTransport.class,
                        GraphReconciliationHttpTransport.class));
    assertThat(HttpTargetE2EGraphReconciliationClient.class.getConstructors())
        .singleElement()
        .satisfies(
            constructor ->
                assertThat(constructor.getParameterTypes())
                    .contains(GraphTransportBundle.class)
                    .doesNotContain(
                        GraphCommandHttpTransport.class,
                        GraphReconciliationHttpTransport.class));

    AtomicBoolean proposalLoaded = new AtomicBoolean();
    GraphTransportBundle plaintext =
        LocalGraphTransportFactory.create(
            LocalGraphTransportFactory.Profile.TEST, Duration.ofMillis(100));
    assertThatThrownBy(
            () ->
                new HttpTargetE2EGraphReconciliationClient(
                    plaintext,
                    TargetE2EGraphTestFixtures.codec(),
                    (ignoredSealed, resultRef, proposalHash, cancellationToken) -> {
                      proposalLoaded.set(true);
                      return TargetE2EGraphTestFixtures.proposalSourceBytes();
                    },
                    MAPPER,
                    URI.create("https://python-agent.internal/"),
                    Duration.ofSeconds(8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLSv1.3 mutual-TLS");
    assertThat(proposalLoaded).isFalse();

    GraphTransportSecurityProof unboundProof = mutualTlsProof(null);
    GraphTransportBundle unboundBundle =
        bundle(
            new FakeCommandTransport("0".repeat(64), unboundProof),
            new FakeReconciliationTransport(
                "{}".getBytes(StandardCharsets.UTF_8), unboundProof),
            unboundProof);
    assertThatThrownBy(
            () ->
                new HttpTargetE2EGraphReconciliationClient(
                    unboundBundle,
                    TargetE2EGraphTestFixtures.codec(),
                    (ignoredSealed, resultRef, proposalHash, cancellationToken) ->
                        TargetE2EGraphTestFixtures.proposalSourceBytes(),
                    MAPPER,
                    URI.create("https://python-agent.internal/base/"),
                    Duration.ofSeconds(8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not bound to an HTTPS base URI");

    GraphTransportSecurityProof proof = mutualTlsProof();
    FakeCommandTransport unusedCommand = new FakeCommandTransport("0".repeat(64), proof);
    FakeReconciliationTransport unusedReconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), proof);
    GraphTransportBundle trustedBundle = bundle(unusedCommand, unusedReconciliation, proof);
    for (URI rejectedUri :
        List.of(
            URI.create("http://python-agent.internal/"),
            URI.create("https://user@python-agent.internal/"),
            URI.create("https://python-agent.internal/?target=elsewhere"),
            URI.create("https://python-agent.internal/#elsewhere"),
            URI.create("https://python-agent.internal/base/../elsewhere/"),
            URI.create("https://python-agent.internal/base/%2e%2e/elsewhere/"))) {
      assertThatThrownBy(
              () ->
                  new HttpTargetE2EGraphReconciliationClient(
                      trustedBundle,
                      TargetE2EGraphTestFixtures.codec(),
                      (ignoredSealed, resultRef, proposalHash, cancellationToken) ->
                          TargetE2EGraphTestFixtures.proposalSourceBytes(),
                      MAPPER,
                      rejectedUri,
                      Duration.ofSeconds(8)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("base URI is not trusted");
    }
    assertThat(unusedCommand.requests).isEmpty();
    assertThat(unusedReconciliation.requests).isEmpty();
  }

  @Test
  void rejectsDifferentBundlesProofsOrBaseUrisBeforeCommandDelivery() throws Exception {
    GraphTransportSecurityProof firstProof = mutualTlsProof();
    FakeCommandTransport firstCommand = new FakeCommandTransport("0".repeat(64), firstProof);
    FakeReconciliationTransport firstReconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), firstProof);
    GraphTransportBundle firstBundle = bundle(firstCommand, firstReconciliation, firstProof);
    var reconciliationClient =
        new HttpTargetE2EGraphReconciliationClient(
            firstBundle,
            TargetE2EGraphTestFixtures.codec(),
            (ignoredSealed, resultRef, proposalHash, cancellationToken) ->
                TargetE2EGraphTestFixtures.proposalSourceBytes(),
            MAPPER,
            URI.create("https://python-agent.internal/base/"),
            Duration.ofSeconds(8));

    GraphTransportSecurityProof secondProof = mutualTlsProof();
    FakeCommandTransport secondCommand = new FakeCommandTransport("0".repeat(64), secondProof);
    FakeReconciliationTransport secondReconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), secondProof);
    GraphTransportBundle secondBundle = bundle(secondCommand, secondReconciliation, secondProof);

    assertThatThrownBy(
            () ->
                new HttpTargetE2EGraphProposalClient(
                    secondBundle,
                    reconciliationClient,
                    MAPPER,
                    URI.create("https://python-agent.internal/base/"),
                    Duration.ofSeconds(8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("share one factory-issued transport bundle");

    FakeCommandTransport sameProofCommand =
        new FakeCommandTransport("0".repeat(64), firstProof);
    FakeReconciliationTransport sameProofReconciliation =
        new FakeReconciliationTransport("{}".getBytes(StandardCharsets.UTF_8), firstProof);
    GraphTransportBundle differentBundleWithSameProof =
        bundle(sameProofCommand, sameProofReconciliation, firstProof);
    assertThatThrownBy(
            () ->
                new HttpTargetE2EGraphProposalClient(
                    differentBundleWithSameProof,
                    reconciliationClient,
                    MAPPER,
                    URI.create("https://python-agent.internal/base/"),
                    Duration.ofSeconds(8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("share one factory-issued transport bundle");

    assertThatThrownBy(
            () ->
                new HttpTargetE2EGraphProposalClient(
                    firstBundle,
                    reconciliationClient,
                    MAPPER,
                    URI.create("https://other-python-agent.internal/base/"),
                    Duration.ofSeconds(8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("factory-bound mTLS endpoint");
    assertThat(secondCommand.requests).isEmpty();
    assertThat(sameProofCommand.requests).isEmpty();
    assertThat(firstReconciliation.requests).isEmpty();
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
    Instant issuedAt = Instant.now().minusSeconds(1);
    return new TargetE2EGraphEnvelopeSigner.SignedEnvelope(
        COMPACT_JWS,
        "java-invocation-es256-1",
        "target-command-jti-001",
        issuedAt,
        issuedAt.plusSeconds(45));
  }

  private static TargetE2EGraphEnvelopeSigner.SignedEnvelope expiredCredential() {
    return new TargetE2EGraphEnvelopeSigner.SignedEnvelope(
        COMPACT_JWS,
        "java-invocation-es256-1",
        "expired-target-command-jti-001",
        Instant.parse("2000-01-01T00:00:00Z"),
        Instant.parse("2000-01-01T00:00:45Z"));
  }

  private static HttpTargetE2EGraphProposalClient proposalClient(
      GraphCommandHttpTransport command,
      GraphReconciliationHttpTransport reconciliation,
      GraphTransportSecurityProof proof,
      TargetE2EGraphEnvelopeCodec codec) {
    GraphTransportBundle transportBundle = bundle(command, reconciliation, proof);
    var reconciliationClient =
        new HttpTargetE2EGraphReconciliationClient(
            transportBundle,
            codec,
            (ignoredSealed, resultRef, proposalHash, cancellationToken) ->
                TargetE2EGraphTestFixtures.proposalSourceBytes(),
            MAPPER,
            URI.create("https://python-agent.internal/base/"),
            Duration.ofSeconds(8));
    return new HttpTargetE2EGraphProposalClient(
        transportBundle,
        reconciliationClient,
        MAPPER,
        URI.create("https://python-agent.internal/base/"),
        Duration.ofSeconds(8));
  }

  private static GraphReconciliationHttpTransport errorReconciliationTransport(
      GraphTransportSecurityProof proof, int status, String body, AtomicInteger exchanges) {
    return new GraphReconciliationHttpTransport() {
      @Override
      public GraphTransportSecurityProof transportProof() {
        return proof;
      }

      @Override
      public Response exchange(Request request, AgentRunCancellationToken cancellationToken) {
        exchanges.incrementAndGet();
        return new Response(
            status,
            Map.of(
                "Content-Type", List.of("application/json; charset=utf-8"),
                "Cache-Control", List.of("no-store")),
            body.getBytes(StandardCharsets.UTF_8));
      }
    };
  }

  private static GraphTransportSecurityProof mutualTlsProof() {
    return mutualTlsProof(URI.create("https://python-agent.internal/base/"));
  }

  private static GraphTransportSecurityProof mutualTlsProof(URI boundBaseUri) {
    try {
      Class<?> type =
          Class.forName(
              "com.example.dispute.workflow.infrastructure.agent."
                  + "TrustedGraphTransportFactory$MutualTlsProof");
      Constructor<?> constructor = type.getDeclaredConstructor(String.class, URI.class);
      constructor.setAccessible(true);
      return (GraphTransportSecurityProof)
          constructor.newInstance("target-test-bundle", boundBaseUri);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static GraphTransportBundle bundle(
      GraphCommandHttpTransport command,
      GraphReconciliationHttpTransport reconciliation,
      GraphTransportSecurityProof proof) {
    try {
      Constructor<GraphTransportBundle> constructor =
          GraphTransportBundle.class.getDeclaredConstructor(
              GraphCommandHttpTransport.class,
              GraphReconciliationHttpTransport.class,
              GraphTransportSecurityProof.class);
      constructor.setAccessible(true);
      return constructor.newInstance(command, reconciliation, proof);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
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

  private static GraphCommandHttpTransport.ResponseHead errorHead(int status, URI uri) {
    return new GraphCommandHttpTransport.ResponseHead(
        status,
        uri,
        Map.of(
            "Content-Type", List.of("application/json; charset=utf-8"),
            "Cache-Control", List.of("no-store, no-transform")));
  }

  private static GraphCommandHttpTransport errorTransport(
      GraphTransportSecurityProof proof,
      int status,
      String contentType,
      List<String> lines,
      AtomicInteger invocations) {
    return new FakeCommandTransport("0".repeat(64), proof) {
      @Override
      public void stream(
          Request request, AgentRunCancellationToken cancellationToken, Listener listener) {
        invocations.incrementAndGet();
        listener.onResponse(
            new ResponseHead(
                status,
                request.uri(),
                Map.of(
                    "Content-Type", List.of(contentType),
                    "Cache-Control", List.of("no-store, no-transform"))));
        lines.forEach(listener::onLine);
      }
    };
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
    private final GraphTransportSecurityProof proof;
    private final List<Request> requests = new ArrayList<>();

    private FakeCommandTransport(String resultHash, GraphTransportSecurityProof proof) {
      this.resultHash = resultHash;
      this.proof = proof;
    }

    @Override
    public GraphTransportSecurityProof transportProof() {
      return proof;
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
    private final GraphTransportSecurityProof proof;
    private final List<Request> requests = new ArrayList<>();

    private FakeReconciliationTransport(byte[] resultBody, GraphTransportSecurityProof proof) {
      this.resultBody = resultBody.clone();
      this.proof = proof;
    }

    @Override
    public GraphTransportSecurityProof transportProof() {
      return proof;
    }

    @Override
    public Response exchange(Request request, AgentRunCancellationToken cancellationToken) {
      requests.add(request);
      return new Response(
          200,
          Map.of(
              "Content-Type", List.of("application/json; charset=utf-8"),
              "Cache-Control", List.of("no-store"),
              "X-Graph-Result-Ref", List.of("urn:graph-result:1"),
              "X-Graph-Result-Hash", List.of(field("result_hash")),
              "X-Graph-Proposal-Hash", List.of(field("proposal_hash"))),
          resultBody);
    }

    private String field(String name) {
      try {
        return MAPPER.readTree(resultBody).required(name).asText();
      } catch (IOException exception) {
        throw new IllegalStateException("test result envelope is invalid", exception);
      }
    }
  }
}
