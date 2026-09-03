package com.example.dispute.workflow.room.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.workflow.activity.hearing.HearingDomainReceiptAdapter;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.temporal.room.hearing.HearingOperationKeys;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyCommand;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomSnapshot;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflow;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingFormalizationActivities;
import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import io.temporal.activity.Activity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HearingRoomWorkflowTest {

  private static final String TASK_QUEUE = "phase6-hearing-room-workflow-test";

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;
  private SequencingFormalizationActivities sequencingFormalization;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(HearingRoomWorkflowImpl.class);
    sequencingFormalization = new SequencingFormalizationActivities();
    environment
        .newWorker("case-control")
        .registerActivitiesImplementations(sequencingFormalization);
    environment.start();
    client = environment.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void stageContractContainsExactlyFifteenStagesAndSevenAgentOperations() {
    assertThat(HearingWorkflowStage.values())
        .extracting(Enum::name)
        .containsExactly(
            "COURT_PREPARING",
            "CASE_INTRODUCTION",
            "EVIDENCE_INTRODUCTION",
            "INTAKE_QUESTIONS_GENERATING",
            "PARTY_ANSWERS_OPEN",
            "INTAKE_SYNTHESIZING",
            "EVIDENCE_REQUESTS_GENERATING",
            "PARTY_EVIDENCE_OPEN",
            "EVIDENCE_SYNTHESIZING",
            "DOSSIER_FREEZING",
            "JUDGE_V1_GENERATING",
            "JURY_REVIEWING",
            "JUDGE_V2_GENERATING",
            "HUMAN_REVIEW_OPEN",
            "CLOSED");
    assertThat(
            Arrays.stream(HearingWorkflowStage.values())
                .filter(HearingWorkflowStage::isPartyWait)
                .toList())
        .containsExactly(
            HearingWorkflowStage.PARTY_ANSWERS_OPEN,
            HearingWorkflowStage.PARTY_EVIDENCE_OPEN);
    assertThat(
            Arrays.stream(HearingWorkflowStage.values())
                .filter(HearingWorkflowStage::requiresAgentRun)
                .map(HearingWorkflowStage::agentOperation)
                .toList())
        .containsExactly(
            "intake_questions",
            "intake_synthesis",
            "evidence_requests",
            "evidence_synthesis",
            "judge_v1",
            "jury_review",
            "judge_v2");
    for (int sequence = 1; sequence < 15; sequence++) {
      assertThat(HearingWorkflowStage.atSequence(sequence).next())
          .isEqualTo(HearingWorkflowStage.atSequence(sequence + 1));
    }
    assertThat(HearingWorkflowStage.CLOSED.next()).isNull();
    assertThatThrownBy(() -> HearingWorkflowStage.atSequence(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HearingWorkflowStage.atSequence(16))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void handoffOperationKeyBindsExactEpochAndJudgeV2Artifact() throws Exception {
    HearingRoomStart start = HearingReceiptTestFactory.start(
        Instant.parse("2026-08-16T06:00:00Z"), Duration.ofMinutes(20));
    String judgeV2Id = "hearing-judge_v2-exact-parent";
    String judgeV2Hash = HearingReceiptTestFactory.hash("judge-v2-exact-parent");

    String operationKey = HearingRoomWorkflowImpl.exactHandoffOperationKey(
        start, judgeV2Id, judgeV2Hash);

    assertThat(operationKey).isEqualTo(HearingOperationKeys.handoff(
        start.tenantSurrogate(), start.caseId(), start.epochId(), start.roomEpoch(),
        judgeV2Id, judgeV2Hash));
    assertThat(operationKey).isNotEqualTo("hearing.handoff:" + start.caseId());
    assertThat(HearingOperationKeys.handoff(
        start.tenantSurrogate(), start.caseId(), "EPOCH_OTHER", start.roomEpoch(),
        judgeV2Id, judgeV2Hash)).isNotEqualTo(operationKey);
    assertThat(HearingRoomWorkflowImpl.exactHandoffOperationKey(
        start, "hearing-judge_v2-other-parent", judgeV2Hash)).isNotEqualTo(operationKey);
    assertThat(HearingRoomWorkflowImpl.exactHandoffOperationKey(
        start, judgeV2Id, HearingReceiptTestFactory.hash("judge-v2-other-hash")))
        .isNotEqualTo(operationKey);

    Started replayable = start("exact-handoff-key", Duration.ofMinutes(20));
    advanceTo(replayable, HearingWorkflowStage.HUMAN_REVIEW_OPEN);
    HearingRoomSnapshot review = replayable.workflow().state();
    replayable.workflow().stageCompleted(
        replayable.receipts().handoff(review, judgeV2Id, judgeV2Hash));
    HearingRoomSnapshot handedOff = awaitState(
        replayable.workflow(), state -> state.handoffReceiptId() != null);
    assertThat(handedOff.status()).isEqualTo("RUNNING");
    assertThat(handedOff.orderedOperationKeys()).contains(operationKey);
    WorkflowReplayer.replayWorkflowExecution(
        client.fetchHistory(replayable.workflowId()), HearingRoomWorkflowImpl.class);
  }

  @Test
  void concurrentPartyCommandsSequenceFromOneStageSnapshotAndReplayFailClosed()
      throws Exception {
    Started concurrent = start("party-command-concurrent", Duration.ofMinutes(20));
    advanceTo(concurrent, HearingWorkflowStage.PARTY_ANSWERS_OPEN);
    HearingRoomSnapshot shared = concurrent.workflow().state();
    sequencingFormalization.setPartyDeadline(shared.stageDeadlineAt());
    HearingPartyCommand initiator = partyCommand(
        concurrent.start(), "CMD_CONCURRENT_I", ActorRole.USER, 1,
        shared.processRevision(), shared.roomRevision());
    HearingPartyCommand respondent = partyCommand(
        concurrent.start(), "CMD_CONCURRENT_R", ActorRole.MERCHANT, 2,
        shared.processRevision(), shared.roomRevision());

    concurrent.workflow().partyCommandAccepted(initiator);
    concurrent.workflow().partyCommandAccepted(respondent);

    HearingRoomSnapshot advanced = awaitStage(
        concurrent.workflow(), HearingWorkflowStage.INTAKE_SYNTHESIZING);
    assertThat(advanced.status()).isEqualTo("RUNNING");
    assertThat(advanced.processRevision()).isEqualTo(shared.processRevision() + 2);
    assertThat(advanced.roomRevision()).isEqualTo(shared.roomRevision() + 2);
    assertThat(advanced.rejectedSignalCount()).isZero();
    concurrent.workflow().partyCommandAccepted(initiator);
    HearingRoomSnapshot replayed = awaitState(
        concurrent.workflow(), state -> state.duplicateSignalCount() == 1);
    assertThat(replayed.processRevision()).isEqualTo(advanced.processRevision());
    assertThat(sequencingFormalization.commandIdsFor(concurrent.workflowId()))
        .containsExactly("CMD_CONCURRENT_I", "CMD_CONCURRENT_R");
    WorkflowReplayer.replayWorkflowExecution(
        client.fetchHistory(concurrent.workflowId()), HearingRoomWorkflowImpl.class);

    Started conflicting = start("party-command-conflict", Duration.ofMinutes(20));
    advanceTo(conflicting, HearingWorkflowStage.PARTY_ANSWERS_OPEN);
    HearingRoomSnapshot conflictSource = conflicting.workflow().state();
    sequencingFormalization.setPartyDeadline(conflictSource.stageDeadlineAt());
    HearingPartyCommand first = partyCommand(
        conflicting.start(), "CMD_CONFLICT_I_1", ActorRole.USER, 1,
        conflictSource.processRevision(), conflictSource.roomRevision());
    HearingPartyCommand sameParticipant = partyCommand(
        conflicting.start(), "CMD_CONFLICT_I_2", ActorRole.USER, 2,
        conflictSource.processRevision(), conflictSource.roomRevision());
    conflicting.workflow().partyCommandAccepted(first);
    awaitState(conflicting.workflow(), state -> state.partyTerminals().size() == 1);
    conflicting.workflow().partyCommandAccepted(sameParticipant);

    HearingRoomSnapshot failed = WorkflowStub.fromTyped(conflicting.workflow())
        .getResult(HearingRoomSnapshot.class);
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.protocolErrorCode()).isEqualTo("HEARING_PARTY_ALREADY_TERMINAL");
    assertThat(sequencingFormalization.commandIdsFor(conflicting.workflowId()))
        .containsExactly("CMD_CONFLICT_I_1");
    WorkflowReplayer.replayWorkflowExecution(
        client.fetchHistory(conflicting.workflowId()), HearingRoomWorkflowImpl.class);
  }

  @Test
  void fullReceiptDrivenFlowPreservesDeadlineAndWaitsForJavaTimeoutReceipt()
      throws Exception {
    Started started = start("full", Duration.ofSeconds(3));
    advanceTo(started, HearingWorkflowStage.PARTY_ANSWERS_OPEN);

    HearingRoomSnapshot answerWait =
        awaitState(started.workflow(), state -> state.stageDeadlineAt() != null);
    Instant answerDeadline = answerWait.stageDeadlineAt();
    started.workflow().partyTerminal(
        started.receipts().party(
            answerWait,
            HearingReceiptTestFactory.INITIATOR,
            "ANSWER_I",
            HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
            false));
    HearingRoomSnapshot afterFirstAnswer = awaitState(
        started.workflow(), state -> state.partyTerminals().size() == 1);
    assertThat(afterFirstAnswer.stageDeadlineAt()).isEqualTo(answerDeadline);
    started.workflow().partyTerminal(
        started.receipts().party(
            afterFirstAnswer,
            HearingReceiptTestFactory.RESPONDENT,
            "ANSWER_R",
            HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
            false));
    HearingRoomSnapshot bothAnswers = awaitState(
        started.workflow(), state -> state.partyTerminals().size() == 2);
    started.workflow().stageCompleted(
        started.receipts().stageCompletion(
            bothAnswers, HearingWorkflowStage.INTAKE_SYNTHESIZING, null));
    awaitStage(started.workflow(), HearingWorkflowStage.INTAKE_SYNTHESIZING);

    advanceTo(started, HearingWorkflowStage.PARTY_EVIDENCE_OPEN);
    HearingRoomSnapshot evidenceWait =
        awaitState(started.workflow(), state -> state.stageDeadlineAt() != null);
    started.workflow().partyTerminal(
        started.receipts().party(
            evidenceWait,
            HearingReceiptTestFactory.INITIATOR,
            "EVIDENCE_I",
            HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
            false));
    environment.sleep(Duration.ofSeconds(4));
    HearingRoomSnapshot expired =
        awaitState(started.workflow(), HearingRoomSnapshot::deadlineReached);
    assertThat(expired.stage()).isEqualTo(HearingWorkflowStage.PARTY_EVIDENCE_OPEN);
    assertThat(expired.timeoutRequiredParticipantIds())
        .containsExactly(HearingReceiptTestFactory.RESPONDENT);
    assertThat(expired.orderedOperationKeys())
        .contains(
            HearingOperationKeys.partyDeadline(
                HearingReceiptTestFactory.TENANT,
                HearingReceiptTestFactory.CASE_ID,
                HearingReceiptTestFactory.ROOM_EPOCH,
                HearingWorkflowStage.PARTY_EVIDENCE_OPEN,
                HearingWorkflowStage.PARTY_EVIDENCE_OPEN.sequence()));
    started.workflow().partyTerminal(
        started.receipts().party(
            expired,
            HearingReceiptTestFactory.RESPONDENT,
            "EVIDENCE_R_TIMEOUT",
            HearingPartyTerminalReceipt.TerminalStatus.AUTO_TIMEOUT,
            false));
    HearingRoomSnapshot bothEvidence = awaitState(
        started.workflow(), state -> state.partyTerminals().size() == 2);
    started.workflow().stageCompleted(
        started.receipts().stageCompletion(
            bothEvidence, HearingWorkflowStage.EVIDENCE_SYNTHESIZING, null));
    awaitStage(started.workflow(), HearingWorkflowStage.EVIDENCE_SYNTHESIZING);

    completeRemaining(started);
    HearingRoomSnapshot result =
        WorkflowStub.fromTyped(started.workflow()).getResult(HearingRoomSnapshot.class);
    assertThat(result.status()).isEqualTo("CLOSED");
    assertThat(result.stage()).isEqualTo(HearingWorkflowStage.CLOSED);
    assertThat(result.stageSequence()).isEqualTo(15);
    assertThat(result.rejectedSignalCount()).isZero();
    assertThat(result.processRevision()).isEqualTo(result.acceptedReceiptCount());
    assertThat(result.roomRevision()).isEqualTo(result.acceptedReceiptCount());
    assertThat(result.pendingReceiptRevisions()).isEmpty();
    assertThat(result.lastReceiptId()).isNotBlank();
    assertThat(result.handoffReceiptId()).isNotBlank();

    WorkflowExecutionHistory history = client.fetchHistory(started.workflowId());
    WorkflowReplayer.replayWorkflowExecution(history, HearingRoomWorkflowImpl.class);
  }

  @Test
  void completedTargetAgentRunReceiptIsPersistedOnlyAtItsExactAgentStage() {
    Started started = start("agent-run-receipt", Duration.ofSeconds(3));
    advanceTo(started, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING);
    HearingRoomSnapshot current = started.workflow().state();
    TargetRoomAgentRunFinalizationReceipt receipt =
        new TargetRoomAgentRunFinalizationReceipt(
            TargetRoomAgentRunFinalizationReceipt.SCHEMA_VERSION,
            current.tenantSurrogate(),
            current.caseId(),
            RoomType.HEARING,
            current.roomEpoch(),
            current.fencingToken(),
            current.processRevision(),
            current.roomRevision(),
            current.stageSequence(),
            "CMD_HEARING_AGENT",
            "RUN_HEARING_AGENT",
            "ATTEMPT_HEARING_AGENT",
            1,
            "a".repeat(64));

    started.workflow().agentRunFinalized(receipt);
    started.workflow().agentRunFinalized(receipt);

    HearingRoomSnapshot observed =
        awaitState(
            started.workflow(), state -> state.agentRunFinalizationReceipts().size() == 1);
    assertThat(observed.agentRunFinalizationReceipts()).containsExactly(receipt);
    assertThat(observed.duplicateSignalCount()).isEqualTo(current.duplicateSignalCount() + 1);
    assertThat(observed.processRevision()).isEqualTo(current.processRevision());
    assertThat(observed.roomRevision()).isEqualTo(current.roomRevision());
  }

  @Test
  void agentResultCannotAdvanceWithoutJavaFinalizerReceipt() {
    Started started = start("agent-order", Duration.ofMinutes(20));
    advanceTo(started, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING);
    HearingRoomSnapshot agentStage = started.workflow().state();
    started.workflow().stageCompleted(started.receipts().agentResult(agentStage));
    HearingRoomSnapshot observed = awaitState(
        started.workflow(), state -> state.agentResultReceiptId() != null);
    assertThat(observed.stage()).isEqualTo(HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING);

    started.workflow().stageCompleted(
        started.receipts().finalizer(
            observed,
            HearingWorkflowStage.PARTY_ANSWERS_OPEN,
            nextPartyDeadline(started.start()),
            "hearing_question_set.v1"));
    assertThat(awaitStage(started.workflow(), HearingWorkflowStage.PARTY_ANSWERS_OPEN).status())
        .isEqualTo("RUNNING");
  }

  @Test
  void agentStageFinalizationRequestRequiresCompletedExactResultAndReplaysByteIdentically()
      throws Exception {
    TargetHearingFormalizationActivities.TransitionRequest transition =
        mock(TargetHearingFormalizationActivities.TransitionRequest.class);
    ExecuteAgentRunRequest request = mock(ExecuteAgentRunRequest.class);
    String logicalRunId = "target-hearing-run:completed-gate";
    String attemptId = logicalRunId + ":1";
    when(request.agentRunId()).thenReturn(logicalRunId);
    when(request.logicalRunId()).thenReturn(logicalRunId);
    when(request.attemptId()).thenReturn(attemptId);
    when(request.attemptNo()).thenReturn(1L);
    when(request.attemptLimit()).thenReturn(3);

    String resultHash = "a".repeat(64);
    RoomGraphResult graph = mock(RoomGraphResult.class);
    when(graph.logicalRunId()).thenReturn(logicalRunId);
    when(graph.attemptId()).thenReturn(attemptId);
    when(graph.outputHash()).thenReturn(resultHash);
    ExecuteAgentRunResult completed = mock(ExecuteAgentRunResult.class);
    when(completed.outcome()).thenReturn(ExecuteAgentRunResult.Outcome.COMPLETED);
    when(completed.agentRunId()).thenReturn(logicalRunId);
    when(completed.logicalRunId()).thenReturn(logicalRunId);
    when(completed.attemptId()).thenReturn(attemptId);
    when(completed.attemptNo()).thenReturn(1L);
    when(completed.graphResult()).thenReturn(graph);
    when(completed.resultHash()).thenReturn(resultHash);

    TargetHearingFormalizationActivities.AgentStageFinalizationRequest first =
        completedAgentStageFinalizationRequest(transition, request, completed);
    TargetHearingFormalizationActivities.AgentStageFinalizationRequest replay =
        completedAgentStageFinalizationRequest(transition, request, completed);
    assertThat(replay).isEqualTo(first);
    assertThat(first.transition()).isSameAs(transition);
    assertThat(first.request()).isSameAs(request);
    assertThat(first.result()).isSameAs(completed);

    ExecuteAgentRunResult terminalFailure = mock(ExecuteAgentRunResult.class);
    when(terminalFailure.outcome()).thenReturn(ExecuteAgentRunResult.Outcome.FAILED);
    when(terminalFailure.errorCode()).thenReturn("GRAPH_STREAM_INTERNAL_ERROR");
    when(terminalFailure.retryable()).thenReturn(false);
    when(terminalFailure.recoveryAction()).thenReturn(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
    when(terminalFailure.agentRunId()).thenReturn("target-hearing-run:terminal-failure");
    when(terminalFailure.attemptId()).thenReturn("target-hearing-run:terminal-failure:1");
    assertThatThrownBy(
            () -> completedAgentStageFinalizationRequest(transition, request, terminalFailure))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            failure -> {
              assertThat(failure.getType()).isEqualTo("GRAPH_STREAM_INTERNAL_ERROR");
              assertThat(failure.isNonRetryable()).isTrue();
            });

    ExecuteAgentRunResult retryableFailure = mock(ExecuteAgentRunResult.class);
    when(retryableFailure.outcome()).thenReturn(ExecuteAgentRunResult.Outcome.FAILED);
    when(retryableFailure.errorCode()).thenReturn("PROVIDER_UNAVAILABLE");
    when(retryableFailure.retryable()).thenReturn(true);
    when(retryableFailure.recoveryAction())
        .thenReturn(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);
    when(retryableFailure.agentRunId()).thenReturn("target-hearing-run:retryable-failure");
    when(retryableFailure.attemptId()).thenReturn("target-hearing-run:retryable-failure:1");
    assertThatThrownBy(
            () -> completedAgentStageFinalizationRequest(transition, request, retryableFailure))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            failure -> {
              assertThat(failure.getType()).isEqualTo("PROVIDER_UNAVAILABLE");
              assertThat(failure.isNonRetryable()).isFalse();
            });

    ExecuteAgentRunResult cancelled = mock(ExecuteAgentRunResult.class);
    when(cancelled.outcome()).thenReturn(ExecuteAgentRunResult.Outcome.CANCELLED);
    when(cancelled.errorCode()).thenReturn("AGENT_RUN_CANCELLED");
    when(cancelled.retryable()).thenReturn(false);
    when(cancelled.recoveryAction()).thenReturn(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
    when(cancelled.agentRunId()).thenReturn("target-hearing-run:cancelled");
    when(cancelled.attemptId()).thenReturn("target-hearing-run:cancelled:1");
    assertThatThrownBy(() -> completedAgentStageFinalizationRequest(transition, request, cancelled))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            failure -> {
              assertThat(failure.getType()).isEqualTo("AGENT_RUN_CANCELLED");
              assertThat(failure.isNonRetryable()).isTrue();
            });

    ExecuteAgentRunResult malformed = mock(ExecuteAgentRunResult.class);
    when(malformed.outcome()).thenReturn(ExecuteAgentRunResult.Outcome.COMPLETED);
    when(malformed.agentRunId()).thenReturn(logicalRunId);
    when(malformed.logicalRunId()).thenReturn(logicalRunId);
    when(malformed.attemptId()).thenReturn(attemptId);
    when(malformed.attemptNo()).thenReturn(1L);
    when(malformed.graphResult()).thenReturn(graph);
    when(malformed.resultHash()).thenReturn("b".repeat(64));
    assertThatThrownBy(() -> completedAgentStageFinalizationRequest(transition, request, malformed))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            failure -> {
              assertThat(failure.getType()).isEqualTo("TARGET_HEARING_AGENT_RESULT_INVALID");
              assertThat(failure.isNonRetryable()).isTrue();
            });
    when(malformed.graphResult()).thenReturn(null);
    when(malformed.resultHash()).thenReturn(null);
    assertThatThrownBy(() -> completedAgentStageFinalizationRequest(transition, request, malformed))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            failure -> {
              assertThat(failure.getType()).isEqualTo("TARGET_HEARING_AGENT_RESULT_INVALID");
              assertThat(failure.isNonRetryable()).isTrue();
            });
  }

  private static TargetHearingFormalizationActivities.AgentStageFinalizationRequest
      completedAgentStageFinalizationRequest(
          TargetHearingFormalizationActivities.TransitionRequest transition,
          ExecuteAgentRunRequest request,
          ExecuteAgentRunResult result) throws Exception {
    Method method = HearingRoomWorkflowImpl.class.getDeclaredMethod(
        "completedAgentStageFinalizationRequest",
        TargetHearingFormalizationActivities.TransitionRequest.class,
        ExecuteAgentRunRequest.class,
        ExecuteAgentRunResult.class);
    method.setAccessible(true);
    try {
      return (TargetHearingFormalizationActivities.AgentStageFinalizationRequest)
          method.invoke(null, transition, request, result);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw failure;
    }
  }

  private Started start(String suffix, Duration partyWindow) {
    Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
    HearingRoomStart start = HearingReceiptTestFactory.start(openedAt, partyWindow);
    HearingReceiptTestFactory receipts = new HearingReceiptTestFactory(start);
    String workflowId = "hearing-room:" + HearingReceiptTestFactory.CASE_ID + ':'
        + HearingReceiptTestFactory.ROOM_EPOCH + ':' + suffix;
    HearingRoomWorkflow workflow = client.newWorkflowStub(
        HearingRoomWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(TASK_QUEUE)
            .build());
    WorkflowClient.start(workflow::run, start);
    awaitStage(workflow, HearingWorkflowStage.COURT_PREPARING);
    return new Started(workflow, workflowId, start, receipts);
  }

  private void advanceTo(Started started, HearingWorkflowStage target) {
    while (started.workflow().state().stage() != target) {
      HearingRoomSnapshot current = started.workflow().state();
      if (current.stage().isPartyWait()) {
        started.workflow().partyTerminal(
            started.receipts().party(
                current,
                HearingReceiptTestFactory.INITIATOR,
                "AUTO_I_" + current.stageSequence(),
                HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
                false));
        HearingRoomSnapshot one = awaitState(
            started.workflow(), state -> state.partyTerminals().size() == 1);
        started.workflow().partyTerminal(
            started.receipts().party(
                one,
                HearingReceiptTestFactory.RESPONDENT,
                "AUTO_R_" + current.stageSequence(),
                HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED,
                false));
        HearingRoomSnapshot both = awaitState(
            started.workflow(), state -> state.partyTerminals().size() == 2);
        started.workflow().stageCompleted(
            started.receipts().stageCompletion(both, current.stage().next(), null));
        awaitState(
            started.workflow(), state -> state.stageSequence() > current.stageSequence());
      } else {
        completeNonWaitStage(started, current);
      }
    }
  }

  private void completeRemaining(Started started) {
    advanceTo(started, HearingWorkflowStage.HUMAN_REVIEW_OPEN);
    HearingRoomSnapshot review = started.workflow().state();
    started.workflow().stageCompleted(
        started.receipts().handoff(
            review,
            "JUDGE_V2_SYNTHETIC",
            HearingReceiptTestFactory.hash("judge-v2")));
    HearingRoomSnapshot handedOff = awaitState(
        started.workflow(), state -> state.handoffReceiptId() != null);
    started.workflow().stageCompleted(started.receipts().close(handedOff));
  }

  private void completeNonWaitStage(Started started, HearingRoomSnapshot current) {
    HearingWorkflowStage next = current.stage().next();
    if (current.stage().requiresAgentRun()) {
      started.workflow().stageCompleted(
          started.receipts().finalizer(
              current,
              next,
              next.isPartyWait() ? nextPartyDeadline(started.start()) : null,
              artifactType(current.stage())));
    } else if (current.stage() == HearingWorkflowStage.DOSSIER_FREEZING) {
      started.workflow().stageCompleted(
          started.receipts().finalizer(
              current, next, null, "trial_dossier.v1"));
    } else {
      started.workflow().stageCompleted(
          started.receipts().stageCompletion(current, next, null));
    }
    awaitState(started.workflow(), state -> state.stageSequence() > current.stageSequence());
  }

  private Instant nextPartyDeadline(HearingRoomStart start) {
    Instant window = Instant.ofEpochMilli(environment.currentTimeMillis())
        .plusSeconds(start.partyStageWindowSeconds());
    return window.isBefore(start.hearingDeadlineAt()) ? window : start.hearingDeadlineAt();
  }

  private static HearingPartyCommand partyCommand(
      HearingRoomStart start,
      String commandId,
      ActorRole role,
      long sequence,
      long expectedProcessRevision,
      long expectedRoomRevision) {
    String participantId = role == ActorRole.USER
        ? start.initiatorParticipantId()
        : start.respondentParticipantId();
    String payloadHash = HearingReceiptTestFactory.hash("payload:" + commandId);
    CaseCommandRef command = new CaseCommandRef(
        "case-command-ref.v1",
        commandId,
        start.tenantSurrogate(),
        start.caseId(),
        sequence,
        CommandType.HEARING_STATEMENT,
        RoomType.HEARING,
        start.roomEpoch(),
        new ActorRef(participantId, role, List.of("hearing:party")),
        new PayloadRef(
            "case-timeline-event.v1",
            "urn:case-timeline-event:" + commandId,
            payloadHash,
            1),
        expectedProcessRevision,
        start.openedAt().plusSeconds(sequence),
        start.hearingDeadlineAt(),
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        HearingReceiptTestFactory.hash("request:" + commandId));
    return new HearingPartyCommand(
        command, start.fencingToken(), expectedProcessRevision, expectedRoomRevision);
  }

  private static String artifactType(HearingWorkflowStage stage) {
    return switch (stage) {
      case INTAKE_QUESTIONS_GENERATING -> "hearing_question_set.v1";
      case INTAKE_SYNTHESIZING -> "hearing_intake_matrix.v1";
      case EVIDENCE_REQUESTS_GENERATING -> "hearing_evidence_request_set.v1";
      case EVIDENCE_SYNTHESIZING -> "hearing_evidence_matrix.v1";
      case JUDGE_V1_GENERATING -> "hearing_judge_v1.v1";
      case JURY_REVIEWING -> "hearing_jury_review.v1";
      case JUDGE_V2_GENERATING -> "hearing_judge_v2.v1";
      default -> throw new IllegalArgumentException("stage has no Agent finalizer artifact");
    };
  }

  static HearingRoomSnapshot awaitStage(
      HearingRoomWorkflow workflow, HearingWorkflowStage expected) {
    return awaitState(workflow, state -> state.stage() == expected);
  }

  static HearingRoomSnapshot awaitState(
      HearingRoomWorkflow workflow, Predicate<HearingRoomSnapshot> predicate) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    HearingRoomSnapshot last = null;
    while (System.nanoTime() < deadline) {
      last = workflow.state();
      if (predicate.test(last)) {
        return last;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("test interrupted", exception);
      }
    }
    throw new AssertionError("workflow state did not reach expected condition: " + last);
  }

  private record Started(
      HearingRoomWorkflow workflow,
      String workflowId,
      HearingRoomStart start,
      HearingReceiptTestFactory receipts) {}

  private static final class SequencingFormalizationActivities
      implements TargetHearingFormalizationActivities {

    private final Map<String, LinkedHashSet<String>> commandIdsByWorkflow =
        new ConcurrentHashMap<>();
    private volatile Instant partyDeadline;

    void setPartyDeadline(Instant partyDeadline) {
      this.partyDeadline = partyDeadline;
    }

    List<String> commandIdsFor(String workflowId) {
      return List.copyOf(commandIdsByWorkflow.getOrDefault(workflowId, new LinkedHashSet<>()));
    }

    @Override
    public PartyResult formalizeParty(PartyRequest request) {
      String workflowId = Activity.getExecutionContext().getInfo().getWorkflowId();
      LinkedHashSet<String> commandIds = commandIdsByWorkflow.computeIfAbsent(
          workflowId, ignored -> new LinkedHashSet<>());
      commandIds.add(request.command().commandId());
      boolean advance = commandIds.size() == 2;
      HearingWorkflowStage source = request.transition().expectedStage();
      HearingWorkflowStage target = advance ? source.next() : source;
      String participantId = request.command().actorRef().actorRole() == ActorRole.USER
          ? request.transition().start().initiatorParticipantId()
          : request.transition().start().respondentParticipantId();
      HearingReceiptTestFactory receipts =
          new HearingReceiptTestFactory(request.transition().start());
      var domainReceipt = receipts.domainReceipt(
          source,
          request.transition().expectedProcessRevision(),
          request.transition().expectedRoomRevision(),
          request.transition().expectedProcessRevision() + 1,
          HearingAuthorityCommit.OperationType.PARTY_TERMINAL,
          request.transition().operationKey(),
          HearingReceiptTestFactory.hash("party-command:" + request.command().commandId()),
          target,
          advance ? null : partyDeadline,
          "party-command");
      return new PartyResult(HearingDomainReceiptAdapter.party(
          domainReceipt,
          request.command().commandId(),
          participantId,
          HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED));
    }

    @Override
    public StageResult bootstrapNext(TransitionRequest request) {
      throw new AssertionError("unexpected bootstrap formalization");
    }

    @Override
    public TimeoutResult formalizeTimeout(TimeoutRequest request) {
      throw new AssertionError("unexpected timeout formalization");
    }

    @Override
    public AgentStagePreparation prepareAgentStage(TransitionRequest request) {
      throw new AssertionError("unexpected Agent stage preparation");
    }

    @Override
    public AgentStageResult finalizeAgentStage(AgentStageFinalizationRequest request) {
      throw new AssertionError("unexpected Agent stage finalization");
    }

    @Override
    public StageResult freezeDossier(TransitionRequest request) {
      throw new AssertionError("unexpected dossier freezing");
    }

    @Override
    public StageResult handoff(TransitionRequest request) {
      throw new AssertionError("unexpected handoff");
    }

    @Override
    public StageResult close(TransitionRequest request) {
      throw new AssertionError("unexpected close");
    }
  }
}
