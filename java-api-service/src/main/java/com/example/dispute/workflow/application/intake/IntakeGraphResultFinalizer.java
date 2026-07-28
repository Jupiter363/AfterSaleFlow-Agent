package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.application.intake.IntakeFormalCommitPort.CommitCommand;
import com.example.dispute.workflow.application.intake.IntakeFormalCommitPort.AgentRunFinalEligibilityRequirement;
import com.example.dispute.workflow.application.intake.IntakeFormalCommitPort.CurrentAuthorityRequirement;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult.ArtifactOperation;
import com.example.dispute.workflow.contract.v1.RoomGraphResult.ExecutionMetadata;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates a graph proposal and hands it to exactly one formal commit port.
 *
 * <p>This class is intentionally framework-free. It is safe to use from tests and from an
 * explicitly injected TEMPORAL Activity adapter, while SHADOW has no bean that can discover it.
 */
public final class IntakeGraphResultFinalizer {

    public static final String LEGACY_GRAPH_KEY = "intake.v2";
    public static final String TARGET_E2E_GRAPH_KEY = "all-rooms.target-e2e.v1";

    private final IntakeTurnProposalLoader proposalLoader;
    private final IntakeFormalCommitPort commitPort;
    private final Optional<IntakeFinalizationReceiptReader> receiptReader;
    private final Optional<AuthorityPreflight> authorityPreflight;
    private final String expectedGraphKey;

    public IntakeGraphResultFinalizer(
            IntakeTurnProposalLoader proposalLoader, IntakeFormalCommitPort commitPort) {
        this(
                proposalLoader,
                commitPort,
                commitPort instanceof IntakeFinalizationReceiptReader reader ? reader : null,
                LEGACY_GRAPH_KEY);
    }

    /**
     * Creates a Finalizer with an optional committed-receipt lookup for Activity completion loss.
     *
     * <p>The reader is intentionally supplied as an explicit port. It is not a Spring dependency,
     * so the Phase 4 SHADOW graph cannot discover the formal boundary.
     */
    public IntakeGraphResultFinalizer(
            IntakeTurnProposalLoader proposalLoader,
            IntakeFormalCommitPort commitPort,
            IntakeFinalizationReceiptReader receiptReader) {
        this(proposalLoader, commitPort, receiptReader, LEGACY_GRAPH_KEY);
    }

    public IntakeGraphResultFinalizer(
            IntakeTurnProposalLoader proposalLoader,
            IntakeFormalCommitPort commitPort,
            IntakeFinalizationReceiptReader receiptReader,
            String expectedGraphKey) {
        this.proposalLoader = Objects.requireNonNull(proposalLoader, "proposalLoader");
        this.commitPort = Objects.requireNonNull(commitPort, "commitPort");
        this.receiptReader = Optional.ofNullable(receiptReader);
        this.authorityPreflight = commitPort instanceof AuthorityPreflight preflight
                ? Optional.of(preflight)
                : Optional.empty();
        if (!LEGACY_GRAPH_KEY.equals(expectedGraphKey)
                && !TARGET_E2E_GRAPH_KEY.equals(expectedGraphKey)) {
            throw new IllegalArgumentException("expectedGraphKey is not an allowed Intake graph");
        }
        this.expectedGraphKey = expectedGraphKey;
    }

