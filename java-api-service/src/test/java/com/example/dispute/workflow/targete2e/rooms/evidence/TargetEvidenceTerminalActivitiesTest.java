package com.example.dispute.workflow.targete2e.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TargetEvidenceTerminalActivitiesTest {

  @Test
  void terminalRequestRejectsMissingOrUnfencedPartyCompletionCoordinates() {
    EvidenceRoomStart start = new EvidenceRoomStart(
        "evidence-room-start.v1", "tenant-e2e", "CASE_E2E", "ROOM_EVIDENCE", 4, 9,
        "USER_E2E", "MERCHANT_E2E", Instant.parse("2026-07-28T00:00:00Z"),
        Instant.parse("2026-07-28T01:00:00Z"), 1, 8, 5, "target-e2e-control");

    assertThatThrownBy(() -> new TargetEvidenceTerminalActivities.TerminalRequest(
        start, 10, 7, "", "COMPLETE_MERCHANT"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TargetRoomProgressReceipt(
        null, 4, 9, 11, 8, "receipt", "a".repeat(64)))
        .isInstanceOf(NullPointerException.class);
  }
}
