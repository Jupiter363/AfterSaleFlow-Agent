package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceiptReader;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeFormalCommitPort;
import com.example.dispute.workflow.application.intake.IntakeGraphFinalizationRequest;
import com.example.dispute.workflow.application.intake.IntakeGraphResultFinalizer;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakeImmutableProposalReader;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.example.dispute.workflow.application.intake.IntakeTurnProposalLoader;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IntakeGraphResultFinalizerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path PROPOSAL_FIXTURE = Path.of(
            "..",
            "contracts",
            "agent-platform",
            "intake",
            "v2",
            "fixtures",
            "valid",
            "intake-turn-proposal-valid.json");

    @Test
    void exactReplayReturnsTheSameReceiptAndOnlyUsesTheFormalPort() throws Exception {
        Fixture fixture = fixture(WriterMode.TEMPORAL);
        RecordingCommitPort port = new RecordingCommitPort();
        IntakeGraphResultFinalizer finalizer = finalizer(fixture, port);

        IntakeFinalizationReceipt first = finalizer.finalizeResult(fixture.request());
        IntakeFinalizationReceipt replay = finalizer.finalizeResult(fixture.request());

        assertThat(replay).isEqualTo(first);
        assertThat(port.calls).isEqualTo(2);
        assertThat(port.commands).allMatch(command ->
                command.request().operationKey().equals(fixture.request().operationKey()));
        assertThat(port.commands.get(0).currentAuthority().actorId())
                .isEqualTo("user-synthetic");
        assertThat(port.commands.get(0).agentRunEligibility().attemptId())
                .isEqualTo("ATTEMPT_P4_USER_2_1");
        assertThat(port.commands.get(0).agentRunEligibility().resultHash())
                .isEqualTo(fixture.authority().resultHash());
    }

    @Test
    void committedReceiptBypassesProposalReloadAfterActivityCompletionLoss() throws Exception {
        Fixture fixture = fixture(WriterMode.TEMPORAL);
        RecordingCommitPort committedPort = new RecordingCommitPort();
        IntakeFinalizationReceipt committed =
                finalizer(fixture, committedPort).finalizeResult(fixture.request());
        RecordingCommitPort replayPort = new RecordingCommitPort();
        IntakeImmutableProposalReader unavailableReader = ignored -> {
            throw new AssertionError("a committed finalization must not reload the proposal");
        };
        IntakeFinalizationReceiptReader receiptReader =
                (tenantSurrogate, operationKey, requestHash) -> Optional.of(committed);
        IntakeGraphResultFinalizer finalizer = new IntakeGraphResultFinalizer(
                new IntakeTurnProposalLoader(unavailableReader), replayPort, receiptReader);

        IntakeFinalizationReceipt replay = finalizer.finalizeResult(fixture.request());

        assertThat(replay).isEqualTo(committed);
        assertThat(replayPort.calls).isZero();
    }

    @Test
    void invalidGraphHashNeverCallsTheFormalPort() throws Exception {
        Fixture fixture = fixture(WriterMode.TEMPORAL);
        RoomGraphResult invalid = copyResult(fixture.result(), "0".repeat(64));
        RecordingCommitPort port = new RecordingCommitPort();

        assertRejected(
                "INTAKE_RESULT_HASH_MISMATCH",
                () -> finalizer(fixture.withResult(invalid), port)
                        .finalizeResult(fixture.withResult(invalid).request()));
        assertThat(port.calls).isZero();
    }

    @Test
    void nonCanonicalFinalizationRequestHashNeverReadsTheProposalOrCallsTheFormalPort()
            throws Exception {
        Fixture fixture = fixture(WriterMode.TEMPORAL);
        String incorrectHash = fixture.request().requestHash().startsWith("0")
                ? "1" + fixture.request().requestHash().substring(1)
                : "0" + fixture.request().requestHash().substring(1);
        IntakeGraphFinalizationRequest invalid = new IntakeGraphFinalizationRequest(
                fixture.request().operationKey(),
                incorrectHash,
                fixture.authority(),
                fixture.command(),
                fixture.result(),
                fixture.binding(),
                fixture.snapshot(),
                fixture.event(),
                fixture.proposalReference());
        RecordingCommitPort port = new RecordingCommitPort();
        IntakeImmutableProposalReader unavailableReader = ignored -> {
            throw new AssertionError("a malformed request must not read the proposal");
        };
        IntakeGraphResultFinalizer finalizer = new IntakeGraphResultFinalizer(
                new IntakeTurnProposalLoader(unavailableReader), port);

        assertRejected(
                "INTAKE_FINALIZATION_REQUEST_HASH_MISMATCH",
                () -> finalizer.finalizeResult(invalid));
        assertThat(port.calls).isZero();
    }

    @Test
    void staleFenceNeverCallsTheFormalPort() throws Exception {
        Fixture fixture = fixture(WriterMode.TEMPORAL);
        IntakeGraphFinalizationRequest.Authority authority = fixture.authority();
        IntakeGraphFinalizationRequest stale = fixture.requestWithAuthority(
                new IntakeGraphFinalizationRequest.Authority(
                        authority.tenantSurrogate(),
                        authority.caseId(),
                        authority.roomEpoch(),
                        authority.fencingToken() + 1,
                        authority.threadId(),
                        authority.actorScopeHash(),
                        authority.agentSessionId(),
                        authority.commandId(),
                        authority.logicalRunId(),
                        authority.attemptId(),
                        authority.resultHash(),
                        authority.proposalHash(),
                        authority.checkpointId(),
                        authority.cognitiveRevision(),
                        authority.processRevision(),
                        authority.roomRevision(),
                        authority.stageCode(),
                        authority.stageSequence(),
                        authority.profileVersions()));
        RecordingCommitPort port = new RecordingCommitPort();

        assertRejected("INTAKE_STALE_FENCE", () -> finalizer(fixture.withRequest(stale), port)
                .finalizeResult(stale));
        assertThat(port.calls).isZero();
    }

    @Test
    void crossScopeAuthorityNeverCallsTheFormalPort() throws Exception {
        Fixture fixture = fixture(WriterMode.TEMPORAL);
        IntakeGraphFinalizationRequest.Authority authority = fixture.authority();
        IntakeGraphFinalizationRequest crossScope = fixture.requestWithAuthority(
                new IntakeGraphFinalizationRequest.Authority(
                        authority.tenantSurrogate(),
                        authority.caseId(),
                        authority.roomEpoch(),
                        authority.fencingToken(),
                        authority.threadId(),
                        "a".repeat(64),
                        authority.agentSessionId(),
                        authority.commandId(),
                        authority.logicalRunId(),
                        authority.attemptId(),
                        authority.resultHash(),
                        authority.proposalHash(),
                        authority.checkpointId(),
                        authority.cognitiveRevision(),
                        authority.processRevision(),
                        authority.roomRevision(),
                        authority.stageCode(),
                        authority.stageSequence(),
                        authority.profileVersions()));
        RecordingCommitPort port = new RecordingCommitPort();

        assertRejected("INTAKE_AUTHORITY_MISMATCH", () -> finalizer(fixture.withRequest(crossScope), port)
                .finalizeResult(crossScope));
        assertThat(port.calls).isZero();
    }

    @Test
    void shadowBindingCannotResolveTheFormalPort() throws Exception {
        Fixture fixture = fixture(WriterMode.SHADOW);
        RecordingCommitPort port = new RecordingCommitPort();

        assertRejected("INTAKE_FORMAL_FINALIZER_UNAVAILABLE", () -> finalizer(fixture, port)
                .finalizeResult(fixture.request()));
        assertThat(port.calls).isZero();
    }

    private static IntakeGraphResultFinalizer finalizer(Fixture fixture, RecordingCommitPort port) {
        IntakeImmutableProposalReader reader = ignored -> fixture.storedProposal();
        return new IntakeGraphResultFinalizer(new IntakeTurnProposalLoader(reader), port);
    }

    private static Fixture fixture(WriterMode writerMode) throws Exception {
        JsonNode document = MAPPER.readTree(PROPOSAL_FIXTURE.toFile());
        byte[] payload = ContractJson.canonicalize(document);
        IntakeProposalReference proposalReference = new IntakeProposalReference(
                "PROPOSAL_P4_USER_2",
                "intake-turn-proposal.v2",
                "urn:intake:proposal:PROPOSAL_P4_USER_2",
                "version-1",
                document.required("proposal_hash").asText(),
                payload.length);
        IntakeGraphThreadBinding binding = binding(writerMode);
        IntakeSnapshotReference snapshot = IntakeTestFixtures.snapshot(binding);
        IntakeEventReference event = IntakeTestFixtures.event(binding);
        RoomGraphCommand command = new com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory()
                .create(new com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory.CommandRequest(
                        "COMMAND_P4_USER_2",
                        "RUN_P4_USER_2",
                        "ATTEMPT_P4_USER_2_1",
                        binding,
                        snapshot,
                        event,
                        5,
                        "INTAKE_ACTIVE",
                        2,
                        "intake-agent.v2",
                        2,
                        3,
                        1,
                        Instant.parse("2026-07-20T08:03:00Z"),
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                        "graph-envelope.synthetic.v1",
                        "nonce-p4-user-2"));
        RoomGraphResult result = result(command, proposalReference, document);
        IntakeTurnProposal.ProfileVersions profiles = profiles(document);
        IntakeGraphFinalizationRequest.Authority authority = new IntakeGraphFinalizationRequest.Authority(
                command.tenantSurrogate(),
                command.caseId(),
                command.roomEpoch(),
                binding.fencingToken(),
                command.threadId(),
                command.actorScope().audience() == Audience.USER
                        ? binding.registration().actorScopeHash()
                        : binding.registration().actorScopeHash(),
                binding.registration().agentSessionId(),
                command.commandId(),
                command.logicalRunId(),
                command.attemptId(),
                result.outputHash(),
                proposalReference.sha256(),
                result.checkpointId(),
                2,
                command.processRevision(),
                3,
                command.stageCode(),
                command.stageSequence(),
                profiles);
        String operationKey = "intake.turn.finalize:"
                + authority.caseId() + ":" + authority.roomEpoch() + ":"
                + authority.threadId() + ":" + authority.commandId() + ":"
                + authority.resultHash();
        IntakeGraphFinalizationRequest request = canonicalRequest(
                operationKey,
                authority,
                command,
                result,
                binding,
                snapshot,
                event,
                proposalReference);
        return new Fixture(
                request,
                authority,
                command,
                result,
                binding,
                snapshot,
                event,
                proposalReference,
                new IntakeImmutableProposalReader.StoredProposal(
                        proposalReference.artifactId(),
                        proposalReference.schemaVersion(),
                        proposalReference.uri(),
                        proposalReference.objectVersion(),
                        proposalReference.sha256(),
                        proposalReference.sizeBytes(),
                        payload));
    }

    private static IntakeGraphThreadBinding binding(WriterMode writerMode) {
        var actor = new IntakePrivateThreadRegistration.ActorScope(
                "user-synthetic",
                ActorRole.USER,
                Audience.USER,
                List.of("graph.command.execute"));
        return new IntakePrivateThreadRegistrationFactory(() -> IntakeTestFixtures.THREAD_ID)
                .issue(new IntakePrivateThreadRegistrationFactory.IssueRequest(
                        "REG_P4_INTAKE_USER_1",
                        "tenant-synthetic",
                        "CASE_P4_SYNTHETIC_1",
                        1,
                        2,
                        actor,
                        "AGENT_SESSION_P4_USER_1",
                        new IntakePrivateThreadRegistrationFactory.VersionPins(
                                "2.0.0",
                                "intake-checkpoint.v2",
                                "intake-prompt.v2",
                                "intake-model.synthetic.v1",
                                "intake-policy.v2",
                                "intake-guardrail.v2",
                                "no-tools.v1"),
                        writerMode,
                        IntakeTestFixtures.ISSUED_AT));
    }

    private static RoomGraphResult result(
            RoomGraphCommand command, IntakeProposalReference proposal, JsonNode document) {
        RoomGraphResult unsigned = new RoomGraphResult(
                "room-graph-result.v1",
                command.commandId(),
                command.logicalRunId(),
                command.attemptId(),
                command.graphKey(),
                command.graphVersion(),
                "CHECKPOINT_P4_USER_2",
                2,
                GraphStatus.COMPLETED,
                List.of(),
                List.of(new RoomGraphResult.ArtifactOperation(
                        ArtifactOperationType.PROPOSE_PATCH,
                        new com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer(
                                "PROPOSAL_P4_USER_2",
                                proposal.schemaVersion(),
                                proposal.uri(),
                                proposal.sha256()))),
                null,
                null,
                null,
                "0".repeat(64),
                new Usage(10, 5, 15),
                new RoomGraphResult.ExecutionMetadata(
                        "intake-prompt.v2",
                        "intake-model.synthetic.v1",
                        "intake-turn-proposal.v2",
                        "intake-policy.v2",
                        "intake-guardrail.v2"));
        return new RoomGraphResult(
                unsigned.schemaVersion(),
                unsigned.commandId(),
                unsigned.logicalRunId(),
                unsigned.attemptId(),
                unsigned.graphKey(),
                unsigned.graphVersion(),
                unsigned.checkpointId(),
                unsigned.cognitiveRevision(),
                unsigned.status(),
                unsigned.publicEventProposals(),
                unsigned.artifactOperations(),
                unsigned.needsInput(),
                unsigned.needsReview(),
                unsigned.error(),
                IntakeContractHashes.graphResultHash(unsigned),
                unsigned.usage(),
                unsigned.executionMetadata());
    }

    private static IntakeTurnProposal.ProfileVersions profiles(JsonNode document) {
        JsonNode p = document.required("profile_versions");
        return new IntakeTurnProposal.ProfileVersions(
                p.required("graph_version").asText(),
                p.required("checkpoint_schema_version").asText(),
                p.required("prompt_version").asText(),
                p.required("model_profile_id").asText(),
                p.required("output_schema_version").asText(),
                p.required("policy_version").asText(),
                p.required("guardrail_version").asText(),
                p.required("tool_policy_version").asText());
    }

    private static RoomGraphResult copyResult(RoomGraphResult source, String outputHash) {
        return new RoomGraphResult(
                source.schemaVersion(),
                source.commandId(),
                source.logicalRunId(),
                source.attemptId(),
                source.graphKey(),
                source.graphVersion(),
                source.checkpointId(),
                source.cognitiveRevision(),
                source.status(),
                source.publicEventProposals(),
                source.artifactOperations(),
                source.needsInput(),
                source.needsReview(),
                source.error(),
                outputHash,
                source.usage(),
                source.executionMetadata());
    }

    private static IntakeGraphFinalizationRequest canonicalRequest(
            String operationKey,
            IntakeGraphFinalizationRequest.Authority authority,
            RoomGraphCommand command,
            RoomGraphResult result,
            IntakeGraphThreadBinding binding,
            IntakeSnapshotReference snapshot,
            IntakeEventReference event,
            IntakeProposalReference proposalReference) {
        IntakeGraphFinalizationRequest unsigned = new IntakeGraphFinalizationRequest(
                operationKey,
                "0".repeat(64),
                authority,
                command,
                result,
                binding,
                snapshot,
                event,
                proposalReference);
        return new IntakeGraphFinalizationRequest(
                operationKey,
                unsigned.canonicalRequestHash(),
                authority,
                command,
                result,
                binding,
                snapshot,
                event,
                proposalReference);
    }

    private static void assertRejected(String code, ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo(code));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private record Fixture(
            IntakeGraphFinalizationRequest request,
            IntakeGraphFinalizationRequest.Authority authority,
            RoomGraphCommand command,
            RoomGraphResult result,
            IntakeGraphThreadBinding binding,
            IntakeSnapshotReference snapshot,
            IntakeEventReference event,
            IntakeProposalReference proposalReference,
            IntakeImmutableProposalReader.StoredProposal storedProposal) {

        private Fixture withResult(RoomGraphResult value) {
            IntakeGraphFinalizationRequest.Authority next = new IntakeGraphFinalizationRequest.Authority(
                    authority.tenantSurrogate(), authority.caseId(), authority.roomEpoch(),
                    authority.fencingToken(), authority.threadId(), authority.actorScopeHash(),
                    authority.agentSessionId(), authority.commandId(), authority.logicalRunId(),
                    authority.attemptId(), value.outputHash(), authority.proposalHash(),
                    authority.checkpointId(), authority.cognitiveRevision(), authority.processRevision(),
                    authority.roomRevision(), authority.stageCode(), authority.stageSequence(),
                    authority.profileVersions());
            String key = "intake.turn.finalize:" + next.caseId() + ":" + next.roomEpoch() + ":"
                    + next.threadId() + ":" + next.commandId() + ":" + next.resultHash();
            IntakeGraphFinalizationRequest nextRequest = canonicalRequest(
                    key, next, command, value, binding, snapshot, event,
                    proposalReference);
            return new Fixture(nextRequest, next, command, value, binding, snapshot, event,
                    proposalReference, storedProposal);
        }

        private Fixture withRequest(IntakeGraphFinalizationRequest value) {
            return new Fixture(value, value.authority(), command, result, binding, snapshot, event,
                    proposalReference, storedProposal);
        }

        private IntakeGraphFinalizationRequest requestWithAuthority(
                IntakeGraphFinalizationRequest.Authority value) {
            String key = "intake.turn.finalize:" + value.caseId() + ":" + value.roomEpoch() + ":"
                    + value.threadId() + ":" + value.commandId() + ":" + value.resultHash();
            return canonicalRequest(
                    key, value, command, result, binding, snapshot, event,
                    proposalReference);
        }
    }

    private static final class RecordingCommitPort implements IntakeFormalCommitPort {
        private int calls;
        private final java.util.ArrayList<CommitCommand> commands = new java.util.ArrayList<>();
        private IntakeFinalizationReceipt receipt;

        @Override
        public IntakeFinalizationReceipt commit(CommitCommand command) {
            calls++;
            commands.add(command);
            if (receipt == null) {
                var request = command.request();
                var authority = request.authority();
                receipt = IntakeFinalizationReceipt.committed(
                        new IntakeFinalizationReceipt.CommitFacts(
                                request.operationKey(),
                                authority.tenantSurrogate(),
                                authority.caseId(),
                                authority.roomEpoch(),
                                authority.threadId(),
                                authority.actorScopeHash(),
                                authority.agentSessionId(),
                                authority.commandId(),
                                authority.logicalRunId(),
                                authority.attemptId(),
                                authority.resultHash(),
                                authority.proposalHash(),
                                authority.processRevision(),
                                authority.roomRevision(),
                                authority.fencingToken(),
                                "MESSAGE_AGENT_P4_2",
                                2L,
                                null,
                                List.of("EVENT_INTAKE_DOSSIER_P4_2"),
                                List.of("OUTBOX_P4_2"),
                                Instant.parse("2026-07-20T08:03:00Z")));
            }
            return receipt;
        }
    }
}
