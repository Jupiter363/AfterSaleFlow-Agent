package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.FailureTerminationReceipt;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ProgressListener;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAdmissionAuthorityResolver;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient.LocalReconciliationException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AbandonmentApplication;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AdmissionReceiptLookup;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AdmissionReceiptPublication;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameManifest;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameRetryAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSealCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSetAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.PublishedAdmissionReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionAction;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionLane;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionPlan;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressKind;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.SlotState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.StagingConflictException;
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
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.StreamFailure;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelTransportCodec.TechnicalEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
    static final String FAILURE_CODE_HEADER = "X-Intake-Parallel-Failure-Code";
    static final String TERMINAL_RECEIPT_HEADER = "X-Intake-Parallel-Terminal-Receipt";
    static final String ABANDONMENT_RECEIPT_HEADER =
            "X-Intake-Parallel-Abandonment-Receipt";
    private static final String FRAME_SET_HEADER = "X-Intake-Frame-Set-Id";
    private static final String STARTED_AMBIGUOUS =
            "INTAKE_PARALLEL_EXECUTION_STARTED_AMBIGUOUS";
    private static final String PROJECTION_REGISTRY_VERSION = "intake-projection-registry.v1";
    private static final Set<String> REMOTE_ERROR_FIELDS = Set.of("code", "retryable");
    private static final Set<String> FAILURE_TERMINATION_FIELDS = Set.of(
            "schema_version",
            "receipt_id",
            "request_hash",
            "frame_set_id",
            "run_id",
            "attempt_id",
            "command_id",
            "admission_receipt_sha256",
            "requested_failure_code",
            "graph_command_status",
            "graph_attempt_status",
            "graph_error_code",
            "graph_error_classification",
            "provider_permit_statuses",
            "receipt_sha256");
    private static final Set<String> ABANDONMENT_FIELDS = Set.of(
            "schema_version",
            "abandonment_id",
            "execution_id",
            "thread_id",
            "command_id",
            "request_hash",
            "attempt_id",
            "frame_set_id",
            "receipt_sha256",
            "authority_sha256",
            "admission_receipt",
            "provider_call_count_before",
            "provider_call_count_after",
            "owner_id",
            "fencing_token",
            "abandoned_at",
            "abandonment_sha256");
    private static final Set<String> TERMINAL_PERMIT_STATUSES = Set.of(
            "RELEASED", "CANCELLED", "EXPIRED", "TIMED_OUT", "ORPHANED");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern SAFE_FAILURE_CODE =
            Pattern.compile("^[A-Z][A-Z0-9_]{2,127}$");

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
        PreparedAdmission prepared;
        try {
            prepared = prepare(
                    request,
                    roomFencingToken,
                    registryBinding,
                    cancellationToken);
        } catch (LocalReconciliationException failure) {
            if (!STARTED_AMBIGUOUS.equals(failure.code())) {
                throw failure;
            }
            abandonStartedExecution(
                    request,
                    roomFencingToken,
                    registryBinding,
                    cancellationToken);
            prepared = prepare(
                    request,
                    roomFencingToken,
                    registryBinding,
                    cancellationToken);
        }
        try {
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
                            prepared.publishedReceipt().receiptSha256()));
            StreamSession session = new StreamSession(
                    request,
                    sealed.envelope(),
                    progressListener,
                    cancellationToken,
                    prepared.executionAuthority(),
                    prepared.frameSetReceipt(),
                    prepared.executionPlan(),
                    prepared.publishedReceipt().receiptSha256());
            GraphCommandHttpTransport.Request transportRequest =
                    new GraphCommandHttpTransport.Request(
                            endpoint,
                            requestHeaders(
                                    sealed,
                                    "EXECUTE",
                                    prepared.publishedReceipt().encodedReceipt()),
                            sealed.body(),
                            timeout,
                            GraphCommandHttpTransport.MAXIMUM_PARALLEL_LINE_BYTES,
                            GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES);
            try {
                transport.stream(transportRequest, cancellationToken, session);
                cancellationToken.throwIfCancellationRequested();
                return session.finish();
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
        } catch (StagingConflictException failure) {
            throw new LocalReconciliationException(
                    failure.code(),
                    "parallel technical staging requires local reconciliation",
                    failure);
        } catch (TargetE2EGraphClientException failure) {
            throw failure;
        }
    }

    private void abandonStartedExecution(
            ExecuteAgentRunRequest request,
            long roomFencingToken,
            GraphRegistryBindingPolicy.ExpectedBinding registryBinding,
            AgentRunCancellationToken cancellationToken) {
        PublishedAdmissionReceipt published = staging
                .findCurrentAdmissionReceipt(new AdmissionReceiptLookup(
                        request.agentRunId(),
                        request.attemptId(),
                        request.command().commandId(),
                        request.command().requestHash()))
                .orElseThrow(() -> new LocalReconciliationException(
                        "INTAKE_PARALLEL_ABANDONMENT_ADMISSION_MISSING",
                        "ambiguous STARTED Frame lacks its published admission receipt",
                        null));
        TargetE2ESealedGraphCommand sealed = envelopeCodec.sealParallelCommand(
                activationId,
                roomFencingToken,
                request.command(),
                registryBinding,
                signer,
                TargetE2EGraphEnvelopeSigner.ParallelDeliveryBinding.abandon(
                        published.receiptSha256()));
        AbandonmentSession session = new AbandonmentSession(
                request,
                sealed.envelope(),
                published,
                cancellationToken);
        GraphCommandHttpTransport.Request transportRequest =
                new GraphCommandHttpTransport.Request(
                        endpoint,
                        requestHeaders(
                                sealed,
                                "ABANDON",
                                published.encodedReceipt()),
                        sealed.body(),
                        timeout,
                        GraphCommandHttpTransport.MAXIMUM_LINE_BYTES,
                        GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES);
        try {
            transport.stream(transportRequest, cancellationToken, session);
            cancellationToken.throwIfCancellationRequested();
            staging.applyAbandonment(session.finish());
        } catch (StagingConflictException failure) {
            throw new LocalReconciliationException(
                    failure.code(),
                    "parallel STARTED abandonment requires local reconciliation",
                    failure);
        } catch (GraphCommandTransportException failure) {
            cancellationToken.throwIfCancellationRequested();
            if (failure.protocolViolation()) {
                throw TargetE2EGraphClientException.protocol(
                        "parallel target Graph abandonment violated the protocol",
                        failure);
            }
            throw TargetE2EGraphClientException.transport(
                    "parallel target Graph abandonment transport failed",
                    failure);
        } catch (IllegalArgumentException failure) {
            throw TargetE2EGraphClientException.protocol(
                    "parallel target Graph abandonment is invalid",
                    failure);
        }
    }

    @Override
    public FailureTerminationReceipt terminateUncommittedFailure(
            ExecuteAgentRunRequest request,
            String failureCode,
            AgentRunCancellationToken cancellationToken) {
        requireParallel(request);
        if (failureCode == null || !SAFE_FAILURE_CODE.matcher(failureCode).matches()) {
            throw new IllegalArgumentException("parallel failureCode is invalid");
        }
        Objects.requireNonNull(cancellationToken, "cancellationToken")
                .throwIfCancellationRequested();
        long roomFencingToken = Objects.requireNonNull(
                        identityResolver.resolve(request),
                        "durable AgentRun identity resolver returned no identity")
                .requireExact(request);
        GraphRegistryBindingPolicy.ExpectedBinding registryBinding =
                GraphRegistryBindingPolicy.requireExpected(
                        registryBindingPolicy,
                        GraphStreamVisibilityPolicy.Binding.from(request.command()));
        PublishedAdmissionReceipt published = staging
                .findCurrentAdmissionReceipt(new AdmissionReceiptLookup(
                        request.agentRunId(),
                        request.attemptId(),
                        request.command().commandId(),
                        request.command().requestHash()))
                .orElseThrow(() -> TargetE2EGraphClientException.protocol(
                        "parallel failure termination lacks a published admission receipt",
                        null));
        TargetE2ESealedGraphCommand sealed = envelopeCodec.sealParallelCommand(
                activationId,
                roomFencingToken,
                request.command(),
                registryBinding,
                signer,
                TargetE2EGraphEnvelopeSigner.ParallelDeliveryBinding.terminate(
                        published.receiptSha256(), failureCode));
        FailureTerminationSession session = new FailureTerminationSession(
                request,
                sealed.envelope(),
                published.frameSetId(),
                published.receiptSha256(),
                failureCode,
                cancellationToken);
        GraphCommandHttpTransport.Request transportRequest =
                new GraphCommandHttpTransport.Request(
                        endpoint,
                        requestHeaders(
                                sealed,
                                "TERMINATE",
                                published.encodedReceipt(),
                                failureCode),
                        sealed.body(),
                        timeout,
                        GraphCommandHttpTransport.MAXIMUM_LINE_BYTES,
                        GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES);
        try {
            transport.stream(transportRequest, cancellationToken, session);
            cancellationToken.throwIfCancellationRequested();
            return session.finish();
        } catch (GraphCommandTransportException failure) {
            cancellationToken.throwIfCancellationRequested();
            if (failure.protocolViolation()) {
                throw TargetE2EGraphClientException.protocol(
                        "parallel target Graph failure termination violated the protocol",
                        failure);
            }
            throw TargetE2EGraphClientException.transport(
                    "parallel target Graph failure termination transport failed", failure);
        } catch (IllegalArgumentException failure) {
            throw TargetE2EGraphClientException.protocol(
                    "parallel target Graph failure termination is invalid", failure);
        }
    }

    private Map<String, String> requestHeaders(
            TargetE2ESealedGraphCommand sealed,
            String phase,
            String admissionReceipt) {
        return requestHeaders(sealed, phase, admissionReceipt, null);
    }

    private Map<String, String> requestHeaders(
            TargetE2ESealedGraphCommand sealed,
            String phase,
            String admissionReceipt,
            String failureCode) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + sealed.credential().compactJws());
        headers.put(
                "Accept",
                "EXECUTE".equals(phase) ? "application/x-ndjson" : "application/json");
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Content-Encoding", "identity");
        headers.put("Cache-Control", "no-store");
        headers.put("X-Agent-Run-Id", sealed.envelope().command().logicalRunId());
        headers.put("traceparent", sealed.envelope().command().traceparent());
        headers.put(PHASE_HEADER, phase);
        if (admissionReceipt != null) {
            headers.put(ADMISSION_HEADER, admissionReceipt);
        }
        if (failureCode != null) {
            headers.put(FAILURE_CODE_HEADER, failureCode);
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
            IntakeParallelFrameStagingPort.FrameSetReceipt frameSetReceipt =
                    staging.admit(admission);
            ExecutionPlan executionPlan = staging.planExecution(admission);
            StreamAuthority executionAuthority = executionAuthority(authority, executionPlan);
            EncodedAdmissionReceipt encodedReceipt = technicalCodec.encodeAdmissionReceipt(
                    request.command().requestHash(),
                    frameSetReceipt.receiptId(),
                    authority,
                    executionPlan);
            PublishedAdmissionReceipt publishedReceipt = staging.publishAdmissionReceipt(
                    new AdmissionReceiptPublication(
                            admission,
                            frameSetReceipt,
                            executionPlan,
                            encodedReceipt.headerValue(),
                            encodedReceipt.receiptSha256()));
            return new PreparedAdmission(
                    authority,
                    executionAuthority,
                    frameSetReceipt,
                    executionPlan,
                    publishedReceipt);
        } catch (StagingConflictException failure) {
            throw new LocalReconciliationException(
                    failure.code(),
                    "parallel technical preparation requires local reconciliation",
                    failure);
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

    private final class AbandonmentSession implements GraphCommandHttpTransport.Listener {

        private final ExecuteAgentRunRequest request;
        private final TargetE2EGraphCommandEnvelope envelope;
        private final PublishedAdmissionReceipt published;
        private final AgentRunCancellationToken cancellationToken;
        private boolean responseReceived;
        private int statusCode;
        private String responseLine;
        private String receiptHeader;

        private AbandonmentSession(
                ExecuteAgentRunRequest request,
                TargetE2EGraphCommandEnvelope envelope,
                PublishedAdmissionReceipt published,
                AgentRunCancellationToken cancellationToken) {
            this.request = request;
            this.envelope = envelope;
            this.published = published;
            this.cancellationToken = cancellationToken;
        }

        @Override
        public void onResponse(GraphCommandHttpTransport.ResponseHead response) {
            cancellationToken.throwIfCancellationRequested();
            if (responseReceived) {
                throw protocol("parallel abandonment returned duplicate metadata", null);
            }
            responseReceived = true;
            statusCode = response.statusCode();
            if (statusCode >= 300 && statusCode <= 399) {
                throw protocol("parallel abandonment redirect is forbidden", null);
            }
            if (statusCode != 200) {
                requireRemoteErrorMetadata(
                        response,
                        "parallel abandonment error metadata is invalid");
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
                            singleHeader(response.headers(), "X-Graph-Activation-Id"))
                    || !published.frameSetId().equals(
                            singleHeader(response.headers(), FRAME_SET_HEADER))) {
                throw protocol("parallel abandonment metadata drifted", null);
            }
            receiptHeader = singleHeader(
                    response.headers(), ABANDONMENT_RECEIPT_HEADER);
            if (!receiptHeader.matches("[0-9a-f]{64}")) {
                throw protocol("parallel abandonment receipt header is invalid", null);
            }
        }

        @Override
        public void onLine(String line) {
            cancellationToken.throwIfCancellationRequested();
            if (!responseReceived) {
                throw protocol("parallel abandonment emitted data before metadata", null);
            }
            if (responseLine != null) {
                throw protocol("parallel abandonment returned multiple bodies", null);
            }
            responseLine = Objects.requireNonNull(line, "line");
        }

        private AbandonmentApplication finish() {
            if (!responseReceived) {
                throw protocol("parallel abandonment response is absent", null);
            }
            if (statusCode != 200) {
                throw parseRemoteFailure(
                        responseLine,
                        "Python rejected parallel Intake abandonment");
            }
            if (responseLine == null || receiptHeader == null) {
                throw protocol("parallel abandonment receipt is absent", null);
            }
            try {
                JsonNode decoded = mapper.readTree(responseLine);
                if (!(decoded instanceof ObjectNode root)) {
                    throw new IllegalArgumentException(
                            "parallel abandonment receipt must be an object");
                }
                Set<String> fields = new HashSet<>();
                root.fieldNames().forEachRemaining(fields::add);
                if (!fields.equals(ABANDONMENT_FIELDS)) {
                    throw new IllegalArgumentException(
                            "parallel abandonment receipt fields drifted");
                }
                byte[] admissionBytes = Base64.getUrlDecoder()
                        .decode(published.encodedReceipt());
                JsonNode admissionDecoded = mapper.readTree(
                        new String(admissionBytes, StandardCharsets.UTF_8));
                if (!(admissionDecoded instanceof ObjectNode admission)
                        || !Arrays.equals(
                                admissionBytes,
                                ContractJson.canonicalize(admission))
                        || !admission.equals(root.required("admission_receipt"))) {
                    throw new IllegalArgumentException(
                            "parallel abandonment admission receipt drifted");
                }
                String schemaVersion = receiptText(root, "schema_version");
                String abandonmentId = receiptText(root, "abandonment_id");
                String executionId = receiptText(root, "execution_id");
                String threadId = receiptText(root, "thread_id");
                String commandId = receiptText(root, "command_id");
                String requestHash = receiptText(root, "request_hash");
                String attemptId = receiptText(root, "attempt_id");
                String frameSetId = receiptText(root, "frame_set_id");
                String receiptSha256 = receiptText(root, "receipt_sha256");
                String authoritySha256 = receiptText(root, "authority_sha256");
                long providerCallCountBefore = receiptLong(
                        root, "provider_call_count_before");
                long providerCallCountAfter = receiptLong(
                        root, "provider_call_count_after");
                String ownerId = receiptText(root, "owner_id");
                long fencingToken = receiptLong(root, "fencing_token");
                Instant abandonedAt = Instant.parse(
                        receiptText(root, "abandoned_at"));
                String abandonmentSha256 = receiptText(
                        root, "abandonment_sha256");
                if (!"intake.parallel-receipt-abandonment.v1".equals(schemaVersion)
                        || !request.command().threadId().equals(threadId)
                        || !request.command().commandId().equals(commandId)
                        || !request.command().requestHash().equals(requestHash)
                        || !request.attemptId().equals(attemptId)
                        || !published.frameSetId().equals(frameSetId)
                        || !published.receiptSha256().equals(receiptSha256)
                        || !receiptSha256.equals(
                                receiptText(admission, "receipt_sha256"))
                        || !authoritySha256.equals(
                                receiptText(admission, "authority_sha256"))
                        || !request.agentRunId().equals(
                                receiptText(admission, "run_id"))
                        || providerCallCountBefore < 0
                        || providerCallCountAfter <= providerCallCountBefore
                        || providerCallCountAfter > Integer.MAX_VALUE
                        || fencingToken < 1
                        || !receiptHeader.equals(abandonmentSha256)
                        || !abandonmentId.equals(
                                "parallel-receipt-abandonment."
                                        + receiptSha256.substring(0, 24)
                                        + "." + fencingToken)
                        || !executionId.equals(
                                "parallel-receipt-execution."
                                        + receiptSha256.substring(0, 24)
                                        + "." + fencingToken)) {
                    throw new IllegalArgumentException(
                            "parallel abandonment receipt authority drifted");
                }
                ObjectNode unsigned = root.deepCopy();
                unsigned.remove("abandonment_sha256");
                if (!abandonmentSha256.equals(ContractJson.sha256Hex(unsigned))) {
                    throw new IllegalArgumentException(
                            "parallel abandonment receipt self-hash drifted");
                }
                byte[] canonicalBytes = ContractJson.canonicalize(root);
                return new AbandonmentApplication(
                        frameSetId,
                        request.agentRunId(),
                        attemptId,
                        commandId,
                        requestHash,
                        threadId,
                        receiptSha256,
                        authoritySha256,
                        abandonmentId,
                        executionId,
                        providerCallCountBefore,
                        providerCallCountAfter,
                        ownerId,
                        fencingToken,
                        abandonedAt,
                        canonicalBytes,
                        abandonmentSha256);
            } catch (RuntimeException
                    | com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw protocol("parallel abandonment receipt is invalid", failure);
            }
        }
    }

    private final class FailureTerminationSession implements GraphCommandHttpTransport.Listener {

        private final ExecuteAgentRunRequest request;
        private final TargetE2EGraphCommandEnvelope envelope;
        private final String frameSetId;
        private final String admissionReceiptSha256;
        private final String requestedFailureCode;
        private final AgentRunCancellationToken cancellationToken;
        private boolean responseReceived;
        private int statusCode;
        private String responseLine;
        private String receiptHeader;

        private FailureTerminationSession(
                ExecuteAgentRunRequest request,
                TargetE2EGraphCommandEnvelope envelope,
                String frameSetId,
                String admissionReceiptSha256,
                String requestedFailureCode,
                AgentRunCancellationToken cancellationToken) {
            this.request = request;
            this.envelope = envelope;
            this.frameSetId = frameSetId;
            this.admissionReceiptSha256 = admissionReceiptSha256;
            this.requestedFailureCode = requestedFailureCode;
            this.cancellationToken = cancellationToken;
        }

        @Override
        public void onResponse(GraphCommandHttpTransport.ResponseHead response) {
            cancellationToken.throwIfCancellationRequested();
            if (responseReceived) {
                throw protocol(
                        "parallel failure termination returned duplicate response metadata",
                        null);
            }
            responseReceived = true;
            statusCode = response.statusCode();
            if (statusCode >= 300 && statusCode <= 399) {
                throw protocol("parallel failure termination redirect is forbidden", null);
            }
            if (statusCode != 200) {
                requireRemoteErrorMetadata(
                        response,
                        "parallel failure termination error metadata is invalid");
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
                            singleHeader(response.headers(), "X-Graph-Activation-Id"))
                    || !frameSetId.equals(
                            singleHeader(response.headers(), FRAME_SET_HEADER))) {
                throw protocol("parallel failure termination metadata drifted", null);
            }
            receiptHeader = singleHeader(response.headers(), TERMINAL_RECEIPT_HEADER);
            if (!receiptHeader.matches("[0-9a-f]{64}")) {
                throw protocol("parallel failure termination receipt header is invalid", null);
            }
        }

        @Override
        public void onLine(String line) {
            cancellationToken.throwIfCancellationRequested();
            if (!responseReceived) {
                throw protocol(
                        "parallel failure termination emitted data before metadata",
                        null);
            }
            if (responseLine != null) {
                throw protocol("parallel failure termination returned multiple bodies", null);
            }
            responseLine = Objects.requireNonNull(line, "line");
        }

        private FailureTerminationReceipt finish() {
            if (!responseReceived) {
                throw protocol("parallel failure termination response is absent", null);
            }
            if (statusCode != 200) {
                throw parseRemoteFailure(
                        responseLine,
                        "Python rejected parallel Intake failure termination");
            }
            if (responseLine == null || receiptHeader == null) {
                throw protocol("parallel failure termination receipt is absent", null);
            }
            try {
                JsonNode decoded = mapper.readTree(responseLine);
                if (!(decoded instanceof ObjectNode root)) {
                    throw new IllegalArgumentException(
                            "parallel failure termination receipt must be an object");
                }
                Set<String> fields = new HashSet<>();
                root.fieldNames().forEachRemaining(fields::add);
                if (!fields.equals(FAILURE_TERMINATION_FIELDS)) {
                    throw new IllegalArgumentException(
                            "parallel failure termination receipt fields drifted");
                }
                String schemaVersion = receiptText(root, "schema_version");
                String receiptId = receiptText(root, "receipt_id");
                String requestHash = receiptText(root, "request_hash");
                String actualFrameSetId = receiptText(root, "frame_set_id");
                String runId = receiptText(root, "run_id");
                String attemptId = receiptText(root, "attempt_id");
                String commandId = receiptText(root, "command_id");
                String actualAdmissionHash =
                        receiptText(root, "admission_receipt_sha256");
                String actualFailureCode =
                        receiptText(root, "requested_failure_code");
                String graphCommandStatus =
                        receiptText(root, "graph_command_status");
                String graphAttemptStatus =
                        receiptText(root, "graph_attempt_status");
                String graphErrorCode = receiptText(root, "graph_error_code");
                String graphErrorClassification =
                        receiptText(root, "graph_error_classification");
                String receiptSha256 = receiptText(root, "receipt_sha256");
                if (!"intake.parallel-failure-termination.v1".equals(schemaVersion)
                        || !request.command().requestHash().equals(requestHash)
                        || !frameSetId.equals(actualFrameSetId)
                        || !request.agentRunId().equals(runId)
                        || !request.attemptId().equals(attemptId)
                        || !request.command().commandId().equals(commandId)
                        || !admissionReceiptSha256.equals(actualAdmissionHash)
                        || !requestedFailureCode.equals(actualFailureCode)
                        || !Set.of("ABORTED", "CANCELLED").contains(graphCommandStatus)
                        || !Set.of("FAILED", "LEASE_LOST", "CANCELLED", "ABSENT")
                                .contains(graphAttemptStatus)
                        || !SAFE_FAILURE_CODE.matcher(actualFailureCode).matches()
                        || !SAFE_FAILURE_CODE.matcher(graphErrorCode).matches()
                        || !SAFE_FAILURE_CODE.matcher(graphErrorClassification).matches()
                        || !receiptHeader.equals(receiptSha256)
                        || !receiptId.equals(
                                "parallel-failure-terminal."
                                        + admissionReceiptSha256.substring(0, 24))) {
                    throw new IllegalArgumentException(
                            "parallel failure termination receipt authority drifted");
                }
                JsonNode permitNode = root.required("provider_permit_statuses");
                if (!permitNode.isArray()) {
                    throw new IllegalArgumentException(
                            "parallel failure termination permits are invalid");
                }
                List<String> permitStatuses = new ArrayList<>();
                permitNode.forEach(value -> {
                    if (!value.isTextual()) {
                        throw new IllegalArgumentException(
                                "parallel failure termination permit is invalid");
                    }
                    permitStatuses.add(value.asText());
                });
                List<String> sortedStatuses = permitStatuses.stream().sorted().toList();
                if (!permitStatuses.equals(sortedStatuses)
                        || permitStatuses.stream()
                                .anyMatch(status -> !TERMINAL_PERMIT_STATUSES.contains(status))) {
                    throw new IllegalArgumentException(
                            "parallel failure termination permits are not terminal");
                }
                ObjectNode unsigned = root.deepCopy();
                unsigned.remove("receipt_sha256");
                if (!receiptSha256.matches("[0-9a-f]{64}")
                        || !receiptSha256.equals(ContractJson.sha256Hex(unsigned))) {
                    throw new IllegalArgumentException(
                            "parallel failure termination self-hash drifted");
                }
                byte[] canonicalBytes = ContractJson.canonicalize(root);
                return new FailureTerminationReceipt(
                        schemaVersion, receiptId, receiptSha256, canonicalBytes);
            } catch (RuntimeException
                    | com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw protocol("parallel failure termination receipt is invalid", failure);
            }
        }
    }

    private static String receiptText(ObjectNode receipt, String field) {
        JsonNode value = receipt.required(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "parallel receipt field is invalid: " + field);
        }
        return value.asText();
    }

    private static long receiptLong(ObjectNode receipt, String field) {
        JsonNode value = receipt.required(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "parallel receipt field is not an integer: " + field);
        }
        return value.longValue();
    }

    private record PreparedAdmission(
            StreamAuthority preparedAuthority,
            StreamAuthority executionAuthority,
            IntakeParallelFrameStagingPort.FrameSetReceipt frameSetReceipt,
            ExecutionPlan executionPlan,
            PublishedAdmissionReceipt publishedReceipt) {}

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
            StreamFailure failure = technicalCodec.decodeStreamFailure(line);
            if (failure != null) {
                if (!failure.frameSetId().equals(authority.frameSetId())
                        || !failure.runId().equals(request.agentRunId())
                        || !failure.attemptId().equals(request.attemptId())
                        || !failure.authoritySha256().equals(authority.authoritySha256())) {
                    throw protocol("parallel stream failure crossed execution authority", null);
                }
                throw TargetE2EGraphClientException.remote(
                        failure.errorCode(),
                        failure.retryable(),
                        "parallel Intake stream ended with an explicit bound failure");
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
            TargetE2EGraphClientException batchFailure = batchFailureAtEof();
            if (batchFailure != null) {
                throw batchFailure;
            }
            var durableProof = staging.findExactThreeCompletion(
                    authority.frameSetId(), request.agentRunId(), request.attemptId());
            if (frames.values().stream().anyMatch(state -> state.state != LaneState.SEALED)) {
                throw protocol(
                        "parallel target Graph ended with a non-terminal Frame lane", null);
            }
            var durable = durableProof.orElseThrow(() -> protocol(
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

        private TargetE2EGraphClientException batchFailureAtEof() {
            boolean allTerminal = frames.values().stream()
                    .allMatch(state -> state.state == LaneState.SEALED
                            || state.state == LaneState.INTERRUPTED);
            if (!allTerminal) {
                return null;
            }
            List<FrameState> interrupted = java.util.Arrays.stream(FrameType.values())
                    .map(frames::get)
                    .filter(Objects::nonNull)
                    .filter(state -> state.state == LaneState.INTERRUPTED)
                    .toList();
            if (interrupted.isEmpty()) {
                return null;
            }
            boolean retryable = interrupted.stream()
                    .allMatch(state -> state.retryable && state.generation < 2);
            FrameState decisiveFailure = interrupted.stream()
                    .filter(state -> !state.retryable || state.generation >= 2)
                    .findFirst()
                    .orElse(interrupted.getFirst());
            String code = retryable
                    ? "INTAKE_PARALLEL_FRAME_BATCH_FAILED"
                    : decisiveFailure.interruptionCode;
            return TargetE2EGraphClientException.remote(
                    code,
                    retryable,
                    retryable
                            ? "parallel Intake will retry only the interrupted Frame lanes"
                            : "parallel Intake Frame failure exhausted its lane authority");
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
                    IntakeParallelFrameStagingPort.RETRY_VALIDATION_PATH,
                    event.common().occurredAt()));
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
