package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphReconciliationEnvelopeSigner;
import com.example.dispute.workflow.activity.agent.GraphReconciliationException;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.HttpAgentGraphReconciliationClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HttpAgentGraphReconciliationClientTest {

    private static final String TEST_COMPACT_JWS = "e30.e30."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path CONTRACT_ROOT =
            Path.of("..", "contracts", "agent-platform", "v1");
    private static final Path FIXTURES = CONTRACT_ROOT.resolve("fixtures/valid");
    private static AgentPlatformContractCodec codec;

    @BeforeAll
    static void setUp() {
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        codec = new AgentPlatformContractCodec(CONTRACT_ROOT);
    }

    @Test
    void postsOneSignedExactCommandAndReturnsTheBoundResult() throws Exception {
        ExecuteAgentRunRequest request = request();
        AtomicInteger signatures = new AtomicInteger();
        GraphReconciliationEnvelopeSigner signer = (command, expectedRegistryBinding) -> {
            assertThat(command).isEqualTo(request.command());
            int sequence = signatures.incrementAndGet();
            return new GraphReconciliationEnvelopeSigner.SignedEnvelope(
                    TEST_COMPACT_JWS,
                    "java:reconciliation-es256-2",
                    "reconciliation:jti-" + sequence,
                    Instant.parse("2026-07-17T08:00:00Z"),
                    Instant.parse("2026-07-17T08:01:00Z"));
        };
        FakeTransport transport = new FakeTransport(success(request.command()));
        var client = client(transport, signer, URI.create("https://python-agent.internal/base"), false);

        GraphReconcileResponse response =
                client.reconcile(request, new AgentRunCancellationToken());

        assertThat(response.commandId()).isEqualTo(request.command().commandId());
        assertThat(response.resultHash()).isEqualTo(response.result().outputHash());
        assertThat(signatures).hasValue(1);
        assertThat(transport.calls).hasSize(1);
        GraphReconciliationHttpTransport.Request sent = transport.calls.getFirst();
        assertThat(sent.uri().toString())
                .isEqualTo("https://python-agent.internal/base/internal/graphs/commands/reconcile");
        assertThat(sent.headers()).containsEntry(
                "Authorization", "Bearer " + TEST_COMPACT_JWS);
        assertThat(sent.headers()).containsEntry(
                "Content-Type", "application/json; charset=utf-8");
        assertThat(sent.headers()).containsEntry("Content-Encoding", "identity");
        assertThat(sent.headers()).containsEntry("Cache-Control", "no-store");
        assertThat(sent.headers()).containsEntry("traceparent", request.command().traceparent());
        assertThat(sent.maximumResponseBytes()).isEqualTo(131_072);
        JsonNode posted = MAPPER.readTree(sent.body());
        assertThat(ContractJson.canonicalize(posted)).isEqualTo(ContractJson.canonicalize(
                codec.encode("room-graph-command.schema.json", request.command())));
    }

    @Test
    void rejectsUnboundedOrCommandReusedSignerMetadataBeforeTransport() throws Exception {
        ExecuteAgentRunRequest request = request();
        List<GraphReconciliationEnvelopeSigner.SignedEnvelope> invalid = List.of(
                envelope("key id with spaces", "reconciliation-jti-001"),
                envelope("-leading-punctuation", "reconciliation-jti-001"),
                envelope("java-reconciliation-es256-2", "j".repeat(129)),
                envelope(
                        "java-reconciliation-es256-2",
                        request.command().invocationContext().envelopeNonce()));

        for (GraphReconciliationEnvelopeSigner.SignedEnvelope signedEnvelope : invalid) {
            FakeTransport transport = new FakeTransport(success(request.command()));
            GraphReconciliationException failure = catchThrowableOfType(
                    GraphReconciliationException.class,
                    () -> client(
                                    transport,
                                    (command, expectedRegistryBinding) -> signedEnvelope,
                                    URI.create("https://python-agent.internal"),
                                    false)
                            .reconcile(request, new AgentRunCancellationToken()));

            assertThat(failure.errorCode()).isEqualTo("GRAPH_RECONCILIATION_PROTOCOL_REJECTED");
            assertThat(failure.retryable()).isFalse();
            assertThat(transport.calls).isEmpty();
        }

        FakeTransport nullEnvelopeTransport = new FakeTransport(success(request.command()));
        GraphReconciliationException nullEnvelopeFailure = catchThrowableOfType(
                GraphReconciliationException.class,
                () -> client(
                                nullEnvelopeTransport,
                                (command, expectedRegistryBinding) -> null,
                                URI.create("https://python-agent.internal"),
                                false)
                        .reconcile(request, new AgentRunCancellationToken()));
        assertThat(nullEnvelopeFailure.errorCode())
                .isEqualTo("GRAPH_RECONCILIATION_PROTOCOL_REJECTED");
        assertThat(nullEnvelopeTransport.calls).isEmpty();
    }

    static Stream<Arguments> resultBindingDrifts() {
        return Stream.of(
                Arguments.of("request_hash", (Consumer<ObjectNode>) root ->
                        root.put("request_hash", "0".repeat(64))),
                Arguments.of("attempt_id", (Consumer<ObjectNode>) root ->
                        root.put("attempt_id", "attempt-forged")),
                Arguments.of("checkpoint_id", (Consumer<ObjectNode>) root ->
                        root.put("checkpoint_id", "checkpoint-forged")),
                Arguments.of("result_hash", (Consumer<ObjectNode>) root ->
                        root.put("result_hash", "1".repeat(64))),
                Arguments.of("registry_binding_hash", (Consumer<ObjectNode>) root ->
                        root.put("registry_binding_hash", "d".repeat(64))),
                Arguments.of("tool_policy_version", (Consumer<ObjectNode>) root ->
                        root.put("tool_policy_version", "forged-tools.v1")),
                Arguments.of("profile", (Consumer<ObjectNode>) root ->
                        ((ObjectNode) root.required("result").required("execution_metadata"))
                                .put("model_profile_id", "forged-model.v1")),
                Arguments.of("nested_self_hash", (Consumer<ObjectNode>) root ->
                        ((ObjectNode) root.required("result"))
                                .put("cognitive_revision", 99)));
    }

    @ParameterizedTest(name = "rejects_{0}_drift")
    @MethodSource("resultBindingDrifts")
    void rejectsEveryResponseBindingDrift(
            String name,
            Consumer<ObjectNode> mutate) throws Exception {
        ExecuteAgentRunRequest request = request();
        ObjectNode body = successBody(request.command());
        mutate.accept(body);
        FakeTransport transport = new FakeTransport(jsonResponse(200, body));

        GraphReconciliationException failure = catchThrowableOfType(
                GraphReconciliationException.class,
                () -> client(transport).reconcile(
                        request, new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("GRAPH_RECONCILIATION_PROTOCOL_REJECTED");
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
    }

    static Stream<Arguments> remoteErrors() {
        return Stream.of(
                Arguments.of(
                        404,
                        "GRAPH_COMMAND_NOT_FOUND",
                        false,
                        "FAIL_LOGICAL_RUN",
                        AgentRunRecoveryAction.FAIL_LOGICAL_RUN),
                Arguments.of(
                        409,
                        "GRAPH_NEW_AGENT_ATTEMPT_REQUIRED",
                        false,
                        "CREATE_NEXT_ATTEMPT",
                        AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT),
                Arguments.of(
                        409,
                        "GRAPH_INVOCATION_NONCE_REPLAY",
                        true,
                        "RETRY_SAME_COMMAND",
                        AgentRunRecoveryAction.RETRY_SAME_COMMAND),
                Arguments.of(
                        503,
                        "GRAPH_GATEWAY_NOT_READY",
                        true,
                        "RETRY_SAME_COMMAND",
                        AgentRunRecoveryAction.RETRY_SAME_COMMAND));
    }

    @ParameterizedTest
    @MethodSource("remoteErrors")
    void preservesClosedRemoteRecoveryActions(
            int status,
            String code,
            boolean retryable,
            String action,
            AgentRunRecoveryAction expected) throws Exception {
        ExecuteAgentRunRequest request = request();
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", code);
        error.put("retryable", retryable);
        error.put("recovery_action", action);
        FakeTransport transport = new FakeTransport(jsonResponse(status, error));

        GraphReconciliationException failure = catchThrowableOfType(
                GraphReconciliationException.class,
                () -> client(transport).reconcile(
                        request, new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo(code);
        assertThat(failure.httpStatus()).isEqualTo(status);
        assertThat(failure.retryable()).isEqualTo(retryable);
        assertThat(failure.recoveryAction()).isEqualTo(expected);
    }

    @Test
    void rejectsMalformedOrInconsistentRemoteErrors() throws Exception {
        ExecuteAgentRunRequest request = request();
        List<String> invalidBodies = List.of(
                "{\"code\":\"GRAPH_FAILED\",\"retryable\":false}",
                "{\"code\":\"GRAPH_FAILED\",\"retryable\":false,"
                        + "\"recovery_action\":\"UNKNOWN\"}",
                "{\"code\":\"GRAPH_FAILED\",\"retryable\":true,"
                        + "\"recovery_action\":\"FAIL_LOGICAL_RUN\"}",
                "{\"code\":\"GRAPH_FAILED\",\"retryable\":false,"
                        + "\"recovery_action\":\"FAIL_LOGICAL_RUN\",\"private\":\"leak\"}",
                "{\"code\":\"GRAPH_FAILED\",\"code\":\"OTHER\","
                        + "\"retryable\":false,\"recovery_action\":\"FAIL_LOGICAL_RUN\"}");

        for (String body : invalidBodies) {
            FakeTransport transport = new FakeTransport(new GraphReconciliationHttpTransport.Response(
                    409,
                    responseHeaders(),
                    body.getBytes(StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> client(transport).reconcile(
                            request, new AgentRunCancellationToken()))
                    .isInstanceOf(GraphReconciliationException.class)
                    .extracting(failure ->
                            ((GraphReconciliationException) failure).recoveryAction())
                    .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        }
    }

    @Test
    void rejectsMissingNoStoreWrongContentTypeTrailingJsonAndOversizedBodies()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        ObjectNode success = successBody(request.command());
        byte[] valid = MAPPER.writeValueAsBytes(success);
        List<GraphReconciliationHttpTransport.Response> invalid = List.of(
                new GraphReconciliationHttpTransport.Response(
                        200,
                        Map.of("content-type", List.of("application/json")),
                        valid),
                new GraphReconciliationHttpTransport.Response(
                        200,
                        Map.of(
                                "content-type", List.of("text/plain"),
                                "cache-control", List.of("no-store")),
                        valid),
                new GraphReconciliationHttpTransport.Response(
                        200,
                        responseHeaders(),
                        (new String(valid, StandardCharsets.UTF_8) + " {}").getBytes(
                                StandardCharsets.UTF_8)),
                new GraphReconciliationHttpTransport.Response(
                        200,
                        responseHeaders(),
                        new byte[131_073]));

        for (GraphReconciliationHttpTransport.Response response : invalid) {
            assertThatThrownBy(() -> client(new FakeTransport(response)).reconcile(
                            request, new AgentRunCancellationToken()))
                    .isInstanceOf(GraphReconciliationException.class)
                    .extracting(failure ->
                            ((GraphReconciliationException) failure).recoveryAction())
                    .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        }
    }

    @Test
    void transportFailureIsRetryableButPlaintextRequiresAnExplicitDevelopmentFlag()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        FakeTransport transport = new FakeTransport(new IllegalStateException("connection lost"));

        GraphReconciliationException failure = catchThrowableOfType(
                GraphReconciliationException.class,
                () -> client(transport).reconcile(
                        request, new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("GRAPH_RECONCILIATION_TRANSPORT_FAILED");
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RETRY_SAME_COMMAND);
        assertThat(failure.getMessage()).doesNotContain("connection lost");
        assertThatThrownBy(() -> client(
                        new FakeTransport(success(request.command())),
                        signer(),
                        URI.create("http://python-agent:18000"),
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not trusted");
        assertThat(client(
                        new FakeTransport(success(request.command())),
                        signer(),
                        URI.create("http://python-agent:18000"),
                        true))
                .isNotNull();
    }

    private static HttpAgentGraphReconciliationClient client(FakeTransport transport) {
        return client(
                transport,
                signer(),
                URI.create("https://python-agent.internal"),
                false);
    }

    private static HttpAgentGraphReconciliationClient client(
            FakeTransport transport,
            GraphReconciliationEnvelopeSigner signer,
            URI baseUri,
            boolean allowPlaintext) {
        return new HttpAgentGraphReconciliationClient(
                transport,
                signer,
                ignored -> new GraphRegistryBindingPolicy.ExpectedBinding(
                        "c".repeat(64), "intake-tools.v1"),
                codec,
                MAPPER,
                baseUri,
                Duration.ofSeconds(10),
                allowPlaintext);
    }

    private static GraphReconciliationEnvelopeSigner signer() {
        return (command, expectedRegistryBinding) ->
                envelope("java-reconciliation-es256-2", "reconciliation-jti-001");
    }

    private static GraphReconciliationEnvelopeSigner.SignedEnvelope envelope(
            String keyId, String jti) {
        return new GraphReconciliationEnvelopeSigner.SignedEnvelope(
                TEST_COMPACT_JWS,
                keyId,
                jti,
                Instant.parse("2026-07-17T08:00:00Z"),
                Instant.parse("2026-07-17T08:01:00Z"));
    }

    private static GraphReconciliationHttpTransport.Response success(RoomGraphCommand command)
            throws Exception {
        return jsonResponse(200, successBody(command));
    }

    private static GraphReconciliationHttpTransport.Response jsonResponse(
            int status,
            ObjectNode body) throws Exception {
        return new GraphReconciliationHttpTransport.Response(
                status,
                responseHeaders(),
                MAPPER.writeValueAsBytes(body));
    }

    private static Map<String, List<String>> responseHeaders() {
        return Map.of(
                "content-type", List.of("application/json; charset=utf-8"),
                "content-encoding", List.of("identity"),
                "cache-control", List.of("no-store, no-transform"));
    }

    private static ObjectNode successBody(RoomGraphCommand command) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                FIXTURES.resolve("graph-reconcile-response-valid.json").toFile())
                .required("instance");
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
        return root;
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

    private static final class FakeTransport implements GraphReconciliationHttpTransport {
        private final Response response;
        private final RuntimeException failure;
        private final List<Request> calls = new java.util.ArrayList<>();

        private FakeTransport(Response response) {
            this.response = response;
            this.failure = null;
        }

        private FakeTransport(RuntimeException failure) {
            this.response = null;
            this.failure = failure;
        }

        @Override
        public Response exchange(Request request, AgentRunCancellationToken cancellationToken) {
            calls.add(request);
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
