package com.example.dispute.workflow.activity.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.workflow.room.hearing.HearingReceiptTestFactory;
import com.example.dispute.workflow.temporal.room.hearing.HearingCommittedReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingOperationKeys;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HearingDomainReceiptAdapterTest {

  @Test
  void mapsCanonicalDomainReceiptWithoutLosingAuthorityFields() {
    HearingRoomStart start = HearingReceiptTestFactory.start(
        Instant.parse("2026-07-24T00:00:00Z"), Duration.ofMinutes(20));
    HearingReceiptTestFactory receipts = new HearingReceiptTestFactory(start);
    String operationKey = HearingOperationKeys.stageCompletion(
        HearingReceiptTestFactory.TENANT,
        HearingReceiptTestFactory.CASE_ID,
        HearingReceiptTestFactory.ROOM_EPOCH,
        HearingWorkflowStage.COURT_PREPARING,
        1);
    String requestHash = HearingReceiptTestFactory.hash("adapter-stage");
    HearingDomainReceipt domain = receipts.domainReceipt(
        HearingWorkflowStage.COURT_PREPARING,
        7,
        11,
        19,
        HearingAuthorityCommit.OperationType.STAGE,
        operationKey,
        requestHash,
        HearingWorkflowStage.CASE_INTRODUCTION,
        null,
        "adapter");

    HearingStageReceipt stage = HearingDomainReceiptAdapter.stage(domain);
    HearingCommittedReceipt committed = stage.committed();
    assertThat(committed.receiptId()).isEqualTo(domain.receiptId());
    assertThat(committed.receiptHash()).isEqualTo(domain.receiptHash());
    assertThat(committed.operationKey()).isEqualTo(operationKey);
    assertThat(committed.requestHash()).isEqualTo(requestHash);
    assertThat(committed.tenantSurrogate()).isEqualTo(start.tenantSurrogate());
    assertThat(committed.caseId()).isEqualTo(start.caseId());
    assertThat(committed.flowInstanceId()).isEqualTo(start.flowInstanceId());
    assertThat(committed.epochId()).isEqualTo(start.epochId());
    assertThat(committed.sourceStage()).isEqualTo(HearingWorkflowStage.COURT_PREPARING);
    assertThat(committed.stage()).isEqualTo(HearingWorkflowStage.CASE_INTRODUCTION);
    assertThat(committed.sourceProcessRevision()).isEqualTo(7);
    assertThat(committed.processRevision()).isEqualTo(8);
    assertThat(committed.sourceRoomRevision()).isEqualTo(11);
    assertThat(committed.roomRevision()).isEqualTo(12);
    assertThat(committed.committedEventSequence()).isEqualTo(19);
    assertThat(committed.matches(start)).isTrue();
  }

  @Test
  void partyAdapterRequiresExactParticipantBoundOperationKey() {
    Instant openedAt = Instant.parse("2026-07-24T00:00:00Z");
    HearingRoomStart start = HearingReceiptTestFactory.start(openedAt, Duration.ofMinutes(20));
    HearingReceiptTestFactory receipts = new HearingReceiptTestFactory(start);
    String requestId = "ANSWER_REQUEST_1";
    String operationKey = HearingOperationKeys.partyTerminal(
        HearingReceiptTestFactory.TENANT,
        HearingReceiptTestFactory.CASE_ID,
        HearingReceiptTestFactory.ROOM_EPOCH,
        HearingWorkflowStage.PARTY_ANSWERS_OPEN,
        HearingWorkflowStage.PARTY_ANSWERS_OPEN.sequence(),
        HearingReceiptTestFactory.INITIATOR,
        requestId);
    HearingDomainReceipt domain = receipts.domainReceipt(
        HearingWorkflowStage.PARTY_ANSWERS_OPEN,
        4,
        4,
        5,
        HearingAuthorityCommit.OperationType.PARTY_TERMINAL,
        operationKey,
        HearingReceiptTestFactory.hash("adapter-party"),
        HearingWorkflowStage.PARTY_ANSWERS_OPEN,
        openedAt.plus(Duration.ofMinutes(20)),
        "party");

    HearingPartyTerminalReceipt party = HearingDomainReceiptAdapter.party(
        domain,
        requestId,
        HearingReceiptTestFactory.INITIATOR,
        HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED);
    assertThat(party.committed().receiptHash()).isEqualTo(domain.receiptHash());
    assertThat(party.participantId()).isEqualTo(HearingReceiptTestFactory.INITIATOR);
    assertThatThrownBy(() -> HearingDomainReceiptAdapter.stage(domain))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("party terminal");
    assertThatThrownBy(
            () -> HearingDomainReceiptAdapter.party(
                domain,
                requestId,
                HearingReceiptTestFactory.RESPONDENT,
                HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operationKey");
  }

  @Test
  void operationKeyBuildersRejectUnboundedOrAmbiguousComponents() {
    assertThatThrownBy(
            () -> HearingOperationKeys.stageCompletion(
                "TENANT:AMBIGUOUS",
                HearingReceiptTestFactory.CASE_ID,
                HearingReceiptTestFactory.ROOM_EPOCH,
                HearingWorkflowStage.COURT_PREPARING,
                1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> HearingOperationKeys.agent(
                HearingReceiptTestFactory.TENANT,
                HearingReceiptTestFactory.CASE_ID,
                HearingReceiptTestFactory.ROOM_EPOCH,
                4,
                "intake_questions",
                "not-a-hash"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
