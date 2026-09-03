package com.example.dispute.workflow.caseprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildKind;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ExpiredTargetEvidenceTerminalRecoveryCommitment;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.RecoveryErrorOrigin;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.UnreconciledChildExecution;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessExpiredTargetEvidenceTerminalRecoveryResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessExpiredTargetEvidenceTerminalRecoveryResult.Disposition;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.ProcessedCommandIdentity;
import com.example.dispute.workflow.temporal.caseprocess.ProvisioningCommitment;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunTerminalNoCommit;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaseProcessCarryStateCompatibilityTest {

  private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  void readsLegacyCarryWithoutDescriptorAndNeverInfersTypedKind() throws Exception {
    String legacyJson =
        """
        {
          "schemaVersion": "case-process-carry-state.v1",
          "tenantSurrogate": "tenant-legacy",
          "caseId": "CASE_Legacy",
          "activeRoomType": "INTAKE",
          "activeRoomEpoch": 3,
          "activeChildWorkflowId": "room:CASE_Legacy:intake:3",
          "observedProcessRevision": 7,
          "nextCommandSequence": 4,
          "nextCaseEventSequence": 5,
          "processedCommandCount": 3,
          "processedEventCount": 4,
          "recentCommands": [],
          "bufferedEvents": [],
          "highestObservedEventSequence": 4,
          "runGeneration": 1,
          "commandRecoveryAttempts": 0,
          "eventRecoveryAttempts": 0,
          "commandManualRecoveryRequired": false,
          "eventManualRecoveryRequired": false,
          "protocolErrorCode": null,
          "closedRooms": [],
          "activeFencingToken": 0,
          "activeChildWorkflowRunId": "legacy-run",
          "provisioningCommitments": [],
          "highestProvisionedEpochs": []
        }
        """;

    CaseProcessCarryState carry = mapper.readValue(legacyJson, CaseProcessCarryState.class);

    assertThat(carry.activeChildDescriptor()).isNull();
    assertThat(carry.activeChildWorkflowId()).isEqualTo("room:CASE_Legacy:intake:3");
    assertThat(carry.activeRoomRevision()).isNull();
    assertThat(carry.protocolErrorOrigin()).isNull();
    assertThat(carry.provisioningManualRecoveryRequired()).isFalse();
    assertThat(carry.unreconciledChildren()).isEmpty();
    assertThat(carry.expiredTargetEvidenceTerminalRecoveryCommitments()).isEmpty();
  }

  @Test
  void readsLegacyCarryWithProtocolErrorAndMissingOrigin() throws Exception {
    String legacyJson =
        """
        {
          "schemaVersion": "case-process-carry-state.v1",
          "tenantSurrogate": null,
          "caseId": null,
          "activeRoomType": null,
          "activeRoomEpoch": -1,
          "activeChildWorkflowId": null,
          "observedProcessRevision": 0,
          "nextCommandSequence": 1,
          "nextCaseEventSequence": 1,
          "processedCommandCount": 0,
          "processedEventCount": 0,
          "recentCommands": [],
          "bufferedEvents": [],
          "highestObservedEventSequence": 0,
          "runGeneration": 1,
          "commandRecoveryAttempts": 0,
          "eventRecoveryAttempts": 0,
          "commandManualRecoveryRequired": false,
          "eventManualRecoveryRequired": false,
          "protocolErrorCode": "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
          "closedRooms": [],
          "activeFencingToken": 0,
          "activeChildWorkflowRunId": null,
          "provisioningCommitments": [],
          "highestProvisionedEpochs": []
        }
        """;

    CaseProcessCarryState carry = mapper.readValue(legacyJson, CaseProcessCarryState.class);

    assertThat(carry.protocolErrorCode()).isEqualTo("INTAKE_CHILD_ACTIVE_BINDING_INVALID");
    assertThat(carry.protocolErrorOrigin()).isNull();
    assertThat(carry.provisioningManualRecoveryRequired()).isFalse();
    assertThat(carry.expiredTargetEvidenceTerminalRecoveryCommitments()).isEmpty();
  }

  @Test
  void provisioningManualRecoveryOutcomeSurvivesCarryRoundTrip() throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode node =
        mapper.valueToTree(CaseProcessCarryState.initial());
    List<UnreconciledChildExecution> unresolved =
        List.of(
            new UnreconciledChildExecution("orphan-child-1", "orphan-run-1"),
            new UnreconciledChildExecution("orphan-child-2", "orphan-run-2"));
    node.put("provisioningManualRecoveryRequired", true);
    node.set("unreconciledChildren", mapper.valueToTree(unresolved));

    CaseProcessCarryState restored =
        mapper.readValue(mapper.writeValueAsBytes(node), CaseProcessCarryState.class);

    assertThat(restored.provisioningManualRecoveryRequired()).isTrue();
    assertThat(restored.unreconciledChildren()).containsExactlyElementsOf(unresolved);
    CaseProcessCarryState roundTripped =
        mapper.readValue(mapper.writeValueAsBytes(restored), CaseProcessCarryState.class);
    assertThat(roundTripped.provisioningManualRecoveryRequired()).isTrue();
    assertThat(roundTripped.unreconciledChildren()).containsExactlyElementsOf(unresolved);
  }

  @Test
  void readsPriorManualRecoveryFlagWithoutUnreconciledIdentityList() throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode node =
        mapper.valueToTree(CaseProcessCarryState.initial());
    node.put("provisioningManualRecoveryRequired", true);
    node.remove("unreconciledChildren");

    CaseProcessCarryState restored =
        mapper.readValue(mapper.writeValueAsBytes(node), CaseProcessCarryState.class);

    assertThat(restored.provisioningManualRecoveryRequired()).isTrue();
    assertThat(restored.unreconciledChildren()).isEmpty();
  }

  @Test
  void explicitGenericDescriptorRoundTripsWithoutChangingTheV1SchemaKey() throws Exception {
    ActiveChildDescriptor descriptor =
        new ActiveChildDescriptor(
            ActiveChildKind.GENERIC_ROOM_CONTROL,
            "room-epoch-selection.v2",
            WriterMode.SHADOW,
            CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
            "case-control.synthetic.v1",
            "IntakeRoomWorkflow",
            "intake-room.synthetic.v1",
            RoomType.INTAKE,
            3,
            7,
            "room:CASE_Legacy:intake:3",
            "legacy-run");
    CaseProcessCarryState carry =
        new CaseProcessCarryState(
            "case-process-carry-state.v1",
            "tenant-legacy",
            "CASE_Legacy",
            RoomType.INTAKE,
            3,
            descriptor.workflowId(),
            7,
            4,
            5,
            3,
            4,
            java.util.List.of(),
            java.util.List.of(),
            4,
            1,
            0,
            0,
            false,
            false,
            null,
            java.util.List.of(),
            7,
            descriptor.startedRunId(),
            java.util.List.of(),
            java.util.List.of(),
            descriptor);

    CaseProcessCarryState restored =
        mapper.readValue(mapper.writeValueAsBytes(carry), CaseProcessCarryState.class);

    assertThat(restored.schemaVersion()).isEqualTo("case-process-carry-state.v1");
    assertThat(restored.activeChildDescriptor()).isEqualTo(descriptor);
    assertThat(restored.activeChildDescriptor().kind())
        .isEqualTo(ActiveChildKind.GENERIC_ROOM_CONTROL);
    assertThat(restored.expiredTargetEvidenceTerminalRecoveryCommitments()).isEmpty();
  }

  @Test
  void expiredEvidenceRecoveryCommitmentRoundTripsAndMalformedCarryFailsClosed()
      throws Exception {
    ExpiredTargetEvidenceTerminalRecoveryCommitment commitment = recoveryCommitment(1);
    ProcessedCommandIdentity identity = commitment.result().commandIdentity();
    CaseProcessCarryState carry = recoveryCarry(List.of(identity), List.of(commitment));

    CaseProcessCarryState restored =
        mapper.readValue(mapper.writeValueAsBytes(carry), CaseProcessCarryState.class);

    assertThat(restored.expiredTargetEvidenceTerminalRecoveryCommitments())
        .containsExactly(commitment);
    assertThat(restored.expiredTargetEvidenceTerminalRecoveryCommitments().getFirst().result())
        .isEqualTo(commitment.result());

    assertThatThrownBy(
            () ->
                new ExpiredTargetEvidenceTerminalRecoveryCommitment(
                    ExpiredTargetEvidenceTerminalRecoveryCommitment.SCHEMA_VERSION,
                    commitment.recoveryId(),
                    commitment.requestSha256(),
                    commitment.resultSha256(),
                    null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new ExpiredTargetEvidenceTerminalRecoveryCommitment(
                    ExpiredTargetEvidenceTerminalRecoveryCommitment.SCHEMA_VERSION,
                    commitment.recoveryId(),
                    "9".repeat(64),
                    commitment.resultSha256(),
                    commitment.result()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hashes");
    assertThatThrownBy(
            () ->
                recoveryCarry(
                    List.of(identity),
                    List.of(commitment, commitment)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commitments");
    assertThatThrownBy(() -> recoveryCarry(List.of(), List.of(commitment)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recent command authority");

    List<ExpiredTargetEvidenceTerminalRecoveryCommitment> oversized = new ArrayList<>();
    List<ProcessedCommandIdentity> recent = new ArrayList<>();
    for (int sequence = 1;
        sequence <=
            CaseProcessCarryState.MAX_EXPIRED_TARGET_EVIDENCE_TERMINAL_RECOVERY_COMMITMENTS + 1;
        sequence++) {
      ExpiredTargetEvidenceTerminalRecoveryCommitment candidate = recoveryCommitment(sequence);
      oversized.add(candidate);
      recent.add(candidate.result().commandIdentity());
    }
    assertThatThrownBy(() -> recoveryCarry(recent, oversized))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commitments");
  }

  @Test
  void readsOriginalSnapshotWithoutNewObservableChildFields() throws Exception {
    String legacyJson =
        """
        {
          "schemaVersion": "case-process-snapshot.v1",
          "workflowId": "case:tenant-legacy:CASE_Legacy",
          "workflowRunId": "run-legacy",
          "tenantSurrogate": "tenant-legacy",
          "caseId": "CASE_Legacy",
          "macroPhase": "CONTROL_PLANE_SHADOW",
          "activeRoomType": "INTAKE",
          "activeRoomEpoch": 3,
          "activeChildWorkflowId": "room:CASE_Legacy:intake:3",
          "observedProcessRevision": 7,
          "nextCommandSequence": 4,
          "nextCaseEventSequence": 5,
          "processedCommandCount": 3,
          "processedEventCount": 4,
          "pendingCommandCount": 0,
          "bufferedEventCount": 0,
          "recentCommandCount": 0,
          "highestObservedCommandSequence": 3,
          "highestObservedEventSequence": 4,
          "runGeneration": 1,
          "blockedReason": "NONE",
          "protocolErrorCode": null,
          "recentCommandIds": [],
          "activeFencingToken": 7,
          "activeChildWorkflowRunId": "legacy-run",
          "provisioningCommitmentCount": 1,
          "activeProvisioningSha256": null
        }
        """;

    CaseProcessSnapshot snapshot = mapper.readValue(legacyJson, CaseProcessSnapshot.class);

    assertThat(snapshot.activeChildKind()).isNull();
    assertThat(snapshot.activeSelectionSchemaVersion()).isNull();
    assertThat(snapshot.activeRoomWorkflowType()).isNull();
    assertThat(snapshot.activeRoomWorkflowBuildId()).isNull();
    assertThat(snapshot.activeRoomRevision()).isNull();
    assertThat(snapshot.protocolErrorOrigin()).isNull();
    assertThat(snapshot.provisioningManualRecoveryRequired()).isFalse();
    assertThat(snapshot.unreconciledChildren()).isEmpty();
  }

  @Test
  void typedDescriptorMustMatchCurrentCommitmentAndReceiptRunPin() {
    ProvisionRoomEpoch request = typedProvision();
    ProvisionRoomEpochReceipt receipt = receipt(request, "receipt-run");
    ProvisioningCommitment commitment =
        new ProvisioningCommitment(
            request.updateId(), request.payloadSha256(), request, receipt);
    ActiveChildDescriptor mismatched = descriptor(request, "descriptor-run");

    assertThatThrownBy(
            () ->
                carry(request, mismatched, List.of(commitment), request.initialRoomRevision()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provisioning commitment");

    ActiveChildDescriptor matching = descriptor(request, receipt.roomWorkflowRunId());
    CaseProcessCarryState carry =
        carry(request, matching, List.of(commitment), request.initialRoomRevision() + 2);
    assertThat(carry.activeChildDescriptor()).isEqualTo(matching);
    assertThat(carry.activeRoomRevision()).isEqualTo(request.initialRoomRevision() + 2);
  }

  private static CaseProcessCarryState carry(
      ProvisionRoomEpoch request,
      ActiveChildDescriptor descriptor,
      List<ProvisioningCommitment> commitments,
      long roomRevision) {
    return new CaseProcessCarryState(
        "case-process-carry-state.v1",
        request.tenantSurrogate(),
        request.caseId(),
        request.roomType(),
        request.roomEpoch(),
        request.roomWorkflowId(),
        request.initialProcessRevision(),
        request.firstCommandSequence(),
        request.firstCaseEventSequence(),
        0,
        0,
        List.of(),
        List.of(),
        request.lastCaseEventSequence(),
        1,
        0,
        0,
        false,
        false,
        null,
        List.of(),
        request.fencingToken(),
        descriptor.startedRunId(),
        commitments,
        List.of(new CaseProcessCarryState.ProvisionedRoomEpochHighWater(
            request.roomType(), request.roomEpoch())),
        descriptor,
        roomRevision,
        null);
  }

  private static CaseProcessCarryState recoveryCarry(
      List<ProcessedCommandIdentity> recentCommands,
      List<ExpiredTargetEvidenceTerminalRecoveryCommitment> commitments) {
    long nextCommandSequence =
        commitments.stream()
            .map(ExpiredTargetEvidenceTerminalRecoveryCommitment::result)
            .mapToLong(CaseProcessExpiredTargetEvidenceTerminalRecoveryResult::nextCommandSequence)
            .max()
            .orElse(1L);
    return new CaseProcessCarryState(
        "case-process-carry-state.v1",
        "tenant-recovery",
        "CASE_Recovery",
        null,
        -1,
        null,
        0,
        nextCommandSequence,
        1,
        Math.max(0, nextCommandSequence - 1),
        0,
        recentCommands,
        List.of(),
        0,
        1,
        0,
        0,
        false,
        false,
        null,
        List.of(),
        0,
        null,
        List.of(),
        List.of(),
        null,
        null,
        null,
        false,
        List.of(),
        null,
        commitments);
  }

  private static ExpiredTargetEvidenceTerminalRecoveryCommitment recoveryCommitment(
      int sequence) {
    Instant occurredAt = Instant.parse("2026-08-11T16:00:00Z").plusSeconds(sequence);
    String hashCharacter = Integer.toHexString(sequence % 16);
    String requestHash = hashCharacter.repeat(64);
    CaseCommandRef command =
        new CaseCommandRef(
            "case-command-ref.v1",
            "expired-evidence-command-" + sequence,
            "tenant-recovery",
            "CASE_Recovery",
            sequence,
            CommandType.EVIDENCE_OPENING,
            RoomType.EVIDENCE,
            0,
            new ActorRef("user-recovery", ActorRole.USER, List.of("evidence:opening")),
            new PayloadRef(
                "target-e2e-evidence-opening.v1",
                "urn:test:expired-evidence:" + sequence,
                requestHash,
                32),
            0,
            occurredAt,
            occurredAt.plusSeconds(60),
            "00-11111111111111111111111111111111-2222222222222222-01",
            requestHash);
    ProcessedCommandIdentity identity =
        new ProcessedCommandIdentity(
            command.commandId(), command.caseCommandSequence(), command.requestHash());
    Instant actualExpiredAt = command.deadlineAt().plusSeconds(1);
    String workflowId =
        CaseProcessWorkflowProtocol.caseWorkflowId(
            command.tenantSurrogate(), command.caseId());
    String firstExecutionRunId = "case-run-recovery";
    CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest request =
        new CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest(
            CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest.SCHEMA_VERSION,
            CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest.recoveryId(
                workflowId, firstExecutionRunId, identity, actualExpiredAt),
            workflowId,
            firstExecutionRunId,
            command.tenantSurrogate(),
            command.caseId(),
            sequence + 1L,
            sequence,
            1,
            0,
            0,
            0,
            "TARGET_TYPED_ROOM_COMMAND_DISPATCH_FAILED",
            RecoveryErrorOrigin.COMMAND,
            actualExpiredAt,
            identity);
    TargetRoomAgentRunTerminalNoCommit authority = terminalAuthority(command);
    CaseProcessExpiredTargetEvidenceTerminalRecoveryResult result =
        new CaseProcessExpiredTargetEvidenceTerminalRecoveryResult(
            CaseProcessExpiredTargetEvidenceTerminalRecoveryResult.SCHEMA_VERSION,
            Disposition.RECOVERED,
            request,
            request.requestSha256(),
            authority,
            CaseCommandLedgerState.FAILED,
            authority.receiptUri(),
            authority.receiptSha256(),
            0,
            0,
            sequence,
            0,
            sequence + 1L,
            sequence,
            1,
            0,
            request.expectedProtocolErrorCode(),
            RecoveryErrorOrigin.COMMAND);
    return new ExpiredTargetEvidenceTerminalRecoveryCommitment(
        ExpiredTargetEvidenceTerminalRecoveryCommitment.SCHEMA_VERSION,
        request.recoveryId(),
        request.requestSha256(),
        result.resultSha256(),
        result);
  }

  private static TargetRoomAgentRunTerminalNoCommit terminalAuthority(CaseCommandRef command) {
    String logicalRunId = "target-evidence-run:" + command.commandId();
    RoomGraphCommand graph =
        new RoomGraphCommand(
            "room-graph-command.v1",
            command.commandId(),
            logicalRunId,
            logicalRunId + ":1",
            command.tenantSurrogate(),
            command.caseId(),
            RoomType.EVIDENCE,
            command.roomEpoch(),
            TargetTypedRoomProtocol.GRAPH_KEY,
            TargetTypedRoomProtocol.GRAPH_VERSION,
            TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
            "grt.v1.carry-compatibility-test",
            new RoomGraphCommand.ActorScope(
                command.actorRef().actorId(),
                command.actorRef().actorRole(),
                Audience.USER,
                command.actorRef().actorScopes()),
            command.expectedProcessRevision(),
            "EVIDENCE_SEAL",
            command.expectedProcessRevision(),
            new RoomGraphCommand.SnapshotRef(
                "evidence-invocation:" + command.caseCommandSequence(),
                command.payloadRef().schemaVersion(),
                command.payloadRef().uri(),
                command.payloadRef().sha256(),
                command.payloadRef().sizeBytes()),
            new RoomGraphCommand.SnapshotRef(
                "case-command:" + command.commandId(),
                command.payloadRef().schemaVersion(),
                command.payloadRef().uri(),
                command.payloadRef().sha256(),
                command.payloadRef().sizeBytes()),
            new RoomGraphCommand.InvocationContext(
                "evidence-clerk",
                "prompt-v1",
                "model-v1",
                "output-v1",
                "policy-v1",
                "guardrail-v1",
                List.of(),
                "key-v1",
                "nonce-v1"),
            new RoomGraphCommand.RetryBudget(1, 1, 0),
            command.deadlineAt(),
            command.traceparent(),
            command.requestHash());
    ExecuteAgentRunRequest root =
        new ExecuteAgentRunRequest(
            ExecuteAgentRunRequest.SCHEMA_VERSION,
            logicalRunId,
            1,
            "agent-stream.v2",
            "e".repeat(64),
            null,
            false,
            0,
            graph);
    ExecuteAgentRunResult terminal =
        new ExecuteAgentRunResult(
            ExecuteAgentRunResult.SCHEMA_VERSION,
            root.agentRunId(),
            root.logicalRunId(),
            root.attemptId(),
            root.attemptNo(),
            ExecuteAgentRunResult.Outcome.FAILED,
            null,
            null,
            0,
            false,
            "EVIDENCE_AGENT_RUN_FAILED",
            false,
            AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
            command.occurredAt().plusSeconds(1));
    return new TargetRoomAgentRunTerminalNoCommit(
        TargetRoomAgentRunTerminalNoCommit.SCHEMA_VERSION,
        command,
        1,
        0,
        0,
        CaseProcessWorkflowProtocol.roomWorkflowId(
            command.caseId(), RoomType.EVIDENCE, command.roomEpoch()),
        "room-run-recovery",
        "evidence-room-recovery.v1",
        "a".repeat(64),
        "b".repeat(64),
        root,
        terminal,
        AgentRunAttemptStatus.FAILED,
        terminal.errorCode(),
        terminal.retryable(),
        terminal.recoveryAction(),
        terminal.lastSequenceNo(),
        terminal.completedAt(),
        false);
  }

  private static ActiveChildDescriptor descriptor(ProvisionRoomEpoch request, String runId) {
    return new ActiveChildDescriptor(
        ActiveChildKind.TYPED_INTAKE,
        request.selectionSchemaVersion(),
        request.writerMode(),
        request.caseWorkflowType(),
        request.caseWorkflowBuildId(),
        request.roomWorkflowType(),
        request.roomWorkflowBuildId(),
        request.roomType(),
        request.roomEpoch(),
        request.fencingToken(),
        request.roomWorkflowId(),
        runId,
        "a".repeat(64),
        "b".repeat(64));
  }

  private static ProvisionRoomEpoch typedProvision() {
    return new ProvisionRoomEpoch(
        ProvisionRoomEpoch.SCHEMA_VERSION,
        "epoch-intake-3",
        "tenant-legacy",
        "CASE_Legacy",
        "room-intake-3",
        RoomType.INTAKE,
        3,
        7,
        5,
        11,
        "ACTIVE",
        "INTAKE",
        "ACTIVE",
        WriterMode.SHADOW,
        CaseProcessWorkflowProtocol.caseWorkflowId("tenant-legacy", "CASE_Legacy"),
        CaseProcessWorkflowProtocol.roomWorkflowId("CASE_Legacy", RoomType.INTAKE, 3),
        "room-epoch-selection.v2",
        "case-process-contract.v1",
        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
        "case-control.synthetic.v1",
        "IntakeRoomWorkflow",
        "intake-room.synthetic.v1",
        "intake.v2",
        "2.0.0",
        "intake-checkpoint.v2",
        "agent-stream.v2",
        3,
        4,
        4,
        5,
        Instant.parse("2026-07-21T10:00:00Z"),
        null,
        null,
        Instant.parse("2026-07-21T08:00:00Z"));
  }

  private static ProvisionRoomEpochReceipt receipt(
      ProvisionRoomEpoch request, String roomWorkflowRunId) {
    return new ProvisionRoomEpochReceipt(
        "provision-room-epoch-receipt.v1",
        request.epochId(),
        request.tenantSurrogate(),
        request.caseId(),
        request.roomId(),
        request.roomType(),
        request.roomEpoch(),
        request.fencingToken(),
        request.initialProcessRevision(),
        request.initialRoomRevision(),
        request.macroPhase(),
        request.currentRoom(),
        request.roomPhase(),
        request.projectedDeadlineAt(),
        request.writerMode(),
        request.selectionSchemaVersion(),
        request.processContractVersion(),
        request.caseWorkflowType(),
        request.caseWorkflowBuildId(),
        request.roomWorkflowType(),
        request.roomWorkflowBuildId(),
        request.graphKey(),
        request.graphVersion(),
        request.checkpointSchemaVersion(),
        request.streamProtocol(),
        request.lastCommandSequence(),
        request.lastCaseEventSequence(),
        request.firstCommandSequence(),
        request.firstCaseEventSequence(),
        request.projectionRef(),
        request.projectionSha256(),
        request.requestedAt(),
        request.caseWorkflowId(),
        "case-run",
        request.roomWorkflowId(),
        roomWorkflowRunId,
        request.payloadSha256());
  }
}
