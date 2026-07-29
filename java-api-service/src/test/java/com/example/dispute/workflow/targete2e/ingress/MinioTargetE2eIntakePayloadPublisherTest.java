package com.example.dispute.workflow.targete2e.ingress;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.application.authority.payload.IntakeBranchCommand;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
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

  @Test
  void acceptsExactCanonicalBranchPayloadBoundByItsContentHash() {
    IntakeBranchCommand branch =
        new IntakeBranchCommand(
            IntakeBranchCommand.SCHEMA_VERSION,
            "intake-branch:command-1",
            CommandType.INTAKE_CONFIRM,
            Party.INITIATOR,
            IntakeBranchCommand.Operation.INITIATOR_ACCEPT,
            true,
            "MISSING_DELIVERY",
            RiskLevel.MEDIUM,
            null,
            null);
    ObjectMapper snakeCaseMapper = new ObjectMapper();
    snakeCaseMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    var document = snakeCaseMapper.valueToTree(branch);
    byte[] canonical = ContractJson.canonicalize(document);

    assertThatCode(
            () ->
                MinioTargetE2eIntakePayloadPublisher.requireCanonicalBranchHash(
                    canonical, ContractJson.sha256Hex(document)))
        .doesNotThrowAnyException();
  }
}
