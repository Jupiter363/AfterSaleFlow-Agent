package com.example.dispute.workflow.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.common.RoomControlSnapshot;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class RoomControlSnapshotCompatibilityTest {

    @Test
    void readsTheOriginalV1SnapshotWithoutRunIdentityFields() throws Exception {
        String legacy =
                """
                {
                  "schemaVersion": "room-control-snapshot.v1",
                  "tenantSurrogate": "tenant-legacy",
                  "caseId": "CASE_LegacySnapshot",
                  "roomType": "EVIDENCE",
                  "roomEpoch": 0,
                  "processedCommandCount": 2,
                  "processedEventCount": 3,
                  "pendingCommandCount": 0,
                  "pendingEventCount": 0,
                  "recentCommandIds": ["command-1", "command-2"],
                  "recentEventIds": ["event-1"],
                  "closeRequested": false,
                  "closeReason": null,
                  "protocolErrorCode": null
                }
                """;

        RoomControlSnapshot snapshot =
                JsonMapper.builder()
                        .build()
                        .readValue(legacy, RoomControlSnapshot.class);

        assertThat(snapshot.workflowRunId()).isNull();
        assertThat(snapshot.runGeneration()).isZero();
        assertThat(snapshot.processedCommandCount()).isEqualTo(2);
        assertThat(snapshot.processedEventCount()).isEqualTo(3);
    }
}
