package com.example.dispute.workflow.infrastructure.agent;

import com.example.dispute.agentstream.application.AgentStreamProtocolException;
import com.example.dispute.agentstream.infrastructure.AgentNdjsonStreamClient;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.GraphCommandEnvelopeSigner;
import com.example.dispute.workflow.activity.agent.GraphReconciliationException;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Signed, bounded NDJSON client for the governed Graph command endpoint. */
public final class HttpAgentGraphCommandClient implements AgentGraphCommandClient {

    public static final String PATH = "internal/graphs/commands/stream";
    private static final String COMMAND_SCHEMA = "room-graph-command.schema.json";
    private static final Set<String> REMOTE_ERROR_FIELDS = Set.of("code", "retryable");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final GraphCommandHttpTransport transport;
    private final GraphCommandEnvelopeSigner envelopeSigner;
    private final AgentGraphReconciliationClient reconciliationClient;
    private final GraphStreamVisibilityPolicy visibilityPolicy;
    private final AgentPlatformContractCodec codec;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final Clock clock;

    public HttpAgentGraphCommandClient(
            GraphCommandHttpTransport transport,
            GraphCommandEnvelopeSigner envelopeSigner,
            AgentGraphReconciliationClient reconciliationClient,
            GraphStreamVisibilityPolicy visibilityPolicy,
            AgentPlatformContractCodec codec,
            ObjectMapper objectMapper,
            URI baseUri,
            Duration timeout) {
        this(
                transport,
                envelopeSigner,
                reconciliationClient,
                visibilityPolicy,
                codec,
                objectMapper,
                baseUri,
                timeout,
                false,
                Clock.systemUTC());
    }

    public HttpAgentGraphCommandClient(
            GraphCommandHttpTransport transport,
            GraphCommandEnvelopeSigner envelopeSigner,
            AgentGraphReconciliationClient reconciliationClient,
            GraphStreamVisibilityPolicy visibilityPolicy,
            AgentPlatformContractCodec codec,
            ObjectMapper objectMapper,
            URI baseUri,
            Duration timeout,
            boolean allowPlaintextTransport) {
        this(
                transport,
                envelopeSigner,
                reconciliationClient,
                visibilityPolicy,
                codec,
                objectMapper,
                baseUri,
                timeout,
                allowPlaintextTransport,
                Clock.systemUTC());
    }

    public HttpAgentGraphCommandClient(
            GraphCommandHttpTransport transport,
            GraphCommandEnvelopeSigner envelopeSigner,
            AgentGraphReconciliationClient reconciliationClient,
            GraphStreamVisibilityPolicy visibilityPolicy,
            AgentPlatformContractCodec codec,
            ObjectMapper objectMapper,
            URI baseUri,
            Duration timeout,
            boolean allowPlaintextTransport,
            Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.envelopeSigner = Objects.requireNonNull(envelopeSigner, "envelopeSigner");
        this.reconciliationClient =
                Objects.requireNonNull(reconciliationClient, "reconciliationClient");
        this.visibilityPolicy = Objects.requireNonNull(visibilityPolicy, "visibilityPolicy");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Graph command timeout must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.endpoint = endpoint(baseUri, allowPlaintextTransport);
    }

