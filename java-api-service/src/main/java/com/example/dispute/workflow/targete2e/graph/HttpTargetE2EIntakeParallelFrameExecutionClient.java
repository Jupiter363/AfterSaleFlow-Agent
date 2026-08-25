package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ProgressListener;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAdmissionAuthorityResolver;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameManifest;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameRetryAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSealCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSetAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionAction;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionLane;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionPlan;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressKind;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.SlotState;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandTransportException;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.FrameAuthority;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.EncodedAdmissionReceipt;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.GenerationReset;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.Interrupted;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.ProjectionItem;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.Sealed;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.Started;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.StreamAuthority;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.TechnicalEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Incrementally translates Python-private Frame events into Java-owned public V4 staging. */
public final class HttpTargetE2EIntakeParallelFrameExecutionClient
        implements IntakeParallelFrameExecutionClient {

    public static final String PATH = "internal/graphs/target-e2e/commands/stream";
    static final String AUTHORITY_HEADER = "X-Intake-Parallel-Authority";
    static final String ADMISSION_HEADER = "X-Intake-Parallel-Admission";
    static final String PHASE_HEADER = "X-Intake-Parallel-Phase";
    private static final String FRAME_SET_HEADER = "X-Intake-Frame-Set-Id";
    private static final String PROJECTION_REGISTRY_VERSION = "intake-projection-registry.v1";
    private static final Set<String> REMOTE_ERROR_FIELDS = Set.of("code", "retryable");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    private final String activationId;
    private final GraphCommandHttpTransport transport;
    private final TargetE2EAgentRunIdentityResolver identityResolver;
    private final TargetE2EGraphEnvelopeCodec envelopeCodec;
    private final TargetE2EGraphEnvelopeSigner signer;
    private final GraphRegistryBindingPolicy registryBindingPolicy;
    private final IntakeParallelFrameAdmissionAuthorityResolver admissionAuthorityResolver;
    private final IntakeParallelFrameStagingPort staging;
    private final ObjectMapper mapper;
    private final TargetE2EIntakeParallelTransportCodec technicalCodec;
    private final URI endpoint;
    private final Duration timeout;

    public HttpTargetE2EIntakeParallelFrameExecutionClient(
            String activationId,
            GraphTransportBundle transportBundle,
            TargetE2EAgentRunIdentityResolver identityResolver,
            TargetE2EGraphEnvelopeCodec envelopeCodec,
            TargetE2EGraphEnvelopeSigner signer,
            GraphRegistryBindingPolicy registryBindingPolicy,
            IntakeParallelFrameAdmissionAuthorityResolver admissionAuthorityResolver,
            IntakeParallelFrameStagingPort staging,
            ObjectMapper objectMapper,
            URI baseUri,
            Duration timeout) {
        TargetE2EGraphCommandEnvelope.requirePattern(
                activationId, TargetE2EGraphCommandEnvelope.ACTIVATION_ID, "activationId");
        TargetE2EGraphTransportPolicy.VerifiedBundle verified =
                TargetE2EGraphTransportPolicy.requireVerified(transportBundle);
        URI trustedBaseUri = TargetE2EGraphTransportPolicy.requireTrustedBaseUri(baseUri);
        if (!trustedBaseUri.equals(verified.boundBaseUri())) {
            throw new IllegalArgumentException(
                    "parallel target Graph base URI differs from its mTLS transport");
        }
        this.activationId = activationId;
        this.transport = verified.commandTransport();
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.envelopeCodec = Objects.requireNonNull(envelopeCodec, "envelopeCodec");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.registryBindingPolicy =
                Objects.requireNonNull(registryBindingPolicy, "registryBindingPolicy");
        this.admissionAuthorityResolver = Objects.requireNonNull(
                admissionAuthorityResolver, "admissionAuthorityResolver");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.technicalCodec = new TargetE2EIntakeParallelTransportCodec(this.mapper);
        this.endpoint = trustedBaseUri.resolve(PATH);
        this.timeout = HttpTargetE2EGraphReconciliationClient.requireTimeout(timeout);
    }

    @Override
    public FrameExecutionReceipt executeOrResume(
            ExecuteAgentRunRequest request,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken) {
        requireParallel(request);
        Objects.requireNonNull(progressListener, "progressListener");
        Objects.requireNonNull(cancellationToken, "cancellationToken")
                .throwIfCancellationRequested();
        long roomFencingToken = Objects.requireNonNull(
                        identityResolver.resolve(request),
                        "durable AgentRun identity resolver returned no identity")
                .requireExact(request);
        RoomGraphCommand command = request.command();
        GraphRegistryBindingPolicy.ExpectedBinding registryBinding =
                GraphRegistryBindingPolicy.requireExpected(
                        registryBindingPolicy, GraphStreamVisibilityPolicy.Binding.from(command));
        PreparedAdmission prepared = prepare(
                request,
                roomFencingToken,
                registryBinding,
                cancellationToken);
        if (prepared.executionPlan().allSealed()) {
            var completion = staging
                    .findExactThreeCompletion(
                            prepared.executionPlan().frameSetId(),
                            request.agentRunId(),
                            request.attemptId())
                    .orElseThrow(() -> TargetE2EGraphClientException.protocol(
                            "parallel all-sealed completion is absent", null));
            return new FrameExecutionReceipt(
                    completion.frameSetId(),
                    completion.lastSequenceNo(),
                    completion.publicOutputEmitted());
        }
        TargetE2ESealedGraphCommand sealed = envelopeCodec.sealParallelCommand(
                activationId,
                roomFencingToken,
                command,
                registryBinding,
                signer,
                TargetE2EGraphEnvelopeSigner.ParallelDeliveryBinding.execute(
                        prepared.encodedReceipt().receiptSha256()));
        StreamSession session = new StreamSession(
                request,
                sealed.envelope(),
                progressListener,
                cancellationToken,
                prepared.executionAuthority(),
                prepared.frameSetReceipt(),
                prepared.executionPlan(),
                prepared.encodedReceipt().receiptSha256());
        GraphCommandHttpTransport.Request transportRequest = new GraphCommandHttpTransport.Request(
                endpoint,
                requestHeaders(sealed, "EXECUTE", prepared.encodedReceipt().headerValue()),
                sealed.body(),
                timeout,
                GraphCommandHttpTransport.MAXIMUM_PARALLEL_LINE_BYTES,
                GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES);
        try {
            transport.stream(transportRequest, cancellationToken, session);
            cancellationToken.throwIfCancellationRequested();
            return session.finish();
        } catch (TargetE2EGraphClientException failure) {
            throw failure;
        } catch (GraphCommandTransportException failure) {
            cancellationToken.throwIfCancellationRequested();
            if (failure.protocolViolation()) {
                throw TargetE2EGraphClientException.protocol(
                        "parallel target Graph transport violated the protocol", failure);
            }
            throw TargetE2EGraphClientException.transport(
                    "parallel target Graph transport failed", failure);
        } catch (IllegalArgumentException failure) {
            throw TargetE2EGraphClientException.protocol(
                    "parallel target Graph stream is invalid", failure);
        } catch (RuntimeException failure) {
            cancellationToken.throwIfCancellationRequested();
            throw failure;
        }
    }

    private Map<String, String> requestHeaders(
            TargetE2ESealedGraphCommand sealed,
            String phase,
            String admissionReceipt) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + sealed.credential().compactJws());
        headers.put("Accept", "PREPARE".equals(phase) ? "application/json" : "application/x-ndjson");
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Content-Encoding", "identity");
        headers.put("Cache-Control", "no-store");
        headers.put("X-Agent-Run-Id", sealed.envelope().command().logicalRunId());
        headers.put("traceparent", sealed.envelope().command().traceparent());
        headers.put(PHASE_HEADER, phase);
        if (admissionReceipt != null) {
            headers.put(ADMISSION_HEADER, admissionReceipt);
        }
        return Map.copyOf(headers);
    }

    private PreparedAdmission prepare(
            ExecuteAgentRunRequest request,
            long roomFencingToken,
            GraphRegistryBindingPolicy.ExpectedBinding registryBinding,
            AgentRunCancellationToken cancellationToken) {
        TargetE2ESealedGraphCommand sealed = envelopeCodec.sealParallelCommand(
                activationId,
                roomFencingToken,
                request.command(),
                registryBinding,
                signer,
                TargetE2EGraphEnvelopeSigner.ParallelDeliveryBinding.prepare());
        PreparationSession preparation = new PreparationSession(request, sealed.envelope());
        GraphCommandHttpTransport.Request transportRequest = new GraphCommandHttpTransport.Request(
                endpoint,
                requestHeaders(sealed, "PREPARE", null),
                sealed.body(),
                timeout,
                GraphCommandHttpTransport.MAXIMUM_LINE_BYTES,
                GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES);
        try {
            transport.stream(transportRequest, cancellationToken, preparation);
            cancellationToken.throwIfCancellationRequested();
            StreamAuthority authority = preparation.finish();
            requirePreparedStreamAuthority(request, authority);
            FrameSetAdmission admission = preparedAdmission(request, authority);
            IntakeParallelFrameStagingPort.FrameSetReceipt frameSetReceipt = staging.admit(admission);
            ExecutionPlan executionPlan = staging.planExecution(admission);
            StreamAuthority executionAuthority = executionAuthority(authority, executionPlan);
            EncodedAdmissionReceipt encodedReceipt = technicalCodec.encodeAdmissionReceipt(
                    request.command().requestHash(),
                    frameSetReceipt.receiptId(),
                    authority,
                    executionPlan);
            return new PreparedAdmission(
                    authority,
                    executionAuthority,
                    frameSetReceipt,
                    executionPlan,
                    encodedReceipt);
        } catch (TargetE2EGraphClientException failure) {
            throw failure;
        } catch (GraphCommandTransportException failure) {
            cancellationToken.throwIfCancellationRequested();
            if (failure.protocolViolation()) {
                throw TargetE2EGraphClientException.protocol(
                        "parallel target Graph preparation violated the protocol", failure);
            }
            throw TargetE2EGraphClientException.transport(
                    "parallel target Graph preparation failed", failure);
        } catch (IllegalArgumentException failure) {
            throw TargetE2EGraphClientException.protocol(
                    "parallel target Graph preparation is invalid", failure);
        }
    }

    private FrameSetAdmission preparedAdmission(
            ExecuteAgentRunRequest request, StreamAuthority authority) {
        IntakeParallelFrameAdmissionAuthorityResolver.AdmissionAuthority durableAuthority =
                admissionAuthorityResolver.resolve(request);
        List<FrameManifest> manifests = authority.frames().stream()
                .map(frame -> new FrameManifest(
                        frame.frameType(),
                        frame.generation(),
                        frame.frameId(),
                        frame.frameType().promptProfileId(),
                        frame.frameType().outputSchemaId(),
                        request.command().invocationContext().modelProfileId(),
                        frame.frameModelInputSha256(),
                        frame.framePromptSha256()))
                .toList();
        FrameAuthority first = authority.frames().getFirst();
        return new FrameSetAdmission(
                authority.frameSetId(),
                request.agentRunId(),
                request.attemptId(),
                request.command().commandId(),
                request.command().tenantSurrogate(),
                request.command().caseId(),
                Objects.requireNonNull(request.command().roomId(), "parallel roomId"),
                request.command().roomEpoch(),
                durableAuthority.fencingToken(),
                request.command().threadId(),
                durableAuthority.actorScopeSha256(),
                durableAuthority.agentSessionId(),
                durableAuthority.eventAuthority(),
                first.contextEnvelopeSha256(),
                first.modelContextViewSha256(),
                "PARALLEL_FRAMES_V1",
                PROJECTION_REGISTRY_VERSION,
                request.command().invocationContext().modelProfileId(),
                request.command().deadlineAt(),
                manifests);
    }

    private final class PreparationSession implements GraphCommandHttpTransport.Listener {

        private final ExecuteAgentRunRequest request;
        private final TargetE2EGraphCommandEnvelope envelope;
        private boolean responseReceived;
        private int statusCode;
        private StreamAuthority authority;
        private String responseLine;

        private PreparationSession(
                ExecuteAgentRunRequest request, TargetE2EGraphCommandEnvelope envelope) {
            this.request = request;
            this.envelope = envelope;
        }

        @Override
        public void onResponse(GraphCommandHttpTransport.ResponseHead response) {
            if (responseReceived) {
                throw protocol("parallel preparation returned duplicate response metadata", null);
            }
            responseReceived = true;
            statusCode = response.statusCode();
            if (statusCode >= 300 && statusCode <= 399) {
                throw protocol("parallel preparation redirect is forbidden", null);
            }
            if (statusCode != 200) {
                requireRemoteErrorMetadata(
                        response, "parallel preparation error metadata is invalid");
                return;
            }
            if (!sharedResponseMetadataIsValid(response, false)
                    || !endpoint.equals(response.uri())
                    || !"application/json".equalsIgnoreCase(
                            mediaType(singleHeader(response.headers(), "Content-Type")))
                    || !request.agentRunId().equals(
                            singleHeader(response.headers(), "X-Agent-Run-Id"))
                    || !"agent-stream.v4".equals(
                            singleHeader(response.headers(), "X-Agent-Stream-Protocol"))
                    || !envelope.executionLane().equals(
                            singleHeader(response.headers(), "X-Graph-Execution-Lane"))
                    || !activationId.equals(
                            singleHeader(response.headers(), "X-Graph-Activation-Id"))) {
                throw protocol("parallel preparation response metadata drifted", null);
            }
            authority = technicalCodec.decodeAuthority(
                    singleHeader(response.headers(), AUTHORITY_HEADER));
            if (!authority.frameSetId().equals(
                    singleHeader(response.headers(), FRAME_SET_HEADER))) {
                throw protocol("parallel preparation frame-set header drifted", null);
            }
        }

        @Override
        public void onLine(String line) {
            if (!responseReceived) {
                throw protocol("parallel preparation emitted data before response metadata", null);
            }
            if (responseLine != null) {
                throw protocol("parallel preparation returned multiple response lines", null);
            }
            responseLine = Objects.requireNonNull(line, "line");
        }

        private StreamAuthority finish() {
            if (!responseReceived) {
                throw protocol("parallel preparation response metadata is missing", null);
            }
            if (statusCode != 200) {
                throw parseRemoteFailure(
                        responseLine, "Python rejected parallel Intake preparation");
            }
            if (authority == null || responseLine == null) {
                throw protocol("parallel preparation did not return durable authority", null);
            }
            try {
                JsonNode body = mapper.readTree(responseLine);
                Set<String> fields = new HashSet<>();
                body.fieldNames().forEachRemaining(fields::add);
                if (!body.isObject()
                        || !fields.equals(Set.of("schema_version"))
                        || !"intake.parallel-prepared.v1"
                                .equals(body.required("schema_version").asText())) {
                    throw new IllegalArgumentException("parallel preparation body is invalid");
                }
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw protocol("parallel preparation body is invalid", failure);
            }
            return authority;
        }
    }

    private record PreparedAdmission(
            StreamAuthority preparedAuthority,
            StreamAuthority executionAuthority,
            IntakeParallelFrameStagingPort.FrameSetReceipt frameSetReceipt,
            ExecutionPlan executionPlan,
            EncodedAdmissionReceipt encodedReceipt) {}

    private final class StreamSession implements GraphCommandHttpTransport.Listener {

        private final ExecuteAgentRunRequest request;
        private final TargetE2EGraphCommandEnvelope envelope;
        private final ProgressListener progressListener;
        private final AgentRunCancellationToken cancellationToken;
        private final EnumMap<FrameType, FrameState> frames = new EnumMap<>(FrameType.class);
        private boolean responseReceived;
        private int statusCode;
        private String remoteErrorLine;
        private StreamAuthority authority;
        private IntakeParallelFrameStagingPort.FrameSetReceipt frameSetReceipt;
        private final ExecutionPlan executionPlan;
        private String streamSessionId;
        private long transportSequence;
        private long lastSequence = -1;
        private boolean publicOutputEmitted;

        private StreamSession(
                ExecuteAgentRunRequest request,
                TargetE2EGraphCommandEnvelope envelope,
                ProgressListener progressListener,
                AgentRunCancellationToken cancellationToken,
                StreamAuthority authority,
                IntakeParallelFrameStagingPort.FrameSetReceipt frameSetReceipt,
                ExecutionPlan executionPlan,
                String admissionReceiptSha256) {
            this.request = request;
            this.envelope = envelope;
            this.progressListener = progressListener;
            this.cancellationToken = cancellationToken;
            this.authority = Objects.requireNonNull(authority, "authority");
            this.frameSetReceipt = Objects.requireNonNull(frameSetReceipt, "frameSetReceipt");
            this.executionPlan = Objects.requireNonNull(executionPlan, "executionPlan");
            this.streamSessionId = "IPSS_" + ContractJson.sha256Hex(
                            authorityNode(authority).put(
                                    "admission_receipt_sha256",
                                    Objects.requireNonNull(
                                            admissionReceiptSha256,
                                            "admissionReceiptSha256")))
                    .substring(0, 32);
            authority.frames().forEach(frame -> {
                ExecutionLane lane = executionPlan.lanes().get(frame.frameType());
                if (lane == null
                        || lane.generation() != frame.generation()
                        || !lane.frameId().equals(frame.frameId())) {
                    throw new IllegalArgumentException(
                            "parallel execution authority differs from its plan");
                }
                frames.put(
                        frame.frameType(),
                        new FrameState(authority.frameSetId(), frame, lane));
            });
        }

        @Override
        public void onResponse(GraphCommandHttpTransport.ResponseHead response) {
            if (responseReceived) {
                throw protocol("parallel target Graph returned duplicate response metadata", null);
            }
            responseReceived = true;
            statusCode = response.statusCode();
            if (statusCode >= 300 && statusCode <= 399) {
                throw protocol("parallel target Graph redirect is forbidden", null);
            }
            if (statusCode != 200) {
                requireErrorMetadata(response);
                return;
            }
            requireSuccessMetadata(response);
            StreamAuthority responseAuthority = technicalCodec.decodeAuthority(
                    singleHeader(response.headers(), AUTHORITY_HEADER));
            if (!responseAuthority.frameSetId()
                    .equals(singleHeader(response.headers(), FRAME_SET_HEADER))) {
                throw protocol("parallel target Graph frame-set header drifted", null);
            }
            if (!authority.equals(responseAuthority)) {
                throw protocol("parallel execution authority differs from preparation", null);
            }
        }

        @Override
        public void onLine(String line) {
            cancellationToken.throwIfCancellationRequested();
            if (!responseReceived) {
                throw protocol("parallel target Graph emitted data before response metadata", null);
            }
            if (statusCode != 200) {
                if (remoteErrorLine != null) {
                    throw protocol("parallel target Graph returned multiple error envelopes", null);
                }
                remoteErrorLine = Objects.requireNonNull(line, "line");
                return;
            }
            TechnicalEvent event = technicalCodec.decodeEvent(line);
            requireCommonAuthority(event);
            if (event instanceof Started started) {
                acceptStarted(started);
            } else if (event instanceof ProjectionItem projection) {
                acceptProjection(projection);
            } else if (event instanceof Interrupted interrupted) {
                acceptInterrupted(interrupted);
            } else if (event instanceof GenerationReset reset) {
                acceptReset(reset);
            } else if (event instanceof Sealed sealed) {
                acceptSealed(sealed);
            } else {
                throw protocol("parallel target Graph emitted an unsupported event", null);
            }
        }

        private FrameExecutionReceipt finish() {
            if (!responseReceived) {
                throw protocol("parallel target Graph response metadata is missing", null);
            }
            if (statusCode != 200) {
                throw remoteFailure();
            }
            if (authority == null
                    || frameSetReceipt == null
                    || executionPlan == null) {
                throw protocol("parallel target Graph ended without execution authority", null);
            }
            var durable = staging
                    .findExactThreeCompletion(
                            authority.frameSetId(),
                            request.agentRunId(),
                            request.attemptId())
                    .orElseThrow(() -> protocol(
                            "parallel exact-three completion is absent after stream EOF", null));
            boolean durableExact = durable.frames().size() == FrameType.values().length
                    && frames.entrySet().stream().allMatch(entry -> {
                        var slot = durable.frames().get(entry.getKey());
                        FrameState state = entry.getValue();
                        return slot != null
                                && slot.generation() == state.generation
                                && slot.frameId().equals(state.frameId);
                    });
            if (!durableExact) {
                throw protocol("parallel exact-three assembly drifted after stream EOF", null);
            }
            return new FrameExecutionReceipt(
                    authority.frameSetId(),
                    durable.lastSequenceNo(),
                    durable.publicOutputEmitted());
        }

        private void acceptStarted(Started event) {
            FrameState state = state(event.common().frameType());
            boolean exact = state.state == LaneState.ADMITTED
                    && event.generation() == state.generation
                    && event.frameId().equals(state.frameId)
                    && event.frameModelInputSha256().equals(state.authority.frameModelInputSha256())
                    && event.framePromptSha256().equals(state.authority.framePromptSha256())
                    && event.contextEnvelopeSha256().equals(state.authority.contextEnvelopeSha256())
                    && event.modelContextViewSha256()
                            .equals(state.authority.modelContextViewSha256());
            if (!exact) {
                throw protocol("parallel Frame start differs from admitted authority", null);
            }
            AgentStreamEventV4.Payload payload = AgentStreamEventV4.Payload.frameStartPayload(
                    state.frameId,
                    wireFrameType(state.authority.frameType()),
                    state.generation,
                    frameSetReceipt.receiptId(),
                    PROJECTION_REGISTRY_VERSION);
            append(
                    state,
                    IngressKind.PUBLIC_FRAME_START,
                    null,
                    payload,
                    "start:" + state.authority.frameType() + ":" + state.generation + ":"
                            + state.frameId,
                    event.common().occurredAt());
            state.state = LaneState.STARTED;
        }

        private void acceptProjection(ProjectionItem event) {
            FrameState state = state(event.common().frameType());
            if (state.state != LaneState.STARTED
                    || event.generation() != state.generation
                    || !event.frameId().equals(state.frameId)
                    || event.localIndex() != state.nextLocalIndex
                    || event.nextLocalIndex() != state.nextLocalIndex + 1) {
                throw protocol("parallel Frame projection crossed its local authority", null);
            }
            AgentStreamEventV4.ValueKind valueKind =
                    AgentStreamEventV4.ValueKind.valueOf(event.valueKind());
            AgentStreamEventV4.Payload payload = AgentStreamEventV4.Payload.projectionItemPayload(
                    state.frameId,
                    wireFrameType(state.authority.frameType()),
                    state.generation,
                    event.localIndex(),
                    event.nextLocalIndex(),
                    event.canonicalItemId(),
                    event.projectionKind(),
                    event.projectionPathId(),
                    valueKind,
                    event.canonicalValueJson(),
                    event.publicText(),
                    event.itemSha256());
            append(
                    state,
                    IngressKind.PUBLIC_FRAME_PROJECTION_ITEM,
                    (long) event.localIndex(),
                    payload,
                    "projection:" + state.authority.frameType() + ":" + state.generation + ":"
                            + event.localIndex() + ":" + event.itemSha256(),
                    event.common().occurredAt());
            state.nextLocalIndex = event.nextLocalIndex();
        }

        private void acceptInterrupted(Interrupted event) {
            FrameState state = state(event.common().frameType());
            if (state.state != LaneState.STARTED
                    || event.generation() != state.generation
                    || !event.frameId().equals(state.frameId)) {
                throw protocol("parallel Frame interruption crossed its active generation", null);
            }
            AgentStreamEventV4.Payload payload = AgentStreamEventV4.Payload.interruptedPayload(
                    state.frameId,
                    wireFrameType(state.authority.frameType()),
                    state.generation,
                    state.nextLocalIndex,
                    event.errorCode(),
                    event.retryable());
            append(
                    state,
                    IngressKind.PUBLIC_FRAME_INTERRUPTED,
                    null,
                    payload,
                    "interrupted:" + state.authority.frameType() + ":" + state.generation + ":"
                            + event.errorCode(),
                    event.common().occurredAt());
            state.state = LaneState.INTERRUPTED;
            state.interruptionCode = event.errorCode();
            state.retryable = event.retryable();
        }

        private void acceptReset(GenerationReset event) {
            FrameState state = state(event.common().frameType());
            if (state.state != LaneState.INTERRUPTED
                    || !state.retryable
                    || state.generation != 1
                    || !event.reasonCode().equals(state.interruptionCode)
                    || event.oldGeneration() != state.generation
                    || event.newGeneration() != state.generation + 1
                    || !event.oldFrameId().equals(state.frameId)
                    || !event.newFrameId().equals(replacementFrameId(state, event.newGeneration()))) {
                throw protocol("parallel Frame generation reset is not the admitted successor", null);
            }
            FrameManifest replacement = new FrameManifest(
                    state.authority.frameType(),
                    event.newGeneration(),
                    event.newFrameId(),
                    state.authority.frameType().promptProfileId(),
                    state.authority.frameType().outputSchemaId(),
                    request.command().invocationContext().modelProfileId(),
                    state.authority.frameModelInputSha256(),
                    state.authority.framePromptSha256());
            SlotState failedState = "CALL_STATE_AMBIGUOUS".equals(event.reasonCode())
                    ? SlotState.AMBIGUOUS
                    : SlotState.FAILED;
            staging.admitRetry(new FrameRetryAdmission(
                    authority.frameSetId(),
                    replacement,
                    event.oldGeneration(),
                    failedState,
                    event.reasonCode(),
                    IntakeParallelFrameStagingPort.RETRY_VALIDATION_PATH));
            AgentStreamEventV4.Payload payload = AgentStreamEventV4.Payload.generationResetPayload(
                    event.oldFrameId(),
                    event.newFrameId(),
                    wireFrameType(state.authority.frameType()),
                    event.oldGeneration(),
                    event.newGeneration(),
                    event.reasonCode());
            state.generation = event.newGeneration();
            state.frameId = event.newFrameId();
            state.nextLocalIndex = 0;
            state.state = LaneState.ADMITTED;
            append(
                    state,
                    IngressKind.FRAME_GENERATION_RESET,
                    null,
                    payload,
                    "reset:" + state.authority.frameType() + ":" + event.oldGeneration() + ":"
                            + event.newGeneration() + ":" + event.newFrameId(),
                    event.common().occurredAt());
        }

        private void acceptSealed(Sealed event) {
            FrameState state = state(event.common().frameType());
            if (state.state != LaneState.STARTED
                    || event.generation() != state.generation
                    || !event.frameId().equals(state.frameId)
                    || event.nextLocalIndex() != state.nextLocalIndex
                    || !event.contextEnvelopeSha256().equals(state.authority.contextEnvelopeSha256())
                    || !event.modelContextViewSha256()
                            .equals(state.authority.modelContextViewSha256())) {
                throw protocol("parallel Frame seal crossed its current authority", null);
            }
            AgentStreamEventV4.Payload usagePayload = AgentStreamEventV4.Payload.usagePayload(
                    wireFrameType(state.authority.frameType()),
                    state.generation,
                    new Usage(
                            event.usage().inputTokens(),
                            event.usage().outputTokens(),
                            event.usage().totalTokens()));
            append(
                    state,
                    IngressKind.USAGE,
                    null,
                    usagePayload,
                    "usage:" + state.authority.frameType() + ":" + state.generation + ":"
                            + event.resultSha256(),
                    event.completedAt());
            var seal = staging.seal(new FrameSealCommand(
                    authority.frameSetId(),
                    request.agentRunId(),
                    request.attemptId(),
                    streamSessionId,
                    transportSequence++,
                    "seal:" + state.authority.frameType() + ":" + state.generation + ":"
                            + event.resultSha256(),
                    audience(),
                    state.authority.frameType(),
                    state.generation,
                    state.frameId,
                    event.childCheckpointRef(),
                    event.childCheckpointSha256(),
                    event.contextEnvelopeSha256(),
                    event.modelContextViewSha256(),
                    event.canonicalResultJson(),
                    event.resultSha256(),
                    event.publicProjectionSha256(),
                    event.nextLocalIndex(),
                    new IntakeParallelFrameStagingPort.ProviderUsage(
                            event.usage().inputTokens(),
                            event.usage().outputTokens(),
                            event.usage().totalTokens(),
                            event.usage().latencyMs(),
                            event.usage().providerCallCount()),
                    event.completedAt()));
            state.state = LaneState.SEALED;
            publishProgress(seal.globalSequence(), true);
        }

        private void append(
                FrameState state,
                IngressKind kind,
                Long localIndex,
                AgentStreamEventV4.Payload payload,
                String ingressIdentity,
                java.time.Instant occurredAt) {
            JsonNode payloadNode = mapper.valueToTree(payload);
            var receipt = staging.append(new IngressCommand(
                    authority.frameSetId(),
                    request.agentRunId(),
                    request.attemptId(),
                    streamSessionId,
                    transportSequence++,
                    ingressIdentity,
                    state.authority.frameType(),
                    state.generation,
                    kind,
                    localIndex,
                    audience(),
                    payload,
                    ContractJson.sha256Hex(payloadNode),
                    occurredAt));
            publishProgress(receipt.globalSequence(), kind != IngressKind.USAGE);
        }

        private void publishProgress(long sequence, boolean visible) {
            lastSequence = Math.max(lastSequence, sequence);
            publicOutputEmitted |= visible;
            progressListener.onProgress(
                    new AgentRunProgress(lastSequence, publicOutputEmitted, false));
        }

        private void requireCommonAuthority(TechnicalEvent event) {
            var common = event.common();
            if (authority == null
                    || !authority.frameSetId().equals(common.frameSetId())
                    || !request.agentRunId().equals(common.runId())
                    || !request.attemptId().equals(common.attemptId())
                    || !frames.containsKey(common.frameType())) {
                throw protocol("parallel Frame event crossed stream authority", null);
            }
        }

        private FrameState state(FrameType frameType) {
            FrameState state = frames.get(frameType);
            if (state == null) {
                throw protocol("parallel Frame type is not admitted", null);
            }
            return state;
        }

        private Audience audience() {
            return request.command().actorScope().audience();
        }

        private void requireSuccessMetadata(GraphCommandHttpTransport.ResponseHead response) {
            if (!sharedResponseMetadataIsValid(response, false)
                    || !endpoint.equals(response.uri())
                    || !"application/x-ndjson".equalsIgnoreCase(
                            mediaType(singleHeader(response.headers(), "Content-Type")))
                    || !request.agentRunId().equals(
                            singleHeader(response.headers(), "X-Agent-Run-Id"))
                    || !"agent-stream.v4".equals(
                            singleHeader(response.headers(), "X-Agent-Stream-Protocol"))
                    || !envelope.executionLane().equals(
                            singleHeader(response.headers(), "X-Graph-Execution-Lane"))
                    || !activationId.equals(
                            singleHeader(response.headers(), "X-Graph-Activation-Id"))) {
                throw protocol("parallel target Graph response metadata drifted", null);
            }
        }

        private void requireErrorMetadata(GraphCommandHttpTransport.ResponseHead response) {
            requireRemoteErrorMetadata(response, "parallel target Graph error metadata is invalid");
        }

        private TargetE2EGraphClientException remoteFailure() {
            return parseRemoteFailure(
                    remoteErrorLine, "Python rejected parallel Intake execution");
        }
    }

    private void requireRemoteErrorMetadata(
            GraphCommandHttpTransport.ResponseHead response, String failureMessage) {
        if (!sharedResponseMetadataIsValid(response, true)
                || !endpoint.equals(response.uri())
                || !"application/json".equalsIgnoreCase(
                        mediaType(singleHeader(response.headers(), "Content-Type")))) {
            throw protocol(failureMessage, null);
        }
    }

    private TargetE2EGraphClientException parseRemoteFailure(
            String responseLine, String rejectionMessage) {
        if (responseLine == null) {
            return protocol("parallel target Graph error body is missing", null);
        }
        try {
            JsonNode root = mapper.readTree(responseLine);
            Set<String> fields = new HashSet<>();
            root.fieldNames().forEachRemaining(fields::add);
            if (!root.isObject()
                    || !fields.equals(REMOTE_ERROR_FIELDS)
                    || !root.required("code").isTextual()
                    || !root.required("retryable").isBoolean()) {
                throw new IllegalArgumentException("remote error envelope is invalid");
            }
            String code = root.required("code").asText();
            if (!ERROR_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("remote error code is invalid");
            }
            return TargetE2EGraphClientException.remote(
                    code, root.required("retryable").asBoolean(), rejectionMessage);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            return protocol("parallel target Graph error body is invalid", failure);
        }
    }

    private ObjectNode authorityNode(StreamAuthority authority) {
        ObjectNode node = mapper.createObjectNode();
        node.put("schema_version", "intake.parallel-frame-session-identity.v1");
        node.put("frame_set_id", authority.frameSetId());
        node.put("run_id", authority.runId());
        node.put("attempt_id", authority.attemptId());
        node.put("authority_sha256", authority.authoritySha256());
        return node;
    }

    private StreamAuthority executionAuthority(
            StreamAuthority preparedAuthority, ExecutionPlan executionPlan) {
        List<FrameAuthority> frames = preparedAuthority.frames().stream()
                .map(prepared -> {
                    ExecutionLane lane = executionPlan.lanes().get(prepared.frameType());
                    if (lane == null) {
                        throw new IllegalArgumentException(
                                "parallel execution plan is missing a prepared Frame");
                    }
                    return new FrameAuthority(
                            prepared.frameType(),
                            Math.toIntExact(lane.generation()),
                            lane.frameId(),
                            prepared.frameModelInputSha256(),
                            prepared.framePromptSha256(),
                            prepared.contextEnvelopeSha256(),
                            prepared.modelContextViewSha256());
                })
                .toList();
        ObjectNode document = mapper.createObjectNode();
        document.put(
                "schema_version",
                TargetE2EIntakeParallelTransportCodec.AUTHORITY_SCHEMA);
        document.put("frame_set_id", preparedAuthority.frameSetId());
        document.put("run_id", preparedAuthority.runId());
        document.put("attempt_id", preparedAuthority.attemptId());
        var encodedFrames = document.putArray("frames");
        for (FrameAuthority frame : frames) {
            ObjectNode encoded = encodedFrames.addObject();
            encoded.put("frame_type", frame.frameType().name());
            encoded.put("generation", frame.generation());
            encoded.put("frame_id", frame.frameId());
            encoded.put("frame_model_input_sha256", frame.frameModelInputSha256());
            encoded.put("frame_prompt_sha256", frame.framePromptSha256());
            encoded.put("context_envelope_sha256", frame.contextEnvelopeSha256());
            encoded.put("model_context_view_sha256", frame.modelContextViewSha256());
        }
        return new StreamAuthority(
                preparedAuthority.frameSetId(),
                preparedAuthority.runId(),
                preparedAuthority.attemptId(),
                frames,
                ContractJson.sha256Hex(document));
    }

    private static void requirePreparedStreamAuthority(
            ExecuteAgentRunRequest request, StreamAuthority authority) {
        RoomGraphCommand command = request.command();
        if (!request.agentRunId().equals(authority.runId())
                || !request.attemptId().equals(authority.attemptId())
                || !deterministicFrameSetId(command, authority.frames().getFirst())
                        .equals(authority.frameSetId())
                || authority.frames().stream().anyMatch(frame ->
                        frame.generation() != 1
                                || !deterministicInitialFrameId(authority.frameSetId(), frame)
                                        .equals(frame.frameId()))) {
            throw new IllegalArgumentException(
                    "parallel Frame response authority differs from the sealed command");
        }
    }

    private static String deterministicFrameSetId(
            RoomGraphCommand command, FrameAuthority first) {
        ObjectNode identity = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode();
        identity.put("contract_version", "intake.parallel-frame-set-identity.v1");
        identity.put("thread_id", command.threadId());
        identity.put("command_id", command.commandId());
        identity.put("logical_run_id", command.logicalRunId());
        identity.put("attempt_id", command.attemptId());
        identity.put("request_hash", command.requestHash());
        identity.put("context_envelope_sha256", first.contextEnvelopeSha256());
        return "IFS_" + ContractJson.sha256Hex(identity).substring(0, 32);
    }

    private static String deterministicInitialFrameId(
            String frameSetId, FrameAuthority frame) {
        ObjectNode identity = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode();
        identity.put("contract_version", "intake.parallel-frame-identity.v1");
        identity.put("frame_set_id", frameSetId);
        identity.put("frame_type", frame.frameType().name());
        identity.put("generation", frame.generation());
        identity.put("frame_model_input_sha256", frame.frameModelInputSha256());
        return "IFR_" + ContractJson.sha256Hex(identity).substring(0, 32);
    }

    private static String replacementFrameId(FrameState state, int generation) {
        ObjectNode identity = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode();
        identity.put("frame_set_id", state.frameSetId);
        identity.put("frame_type", state.authority.frameType().name());
        identity.put("old_frame_id", state.frameId);
        identity.put("generation", generation);
        identity.put("frame_model_input_sha256", state.authority.frameModelInputSha256());
        return "intake.frame." + ContractJson.sha256Hex(identity).substring(0, 32);
    }

    private static AgentStreamEventV4.FrameType wireFrameType(FrameType frameType) {
        return AgentStreamEventV4.FrameType.valueOf(frameType.name());
    }

    private static String singleHeader(Map<String, List<String>> headers, String name) {
        List<String> matches = headerValues(headers, name);
        if (matches.size() != 1 || matches.getFirst() == null || matches.getFirst().isBlank()) {
            throw new IllegalArgumentException("response header is absent or ambiguous: " + name);
        }
        return matches.getFirst().trim();
    }

    private static List<String> headerValues(
            Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }

    private static boolean sharedResponseMetadataIsValid(
            GraphCommandHttpTransport.ResponseHead response, boolean requireNoTransform) {
        List<String> encodings = headerValues(response.headers(), "Content-Encoding");
        Set<String> cacheDirectives = headerValues(response.headers(), "Cache-Control").stream()
                .flatMap(value -> List.of(value.split(",", -1)).stream())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return encodings.size() <= 1
                && (encodings.isEmpty()
                        || "identity".equalsIgnoreCase(encodings.getFirst().trim()))
                && cacheDirectives.contains("no-store")
                && (!requireNoTransform || cacheDirectives.contains("no-transform"));
    }

    private static String mediaType(String contentType) {
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static TargetE2EGraphClientException protocol(String message, Throwable cause) {
        return TargetE2EGraphClientException.protocol(message, cause);
    }

    private static void requireParallel(ExecuteAgentRunRequest request) {
        if (request == null
                || !ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                || !"agent-stream.v4".equals(request.streamProtocol())) {
            throw new IllegalArgumentException(
                    "parallel Frame client requires the explicit Intake V4 profile");
        }
    }

    private enum LaneState {
        ADMITTED,
        STARTED,
        INTERRUPTED,
        SEALED
    }

    private static final class FrameState {
        private final FrameAuthority authority;
        private final String frameSetId;
        private int generation;
        private String frameId;
        private int nextLocalIndex;
        private LaneState state;
        private String interruptionCode;
        private boolean retryable;

        private FrameState(
                String frameSetId,
                FrameAuthority authority,
                ExecutionLane executionLane) {
            this.authority = authority;
            this.frameSetId = frameSetId;
            this.generation = authority.generation();
            this.frameId = authority.frameId();
            this.nextLocalIndex = Math.toIntExact(executionLane.nextLocalIndex());
            this.state = executionLane.action() == ExecutionAction.SKIP_SEALED
                    ? LaneState.SEALED
                    : LaneState.ADMITTED;
        }
    }
}
