package com.example.dispute.workflow.targete2e.ingress.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JdbcTargetReviewInvocationFactsLoaderTest {
  private final JdbcTargetReviewInvocationFactsLoader loader = new JdbcTargetReviewInvocationFactsLoader(
      Mockito.mock(DataSource.class), new ObjectMapper());

  @Test
  void canonicalizesRepeatedFrozenFactAndRuleReferences() {
    var refs = loader.refs("[{\"claim_id\":\"FACT_2\"},{\"claim_ids\":[\"FACT_1\",\"FACT_2\"]}]",
        "[{\"evidence_ids\":[\"FACT_1\"]}]", "{\"rule_ids\":[\"RULE_2\",\"RULE_1\",\"RULE_2\"]}",
        "RULE_0", "PACKET_1", "DRAFT_1");

    assertThat(refs.facts()).extracting(value -> value.asText()).containsExactly("FACT_1", "FACT_2");
    assertThat(refs.rules()).extracting(value -> value.asText()).containsExactly("RULE_0", "RULE_1", "RULE_2");
    assertThat(refs.drafts()).extracting(value -> value.asText()).containsExactly("DRAFT_1");
  }

  @Test
  void rejectsReferenceThatAppearsInMoreThanOneCapabilityCategory() {
    assertThatThrownBy(() -> loader.refs("[{\"claim_id\":\"DUPLICATE\"}]", "[]",
        "{\"rule_id\":\"DUPLICATE\"}", "RULE_0", "PACKET_1", "DRAFT_1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("multiple categories");
  }

  @Test
  void ignoresTextOutsideExactSingularOrPluralCapabilityKeys() {
    var refs = loader.refs("{\"labels\":[\"FACT_FORBIDDEN\"],\"fact_ids\":[\"FACT_ALLOWED\"]}",
        "[]", "{\"notes\":[\"RULE_FORBIDDEN\"]}", "RULE_0", "PACKET_1", "DRAFT_1");

    assertThat(refs.facts()).extracting(value -> value.asText()).containsExactly("FACT_ALLOWED");
    assertThat(refs.rules()).extracting(value -> value.asText()).containsExactly("RULE_0");
  }

  @Test
  void selectsEveryFrozenPacketVersionRequiredByTheRowMapper() {
    assertThat(JdbcTargetReviewInvocationFactsLoader.SQL)
        .contains(
            "packet.case_version",
            "packet.dossier_version",
            "packet.issue_version",
            "packet.adjudication_draft_version",
            "packet.deliberation_report_version",
            "packet.remedy_plan_version");
  }

  @Test
  void rejectsDecisionEventHashUsedAsTheFrozenActionHash() {
    ObjectMapper mapper = new ObjectMapper();
    var packet = mapper.createObjectNode().put("action_hash", "a".repeat(64));
    var event = mapper.createObjectNode().put("approval_record_id", "APPROVAL_1");
    var empty = mapper.createArrayNode();
    var refs = new JdbcTargetReviewInvocationFactsLoader.Refs(empty, empty, empty, empty);

    assertThatThrownBy(() -> new JdbcTargetReviewInvocationFactsLoader.Facts(
        "REVIEW_1", "PACKET_1", 1, "COMPLETED", 7, Instant.parse("2026-07-29T01:00:00Z"),
        "b".repeat(64), packet, com.example.dispute.workflow.contract.v1.ContractJson.sha256Hex(packet),
        com.example.dispute.workflow.contract.v1.ContractJson.sha256Hex(event), event,
        com.example.dispute.workflow.contract.v1.ContractJson.sha256Hex(event), refs))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("frozen facts");
  }
}
