package com.example.dispute.workflow.room.hearing;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFormalCommitResult;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.activity.hearing.HearingDomainReceiptAdapter;
import com.example.dispute.workflow.temporal.room.hearing.HearingOperationKeys;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomSnapshot;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

public final class HearingReceiptTestFactory {

  public static final String TENANT = "TENANT_P6_SYNTHETIC_HEARING";
  public static final String CASE_ID = "CASE_P6_SYNTHETIC_HEARING";
  public static final String ROOM_ID = "ROOM_P6_HEARING";
  public static final String FLOW_ID = "FLOW_P6_SYNTHETIC_HEARING";
  public static final String EPOCH_ID = "EPOCH_P6_SYNTHETIC_HEARING_6";
  public static final long ROOM_EPOCH = 6;
  public static final long FENCE = 19;
  public static final String INITIATOR = "PARTICIPANT_P6_INITIATOR";
  public static final String RESPONDENT = "PARTICIPANT_P6_RESPONDENT";

  private final HearingRoomStart start;

  public HearingReceiptTestFactory(HearingRoomStart start) {
    this.start = start;
  }

  public static HearingRoomStart start(Instant openedAt, Duration partyWindow) {
    return new HearingRoomStart(
        "hearing-room-start.v1",
        TENANT,
        CASE_ID,
        ROOM_ID,
        FLOW_ID,
        EPOCH_ID,
        HearingWriterMode.TEMPORAL,
        ROOM_EPOCH,
        FENCE,
        INITIATOR,
        RESPONDENT,
        openedAt,
        openedAt.plus(Duration.ofHours(3)),
        partyWindow.toSeconds(),
        0,
        0,
        "hearing-workflow.synthetic.v1");
  }

  public HearingStageReceipt stageCompletion(
      HearingRoomSnapshot source,
      HearingWorkflowStage target,
      Instant targetDeadlineAt) {
    return stageCompletion(
        source.stage(),
        source.processRevision(),
        source.roomRevision(),
        source.lastCommittedEventSequence() + 1,
        target,
        targetDeadlineAt);
  }

  public HearingStageReceipt stageCompletion(
      HearingWorkflowStage source,
      long sourceProcessRevision,
      long sourceRoomRevision,
      long eventSequence,
      HearingWorkflowStage target,
      Instant targetDeadlineAt) {
    String requestHash = hash("stage:" + source.name() + ':' + eventSequence);
    String operationKey = HearingOperationKeys.stageCompletion(
        TENANT, CASE_ID, ROOM_EPOCH, source, source.sequence());
    return HearingDomainReceiptAdapter.stage(
        domainReceipt(
            source,
            sourceProcessRevision,
            sourceRoomRevision,
            eventSequence,
            HearingAuthorityCommit.OperationType.STAGE,
            operationKey,
            requestHash,
            target,
            targetDeadlineAt,
            "stage"));
  }

  public HearingStageReceipt agentResult(HearingRoomSnapshot source) {
    HearingWorkflowStage stage = source.stage();
    long eventSequence = source.lastCommittedEventSequence() + 1;
    String commandHash = hash("command:" + stage.name() + ':' + eventSequence);
    String requestHash = hash("agent:" + stage.name() + ':' + eventSequence);
    String operationKey = HearingOperationKeys.agent(
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        stage.sequence(),
        stage.agentOperation(),
        commandHash);
    return HearingDomainReceiptAdapter.stage(
        domainReceipt(
            stage,
            source.processRevision(),
            source.roomRevision(),
            eventSequence,
            HearingAuthorityCommit.OperationType.AGENT_RESULT,
            operationKey,
            requestHash,
            stage,
            null,
            "agent"));
  }

  public HearingStageReceipt finalizer(
      HearingRoomSnapshot source,
      HearingWorkflowStage target,
      Instant targetDeadlineAt,
      String artifactType) {
    long eventSequence = source.lastCommittedEventSequence() + 1;
    String requestHash = hash("finalize:" + source.stage().name() + ':' + eventSequence);
    String operationKey = HearingOperationKeys.finalizeArtifact(
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        source.stageSequence(),
        artifactType,
        requestHash);
    return HearingDomainReceiptAdapter.stage(
        domainReceipt(
            source.stage(),
            source.processRevision(),
            source.roomRevision(),
            eventSequence,
            HearingAuthorityCommit.OperationType.FINALIZE,
            operationKey,
            requestHash,
            target,
            targetDeadlineAt,
            "finalize"));
  }

