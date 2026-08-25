package com.example.dispute.workflow.contract.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContractJsonValueTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void canonicalizesEveryJsonValueWithoutChangingObjectAuthority() throws Exception {
        assertThat(canonical("{\"b\":1,\"a\":2}"))
                .isEqualTo("{\"a\":2,\"b\":1}");
        assertThat(canonical("[3,{\"b\":2,\"a\":1}]"))
                .isEqualTo("[3,{\"a\":1,\"b\":2}]");
        assertThat(canonical("15")).isEqualTo("15");
        assertThat(canonical("\"用户提交了本轮争议事实与处理诉求。\""))
                .isEqualTo("\"用户提交了本轮争议事实与处理诉求。\"");
        assertThat(canonical("true")).isEqualTo("true");
        assertThat(canonical("null")).isEqualTo("null");
    }

    @Test
    void matchesPythonGoldenHashesForDossierStringAndQualityNumber() throws Exception {
        var dossier = MAPPER.readTree("""
                {
                  "canonical_item_id":"shared_slot_0",
                  "projection_kind":"CURRENT_FACT",
                  "projection_path_id":"case_story.one_sentence_summary",
                  "value_kind":"JSON_VALUE",
                  "canonical_value":"用户提交了本轮争议事实与处理诉求。"
                }
                """);
        var quality = MAPPER.readTree("""
                {
                  "canonical_item_id":"shared_slot_0",
                  "projection_kind":"DIMENSION_SCORE",
                  "projection_path_id":"intake.quality.scores.references",
                  "value_kind":"JSON_VALUE",
                  "canonical_value":15
                }
                """);

        assertThat(ContractJson.sha256Hex(dossier))
                .isEqualTo("5e08fa34909b50c6e0e48944fd1dfd965b10b7c2e75ea0a8d908b46fc8bb70f6");
        assertThat(ContractJson.sha256Hex(quality))
                .isEqualTo("65211dfe7817dd88f8e1bffabc800b3d13fafb78d9193e2701cea7a6bc1b6a6d");
    }

    private static String canonical(String json) throws Exception {
        return new String(
                ContractJson.canonicalize(MAPPER.readTree(json)),
                StandardCharsets.UTF_8);
    }
}
