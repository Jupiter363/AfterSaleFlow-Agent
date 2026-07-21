package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import java.util.List;
import java.util.Objects;

public record CaseProcessCarryState(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    RoomType activeRoomType,
    long activeRoomEpoch,
    String activeChildWorkflowId,
    long observedProcessRevision,
    long nextCommandSequence,
    long nextCaseEventSequence,
    long processedCommandCount,
    long processedEventCount,
    List<ProcessedCommandIdentity> recentCommands,
    List<CaseDomainEventRef> bufferedEvents,
    long highestObservedEventSequence,
    int runGeneration,
    int commandRecoveryAttempts,
    int eventRecoveryAttempts,
    boolean commandManualRecoveryRequired,
    boolean eventManualRecoveryRequired,
    String protocolErrorCode,
    List<ClosedRoomTuple> closedRooms,
    long activeFencingToken,
    String activeChildWorkflowRunId,
    List<ProvisioningCommitment> provisioningCommitments,
    List<ProvisionedRoomEpochHighWater> highestProvisionedEpochs,
    ActiveChildDescriptor activeChildDescriptor,
    Long activeRoomRevision,
    RecoveryErrorOrigin protocolErrorOrigin) {

  public static final int MAX_RECENT_COMMANDS = 256;
  public static final int MAX_BUFFERED_EVENTS = 128;
  public static final int MAX_CLOSED_ROOMS = 256;
  public static final int MAX_PROVISIONING_COMMITMENTS = 64;

  public CaseProcessCarryState(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      RoomType activeRoomType,
      long activeRoomEpoch,
      String activeChildWorkflowId,
      long observedProcessRevision,
      long nextCommandSequence,
      long nextCaseEventSequence,
      long processedCommandCount,
      long processedEventCount,
      List<ProcessedCommandIdentity> recentCommands,
      List<CaseDomainEventRef> bufferedEvents,
      long highestObservedEventSequence,
      int runGeneration,
      int commandRecoveryAttempts,
      int eventRecoveryAttempts,
      boolean commandManualRecoveryRequired,
      boolean eventManualRecoveryRequired,
      String protocolErrorCode,
      List<ClosedRoomTuple> closedRooms,
      long activeFencingToken,
      String activeChildWorkflowRunId,
      List<ProvisioningCommitment> provisioningCommitments,
      List<ProvisionedRoomEpochHighWater> highestProvisionedEpochs) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        activeRoomType,
        activeRoomEpoch,
        activeChildWorkflowId,
        observedProcessRevision,
        nextCommandSequence,
        nextCaseEventSequence,
        processedCommandCount,
        processedEventCount,
        recentCommands,
        bufferedEvents,
        highestObservedEventSequence,
        runGeneration,
        commandRecoveryAttempts,
        eventRecoveryAttempts,
        commandManualRecoveryRequired,
        eventManualRecoveryRequired,
        protocolErrorCode,
        closedRooms,
        activeFencingToken,
        activeChildWorkflowRunId,
        provisioningCommitments,
        highestProvisionedEpochs,
        null,
        null,
        null);
  }

  public CaseProcessCarryState(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      RoomType activeRoomType,
      long activeRoomEpoch,
      String activeChildWorkflowId,
      long observedProcessRevision,
      long nextCommandSequence,
      long nextCaseEventSequence,
      long processedCommandCount,
      long processedEventCount,
      List<ProcessedCommandIdentity> recentCommands,
      List<CaseDomainEventRef> bufferedEvents,
      long highestObservedEventSequence,
      int runGeneration,
      int commandRecoveryAttempts,
      int eventRecoveryAttempts,
      boolean commandManualRecoveryRequired,
      boolean eventManualRecoveryRequired,
      String protocolErrorCode,
      List<ClosedRoomTuple> closedRooms) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        activeRoomType,
        activeRoomEpoch,
        activeChildWorkflowId,
        observedProcessRevision,
        nextCommandSequence,
        nextCaseEventSequence,
        processedCommandCount,
        processedEventCount,
        recentCommands,
        bufferedEvents,
        highestObservedEventSequence,
        runGeneration,
        commandRecoveryAttempts,
        eventRecoveryAttempts,
        commandManualRecoveryRequired,
        eventManualRecoveryRequired,
        protocolErrorCode,
        closedRooms,
        0,
        null,
        List.of(),
        List.of(),
        null,
        null,
        null);
  }

  public CaseProcessCarryState(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      RoomType activeRoomType,
      long activeRoomEpoch,
      String activeChildWorkflowId,
      long observedProcessRevision,
      long nextCommandSequence,
      long nextCaseEventSequence,
      long processedCommandCount,
      long processedEventCount,
      List<ProcessedCommandIdentity> recentCommands,
      List<CaseDomainEventRef> bufferedEvents,
      long highestObservedEventSequence,
      int runGeneration,
      int commandRecoveryAttempts,
      int eventRecoveryAttempts,
      boolean commandManualRecoveryRequired,
      boolean eventManualRecoveryRequired,
      String protocolErrorCode,
      List<ClosedRoomTuple> closedRooms,
      long activeFencingToken,
      String activeChildWorkflowRunId,
      List<ProvisioningCommitment> provisioningCommitments,
      List<ProvisionedRoomEpochHighWater> highestProvisionedEpochs,
      ActiveChildDescriptor activeChildDescriptor) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        activeRoomType,
        activeRoomEpoch,
        activeChildWorkflowId,
        observedProcessRevision,
        nextCommandSequence,
        nextCaseEventSequence,
        processedCommandCount,
        processedEventCount,
        recentCommands,
        bufferedEvents,
        highestObservedEventSequence,
        runGeneration,
        commandRecoveryAttempts,
        eventRecoveryAttempts,
        commandManualRecoveryRequired,
        eventManualRecoveryRequired,
        protocolErrorCode,
        closedRooms,
        activeFencingToken,
        activeChildWorkflowRunId,
        provisioningCommitments,
        highestProvisionedEpochs,
        activeChildDescriptor,
        null,
        null);
  }

  public CaseProcessCarryState {
    if (!"case-process-carry-state.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be case-process-carry-state.v1");
    }
    if ((tenantSurrogate == null) != (caseId == null)) {
      throw new IllegalArgumentException("tenant and case identity must be bound together");
    }
    if ((activeRoomType == null) != (activeChildWorkflowId == null)) {
      throw new IllegalArgumentException("active room and child identity must be bound together");
    }
    if (activeRoomType == null && activeRoomEpoch != -1) {
      throw new IllegalArgumentException("inactive room epoch must be -1");
    }
    if (activeRoomType != null && activeRoomEpoch < 0) {
      throw new IllegalArgumentException("active room epoch must not be negative");
    }
    if (activeFencingToken < 0
        || (activeRoomType == null && activeFencingToken != 0)
        || (activeFencingToken > 0
            && (activeChildWorkflowRunId == null || activeChildWorkflowRunId.isBlank()))) {
      throw new IllegalArgumentException("active room fencing identity is invalid");
    }
    if (activeChildDescriptor != null
        && (activeRoomType == null
            || activeChildDescriptor.roomType() != activeRoomType
            || activeChildDescriptor.roomEpoch() != activeRoomEpoch
            || activeChildDescriptor.fencingToken() != activeFencingToken
            || !Objects.equals(activeChildDescriptor.workflowId(), activeChildWorkflowId)
            || !Objects.equals(activeChildDescriptor.startedRunId(), activeChildWorkflowRunId))) {
      throw new IllegalArgumentException(
          "active child descriptor does not match the top-level active child identity");
    }
    if (observedProcessRevision < 0
        || nextCommandSequence < 1
        || nextCaseEventSequence < 1
        || processedCommandCount < 0
        || processedEventCount < 0
        || highestObservedEventSequence < 0
        || runGeneration < 0
        || commandRecoveryAttempts < 0
        || eventRecoveryAttempts < 0) {
      throw new IllegalArgumentException("case process counters are invalid");
    }
    recentCommands = List.copyOf(recentCommands);
    bufferedEvents = List.copyOf(bufferedEvents);
    closedRooms = closedRooms == null ? List.of() : List.copyOf(closedRooms);
    provisioningCommitments =
        provisioningCommitments == null ? List.of() : List.copyOf(provisioningCommitments);
    highestProvisionedEpochs =
        highestProvisionedEpochs == null ? List.of() : List.copyOf(highestProvisionedEpochs);
    if (activeRoomRevision != null
        && (activeRoomRevision < 0 || activeRoomType == null)) {
      throw new IllegalArgumentException("active room revision is invalid");
    }
    if (recentCommands.size() > MAX_RECENT_COMMANDS) {
      throw new IllegalArgumentException("recent command cache is too large");
    }
    if (bufferedEvents.size() > MAX_BUFFERED_EVENTS) {
      throw new IllegalArgumentException("buffered event cache is too large");
    }
    if (closedRooms.size() > MAX_CLOSED_ROOMS) {
      throw new IllegalArgumentException("closed room cache is too large");
    }
    if (provisioningCommitments.size() > MAX_PROVISIONING_COMMITMENTS) {
      throw new IllegalArgumentException("provisioning commitment cache is too large");
    }
    int previousRoomTypeOrdinal = -1;
    for (ProvisionedRoomEpochHighWater highWater : highestProvisionedEpochs) {
      if (highWater.roomType().ordinal() <= previousRoomTypeOrdinal) {
        throw new IllegalArgumentException("provisioned room high-water list is not ordered");
      }
      previousRoomTypeOrdinal = highWater.roomType().ordinal();
    }
    if (activeChildDescriptor != null
        && activeChildDescriptor.kind() == ActiveChildKind.TYPED_INTAKE) {
      ProvisioningCommitment pinned = null;
      for (ProvisioningCommitment commitment : provisioningCommitments) {
        if (commitment.request().roomType() == activeRoomType
            && commitment.request().roomEpoch() == activeRoomEpoch
            && commitment.request().fencingToken() == activeFencingToken) {
          pinned = commitment;
        }
      }
      if (pinned == null || !activeChildDescriptor.matches(pinned)) {
        throw new IllegalArgumentException(
            "typed active child descriptor does not match its provisioning commitment");
      }
      long initialRoomRevision = pinned.request().initialRoomRevision();
      if (activeRoomRevision != null && activeRoomRevision < initialRoomRevision) {
        throw new IllegalArgumentException(
            "active room revision precedes the provisioned initial revision");
      }
    }
  }

  public static CaseProcessCarryState initial() {
    return new CaseProcessCarryState(
        "case-process-carry-state.v1",
        null,
        null,
        null,
        -1,
        null,
        0,
        1,
        1,
        0,
        0,
        List.of(),
        List.of(),
        0,
        0,
        0,
        0,
        false,
        false,
        null,
        List.of());
  }

  public record ClosedRoomTuple(RoomType roomType, long roomEpoch) {

    public ClosedRoomTuple {
      if (roomType == null || roomEpoch < 0) {
        throw new IllegalArgumentException("closed room tuple is invalid");
      }
    }
  }

  public record ProvisionedRoomEpochHighWater(RoomType roomType, long roomEpoch) {

    public ProvisionedRoomEpochHighWater {
      if (roomType == null || roomEpoch < 0) {
        throw new IllegalArgumentException("provisioned room epoch high-water is invalid");
      }
    }
  }

  public enum ActiveChildKind {
    GENERIC_ROOM_CONTROL,
    TYPED_INTAKE
  }

  public enum RecoveryErrorOrigin {
    PROVISIONING,
    COMMAND,
    DOMAIN_EVENT,
    SYSTEM
  }

  public record ActiveChildDescriptor(
      ActiveChildKind kind,
      String selectionSchemaVersion,
      WriterMode writerMode,
      String caseWorkflowType,
      String caseWorkflowBuildId,
      String roomWorkflowType,
      String roomWorkflowBuildId,
      RoomType roomType,
      long roomEpoch,
      long fencingToken,
      String workflowId,
      String startedRunId) {

    public ActiveChildDescriptor {
      Objects.requireNonNull(kind, "active child kind must not be null");
      requireText(selectionSchemaVersion, "selectionSchemaVersion");
      Objects.requireNonNull(writerMode, "writerMode must not be null");
      requireText(caseWorkflowType, "caseWorkflowType");
      requireText(caseWorkflowBuildId, "caseWorkflowBuildId");
      Objects.requireNonNull(roomType, "roomType must not be null");
      if (roomEpoch < 0 || fencingToken < 0) {
        throw new IllegalArgumentException("active child room tuple is invalid");
      }
      requireText(workflowId, "workflowId");
      if (startedRunId != null && startedRunId.isBlank()) {
        throw new IllegalArgumentException("startedRunId must not be blank");
      }
      if ("room-epoch-selection.v1".equals(selectionSchemaVersion)) {
        if (roomWorkflowType != null || roomWorkflowBuildId != null) {
          throw new IllegalArgumentException("v1 descriptor cannot contain a room child binding");
        }
      } else if ("room-epoch-selection.v2".equals(selectionSchemaVersion)) {
        requireText(roomWorkflowType, "roomWorkflowType");
        requireText(roomWorkflowBuildId, "roomWorkflowBuildId");
      } else {
        throw new IllegalArgumentException("active child selection version is unsupported");
      }
      if (kind == ActiveChildKind.TYPED_INTAKE
          && (!"room-epoch-selection.v2".equals(selectionSchemaVersion)
              || writerMode == WriterMode.LEGACY
              || roomType != RoomType.INTAKE
              || !"IntakeRoomWorkflow".equals(roomWorkflowType)
              || !"intake-room.synthetic.v1".equals(roomWorkflowBuildId)
              || fencingToken < 1
              || startedRunId == null)) {
        throw new IllegalArgumentException("typed Intake descriptor binding is invalid");
      }
    }

    private static void requireText(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " must not be blank");
      }
    }

    boolean matches(ProvisioningCommitment commitment) {
      ProvisionRoomEpoch request = commitment.request();
      return selectionSchemaVersion.equals(request.selectionSchemaVersion())
          && writerMode == request.writerMode()
          && caseWorkflowType.equals(request.caseWorkflowType())
          && caseWorkflowBuildId.equals(request.caseWorkflowBuildId())
          && Objects.equals(roomWorkflowType, request.roomWorkflowType())
          && Objects.equals(roomWorkflowBuildId, request.roomWorkflowBuildId())
          && roomType == request.roomType()
          && roomEpoch == request.roomEpoch()
          && fencingToken == request.fencingToken()
          && workflowId.equals(request.roomWorkflowId())
          && startedRunId.equals(commitment.receipt().roomWorkflowRunId());
    }
  }
}
