package com.example.dispute.workflow.targete2e.ingress;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

class MinioTargetE2eIntakePayloadPublisherTest {

  @Test
  void acceptsCanonicalPayloadWhoseEmbeddedHashExcludesItsOwnField() {
    var payload = JsonNodeFactory.instance.objectNode();
    payload.put("schema_version", "intake-domain-snapshot.v2");
    payload.put("snapshot_id", "snapshot-1");
    payload.put("snapshot_hash", "0".repeat(64));
    String hash = IntakeContractHashes.canonicalHashExcluding(payload, "snapshot_hash");
    payload.put("snapshot_hash", hash);

    assertThatCode(() -> MinioTargetE2eIntakePayloadPublisher.requireCanonicalSelfHash(
            ContractJson.canonicalize(payload), "snapshot_hash", hash))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAHashThatDoesNotBindTheCanonicalPayload() {
    var payload = JsonNodeFactory.instance.objectNode();
    payload.put("schema_version", "intake-turn-event.v2");
    payload.put("event_hash", "a".repeat(64));

    assertThatThrownBy(() -> MinioTargetE2eIntakePayloadPublisher.requireCanonicalSelfHash(
            ContractJson.canonicalize(payload), "event_hash", "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("target Intake payload hash is invalid");
  }
}