    @Override
    public RoomGraphResult execute(
            ExecuteAgentRunRequest request,
            ExecutionMode mode,
            Consumer<AgentStreamEvent> eventSink,
            AgentRunCancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(eventSink, "eventSink");
        Objects.requireNonNull(cancellationToken, "cancellationToken")
                .throwIfCancellationRequested();
        if (mode == ExecutionMode.RECONCILE_ONLY) {
            throw failure(
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                    "AGENT_RUN_RECONCILE_ONLY_ROUTE_VIOLATION",
                    "RECONCILE_ONLY must use the result-only client",
                    request,
                    request.publicSequenceOffset(),
                    false,
                    null);
        }

        RoomGraphCommand command = request.command();
        Duration requestTimeout = requestTimeout(request);
        Map<String, Set<String>> visibleFieldsByNode;
        byte[] body;
        GraphCommandEnvelopeSigner.SignedEnvelope envelope;
        try {
            visibleFieldsByNode = GraphStreamVisibilityPolicy.immutablePolicy(
                    visibilityPolicy.allowedVisibleFields(
                            GraphStreamVisibilityPolicy.Binding.from(command)));
            JsonNode commandJson = codec.encode(COMMAND_SCHEMA, command);
            body = mapper.writeValueAsBytes(commandJson);
            envelope = envelopeSigner.sign(command);
            requireEnvelope(command, envelope);
        } catch (IllegalArgumentException | IOException | NullPointerException exception) {
            throw protocolFailure(
                    request,
                    request.publicSequenceOffset(),
                    false,
                    "Graph command request or visibility binding is invalid",
                    exception);
        }

        GraphCommandHttpTransport.Request transportRequest;
        try {
            transportRequest = new GraphCommandHttpTransport.Request(
                    endpoint,
                    Map.of(
                            "Authorization", "Bearer " + envelope.compactJws(),
                            "Accept", "application/x-ndjson",
                            "Content-Type", "application/json; charset=utf-8",
                            "Content-Encoding", "identity",
                            "Cache-Control", "no-store",
                            "X-Agent-Run-Id", request.agentRunId(),
                            "traceparent", command.traceparent()),
                    body,
                    requestTimeout,
                    GraphCommandHttpTransport.MAXIMUM_LINE_BYTES,
                    GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw protocolFailure(
                    request,
                    request.publicSequenceOffset(),
                    false,
                    "Graph command transport request is invalid",
                    exception);
        }

        StreamSession session = new StreamSession(request, visibleFieldsByNode, eventSink);
        try {
            transport.stream(transportRequest, cancellationToken, session);
        } catch (SinkFailure failure) {
            throw failure.original();
        } catch (StreamProtocolFailure failure) {
            throw protocolFailure(
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    failure.getMessage(),
                    failure.getCause());
        } catch (GraphCommandTransportException failure) {
            cancellationToken.throwIfCancellationRequested();
            if (failure.protocolViolation()) {
                throw protocolFailure(
                        request,
                        session.lastPublicSequence(),
                        session.publicOutputEmitted(),
                        "Graph command transport violated the protocol",
                        failure);
            }
            throw failure(
                    session.hasFinalCandidate()
                            ? AgentRunRecoveryAction.RECONCILE_TERMINAL
                            : AgentRunRecoveryAction.RETRY_SAME_COMMAND,
                    "GRAPH_COMMAND_TRANSPORT_FAILED",
                    "Graph command transport failed",
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    failure);
        } catch (AgentRunExecutionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            cancellationToken.throwIfCancellationRequested();
            throw failure(
                    session.hasFinalCandidate()
                            ? AgentRunRecoveryAction.RECONCILE_TERMINAL
                            : AgentRunRecoveryAction.RETRY_SAME_COMMAND,
                    "GRAPH_COMMAND_TRANSPORT_FAILED",
                    "Graph command transport failed",
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    failure);
        }

        cancellationToken.throwIfCancellationRequested();
        int statusCode;
        try {
            statusCode = session.statusCode();
        } catch (StreamProtocolFailure failure) {
            throw protocolFailure(
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    failure.getMessage(),
                    failure.getCause());
        }
        if (statusCode != 200) {
            throw decodeRemoteError(request, session);
        }
        try {
            session.assertComplete();
        } catch (AgentStreamProtocolException failure) {
            throw protocolFailure(
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    "Graph command stream ended without a valid terminal",
                    failure);
        }

        AgentStreamEvent terminal = session.terminalEvent();
        cancellationToken.throwIfCancellationRequested();
        try {
            session.publishTerminal();
        } catch (SinkFailure failure) {
            throw failure.original();
        }
        if (terminal.eventType() == StreamEventType.ATTEMPT_ABORTED) {
            throw failure(
                    AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                    terminal.payload().reasonCode(),
                    "Graph command attempt was durably aborted",
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    null);
        }
        if (terminal.eventType() == StreamEventType.ERROR) {
            throw failure(
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                    terminal.payload().errorCode(),
                    "Graph command reached a logical error terminal",
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    null);
        }
        if (terminal.eventType() != StreamEventType.FINAL) {
            throw protocolFailure(
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    "Graph command returned an unsupported terminal",
                    null);
        }
        return reconcileFinal(request, terminal, session, cancellationToken);
    }

    private RoomGraphResult reconcileFinal(
            ExecuteAgentRunRequest request,
            AgentStreamEvent terminal,
            StreamSession session,
            AgentRunCancellationToken cancellationToken) {
        GraphReconcileResponse response;
        try {
            response = reconciliationClient.reconcile(request, cancellationToken);
        } catch (GraphReconciliationException failure) {
            cancellationToken.throwIfCancellationRequested();
            if (failure.recoveryAction() == AgentRunRecoveryAction.RETRY_SAME_COMMAND
                    || failure.recoveryAction() == AgentRunRecoveryAction.RECONCILE_TERMINAL) {
                throw failure(
                        AgentRunRecoveryAction.RECONCILE_TERMINAL,
                        failure.errorCode(),
                        "Graph final requires result-only reconciliation",
                        request,
                        session.lastPublicSequence(),
                        session.publicOutputEmitted(),
                        failure);
            }
            throw failure(
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                    failure.errorCode(),
                    "Graph final reconciliation was rejected",
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    failure);
        } catch (RuntimeException failure) {
            cancellationToken.throwIfCancellationRequested();
            throw failure(
                    AgentRunRecoveryAction.RECONCILE_TERMINAL,
                    "GRAPH_FINAL_RECONCILIATION_FAILED",
                    "Graph final requires result-only reconciliation",
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    failure);
        }

        try {
            requireFinalReconciliation(request, terminal, response);
            return response.result();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw protocolFailure(
                    request,
                    session.lastPublicSequence(),
                    session.publicOutputEmitted(),
                    "Graph final differs from its reconciled result",
                    exception);
        }
    }

    private void requireFinalReconciliation(
            ExecuteAgentRunRequest request,
            AgentStreamEvent terminal,
            GraphReconcileResponse response) {
        Objects.requireNonNull(response, "reconciliation response");
        RoomGraphCommand command = request.command();
        RoomGraphResult result = response.result();
        Objects.requireNonNull(result, "reconciliation result");
        ObjectNode resultJson = mapper.valueToTree(result);
        JsonNode declaredOutputHash = resultJson.remove("output_hash");
        String computedOutputHash = ContractJson.sha256Hex(resultJson);
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
                && response.resultRef().equals(terminal.payload().finalResultRef())
                && response.resultHash().equals(terminal.payload().finalResultHash())
                && response.resultHash().equals(result.outputHash())
                && response.resultHash().equals(computedOutputHash)
                && declaredOutputHash != null
                && declaredOutputHash.isTextual()
                && response.resultHash().equals(declaredOutputHash.asText())
                && SHA256.matcher(response.resultHash()).matches()
                && SHA256.matcher(response.registryBindingHash()).matches()
                && command.commandId().equals(result.commandId())
                && request.logicalRunId().equals(result.logicalRunId())
                && request.attemptId().equals(result.attemptId())
                && command.graphKey().equals(result.graphKey())
                && command.graphVersion().equals(result.graphVersion())
                && metadata != null
                && invocation.promptProfileId().equals(metadata.promptVersion())
                && invocation.modelProfileId().equals(metadata.modelProfileId())
                && invocation.outputSchemaVersion().equals(metadata.schemaVersion())
                && invocation.policyVersion().equals(metadata.policyVersion())
                && invocation.guardrailVersion().equals(metadata.guardrailVersion());
        if (!matches) {
            throw new IllegalArgumentException("final reconciliation binding mismatch");
        }
    }

    private AgentRunExecutionException decodeRemoteError(
            ExecuteAgentRunRequest request, StreamSession session) {
        try {
            String line = session.remoteErrorLine();
            JsonNode node = mapper.readTree(line);
            if (!(node instanceof ObjectNode object)) {
                throw new IllegalArgumentException("remote error must be an object");
            }
            Set<String> fields = object.properties().stream()
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            if (!fields.equals(REMOTE_ERROR_FIELDS)
                    || !object.required("code").isTextual()
                    || !object.required("retryable").isBoolean()) {
                throw new IllegalArgumentException("remote error envelope is invalid");
            }
            String code = object.required("code").asText();
            boolean retryable = object.required("retryable").asBoolean();
            if (!ERROR_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("remote error code is invalid");
            }
            AgentRunRecoveryAction action = remoteRecoveryAction(
                    session.statusCode(), code, retryable);
            return failure(
                    action,
                    code,
                    "Python rejected the Graph command before streaming",
                    request,
                    request.publicSequenceOffset(),
                    false,
                    null);
        } catch (AgentRunExecutionException failure) {
            return failure;
        } catch (IllegalArgumentException | IOException | StreamProtocolFailure exception) {
            return protocolFailure(
                    request,
                    request.publicSequenceOffset(),
                    false,
                    "Graph command error response is invalid",
                    exception);
        }
    }

    private static AgentRunRecoveryAction remoteRecoveryAction(
            int status, String code, boolean retryable) {
        if (status == 409 && "GRAPH_NEW_AGENT_ATTEMPT_REQUIRED".equals(code)) {
            if (!retryable) {
                throw new IllegalArgumentException("new-attempt response must be retryable");
            }
            return AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT;
        }
        if (status == 409 && "GRAPH_CONTRACT_REJECTED".equals(code) && !retryable) {
            return AgentRunRecoveryAction.RECONCILE_TERMINAL;
        }
        if (status == 503 && retryable) {
            return AgentRunRecoveryAction.RETRY_SAME_COMMAND;
        }
        if (retryable) {
            throw new IllegalArgumentException("remote retry flag has no closed recovery action");
        }
        return AgentRunRecoveryAction.FAIL_LOGICAL_RUN;
    }

    private static AgentRunExecutionException protocolFailure(
            ExecuteAgentRunRequest request,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            String message,
            Throwable cause) {
        return failure(
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                "AGENT_RUN_STREAM_V2_INVALID",
                message,
                request,
                lastSequenceNo,
                publicOutputEmitted,
                cause);
    }

    private static AgentRunExecutionException failure(
            AgentRunRecoveryAction action,
            String code,
            String message,
            ExecuteAgentRunRequest request,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            Throwable cause) {
        Objects.requireNonNull(request, "request");
        return switch (action) {
            case RETRY_SAME_COMMAND -> AgentRunExecutionException.retrySameCommand(
                    code, message, lastSequenceNo, publicOutputEmitted, cause);
            case CREATE_NEXT_ATTEMPT -> AgentRunExecutionException.createNextAttempt(
                    code, message, lastSequenceNo, publicOutputEmitted, cause);
            case RECONCILE_TERMINAL -> AgentRunExecutionException.reconcileTerminal(
                    code, message, lastSequenceNo, publicOutputEmitted, cause);
            case FAIL_LOGICAL_RUN -> AgentRunExecutionException.failLogicalRun(
                    code, message, lastSequenceNo, publicOutputEmitted, cause);
        };
    }

    private static void requireSuccessMetadata(
            GraphCommandHttpTransport.ResponseHead response, String expectedRunId) {
        requireCommonMetadata(response);
        List<String> contentType = headerValues(response.headers(), "content-type");
        List<String> runId = headerValues(response.headers(), "x-agent-run-id");
        if (response.statusCode() != 200
                || contentType.size() != 1
                || !mediaTypeUtf8(contentType.getFirst(), "application/x-ndjson")
                || runId.size() != 1
                || !expectedRunId.equals(runId.getFirst())) {
            throw new StreamProtocolFailure("Graph command response metadata is invalid", null);
        }
    }

    private static void requireErrorMetadata(GraphCommandHttpTransport.ResponseHead response) {
        requireCommonMetadata(response);
        List<String> contentType = headerValues(response.headers(), "content-type");
        if (response.statusCode() < 400
                || response.statusCode() > 599
                || contentType.size() != 1
                || !mediaTypeUtf8(contentType.getFirst(), "application/json")) {
            throw new StreamProtocolFailure("Graph command error metadata is invalid", null);
        }
    }

    private static void requireCommonMetadata(GraphCommandHttpTransport.ResponseHead response) {
        Objects.requireNonNull(response, "response");
        List<String> contentEncoding = headerValues(response.headers(), "content-encoding");
        Set<String> cacheDirectives = headerValues(response.headers(), "cache-control").stream()
                .flatMap(value -> List.of(value.split(",", -1)).stream())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!response.uri().isAbsolute()
                || contentEncoding.size() > 1
                || (contentEncoding.size() == 1
                        && !"identity".equalsIgnoreCase(contentEncoding.getFirst().trim()))
                || !cacheDirectives.contains("no-store")
                || !cacheDirectives.contains("no-transform")) {
            throw new StreamProtocolFailure("Graph command response metadata is invalid", null);
        }
    }