    public IntakeFinalizationReceipt finalizeResult(IntakeGraphFinalizationRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequest(request);

        Optional<IntakeFinalizationReceipt> existing = receiptReader.flatMap(reader ->
                reader.findCommitted(
                        request.authority().tenantSurrogate(),
                        request.operationKey(),
                        request.requestHash()));
        if (existing.isPresent()) {
            IntakeFinalizationReceipt receipt = existing.orElseThrow();
            validateReceipt(request, receipt);
            return receipt;
        }

        // The database-backed adapter performs a short read-only authority check here and repeats
        // the same checks under write locks in commit(). This prevents stale or revoked work from
        // reaching immutable object storage without weakening the final transaction boundary.
        try {
            authorityPreflight.ifPresent(preflight -> preflight.preflight(request));
        } catch (RuntimeException preflightFailure) {
            Optional<IntakeFinalizationReceipt> racedCommit = receiptReader.flatMap(reader ->
                    reader.findCommitted(
                            request.authority().tenantSurrogate(),
                            request.operationKey(),
                            request.requestHash()));
            if (racedCommit.isPresent()) {
                IntakeFinalizationReceipt receipt = racedCommit.orElseThrow();
                validateReceipt(request, receipt);
                return receipt;
            }
            throw preflightFailure;
        }

        IntakeGraphFinalizationRequest.Authority authority = request.authority();
        IntakeProposalAuthority proposalAuthority = new IntakeProposalAuthority(
                authority.commandId(),
                authority.logicalRunId(),
                authority.attemptId(),
                authority.caseId(),
                authority.roomEpoch(),
                authority.threadId(),
                authority.actorScopeHash(),
                authority.agentSessionId(),
                authority.cognitiveRevision(),
                request.initialSnapshot().payloadRef().sha256(),
                request.event() == null ? null : request.event().payloadRef().sha256(),
                authority.profileVersions());

        // The object is loaded only after every envelope and graph hash check and before any
        // formal transaction can begin.
        IntakeTurnProposalLoader.LoadedProposal loaded =
                proposalLoader.load(request.proposalReference(), proposalAuthority);
        if (!authority.proposalHash().equals(loaded.proposal().proposalHash())) {
            throw rejected(
                    "INTAKE_PROPOSAL_HASH_MISMATCH",
                    "loaded proposal hash does not match the trusted Activity reference");
        }

        IntakeFinalizationReceipt receipt =
                Objects.requireNonNull(
                        commitPort.commit(
                                new CommitCommand(
                                        request,
                                        loaded,
                                        currentAuthority(request),
                                        agentRunEligibility(request))),
                        "formal commit port returned no receipt");
        validateReceipt(request, receipt);
        return receipt;
    }

    private static CurrentAuthorityRequirement currentAuthority(
            IntakeGraphFinalizationRequest request) {
        IntakeGraphFinalizationRequest.Authority authority = request.authority();
        IntakePrivateThreadRegistration.ActorScope actor =
                request.threadBinding().registration().actorScope();
        return new CurrentAuthorityRequirement(
                authority.tenantSurrogate(),
                authority.caseId(),
                authority.roomEpoch(),
                authority.fencingToken(),
                authority.processRevision(),
                authority.roomRevision(),
                authority.stageCode(),
                authority.stageSequence(),
                actor.actorId(),
                actor.actorRole(),
                actor.audience(),
                authority.actorScopeHash(),
                authority.agentSessionId());
    }

    private static AgentRunFinalEligibilityRequirement agentRunEligibility(
            IntakeGraphFinalizationRequest request) {
        IntakeGraphFinalizationRequest.Authority authority = request.authority();
        return new AgentRunFinalEligibilityRequirement(
                authority.caseId(),
                authority.commandId(),
                authority.logicalRunId(),
                authority.attemptId(),
                authority.resultHash(),
                authority.proposalHash(),
                authority.checkpointId(),
                authority.cognitiveRevision(),
                authority.fencingToken());
    }

