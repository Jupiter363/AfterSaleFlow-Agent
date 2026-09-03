package com.example.dispute.room.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaseEventWakeupTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void serializesOnlySchemaCaseAndDurableCursorMetadata() throws Exception {
        JsonNode json =
                objectMapper.readTree(
                        objectMapper.writeValueAsString(
                                new CaseEventWakeup(
                                        CaseEventWakeup.SCHEMA_VERSION, "CASE_1", 17)));

        Set<String> fieldNames = new HashSet<>();
        json.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames)
                .containsExactlyInAnyOrder("schema_version", "case_id", "durable_sequence");
        assertThat(json.path("schema_version").asText())
                .isEqualTo(CaseEventWakeup.SCHEMA_VERSION);
        assertThat(json.path("case_id").asText()).isEqualTo("CASE_1");
        assertThat(json.path("durable_sequence").asLong()).isEqualTo(17);
    }

    @Test
    void rejectsUnsupportedOrIncompleteHints() {
        assertThatThrownBy(() -> new CaseEventWakeup("wrong", "CASE_1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new CaseEventWakeup(
                                        CaseEventWakeup.SCHEMA_VERSION, " ", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new CaseEventWakeup(
                                        CaseEventWakeup.SCHEMA_VERSION, "CASE_1", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
