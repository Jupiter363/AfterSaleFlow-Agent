package com.example.dispute.hearing.application;

import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageSource;
import com.example.dispute.room.domain.MessageType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure public-transcript policy shared by legacy and fenced Temporal Hearing writers.
 *
 * <p>The policy owns presentation order and deterministic public wording only. It never advances a
 * workflow, reads persistence, accepts a model proposal, or grants formal authority.
 */
public final class HearingPublicTranscriptPolicy {

    public static final String SYSTEM_ACTOR = "hearing-flow-v2";

    public List<Draft> deterministicStage(
            HearingFlowStage sourceStage, ObjectNode caseMatrix, JsonNode evidenceMatrix) {
        Objects.requireNonNull(sourceStage, "sourceStage");
        return switch (sourceStage) {
            case COURT_PREPARING ->
                    List.of(
                            system(
                                    HearingFlowStage.COURT_PREPARING,
                                    "prepare",
                                    "法庭正在装载冻结前案情矩阵和证据矩阵。"),
                            template(
                                    HearingFlowStage.COURT_PREPARING,
                                    "judge-opening",
                                    "PRESIDING_JUDGE",
                                    "presiding-judge-template",
                                    "现在开庭。庭前案情与证据材料将依次宣读；本席在庭审卷宗冻结后进入裁决审理。"),
                            system(
                                    HearingFlowStage.COURT_PREPARING,
                                    "prepare-completed",
                                    "前序案情矩阵和证据矩阵已装载。"),
                            system(
                                    HearingFlowStage.CASE_INTRODUCTION,
                                    "case-introduction-next",
                                    "下面请案情接待官介绍庭前案情。"));
            case CASE_INTRODUCTION ->
                    List.of(
                            template(
                                    HearingFlowStage.CASE_INTRODUCTION,
                                    "case-introduction",
                                    "INTAKE_OFFICER",
                                    "intake-officer-template",
                                    caseIntroductionText(requiredObject(caseMatrix, "caseMatrix"))),
                            system(
                                    HearingFlowStage.EVIDENCE_INTRODUCTION,
                                    "evidence-introduction-next",
                                    "下面请证据书记官介绍庭前证据覆盖情况。"));
            case EVIDENCE_INTRODUCTION ->
                    List.of(
                            template(
                                    HearingFlowStage.EVIDENCE_INTRODUCTION,
                                    "evidence-introduction",
                                    "EVIDENCE_CLERK",
                                    "evidence-clerk-template",
                                    evidenceIntroductionText(
                                            requiredObject(caseMatrix, "caseMatrix"),
                                            Objects.requireNonNull(
                                                    evidenceMatrix, "evidenceMatrix"))),
                            system(
                                    HearingFlowStage.INTAKE_QUESTIONS_GENERATING,
                                    "intake-questions-next",
                                    "案情接待官将根据庭前案情矩阵提出澄清问题。"));
            default -> throw new IllegalArgumentException(
                    "stage is not a deterministic Hearing opening stage");
        };
    }

    public List<Draft> agentFinalized(
            HearingFlowStage sourceStage, JsonNode formalOutput, String agentRunId) {
        Objects.requireNonNull(sourceStage, "sourceStage");
        String exactAgentRunId = requiredText(agentRunId, "agentRunId");
        Presentation presentation = presentation(sourceStage);
        List<Draft> drafts = new ArrayList<>();
        drafts.add(
                new Draft(
                        sourceStage,
                        presentation.suffix(),
                        MessageSenderType.AGENT,
                        presentation.role(),
                        presentation.senderId(),
                        MessageSource.AGENT_LLM,
                        presentation.messageType(),
                        formalPublicText(sourceStage, formalOutput),
                        exactAgentRunId));
        Draft notice = nextStageNotice(sourceStage);
        if (notice != null) {
            drafts.add(notice);
        }
        return List.copyOf(drafts);
    }

