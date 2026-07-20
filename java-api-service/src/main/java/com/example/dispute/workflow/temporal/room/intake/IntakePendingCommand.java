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
    String requestHash) {

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
        command.requestHash());
  }
}
