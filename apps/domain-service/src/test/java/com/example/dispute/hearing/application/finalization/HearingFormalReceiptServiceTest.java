package com.example.dispute.hearing.application.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowSubmissionStatus;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.workflow.room.hearing.HearingReceiptTestFactory;
import com.example.dispute.workflow.temporal.room.hearing.HearingOperationKeys;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class HearingFormalReceiptServiceTest {

    @Test
    void mapsCommittedPartyActionToTheExactWorkflowSignal() {
        HearingRoomStart start = HearingReceiptTestFactory.start(
                Instant.parse("2026-07-24T00:00:00Z"), Duration.ofMinutes(20));
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
        HearingDomainReceipt domainReceipt = receipts.domainReceipt(
                HearingWorkflowStage.PARTY_ANSWERS_OPEN,
                4,
                4,
                5,
                HearingAuthorityCommit.OperationType.PARTY_TERMINAL,
                operationKey,
                HearingReceiptTestFactory.hash("party-command"),
                HearingWorkflowStage.PARTY_ANSWERS_OPEN,
                start.openedAt().plus(Duration.ofMinutes(20)),
                "party");
        HearingFormalFinalizer finalizer = mock(HearingFormalFinalizer.class);
        HearingFormalFinalizer.ActionCommand command =
                mock(HearingFormalFinalizer.ActionCommand.class);
        when(command.actionType()).thenReturn(HearingFlowActionType.ANSWER_BUNDLE);
        when(command.requestId()).thenReturn(requestId);
        when(command.participantId()).thenReturn(HearingReceiptTestFactory.INITIATOR);
        when(command.submissionStatus()).thenReturn(HearingFlowSubmissionStatus.SUBMITTED);
        when(finalizer.appendAction(command)).thenReturn(domainReceipt);

        HearingPartyTerminalReceipt signal =
                new HearingFormalReceiptService(finalizer).appendPartyAction(command);

        assertThat(signal.requestId()).isEqualTo(requestId);
        assertThat(signal.participantId()).isEqualTo(HearingReceiptTestFactory.INITIATOR);
        assertThat(signal.terminalStatus())
                .isEqualTo(HearingPartyTerminalReceipt.TerminalStatus.SUBMITTED);
        assertThat(signal.committed().receiptHash()).isEqualTo(domainReceipt.receiptHash());
    }

    @Test
    void remainsDormantWithoutComponentRegistration() {
        assertThat(HearingFormalReceiptService.class.isAnnotationPresent(Component.class)).isFalse();
    }
}
