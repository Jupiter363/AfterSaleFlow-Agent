package com.example.dispute.workflow.temporal.room.intake;

public record IntakePendingCommand(
    String schemaVersion,
    String commandId,
    long sequence,
    IntakeCommandType commandType,
    IntakeParty party,
    String actorScopeHash,
    String payloadRef,
    String payloadHash,
    String operationKey,
    String requestHash,
    IntakeCommandExecutionContext executionContext) {

  public IntakePendingCommand(
      String schemaVersion,
      String commandId,
      long sequence,
      IntakeCommandType commandType,
      IntakeParty party,
      String actorScopeHash,
      String payloadRef,
      String payloadHash,
      String operationKey,
      String requestHash) {
    this(
        schemaVersion,
        commandId,
        sequence,
        commandType,
        party,
        actorScopeHash,
        payloadRef,
        payloadHash,
        operationKey,
        requestHash,
        null);
  }

  public IntakePendingCommand {
    if (!"intake-pending-command.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-pending-command.v1");
    }
  }

  static IntakePendingCommand from(IntakeWorkflowCommand command) {
    return new IntakePendingCommand(
        "intake-pending-command.v1",
        command.commandId(),
        command.sequence(),
        command.commandType(),
        command.party(),
        command.actorScopeHash(),
        command.payloadRef(),
        command.payloadHash(),
        command.operationKey(),
        command.requestHash(),
        command.executionContext());
  }
}
