package com.example.dispute.workflow.targete2e.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAdmissionAuthorityResolver;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient.FrameExecutionReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyView;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.EventAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExactThreeCompletion;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExactThreeFrame;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionAction;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionLane;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionPlan;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameRetryAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameRetryReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSealCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSealReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSetFailureCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSetFailureReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSetAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSetReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSlotView;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.SlotState;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HttpTargetE2EIntakeParallelFrameExecutionClientTest {

    private static final String ACTIVATION_ID = "p9act.v1.0123456789abcdef0123456789abcdef";
    private static final URI BASE_URI = URI.create("https://python-agent.internal/base/");
    private static final URI ENDPOINT = BASE_URI.resolve(
            HttpTargetE2EIntakeParallelFrameExecutionClient.PATH);
    private static final String COMPACT_JWS =
            "e30.e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-25T01:00:00Z");
    private static final String CONTEXT_HASH = "c".repeat(64);
    private static final String MODEL_CONTEXT_HASH = "d".repeat(64);

    @Test
    void incrementallyStagesInterleavedFramesAndReturnsOnlyAfterDurableExactThreeSeal() {
        ExecuteAgentRunRequest request = validParallelRequest();
        StreamFixture fixture = StreamFixture.complete(request);
        GraphTransportSecurityProof proof = mutualTlsProof();
        RecordingStaging staging = new RecordingStaging(request, fixture);
        FakeCommandTransport transport = new FakeCommandTransport(proof, fixture);
        List<AgentRunProgress> progress = new ArrayList<>();

        FrameExecutionReceipt receipt = client(request, proof, transport, staging)
                .executeOrResume(
                        request,
                        progress::add,
                        new AgentRunCancellationToken());

        assertThat(receipt.frameSetId()).isEqualTo(fixture.frameSetId());
        assertThat(receipt.lastSequenceNo()).isEqualTo(14L);
        assertThat(receipt.publicOutputEmitted()).isTrue();
        assertThat(progress).hasSize(15);
        assertThat(progress)
                .extracting(AgentRunProgress::lastSequenceNo)
                .containsExactly(
                        0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L,
                        14L);
        assertThat(staging.actions)
                .containsExactly(
                        "admit",
                        "plan",
                        "append:PUBLIC_FRAME_START:DIALOGUE_FRAME",
                        "append:PUBLIC_FRAME_START:DOSSIER_FRAME",
                        "append:PUBLIC_FRAME_START:QUALITY_FRAME",
                        "append:PUBLIC_FRAME_INTERRUPTED:DIALOGUE_FRAME",
                        "retry:DIALOGUE_FRAME:2",
                        "append:FRAME_GENERATION_RESET:DIALOGUE_FRAME",
                        "append:PUBLIC_FRAME_START:DIALOGUE_FRAME",
                        "append:PUBLIC_FRAME_PROJECTION_ITEM:DIALOGUE_FRAME",
                        "append:PUBLIC_FRAME_PROJECTION_ITEM:QUALITY_FRAME",
                        "append:PUBLIC_FRAME_PROJECTION_ITEM:DOSSIER_FRAME",
                        "append:USAGE:QUALITY_FRAME",
                        "seal:QUALITY_FRAME",
                        "append:USAGE:DIALOGUE_FRAME",
                        "seal:DIALOGUE_FRAME",
                        "append:USAGE:DOSSIER_FRAME",
                        "seal:DOSSIER_FRAME",
                        "find-completion");
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.get(0).headers())
                .containsEntry(HttpTargetE2EIntakeParallelFrameExecutionClient.PHASE_HEADER, "PREPARE")
                .doesNotContainKey(HttpTargetE2EIntakeParallelFrameExecutionClient.ADMISSION_HEADER);
        assertThat(transport.requests.get(1).maximumLineBytes())
                .isEqualTo(GraphCommandHttpTransport.MAXIMUM_PARALLEL_LINE_BYTES);
        assertThat(transport.requests.get(1).headers())
                .containsEntry("Accept", "application/x-ndjson")
                .containsEntry("X-Agent-Run-Id", request.agentRunId())
                .containsEntry(HttpTargetE2EIntakeParallelFrameExecutionClient.PHASE_HEADER, "EXECUTE")
                .containsKey(HttpTargetE2EIntakeParallelFrameExecutionClient.ADMISSION_HEADER);
        assertThat(staging.deliveryBindings)
                .extracting(TargetE2EGraphEnvelopeSigner.ParallelDeliveryBinding::phase)
                .containsExactly("PREPARE", "EXECUTE");
        assertThat(staging.deliveryBindings.get(1).admissionReceiptSha256())
                .isEqualTo(receiptHash(transport.requests.get(1)));
        assertThat(staging.admission.manifests())
                .extracting(IntakeParallelFrameStagingPort.FrameManifest::frameType)
                .containsExactly(FrameType.values());
    }

    @Test
    void rejectsCorruptedAuthorityBeforeAdmission() {
        ExecuteAgentRunRequest request = validParallelRequest();
        StreamFixture fixture = StreamFixture.complete(request).withAuthoritySuffix("A");
        GraphTransportSecurityProof proof = mutualTlsProof();
        RecordingStaging staging = new RecordingStaging(request, fixture);

        assertThatThrownBy(() -> client(
                                request,
                                proof,
                                new FakeCommandTransport(proof, fixture),
                                staging)
                        .executeOrResume(
                                request,
                                ignored -> {},
                                new AgentRunCancellationToken()))
                .isInstanceOf(TargetE2EGraphClientException.class);

        assertThat(staging.actions).isEmpty();
    }

    @Test
    void partialEofPreservesCommittedPrefixButNeverReturnsAFrameReceipt() {
        ExecuteAgentRunRequest request = validParallelRequest();
        StreamFixture complete = StreamFixture.complete(request);
        StreamFixture partial = complete.withLines(List.of(complete.lines().getFirst()));
        GraphTransportSecurityProof proof = mutualTlsProof();
        RecordingStaging staging = new RecordingStaging(request, partial);

        assertThatThrownBy(() -> client(
                                request,
                                proof,
                                new FakeCommandTransport(proof, partial),
                                staging)
                        .executeOrResume(
                                request,
                                ignored -> {},
                                new AgentRunCancellationToken()))
                .isInstanceOf(TargetE2EGraphClientException.class);

        assertThat(staging.actions)
                .containsExactly(
                        "admit",
                        "plan",
                        "append:PUBLIC_FRAME_START:DIALOGUE_FRAME",
                        "find-completion",
                        "fail:TARGET_E2E_GRAPH_PROTOCOL_REJECTED");
        assertThat(staging.nextSequence).isEqualTo(1L);
    }

    @Test
    void preparePreservesStrictNonRetryableRemoteErrorEnvelope() {
        ExecuteAgentRunRequest request = validParallelRequest();
        StreamFixture fixture = StreamFixture.complete(request);
        GraphTransportSecurityProof proof = mutualTlsProof();
        RecordingStaging staging = new RecordingStaging(request, fixture);

        assertThatThrownBy(() -> client(
                                request,
                                proof,
                                new PrepareErrorTransport(
                                        proof,
                                        "{\"code\":\"GRAPH_CONTRACT_REJECTED\",\"retryable\":false}"),
                                staging)
                        .executeOrResume(
                                request,
                                ignored -> {},
                                new AgentRunCancellationToken()))
                .isInstanceOfSatisfying(
                        TargetE2EGraphClientException.class,
                        failure -> {
                            assertThat(failure.errorCode())
                                    .isEqualTo("GRAPH_CONTRACT_REJECTED");
                            assertThat(failure.recoveryAction())
                                    .isEqualTo(
                                            TargetE2EGraphClientException.RecoveryAction
                                                    .FAIL_LOGICAL_RUN);
                        });
        assertThat(staging.actions).isEmpty();

        assertThatThrownBy(() -> client(
                                request,
                                proof,
                                new PrepareErrorTransport(
                                        proof,
                                        "{\"code\":\"GRAPH_CONTRACT_REJECTED\","
                                                + "\"retryable\":false,\"detail\":\"forbidden\"}"),
                                new RecordingStaging(request, fixture))
                        .executeOrResume(
                                request,
                                ignored -> {},
                                new AgentRunCancellationToken()))
                .isInstanceOfSatisfying(
                        TargetE2EGraphClientException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo("TARGET_E2E_GRAPH_PROTOCOL_REJECTED"));
    }

    @Test
    void executeNonRetryableFailureTerminalizesItsAdmittedFrameSet() {
        ExecuteAgentRunRequest request = validParallelRequest();
        StreamFixture fixture = StreamFixture.complete(request);
        GraphTransportSecurityProof proof = mutualTlsProof();
        RecordingStaging staging = new RecordingStaging(request, fixture);

        assertThatThrownBy(() -> client(
                                request,
                                proof,
                                new ExecuteErrorTransport(
                                        proof,
                                        fixture,
                                        "{\"code\":\"GRAPH_BULKHEAD_SCOPE_INVALID\","
                                                + "\"retryable\":false}"),
                                staging)
                        .executeOrResume(
                                request,
                                ignored -> {},
                                new AgentRunCancellationToken()))
                .isInstanceOfSatisfying(
                        TargetE2EGraphClientException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo("GRAPH_BULKHEAD_SCOPE_INVALID"));

        assertThat(staging.actions)
                .containsExactly(
                        "admit",
                        "plan",
                        "fail:GRAPH_BULKHEAD_SCOPE_INVALID");
    }

    private static HttpTargetE2EIntakeParallelFrameExecutionClient client(
            ExecuteAgentRunRequest request,
            GraphTransportSecurityProof proof,
            GraphCommandHttpTransport transport,
            RecordingStaging staging) {
        GraphRegistryBindingPolicy policy = ignored ->
                new GraphRegistryBindingPolicy.ExpectedBinding("9".repeat(64), "tools.none.v1");
        return new HttpTargetE2EIntakeParallelFrameExecutionClient(
                ACTIVATION_ID,
                bundle(transport, proof),
                exact -> TargetE2EAgentRunIdentityResolver.DurableIdentity.from(exact, 7L),
                TargetE2EGraphTestFixtures.codec(),
                signer(request, staging),
                policy,
                ignored -> new IntakeParallelFrameAdmissionAuthorityResolver.AdmissionAuthority(
                        7L,
                        "a".repeat(64),
                        "SESSION_V4_1",
                        staging.eventAuthority),
                staging,
                TargetE2EGraphTestFixtures.MAPPER,
                BASE_URI,
                Duration.ofSeconds(8));
    }

    private static ExecuteAgentRunRequest validParallelRequest() {
        ExecuteAgentRunRequest fixture = AgentRunPersistenceFixtures.parallelIntakeRequest();
        ObjectNode commandJson = TargetE2EGraphTestFixtures.MAPPER.valueToTree(fixture.command());
        commandJson.put("thread_id", "grt.v1." + "1".repeat(32));
        commandJson.remove("request_hash");
        commandJson.put("request_hash", ContractJson.sha256Hex(commandJson));
        try {
            RoomGraphCommand command = TargetE2EGraphTestFixtures.MAPPER.treeToValue(
                    commandJson, RoomGraphCommand.class);
            return new ExecuteAgentRunRequest(
                    fixture.schemaVersion(),
                    fixture.agentRunId(),
                    fixture.attemptNo(),
                    fixture.attemptLimit(),
                    fixture.streamProtocol(),
                    fixture.logicalInputHash(),
                    fixture.previousAttemptId(),
                    fixture.resetRequired(),
                    fixture.publicSequenceOffset(),
                    command);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("valid parallel request fixture failed", exception);
        }
    }

    private static TargetE2EGraphEnvelopeSigner.SignedEnvelope credential(
            ExecuteAgentRunRequest request) {
        Instant issuedAt = Instant.now().minusSeconds(1);
        return new TargetE2EGraphEnvelopeSigner.SignedEnvelope(
                COMPACT_JWS,
                request.command().invocationContext().envelopeKeyId(),
                "parallel-command-jti-001",
                issuedAt,
                issuedAt.plusSeconds(45));
    }

    private static TargetE2EGraphEnvelopeSigner signer(
            ExecuteAgentRunRequest request, RecordingStaging staging) {
        return new TargetE2EGraphEnvelopeSigner() {
            @Override
            public SignedEnvelope sign(
                    TargetE2EGraphCommandEnvelope envelope,
                    GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding) {
                return credential(request);
            }

            @Override
            public SignedEnvelope signParallel(
                    TargetE2EGraphCommandEnvelope envelope,
                    GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding,
                    ParallelDeliveryBinding deliveryBinding) {
                staging.deliveryBindings.add(deliveryBinding);
                return credential(request);
            }
        };
    }

    private static String receiptHash(GraphCommandHttpTransport.Request request) {
        try {
            String encoded = request.headers().get(
                    HttpTargetE2EIntakeParallelFrameExecutionClient.ADMISSION_HEADER);
            JsonNode receipt = TargetE2EGraphTestFixtures.MAPPER.readTree(
                    Base64.getUrlDecoder().decode(encoded));
            return receipt.path("receipt_sha256").asText();
        } catch (Exception exception) {
            throw new IllegalStateException("parallel admission receipt could not be decoded", exception);
        }
    }

    private static GraphTransportSecurityProof mutualTlsProof() {
        try {
            Class<?> type = Class.forName(
                    "com.example.dispute.workflow.infrastructure.agent."
                            + "TrustedGraphTransportFactory$MutualTlsProof");
            Constructor<?> constructor = type.getDeclaredConstructor(String.class, URI.class);
            constructor.setAccessible(true);
            return (GraphTransportSecurityProof)
                    constructor.newInstance("parallel-client-test", BASE_URI);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static GraphTransportBundle bundle(
            GraphCommandHttpTransport transport, GraphTransportSecurityProof proof) {
        GraphReconciliationHttpTransport reconciliation = new GraphReconciliationHttpTransport() {
            @Override
            public GraphTransportSecurityProof transportProof() {
                return proof;
            }

            @Override
            public Response exchange(
                    Request request, AgentRunCancellationToken cancellationToken) {
                throw new AssertionError("parallel Frame execution must not reconcile through HTTP");
            }
        };
        try {
            Constructor<GraphTransportBundle> constructor = GraphTransportBundle.class
                    .getDeclaredConstructor(
                            GraphCommandHttpTransport.class,
                            GraphReconciliationHttpTransport.class,
                            GraphTransportSecurityProof.class);
            constructor.setAccessible(true);
            return constructor.newInstance(transport, reconciliation, proof);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class FakeCommandTransport implements GraphCommandHttpTransport {

        private final GraphTransportSecurityProof proof;
        private final StreamFixture fixture;
        private final List<Request> requests = new ArrayList<>();

        private FakeCommandTransport(
                GraphTransportSecurityProof proof, StreamFixture fixture) {
            this.proof = proof;
            this.fixture = fixture;
        }

        @Override
        public GraphTransportSecurityProof transportProof() {
            return proof;
        }

        @Override
        public void stream(
                Request request,
                AgentRunCancellationToken cancellationToken,
                Listener listener) {
            requests.add(request);
            TargetE2EGraphCommandEnvelope envelope =
                    TargetE2EGraphTestFixtures.codec().decodeCommand(request.body());
            boolean prepare = "PREPARE".equals(request.headers().get(
                    HttpTargetE2EIntakeParallelFrameExecutionClient.PHASE_HEADER));
            listener.onResponse(new ResponseHead(
                    200,
                    request.uri(),
                    Map.of(
                            "Content-Type", List.of(prepare
                                    ? "application/json; charset=utf-8"
                                    : "application/x-ndjson; charset=utf-8"),
                            "Cache-Control", List.of("no-store, no-transform"),
                            "X-Agent-Run-Id", List.of(envelope.command().logicalRunId()),
                            "X-Agent-Stream-Protocol", List.of("agent-stream.v4"),
                            "X-Graph-Execution-Lane", List.of(envelope.executionLane()),
                            "X-Graph-Activation-Id", List.of(envelope.activationId()),
                            "X-Intake-Frame-Set-Id", List.of(fixture.frameSetId()),
                            "X-Intake-Parallel-Authority",
                                    List.of(fixture.authorityHeader()))));
            if (prepare) {
                listener.onLine("{\"schema_version\":\"intake.parallel-prepared.v1\"}");
            } else {
                fixture.lines().forEach(listener::onLine);
            }
        }
    }

    private static final class PrepareErrorTransport implements GraphCommandHttpTransport {

        private final GraphTransportSecurityProof proof;
        private final String body;

        private PrepareErrorTransport(GraphTransportSecurityProof proof, String body) {
            this.proof = proof;
            this.body = body;
        }

        @Override
        public GraphTransportSecurityProof transportProof() {
            return proof;
        }

        @Override
        public void stream(
                Request request,
                AgentRunCancellationToken cancellationToken,
                Listener listener) {
            assertThat(request.headers())
                    .containsEntry(
                            HttpTargetE2EIntakeParallelFrameExecutionClient.PHASE_HEADER,
                            "PREPARE");
            listener.onResponse(new ResponseHead(
                    409,
                    request.uri(),
                    Map.of(
                            "Content-Type", List.of("application/json; charset=utf-8"),
                            "Cache-Control", List.of("no-store, no-transform"))));
            listener.onLine(body);
        }
    }

    private static final class ExecuteErrorTransport implements GraphCommandHttpTransport {

        private final GraphTransportSecurityProof proof;
        private final FakeCommandTransport prepared;
        private final String body;

        private ExecuteErrorTransport(
                GraphTransportSecurityProof proof, StreamFixture fixture, String body) {
            this.proof = proof;
            this.prepared = new FakeCommandTransport(proof, fixture);
            this.body = body;
        }

        @Override
        public GraphTransportSecurityProof transportProof() {
            return proof;
        }

        @Override
        public void stream(
                Request request,
                AgentRunCancellationToken cancellationToken,
                Listener listener) {
            if ("PREPARE".equals(request.headers().get(
                    HttpTargetE2EIntakeParallelFrameExecutionClient.PHASE_HEADER))) {
                prepared.stream(request, cancellationToken, listener);
                return;
            }
            listener.onResponse(new ResponseHead(
                    409,
                    request.uri(),
                    Map.of(
                            "Content-Type", List.of("application/json; charset=utf-8"),
                            "Cache-Control", List.of("no-store, no-transform"))));
            listener.onLine(body);
        }
    }

    private static final class RecordingStaging implements IntakeParallelFrameStagingPort {

        private final ExecuteAgentRunRequest request;
        private final StreamFixture fixture;
        private final EventAuthority eventAuthority;
        private final List<String> actions = new ArrayList<>();
        private final List<TargetE2EGraphEnvelopeSigner.ParallelDeliveryBinding>
                deliveryBindings = new ArrayList<>();
        private final EnumMap<FrameType, FrameSlotView> slots = new EnumMap<>(FrameType.class);
        private final EnumMap<FrameType, FrameSealCommand> seals = new EnumMap<>(FrameType.class);
        private FrameSetAdmission admission;
        private long nextSequence;
        private int sealed;
        private long frameSetVersion;

        private RecordingStaging(ExecuteAgentRunRequest request, StreamFixture fixture) {
            this.request = request;
            this.fixture = fixture;
            eventAuthority = new EventAuthority(
                    "BINDING_V4_1",
                    "THREAD_REGISTRATION_V4_1",
                    1L,
                    1L,
                    0L,
                    request.command().requestHash());
        }

        @Override
        public FrameSetReceipt admit(FrameSetAdmission value) {
            actions.add("admit");
            admission = value;
            EnumMap<FrameType, Long> selected = new EnumMap<>(FrameType.class);
            value.manifests().forEach(manifest -> {
                selected.put(manifest.frameType(), manifest.generation());
                slots.put(
                        manifest.frameType(),
                        new FrameSlotView(
                                manifest.frameType(),
                                manifest.generation(),
                                manifest.frameId(),
                                SlotState.ADMITTED,
                                null));
            });
            return new FrameSetReceipt(
                    value.frameSetId(),
                    true,
                    "FRAME_SET_RECEIPT_V4_1",
                    AssemblyState.COLLECTING,
                    selected);
        }

        @Override
        public FrameSetFailureReceipt failUncommitted(FrameSetFailureCommand command) {
            actions.add("fail:" + command.failureCode());
            frameSetVersion++;
            return new FrameSetFailureReceipt(
                    command.frameSetId(),
                    "FRAME_SET_FAILURE_RECEIPT_V4_1",
                    command.failureCode(),
                    true,
                    frameSetVersion);
        }

        @Override
        public IngressReceipt append(IngressCommand command) {
            actions.add("append:" + command.ingressKind() + ":" + command.frameType());
            FrameSlotView previous = slots.get(command.frameType());
            SlotState state = switch (command.ingressKind()) {
                case PUBLIC_FRAME_START -> SlotState.STARTED;
                case PUBLIC_FRAME_INTERRUPTED -> SlotState.FAILED;
                default -> previous.state();
            };
            slots.put(
                    command.frameType(),
                    new FrameSlotView(
                            command.frameType(),
                            command.generation(),
                            previous.frameId(),
                            state,
                            null));
            long sequence = nextSequence++;
            return new IngressReceipt(
                    "INGRESS_" + sequence,
                    "INGRESS_RECEIPT_" + sequence,
                    true,
                    sequence,
                    sequence);
        }

        @Override
        public ExecutionPlan planExecution(FrameSetAdmission value) {
            actions.add("plan");
            EnumMap<FrameType, ExecutionLane> lanes = new EnumMap<>(FrameType.class);
            value.manifests().forEach(manifest -> lanes.put(
                    manifest.frameType(),
                    new ExecutionLane(
                            manifest.frameType(),
                            manifest.generation(),
                            manifest.frameId(),
                            SlotState.ADMITTED,
                            ExecutionAction.RUN_CURRENT,
                            0,
                            0,
                            null,
                            null,
                            null,
                            null)));
            return new ExecutionPlan(
                    value.frameSetId(), value.runId(), value.attemptId(), lanes);
        }

        @Override
        public FrameSealReceipt seal(FrameSealCommand command) {
            actions.add("seal:" + command.frameType());
            long sequence = nextSequence++;
            sealed++;
            String resultId = "FRAME_RESULT_" + command.frameType();
            seals.put(command.frameType(), command);
            slots.put(
                    command.frameType(),
                    new FrameSlotView(
                            command.frameType(),
                            command.generation(),
                            command.frameId(),
                            SlotState.SEALED,
                            resultId));
            return new FrameSealReceipt(
                    command.frameSetId(),
                    command.frameType(),
                    command.generation(),
                    resultId,
                    "FRAME_RECEIPT_" + command.frameType(),
                    true,
                    sealed == FrameType.values().length,
                    AssemblyState.COLLECTING,
                    sequence,
                    sequence);
        }

        @Override
        public Optional<ExactThreeCompletion> findExactThreeCompletion(
                String frameSetId, String runId, String attemptId) {
            actions.add("find-completion");
            if (seals.size() != FrameType.values().length) {
                return Optional.empty();
            }
            EnumMap<FrameType, ExactThreeFrame> frames = new EnumMap<>(FrameType.class);
            for (FrameType type : FrameType.values()) {
                FrameSealCommand seal = seals.get(type);
                FrameSlotView slot = slots.get(type);
                frames.put(type, new ExactThreeFrame(
                        type,
                        seal.generation(),
                        seal.frameId(),
                        0,
                        slot.resultId(),
                        seal.resultSha256(),
                        seal.publicProjectionSha256(),
                        seal.nextLocalIndex()));
            }
            return Optional.of(new ExactThreeCompletion(
                    frameSetId,
                    runId,
                    attemptId,
                    nextSequence - 1,
                    true,
                    frames));
        }

        @Override
        public FrameRetryReceipt admitRetry(FrameRetryAdmission admission) {
            actions.add("retry:" + admission.replacement().frameType() + ":"
                    + admission.replacement().generation());
            slots.put(
                    admission.replacement().frameType(),
                    new FrameSlotView(
                            admission.replacement().frameType(),
                            admission.replacement().generation(),
                            admission.replacement().frameId(),
                            SlotState.ADMITTED,
                            null));
            return new FrameRetryReceipt(
                    admission.frameSetId(),
                    admission.replacement().frameType(),
                    admission.replacement().generation(),
                    admission.replacement().frameId(),
                    "FRAME_RETRY_RECEIPT_" + admission.replacement().frameType(),
                    true);
        }

        @Override
        public Optional<AssemblyView> findAssembly(String frameSetId) {
            actions.add("find");
            return Optional.of(new AssemblyView(
                    frameSetId,
                    request.agentRunId(),
                    request.attemptId(),
                    eventAuthority,
                    CONTEXT_HASH,
                    MODEL_CONTEXT_HASH,
                    AssemblyState.COLLECTING,
                    slots,
                    null,
                    null,
                    null,
                    null,
                    null));
        }
    }

    private record StreamFixture(
            String frameSetId,
            String authorityHeader,
            List<String> lines,
            Map<FrameType, FrameWire> frames) {

        private StreamFixture {
            lines = List.copyOf(lines);
            frames = Map.copyOf(frames);
        }

        static StreamFixture complete(ExecuteAgentRunRequest request) {
            RoomGraphCommand command = request.command();
            String frameSetId =
                    HttpTargetE2EIntakeParallelFrameExecutionClientTest.frameSetId(command);
            EnumMap<FrameType, FrameWire> frames = new EnumMap<>(FrameType.class);
            int discriminator = 1;
            for (FrameType type : FrameType.values()) {
                String inputHash = Integer.toString(discriminator).repeat(64);
                String promptHash = Integer.toHexString(9 + discriminator).repeat(64);
                frames.put(
                        type,
                        new FrameWire(
                                type,
                                initialFrameId(frameSetId, type, inputHash),
                                inputHash,
                                promptHash));
                discriminator++;
            }
            ObjectNode authority = TargetE2EGraphTestFixtures.MAPPER.createObjectNode();
            authority.put(
                    "schema_version",
                    TargetE2EIntakeParallelTransportCodec.AUTHORITY_SCHEMA);
            authority.put("frame_set_id", frameSetId);
            authority.put("run_id", request.agentRunId());
            authority.put("attempt_id", request.attemptId());
            ArrayNode rawFrames = authority.putArray("frames");
            for (FrameType type : FrameType.values()) {
                FrameWire frame = frames.get(type);
                ObjectNode raw = rawFrames.addObject();
                raw.put("frame_type", type.name());
                raw.put("generation", 1);
                raw.put("frame_id", frame.frameId());
                raw.put("frame_model_input_sha256", frame.inputHash());
                raw.put("frame_prompt_sha256", frame.promptHash());
                raw.put("context_envelope_sha256", CONTEXT_HASH);
                raw.put("model_context_view_sha256", MODEL_CONTEXT_HASH);
            }
            authority.put("authority_sha256", ContractJson.sha256Hex(authority));
            String header = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(ContractJson.canonicalize(authority));

            List<String> lines = new ArrayList<>();
            for (FrameType type : FrameType.values()) {
                lines.add(started(request, frameSetId, frames.get(type)));
            }
            FrameWire originalDialogue = frames.get(FrameType.DIALOGUE_FRAME);
            FrameWire replacementDialogue = new FrameWire(
                    FrameType.DIALOGUE_FRAME,
                    replacementFrameId(
                            frameSetId,
                            originalDialogue,
                            2),
                    originalDialogue.inputHash(),
                    originalDialogue.promptHash(),
                    2);
            lines.add(interrupted(request, frameSetId, originalDialogue));
            lines.add(reset(request, frameSetId, originalDialogue, replacementDialogue));
            lines.add(started(request, frameSetId, replacementDialogue));
            List<FrameType> projectionOrder =
                    List.of(FrameType.DIALOGUE_FRAME, FrameType.QUALITY_FRAME, FrameType.DOSSIER_FRAME);
            for (FrameType type : projectionOrder) {
                lines.add(projection(
                        request,
                        frameSetId,
                        type == FrameType.DIALOGUE_FRAME
                                ? replacementDialogue
                                : frames.get(type)));
            }
            List<FrameType> sealOrder =
                    List.of(FrameType.QUALITY_FRAME, FrameType.DIALOGUE_FRAME, FrameType.DOSSIER_FRAME);
            for (FrameType type : sealOrder) {
                lines.add(sealed(
                        request,
                        frameSetId,
                        type == FrameType.DIALOGUE_FRAME
                                ? replacementDialogue
                                : frames.get(type)));
            }
            return new StreamFixture(frameSetId, header, lines, frames);
        }

        StreamFixture withAuthoritySuffix(String suffix) {
            return new StreamFixture(frameSetId, authorityHeader + suffix, lines, frames);
        }

        StreamFixture withLines(List<String> replacement) {
            return new StreamFixture(frameSetId, authorityHeader, replacement, frames);
        }
    }

    private record FrameWire(
            FrameType type,
            String frameId,
            String inputHash,
            String promptHash,
            int generation) {

        private FrameWire(
                FrameType type, String frameId, String inputHash, String promptHash) {
            this(type, frameId, inputHash, promptHash, 1);
        }
    }

    private static String started(
            ExecuteAgentRunRequest request, String frameSetId, FrameWire frame) {
        ObjectNode root = common(request, frameSetId, frame.type(), "FRAME_STARTED");
        root.put("generation", frame.generation());
        root.put("frame_id", frame.frameId());
        root.put("frame_model_input_sha256", frame.inputHash());
        root.put("frame_prompt_sha256", frame.promptHash());
        root.put("context_envelope_sha256", CONTEXT_HASH);
        root.put("model_context_view_sha256", MODEL_CONTEXT_HASH);
        return ContractJson.canonicalString(root);
    }

    private static String projection(
            ExecuteAgentRunRequest request, String frameSetId, FrameWire frame) {
        ObjectNode root = common(
                request, frameSetId, frame.type(), "FRAME_PROJECTION_ITEM");
        root.put("generation", frame.generation());
        root.put("frame_id", frame.frameId());
        root.put("local_index", 0);
        root.put("next_local_index", 1);
        ObjectNode item = root.putObject("item");
        item.put("canonical_item_id", "item." + frame.type().name().toLowerCase());
        item.put("projection_kind", "PUBLIC_TEXT");
        item.put("projection_path_id", "intake.preview");
        item.put("value_kind", "TEXT");
        item.put("public_text", "公开输出 " + frame.type());
        root.put("item_sha256", ContractJson.sha256Hex(item));
        return ContractJson.canonicalString(root);
    }

    private static String sealed(
            ExecuteAgentRunRequest request, String frameSetId, FrameWire frame) {
        ObjectNode result = TargetE2EGraphTestFixtures.MAPPER.createObjectNode();
        result.put("schema_version", frame.type().outputSchemaId());
        result.put("frame_type", frame.type().name());
        ObjectNode root = common(request, frameSetId, frame.type(), "FRAME_SEALED");
        root.put("generation", frame.generation());
        root.put("frame_id", frame.frameId());
        root.put("child_checkpoint_ref", "checkpoint://" + frame.type().name().toLowerCase());
        root.put("child_checkpoint_sha256", "4".repeat(64));
        root.put("context_envelope_sha256", CONTEXT_HASH);
        root.put("model_context_view_sha256", MODEL_CONTEXT_HASH);
        root.put("canonical_result_json", ContractJson.canonicalString(result));
        root.put("result_sha256", ContractJson.sha256Hex(result));
        root.put("public_projection_sha256", "5".repeat(64));
        root.put("next_local_index", 1);
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 10);
        usage.put("output_tokens", 5);
        usage.put("total_tokens", 15);
        usage.put("latency_ms", 20);
        usage.put("provider_call_count", 1);
        usage.put("model", "qwen3.7-max");
        root.put("completed_at", OCCURRED_AT.plusSeconds(1).toString());
        return ContractJson.canonicalString(root);
    }

    private static ObjectNode common(
            ExecuteAgentRunRequest request,
            String frameSetId,
            FrameType frameType,
            String eventKind) {
        ObjectNode root = TargetE2EGraphTestFixtures.MAPPER.createObjectNode();
        root.put("schema_version", TargetE2EIntakeParallelTransportCodec.EVENT_SCHEMA);
        root.put("frame_set_id", frameSetId);
        root.put("run_id", request.agentRunId());
        root.put("attempt_id", request.attemptId());
        root.put("frame_type", frameType.name());
        root.put("occurred_at", OCCURRED_AT.toString());
        root.put("event_kind", eventKind);
        return root;
    }

    private static String interrupted(
            ExecuteAgentRunRequest request, String frameSetId, FrameWire frame) {
        ObjectNode root = common(
                request, frameSetId, frame.type(), "FRAME_INTERRUPTED");
        root.put("generation", frame.generation());
        root.put("frame_id", frame.frameId());
        root.put("error_code", "OUTPUT_SCHEMA_INVALID");
        root.put("retryable", true);
        return ContractJson.canonicalString(root);
    }

    private static String reset(
            ExecuteAgentRunRequest request,
            String frameSetId,
            FrameWire previous,
            FrameWire replacement) {
        ObjectNode root = common(
                request, frameSetId, previous.type(), "FRAME_GENERATION_RESET");
        root.put("old_generation", previous.generation());
        root.put("new_generation", replacement.generation());
        root.put("old_frame_id", previous.frameId());
        root.put("new_frame_id", replacement.frameId());
        root.put("reason_code", "OUTPUT_SCHEMA_INVALID");
        return ContractJson.canonicalString(root);
    }

    private static String frameSetId(RoomGraphCommand command) {
        ObjectNode identity = TargetE2EGraphTestFixtures.MAPPER.createObjectNode();
        identity.put("contract_version", "intake.parallel-frame-set-identity.v1");
        identity.put("thread_id", command.threadId());
        identity.put("command_id", command.commandId());
        identity.put("logical_run_id", command.logicalRunId());
        identity.put("attempt_id", command.attemptId());
        identity.put("request_hash", command.requestHash());
        identity.put("context_envelope_sha256", CONTEXT_HASH);
        return "IFS_" + ContractJson.sha256Hex(identity).substring(0, 32);
    }

    private static String initialFrameId(
            String frameSetId, FrameType frameType, String inputHash) {
        ObjectNode identity = TargetE2EGraphTestFixtures.MAPPER.createObjectNode();
        identity.put("contract_version", "intake.parallel-frame-identity.v1");
        identity.put("frame_set_id", frameSetId);
        identity.put("frame_type", frameType.name());
        identity.put("generation", 1);
        identity.put("frame_model_input_sha256", inputHash);
        return "IFR_" + ContractJson.sha256Hex(identity).substring(0, 32);
    }

    private static String replacementFrameId(
            String frameSetId, FrameWire previous, int generation) {
        ObjectNode identity = TargetE2EGraphTestFixtures.MAPPER.createObjectNode();
        identity.put("frame_set_id", frameSetId);
        identity.put("frame_type", previous.type().name());
        identity.put("old_frame_id", previous.frameId());
        identity.put("generation", generation);
        identity.put("frame_model_input_sha256", previous.inputHash());
        return "intake.frame." + ContractJson.sha256Hex(identity).substring(0, 32);
    }
}
