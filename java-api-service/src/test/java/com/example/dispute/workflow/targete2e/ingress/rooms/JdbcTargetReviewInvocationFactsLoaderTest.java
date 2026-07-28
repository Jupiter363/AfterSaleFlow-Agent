package com.example.dispute.workflow.targete2e.ingress.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JdbcTargetReviewInvocationFactsLoaderTest {
  private final JdbcTargetReviewInvocationFactsLoader loader = new JdbcTargetReviewInvocationFactsLoader(
      Mockito.mock(DataSource.class), new ObjectMapper());

  @Test
  void canonicalizesRepeatedFrozenFactAndRuleReferences() {
    var refs = loader.refs("[{\"claim_id\":\"FACT_2\"},{\"claim_id\":\"FACT_1\"}]",
        "[{\"evidence_id\":\"FACT_1\"}]", "{\"rule_ids\":[\"RULE_2\",\"RULE_1\",\"RULE_2\"]}",
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
}