    private void validateRequest(IntakeGraphFinalizationRequest request) {
        try {
            request.requireCanonicalRequestHash();
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw rejected(
                    "INTAKE_FINALIZATION_REQUEST_HASH_INVALID",
                    "finalization request cannot be canonically hashed",
                    failure);
        }
        IntakeGraphFinalizationRequest.Authority authority = request.authority();
        IntakeGraphThreadBinding binding = request.threadBinding();
        IntakePrivateThreadRegistration registration = binding.registration();

        try {
            registration.requireCanonicalHash();
        } catch (RuntimeException failure) {
            throw rejected(
                    "INTAKE_THREAD_BINDING_HASH_INVALID",
                    "thread registration hash is not canonical",
                    failure);
        }
        if (registration.writerMode() != WriterMode.TEMPORAL) {
            throw rejected(
                    "INTAKE_FORMAL_FINALIZER_UNAVAILABLE",
                    "formal Intake finalization requires a TEMPORAL writer binding");
        }
        if (binding.fencingToken() != authority.fencingToken()) {
            throw rejected("INTAKE_STALE_FENCE", "finalization fence does not match the binding");
        }
        requireEqual(registration.tenantSurrogate(), authority.tenantSurrogate(), "tenant");
        requireEqual(registration.caseId(), authority.caseId(), "case");
        requireEqual(registration.roomEpoch(), authority.roomEpoch(), "room epoch");
        requireEqual(registration.threadId(), authority.threadId(), "thread");
        requireEqual(registration.actorScopeHash(), authority.actorScopeHash(), "actor scope");
        requireEqual(registration.agentSessionId(), authority.agentSessionId(), "agent session");
        requireEqual(registration.graphKey(), expectedGraphKey, "graph key");
        requireEqual(registration.graphVersion(), authority.profileVersions().graphVersion(), "graph version");
        requireEqual(
                registration.checkpointSchemaVersion(),
                authority.profileVersions().checkpointSchemaVersion(),
                "checkpoint schema");
        requireEqual(
                registration.promptVersion(),
                authority.profileVersions().promptVersion(),
                "prompt version");
        requireEqual(
                registration.modelProfileId(),
                authority.profileVersions().modelProfileId(),
                "model profile");
        requireEqual(
                registration.outputSchemaVersion(),
                authority.executionOutputSchemaVersion(),
                "execution output schema");
        requireEqual(
                registration.policyVersion(),
                authority.profileVersions().policyVersion(),
                "policy version");
        requireEqual(
                registration.guardrailVersion(),
                authority.profileVersions().guardrailVersion(),
                "guardrail version");
        requireEqual(
                registration.toolPolicyVersion(),
                authority.profileVersions().toolPolicyVersion(),
                "tool policy version");

        RoomGraphCommand command = request.command();
        if (command.roomType() != com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE) {
            throw rejected("INTAKE_ROOM_TYPE_MISMATCH", "graph command is not an Intake command");
        }
        requireEqual(command.commandId(), authority.commandId(), "command id");
        requireEqual(command.logicalRunId(), authority.logicalRunId(), "logical run id");
        requireEqual(command.attemptId(), authority.attemptId(), "attempt id");
        requireEqual(command.tenantSurrogate(), authority.tenantSurrogate(), "command tenant");
        requireEqual(command.caseId(), authority.caseId(), "command case");
        requireEqual(command.roomEpoch(), authority.roomEpoch(), "command room epoch");
        requireEqual(command.graphKey(), registration.graphKey(), "command graph key");
        requireEqual(command.graphVersion(), registration.graphVersion(), "command graph version");
        requireEqual(
                command.checkpointSchemaVersion(),
                registration.checkpointSchemaVersion(),
                "command checkpoint schema");
        requireEqual(command.threadId(), registration.threadId(), "command thread");
        requireEqual(command.processRevision(), authority.processRevision(), "process revision");
        requireEqual(command.stageCode(), authority.stageCode(), "stage code");
        requireEqual(command.stageSequence(), authority.stageSequence(), "stage sequence");
        if (!new RoomGraphCommand.ActorScope(
                        registration.actorScope().actorId(),
                        registration.actorScope().actorRole(),
                        registration.actorScope().audience(),
                        registration.actorScope().capabilities())
                .equals(command.actorScope())) {
            throw rejected("INTAKE_ACTOR_SCOPE_MISMATCH", "graph command actor scope is not bound");
        }
        RoomGraphCommand.InvocationContext context = command.invocationContext();
        IntakeTurnProposal.ProfileVersions profiles = authority.profileVersions();
        requireEqual(context.promptProfileId(), profiles.promptVersion(), "prompt profile");
        requireEqual(context.modelProfileId(), profiles.modelProfileId(), "model profile");
        requireEqual(
                context.outputSchemaVersion(),
                authority.executionOutputSchemaVersion(),
                "execution output schema");
        requireEqual(context.policyVersion(), profiles.policyVersion(), "policy version");
        requireEqual(context.guardrailVersion(), profiles.guardrailVersion(), "guardrail version");
        if (!context.toolCapabilities().isEmpty()) {
            throw rejected("INTAKE_TOOL_CAPABILITY_FORBIDDEN", "Intake formal proposals cannot execute tools");
        }
        try {
            if (!command.requestHash().equals(IntakeContractHashes.graphCommandHash(command))) {
                throw rejected("INTAKE_COMMAND_HASH_MISMATCH", "graph command request hash is not canonical");
            }
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw rejected("INTAKE_COMMAND_HASH_INVALID", "graph command cannot be hashed", failure);
        }

        IntakeSnapshotReference snapshot = request.initialSnapshot();
        requireEqual(snapshot.tenantSurrogate(), authority.tenantSurrogate(), "snapshot tenant");
        requireEqual(snapshot.caseId(), authority.caseId(), "snapshot case");
        requireEqual(snapshot.roomEpoch(), authority.roomEpoch(), "snapshot room epoch");
        requireEqual(snapshot.fencingToken(), authority.fencingToken(), "snapshot fence");
        requireEqual(snapshot.threadId(), authority.threadId(), "snapshot thread");
        requireEqual(snapshot.actorScopeHash(), authority.actorScopeHash(), "snapshot actor scope");
        requireEqual(snapshot.agentSessionId(), authority.agentSessionId(), "snapshot agent session");
        requireEqual(
                snapshot.threadRegistrationId(),
                registration.registrationId(),
                "snapshot thread registration");
        requireEqual(snapshot.payloadRef(), command.domainSnapshotRef(), "snapshot reference");
        if (request.event() == null) {
            if (command.eventRef() != null) {
                throw rejected("INTAKE_EVENT_SCOPE_MISMATCH", "command carries an unbound event reference");
            }
        } else {
            IntakeEventReference event = request.event();
            requireEqual(event.tenantSurrogate(), authority.tenantSurrogate(), "event tenant");
            requireEqual(event.caseId(), authority.caseId(), "event case");
            requireEqual(event.roomEpoch(), authority.roomEpoch(), "event room epoch");
            requireEqual(event.fencingToken(), authority.fencingToken(), "event fence");
            requireEqual(event.threadId(), authority.threadId(), "event thread");
            requireEqual(event.actorScopeHash(), authority.actorScopeHash(), "event actor scope");
            requireEqual(event.agentSessionId(), authority.agentSessionId(), "event agent session");
            requireEqual(
                    event.threadRegistrationId(),
                    registration.registrationId(),
                    "event thread registration");
            requireEqual(event.audience(), registration.actorScope().audience(), "event audience");
            requireEqual(event.payloadRef(), command.eventRef(), "event reference");
            if (event.domainRevision() < snapshot.domainRevision()) {
                throw rejected("INTAKE_EVENT_REVISION_INVALID", "event revision predates the snapshot");
            }
            if (event.sequenceNo() <= snapshot.initialLastSequence()
                    || event.occurredAt().isBefore(snapshot.createdAt())
                    || event.createdAt().isBefore(event.occurredAt())) {
                throw rejected(
                        "INTAKE_EVENT_ORDER_INVALID",
                        "event sequence or timestamps predate the bound initialization snapshot");
            }
        }

        RoomGraphResult result = request.result();
        requireEqual(result.commandId(), authority.commandId(), "result command id");
        requireEqual(result.logicalRunId(), authority.logicalRunId(), "result logical run id");
        requireEqual(result.attemptId(), authority.attemptId(), "result attempt id");
        requireEqual(result.graphKey(), registration.graphKey(), "result graph key");
        requireEqual(result.graphVersion(), registration.graphVersion(), "result graph version");
        requireEqual(result.checkpointId(), authority.checkpointId(), "checkpoint id");
        requireEqual(result.cognitiveRevision(), authority.cognitiveRevision(), "cognitive revision");
        if (result.status() != GraphStatus.COMPLETED
                || result.needsInput() != null
                || result.needsReview() != null
                || result.error() != null) {
            throw rejected("INTAKE_RESULT_NOT_FORMALIZABLE", "only a completed proposal can be finalized");
        }
        if (!result.publicEventProposals().isEmpty()) {
            throw rejected("INTAKE_PUBLIC_EVENT_FORBIDDEN", "Graph cannot publish formal Intake events");
        }
        if (result.artifactOperations().size() != 1) {
            throw rejected("INTAKE_PROPOSAL_OPERATION_COUNT_INVALID", "exactly one proposal operation is required");
        }
        ArtifactOperation operation = result.artifactOperations().get(0);
        if (operation.operation() != ArtifactOperationType.PROPOSE_PATCH) {
            throw rejected("INTAKE_PROPOSAL_OPERATION_INVALID", "the proposal operation must be PROPOSE_PATCH");
        }
        var pointer = operation.artifact();
        IntakeProposalReference proposal = request.proposalReference();
        requireEqual(pointer.artifactId(), proposal.artifactId(), "proposal artifact id");
        requireEqual(pointer.schemaVersion(), proposal.schemaVersion(), "proposal schema");
        requireEqual(pointer.uri(), proposal.uri(), "proposal URI");
        requireEqual(pointer.sha256(), proposal.sha256(), "proposal hash");
        requireEqual(proposal.sha256(), authority.proposalHash(), "trusted proposal hash");
        try {
            if (!result.outputHash().equals(IntakeContractHashes.graphResultHash(result))) {
                throw rejected("INTAKE_RESULT_HASH_MISMATCH", "graph result output hash is not canonical");
            }
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw rejected("INTAKE_RESULT_HASH_INVALID", "graph result cannot be hashed", failure);
        }
        requireEqual(result.outputHash(), authority.resultHash(), "trusted result hash");
        ExecutionMetadata metadata = result.executionMetadata();
        requireEqual(metadata.promptVersion(), profiles.promptVersion(), "result prompt version");
        requireEqual(metadata.modelProfileId(), profiles.modelProfileId(), "result model profile");
        requireEqual(
                metadata.schemaVersion(),
                authority.executionOutputSchemaVersion(),
                "result execution output schema");
        requireEqual(metadata.policyVersion(), profiles.policyVersion(), "result policy version");
        requireEqual(metadata.guardrailVersion(), profiles.guardrailVersion(), "result guardrail version");
        if (result.usage().inputTokens() < 0
                || result.usage().outputTokens() < 0
                || result.usage().totalTokens() < 0) {
            throw rejected("INTAKE_RESULT_USAGE_INVALID", "graph result usage cannot be negative");
        }

        String expectedOperation;
        try {
            expectedOperation = IntakeFinalizationOperationKey.create(
                    authority.caseId(),
                    authority.roomEpoch(),
                    authority.threadId(),
                    authority.commandId(),
                    authority.resultHash());
        } catch (IllegalArgumentException failure) {
            throw rejected(
                    "INTAKE_OPERATION_KEY_INPUT_INVALID",
                    "finalization identifiers exceed the frozen operation-key bounds",
                    failure);
        }
        requireEqual(request.operationKey(), expectedOperation, "finalization operation key");
    }

