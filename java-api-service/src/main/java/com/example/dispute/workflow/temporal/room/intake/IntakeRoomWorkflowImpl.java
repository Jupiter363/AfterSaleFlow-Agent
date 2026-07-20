package com.example.dispute.workflow.temporal.room.intake;

import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowQueue;
import java.util.LinkedHashMap;
import java.util.Map;

public final class IntakeRoomWorkflowImpl implements IntakeRoomWorkflow {

  private static final int INBOX_CAPACITY = 128;
  private static final int RECENT_CAPACITY = 256;

  private final WorkflowQueue<IntakeWorkflowCommand> commandInbox =
      Workflow.newQueue(INBOX_CAPACITY);
  private final WorkflowQueue<IntakeDomainReceipt> receiptInbox =
      Workflow.newQueue(INBOX_CAPACITY);
  private final Map<String, String> commandHashes = new LinkedHashMap<>();
  private final Map<String, String> commandRejectionCodes = new LinkedHashMap<>();
  private final Map<String, String> receiptHashes = new LinkedHashMap<>();

  private IntakeRoomStart start;
  private IntakeRoomPhase roomPhase = IntakeRoomPhase.OPEN;
  private long nextCommandSequence;
  private long nextEventSequence;
  private long processedCommandCount;
  private long processedEventCount;
  private int pendingCommandCount;
  private int pendingReceiptCount;
  private boolean initiatorComplete;
  private boolean respondentUnlocked;
  private boolean respondentComplete;
  private IntakeParty readinessParty;
  private String pendingCommandId;
  private String pendingOperationKey;
  private IntakeTerminalReason terminalReason;
  private long processRevision;
  private long roomRevision;
  private String protocolErrorCode;
  private IntakeCommandDecision lastDecision;

  @Override
  public IntakeRoomSnapshot run(IntakeRoomStart start) {
    this.start = start;
    nextCommandSequence = start.firstCommandSequence();
    nextEventSequence = start.firstEventSequence();
    processRevision = start.initialProcessRevision();
    roomRevision = start.initialRoomRevision();

    while (roomPhase != IntakeRoomPhase.COMPLETED) {
      Workflow.await(() -> pendingCommandCount > 0 || pendingReceiptCount > 0);
      drainOneCommand();
      drainOneReceipt();
    }
    Workflow.await(Workflow::isEveryHandlerFinished);
    return state();
  }

  @Override
  public void commandAccepted(IntakeWorkflowCommand command) {
    commandInbox.put(command);
    pendingCommandCount++;
  }

  @Override
  public void domainReceiptCommitted(IntakeDomainReceipt receipt) {
    receiptInbox.put(receipt);
    pendingReceiptCount++;
  }

  @Override
  public IntakeRoomSnapshot state() {
    return new IntakeRoomSnapshot(
        "intake-room-snapshot.v1",
        start == null ? null : start.tenantSurrogate(),
        start == null ? null : start.caseId(),
        start == null ? 0 : start.roomEpoch(),
        start == null ? 0 : start.fencingToken(),
        roomPhase,
        nextCommandSequence,
        nextEventSequence,
        processedCommandCount,
        processedEventCount,
        initiatorComplete,
        respondentUnlocked,
        respondentComplete,
        readinessParty,
        pendingCommandId,
        pendingOperationKey,
        terminalReason,
        processRevision,
        roomRevision,
        protocolErrorCode);
  }

  @Override
  public IntakeCommandDecision lastCommandDecision() {
    return lastDecision;
  }

  private void drainOneCommand() {
    if (pendingCommandCount == 0) {
      return;
    }
    IntakeWorkflowCommand command = commandInbox.poll();
    if (command == null) {
      return;
    }
    pendingCommandCount--;
    if (command.sequence() < nextCommandSequence) {
      handleCommandReplay(command);
      return;
    }
    if (command.sequence() > nextCommandSequence) {
      reject(command, "COMMAND_SEQUENCE_GAP", false);
      return;
    }
    if (!matches(command)) {
      reject(command, "COMMAND_SCOPE_MISMATCH", true);
      return;
    }
    String rejection = businessRejection(command);
    if (rejection != null) {
      reject(command, rejection, true);
      return;
    }

    commandHashes.put(command.commandId(), command.requestHash());
    commandRejectionCodes.put(command.commandId(), null);
    trim(commandHashes);
    trim(commandRejectionCodes);
    nextCommandSequence++;
    processedCommandCount++;
    protocolErrorCode = null;
    pendingCommandId = command.commandId();
    pendingOperationKey = command.operationKey();
    if (command.commandType() == IntakeCommandType.INTAKE_MESSAGE) {
      roomPhase = IntakeRoomPhase.AGENT_RUNNING;
      readinessParty = null;
    }
    lastDecision =
        new IntakeCommandDecision(
            "intake-command-decision.v1",
            command.commandId(),
            command.sequence(),
            "ACCEPTED",
            null,
            roomPhase,
            command.requestHash());
  }