    public List<Draft> partyStageAdvanced(HearingFlowStage sourceStage) {
        Objects.requireNonNull(sourceStage, "sourceStage");
        return switch (sourceStage) {
            case PARTY_ANSWERS_OPEN ->
                    List.of(
                            system(
                                    HearingFlowStage.INTAKE_SYNTHESIZING,
                                    "intake-synthesis-next",
                                    "双方回答已封存，案情接待官正在综合更新案情矩阵。"));
            case PARTY_EVIDENCE_OPEN ->
                    List.of(
                            system(
                                    HearingFlowStage.EVIDENCE_SYNTHESIZING,
                                    "evidence-synthesis-next",
                                    "双方证据批次已封存，证据书记官正在核验并综合证据矩阵。"));
            default -> throw new IllegalArgumentException("stage is not a Hearing party wait");
        };
    }

    public List<Draft> dossierFrozen() {
        return List.of(
                system(
                        HearingFlowStage.DOSSIER_FREEZING,
                        "dossier-frozen",
                        "庭审卷宗已冻结，后续法官与评审团只读取该不可变版本。"),
                system(
                        HearingFlowStage.JUDGE_V1_GENERATING,
                        "judge-v1-next",
                        "庭审卷宗已交付法官，现进入裁决审理。"));
    }

    public String caseIntroductionText(ObjectNode caseMatrix) {
        List<String> lines = new ArrayList<>();
        lines.add("现宣读庭前双方案情汇总：");
        JsonNode overview = caseMatrix.path("case_overview");
        appendSummaryLine(lines, "案情概览", overview.path("neutral_summary").asText(null));
        appendSummaryLine(lines, "核心争议", overview.path("core_conflict").asText(null));
        JsonNode claims = caseMatrix.path("claims");
        JsonNode initiatorClaim = claims.path("initiator_claim");
        appendSummaryLine(
                lines,
                roleDisplay(initiatorClaim.path("initiator_role").asText()),
                initiatorClaim.path("position_summary").asText(null));
        JsonNode respondentClaim = claims.path("respondent_direct");
        if (respondentClaim.isObject()) {
            appendSummaryLine(
                    lines,
                    roleDisplay(respondentClaim.path("respondent_role").asText()),
                    respondentClaim.path("position_summary").asText(null));
        }
        ArrayNode factRows = array(caseMatrix.path("fact_rows"), "case fact rows");
        if (!factRows.isEmpty()) {
            lines.add("争议事实：");
            int limit = Math.min(factRows.size(), 8);
            for (int index = 0; index < limit; index++) {
                JsonNode row = factRows.get(index);
                String target = nonBlank(row.path("fact_target").asText(null), "待确认事实");
                JsonNode positions = row.path("positions");
                String user = stanceDisplay(positions.path("USER").path("stance").asText());
                String merchant =
                        stanceDisplay(positions.path("MERCHANT").path("stance").asText());
                String resolution =
                        row.path("requires_resolution").asBoolean(false) ? "，待庭审核实" : "";
                lines.add(
                        (index + 1)
                                + ". "
                                + target
                                + "（用户："
                                + user
                                + "；商家："
                                + merchant
                                + resolution
                                + "）");
            }
            if (factRows.size() > limit) {
                lines.add("另有 " + (factRows.size() - limit) + " 项事实已收入案情矩阵。");
            }
        }
        return String.join("\n", lines);
    }

