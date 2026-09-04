package com.example.dispute.workflow.runtime.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Focused contract proof for ordered evidence scope and replay-stable projections. */
class TargetEvidenceTurnResultV2Test {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String COMMAND_ID = "evidence-command-1";
    private static final String ATTEMPT_ID = "evidence-attempt-1";

    @Test
    void acceptsTheOrderedMaterialScopeAndReplaysItsExactProjection() {
        JsonNode raw = materialResult(true, true, true);
        TargetEvidenceTurnResultV2 first = TargetEvidenceTurnResultV2.parse(MAPPER, raw);
        first.requireCommandBinding(COMMAND_ID, ATTEMPT_ID);
        first.requireFormalScope(
                "PARTY_MESSAGE", List.of("E1", "E2"), Set.of("F1", "F2"));

        TargetEvidenceTurnResultV2 replay = TargetEvidenceTurnResultV2.parse(MAPPER, raw);
        replay.requireCommandBinding(COMMAND_ID, ATTEMPT_ID);
        replay.requireFormalScope(
                "PARTY_MESSAGE", List.of("E1", "E2"), Set.of("F1", "F2"));
        assertThat(replay.document()).isEqualTo(first.document());
        assertThat(replay.roomUtterance()).isEqualTo(first.roomUtterance());
        assertThat(replay.referencedEvidenceIds()).containsExactly("E1", "E2");
    }