  private void drainOneReceipt() {
    if (pendingReceiptCount == 0) {
      return;
    }
    IntakeDomainReceipt receipt = receiptInbox.poll();
    if (receipt == null) {
      return;
    }
    pendingReceiptCount--;
    String previousHash = receiptHashes.get(receipt.receiptId());
    if (previousHash != null) {
      if (!previousHash.equals(receipt.receiptHash())) {
        protocolErrorCode = "RECEIPT_REPLAY_CONFLICT";
      }
      return;
    }
    if (receipt.eventSequence() != nextEventSequence) {
      protocolErrorCode =
          receipt.eventSequence() < nextEventSequence
              ? "RECEIPT_SEQUENCE_REPLAY_UNKNOWN"
              : "RECEIPT_SEQUENCE_GAP";
      return;
    }
    if (!matches(receipt)) {
      protocolErrorCode = "RECEIPT_SCOPE_MISMATCH";
      return;
    }
    if (pendingCommandId == null || !pendingCommandId.equals(receipt.commandId())) {
      protocolErrorCode = "RECEIPT_COMMAND_MISMATCH";
      return;
    }
    if (!pendingOperationKey.equals(receipt.operationKey())) {
      protocolErrorCode = "RECEIPT_OPERATION_KEY_MISMATCH";
      return;
    }
    if (!commandHashes.get(receipt.commandId()).equals(receipt.requestHash())) {
      protocolErrorCode = "RECEIPT_REQUEST_HASH_MISMATCH";
      return;
    }
    if (receipt.processRevision() < processRevision || receipt.roomRevision() < roomRevision) {
      protocolErrorCode = "RECEIPT_STALE_REVISION";
      return;
    }
    if (!applyReceipt(receipt)) {
      return;
    }

    receiptHashes.put(receipt.receiptId(), receipt.receiptHash());
    trim(receiptHashes);
    nextEventSequence++;
    processedEventCount++;
    processRevision = receipt.processRevision();
    roomRevision = receipt.roomRevision();
    pendingCommandId = null;
    pendingOperationKey = null;
    protocolErrorCode = null;
  }

  private void handleCommandReplay(IntakeWorkflowCommand command) {
    String existing = commandHashes.get(command.commandId());
    if (existing != null && existing.equals(command.requestHash())) {
      lastDecision =
          new IntakeCommandDecision(
              "intake-command-decision.v1",
              command.commandId(),
              command.sequence(),
              "DUPLICATE",
              commandRejectionCodes.get(command.commandId()),
              roomPhase,
              command.requestHash());
      return;
    }
    reject(command, "COMMAND_REPLAY_CONFLICT", false);
  }

  private String businessRejection(IntakeWorkflowCommand command) {
    if (roomPhase == IntakeRoomPhase.COMPLETED) {
      return "INTAKE_ALREADY_COMPLETED";
    }
    if (command.commandType() == IntakeCommandType.INTAKE_CANCEL
        && command.party() == IntakeParty.RESPONDENT) {
      return "RESPONDENT_CANCEL_FORBIDDEN";
    }
    if (command.party() == IntakeParty.RESPONDENT && !respondentUnlocked) {
      return "RESPONDENT_LOCKED";
    }
    if (command.commandType() == IntakeCommandType.INTAKE_CANCEL) {
      return null;
    }
    if (pendingCommandId != null) {
      return "INTAKE_OPERATION_PENDING";
    }
    if (command.commandType() == IntakeCommandType.INTAKE_MESSAGE) {
      if (command.party() == IntakeParty.INITIATOR && initiatorComplete) {
        return "INITIATOR_ALREADY_COMPLETE";
      }
      if (command.party() == IntakeParty.RESPONDENT && respondentComplete) {
        return "RESPONDENT_ALREADY_COMPLETE";
      }
      return null;
    }
    if (command.commandType() == IntakeCommandType.INTAKE_CONFIRM) {
      if (roomPhase != IntakeRoomPhase.READY_TO_CONFIRM || readinessParty != command.party()) {
        return "PARTY_NOT_READY_TO_CONFIRM";
      }
      return null;
    }
    return "COMMAND_TYPE_UNSUPPORTED";
  }

