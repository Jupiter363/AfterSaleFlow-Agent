package com.example.dispute.workflow.temporal.room.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HearingRoomParticipantBindingTest {

  private static final Instant OPENED_AT = Instant.parse("2026-08-24T00:00:00Z");

  @Test
  void merchantInitiatedCaseUsesTheAuthenticatedMerchantInsteadOfTheRespondentPosition() {
    HearingRoomStart start = merchantInitiatedStart();
    CaseCommandRef merchant = command("merchant-local", ActorRole.MERCHANT);
    CaseCommandRef user = command("user-local", ActorRole.USER);

    assertThat(HearingRoomWorkflowImpl.participantFor(start, merchant)).isEqualTo("merchant-local");
    assertThat(HearingRoomWorkflowImpl.participantFor(start, user)).isEqualTo("user-local");
    assertThat(HearingOperationKeys.partyTerminal(
            start.tenantSurrogate(), start.caseId(), start.roomEpoch(),
            HearingWorkflowStage.PARTY_ANSWERS_OPEN,
            HearingWorkflowStage.PARTY_ANSWERS_OPEN.sequence(),
            HearingRoomWorkflowImpl.participantFor(start, merchant), merchant.commandId()))
        .contains(":merchant-local:")
        .doesNotContain(":user-local:");
  }

  @Test
  void partyCommandRejectsAnActorOutsideTheBoundCaseParticipants() {
    assertThatThrownBy(
            () -> HearingRoomWorkflowImpl.participantFor(
                merchantInitiatedStart(), command("other-merchant", ActorRole.MERCHANT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a bound participant");
  }

  private static HearingRoomStart merchantInitiatedStart() {
    return new HearingRoomStart(
        "hearing-room-start.v1",
        "TENANT_P9",
        "CASE_P9_MERCHANT_INITIATED",
        "ROOM_P9_HEARING",
        "FLOW_P9_HEARING",
        "EPOCH_P9_HEARING",
        HearingWriterMode.TEMPORAL,
        0,
        1,
        "merchant-local",
        "user-local",
        OPENED_AT,
        OPENED_AT.plusSeconds(3600),
        60,
        18,
        5,
        "hearing-workflow.test.v1");
  }

  private static CaseCommandRef command(String actorId, ActorRole role) {
    return new CaseCommandRef(
        "case-command-ref.v1",
        "CMD_" + actorId.replace('-', '_'),
        "TENANT_P9",
        "CASE_P9_MERCHANT_INITIATED",
        1,
        CommandType.HEARING_ANSWER_BUNDLE,
        RoomType.HEARING,
        0,
        new ActorRef(actorId, role, List.of("hearing:party")),
        new PayloadRef("case-timeline-event.v1", "urn:case-timeline-event:EVENT_P9", "a".repeat(64), 1),
        18,
        OPENED_AT.plusSeconds(1),
        OPENED_AT.plusSeconds(300),
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        "b".repeat(64));
  }
}
