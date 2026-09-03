package com.example.dispute.workflow.projection.hearing;

import com.example.dispute.hearing.domain.HearingFlowStage;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Stable presentation metadata for the authoritative hearing_flow.v2 stage cursor. */
public final class HearingProjectionContract {

    public static final String FLOW_SCHEMA_VERSION = "hearing_flow.v2";
    public static final String PROJECTION_SCHEMA_VERSION = "hearing-flow-projection.v5";

    private static final List<StageDefinition> STAGES =
            List.of(
                    stage(1, HearingFlowStage.COURT_PREPARING, ProgressGroup.CASE_HANDOFF, "SYSTEM"),
                    stage(
                            2,
                            HearingFlowStage.CASE_INTRODUCTION,
                            ProgressGroup.CASE_HANDOFF,
                            "INTAKE_OFFICER"),
                    stage(
                            3,
                            HearingFlowStage.EVIDENCE_INTRODUCTION,
                            ProgressGroup.CASE_HANDOFF,
                            "EVIDENCE_CLERK"),
                    stage(
                            4,
                            HearingFlowStage.INTAKE_QUESTIONS_GENERATING,
                            ProgressGroup.CASE_CLARIFICATION,
                            "INTAKE_OFFICER"),
                    stage(
                            5,
                            HearingFlowStage.PARTY_ANSWERS_OPEN,
                            ProgressGroup.CASE_CLARIFICATION,
                            "PARTIES"),
                    stage(
                            6,
                            HearingFlowStage.INTAKE_SYNTHESIZING,
                            ProgressGroup.CASE_CLARIFICATION,
                            "INTAKE_OFFICER"),
                    stage(
                            7,
                            HearingFlowStage.EVIDENCE_REQUESTS_GENERATING,
                            ProgressGroup.EVIDENCE_VERIFICATION,
                            "EVIDENCE_CLERK"),
                    stage(
                            8,
                            HearingFlowStage.PARTY_EVIDENCE_OPEN,
                            ProgressGroup.EVIDENCE_VERIFICATION,
                            "PARTIES"),
                    stage(
                            9,
                            HearingFlowStage.EVIDENCE_SYNTHESIZING,
                            ProgressGroup.EVIDENCE_VERIFICATION,
                            "EVIDENCE_CLERK"),
                    stage(
                            10,
                            HearingFlowStage.DOSSIER_FREEZING,
                            ProgressGroup.DOSSIER_FREEZE,
                            "SYSTEM"),
                    stage(
                            11,
                            HearingFlowStage.JUDGE_V1_GENERATING,
                            ProgressGroup.ADJUDICATION_REVIEW,
                            "PRESIDING_JUDGE"),
                    stage(
                            12,
                            HearingFlowStage.JURY_REVIEWING,
                            ProgressGroup.ADJUDICATION_REVIEW,
                            "JURY_PANEL"),
                    stage(
                            13,
                            HearingFlowStage.JUDGE_V2_GENERATING,
                            ProgressGroup.ADJUDICATION_REVIEW,
                            "PRESIDING_JUDGE"),
                    stage(
                            14,
                            HearingFlowStage.HUMAN_REVIEW_OPEN,
                            ProgressGroup.HUMAN_REVIEW,
                            "SYSTEM"),
                    stage(15, HearingFlowStage.CLOSED, ProgressGroup.HUMAN_REVIEW, "SYSTEM"));

    static {
        if (STAGES.size() != HearingFlowStage.values().length
                || !EnumSet.copyOf(STAGES.stream().map(StageDefinition::stageCode).toList())
                        .equals(EnumSet.allOf(HearingFlowStage.class))) {
            throw new IllegalStateException("hearing projection must cover every flow stage once");
        }
    }

    private HearingProjectionContract() {}

    public static List<StageDefinition> stages() {
        return STAGES;
    }

    public static StageDefinition definition(HearingFlowStage stageCode) {
        Objects.requireNonNull(stageCode, "stageCode");
        return STAGES.get(stageCode.ordinal());
    }

    public enum ProgressGroup {
        CASE_HANDOFF,
        CASE_CLARIFICATION,
        EVIDENCE_VERIFICATION,
        DOSSIER_FREEZE,
        ADJUDICATION_REVIEW,
        HUMAN_REVIEW
    }

    public record StageDefinition(
            int sequence,
            HearingFlowStage stageCode,
            ProgressGroup progressGroup,
            String publicOwner,
            boolean partyInput) {

        public StageDefinition {
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            Objects.requireNonNull(stageCode, "stageCode");
            Objects.requireNonNull(progressGroup, "progressGroup");
            if (publicOwner == null || publicOwner.isBlank()) {
                throw new IllegalArgumentException("publicOwner must not be blank");
            }
            if (partyInput != stageCode.hasSharedPartyDeadline()) {
                throw new IllegalArgumentException(
                        "partyInput must match the authoritative shared-deadline stage");
            }
        }
    }

    private static StageDefinition stage(
            int sequence,
            HearingFlowStage stageCode,
            ProgressGroup progressGroup,
            String publicOwner) {
        return new StageDefinition(
                sequence,
                stageCode,
                progressGroup,
                publicOwner,
                stageCode.hasSharedPartyDeadline());
    }
}
