package com.example.dispute.workflow.shadow;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Dimension;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.ObservedValue;
import com.example.dispute.workflow.shadow.intake.IntakeShadowParityService.ParitySnapshot;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.ActivityAuthorization;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.AdmissionAttempt;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.VerifiedAdmission;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonLedger;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonLedger.CommitRequest;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonLedger.CommitResult;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonReceiptFactory;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class IntakeSyntheticTestFixtures {

    public static final String TENANT = "tenant-p4-synthetic";
    public static final String CASE_ID = "CASE_P4_SYNTHETIC";
    public static final long EPOCH = 9;
    public static final long FENCE = 41;
    public static final String ACTOR_SCOPE = "a".repeat(64);
    public static final String RESPONDENT_SCOPE = "b".repeat(64);
    public static final String THREAD_ID = "grt.v1." + "c".repeat(32);
    public static final String AGENT_SESSION = "AGENT_SESSION_P4_SYNTHETIC";

    private IntakeSyntheticTestFixtures() {}

    public static IntakeRoomStart start() {
        return new IntakeRoomStart(
                "intake-room-start.v1",
                TENANT,
                CASE_ID,
                EPOCH,
                FENCE,
                0,
                0,
                1,
                1,
                "intake-workflow.synthetic.v1",
                "2.0.0",
                "intake-checkpoint.v2",
                "intake-prompt.v2",
                "intake-model.synthetic.v1",
                "intake-turn-proposal.v2",
                "intake-policy.v2",
                "intake-guardrail.v2",
                "no-tools.v1",
                ACTOR_SCOPE,
                RESPONDENT_SCOPE);
    }

    public static IntakeWorkflowCommand inertCommand(
            String commandId, IntakeCommandType commandType) {
        return new IntakeWorkflowCommand(
                "intake-workflow-command.v1",
                commandId,
                TENANT,
                CASE_ID,
                EPOCH,
                FENCE,
                1,
                commandType,
                IntakeParty.INITIATOR,
                ACTOR_SCOPE,
                "urn:after-sale-flow:intake-command:" + commandId,
                hash(1),
                "intake.operation:" + CASE_ID + ":" + commandId,
                hash(2));
    }

    public static AdmissionAttempt signedAttempt(TrafficSource source) {
        String compactJws = "eyJhbGciOiJFUzI1NiJ9.c3ludGhldGljLWludGFrZQ.c2lnbmF0dXJl";
        return new AdmissionAttempt(
                "intake-signed-synthetic-admission-attempt.v1",
                source,
                "synthetic-key.v1",
                compactJws,
                sha256(compactJws),
                THREAD_ID,
                AGENT_SESSION,
                Long.MAX_VALUE,
                retryBudget());
    }

    public static RetryBudget retryBudget() {
        return new RetryBudget("intake-retry-budget.v1", 2, 3, 1);
    }

    public static ParitySnapshot paritySnapshot() {
        EnumMap<Dimension, ObservedValue> values = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            values.put(
                    dimension,
                    new ObservedValue(
                            IntakeShadowComparison.Classification.VALUE,
                            hash(dimension.ordinal() + 20)));
        }
        return new ParitySnapshot(values, Set.of());
    }

    public static SnapshotPublicationReceipt snapshotReceipt(
            SnapshotPublicationRequest request) {
        return new SnapshotPublicationReceipt(
                "intake-snapshot-publication-receipt.v1",
                operation(request.operationKey(), request.requestHash(), hash(4)),
                new ImmutablePayloadRef(
                        "immutable-payload-ref.v1",
                        "SYNTHETIC_SNAPSHOT_1",
                        "INTAKE_SNAPSHOT",
                        "intake-domain-snapshot.v2",
                        "urn:after-sale-flow:intake-snapshot:synthetic-1",
                        "synthetic-snapshot.v1",
                        hash(5),
                        1024),
                request.domainRevision());
    }

    public static GraphExecutionReceipt graphReceipt(GraphExecutionRequest request) {
        String resultHash = hash(6);
        String proposalHash = hash(7);
        IntakeAgentRunRef run =
                new IntakeAgentRunRef(
                        "intake-agent-run-ref.v1",
                        "RUN_SYNTHETIC_1",
                        "ATTEMPT_SYNTHETIC_1",
                        resultHash);
        IntakeGraphExecutionRef graph =
                new IntakeGraphExecutionRef(
                        "intake-graph-execution-ref.v1",
                        request.threadId(),
                        request.envelope().commandId(),
                        "intake.v2",
                        request.envelope().pinnedVersions().graphVersion(),
                        "CHECKPOINT_SYNTHETIC_1",
                        "urn:after-sale-flow:graph-result:synthetic-1",
                        resultHash,
                        "urn:after-sale-flow:intake-proposal:synthetic-1",
                        proposalHash);
        return new GraphExecutionReceipt(
                "intake-graph-execution-receipt.v1",
                operation(request.operationKey(), request.requestHash(), resultHash),
                run,
                graph,
                new ImmutablePayloadRef(
                        "immutable-payload-ref.v1",
                        "SYNTHETIC_RESULT_1",
                        "GRAPH_RESULT",
                        "room-graph-result.v1",
                        graph.resultRef(),
                        "synthetic-result.v1",
                        resultHash,
                        1024),
                new ImmutablePayloadRef(
                        "immutable-payload-ref.v1",
                        "SYNTHETIC_PROPOSAL_1",
                        "INTAKE_PROPOSAL",
                        "intake-turn-proposal.v2",
                        graph.proposalRef(),
                        "synthetic-proposal.v1",
                        proposalHash,
                        1024));
    }

    public static TurnFinalizationRequest finalizationRequest(
            String commandId,
            IntakeParty party,
            String actorScopeHash,
            String requestHash) {
        return finalizationRequest(
                TENANT,
                CASE_ID,
                EPOCH,
                FENCE,
                commandId,
                party,
                actorScopeHash,
                THREAD_ID,
                AGENT_SESSION,
                requestHash);
    }

    public static TurnFinalizationRequest finalizationRequest(
            String tenant,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String commandId,
            IntakeParty party,
            String actorScopeHash,
            String threadId,
            String agentSessionId,
            String requestHash) {
        RetryBudget invocationBudget =
                new RetryBudget("intake-retry-budget.v1", 2, 1, 1);
        ActivityEnvelope envelope = new ActivityEnvelope(
                "intake-activity-envelope.v1",
                tenant,
                caseId,
                roomEpoch,
                fencingToken,
                commandId,
                1,
                IntakeCommandType.INTAKE_MESSAGE,
                party,
                actorScopeHash,
                "urn:after-sale-flow:intake-command:" + commandId,
                hash(1),
                0,
                0,
                Long.MAX_VALUE,
                invocationBudget,
                new PinnedVersions(
                        "intake-pinned-versions.v1",
                        "intake-workflow.synthetic.v1",
                        "2.0.0",
                        "intake-checkpoint.v2",
                        "intake-prompt.v2",
                        "intake-model.synthetic.v1",
                        "intake-turn-proposal.v2",
                        "intake-policy.v2",
                        "intake-guardrail.v2",
                        "no-tools.v1"),
                new ActivityInvocation(
                        "intake-activity-invocation.v1",
                        ActivityInvocationMode.FIRST_EXECUTION,
                        2));
        String graphOperationKey = IntakeOperationKeys.graphExecute(
                caseId, roomEpoch, threadId, commandId);
        GraphExecutionRequest graphRequest = new GraphExecutionRequest(
                "intake-graph-execution-request.v1",
                envelope,
                threadId,
                agentSessionId,
                graphOperationKey,
                requestHash);
        GraphExecutionReceipt graph = graphReceipt(graphRequest);
        String finalizationKey = IntakeOperationKeys.turnFinalize(
                caseId,
                roomEpoch,
                threadId,
                commandId,
                graph.operation().resultHash());
        return new TurnFinalizationRequest(
                "intake-turn-finalization-request.v1",
                envelope,
                threadId,
                agentSessionId,
                graph,
                finalizationKey,
                requestHash);
    }

    private static OperationReceipt operation(
            String operationKey, String requestHash, String resultHash) {
        return new OperationReceipt(
                "intake-operation-receipt.v1",
                operationKey,
                requestHash,
                resultHash,
                0,
                0);
    }

    public static String hash(long value) {
        return String.format("%064x", value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static final class Admission implements IntakeSignedSyntheticAdmissionPort {
        private volatile VerifiedAdmission verified;
        public volatile AdmissionAttempt lastAttempt;
        public volatile String verifiedRequestHashOverride;
        public final AtomicInteger admissions = new AtomicInteger();
        public volatile boolean activityAuthorized = true;

        @Override
        public VerifiedAdmission admit(AdmissionAttempt attempt, IntakeWorkflowCommand command) {
            admissions.incrementAndGet();
            lastAttempt = attempt;
            verified =
                    new VerifiedAdmission(
                            "intake-verified-synthetic-admission.v1",
                            TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC,
                            command.tenantSurrogate(),
                            command.caseId(),
                            command.roomEpoch(),
                            command.fencingToken(),
                            command.commandId(),
                            command.commandType(),
                            command.party(),
                            command.actorScopeHash(),
                            verifiedRequestHashOverride == null
                                    ? command.requestHash()
                                    : verifiedRequestHashOverride,
                            attempt.threadId(),
                            attempt.agentSessionId(),
                            attempt.deadlineEpochMillis(),
                            attempt.retryBudget(),
                            hash(8));
            return verified;
        }

        @Override
        public boolean isActivityAuthorized(ActivityAuthorization authorization) {
            VerifiedAdmission current = verified;
            return activityAuthorized
                    && current != null
                    && current.tenantSurrogate().equals(authorization.tenantSurrogate())
                    && current.caseId().equals(authorization.caseId())
                    && current.roomEpoch() == authorization.roomEpoch()
                    && current.fencingToken() == authorization.fencingToken()
                    && current.commandId().equals(authorization.commandId())
                    && current.commandType() == authorization.commandType()
                    && current.party() == authorization.party()
                    && current.actorScopeHash().equals(authorization.actorScopeHash())
                    && current.requestHash().equals(authorization.requestHash())
                    && current.threadId().equals(authorization.threadId())
                    && current.agentSessionId().equals(authorization.agentSessionId())
                    && current.deadlineEpochMillis() == authorization.deadlineEpochMillis()
                    && authorization.retryBudget().providerAttemptsRemaining()
                            <= current.retryBudget().providerAttemptsRemaining()
                    && authorization.retryBudget().activityAttemptsRemaining()
                            <= current.retryBudget().activityAttemptsRemaining()
                    && authorization.retryBudget().repairsRemaining()
                            <= current.retryBudget().repairsRemaining();
        }
    }

    public static final class InMemoryLedger implements IntakeSyntheticComparisonLedger {
        private final Map<String, CommitResult> committed = new ConcurrentHashMap<>();
        public final AtomicInteger writes = new AtomicInteger();

        @Override
        public CommitResult commit(CommitRequest request) {
            String operationKey = request.finalization().operationKey();
            CommitResult produced =
                    new CommitResult(
                            request.comparison(),
                            IntakeSyntheticComparisonReceiptFactory.create(
                                    request.finalization(),
                                    request.comparison(),
                                    request.projectedEventType(),
                                    OffsetDateTime.parse("2026-07-22T12:00:00Z")),
                            true);
            CommitResult existing = committed.putIfAbsent(operationKey, produced);
            if (existing != null) {
                if (!existing.comparison().equals(request.comparison())) {
                    throw new IllegalStateException("comparison conflict");
                }
                return new CommitResult(existing.comparison(), existing.receipt(), false);
            }
            writes.incrementAndGet();
            return produced;
        }

        @Override
        public Optional<CommitResult> find(TurnFinalizationRequest request) {
            return Optional.ofNullable(committed.get(request.operationKey()))
                    .map(result -> new CommitResult(result.comparison(), result.receipt(), false));
        }
    }
}
