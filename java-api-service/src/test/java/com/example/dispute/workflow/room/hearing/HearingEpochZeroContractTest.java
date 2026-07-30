package com.example.dispute.workflow.room.hearing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.temporal.room.hearing.HearingCommittedReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HearingEpochZeroContractTest {

  private static final String HASH = "a".repeat(64);

  @Test
  void initialTargetEpochIsValidAcrossStartAndCommittedReceiptContracts() {
    Instant openedAt = Instant.parse("2026-07-30T09:00:00Z");
    assertDoesNotThrow(
        () ->
            new HearingRoomStart(
                "hearing-room-start.v1",
                "tenant-1",
                "case-1",
                "hearing-room-1",
                "hearing-room-1",
                "epoch-1",
                HearingWriterMode.TEMPORAL,
                0,
                9,
                "merchant-1",
                "user-1",
                openedAt,
                openedAt.plusSeconds(3600),
                300,
                0,
                0,
                "hearing-build.v1"));

    assertDoesNotThrow(
        () ->
            new HearingCommittedReceipt(
                HearingCommittedReceipt.SCHEMA_VERSION,
                "receipt-1",
                HASH,
                HearingAuthorityCommit.OperationType.STAGE,
                "hearing.stage:tenant-1:case-1:0:1:COURT_PREPARING",
                HASH,
                "tenant-1",
                "case-1",
                "hearing-room-1",
                "epoch-1",
                0,
                HearingWriterMode.TEMPORAL,
                9,
                HearingWorkflowStage.COURT_PREPARING,
                1,
                0,
                0,
                HearingWorkflowStage.CASE_INTRODUCTION,
                2,
                null,
                1,
                1,
                "urn:hearing:stage:case-introduction",
                HASH,
                1,
                null));
  }
}
