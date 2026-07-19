package com.example.dispute.workflow.infrastructure.agent;

import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphReconciliationEnvelopeSigner;
import com.example.dispute.workflow.activity.agent.GraphReconciliationException;
import com.example.dispute.workflow.activity.agent.GraphReconciliationException.RecoveryAction;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.annotation.JsonInclude;
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

/** Signed JSON client for the result-only Graph reconciliation endpoint. */
public final class HttpAgentGraphReconciliationClient
        implements AgentGraphReconciliationClient {

    public static final String PATH = "internal/graphs/commands/reconcile";
    private static final String COMMAND_SCHEMA = "room-graph-command.schema.json";
    private static final String RESPONSE_SCHEMA = "graph-reconcile-response.schema.json";
    private static final int MAXIMUM_RESPONSE_BYTES = 131_072;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ERROR_FIELDS =
            Set.of("code", "retryable", "recovery_action");

    private final GraphReconciliationHttpTransport transport;
    private final GraphReconciliationEnvelopeSigner envelopeSigner;
    private final AgentPlatformContractCodec codec;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;

    public HttpAgentGraphReconciliationClient(
            GraphReconciliationHttpTransport transport,
            GraphReconciliationEnvelopeSigner envelopeSigner,
            AgentPlatformContractCodec codec,
            ObjectMapper objectMapper,
            URI baseUri,
            Duration timeout) {
        this(transport, envelopeSigner, codec, objectMapper, baseUri, timeout, false);
    }

    public HttpAgentGraphReconciliationClient(
            GraphReconciliationHttpTransport transport,
            GraphReconciliationEnvelopeSigner envelopeSigner,
            AgentPlatformContractCodec codec,
            ObjectMapper objectMapper,
            URI baseUri,
            Duration timeout,
            boolean allowPlaintextTransport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.envelopeSigner = Objects.requireNonNull(envelopeSigner, "envelopeSigner");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Graph reconciliation timeout must be positive");
        }
        this.endpoint = endpoint(baseUri, allowPlaintextTransport);
    }

    @Override
    public GraphReconcileResponse reconcile(
            ExecuteAgentRunRequest request,
            AgentRunCancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken")
                .throwIfCancellationRequested();
        RoomGraphCommand command = request.command();
        byte[] body;
        GraphReconciliationEnvelopeSigner.SignedEnvelope envelope;
        try {
            JsonNode commandJson = codec.encode(COMMAND_SCHEMA, command);
            body = mapper.writeValueAsBytes(commandJson);
            envelope = envelopeSigner.sign(command);
        } catch (IllegalArgumentException | IOException exception) {
            throw GraphReconciliationException.protocol(
                    "Graph reconciliation request is invalid", exception);
        }
        GraphReconciliationHttpTransport.Request transportRequest =
                new GraphReconciliationHttpTransport.Request(
                        endpoint,
                        Map.of(
                                "Authorization", "Bearer " + envelope.compactJws(),
                                "Accept", "application/json",
                                "Content-Type", "application/json; charset=utf-8",
                                "Content-Encoding", "identity",
                                "Cache-Control", "no-store",
                                "traceparent", command.traceparent()),
                        body,
                        timeout,
                        MAXIMUM_RESPONSE_BYTES);

        GraphReconciliationHttpTransport.Response response;
        try {
            response = transport.exchange(transportRequest, cancellationToken);
        } catch (GraphReconciliationException exception) {
            throw exception;
        } catch (GraphReconciliationTransportException exception) {
            cancellationToken.throwIfCancellationRequested();
            if (exception.protocolViolation()) {
                throw GraphReconciliationException.protocol(
                        "Graph reconciliation transport violated the protocol", exception);
            }
            throw GraphReconciliationException.transport(exception);
        } catch (RuntimeException exception) {
            cancellationToken.throwIfCancellationRequested();
            throw GraphReconciliationException.transport(exception);
        }
        cancellationToken.throwIfCancellationRequested();
        requireResponseMetadata(response);
        if (response.statusCode() == 200) {
            return decodeSuccess(request, response.body());
        }
        if (response.statusCode() >= 400 && response.statusCode() <= 599) {
            throw decodeRemoteError(response.statusCode(), response.body());
        }
        throw GraphReconciliationException.protocol(
                "Graph reconciliation returned an unsupported HTTP status", null);
    }

    private GraphReconcileResponse decodeSuccess(
            ExecuteAgentRunRequest request,
            byte[] body) {
        try {
            JsonNode node = readObject(body);
            GraphReconcileResponse response = codec.decode(
                    RESPONSE_SCHEMA, node, GraphReconcileResponse.class);
            requireExactResponse(request, response);
            return response;
        } catch (GraphReconciliationException exception) {
            throw exception;
        } catch (IllegalArgumentException | IOException exception) {
            throw GraphReconciliationException.protocol(
                    "Graph reconciliation success body is invalid", exception);
        }
    }

    private GraphReconciliationException decodeRemoteError(int status, byte[] body) {
        try {
            ObjectNode node = readObject(body);
            Set<String> fields = node.properties().stream()
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            if (!fields.equals(ERROR_FIELDS)
                    || !node.required("code").isTextual()
                    || !node.required("retryable").isBoolean()
                    || !node.required("recovery_action").isTextual()) {
                throw new IllegalArgumentException("remote error envelope is invalid");
            }
            String code = node.required("code").asText();
            boolean retryable = node.required("retryable").asBoolean();
            RecoveryAction action = RecoveryAction.valueOf(
                    node.required("recovery_action").asText());
            requireRemoteAction(status, code, retryable, action);
            return new GraphReconciliationException(
                    code,
                    status,
                    retryable,
                    action,
                    "Python rejected Graph result reconciliation",
                    null);
        } catch (GraphReconciliationException exception) {
            throw exception;
        } catch (IllegalArgumentException | IOException exception) {
            throw GraphReconciliationException.protocol(
                    "Graph reconciliation error body is invalid", exception);
        }
    }

    private void requireExactResponse(
            ExecuteAgentRunRequest request,
            GraphReconcileResponse response) {
        RoomGraphCommand command = request.command();
        RoomGraphResult result = response.result();
        ObjectNode resultJson = mapper.valueToTree(result);
        JsonNode outputHash = resultJson.remove("output_hash");
        String actualResultHash = ContractJson.sha256Hex(resultJson);
        RoomGraphResult.ExecutionMetadata metadata = result.executionMetadata();
        RoomGraphCommand.InvocationContext invocation = command.invocationContext();
        boolean matches = response.threadId().equals(command.threadId())
                && response.commandId().equals(command.commandId())
                && response.requestHash().equals(command.requestHash())
                && response.logicalRunId().equals(request.logicalRunId())
                && response.attemptId().equals(request.attemptId())
                && response.graphKey().equals(command.graphKey())
                && response.graphVersion().equals(command.graphVersion())
                && response.checkpointSchemaVersion().equals(command.checkpointSchemaVersion())
                && response.checkpointId().equals(result.checkpointId())
                && response.resultHash().equals(result.outputHash())
                && outputHash != null
                && outputHash.isTextual()
                && response.resultHash().equals(outputHash.asText())
                && response.resultHash().equals(actualResultHash)
                && SHA256.matcher(response.resultHash()).matches()
                && SHA256.matcher(response.registryBindingHash()).matches()
                && metadata.promptVersion().equals(invocation.promptProfileId())
                && metadata.modelProfileId().equals(invocation.modelProfileId())
                && metadata.schemaVersion().equals(invocation.outputSchemaVersion())
                && metadata.policyVersion().equals(invocation.policyVersion())
                && metadata.guardrailVersion().equals(invocation.guardrailVersion());
        if (!matches) {
            throw GraphReconciliationException.protocol(
                    "Graph reconciliation result differs from its immutable command", null);
        }
    }

    private ObjectNode readObject(byte[] body) throws IOException {
        if (body == null || body.length == 0 || body.length > MAXIMUM_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Graph reconciliation body size is invalid");
        }
        JsonNode node = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(body);
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("Graph reconciliation body must be an object");
        }
        return object;
    }

    private static void requireRemoteAction(
            int status,
            String code,
            boolean retryable,
            RecoveryAction action) {
        if (action == RecoveryAction.RETRY_SAME_COMMAND) {
            if (!retryable || (status != 409 && status != 503)) {
                throw new IllegalArgumentException("retry action conflicts with HTTP status");
            }
            return;
        }
        if (retryable) {
            throw new IllegalArgumentException("non-retry action cannot be retryable");
        }
        if (action == RecoveryAction.CREATE_NEXT_ATTEMPT
                && (status != 409 || !"GRAPH_NEW_AGENT_ATTEMPT_REQUIRED".equals(code))) {
            throw new IllegalArgumentException("new-attempt action conflicts with error code");
        }
    }

    private static void requireResponseMetadata(GraphReconciliationHttpTransport.Response response) {
        Objects.requireNonNull(response, "response");
        List<String> contentType = headerValues(response.headers(), "content-type");
        List<String> contentEncoding = headerValues(response.headers(), "content-encoding");
        List<String> cacheControl = headerValues(response.headers(), "cache-control");
        if (contentType.size() != 1
                || !jsonUtf8(contentType.getFirst())
                || contentEncoding.size() > 1
                || (contentEncoding.size() == 1
                        && !"identity".equalsIgnoreCase(contentEncoding.getFirst().trim()))
                || cacheControl.stream()
                        .flatMap(value -> List.of(value.split(",")).stream())
                        .map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .noneMatch("no-store"::equals)) {
            throw GraphReconciliationException.protocol(
                    "Graph reconciliation response metadata is invalid", null);
        }
    }

    private static List<String> headerValues(
            Map<String, List<String>> headers,
            String expectedName) {
        List<String> values = new ArrayList<>();
        headers.forEach((name, candidates) -> {
            if (name.equalsIgnoreCase(expectedName)) {
                values.addAll(candidates);
            }
        });
        return List.copyOf(values);
    }

    private static boolean jsonUtf8(String value) {
        String[] parts = value.split(";", -1);
        if (!"application/json".equalsIgnoreCase(parts[0].trim())) {
            return false;
        }
        return parts.length == 1
                || (parts.length == 2
                        && "charset=utf-8".equalsIgnoreCase(parts[1].trim()));
    }

    private static URI endpoint(URI baseUri, boolean allowPlaintextTransport) {
        Objects.requireNonNull(baseUri, "baseUri");
        String scheme = baseUri.getScheme();
        if (baseUri.getHost() == null
                || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null
                || baseUri.getFragment() != null
                || (!("https".equalsIgnoreCase(scheme))
                        && !(allowPlaintextTransport && "http".equalsIgnoreCase(scheme)))) {
            throw new IllegalArgumentException("Graph reconciliation base URI is not trusted");
        }
        String normalized = baseUri.toString().endsWith("/")
                ? baseUri.toString()
                : baseUri + "/";
        return URI.create(normalized).resolve(PATH);
    }
}
