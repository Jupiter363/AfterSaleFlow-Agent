package com.example.dispute.hearing.application;

import com.example.dispute.hearing.domain.HearingFlowStage;

/** Pure mappings for the persisted hearing stage machine. */
final class HearingFlowStagePlan {

    private HearingFlowStagePlan() {}

    static HearingFlowStage stageForOperation(String operation) {
        return switch (operation) {
            case "HEARING_INTAKE_QUESTIONS" -> HearingFlowStage.INTAKE_QUESTIONS_GENERATING;
            case "HEARING_INTAKE_SYNTHESIS" -> HearingFlowStage.INTAKE_SYNTHESIZING;
            case "HEARING_EVIDENCE_REQUESTS" -> HearingFlowStage.EVIDENCE_REQUESTS_GENERATING;
            case "HEARING_EVIDENCE_SYNTHESIS" -> HearingFlowStage.EVIDENCE_SYNTHESIZING;
            case "HEARING_JUDGE_V1" -> HearingFlowStage.JUDGE_V1_GENERATING;
            case "HEARING_JURY_REVIEW" -> HearingFlowStage.JURY_REVIEWING;
            case "HEARING_JUDGE_V2" -> HearingFlowStage.JUDGE_V2_GENERATING;
            default -> throw new IllegalArgumentException("unsupported hearing flow operation");
        };
    }

    static String operationForStage(HearingFlowStage stage) {
        return switch (stage) {
            case INTAKE_QUESTIONS_GENERATING -> "HEARING_INTAKE_QUESTIONS";
            case INTAKE_SYNTHESIZING -> "HEARING_INTAKE_SYNTHESIS";
            case EVIDENCE_REQUESTS_GENERATING -> "HEARING_EVIDENCE_REQUESTS";
            case EVIDENCE_SYNTHESIZING -> "HEARING_EVIDENCE_SYNTHESIS";
            case JUDGE_V1_GENERATING -> "HEARING_JUDGE_V1";
            case JURY_REVIEWING -> "HEARING_JURY_REVIEW";
            case JUDGE_V2_GENERATING -> "HEARING_JUDGE_V2";
            default -> null;
        };
    }

    static HearingFlowStage nextStage(HearingFlowStage current) {
        return switch (current) {
            case COURT_PREPARING -> HearingFlowStage.CASE_INTRODUCTION;
            case CASE_INTRODUCTION -> HearingFlowStage.EVIDENCE_INTRODUCTION;
            case EVIDENCE_INTRODUCTION -> HearingFlowStage.INTAKE_QUESTIONS_GENERATING;
            case INTAKE_QUESTIONS_GENERATING -> HearingFlowStage.PARTY_ANSWERS_OPEN;
            case PARTY_ANSWERS_OPEN -> HearingFlowStage.INTAKE_SYNTHESIZING;
            case INTAKE_SYNTHESIZING -> HearingFlowStage.EVIDENCE_REQUESTS_GENERATING;
            case EVIDENCE_REQUESTS_GENERATING -> HearingFlowStage.PARTY_EVIDENCE_OPEN;
            case PARTY_EVIDENCE_OPEN -> HearingFlowStage.EVIDENCE_SYNTHESIZING;
            case EVIDENCE_SYNTHESIZING -> HearingFlowStage.DOSSIER_FREEZING;
            case DOSSIER_FREEZING -> HearingFlowStage.JUDGE_V1_GENERATING;
            case JUDGE_V1_GENERATING -> HearingFlowStage.JURY_REVIEWING;
            case JURY_REVIEWING -> HearingFlowStage.JUDGE_V2_GENERATING;
            case JUDGE_V2_GENERATING -> HearingFlowStage.HUMAN_REVIEW_OPEN;
            case HUMAN_REVIEW_OPEN -> HearingFlowStage.CLOSED;
            case CLOSED -> throw new IllegalStateException("closed hearing flow has no successor");
        };
    }

    static String processorRole(HearingFlowStage stage) {
        return switch (stage) {
            case CASE_INTRODUCTION, INTAKE_QUESTIONS_GENERATING, INTAKE_SYNTHESIZING ->
                    "INTAKE_OFFICER";
            case EVIDENCE_INTRODUCTION,
                    EVIDENCE_REQUESTS_GENERATING,
                    EVIDENCE_SYNTHESIZING -> "EVIDENCE_CLERK";
            case PARTY_ANSWERS_OPEN, PARTY_EVIDENCE_OPEN -> "PARTIES";
            case JUDGE_V1_GENERATING, JUDGE_V2_GENERATING -> "PRESIDING_JUDGE";
            case JURY_REVIEWING -> "JURY_PANEL";
            case COURT_PREPARING, DOSSIER_FREEZING, HUMAN_REVIEW_OPEN, CLOSED -> "SYSTEM";
        };
    }

    static boolean isJudgeOperation(String operation) {
        return "HEARING_JUDGE_V1".equals(operation) || "HEARING_JUDGE_V2".equals(operation);
    }
}
