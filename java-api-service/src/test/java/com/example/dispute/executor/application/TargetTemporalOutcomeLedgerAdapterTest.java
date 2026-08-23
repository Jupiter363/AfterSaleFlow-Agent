package com.example.dispute.executor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.executor.domain.ledger.OutcomeOperation;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetTemporalOutcomeLedgerAdapterTest {

  @Test
  void targetAdapterUsesItsBoundedVersionInsteadOfTheWorkflowBuildPin() {
    OutcomeOperationLedger ledger = mock(OutcomeOperationLedger.class);
    when(ledger.reserve(any(OutcomeOperation.class), isNull()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Instant now = Instant.parse("2026-08-23T10:00:00Z");
    OutcomeProcessProjection projection = new OutcomeProcessProjection(
        "PROJECTION_1", "legacy-default", "CASE_1", "EPOCH_1", 0,
        OutcomeProcessProjection.WriterMode.TEMPORAL,
        OutcomeProcessProjection.RuntimeMode.TEMPORAL,
        4, 35, 0, "APPROVAL_1", "d".repeat(64), "approved-operation-set", 1,
        OutcomeProcessProjection.ProcessState.DECISION_RECORDED, now, now);
    TargetTemporalOutcomeLedgerAdapter.Binding binding =
        new TargetTemporalOutcomeLedgerAdapter.Binding(
            projection, "PACKET_1", 1, "a".repeat(64), "packet-action-hash",
            "APPROVAL_1", "approval-action-hash", "d".repeat(64), "policy-v1",
            List.of("ACTION_1"));
    String workflowBuildPin = "local-" + "b".repeat(80) + "-control";
    OutcomeOperationCommand command = new OutcomeOperationCommand(
        OutcomeOperationCommand.SCHEMA_VERSION, "OUTCOME_WORKFLOW_1", "CASE_1",
        "COMMAND_1", "OPERATION_1", "1".repeat(64), "APPROVAL_1", "2".repeat(64),
        "review-packet:action", "3".repeat(64), "urn:target-outcome:request:1",
        "4".repeat(64), "5".repeat(64), OutcomeWireTypes.EffectClass.NO_EXTERNAL_EFFECT,
        true, false, 1, 0, 35, 36, 4, 100, 1, now.plusSeconds(300),
        workflowBuildPin, OutcomeWireTypes.RuntimeMode.TEMPORAL, null, false);

    OutcomeOperation reserved =
        new TargetTemporalOutcomeLedgerAdapter(ledger).reserve(command, binding, now);

    assertThat(workflowBuildPin).hasSizeGreaterThan(64);
    assertThat(reserved.adapterId())
        .isEqualTo(TargetTemporalOutcomeLedgerAdapter.ADAPTER_ID);
    assertThat(reserved.adapterVersion())
        .isEqualTo(TargetTemporalOutcomeLedgerAdapter.ADAPTER_VERSION)
        .isEqualTo("v1");
  }
}
