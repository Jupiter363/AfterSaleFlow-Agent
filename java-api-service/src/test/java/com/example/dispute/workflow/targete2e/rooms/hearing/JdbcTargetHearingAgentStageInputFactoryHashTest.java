package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class JdbcTargetHearingAgentStageInputFactoryHashTest {

  @Test
  void hearingSelfHashPreservesPythonFloatEncodingAndDefaultMembers() {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode value = mapper.createObjectNode();
    value.put("schema_version", "hearing_judge_v2.v1");
    ObjectNode draft = value.putObject("draft");
    draft.put("confidence", 0.0);
    draft.putArray("limitations");
    value.put("judge_v2_hash", "0".repeat(64));

    assertThat(JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
        mapper, value, "judge_v2_hash"))
        .isEqualTo("7ea476962881d54d254b43c3064682cf8fd6c3b5436dda2f260c5d79cb0c4c69");
  }
}
