package com.example.dispute.workflow.targete2e.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetEvidenceFormalObservationBinderTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void bindsLegacyAndModelRelationsWithoutMutatingTheImmutableProposal() throws Exception {
    JsonNode observation = mapper.readTree("""
        {
          "binding_status":"BOUND",
          "fact_bindings":[
            {"fact_id":"FACT_1","relation":"SUPPORTS","reason":"direct"},
            {"fact_id":"FACT_2","relation":"CONTEXTUALIZES","reason":"context"},
            {"fact_id":"FACT_3","relation":"OPPOSES","reason":"conflict"},
            {"fact_id":"FACT_4","relation":"SUPPORTS_CLAIM","reason":"claim"}
          ]
        }
        """);

    List<JsonNode> bound = TargetEvidenceFormalObservationBinder.bind(List.of(observation));

    assertThat(bound.getFirst().path("fact_bindings").get(0).path("relation").asText())
        .isEqualTo("CONTENT_SUPPORTS");
    assertThat(bound.getFirst().path("fact_bindings").get(1).path("relation").asText())
        .isEqualTo("CONTEXT_ONLY");
    assertThat(bound.getFirst().path("fact_bindings").get(2).path("relation").asText())
        .isEqualTo("CONTENT_CONTRADICTS");
    assertThat(bound.getFirst().path("fact_bindings").get(3).path("relation").asText())
        .isEqualTo("CONTENT_SUPPORTS");
    assertThat(observation.path("fact_bindings").get(0).path("relation").asText())
        .isEqualTo("SUPPORTS");
  }

  @Test
  void preservesCanonicalRelationsAndBindsUnknownOrMissingValuesAsInconclusive()
      throws Exception {
    JsonNode observation = mapper.readTree("""
        {
          "fact_bindings":[
            {"fact_id":"FACT_1","relation":"CONTENT_SUPPORTS"},
            {"fact_id":"FACT_2","relation":"CONTENT_CONTRADICTS"},
            {"fact_id":"FACT_3","relation":"CONTEXT_ONLY"},
            {"fact_id":"FACT_4","relation":"INCONCLUSIVE"},
            {"fact_id":"FACT_5","relation":"MODEL_FREE_FORM"},
            {"fact_id":"FACT_6"}
          ]
        }
        """);

    JsonNode bindings = TargetEvidenceFormalObservationBinder.bind(List.of(observation))
        .getFirst().path("fact_bindings");

    assertThat(bindings.get(0).path("relation").asText()).isEqualTo("CONTENT_SUPPORTS");
    assertThat(bindings.get(1).path("relation").asText()).isEqualTo("CONTENT_CONTRADICTS");
    assertThat(bindings.get(2).path("relation").asText()).isEqualTo("CONTEXT_ONLY");
    assertThat(bindings.get(3).path("relation").asText()).isEqualTo("INCONCLUSIVE");
    assertThat(bindings.get(4).path("relation").asText()).isEqualTo("INCONCLUSIVE");
    assertThat(bindings.get(5).path("relation").asText()).isEqualTo("INCONCLUSIVE");
  }
}
