package com.example.dispute.workflow.temporal.room.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutcomeReliabilityHarnessTest {

  private static final Instant OPENED_AT = Instant.parse("2026-07-24T08:00:00Z");
  private static final String WORKFLOW_ID = "OUTCOME_P7_RELIABILITY";
  private static final String CASE_ID = "CASE_P7_RELIABILITY";
  private static final long EPOCH = 7;
  private static final long FENCE = 701;

  @Test
  void duplicateAndLostResponseReplaysConvergeOnOneOperationAndOneTerminalFact() {
    Scenario scenario = formalScenario(1);
    approve(scenario, 1, "REVIEWER_A");
    OutcomeWorkflowKernel.OperationCommandReceipt command = command(
        scenario, "COMMAND_1", 1, 1, 1);
    scenario.kernel().submit(command);
    scenario.kernel().submit(command);

    OutcomeWorkflowKernel.OperationCommandReceipt equivalentReplay = command(
        scenario, "COMMAND_1_REPLAY", 1, 2, 1);
    scenario.kernel().submit(equivalentReplay);
    OutcomeWorkflowKernel.OperationReceipt terminal = operationReceipt(
        scenario, 1, 3, OutcomeWorkflowKernel.TerminalStatus.SUCCEEDED);
    scenario.kernel().submit(terminal);
    scenario.kernel().submit(terminal);

    OutcomeWorkflowKernel.Snapshot state = scenario.kernel().snapshot();
    assertThat(state.phase()).isEqualTo(OutcomeWorkflowKernel.Phase.CLOSURE_PENDING);
    assertThat(state.revision()).isEqualTo(4);
    assertThat(state.operations()).hasSize(1);
    assertThat(state.operations().getFirst().successReceiptId()).isEqualTo(terminal.receiptId());
    assertThat(state.duplicateSignalCount()).isEqualTo(2);
    assertThat(state.orderedReceiptIds())
        .containsExactly("DECISION_REVIEWER_A", "COMMAND_1", "COMMAND_1_REPLAY",
            terminal.receiptId());
  }

  @Test
  void actorHashAndFenceSubstitutionAllFailClosed() {
    Scenario actorScenario = shadowScenario(1);
    OutcomeWorkflowKernel.DecisionReceipt actorA = decision(
        actorScenario, 1, 0, "REVIEWER_A", hash("actor-a-request"));
    actorScenario.kernel().submit(actorA);
    OutcomeWorkflowKernel.DecisionReceipt actorBConflict = new OutcomeWorkflowKernel.DecisionReceipt(
        actorScenario.authority(),
        actorA.receiptId(),
        hash("receipt:REVIEWER_B"),
        1,
        2,
        2,
        OutcomeWorkflowKernel.Decision.APPROVE,
        hash("decision-operation"),
        hash("actor-b-request"),
        "ref:approved-action",
        hash("approved-action"),
        "ref:operation-set",
        hash("operation-set:1"),
        1,
        OPENED_AT.plusSeconds(1),
        true);
    actorScenario.kernel().submit(actorBConflict);
    assertThat(actorScenario.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.EXECUTION_INTENT);
    assertThat(actorScenario.kernel().snapshot().rejectedSignalCount()).isEqualTo(1);
    assertThat(actorScenario.kernel().snapshot().protocolErrorCode())
        .isEqualTo("OUTCOME_RECEIPT_ID_PAYLOAD_CONFLICT");

    Scenario hashScenario = formalScenario(1);
    approve(hashScenario, 1, "REVIEWER_A");
    hashScenario.kernel().submit(command(hashScenario, "COMMAND_1", 1, 1, 1));
    hashScenario.kernel().submit(new OutcomeWorkflowKernel.OperationCommandReceipt(
        hashScenario.authority(),
        "COMMAND_HASH_SUBSTITUTION",
        hash("command-hash-substitution"),
        2,
        3,
        3,
        operationId(1),
        operationKey(1),
        hash("substituted-request"),
        idempotencyKey(1),
        1,
        true,
        true,
        1,
        OutcomeWireTypes.RuntimeMode.TEMPORAL,
        false,
        false));
    assertThat(hashScenario.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.FAILED);
    assertThat(hashScenario.kernel().snapshot().protocolErrorCode())
        .isEqualTo("OUTCOME_OPERATION_RETRY_COMMAND_CONFLICT");

    Scenario fenceScenario = shadowScenario(1);
    approve(fenceScenario, 1, "REVIEWER_A");
    OutcomeWorkflowKernel.Authority staleFence = new OutcomeWorkflowKernel.Authority(
        WORKFLOW_ID, CASE_ID, EPOCH, FENCE - 1);
    fenceScenario.kernel().submit(command(
        fenceScenario.withAuthority(staleFence), "COMMAND_STALE_FENCE", 1, 1, 1));
    assertThat(fenceScenario.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.EXECUTION_INTENT);
    assertThat(fenceScenario.kernel().snapshot().revision()).isEqualTo(1);
    assertThat(fenceScenario.kernel().snapshot().protocolErrorCode())
        .isEqualTo("OUTCOME_RECEIPT_AUTHORITY_MISMATCH");
  }

  @Test
  void ambiguousObservationBlocksBlindProgressUntilTypedReconciliationAndRetryCommand() {
    Scenario blindScenario = shadowScenario(1);
    approve(blindScenario, 1, "REVIEWER_A");
    blindScenario.kernel().submit(command(blindScenario, "COMMAND_1", 1, 1, 1));
    blindScenario.kernel().submit(observation(blindScenario, 1, 2));
    assertThat(blindScenario.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.RECONCILING);
    blindScenario.kernel().submit(command(
        blindScenario, "BLIND_RETRY", 1, 3, 2));
    assertThat(blindScenario.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.FAILED);
    assertThat(blindScenario.kernel().snapshot().protocolErrorCode())
        .isEqualTo("OUTCOME_OPERATION_COMMAND_WITHOUT_APPROVAL");

    Scenario recovered = shadowScenario(1);
    approve(recovered, 1, "REVIEWER_A");
    recovered.kernel().submit(command(recovered, "COMMAND_1", 1, 1, 1));
    recovered.kernel().submit(observation(recovered, 1, 2));
    recovered.kernel().submit(reconciliation(
        recovered, 1, 3,
        OutcomeWorkflowKernel.ReconciliationResolution.NOT_FOUND_SAFE_TO_RETRY));
    assertThat(recovered.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.EXECUTING);
    assertThat(recovered.kernel().snapshot().operations().getFirst().retryAuthorized()).isTrue();

    recovered.kernel().submit(command(recovered, "COMMAND_1_RETRY", 1, 4, 2));
    OutcomeWorkflowKernel.Snapshot resolved = recovered.kernel().snapshot();
    assertThat(resolved.phase()).isEqualTo(OutcomeWorkflowKernel.Phase.EXECUTING);
    assertThat(resolved.ambiguousOperationId()).isNull();
    assertThat(resolved.operations().getFirst().attemptNo()).isEqualTo(2);
  }

  @Test
  void javaCommitTimeAndRevisionOrderArbitrateReviewTimeoutWithoutTransportBias() {
    Scenario earlyCommit = shadowScenario(1);
    earlyCommit.kernel().deadlineReached();
    earlyCommit.kernel().submit(decision(
        earlyCommit,
        1,
        0,
        "REVIEWER_EARLY",
        hash("early-request"),
        earlyCommit.start().reviewDeadlineAt().minusMillis(1)));
    assertThat(earlyCommit.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.EXECUTION_INTENT);
    assertThat(earlyCommit.kernel().snapshot().rejectedSignalCount()).isZero();

    Scenario lateCommit = shadowScenario(1);
    lateCommit.kernel().deadlineReached();
    lateCommit.kernel().submit(decision(
        lateCommit,
        1,
        0,
        "REVIEWER_LATE",
        hash("late-request"),
        lateCommit.start().reviewDeadlineAt().plusMillis(1)));
    assertThat(lateCommit.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.WAITING_SLA_ESCALATION);
    assertThat(lateCommit.kernel().snapshot().revision()).isZero();
    assertThat(lateCommit.kernel().snapshot().pendingRevisions()).isEmpty();

    Scenario boundary = shadowScenario(0);
    boundary.kernel().submit(decision(
        boundary,
        0,
        1,
        "REVIEWER_BOUNDARY",
        hash("boundary-request"),
        boundary.start().reviewDeadlineAt()));
    boundary.kernel().submit(sla(boundary, 0, boundary.start().reviewDeadlineAt()));
    boundary.kernel().deadlineReached();
    OutcomeWorkflowKernel.Snapshot revisionWinner = boundary.kernel().snapshot();
    assertThat(revisionWinner.phase()).isEqualTo(OutcomeWorkflowKernel.Phase.SLA_ESCALATED);
    assertThat(revisionWinner.terminalReviewReceiptId()).isEqualTo("SLA_RECEIPT_1");
    assertThat(revisionWinner.revision()).isEqualTo(1);
  }

  @Test
  void compensationUsesStrictReverseSuccessfulEffectOrder() {
    Scenario scenario = formalScenario(3);
    approve(scenario, 3, "REVIEWER_A");
    scenario.kernel().submit(command(scenario, "COMMAND_1", 1, 1, 1));
    scenario.kernel().submit(command(scenario, "COMMAND_2", 2, 2, 1));
    scenario.kernel().submit(command(scenario, "COMMAND_3", 3, 3, 1));
    OutcomeWorkflowKernel.OperationReceipt first = operationReceipt(
        scenario, 1, 4, OutcomeWorkflowKernel.TerminalStatus.SUCCEEDED);
    OutcomeWorkflowKernel.OperationReceipt second = operationReceipt(
        scenario, 2, 5, OutcomeWorkflowKernel.TerminalStatus.SUCCEEDED);
    scenario.kernel().submit(first);
    scenario.kernel().submit(second);
    scenario.kernel().submit(operationReceipt(
        scenario, 3, 6, OutcomeWorkflowKernel.TerminalStatus.FAILED));

    assertThat(scenario.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.COMPENSATING);
    assertThat(scenario.kernel().snapshot().compensationOrder())
        .containsExactly(operationId(2), operationId(1));
    scenario.kernel().submit(compensation(
        scenario.authority(), second, 1, 7, OutcomeWorkflowKernel.TerminalStatus.SUCCEEDED));
    assertThat(scenario.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.COMPENSATING);
    scenario.kernel().submit(compensation(
        scenario.authority(), first, 2, 8, OutcomeWorkflowKernel.TerminalStatus.SUCCEEDED));

    OutcomeWorkflowKernel.Snapshot terminal = scenario.kernel().snapshot();
    assertThat(terminal.phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.MANUAL_RECOVERY_REQUIRED);
    assertThat(terminal.compensationCursor()).isEqualTo(2);
  }

  @Test
  void closureIsBlockedByMissingOrAmbiguousRequiredOperations() {
    Scenario missing = formalScenario(2);
    approve(missing, 2, "REVIEWER_A");
    missing.kernel().submit(command(missing, "COMMAND_1", 1, 1, 1));
    missing.kernel().submit(closure(missing.authority(), 2, "missing"));
    assertThat(missing.kernel().snapshot().phase()).isEqualTo(OutcomeWorkflowKernel.Phase.FAILED);
    assertThat(missing.kernel().snapshot().protocolErrorCode())
        .isEqualTo("OUTCOME_CLOSURE_PREREQUISITES_NOT_SATISFIED");

    Scenario ambiguous = formalScenario(1);
    approve(ambiguous, 1, "REVIEWER_A");
    ambiguous.kernel().submit(command(ambiguous, "COMMAND_1", 1, 1, 1));
    ambiguous.kernel().submit(observation(ambiguous, 1, 2));
    ambiguous.kernel().submit(closure(ambiguous.authority(), 3, "ambiguous"));
    assertThat(ambiguous.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.FAILED);
    assertThat(ambiguous.kernel().snapshot().closedSnapshotRef()).isNull();
  }

  @Test
  void syntheticShadowRejectsEveryFormalEffectAndTerminalReceiptBeforeMutation() {
    Scenario scenario = shadowScenario(1);
    approve(scenario, 1, "REVIEWER_A");
    scenario.kernel().submit(command(scenario, "COMMAND_1", 1, 1, 1));
    OutcomeWorkflowKernel.OperationReceipt formalReceipt = operationReceipt(
        scenario, 1, 2, OutcomeWorkflowKernel.TerminalStatus.SUCCEEDED);
    OutcomeWorkflowKernel.ClosureReceipt closure = closure(scenario.authority(), 2, "shadow");

    scenario.kernel().submit(formalReceipt);
    scenario.kernel().submit(compensation(
        scenario.authority(), formalReceipt, 1, 2,
        OutcomeWorkflowKernel.TerminalStatus.SUCCEEDED));
    scenario.kernel().submit(closure);
    scenario.kernel().submit(evaluation(
        scenario.authority(), 2, closure, OutcomeWorkflowKernel.EvaluationStatus.SUCCEEDED,
        "shadow"));

    OutcomeWorkflowKernel.Snapshot unchanged = scenario.kernel().snapshot();
    assertThat(unchanged.phase()).isEqualTo(OutcomeWorkflowKernel.Phase.EXECUTING);
    assertThat(unchanged.revision()).isEqualTo(2);
    assertThat(unchanged.operations().getFirst().terminalStatus()).isNull();
    assertThat(unchanged.closedSnapshotRef()).isNull();
    assertThat(unchanged.evaluationReceiptId()).isNull();
    assertThat(unchanged.orderedReceiptIds()).containsExactly("DECISION_REVIEWER_A", "COMMAND_1");
    assertThat(unchanged.rejectedSignalCount()).isEqualTo(4);

    Scenario reconciliationScenario = shadowScenario(1);
    approve(reconciliationScenario, 1, "REVIEWER_A");
    reconciliationScenario.kernel().submit(command(
        reconciliationScenario, "COMMAND_1", 1, 1, 1));
    reconciliationScenario.kernel().submit(observation(reconciliationScenario, 1, 2));
    reconciliationScenario.kernel().submit(reconciliation(
        reconciliationScenario,
        1,
        3,
        OutcomeWorkflowKernel.ReconciliationResolution.CONFIRMED_SUCCESS));
    OutcomeWorkflowKernel.Snapshot stillReconciling = reconciliationScenario.kernel().snapshot();
    assertThat(stillReconciling.phase()).isEqualTo(OutcomeWorkflowKernel.Phase.RECONCILING);
    assertThat(stillReconciling.revision()).isEqualTo(3);
    assertThat(stillReconciling.ambiguousOperationId()).isEqualTo(operationId(1));
    assertThat(stillReconciling.protocolErrorCode())
        .isEqualTo("OUTCOME_SHADOW_AUTHORITATIVE_RECONCILIATION_FORBIDDEN");
  }

  @Test
  void evaluationFailurePreservesTheExactClosedSnapshotAndCannotReopen() {
    Scenario scenario = formalScenario(0);
    approve(scenario, 0, "REVIEWER_A");
    OutcomeWorkflowKernel.ClosureReceipt closure = closure(scenario.authority(), 1, "closed");
    scenario.kernel().submit(closure);
    scenario.kernel().submit(evaluation(
        scenario.authority(), 2, closure, OutcomeWorkflowKernel.EvaluationStatus.FAILED,
        "evaluation-failed"));

    OutcomeWorkflowKernel.Snapshot failedEvaluation = scenario.kernel().snapshot();
    assertThat(failedEvaluation.phase()).isEqualTo(OutcomeWorkflowKernel.Phase.CLOSED);
    assertThat(failedEvaluation.closedSnapshotRef()).isEqualTo(closure.closedSnapshotRef());
    assertThat(failedEvaluation.closedSnapshotHash()).isEqualTo(closure.closedSnapshotHash());
    assertThat(failedEvaluation.evaluationFailureCount()).isEqualTo(1);
    assertThat(failedEvaluation.revision()).isEqualTo(3);

    scenario.kernel().submit(evaluation(
        scenario.authority(), 3, closure, OutcomeWorkflowKernel.EvaluationStatus.SUCCEEDED,
        "evaluation-success"));
    assertThat(scenario.kernel().snapshot().phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.EVALUATED);
    assertThat(scenario.kernel().snapshot().closedSnapshotHash())
        .isEqualTo(closure.closedSnapshotHash());
  }

  @Test
  void sixtyFourOperationsWithOneAuthorizedRetryEachReachEvaluationWithinTheBound() {
    Scenario scenario = formalScenario(OutcomeWorkflowKernel.MAX_OPERATIONS);
    approve(scenario, OutcomeWorkflowKernel.MAX_OPERATIONS, "REVIEWER_A");
    long revision = 1;
    for (int operation = 1; operation <= OutcomeWorkflowKernel.MAX_OPERATIONS; operation++) {
      scenario.kernel().submit(command(
          scenario, "COMMAND_" + operation, operation, revision, 1));
      revision++;
    }
    for (int operation = 1; operation <= OutcomeWorkflowKernel.MAX_OPERATIONS; operation++) {
      scenario.kernel().submit(observation(scenario, operation, revision));
      revision++;
      scenario.kernel().submit(reconciliation(
          scenario,
          operation,
          revision,
          OutcomeWorkflowKernel.ReconciliationResolution.NOT_FOUND_SAFE_TO_RETRY));
      revision++;
      scenario.kernel().submit(command(
          scenario, "COMMAND_" + operation + "_RETRY", operation, revision, 2));
      revision++;
      scenario.kernel().submit(observation(scenario, operation, revision));
      revision++;
      scenario.kernel().submit(reconciliation(
          scenario,
          operation,
          revision,
          OutcomeWorkflowKernel.ReconciliationResolution.CONFIRMED_SUCCESS));
      revision++;
    }

    OutcomeWorkflowKernel.ClosureReceipt closure = closure(
        scenario.authority(), revision, "max-success");
    scenario.kernel().submit(closure);
    revision++;
    scenario.kernel().submit(evaluation(
        scenario.authority(),
        revision,
        closure,
        OutcomeWorkflowKernel.EvaluationStatus.SUCCEEDED,
        "max-success"));
    revision++;

    OutcomeWorkflowKernel.Snapshot terminal = scenario.kernel().snapshot();
    assertThat(terminal.phase()).isEqualTo(OutcomeWorkflowKernel.Phase.EVALUATED);
    assertThat(terminal.revision())
        .isEqualTo(OutcomeWorkflowKernel.MAX_ONE_RETRY_SUCCESS_RECEIPTS);
    assertThat(terminal.revision()).isEqualTo(revision);
    assertThat(terminal.orderedReceiptIds())
        .hasSize(OutcomeWorkflowKernel.MAX_ONE_RETRY_SUCCESS_RECEIPTS);
    assertThat(terminal.rejectedSignalCount()).isZero();
  }

  @Test
  void sixtyFourRetriedOperationsAndFullPendingBufferReachExplicitManualRecovery() {
    Scenario scenario = formalScenario(OutcomeWorkflowKernel.MAX_OPERATIONS);
    long futureRevision = 10_000;
    for (int pending = 0; pending < OutcomeWorkflowKernel.MAX_PENDING_RECEIPTS; pending++) {
      scenario.kernel().submit(decision(
          scenario,
          OutcomeWorkflowKernel.MAX_OPERATIONS,
          futureRevision - 1,
          "FUTURE_" + futureRevision,
          hash("future-request:" + futureRevision)));
      futureRevision++;
    }
    approve(scenario, OutcomeWorkflowKernel.MAX_OPERATIONS, "REVIEWER_A");
    long revision = 1;
    for (int operation = 1; operation <= OutcomeWorkflowKernel.MAX_OPERATIONS; operation++) {
      scenario.kernel().submit(command(
          scenario, "COMMAND_" + operation, operation, revision, 1));
      revision++;
    }

    for (int operation = 1; operation <= OutcomeWorkflowKernel.MAX_OPERATIONS; operation++) {
      scenario.kernel().submit(observation(scenario, operation, revision));
      revision++;
      scenario.kernel().submit(reconciliation(
          scenario,
          operation,
          revision,
          OutcomeWorkflowKernel.ReconciliationResolution.NOT_FOUND_SAFE_TO_RETRY));
      revision++;
      scenario.kernel().submit(command(
          scenario, "COMMAND_" + operation + "_RETRY", operation, revision, 2));
      revision++;
      scenario.kernel().submit(observation(scenario, operation, revision));
      revision++;
      OutcomeWorkflowKernel.ReconciliationResolution resolution =
          operation < OutcomeWorkflowKernel.MAX_OPERATIONS
              ? OutcomeWorkflowKernel.ReconciliationResolution.CONFIRMED_SUCCESS
              : OutcomeWorkflowKernel.ReconciliationResolution.CONFIRMED_FAILURE;
      scenario.kernel().submit(reconciliation(scenario, operation, revision, resolution));
      revision++;
    }

    for (int operation = OutcomeWorkflowKernel.MAX_OPERATIONS - 1, reverseOrder = 1;
        operation >= 1;
        operation--, reverseOrder++) {
      scenario.kernel().submit(compensationForReconciledSuccess(
          scenario.authority(),
          operation,
          reverseOrder,
          revision));
      revision++;
    }

    OutcomeWorkflowKernel.Snapshot terminal = scenario.kernel().snapshot();
    assertThat(terminal.phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.MANUAL_RECOVERY_REQUIRED);
    assertThat(terminal.revision())
        .isEqualTo(OutcomeWorkflowKernel.MAX_ONE_RETRY_MANUAL_RECEIPTS);
    assertThat(terminal.revision()).isEqualTo(revision);
    assertThat(terminal.orderedReceiptIds())
        .hasSize(OutcomeWorkflowKernel.MAX_ONE_RETRY_MANUAL_RECEIPTS);
    assertThat(terminal.pendingRevisions()).hasSize(OutcomeWorkflowKernel.MAX_PENDING_RECEIPTS);
    assertThat(terminal.compensationCursor()).isEqualTo(63);
    assertThat(terminal.rejectedSignalCount()).isZero();
  }

  @Test
  void exactCausalFactsBypassTheSoftBudgetButStopAtTheHardLifetimeBound() {
    Scenario scenario = formalScenario(1);
    approve(scenario, 1, "REVIEWER_A");
    OutcomeWorkflowKernel.OperationCommandReceipt last = command(
        scenario, "COMMAND_1", 1, 1, 1);
    scenario.kernel().submit(last);
    for (long revision = 2; revision < OutcomeWorkflowKernel.MAX_CAUSAL_RECEIPTS; revision++) {
      last = command(scenario, "COMMAND_REPLAY_" + revision, 1, revision, 1);
      scenario.kernel().submit(last);
    }
    assertThat(scenario.kernel().snapshot().revision())
        .isEqualTo(OutcomeWorkflowKernel.MAX_CAUSAL_RECEIPTS);

    scenario.kernel().submit(command(
        scenario,
        "COMMAND_EXCEEDS_LIFETIME_BOUND",
        1,
        OutcomeWorkflowKernel.MAX_CAUSAL_RECEIPTS,
        1));
    scenario.kernel().submit(last);

    OutcomeWorkflowKernel.Snapshot terminal = scenario.kernel().snapshot();
    assertThat(terminal.phase())
        .isEqualTo(OutcomeWorkflowKernel.Phase.MANUAL_RECOVERY_REQUIRED);
    assertThat(terminal.protocolErrorCode()).isEqualTo("OUTCOME_CAUSAL_RECEIPT_LIMIT");
    assertThat(terminal.revision()).isEqualTo(OutcomeWorkflowKernel.MAX_CAUSAL_RECEIPTS);
    assertThat(terminal.orderedReceiptIds())
        .hasSize(OutcomeWorkflowKernel.MAX_CAUSAL_RECEIPTS);
    assertThat(terminal.duplicateSignalCount()).isEqualTo(1);
  }

  private static Scenario shadowScenario(int requiredOperationCount) {
    return scenario(
        requiredOperationCount,
        OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW,
        true);
  }

  private static Scenario formalScenario(int requiredOperationCount) {
    return scenario(requiredOperationCount, OutcomeWireTypes.RuntimeMode.TEMPORAL, false);
  }

  private static Scenario scenario(
      int requiredOperationCount,
      OutcomeWireTypes.RuntimeMode runtimeMode,
      boolean syntheticOnly) {
    OutcomeWorkflowStart start = start(requiredOperationCount, runtimeMode, syntheticOnly);
    OutcomeWorkflowKernel.Authority authority = new OutcomeWorkflowKernel.Authority(
        start.workflowId(), start.caseId(), start.epoch(), start.fence());
    OutcomeWorkflowKernel kernel = new OutcomeWorkflowKernel(new OutcomeWorkflowKernel.Start(
        start.workflowId(), start.caseId(), start.epoch(), start.fence(), start.revision(),
        OPENED_AT, start.reviewDeadlineAt(), start.runtimeMode(), start.syntheticOnly()));
    return new Scenario(start, authority, kernel);
  }

  private static OutcomeWorkflowStart start(
      int requiredOperationCount,
      OutcomeWireTypes.RuntimeMode runtimeMode,
      boolean syntheticOnly) {
    return new OutcomeWorkflowStart(
        OutcomeWorkflowStart.SCHEMA_VERSION,
        WORKFLOW_ID,
        CASE_ID,
        "REVIEW_TASK_P7_RELIABILITY",
        "ref:packet:p7:reliability",
        hash("packet"),
        "ref:draft:p7:reliability",
        hash("draft"),
        "ref:action:p7:reliability",
        hash("action"),
        "ref:operation-set:p7:reliability",
        hash("operation-set:" + requiredOperationCount),
        requiredOperationCount,
        EPOCH,
        0,
        FENCE,
        OPENED_AT,
        OPENED_AT.plusSeconds(60),
        runtimeMode,
        "outcome_workflow_v1",
        "outcome_policy_v1",
        "outcome_graph_v1",
        "outcome_prompt_v1",
        "outcome_model_v1",
        syntheticOnly);
  }

  private static void approve(
      Scenario scenario, int requiredOperationCount, String actor) {
    scenario.kernel().submit(decision(
        scenario, requiredOperationCount, 0, actor, hash("decision-request:" + actor)));
  }

  private static OutcomeWorkflowKernel.DecisionReceipt decision(
      Scenario scenario,
      int requiredOperationCount,
      long sourceRevision,
      String actor,
      String requestHash) {
    return decision(
        scenario, requiredOperationCount, sourceRevision, actor, requestHash,
        OPENED_AT.plusSeconds(1));
  }

  private static OutcomeWorkflowKernel.DecisionReceipt decision(
      Scenario scenario,
      int requiredOperationCount,
      long sourceRevision,
      String actor,
      String requestHash,
      Instant committedAt) {
    return new OutcomeWorkflowKernel.DecisionReceipt(
        scenario.authority(),
        "DECISION_" + actor,
        hash("receipt:" + actor),
        sourceRevision,
        sourceRevision + 1,
        sourceRevision + 1,
        OutcomeWorkflowKernel.Decision.APPROVE,
        hash("decision-operation"),
        requestHash,
        "ref:approved-action",
        hash("approved-action"),
        "ref:operation-set",
        hash("operation-set:" + requiredOperationCount),
        requiredOperationCount,
        committedAt,
        scenario.start().syntheticOnly());
  }

  private static OutcomeWorkflowKernel.SlaReceipt sla(
      Scenario scenario, long sourceRevision, Instant committedAt) {
    return new OutcomeWorkflowKernel.SlaReceipt(
        scenario.authority(),
        "SLA_RECEIPT_" + (sourceRevision + 1),
        hash("sla-receipt:" + (sourceRevision + 1)),
        sourceRevision,
        sourceRevision + 1,
        sourceRevision + 1,
        scenario.start().reviewDeadlineAt(),
        committedAt,
        scenario.start().syntheticOnly());
  }

  private static OutcomeWorkflowKernel.OperationCommandReceipt command(
      Scenario scenario,
      String commandId,
      int operationSequence,
      long sourceRevision,
      long attemptNo) {
    return new OutcomeWorkflowKernel.OperationCommandReceipt(
        scenario.authority(),
        commandId,
        hash("command:" + commandId),
        sourceRevision,
        sourceRevision + 1,
        sourceRevision + 1,
        operationId(operationSequence),
        operationKey(operationSequence),
        requestHash(operationSequence),
        idempotencyKey(operationSequence),
        operationSequence,
        true,
        !scenario.start().syntheticOnly(),
        attemptNo,
        scenario.start().runtimeMode(),
        scenario.start().syntheticOnly(),
        scenario.start().syntheticOnly());
  }

  private static OutcomeWorkflowKernel.OperationReceipt operationReceipt(
      Scenario scenario,
      int operationSequence,
      long sourceRevision,
      OutcomeWorkflowKernel.TerminalStatus status) {
    String receiptId = "OPERATION_RECEIPT_" + operationSequence + '_' + (sourceRevision + 1);
    return new OutcomeWorkflowKernel.OperationReceipt(
        scenario.authority(),
        receiptId,
        hash("receipt:" + receiptId),
        sourceRevision,
        sourceRevision + 1,
        sourceRevision + 1,
        operationId(operationSequence),
        operationKey(operationSequence),
        requestHash(operationSequence),
        idempotencyKey(operationSequence),
        operationSequence,
        true,
        !scenario.start().syntheticOnly(),
        status,
        "ref:operation-result:" + operationSequence,
        hash("operation-result:" + receiptId),
        OutcomeWireTypes.RuntimeMode.TEMPORAL,
        false);
  }

  private static OutcomeWorkflowKernel.AttemptObservationReceipt observation(
      Scenario scenario, int operationSequence, long sourceRevision) {
    String observationId = "OBSERVATION_" + operationSequence + '_' + (sourceRevision + 1);
    return new OutcomeWorkflowKernel.AttemptObservationReceipt(
        scenario.authority(),
        observationId,
        hash("observation:" + observationId),
        sourceRevision,
        sourceRevision + 1,
        sourceRevision + 1,
        operationId(operationSequence),
        operationKey(operationSequence),
        requestHash(operationSequence),
        idempotencyKey(operationSequence),
        operationSequence,
        true,
        !scenario.start().syntheticOnly(),
        observationId,
        hash("observation:" + observationId));
  }

  private static OutcomeWorkflowKernel.ReconciliationReceipt reconciliation(
      Scenario scenario,
      int operationSequence,
      long sourceRevision,
      OutcomeWorkflowKernel.ReconciliationResolution resolution) {
    String receiptId = "RECONCILIATION_" + operationSequence + '_' + (sourceRevision + 1);
    String observationId = "OBSERVATION_" + operationSequence + '_' + sourceRevision;
    boolean confirmed = resolution == OutcomeWorkflowKernel.ReconciliationResolution.CONFIRMED_SUCCESS
        || resolution == OutcomeWorkflowKernel.ReconciliationResolution.CONFIRMED_FAILURE;
    return new OutcomeWorkflowKernel.ReconciliationReceipt(
        scenario.authority(),
        receiptId,
        hash("receipt:" + receiptId),
        sourceRevision,
        sourceRevision + 1,
        sourceRevision + 1,
        operationId(operationSequence),
        operationKey(operationSequence),
        requestHash(operationSequence),
        idempotencyKey(operationSequence),
        operationSequence,
        true,
        !scenario.start().syntheticOnly(),
        observationId,
        resolution,
        confirmed ? "SUCCESS_RECEIPT_" + operationSequence : null,
        confirmed ? hash("success-receipt:" + operationSequence) : null);
  }

  private static OutcomeWorkflowKernel.CompensationReceipt compensation(
      OutcomeWorkflowKernel.Authority authority,
      OutcomeWorkflowKernel.OperationReceipt parent,
      long reverseOrder,
      long sourceRevision,
      OutcomeWorkflowKernel.TerminalStatus status) {
    String receiptId = "COMPENSATION_RECEIPT_" + reverseOrder;
    return new OutcomeWorkflowKernel.CompensationReceipt(
        authority,
        receiptId,
        hash("receipt:" + receiptId),
        sourceRevision,
        sourceRevision + 1,
        sourceRevision + 1,
        "COMPENSATION_OPERATION_" + reverseOrder,
        hash("compensation-request:" + reverseOrder),
        parent.operationId(),
        parent.receiptId(),
        parent.receiptHash(),
        reverseOrder,
        status,
        receiptId,
        hash("receipt:" + receiptId));
  }

  private static OutcomeWorkflowKernel.CompensationReceipt compensationForReconciledSuccess(
      OutcomeWorkflowKernel.Authority authority,
      int operationSequence,
      long reverseOrder,
      long sourceRevision) {
    String receiptId = "RETRY_COMPENSATION_RECEIPT_" + reverseOrder;
    return new OutcomeWorkflowKernel.CompensationReceipt(
        authority,
        receiptId,
        hash("receipt:" + receiptId),
        sourceRevision,
        sourceRevision + 1,
        sourceRevision + 1,
        "RETRY_COMPENSATION_OPERATION_" + reverseOrder,
        hash("retry-compensation-request:" + reverseOrder),
        operationId(operationSequence),
        "SUCCESS_RECEIPT_" + operationSequence,
        hash("success-receipt:" + operationSequence),
        reverseOrder,
        OutcomeWorkflowKernel.TerminalStatus.SUCCEEDED,
        receiptId,
        hash("receipt:" + receiptId));
  }

  private static OutcomeWorkflowKernel.ClosureReceipt closure(
      OutcomeWorkflowKernel.Authority authority, long sourceRevision, String suffix) {
    return new OutcomeWorkflowKernel.ClosureReceipt(
        authority,
        "CLOSURE_RECEIPT_" + suffix,
        hash("closure-receipt:" + suffix),
        sourceRevision,
        sourceRevision + 1,
        sourceRevision + 1,
        "ref:closed-snapshot:" + suffix,
        hash("closed-snapshot:" + suffix));
  }

  private static OutcomeWorkflowKernel.EvaluationReceipt evaluation(
      OutcomeWorkflowKernel.Authority authority,
      long sourceRevision,
      OutcomeWorkflowKernel.ClosureReceipt closure,
      OutcomeWorkflowKernel.EvaluationStatus status,
      String suffix) {
    return new OutcomeWorkflowKernel.EvaluationReceipt(
        authority,
        "EVALUATION_RECEIPT_" + suffix,
        hash("evaluation-receipt:" + suffix),
        sourceRevision,
        sourceRevision + 1,
        sourceRevision + 1,
        closure.closedSnapshotRef(),
        closure.closedSnapshotHash(),
        status,
        "ref:evaluation:" + suffix,
        hash("evaluation:" + suffix));
  }

  private static String operationId(int sequence) {
    return "OPERATION_" + sequence;
  }

  private static String operationKey(int sequence) {
    return hash("operation-key:" + sequence);
  }

  private static String requestHash(int sequence) {
    return hash("operation-request:" + sequence);
  }

  private static String idempotencyKey(int sequence) {
    return hash("idempotency-key:" + sequence);
  }

  private static String hash(String value) {
    return OutcomeReceiptTestFactory.hash(value);
  }

  private record Scenario(
      OutcomeWorkflowStart start,
      OutcomeWorkflowKernel.Authority authority,
      OutcomeWorkflowKernel kernel) {

    private Scenario withAuthority(OutcomeWorkflowKernel.Authority replacement) {
      return new Scenario(start, replacement, kernel);
    }
  }
}