    private static void validateReceipt(
            IntakeGraphFinalizationRequest request, IntakeFinalizationReceipt receipt) {
        try {
            receipt.requireCanonicalHash();
        } catch (RuntimeException failure) {
            throw rejected("INTAKE_RECEIPT_HASH_MISMATCH", "formal receipt hash is not canonical", failure);
        }
        IntakeGraphFinalizationRequest.Authority authority = request.authority();
        requireEqual(receipt.operationKey(), request.operationKey(), "receipt operation key");
        requireEqual(receipt.tenantSurrogate(), authority.tenantSurrogate(), "receipt tenant");
        requireEqual(receipt.caseId(), authority.caseId(), "receipt case");
        requireEqual(receipt.roomEpoch(), authority.roomEpoch(), "receipt room epoch");
        requireEqual(receipt.threadId(), authority.threadId(), "receipt thread");
        requireEqual(receipt.actorScopeHash(), authority.actorScopeHash(), "receipt actor scope");
        requireEqual(receipt.agentSessionId(), authority.agentSessionId(), "receipt agent session");
        requireEqual(receipt.commandId(), authority.commandId(), "receipt command id");
        requireEqual(receipt.logicalRunId(), authority.logicalRunId(), "receipt logical run id");
        requireEqual(receipt.attemptId(), authority.attemptId(), "receipt attempt id");
        requireEqual(receipt.resultHash(), authority.resultHash(), "receipt result hash");
        requireEqual(receipt.proposalHash(), authority.proposalHash(), "receipt proposal hash");
        requireEqual(receipt.processRevision(), authority.processRevision(), "receipt process revision");
        requireEqual(receipt.roomRevision(), authority.roomRevision(), "receipt room revision");
        requireEqual(receipt.fencingToken(), authority.fencingToken(), "receipt fence");
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw rejected("INTAKE_AUTHORITY_MISMATCH", field + " does not match trusted authority");
        }
    }

    private static void requireEqual(long actual, long expected, String field) {
        if (actual != expected) {
            throw rejected("INTAKE_AUTHORITY_MISMATCH", field + " does not match trusted authority");
        }
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    private static IntakeFinalizationRejectedException rejected(
            String code, String message, Throwable cause) {
        return new IntakeFinalizationRejectedException(code, message, cause);
    }

    /** Optional two-pass authority boundary implemented by database-backed formal adapters. */
    @FunctionalInterface
    public interface AuthorityPreflight {
        void preflight(IntakeGraphFinalizationRequest request);
    }
}
