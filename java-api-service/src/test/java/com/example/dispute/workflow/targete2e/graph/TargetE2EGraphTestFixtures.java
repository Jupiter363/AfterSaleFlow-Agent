package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class TargetE2EGraphTestFixtures {

  static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
  static final Path CONTRACT_ROOT = Path.of("..", "contracts", "agent-platform", "v1");
  static final String ACTIVATION_ID = "p9act.v1." + "a".repeat(32);
  static final GraphRegistryBindingPolicy.ExpectedBinding REGISTRY_BINDING =
      new GraphRegistryBindingPolicy.ExpectedBinding("c".repeat(64), "tools.none.v1");
  static final AgentPlatformContractCodec V1_CODEC = new AgentPlatformContractCodec(CONTRACT_ROOT);

  static {
    MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  private TargetE2EGraphTestFixtures() {}

  static TargetE2EGraphEnvelopeCodec codec() {
    return new TargetE2EGraphEnvelopeCodec(MAPPER, V1_CODEC);
  }

  static RoomGraphCommand command() {
    try {
      ObjectNode fixture =
          (ObjectNode)
              MAPPER.readTree(
                  Files.readAllBytes(
                      CONTRACT_ROOT.resolve(
                          "fixtures/canonical-hash/room-graph-command-self-hash.json")));
      ObjectNode input = (ObjectNode) fixture.required("input").deepCopy();
      input.put(fixture.required("hash_field").asText(), fixture.required("sha256").asText());
      return V1_CODEC.decode("room-graph-command.schema.json", input, RoomGraphCommand.class);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static RoomGraphResult result() {
    try {
      ObjectNode fixture =
          (ObjectNode)
              MAPPER.readTree(
                  Files.readAllBytes(
                      CONTRACT_ROOT.resolve("fixtures/valid/room-graph-result-valid.json")));
      ObjectNode instance = (ObjectNode) fixture.required("instance").deepCopy();
      instance.remove("output_hash");
      instance.put("output_hash", ContractJson.sha256Hex(instance));
      return V1_CODEC.decode("room-graph-result.schema.json", instance, RoomGraphResult.class);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static JsonNode proposalSource() {
    ObjectNode proposal = MAPPER.createObjectNode();
    proposal.put("schema_version", "target-e2e-intake-proposal.v1");
    proposal.put("proposal_id", "proposal-intake-001");
    proposal.put("command_id", command().commandId());
    proposal.put("logical_run_id", command().logicalRunId());
    proposal.put("attempt_id", command().attemptId());
    proposal.put("payload_schema_version", "intake-turn-proposal.v2");
    proposal.put("payload_ref", "urn:target-e2e:proposal:intake:001");
    proposal.put("payload_hash", "1".repeat(64));
    proposal.put("terminal_class", "COMPLETED");
    proposal.put("formal_authority", false);
    ObjectNode source = MAPPER.createObjectNode();
    source.put("schema_version", "target-e2e-room-proposal-source.v1");
    source.put("room_type", "INTAKE");
    source.set("proposal", proposal);
    return source;
  }

  static byte[] proposalSourceBytes() {
    try {
      return MAPPER.writeValueAsBytes(proposalSource());
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
