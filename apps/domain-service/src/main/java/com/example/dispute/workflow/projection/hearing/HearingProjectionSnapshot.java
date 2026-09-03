package com.example.dispute.workflow.projection.hearing;

import com.example.dispute.hearing.application.HearingFlowView;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Sanitized read model assembled from Java-owned Hearing facts. */
public record HearingProjectionSnapshot(
        String flowSchemaVersion,
        HearingFlowStage stageCode,
        long stageSequence,
        String stageStatus,
        String flowStatus,
        Instant stageDeadlineAt,
        Instant sharedDeadlineAt,
        Map<String, String> partyStatuses,
        List<HearingFlowView.ParticipantStatus> participantStatuses,
        boolean reviewGateReady,
        String latestDraftId,
        JsonNode questionSet,
        JsonNode evidenceRequestSet,
        HearingFlowView.Reference caseFactMatrix,
        HearingFlowView.Reference issueStateSet,
        HearingFlowView.Reference trialDossier,
        JsonNode juryReviewReport,
        Map<String, HearingFlowView.Reference> decisionChain) {

    public HearingProjectionSnapshot(
            String flowSchemaVersion,
            HearingFlowStage stageCode,
            long stageSequence,
            String stageStatus,
            String flowStatus,
            Instant stageDeadlineAt,
            Instant sharedDeadlineAt,
            Map<String, String> partyStatuses,
            List<HearingFlowView.ParticipantStatus> participantStatuses,
            boolean reviewGateReady,
            String latestDraftId,
            JsonNode questionSet,
            JsonNode evidenceRequestSet,
            HearingFlowView.Reference trialDossier,
            Map<String, HearingFlowView.Reference> decisionChain) {
        this(
                flowSchemaVersion,
                stageCode,
                stageSequence,
                stageStatus,
                flowStatus,
                stageDeadlineAt,
                sharedDeadlineAt,
                partyStatuses,
                participantStatuses,
                reviewGateReady,
                latestDraftId,
                questionSet,
                evidenceRequestSet,
                null,
                null,
                trialDossier,
                null,
                decisionChain);
    }

    public HearingProjectionSnapshot {
        if (flowSchemaVersion == null || flowSchemaVersion.isBlank()) {
            throw new IllegalArgumentException("flowSchemaVersion must not be blank");
        }
        Objects.requireNonNull(stageCode, "stageCode");
        if (stageSequence < 1) {
            throw new IllegalArgumentException("stageSequence must be positive");
        }
        if (stageStatus == null || stageStatus.isBlank()) {
            throw new IllegalArgumentException("stageStatus must not be blank");
        }
        if (flowStatus == null || flowStatus.isBlank()) {
            throw new IllegalArgumentException("flowStatus must not be blank");
        }
        partyStatuses = partyStatuses == null ? Map.of() : Map.copyOf(partyStatuses);
        participantStatuses =
                participantStatuses == null ? List.of() : List.copyOf(participantStatuses);
        questionSet = copy(questionSet);
        evidenceRequestSet = copy(evidenceRequestSet);
        juryReviewReport = copy(juryReviewReport);
        decisionChain = decisionChain == null ? Map.of() : Map.copyOf(decisionChain);
    }

    @Override
    public JsonNode questionSet() {
        return copy(questionSet);
    }

    @Override
    public JsonNode evidenceRequestSet() {
        return copy(evidenceRequestSet);
    }

    @Override
    public JsonNode juryReviewReport() {
        return copy(juryReviewReport);
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