    private static List<String> headerValues(
            Map<String, List<String>> headers, String expectedName) {
        List<String> values = new ArrayList<>();
        headers.forEach((name, candidates) -> {
            if (name.equalsIgnoreCase(expectedName)) {
                values.addAll(candidates);
            }
        });
        return List.copyOf(values);
    }

    private static boolean mediaTypeUtf8(String value, String mediaType) {
        String[] parts = value.split(";", -1);
        return parts.length >= 1
                && parts.length <= 2
                && mediaType.equalsIgnoreCase(parts[0].trim())
                && (parts.length == 1
                        || "charset=utf-8".equalsIgnoreCase(parts[1].trim()));
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
            throw new IllegalArgumentException("Graph command base URI is not trusted");
        }
        String normalized = baseUri.toString().endsWith("/")
                ? baseUri.toString()
                : baseUri + "/";
        return URI.create(normalized).resolve(PATH);
    }

    private Duration requestTimeout(ExecuteAgentRunRequest request) {
        Duration remaining = Duration.between(clock.instant(), request.command().deadlineAt());
        if (remaining.isZero() || remaining.isNegative()) {
            throw failure(
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                    "GRAPH_COMMAND_DEADLINE_EXCEEDED",
                    "Graph command deadline elapsed before dispatch",
                    request,
                    request.publicSequenceOffset(),
                    false,
                    null);
        }
        return remaining.compareTo(timeout) < 0 ? remaining : timeout;
    }

    private static void requireEnvelope(
            RoomGraphCommand command, GraphCommandEnvelopeSigner.SignedEnvelope envelope) {
        Objects.requireNonNull(envelope, "signed envelope");
        String keyId = command.invocationContext().envelopeKeyId();
        if (!keyId.equals(envelope.keyId())
                || !ERROR_CODE.matcher(envelope.keyId()).matches()
                || !ERROR_CODE.matcher(envelope.jti()).matches()
                || envelope.jti().equals(command.invocationContext().envelopeNonce())
                || !GraphCommandEnvelopeSigner.SignedEnvelope.isWellFormedCompactJws(
                        envelope.compactJws())) {
            throw new IllegalArgumentException(
                    "Graph command credential conflicts with its immutable command");
        }
    }

    private final class StreamSession implements GraphCommandHttpTransport.Listener {
        private final ExecuteAgentRunRequest request;
        private final Consumer<AgentStreamEvent> eventSink;
        private final AgentNdjsonStreamClient.V2ProtocolState protocolState;
        private boolean responseSeen;
        private boolean metadataAccepted;
        private int statusCode;
        private String remoteErrorLine;
        private AgentStreamEvent terminalEvent;
        private long lastPublicSequence;
        private boolean publicOutputEmitted;

        private StreamSession(
                ExecuteAgentRunRequest request,
                Map<String, Set<String>> visibleFieldsByNode,
                Consumer<AgentStreamEvent> eventSink) {
            this.request = request;
            this.eventSink = eventSink;
            this.protocolState = new AgentNdjsonStreamClient.V2ProtocolState(
                    request.logicalRunId(),
                    request.attemptId(),
                    request.command().actorScope().audience(),
                    visibleFieldsByNode);
        }

        @Override
        public void onResponse(GraphCommandHttpTransport.ResponseHead response) {
            if (responseSeen) {
                throw new StreamProtocolFailure(
                        "Graph command transport emitted duplicate response metadata", null);
            }
            responseSeen = true;
            if (response == null || !endpoint.equals(response.uri())) {
                throw new StreamProtocolFailure(
                        "Graph command response URI differs from its endpoint", null);
            }
            statusCode = response.statusCode();
            if (statusCode == 200) {
                requireSuccessMetadata(response, request.agentRunId());
            } else {
                requireErrorMetadata(response);
            }
            metadataAccepted = true;
        }

        @Override
        public void onLine(String line) {
            if (!metadataAccepted) {
                throw new StreamProtocolFailure(
                        "Graph command line arrived before valid response metadata", null);
            }
            if (statusCode != 200) {
                if (remoteErrorLine != null) {
                    throw new StreamProtocolFailure(
                            "Graph command returned multiple error envelopes", null);
                }
                remoteErrorLine = Objects.requireNonNull(line, "line");
                return;
            }

            AgentStreamEvent event;
            try {
                event = AgentNdjsonStreamClient.parseV2Line(mapper, line, protocolState);
            } catch (AgentStreamProtocolException failure) {
                throw new StreamProtocolFailure(
                        "Graph command emitted an invalid Agent Stream v2 event", failure);
            }
            if (isTerminal(event.eventType())) {
                terminalEvent = event;
                return;
            }
            publish(event);
        }

        private void assertComplete() {
            if (!responseSeen || !metadataAccepted || statusCode != 200) {
                throw new AgentStreamProtocolException(
                        "agent stream v2 response metadata is incomplete");
            }
            protocolState.assertComplete();
            if (terminalEvent == null) {
                throw new AgentStreamProtocolException(
                        "agent stream v2 terminal payload is missing");
            }
        }

        private void publishTerminal() {
            publish(Objects.requireNonNull(terminalEvent, "terminalEvent"));
        }

        private void publish(AgentStreamEvent event) {
            try {
                eventSink.accept(event);
                lastPublicSequence = publicSequence(event);
                publicOutputEmitted |= event.eventType() == StreamEventType.VISIBLE_DELTA;
            } catch (RuntimeException failure) {
                throw new SinkFailure(failure);
            }
        }

        private long publicSequence(AgentStreamEvent event) {
            try {
                return event.sequenceNo() < 1
                        ? request.publicSequenceOffset()
                        : Math.addExact(
                                event.sequenceNo(),
                                (long) request.publicSequenceOffset());
            } catch (ArithmeticException failure) {
                throw new StreamProtocolFailure(
                        "Graph command sequence exceeds the public sequence range", failure);
            }
        }

        private int statusCode() {
            if (!responseSeen || !metadataAccepted) {
                throw new StreamProtocolFailure(
                        "Graph command response metadata is missing", null);
            }
            return statusCode;
        }

        private String remoteErrorLine() {
            if (remoteErrorLine == null) {
                throw new StreamProtocolFailure(
                        "Graph command error body is missing", null);
            }
            return remoteErrorLine;
        }

        private AgentStreamEvent terminalEvent() {
            return Objects.requireNonNull(terminalEvent, "terminalEvent");
        }

        private long lastPublicSequence() {
            return Math.max(request.publicSequenceOffset(), lastPublicSequence);
        }

        private boolean publicOutputEmitted() {
            return publicOutputEmitted;
        }

        private boolean hasFinalCandidate() {
            return terminalEvent != null && terminalEvent.eventType() == StreamEventType.FINAL;
        }
    }

    private static boolean isTerminal(StreamEventType eventType) {
        return eventType == StreamEventType.FINAL
                || eventType == StreamEventType.ERROR
                || eventType == StreamEventType.ATTEMPT_ABORTED;
    }

    private static final class StreamProtocolFailure extends RuntimeException {
        private StreamProtocolFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class SinkFailure extends RuntimeException {
        private final RuntimeException original;

        private SinkFailure(RuntimeException original) {
            super("Graph command event sink failed", original);
            this.original = original;
        }

        private RuntimeException original() {
            return original;
        }
    }
}
