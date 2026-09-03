package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.GraphCommandEnvelopeSigner;
import com.example.dispute.workflow.activity.agent.GraphReconciliationException;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandTransportException;
import com.example.dispute.workflow.infrastructure.agent.HttpAgentGraphCommandClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HttpAgentGraphCommandClientTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path CONTRACT_ROOT = Path.of("..", "..", "contracts", "agent-platform", "v1");
    private static final Path FIXTURES = CONTRACT_ROOT.resolve("fixtures/valid");
    private static final URI ENDPOINT = URI.create(
            "https://python-agent.internal/base/internal/graphs/commands/stream");
    private static final String TEST_COMPACT_JWS = "e30.e30."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
    private static AgentPlatformContractCodec codec;

    @BeforeAll
    static void setUp() {
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        codec = new AgentPlatformContractCodec(CONTRACT_ROOT);
    }

    @Test
    void postsOneSignedExactCommandAndReconcilesOnlyAfterACompleteFinalStream()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciled = reconciliation(request);
        List<AgentStreamEvent> events = new ArrayList<>();
        AtomicInteger signatures = new AtomicInteger();
        AtomicInteger reconciliations = new AtomicInteger();
        AtomicReference<GraphStreamVisibilityPolicy.Binding> binding = new AtomicReference<>();
        FakeTransport transport = new FakeTransport((sent, token, listener) -> {
            listener.onResponse(successHead(request.agentRunId()));
            listener.onLine(event(
                    request,
                    0,
                    "attempt_started",
                    "{\"node\":\"intake.reason\"}"));
            listener.onLine(event(
                    request,
                    1,
                    "visible_delta",
                    "{\"node\":\"intake.reason\",\"field\":\"room_utterance\","
                            + "\"delta\":\"public\"}"));
            listener.onLine(event(
                    request,
                    2,
                    "final",
                    finalPayload(reconciled)));
            assertThat(events)
                    .extracting(AgentStreamEvent::eventType)
                    .containsExactly(
                            StreamEventType.ATTEMPT_STARTED,
                            StreamEventType.VISIBLE_DELTA);
            assertThat(reconciliations).hasValue(0);
        });
        GraphCommandEnvelopeSigner signer = (command, expectedRegistryBinding) -> {
            assertThat(command).isEqualTo(request.command());
            signatures.incrementAndGet();
            return envelope();
        };
        AgentGraphReconciliationClient reconciliationClient = (candidate, token) -> {
            assertThat(candidate).isEqualTo(request);
            assertThat(events)
                    .extracting(AgentStreamEvent::eventType)
                    .containsExactly(
                            StreamEventType.ATTEMPT_STARTED,
                            StreamEventType.VISIBLE_DELTA,
                            StreamEventType.FINAL);
            reconciliations.incrementAndGet();
            return reconciled;
        };
        GraphStreamVisibilityPolicy policy = candidate -> {
            binding.set(candidate);
            return Map.of("intake.reason", Set.of("room_utterance"));
        };

        var result = client(transport, signer, reconciliationClient, policy)
                .execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        events::add,
                        new AgentRunCancellationToken());

        assertThat(result).isEqualTo(reconciled.result());
        assertThat(signatures).hasValue(1);
        assertThat(reconciliations).hasValue(1);
        assertThat(binding.get()).isEqualTo(GraphStreamVisibilityPolicy.Binding.from(
                request.command()));
        assertThat(transport.requests).hasSize(1);
        GraphCommandHttpTransport.Request sent = transport.requests.getFirst();
        assertThat(sent.uri()).isEqualTo(ENDPOINT);
        assertThat(sent.headers())
                .containsEntry("Authorization", "Bearer " + TEST_COMPACT_JWS)
                .containsEntry("Accept", "application/x-ndjson")
                .containsEntry("Content-Type", "application/json; charset=utf-8")
                .containsEntry("Content-Encoding", "identity")
                .containsEntry("Cache-Control", "no-store")
                .containsEntry("X-Agent-Run-Id", request.agentRunId())
                .containsEntry("traceparent", request.command().traceparent());
        assertThat(sent.maximumLineBytes())
                .isEqualTo(GraphCommandHttpTransport.MAXIMUM_LINE_BYTES);
        assertThat(sent.maximumResponseBytes())
                .isEqualTo(GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES);
        assertThat(sent.timeout()).isEqualTo(Duration.ofSeconds(10));
        JsonNode posted = MAPPER.readTree(sent.body());
        assertThat(ContractJson.canonicalize(posted)).isEqualTo(ContractJson.canonicalize(
                codec.encode("room-graph-command.schema.json", request.command())));
    }

    @Test
    void emitsNothingWhenMetadataFailsOrWhenALineArrivesBeforeMetadata() throws Exception {
        ExecuteAgentRunRequest request = request();
        List<Map<String, List<String>>> invalidHeaders = List.of(
                Map.of(
                        "content-type", List.of("application/x-ndjson; charset=utf-8"),
                        "cache-control", List.of("no-store, no-transform"),
                        "x-agent-run-id", List.of("another-run")),
                Map.of(
                        "content-type", List.of("application/x-ndjson; charset=utf-8"),
                        "cache-control", List.of("no-store"),
                        "x-agent-run-id", List.of(request.agentRunId())),
                Map.of(
                        "content-type", List.of("text/plain; charset=utf-8"),
                        "cache-control", List.of("no-store, no-transform"),
                        "x-agent-run-id", List.of(request.agentRunId())),
                Map.of(
                        "content-type", List.of("application/x-ndjson; charset=utf-8"),
                        "content-encoding", List.of("gzip"),
                        "cache-control", List.of("no-store, no-transform"),
                        "x-agent-run-id", List.of(request.agentRunId())),
                Map.of(
                        "Content-Type", List.of("application/x-ndjson"),
                        "content-type", List.of("application/x-ndjson; charset=utf-8"),
                        "cache-control", List.of("no-store, no-transform"),
                        "x-agent-run-id", List.of(request.agentRunId())));
        for (Map<String, List<String>> headers : invalidHeaders) {
            List<AgentStreamEvent> badMetadataEvents = new ArrayList<>();
            FakeTransport badMetadata = new FakeTransport((sent, token, listener) -> {
                listener.onResponse(new GraphCommandHttpTransport.ResponseHead(
                        200, ENDPOINT, headers));
                listener.onLine(event(
                        request,
                        0,
                        "attempt_started",
                        "{\"node\":\"intake.reason\"}"));
            });

            assertThatThrownBy(() -> client(badMetadata).execute(
                            request,
                            ExecutionMode.EXECUTE_OR_RECONCILE,
                            badMetadataEvents::add,
                            new AgentRunCancellationToken()))
                    .isInstanceOf(AgentRunExecutionException.class)
                    .extracting(failure ->
                            ((AgentRunExecutionException) failure).recoveryAction())
                    .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
            assertThat(badMetadataEvents).isEmpty();
        }

        List<AgentStreamEvent> earlyLineEvents = new ArrayList<>();
        FakeTransport earlyLine = new FakeTransport((sent, token, listener) -> listener.onLine(
                event(
                        request,
                        0,
                        "attempt_started",
                        "{\"node\":\"intake.reason\"}")));
        assertThatThrownBy(() -> client(earlyLine).execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        earlyLineEvents::add,
                        new AgentRunCancellationToken()))
                .isInstanceOf(AgentRunExecutionException.class)
                .extracting(failure ->
                        ((AgentRunExecutionException) failure).recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(earlyLineEvents).isEmpty();
    }

    @Test
    void rejectsCrossNodeVisibleFieldsBeforeTheyReachTheSink() throws Exception {
        ExecuteAgentRunRequest request = request();
        List<AgentStreamEvent> events = new ArrayList<>();
        FakeTransport transport = new FakeTransport((sent, token, listener) -> {
            listener.onResponse(successHead(request.agentRunId()));
            listener.onLine(event(
                    request,
                    0,
                    "attempt_started",
                    "{\"node\":\"intake.reason\"}"));
            listener.onLine(event(
                    request,
                    1,
                    "visible_delta",
                    "{\"node\":\"private.reason\",\"field\":\"room_utterance\","
                            + "\"delta\":\"secret\"}"));
        });
        GraphStreamVisibilityPolicy policy = ignored -> Map.of(
                "intake.reason", Set.of("room_utterance"),
                "private.reason", Set.of("internal_summary"));

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> client(transport, signer(), noReconciliation(), policy)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                events::add,
                                new AgentRunCancellationToken()));

        assertThat(failure.recoveryAction()).isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(events)
                .extracting(AgentStreamEvent::eventType)
                .containsExactly(StreamEventType.ATTEMPT_STARTED);
    }

    static Stream<Arguments> remoteErrors() {
        return Stream.of(
                Arguments.of(
                        503,
                        "GRAPH_GATEWAY_NOT_READY",
                        true,
                        AgentRunRecoveryAction.RETRY_SAME_COMMAND),
                Arguments.of(
                        409,
                        "GRAPH_NEW_AGENT_ATTEMPT_REQUIRED",
                        true,
                        AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT),
                Arguments.of(
                        409,
                        "GRAPH_CONTRACT_REJECTED",
                        false,
                        AgentRunRecoveryAction.RECONCILE_TERMINAL),
                Arguments.of(
                        401,
                        "INVOCATION_BODY_HASH_REJECTED",
                        false,
                        AgentRunRecoveryAction.FAIL_LOGICAL_RUN));
    }

    @ParameterizedTest
    @MethodSource("remoteErrors")
    void mapsSanitizedPreStreamErrorsToClosedRecoveryActions(
            int status,
            String code,
            boolean retryable,
            AgentRunRecoveryAction expected) throws Exception {
        ExecuteAgentRunRequest request = request();
        FakeTransport transport = new FakeTransport((sent, token, listener) -> {
            listener.onResponse(errorHead(status));
            listener.onLine("{\"code\":\"" + code + "\",\"retryable\":"
                    + retryable + "}");
        });

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> client(transport).execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo(code);
        assertThat(failure.recoveryAction()).isEqualTo(expected);
        assertThat(failure.publicOutputEmitted()).isFalse();
    }

    @Test
    void attemptAbortedCreatesANewAttemptButErrorIsALogicalTerminal() throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunExecutionException aborted = terminalFailure(
                request,
                "attempt_aborted",
                "{\"reason_code\":\"PROVIDER_TIMEOUT\"}");
        AgentRunExecutionException error = terminalFailure(
                request,
                "error",
                "{\"error_code\":\"OUTPUT_POLICY_REJECTED\",\"retryable\":true}");

        assertThat(aborted.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(aborted.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);
        assertThat(error.errorCode()).isEqualTo("OUTPUT_POLICY_REJECTED");
        assertThat(error.recoveryAction()).isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
    }

    @Test
    void missingTerminalAndMissingReconciledResultFailClosed() throws Exception {
        ExecuteAgentRunRequest request = request();
        FakeTransport incomplete = new FakeTransport((sent, token, listener) -> {
            listener.onResponse(successHead(request.agentRunId()));
            listener.onLine(event(
                    request,
                    0,
                    "attempt_started",
                    "{\"node\":\"intake.reason\"}"));
        });
        assertThatThrownBy(() -> client(incomplete).execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()))
                .isInstanceOf(AgentRunExecutionException.class)
                .extracting(failure ->
                        ((AgentRunExecutionException) failure).recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);

        GraphReconcileResponse expected = reconciliation(request);
        FakeTransport complete = finalTransport(request, expected);
        AgentGraphReconciliationClient missing = (candidate, token) -> null;
        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> client(complete, signer(), missing, visibility())
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));
        assertThat(failure.recoveryAction()).isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_STREAM_V2_INVALID");
    }

    @Test
    void rejectsAFinalWhoseReferenceOrHashDiffersFromReconciliation() throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse expected = reconciliation(request);
        FakeTransport forgedFinal = new FakeTransport((sent, token, listener) -> {
            listener.onResponse(successHead(request.agentRunId()));
            listener.onLine(event(
                    request,
                    0,
                    "attempt_started",
                    "{\"node\":\"intake.reason\"}"));
            listener.onLine(event(
                    request,
                    1,
                    "final",
                    "{\"final_result_ref\":\"urn:forged:result\","
                            + "\"final_result_hash\":\""
                            + "a".repeat(64)
                            + "\"}"));
        });
        AgentGraphReconciliationClient reconciliationClient = (candidate, token) -> expected;

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> client(forgedFinal, signer(), reconciliationClient, visibility())
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_STREAM_V2_INVALID");
        assertThat(failure.recoveryAction()).isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
    }

    @Test
    void reconcileOnlyIsRejectedBeforePolicySigningOrTransport() throws Exception {
        ExecuteAgentRunRequest request = request();
        AtomicInteger sideEffects = new AtomicInteger();
        FakeTransport transport = new FakeTransport((sent, token, listener) ->
                sideEffects.incrementAndGet());
        GraphCommandEnvelopeSigner signer = (command, expectedRegistryBinding) -> {
            sideEffects.incrementAndGet();
            return envelope();
        };
        GraphStreamVisibilityPolicy policy = binding -> {
            sideEffects.incrementAndGet();
            return Map.of();
        };

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> client(transport, signer, noReconciliation(), policy)
                        .execute(
                                request,
                                ExecutionMode.RECONCILE_ONLY,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.recoveryAction()).isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(sideEffects).hasValue(0);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void clampsTheTransportTimeoutToDeadlineAndRejectsExpiredCommandsBeforeSideEffects()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        FakeTransport clamped = new FakeTransport((sent, token, listener) -> {
            assertThat(sent.timeout()).isEqualTo(Duration.ofSeconds(1));
            listener.onResponse(errorHead(503));
            listener.onLine("{\"code\":\"GRAPH_GATEWAY_NOT_READY\",\"retryable\":true}");
        });
        HttpAgentGraphCommandClient nearDeadline = client(
                clamped,
                signer(),
                noReconciliation(),
                visibility(),
                Clock.fixed(Instant.parse("2026-07-17T08:09:59Z"), ZoneOffset.UTC),
                Duration.ofSeconds(10));
        assertThatThrownBy(() -> nearDeadline.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()))
                .isInstanceOf(AgentRunExecutionException.class)
                .extracting(failure ->
                        ((AgentRunExecutionException) failure).recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RETRY_SAME_COMMAND);
        assertThat(clamped.requests).hasSize(1);

        AtomicInteger sideEffects = new AtomicInteger();
        FakeTransport expiredTransport = new FakeTransport((sent, token, listener) ->
                sideEffects.incrementAndGet());
        GraphCommandEnvelopeSigner expiredSigner = (command, expectedRegistryBinding) -> {
            sideEffects.incrementAndGet();
            return envelope();
        };
        GraphStreamVisibilityPolicy expiredPolicy = binding -> {
            sideEffects.incrementAndGet();
            return Map.of();
        };
        HttpAgentGraphCommandClient expired = client(
                expiredTransport,
                expiredSigner,
                noReconciliation(),
                expiredPolicy,
                Clock.fixed(request.command().deadlineAt(), ZoneOffset.UTC),
                Duration.ofSeconds(10));
        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> expired.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()));
        assertThat(failure.errorCode()).isEqualTo("GRAPH_COMMAND_DEADLINE_EXCEEDED");
        assertThat(failure.recoveryAction()).isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(sideEffects).hasValue(0);
        assertThat(expiredTransport.requests).isEmpty();
    }

    @Test
    void rejectsSignerBindingDriftAndReconcilesAfterTransportLossPastFinal()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphCommandEnvelopeSigner wrongKey = (command, expectedRegistryBinding) ->
                new GraphCommandEnvelopeSigner.SignedEnvelope(
                        TEST_COMPACT_JWS,
                        "another-key",
                        "delivery-jti-001",
                        Instant.parse("2026-07-17T08:00:00Z"),
                        Instant.parse("2026-07-17T08:01:00Z"));
        GraphCommandEnvelopeSigner reusedNonce = (command, expectedRegistryBinding) ->
                new GraphCommandEnvelopeSigner.SignedEnvelope(
                        TEST_COMPACT_JWS,
                        "java-invocation-es256-1",
                        command.invocationContext().envelopeNonce(),
                        Instant.parse("2026-07-17T08:00:00Z"),
                        Instant.parse("2026-07-17T08:01:00Z"));
        GraphCommandEnvelopeSigner leadingPunctuationJti =
                (command, expectedRegistryBinding) ->
                        new GraphCommandEnvelopeSigner.SignedEnvelope(
                                TEST_COMPACT_JWS,
                                "java-invocation-es256-1",
                                "-delivery-jti-001",
                                Instant.parse("2026-07-17T08:00:00Z"),
                                Instant.parse("2026-07-17T08:01:00Z"));
        FakeTransport unused = new FakeTransport((sent, token, listener) -> {});
        for (GraphCommandEnvelopeSigner invalidSigner :
                List.of(wrongKey, reusedNonce, leadingPunctuationJti)) {
            AgentRunExecutionException bindingFailure = catchThrowableOfType(
                    AgentRunExecutionException.class,
                    () -> client(unused, invalidSigner, noReconciliation(), visibility())
                            .execute(
                                    request,
                                    ExecutionMode.EXECUTE_OR_RECONCILE,
                                    ignored -> {},
                                    new AgentRunCancellationToken()));
            assertThat(bindingFailure.recoveryAction())
                    .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        }
        assertThat(unused.requests).isEmpty();

        GraphReconcileResponse expected = reconciliation(request);
        List<AgentStreamEvent> events = new ArrayList<>();
        FakeTransport truncated = new FakeTransport((sent, token, listener) -> {
            listener.onResponse(successHead(request.agentRunId()));
            listener.onLine(event(
                    request,
                    0,
                    "attempt_started",
                    "{\"node\":\"intake.reason\"}"));
            listener.onLine(event(request, 1, "final", finalPayload(expected)));
            throw new GraphCommandTransportException("connection lost after final", null);
        });
        AgentRunExecutionException transportFailure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> client(truncated).execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        events::add,
                        new AgentRunCancellationToken()));
        assertThat(transportFailure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RECONCILE_TERMINAL);
        assertThat(events)
                .extracting(AgentStreamEvent::eventType)
                .containsExactly(StreamEventType.ATTEMPT_STARTED);
    }

    @Test
    void retryableFinalReconciliationFailureNeverReplaysTheCommand() throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse expected = reconciliation(request);
        AgentGraphReconciliationClient unavailable = (candidate, token) -> {
            throw GraphReconciliationException.transport(new IOException("unavailable"));
        };

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> client(finalTransport(request, expected), signer(), unavailable, visibility())
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("GRAPH_RECONCILIATION_TRANSPORT_FAILED");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RECONCILE_TERMINAL);
    }

    private static AgentRunExecutionException terminalFailure(
            ExecuteAgentRunRequest request, String type, String payload) {
        List<AgentStreamEvent> events = new ArrayList<>();
        FakeTransport transport = new FakeTransport((sent, token, listener) -> {
            listener.onResponse(successHead(request.agentRunId()));
            listener.onLine(event(
                    request,
                    0,
                    "attempt_started",
                    "{\"node\":\"intake.reason\"}"));
            listener.onLine(event(request, 1, type, payload));
        });
        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> client(transport).execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        events::add,
                        new AgentRunCancellationToken()));
        assertThat(events).hasSize(2);
        return failure;
    }

    private static HttpAgentGraphCommandClient client(FakeTransport transport) {
        return client(transport, signer(), noReconciliation(), visibility());
    }

    private static HttpAgentGraphCommandClient client(
            FakeTransport transport,
            GraphCommandEnvelopeSigner signer,
            AgentGraphReconciliationClient reconciliationClient,
            GraphStreamVisibilityPolicy visibilityPolicy) {
        return client(
                transport,
                signer,
                reconciliationClient,
                visibilityPolicy,
                Clock.fixed(Instant.parse("2026-07-17T08:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(10));
    }

    private static HttpAgentGraphCommandClient client(
            FakeTransport transport,
            GraphCommandEnvelopeSigner signer,
            AgentGraphReconciliationClient reconciliationClient,
            GraphStreamVisibilityPolicy visibilityPolicy,
            Clock clock,
            Duration timeout) {
        return new HttpAgentGraphCommandClient(
                transport,
                signer,
                reconciliationClient,
                visibilityPolicy,
                registryPolicy(),
                codec,
                MAPPER,
                URI.create("https://python-agent.internal/base"),
                timeout,
                false,
                clock);
    }

    private static GraphCommandEnvelopeSigner signer() {
        return (ignored, expectedRegistryBinding) -> envelope();
    }

    private static GraphCommandEnvelopeSigner.SignedEnvelope envelope() {
        return new GraphCommandEnvelopeSigner.SignedEnvelope(
                TEST_COMPACT_JWS,
                "java-invocation-es256-1",
                "delivery:jti-001",
                Instant.parse("2026-07-17T08:00:00Z"),
                Instant.parse("2026-07-17T08:01:00Z"));
    }

    private static AgentGraphReconciliationClient noReconciliation() {
        return (request, token) -> {
            throw new AssertionError("reconciliation must not run");
        };
    }

    private static GraphStreamVisibilityPolicy visibility() {
        return ignored -> Map.of("intake.reason", Set.of("room_utterance"));
    }

    private static GraphRegistryBindingPolicy registryPolicy() {
        return ignored -> new GraphRegistryBindingPolicy.ExpectedBinding(
                "c".repeat(64), "intake-tools.v1");
    }

    private static FakeTransport finalTransport(
            ExecuteAgentRunRequest request, GraphReconcileResponse response) {
        return new FakeTransport((sent, token, listener) -> {
            listener.onResponse(successHead(request.agentRunId()));
            listener.onLine(event(
                    request,
                    0,
                    "attempt_started",
                    "{\"node\":\"intake.reason\"}"));
            listener.onLine(event(request, 1, "final", finalPayload(response)));
        });
    }

    private static GraphCommandHttpTransport.ResponseHead successHead(String runId) {
        return new GraphCommandHttpTransport.ResponseHead(
                200,
                ENDPOINT,
                Map.of(
                        "content-type", List.of("application/x-ndjson; charset=utf-8"),
                        "content-encoding", List.of("identity"),
                        "cache-control", List.of("no-store, no-transform"),
                        "x-agent-run-id", List.of(runId)));
    }

    private static GraphCommandHttpTransport.ResponseHead errorHead(int status) {
        return new GraphCommandHttpTransport.ResponseHead(
                status,
                ENDPOINT,
                Map.of(
                        "content-type", List.of("application/json; charset=utf-8"),
                        "content-encoding", List.of("identity"),
                        "cache-control", List.of("no-store, no-transform")));
    }

    private static String event(
            ExecuteAgentRunRequest request, long sequence, String type, String payload) {
        return "{\"schema_version\":\"agent-stream.v2\",\"run_id\":\""
                + request.logicalRunId()
                + "\",\"attempt_id\":\""
                + request.attemptId()
                + "\",\"sequence_no\":"
                + sequence
                + ",\"event_type\":\""
                + type
                + "\",\"audience\":\"USER\","
                + "\"occurred_at\":\"2026-07-17T08:00:00Z\",\"payload\":"
                + payload
                + "}";
    }

    private static String finalPayload(GraphReconcileResponse response) {
        return "{\"final_result_ref\":\""
                + response.resultRef()
                + "\",\"final_result_hash\":\""
                + response.resultHash()
                + "\"}";
    }

    private static GraphReconcileResponse reconciliation(ExecuteAgentRunRequest request)
            throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                        FIXTURES.resolve("graph-reconcile-response-valid.json").toFile())
                .required("instance");
        RoomGraphCommand command = request.command();
        root.put("thread_id", command.threadId());
        root.put("command_id", command.commandId());
        root.put("request_hash", command.requestHash());
        root.put("logical_run_id", command.logicalRunId());
        root.put("attempt_id", command.attemptId());
        root.put("graph_key", command.graphKey());
        root.put("graph_version", command.graphVersion());
        root.put("checkpoint_schema_version", command.checkpointSchemaVersion());
        ObjectNode result = (ObjectNode) root.required("result");
        result.put("command_id", command.commandId());
        result.put("logical_run_id", command.logicalRunId());
        result.put("attempt_id", command.attemptId());
        result.put("graph_key", command.graphKey());
        result.put("graph_version", command.graphVersion());
        result.remove("output_hash");
        String resultHash = ContractJson.sha256Hex(result);
        result.put("output_hash", resultHash);
        root.put("result_hash", resultHash);
        root.put("result_ref", "urn:after-sale-flow:graph-result:" + resultHash);
        return MAPPER.treeToValue(root, GraphReconcileResponse.class);
    }

    private static ExecuteAgentRunRequest request() throws Exception {
        JsonNode wrapper = MAPPER.readTree(
                FIXTURES.resolve("room-graph-command-valid.json").toFile());
        RoomGraphCommand command = MAPPER.treeToValue(
                wrapper.required("instance"), RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                1,
                "agent-stream.v2",
                "b".repeat(64),
                null,
                false,
                0,
                command);
    }

    @FunctionalInterface
    private interface Script {
        void run(
                GraphCommandHttpTransport.Request request,
                AgentRunCancellationToken cancellationToken,
                GraphCommandHttpTransport.Listener listener);
    }

    private static final class FakeTransport implements GraphCommandHttpTransport {
        private final Script script;
        private final List<Request> requests = new ArrayList<>();

        private FakeTransport(Script script) {
            this.script = script;
        }

        @Override
        public void stream(
                Request request,
                AgentRunCancellationToken cancellationToken,
                Listener listener) {
            requests.add(request);
            script.run(request, cancellationToken, listener);
        }
    }
}
