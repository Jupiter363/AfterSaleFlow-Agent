package com.example.dispute.workflow.projection.hearing;

import com.example.dispute.hearing.application.HearingFlowView;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Converts sanitized Hearing facts to the existing HTTP payload without inferring progress. */
@Component
public class HearingProjectionAdapter {

    public HearingFlowView adapt(HearingProjectionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        HearingProjectionContract.StageDefinition definition =
                HearingProjectionContract.definition(snapshot.stageCode());
        if (!HearingProjectionContract.FLOW_SCHEMA_VERSION.equals(snapshot.flowSchemaVersion())) {
            throw new IllegalStateException("unsupported hearing flow schema version");
        }
        if (definition.sequence() != snapshot.stageSequence()) {
            throw new IllegalStateException("hearing stage sequence does not match its stage code");
        }
        if (!definition.partyInput()
                && (!snapshot.partyStatuses().isEmpty()
                        || !snapshot.participantStatuses().isEmpty())) {
            throw new IllegalStateException("party status is authorized only for active party stages");
        }

        HearingFlowView.Status status =
                new HearingFlowView.Status(
                        snapshot.flowSchemaVersion(),
                        snapshot.stageCode().name(),
                        snapshot.stageCode().name(),
                        snapshot.stageSequence(),
                        snapshot.stageStatus(),
                        snapshot.flowStatus(),
                        snapshot.stageDeadlineAt(),
                        snapshot.sharedDeadlineAt(),
                        snapshot.partyStatuses(),
                        snapshot.participantStatuses(),
                        snapshot.reviewGateReady(),
                        snapshot.latestDraftId());
        return new HearingFlowView(
                status,
                snapshot.questionSet(),
                snapshot.evidenceRequestSet(),
                snapshot.trialDossier(),
                snapshot.decisionChain());
    }
}