    public String evidenceIntroductionText(ObjectNode caseMatrix, JsonNode evidenceMatrix) {
        Map<String, String> factTargets = new LinkedHashMap<>();
        for (JsonNode row : array(caseMatrix.path("fact_rows"), "case fact rows")) {
            factTargets.put(
                    row.path("fact_id").asText(),
                    nonBlank(row.path("fact_target").asText(null), "待确认事实"));
        }
        ArrayNode coverage =
                array(evidenceMatrix.path("fact_coverage"), "evidence fact coverage");
        long covered = 0;
        long partial = 0;
        long uncovered = 0;
        long review = 0;
        for (JsonNode row : coverage) {
            switch (row.path("coverage_status").asText()) {
                case "COVERED_BY_SUBMITTED_EVIDENCE", "COVERED_BY_FROZEN_DOSSIER" ->
                        covered++;
                case "PARTIALLY_COVERED_BY_FROZEN_DOSSIER" -> partial++;
                case "REQUIRES_HUMAN_REVIEW" -> review++;
                default -> uncovered++;
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("现宣读庭前证据覆盖汇总：");
        lines.add(
                "共核对 "
                        + coverage.size()
                        + " 项事实：已覆盖 "
                        + covered
                        + " 项，部分覆盖 "
                        + partial
                        + " 项，待补充 "
                        + uncovered
                        + " 项，需人工复核 "
                        + review
                        + " 项。");
        int limit = Math.min(coverage.size(), 8);
        for (int index = 0; index < limit; index++) {
            JsonNode row = coverage.get(index);
            String target =
                    nonBlank(
                            factTargets.get(row.path("fact_id").asText()),
                            "第 " + (index + 1) + " 项待确认事实");
            lines.add(
                    (index + 1)
                            + ". "
                            + target
                            + "："
                            + coverageDisplay(row.path("coverage_status").asText()));
        }
        if (coverage.size() > limit) {
            lines.add("另有 " + (coverage.size() - limit) + " 项覆盖情况已收入证据矩阵。");
        }
        return String.join("\n", lines);
    }

    private Draft nextStageNotice(HearingFlowStage sourceStage) {
        return switch (sourceStage) {
            case INTAKE_QUESTIONS_GENERATING ->
                    system(
                            HearingFlowStage.PARTY_ANSWERS_OPEN,
                            "party-answers-open",
                            "案情澄清问题已生成，请双方在统一截止时间前完成回答。");
            case INTAKE_SYNTHESIZING ->
                    system(
                            HearingFlowStage.EVIDENCE_REQUESTS_GENERATING,
                            "evidence-requests-next",
                            "案情矩阵已更新，证据书记官正在生成针对性补证请求。");
            case EVIDENCE_REQUESTS_GENERATING ->
                    system(
                            HearingFlowStage.PARTY_EVIDENCE_OPEN,
                            "party-evidence-open",
                            "补证请求已生成，请双方在统一截止时间前完成证据提交。");
            case EVIDENCE_SYNTHESIZING -> null;
            case JUDGE_V1_GENERATING ->
                    system(
                            HearingFlowStage.JURY_REVIEWING,
                            "jury-review-next",
                            "法官初步裁决意见已形成，现交评审团复核。");
            case JURY_REVIEWING ->
                    system(
                            HearingFlowStage.JUDGE_V2_GENERATING,
                            "judge-v2-next",
                            "评审团复核完成，法官将据此形成最终裁决草案。");
            case JUDGE_V2_GENERATING ->
                    system(
                            HearingFlowStage.HUMAN_REVIEW_OPEN,
                            "human-review-open",
                            "本庭休庭，裁决草案已原样移交人工审核。");
            default -> throw new IllegalArgumentException("stage has no Hearing Agent result");
        };
    }

    private String formalPublicText(HearingFlowStage stage, JsonNode output) {
        Objects.requireNonNull(output, "formalOutput");
        String publicText = firstText(
                output.path("public_message"),
                output.path("public_text"),
                output.path("proposal").path("public_message"));
        if (stage == HearingFlowStage.INTAKE_QUESTIONS_GENERATING) {
            JsonNode questions = output.path("questions");
            if (!questions.isArray()) {
                questions = output.path("proposal").path("questions");
            }
            return appendStructuredItems(publicText, "本轮澄清问题：", questions, "question_text");
        }
        if (stage == HearingFlowStage.EVIDENCE_REQUESTS_GENERATING) {
            JsonNode requests = output.path("requests");
            if (!requests.isArray()) {
                requests = output.path("proposal").path("requests");
            }
            return appendStructuredItems(publicText, "本轮补证要求：", requests, "requested_material");
        }
        return publicText;
    }

    private static String appendStructuredItems(
            String publicText, String heading, JsonNode items, String field) {
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("formal Hearing public items are absent");
        }
        List<String> lines = new ArrayList<>();
        lines.add(publicText);
        lines.add(heading);
        int index = 0;
        for (JsonNode item : items) {
            String text = item.path(field).asText(null);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("formal Hearing public item text is absent");
            }
            lines.add((++index) + ". " + text);
        }
        return String.join("\n", lines);
    }

