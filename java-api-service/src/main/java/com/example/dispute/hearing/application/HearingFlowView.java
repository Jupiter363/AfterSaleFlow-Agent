package com.example.dispute.hearing.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Authoritative projection returned by GET /hearing. */
public record HearingFlowView(
        Status status,
        JsonNode questionSet,
        JsonNode evidenceRequestSet,
        Reference trialDossier,
        Map<String, Reference> decisionChain) {

    public record Status(
            String flowSchemaVersion,
            String flowStage,
            String stageCode,
            long stageSequence,
            String stageStatus,
            String flowStatus,
            Instant stageDeadlineAt,
            Instant sharedDeadlineAt,
            Map<String, String> partyStatuses,
            List<ParticipantStatus> participantStatuses,
            boolean reviewGateReady,
            String latestDraftId) {

        public Status {
            partyStatuses = partyStatuses == null ? Map.of() : Map.copyOf(partyStatuses);
            participantStatuses =
                    participantStatuses == null
                            ? List.of()
                            : List.copyOf(participantStatuses);
        }
    }

    public record ParticipantStatus(
            String participantId, String participantRole, String status) {}

    public record Reference(String id, String schemaVersion, String contentHash) {}
}