  public HearingPartyTerminalReceipt party(
      HearingRoomSnapshot source,
      String participantId,
      String requestId,
      HearingPartyTerminalReceipt.TerminalStatus status,
      boolean advance) {
    long eventSequence = source.lastCommittedEventSequence() + 1;
    String requestHash = hash("party:" + requestId);
    String operationKey = HearingOperationKeys.partyTerminal(
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        source.stage(),
        source.stageSequence(),
        participantId,
        requestId);
    HearingWorkflowStage target = advance ? source.stage().next() : source.stage();
    Instant targetDeadlineAt = advance ? null : source.stageDeadlineAt();
    HearingDomainReceipt receipt = domainReceipt(
        source.stage(),
        source.processRevision(),
        source.roomRevision(),
        eventSequence,
        HearingAuthorityCommit.OperationType.PARTY_TERMINAL,
        operationKey,
        requestHash,
        target,
        targetDeadlineAt,
        "party");
    return HearingDomainReceiptAdapter.party(receipt, requestId, participantId, status);
  }

  public HearingStageReceipt handoff(
      HearingRoomSnapshot source, String judgeV2Id, String judgeV2Hash) {
    long eventSequence = source.lastCommittedEventSequence() + 1;
    String requestHash = hash("handoff:" + eventSequence);
    String operationKey = HearingOperationKeys.handoff(
        TENANT, CASE_ID, EPOCH_ID, ROOM_EPOCH, judgeV2Id, judgeV2Hash);
    return HearingDomainReceiptAdapter.stage(
        domainReceipt(
            source.stage(),
            source.processRevision(),
            source.roomRevision(),
            eventSequence,
            HearingAuthorityCommit.OperationType.HANDOFF,
            operationKey,
            requestHash,
            source.stage(),
            null,
            "handoff"));
  }

  public HearingStageReceipt close(HearingRoomSnapshot source) {
    long eventSequence = source.lastCommittedEventSequence() + 1;
    String requestHash = hash("close:" + eventSequence);
    String operationKey = HearingOperationKeys.close(
        TENANT, CASE_ID, ROOM_EPOCH, source.handoffReceiptHash());
    return HearingDomainReceiptAdapter.stage(
        domainReceipt(
            source.stage(),
            source.processRevision(),
            source.roomRevision(),
            eventSequence,
            HearingAuthorityCommit.OperationType.CLOSE,
            operationKey,
            requestHash,
            HearingWorkflowStage.CLOSED,
            null,
            "close"));
  }

  public HearingDomainReceipt domainReceipt(
      HearingWorkflowStage source,
      long sourceProcessRevision,
      long sourceRoomRevision,
      long eventSequence,
      HearingAuthorityCommit.OperationType operationType,
      String operationKey,
      String requestHash,
      HearingWorkflowStage target,
      Instant targetDeadlineAt,
      String resultKind) {
    HearingAuthorityExpectation authority = new HearingAuthorityExpectation(
        start.tenantSurrogate(),
        start.caseId(),
        start.flowInstanceId(),
        start.epochId(),
        start.roomEpoch(),
        start.writerMode(),
        HearingFlowStage.valueOf(source.name()),
        source.sequence(),
        sourceProcessRevision,
        sourceRoomRevision,
        start.fencingToken());
    HearingAuthorityCommit command = new HearingAuthorityCommit(
        HearingAuthorityCommit.SCHEMA_VERSION,
        authority,
        operationType,
        operationKey,
        requestHash,
        null,
        start.openedAt().plusMillis(eventSequence));
    HearingFormalCommitResult result = new HearingFormalCommitResult(
        HearingFlowStage.valueOf(target.name()),
        target.sequence(),
        targetDeadlineAt,
        "urn:hearing:test:" + resultKind + ':' + eventSequence,
        hash("result:" + resultKind + ':' + eventSequence),
        eventSequence);
    return HearingDomainReceipt.committed(
        command,
        result,
        "phase6-test-namespace",
        "hearing-test-workflow",
        "hearing-test-run-" + eventSequence,
        start.workflowBuildId());
  }

  public static String hash(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
