package com.example.dispute.workflow.temporal.room.intake;

import java.util.List;

/** Bounded state handed to the next Intake run by Continue-As-New. */
public record IntakeRoomCarryState(
    String schemaVersion,
    IntakeRoomPhase roomPhase,
    IntakeParty activeParty,
    long nextCommandSequence,
    long nextEventSequence,
    long processedCommandCount,
    long processedEventCount,
    boolean initiatorComplete,
    boolean respondentUnlocked,
    boolean respondentComplete,
    IntakeParty readinessParty,
    String lastEventId,
    String lastEventRef,
    String lastEventHash,
    IntakeAgentRunRef lastAgentRunRef,
    IntakeGraphExecutionRef lastGraphExecutionRef,
    IntakeTerminalReason terminalReason,
    long processRevision,
    long roomRevision,
    String protocolErrorCode,
    int runGeneration,
    IntakeCommandDecision lastDecision,
    List<ObservedCommand> observedCommands,
    List<ObservedEvent> observedEvents,
    List<IntakeThreadInitialization> threadInitializations,
    IntakeAgentRunChildState targetAgentRunChild,
    List<ObservedTargetSourceEvent> observedTargetSourceEvents) {

  public static final int MAX_OBSERVED = 256;
  public static final int MAX_THREAD_INITIALIZATIONS = 2;

  public IntakeRoomCarryState(
      String schemaVersion,
      IntakeRoomPhase roomPhase,
      IntakeParty activeParty,
      long nextCommandSequence,
      long nextEventSequence,
      long processedCommandCount,
      long processedEventCount,
      boolean initiatorComplete,
      boolean respondentUnlocked,
      boolean respondentComplete,
      IntakeParty readinessParty,
      String lastEventId,
      String lastEventRef,
      String lastEventHash,
      IntakeAgentRunRef lastAgentRunRef,
      IntakeGraphExecutionRef lastGraphExecutionRef,
      IntakeTerminalReason terminalReason,
      long processRevision,
      long roomRevision,
      String protocolErrorCode,
      int runGeneration,
      IntakeCommandDecision lastDecision,
      List<ObservedCommand> observedCommands,
      List<ObservedEvent> observedEvents,
      List<IntakeThreadInitialization> threadInitializations) {
    this(
        schemaVersion,
        roomPhase,
        activeParty,
        nextCommandSequence,
        nextEventSequence,
        processedCommandCount,
        processedEventCount,
        initiatorComplete,
        respondentUnlocked,
        respondentComplete,
        readinessParty,
        lastEventId,
        lastEventRef,
        lastEventHash,
        lastAgentRunRef,
        lastGraphExecutionRef,
        terminalReason,
        processRevision,
        roomRevision,
        protocolErrorCode,
        runGeneration,
        lastDecision,
        observedCommands,
        observedEvents,
        threadInitializations,
        null,
        List.of());
  }

  public IntakeRoomCarryState(
      String schemaVersion,
      IntakeRoomPhase roomPhase,
      IntakeParty activeParty,
      long nextCommandSequence,
      long nextEventSequence,
      long processedCommandCount,
      long processedEventCount,
      boolean initiatorComplete,
      boolean respondentUnlocked,
      boolean respondentComplete,
      IntakeParty readinessParty,
      String lastEventId,
      String lastEventRef,
      String lastEventHash,
      IntakeAgentRunRef lastAgentRunRef,
      IntakeGraphExecutionRef lastGraphExecutionRef,
      IntakeTerminalReason terminalReason,
      long processRevision,
      long roomRevision,
      String protocolErrorCode,
      int runGeneration,
      IntakeCommandDecision lastDecision,
      List<ObservedCommand> observedCommands,
      List<ObservedEvent> observedEvents,
      List<IntakeThreadInitialization> threadInitializations,
      IntakeAgentRunChildState targetAgentRunChild) {
    this(
        schemaVersion,
        roomPhase,
        activeParty,
        nextCommandSequence,
        nextEventSequence,
        processedCommandCount,
        processedEventCount,
        initiatorComplete,
        respondentUnlocked,
        respondentComplete,
        readinessParty,
        lastEventId,
        lastEventRef,
        lastEventHash,
        lastAgentRunRef,
        lastGraphExecutionRef,
        terminalReason,
        processRevision,
        roomRevision,
        protocolErrorCode,
        runGeneration,
        lastDecision,
        observedCommands,
        observedEvents,
        threadInitializations,
        targetAgentRunChild,
        List.of());
  }

  public IntakeRoomCarryState {
    if (!"intake-room-carry-state.v1".equals(schemaVersion)
        && !"intake-room-carry-state.v2".equals(schemaVersion)
        && !"intake-room-carry-state.v3".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-room-carry-state.v1, v2, or v3");
    }
    if ("intake-room-carry-state.v1".equals(schemaVersion) && targetAgentRunChild != null) {
      throw new IllegalArgumentException("v1 carry state cannot contain target child identity");
    }
    if (targetAgentRunChild != null && targetAgentRunChild.unresolved()) {
      throw new IllegalArgumentException("unresolved target child cannot cross continue-as-new");
    }
    if (roomPhase == null || activeParty == null) {
      throw new IllegalArgumentException("room phase and active party must not be null");
    }
    if (nextCommandSequence < 1
        || nextEventSequence < 1
        || processedCommandCount < 0
        || processedEventCount < 0
        || processRevision < 0
        || roomRevision < 0
        || runGeneration < 0) {
      throw new IllegalArgumentException("intake carry counters are invalid");
    }
    observedCommands = List.copyOf(observedCommands == null ? List.of() : observedCommands);
    observedEvents = List.copyOf(observedEvents == null ? List.of() : observedEvents);
    observedTargetSourceEvents =
        List.copyOf(
            observedTargetSourceEvents == null ? List.of() : observedTargetSourceEvents);
    threadInitializations =
        List.copyOf(threadInitializations == null ? List.of() : threadInitializations);
    if (observedCommands.size() > MAX_OBSERVED
        || observedEvents.size() > MAX_OBSERVED
        || observedTargetSourceEvents.size() > MAX_OBSERVED) {
      throw new IllegalArgumentException("intake observation cache exceeds the bound");
    }
    if (!"intake-room-carry-state.v3".equals(schemaVersion)
        && !observedTargetSourceEvents.isEmpty()) {
      throw new IllegalArgumentException(
          "target source event observations require intake-room-carry-state.v3");
    }
    if (threadInitializations.size() > MAX_THREAD_INITIALIZATIONS) {
      throw new IllegalArgumentException("intake thread initialization cache exceeds the bound");
    }
  }

  public record ObservedCommand(
      String schemaVersion, IntakeWorkflowCommand command, IntakeCommandDecision decision) {
    public ObservedCommand {
      if (!"intake-observed-command.v1".equals(schemaVersion)) {
        throw new IllegalArgumentException("schemaVersion must be intake-observed-command.v1");
      }
      if (command == null) {
        throw new IllegalArgumentException("observed command must not be null");
      }
    }
  }

  public record ObservedEvent(String schemaVersion, IntakeDomainEventRef event, boolean applied) {
    public ObservedEvent {
      if (!"intake-observed-event.v1".equals(schemaVersion)) {
        throw new IllegalArgumentException("schemaVersion must be intake-observed-event.v1");
      }
      if (event == null) {
        throw new IllegalArgumentException("observed event must not be null");
      }
    }
  }

  public record ObservedTargetSourceEvent(
      String schemaVersion, TargetIntakeSourceEventRef event) {
    public ObservedTargetSourceEvent {
      if (!"intake-observed-target-source-event.v1".equals(schemaVersion)) {
        throw new IllegalArgumentException(
            "schemaVersion must be intake-observed-target-source-event.v1");
      }
      if (event == null) {
        throw new IllegalArgumentException("observed target source event must not be null");
      }
    }
  }

  public static IntakeRoomCarryState initial() {
    return new IntakeRoomCarryState(
        "intake-room-carry-state.v1",
        IntakeRoomPhase.OPEN,
        IntakeParty.INITIATOR,
        1,
        1,
        0,
        0,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        null,
        0,
        null,
        List.of(),
        List.of(),
        List.of());
  }
}
