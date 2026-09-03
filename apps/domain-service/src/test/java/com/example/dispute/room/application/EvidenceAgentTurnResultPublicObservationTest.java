package com.example.dispute.room.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EvidenceAgentTurnResultPublicObservationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void typedPublicObservationSurvivesExactFormalResultRoundTrip() throws Exception {
        JsonNode source = objectMapper.readTree(
                """
                {
                  "room_utterance":"我会先核验本轮材料与案情的关联。材料所载“退款工单未生成”，可供后续核对。",
                  "memory_patch":{},
                  "canvas_operations":[],
                  "referenced_evidence_ids":["EVIDENCE_1"],
                  "verification_suggestions":[],
                  "authenticity_flags":[],
                  "public_observations":[{
                    "schema_version":"public_evidence_observation.v1",
                    "provider_slot_id":"OBS_01",
                    "observation_id":"PUBOBS_0123456789ABCDEF01234567",
                    "evidence_id":"EVIDENCE_1",
                    "fact_id":"FACT_REFUND_RECORD",
                    "observation_kind":"PARSED_TRANSACTION_STATUS",
                    "epistemic_status":"PROVISIONAL",
                    "parsed_content_sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "source_quote":"退款工单未生成",
                    "public_text":"材料所载“退款工单未生成”，可供后续核对。",
                    "source_start_byte":18,
                    "source_end_byte":42,
                    "quote_sha256":"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                  }],
                  "evidence_assessments":[{
                    "evidence_id":"EVIDENCE_1",
                    "public_observation_slots":[],
                    "public_observation_ids":["PUBOBS_0123456789ABCDEF01234567"],
                    "analysis_method":"TEXT_ONLY",
                    "inspected_modalities":["PARSED_TEXT"],
                    "fact_links":[{"fact_id":"FACT_REFUND_RECORD","relation":"INCONCLUSIVE"}],
                    "authenticity_score":0.5,
                    "relevance_score":0.8,
                    "completeness_score":0.6,
                    "assessment_confidence":0.7,
                    "source_basis":["冻结解析正文"],
                    "supported_fact_ids":[],
                    "unsupported_claims":[],
                    "formation_time_assessment":"形成时间仍待核验。",
                    "findings":[],
                    "limitations":["当前仅核对可读取文本。"],
                    "risk_flags":[],
                    "recommendation":"PLAUSIBLE",
                    "human_review":{"required":false,"reason_codes":[],"instructions":[]},
                    "asset_audit":{},
                    "summary":"材料记录可供后续核对。"
                  }],
                  "fact_matrix_patch":[],
                  "human_review_tasks":[],
                  "internal_handoff":{},
                  "liability_determined":false,
                  "remedy_recommended":false,
                  "knowledge_answer_mode":"NONE",
                  "confidence":0.7
                }
                """);

        EvidenceAgentTurnResult result =
                objectMapper.treeToValue(source, EvidenceAgentTurnResult.class);
        JsonNode firstReplay = objectMapper.valueToTree(result);
        EvidenceAgentTurnResult replayed =
                objectMapper.treeToValue(firstReplay, EvidenceAgentTurnResult.class);
        JsonNode secondReplay = objectMapper.valueToTree(replayed);

        assertThat(firstReplay).isEqualTo(source);
        assertThat(secondReplay).isEqualTo(source);
        assertThat(result.publicObservations()).hasSize(1);
        assertThat(result.publicObservations().get(0))
                .containsEntry("observation_id", "PUBOBS_0123456789ABCDEF01234567")
                .containsEntry("source_quote", "退款工单未生成");
        assertThat(result.evidenceAssessments().get(0).publicObservationSlots()).isEmpty();
        assertThat(result.evidenceAssessments().get(0).publicObservationIds())
                .containsExactly("PUBOBS_0123456789ABCDEF01234567");
        assertThatThrownBy(() -> result.publicObservations().add(java.util.Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