    private static String firstText(JsonNode... values) {
        for (JsonNode value : values) {
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        throw new IllegalStateException("formal Hearing public text is absent");
    }

    private static Presentation presentation(HearingFlowStage stage) {
        return switch (stage) {
            case INTAKE_QUESTIONS_GENERATING ->
                    new Presentation(
                            "INTAKE_OFFICER", "intake_officer", MessageType.AGENT_MESSAGE,
                            "intake-questions");
            case INTAKE_SYNTHESIZING ->
                    new Presentation(
                            "INTAKE_OFFICER", "intake_officer", MessageType.AGENT_MESSAGE,
                            "intake-synthesis");
            case EVIDENCE_REQUESTS_GENERATING ->
                    new Presentation(
                            "EVIDENCE_CLERK", "evidence_clerk", MessageType.AGENT_MESSAGE,
                            "evidence-requests");
            case EVIDENCE_SYNTHESIZING ->
                    new Presentation(
                            "EVIDENCE_CLERK", "evidence_clerk", MessageType.AGENT_MESSAGE,
                            "evidence-synthesis");
            case JUDGE_V1_GENERATING ->
                    new Presentation(
                            "PRESIDING_JUDGE", "presiding_judge", MessageType.AGENT_MESSAGE,
                            "judge-v1");
            case JURY_REVIEWING ->
                    new Presentation(
                            "JURY_PANEL", "jury_panel", MessageType.JURY_REVIEW_REPORT,
                            "jury-review");
            case JUDGE_V2_GENERATING ->
                    new Presentation(
                            "PRESIDING_JUDGE", "presiding_judge", MessageType.AGENT_MESSAGE,
                            "judge-v2");
            default -> throw new IllegalArgumentException("stage has no Hearing Agent result");
        };
    }

    private static Draft system(HearingFlowStage stage, String suffix, String text) {
        return new Draft(
                stage,
                suffix,
                MessageSenderType.SYSTEM,
                "SYSTEM",
                SYSTEM_ACTOR,
                MessageSource.SYSTEM_STAGE_EVENT,
                MessageType.SYSTEM_STAGE_EVENT,
                text,
                null);
    }

    private static Draft template(
            HearingFlowStage stage,
            String suffix,
            String role,
            String senderId,
            String text) {
        return new Draft(
                stage,
                suffix,
                MessageSenderType.AGENT,
                role,
                senderId,
                MessageSource.ROLE_TEMPLATE,
                MessageType.AGENT_MESSAGE,
                text,
                null);
    }

    private static ObjectNode requiredObject(ObjectNode value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value;
    }

    private static ArrayNode array(JsonNode value, String field) {
        if (!(value instanceof ArrayNode array)) {
            throw new IllegalStateException(field + " must be an array");
        }
        return array;
    }

    private static void appendSummaryLine(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + "：" + value);
        }
    }

    private static String roleDisplay(String role) {
        return "MERCHANT".equals(role) ? "商家主张" : "用户主张";
    }

    private static String stanceDisplay(String stance) {
        return switch (stance) {
            case "CONFIRM", "AGREE", "ACCEPT" -> "确认";
            case "DENY", "DISAGREE", "REJECT" -> "否认";
            case "PARTIAL", "PARTIALLY_AGREE" -> "部分认可";
            default -> "未回应";
        };
    }

    private static String coverageDisplay(String status) {
        return switch (status) {
            case "COVERED_BY_SUBMITTED_EVIDENCE", "COVERED_BY_FROZEN_DOSSIER" ->
                    "已有证据覆盖";
            case "PARTIALLY_COVERED_BY_FROZEN_DOSSIER" -> "部分证据覆盖";
            case "REQUIRES_HUMAN_REVIEW" -> "需人工复核";
            default -> "尚待补充证据";
        };
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public record Draft(
            HearingFlowStage stage,
            String suffix,
            MessageSenderType senderType,
            String senderRole,
            String senderId,
            MessageSource messageSource,
            MessageType messageType,
            String text,
            String agentRunId) {
        public Draft {
            Objects.requireNonNull(stage, "stage");
            suffix = requiredText(suffix, "suffix");
            Objects.requireNonNull(senderType, "senderType");
            senderRole = requiredText(senderRole, "senderRole");
            senderId = requiredText(senderId, "senderId");
            Objects.requireNonNull(messageSource, "messageSource");
            Objects.requireNonNull(messageType, "messageType");
            text = requiredText(text, "text");
            if (agentRunId != null) {
                agentRunId = requiredText(agentRunId, "agentRunId");
            }
        }

        public int stageSequence() {
            return stage.ordinal() + 1;
        }
    }

    private record Presentation(
            String role, String senderId, MessageType messageType, String suffix) {}
}