    @Test
    void rejectsWrongReceiptAuthorityAndOutOfScopeFactsButTrustsAssessmentCoverage() {
        TargetEvidenceTurnResultV2 ordered = TargetEvidenceTurnResultV2.parse(
                MAPPER, materialResult(true, true, true));
        assertThatThrownBy(() -> ordered.requireFormalScope(
                "PARTY_MESSAGE", List.of("E2", "E1"), Set.of("F1", "F2")))
                .isInstanceOf(IllegalStateException.class);

        TargetEvidenceTurnResultV2 modelSelectedCoverage = TargetEvidenceTurnResultV2.parse(
                MAPPER, materialResult(false, true, true));
        modelSelectedCoverage.requireFormalScope(
                "PARTY_MESSAGE", List.of("E1", "E2"), Set.of("F1", "F2"));

        TargetEvidenceTurnResultV2 outOfScope = TargetEvidenceTurnResultV2.parse(
                MAPPER, materialResult(true, true, true));
        assertThatThrownBy(() -> outOfScope.requireFormalScope(
                "PARTY_MESSAGE", List.of("E1", "E2"), Set.of("F1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsUnrelatedObservationWithoutAssessmentReference() {
        TargetEvidenceTurnResultV2 result = TargetEvidenceTurnResultV2.parse(
                MAPPER, materialResult(false, true, false));

        result.requireFormalScope(
                "PARTY_MESSAGE", List.of("E1", "E2"), Set.of("F1", "F2"));
    }

    @Test
    void acceptsModelDefinedKindsAndMissingSemanticHeaderFields() {
        ObjectNode raw = (ObjectNode) materialResult(true, true, true);
        ArrayNode manifest = (ArrayNode) raw.get("frame_manifest");
        ObjectNode observationFrame = (ObjectNode) manifest.get(1);
        ObjectNode observationHeader = base(2, "EVIDENCE_OBSERVATION");
        observationHeader.put("observation_kind", "PARSED_TEXT");
        observationHeader.put("model_optional_note", "模型自由字段");
        replaceHeader(observationFrame, observationHeader);
        ((ArrayNode) raw.get("observation_graph")).set(0, observationHeader);

        ObjectNode assessmentFrame = (ObjectNode) manifest.get(3);
        ObjectNode assessmentHeader = base(4, "EVIDENCE_ASSESSMENT");
        assessmentHeader.put("evidence_id", "E1");
        assessmentHeader.put("risk_level", "MODEL_DEFINED_LEVEL");
        replaceHeader(assessmentFrame, assessmentHeader);
        ((ArrayNode) raw.get("evidence_assessments")).set(0, assessmentHeader);
        raw.put("frame_manifest_sha256", ContractJson.sha256Hex(manifest));

        TargetEvidenceTurnResultV2 result = TargetEvidenceTurnResultV2.parse(MAPPER, raw);

        result.requireCommandBinding(COMMAND_ID, ATTEMPT_ID);
        result.requireFormalScope(
                "PARTY_MESSAGE", List.of("E1", "E2"), Set.of("F1", "F2"));
        assertThat(result.observationGraph().getFirst().path("observation_kind").asText())
                .isEqualTo("PARSED_TEXT");
        assertThat(result.evidenceAssessments().getFirst().path("authenticity_score").isMissingNode())
                .isTrue();
    }

    @Test
    void bindsMissingReadinessFrameToAnEmptyReplayStableProjection() {
        JsonNode raw = materialResultWithoutReadiness(true);

        TargetEvidenceTurnResultV2 first = TargetEvidenceTurnResultV2.parse(MAPPER, raw);
        first.requireCommandBinding(COMMAND_ID, ATTEMPT_ID);
        first.requireFormalScope(
                "PARTY_MESSAGE", List.of("E1", "E2"), Set.of("F1", "F2"));
        TargetEvidenceTurnResultV2 replay = TargetEvidenceTurnResultV2.parse(MAPPER, raw);

        assertThat(first.roomReadiness()).isEqualTo(MAPPER.createObjectNode());
        assertThat(replay.roomReadiness()).isEqualTo(first.roomReadiness());
        assertThat(replay.document()).isEqualTo(first.document());
        assertThat(first.frames())
                .extracting(TargetEvidenceTurnResultV2.Frame::frameType)
                .doesNotContain("ROOM_READINESS");
    }

    @Test
    void rejectsNonEmptyReadinessProjectionWhenItsSourceFrameIsMissing() {
        assertThatThrownBy(() -> TargetEvidenceTurnResultV2.parse(
                        MAPPER, materialResultWithoutReadiness(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("derived projections differ from the frame manifest");
    }

    private static JsonNode materialResult(
            boolean coverEveryObservation, boolean ordered, boolean secondObservationBound) {
        ArrayNode manifest = MAPPER.createArrayNode();
        manifest.add(frame(1, "MATERIAL_RECEIPT", receiptHeader(1), "已收到两份材料"));
        manifest.add(frame(
                2,
                "EVIDENCE_OBSERVATION",
                observationHeader(2, "O1", "S1", "F1"),
                "观察第一份材料"));
        manifest.add(frame(
                3,
                "EVIDENCE_OBSERVATION",
                observationHeader(3, "O2", "S2", "F2", secondObservationBound),
                "观察第二份材料"));
        manifest.add(frame(
                4,
                "EVIDENCE_ASSESSMENT",
                assessmentHeader(4, ordered ? "E1" : "E2", "O1"),
                "评估第一份材料"));
        manifest.add(frame(
                5,
                "EVIDENCE_ASSESSMENT",
                assessmentHeader(
                        5,
                        ordered ? "E2" : "E1",
                        coverEveryObservation ? "O2" : null),
                "评估第二份材料"));
        manifest.add(frame(6, "EVIDENCE_REQUEST", requestHeader(6), "请补充原始记录"));
        manifest.add(frame(7, "ROOM_READINESS", readinessHeader(7), "本轮核验完成"));

        ObjectNode result = MAPPER.createObjectNode();
        result.put("schema_version", TargetEvidenceTurnResultV2.SCHEMA_VERSION);
        result.put("frame_authority_schema", TargetEvidenceTurnResultV2.FRAME_SCHEMA_VERSION);
        result.set("frame_manifest", manifest);
        result.put("frame_manifest_sha256", ContractJson.sha256Hex(manifest));
        result.put(
                "room_utterance",
                "已收到两份材料\n\n观察第一份材料\n\n观察第二份材料\n\n评估第一份材料"
                        + "\n\n评估第二份材料\n\n请补充原始记录\n\n本轮核验完成");
        result.set("referenced_evidence_ids", array("E1", "E2"));
        result.set(
                "observation_graph",
                MAPPER.createArrayNode()
                        .add(manifest.get(1).get("header"))
                        .add(manifest.get(2).get("header")));
        result.set(
                "evidence_assessments",
                MAPPER.createArrayNode()
                        .add(manifest.get(3).get("header"))
                        .add(manifest.get(4).get("header")));
        result.set("evidence_requests", MAPPER.createArrayNode().add(manifest.get(5).get("header")));
        result.set("room_readiness", manifest.get(6).get("header"));
        return result;
    }

    private static JsonNode materialResultWithoutReadiness(boolean emptyProjection) {
        ObjectNode result = (ObjectNode) materialResult(true, true, true);
        ArrayNode manifest = (ArrayNode) result.get("frame_manifest");
        JsonNode readiness = manifest.get(manifest.size() - 1).get("header").deepCopy();
        manifest.remove(manifest.size() - 1);
        result.put("frame_manifest_sha256", ContractJson.sha256Hex(manifest));
        result.put(
                "room_utterance",
                result.get("room_utterance").textValue().replace("\n\n本轮核验完成", ""));
        result.set(
                "room_readiness",
                emptyProjection ? MAPPER.createObjectNode() : readiness);
        return result;
    }

    private static ObjectNode receiptHeader(int sequence) {
        ObjectNode header = base(sequence, "MATERIAL_RECEIPT");
        header.set("evidence_ids", array("E1", "E2"));
        return header;
    }

    private static ObjectNode observationHeader(
            int sequence, String slot, String sourceUnit, String factId) {
        return observationHeader(sequence, slot, sourceUnit, factId, true);
    }

    private static ObjectNode observationHeader(
            int sequence,
            String slot,
            String sourceUnit,
            String factId,
            boolean bound) {
        ObjectNode header = base(sequence, "EVIDENCE_OBSERVATION");
        header.put("observation_slot", slot);
        header.put("source_unit_id", sourceUnit);
        header.put("binding_status", bound ? "BOUND" : "UNRELATED");
        ArrayNode bindings = MAPPER.createArrayNode();
        if (bound) {
            ObjectNode binding = MAPPER.createObjectNode();
            binding.put("fact_id", factId);
            binding.put("relation", "CONTENT_SUPPORTS");
            binding.put("reason", "材料内容与冻结事实存在明确关联");
            bindings.add(binding);
        }
        header.set("fact_bindings", bindings);
        header.put("observation_kind", "PARSED_RECORD");
        header.put("epistemic_status", "PENDING_VERIFICATION");
        return header;
    }

    private static ObjectNode assessmentHeader(int sequence, String evidenceId, String slot) {
        ObjectNode header = base(sequence, "EVIDENCE_ASSESSMENT");
        header.put("evidence_id", evidenceId);
        if (slot != null) header.set("observation_slots", array(slot));
        header.put("authenticity_score", 0.78);
        header.put("authenticity_score_explanation", "材料来源能够识别，但缺少平台原始导出。");
        header.put("relevance_score", 0.91);
        header.put("relevance_score_explanation", "材料内容直接对应冻结事实。");
        header.put("completeness_score", 0.67);
        header.put("completeness_score_explanation", "主要内容可见，但缺少完整上下文。");
        header.put("assessment_confidence", 0.83);
        header.put("assessment_confidence_explanation", "当前文本清晰，能够完成范围内判断。");
        header.put("risk_level", "MEDIUM");
        header.put("risk_explanation", "来源链仍不完整，综合判断为中风险。");
        header.set("source_basis", array("解析文本"));
        header.put("formation_time_assessment", "形成时间只能部分确认");
        ObjectNode finding = MAPPER.createObjectNode();
        finding.put("finding_type", "PARSED_RECORD");
        finding.put("description", "读取到与冻结事实相关的记录");
        header.set("findings", MAPPER.createArrayNode().add(finding));
        header.set("limitations", array("缺少原始导出来源"));
        header.set("unsupported_claims", array("不能单独确认完整事件链"));
        return header;
    }

    private static ObjectNode requestHeader(int sequence) {
        ObjectNode header = base(sequence, "EVIDENCE_REQUEST");
        header.put("request_slot", "R1");
        header.set("target_fact_ids", array("F1"));
        header.set("gap_codes", MAPPER.createArrayNode());
        header.put("requested_material_kind", "原始记录");
        header.put("priority", "MEDIUM");
        return header;
    }

    private static ObjectNode readinessHeader(int sequence) {
        ObjectNode header = base(sequence, "ROOM_READINESS");
        header.put("core_fact_coverage", "PARTIAL");
        header.put("source_chain_coverage", "PARTIAL");
        header.put("time_integrity_coverage", "PARTIAL");
        header.set("remaining_core_fact_ids", array("F2"));
        header.put("overall_readiness", "PARTIAL");
        return header;
    }

    private static ObjectNode base(int sequence, String frameType) {
        ObjectNode header = MAPPER.createObjectNode();
        header.put("frame_sequence", sequence);
        header.put("frame_type", frameType);
        return header;
    }

    private static ObjectNode frame(
            int sequence, String frameType, ObjectNode header, String publicText) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put(
                "frame_id",
                TargetEvidenceTurnResultV2.frameId(COMMAND_ID, ATTEMPT_ID, sequence, frameType));
        frame.put("frame_sequence", sequence);
        frame.put("frame_type", frameType);
        frame.set("header", header);
        frame.put("header_sha256", ContractJson.sha256Hex(header));
        frame.put("public_text", publicText);
        String text = publicText;
        frame.put("public_text_sha256", sha256(text));
        frame.put("public_text_length", text.codePointCount(0, text.length()));
        ObjectNode preimage = frame.deepCopy();
        frame.put("frame_sha256", ContractJson.sha256Hex(preimage));
        return frame;
    }

    private static void replaceHeader(ObjectNode frame, ObjectNode header) {
        frame.set("header", header);
        frame.put("header_sha256", ContractJson.sha256Hex(header));
        ObjectNode preimage = frame.deepCopy();
        preimage.remove("frame_sha256");
        frame.put("frame_sha256", ContractJson.sha256Hex(preimage));
    }

    private static ArrayNode array(String... values) {
        ArrayNode result = MAPPER.createArrayNode();
        for (String value : values) result.add(value);
        return result;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
