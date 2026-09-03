package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class JdbcTargetHearingAgentStageInputFactoryHashTest {

  @Test
  void hearingSelfHashUsesTheSameRfc8785NumberEncodingAsPython() {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode value = mapper.createObjectNode();
    value.put("schema_version", "hearing_judge_v2.v1");
    ObjectNode draft = value.putObject("draft");
    draft.put("confidence", 0.0);
    draft.putArray("limitations");
    value.put("judge_v2_hash", "0".repeat(64));

    assertThat(JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
        mapper, value, "judge_v2_hash"))
        .isEqualTo("6fc157be68ad534f35b35bf54d3d29e902c18c52f3cf1023a609f5d1a9740ee2");
  }
}