  private boolean applyReceipt(IntakeDomainReceipt receipt) {
    switch (receipt.receiptType()) {
      case TURN_NEEDS_INPUT -> {
        if (receipt.party() == IntakeParty.RESPONDENT && !respondentUnlocked) {
          protocolErrorCode = "RESPONDENT_LOCKED";
          return false;
        }
        roomPhase = IntakeRoomPhase.WAITING_PARTY;
        readinessParty = receipt.party();
      }
      case TURN_READY_TO_CONFIRM -> {
        if (receipt.party() == IntakeParty.RESPONDENT && !respondentUnlocked) {
          protocolErrorCode = "RESPONDENT_LOCKED";
          return false;
        }
        roomPhase = IntakeRoomPhase.READY_TO_CONFIRM;
        readinessParty = receipt.party();
      }
      case INITIATOR_ACCEPTED -> {
        if (receipt.party() != IntakeParty.INITIATOR || readinessParty != IntakeParty.INITIATOR) {
          protocolErrorCode = "INITIATOR_ACCEPT_RECEIPT_INVALID";
          return false;
        }
        initiatorComplete = true;
        respondentUnlocked = true;
        readinessParty = null;
        roomPhase = IntakeRoomPhase.WAITING_PARTY;
      }
      case NOT_ADMISSIBLE -> {
        if (receipt.party() != IntakeParty.INITIATOR) {
          protocolErrorCode = "NOT_ADMISSIBLE_PARTY_INVALID";
          return false;
        }
        terminalReason = IntakeTerminalReason.NOT_ADMISSIBLE;
        roomPhase = IntakeRoomPhase.COMPLETED;
      }
      case CANCELLED -> {
        if (receipt.party() != IntakeParty.INITIATOR) {
          protocolErrorCode = "CANCEL_PARTY_INVALID";
          return false;
        }
        terminalReason = IntakeTerminalReason.CANCELLED;
        roomPhase = IntakeRoomPhase.COMPLETED;
      }
      case RESPONDENT_CONFIRMED -> {
        if (receipt.party() != IntakeParty.RESPONDENT
            || !initiatorComplete
            || readinessParty != IntakeParty.RESPONDENT) {
          protocolErrorCode = "RESPONDENT_CONFIRM_RECEIPT_INVALID";
          return false;
        }
        respondentComplete = true;
        terminalReason = IntakeTerminalReason.ADMITTED;
        roomPhase = IntakeRoomPhase.COMPLETED;
      }
    }
    return true;
  }

  private void reject(IntakeWorkflowCommand command, String code, boolean consumeSequence) {
    protocolErrorCode = code;
    if (consumeSequence) {
      commandHashes.put(command.commandId(), command.requestHash());
      commandRejectionCodes.put(command.commandId(), code);
      trim(commandHashes);
      trim(commandRejectionCodes);
      nextCommandSequence++;
      processedCommandCount++;
    }
    lastDecision =
        new IntakeCommandDecision(
            "intake-command-decision.v1",
            command.commandId(),
            command.sequence(),
            "REJECTED",
            code,
            roomPhase,
            command.requestHash());
  }

  private boolean matches(IntakeWorkflowCommand command) {
    return start.tenantSurrogate().equals(command.tenantSurrogate())
        && start.caseId().equals(command.caseId())
        && start.roomEpoch() == command.roomEpoch()
        && start.fencingToken() == command.fencingToken();
  }

  private boolean matches(IntakeDomainReceipt receipt) {
    return start.tenantSurrogate().equals(receipt.tenantSurrogate())
        && start.caseId().equals(receipt.caseId())
        && start.roomEpoch() == receipt.roomEpoch()
        && start.fencingToken() == receipt.fencingToken();
  }

  private static void trim(Map<String, String> values) {
    if (values.size() <= RECENT_CAPACITY) {
      return;
    }
    values.remove(values.keySet().iterator().next());
  }
}
