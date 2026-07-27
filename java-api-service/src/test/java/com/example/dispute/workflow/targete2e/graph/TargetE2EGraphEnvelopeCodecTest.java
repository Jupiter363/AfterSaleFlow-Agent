package com.example.dispute.workflow.targete2e.graph;

import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.ACTIVATION_ID;
import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TargetE2EGraphEnvelopeCodecTest {

  @Test
  void wrapsTheCanonicalFullCommandAndUsesExactSnakeCaseMembers() throws Exception {
    var codec = TargetE2EGraphTestFixtures.codec();
    var command = TargetE2EGraphTestFixtures.command();
    var envelope = codec.wrapCommand(ACTIVATION_ID, 7L, command);
    ObjectNode encoded = (ObjectNode) MAPPER.readTree(codec.encodeCommand(envelope));
    ObjectNode commandJson = (ObjectNode) encoded.required("command");
    ObjectNode unhashed = encoded.deepCopy();
    unhashed.remove("command_envelope_hash");

    assertThat(envelope.commandHash()).isEqualTo(ContractJson.sha256Hex(commandJson));
    assertThat(envelope.commandEnvelopeHash()).isEqualTo(ContractJson.sha256Hex(unhashed));
    assertThat(encoded.properties().stream().map(Map.Entry::getKey).toList())
        .containsExactlyInAnyOrder(
            "schema_version",
            "execution_lane",
            "activation_id",
            "room_fencing_token",
            "command_hash",
            "command_envelope_hash",
            "command");
    assertThat(codec.decodeCommand(codec.encodeCommand(envelope))).isEqualTo(envelope);
  }

  @ParameterizedTest
  @MethodSource("commandTampering")
  void rejectsEveryCommandEnvelopeTamper(Consumer<ObjectNode> tamper) throws Exception {
    var codec = TargetE2EGraphTestFixtures.codec();
    ObjectNode encoded =
        (ObjectNode)
            MAPPER.readTree(
                codec.encodeCommand(
                    codec.wrapCommand(ACTIVATION_ID, 7L, TargetE2EGraphTestFixtures.command())));
    tamper.accept(encoded);

    assertThatThrownBy(() -> codec.decodeCommand(MAPPER.writeValueAsBytes(encoded)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> commandTampering() {
    return Stream.of(
        Arguments.of((Consumer<ObjectNode>) node -> node.put("execution_lane", "SHADOW")),
        Arguments.of(
            (Consumer<ObjectNode>) node -> node.put("activation_id", "p9act.v1." + "g".repeat(32))),
        Arguments.of((Consumer<ObjectNode>) node -> node.put("room_fencing_token", 0)),
        Arguments.of((Consumer<ObjectNode>) node -> node.put("command_hash", "0".repeat(64))),
        Arguments.of(
            (Consumer<ObjectNode>) node -> node.put("command_envelope_hash", "0".repeat(64))),
        Arguments.of(
            (Consumer<ObjectNode>)
                node ->
                    ((ObjectNode) node.required("command")).put("request_hash", "0".repeat(64))),
        Arguments.of((Consumer<ObjectNode>) node -> node.put("unexpected", true)));
  }

  @Test
  void validatesProposalOnlyResultAndAllCommandResultAndProfileCausality() throws Exception {
    var codec = TargetE2EGraphTestFixtures.codec();
    var command = codec.wrapCommand(ACTIVATION_ID, 7L, TargetE2EGraphTestFixtures.command());
    JsonNode proposal = TargetE2EGraphTestFixtures.proposalSource();
    var result = codec.wrapResult(command, TargetE2EGraphTestFixtures.result(), proposal);
    ObjectNode encoded =
        (ObjectNode) MAPPER.readTree(codec.encodeResult(result, command, proposal));
    ObjectNode unhashed = encoded.deepCopy();
    unhashed.remove("result_envelope_hash");

    assertThat(result.resultHash()).isEqualTo(result.result().outputHash());
    assertThat(result.proposalHash())
        .isEqualTo(ContractJson.sha256Hex(proposal.required("proposal")));
    assertThat(result.resultEnvelopeHash()).isEqualTo(ContractJson.sha256Hex(unhashed));
    assertThat(result.graphOutputAuthority()).isEqualTo("PROPOSAL_ONLY");
    assertThat(encoded.properties().stream().map(Map.Entry::getKey).toList())
        .containsExactlyInAnyOrder(
            "schema_version",
            "execution_lane",
            "activation_id",
            "room_fencing_token",
            "command_hash",
            "command_envelope_hash",
            "result_hash",
            "proposal_hash",
            "result_envelope_hash",
            "graph_output_authority",
            "result");
    assertThat(
            codec.decodeResult(
                MAPPER.writeValueAsBytes(encoded), command, MAPPER.writeValueAsBytes(proposal)))
        .isEqualTo(result);
  }

  @ParameterizedTest
  @MethodSource("resultTampering")
  void rejectsEveryResultEnvelopeTamper(Consumer<ObjectNode> tamper) throws Exception {
    var codec = TargetE2EGraphTestFixtures.codec();
    var command = codec.wrapCommand(ACTIVATION_ID, 7L, TargetE2EGraphTestFixtures.command());
    JsonNode proposal = TargetE2EGraphTestFixtures.proposalSource();
    ObjectNode encoded =
        (ObjectNode)
            MAPPER.readTree(
                codec.encodeResult(
                    codec.wrapResult(command, TargetE2EGraphTestFixtures.result(), proposal),
                    command,
                    proposal));
    tamper.accept(encoded);

    assertThatThrownBy(
            () ->
                codec.decodeResult(
                    MAPPER.writeValueAsBytes(encoded), command, MAPPER.writeValueAsBytes(proposal)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> resultTampering() {
    return Stream.of(
        Arguments.of((Consumer<ObjectNode>) node -> node.put("execution_lane", "SHADOW")),
        Arguments.of(
            (Consumer<ObjectNode>) node -> node.put("activation_id", "p9act.v1." + "b".repeat(32))),
        Arguments.of((Consumer<ObjectNode>) node -> node.put("room_fencing_token", 8)),
        Arguments.of((Consumer<ObjectNode>) node -> node.put("command_hash", "0".repeat(64))),
        Arguments.of(
            (Consumer<ObjectNode>) node -> node.put("command_envelope_hash", "0".repeat(64))),
        Arguments.of((Consumer<ObjectNode>) node -> node.put("result_hash", "0".repeat(64))),
        Arguments.of((Consumer<ObjectNode>) node -> node.put("proposal_hash", "0".repeat(64))),
        Arguments.of(
            (Consumer<ObjectNode>) node -> node.put("result_envelope_hash", "0".repeat(64))),
        Arguments.of(
            (Consumer<ObjectNode>) node -> node.put("graph_output_authority", "FORMAL_WRITE")),
        Arguments.of(
            (Consumer<ObjectNode>)
                node ->
                    ((ObjectNode) node.required("result")).put("command_id", "another-command")),
        Arguments.of(
            (Consumer<ObjectNode>)
                node ->
                    ((ObjectNode)
                            ((ObjectNode) node.required("result")).required("execution_metadata"))
                        .put("policy_version", "other-policy.v1")),
        Arguments.of((Consumer<ObjectNode>) node -> node.put("proposal", true)));
  }

  @Test
  void rejectsDifferentSchemaValidatedProposalBytes() throws Exception {
    var codec = TargetE2EGraphTestFixtures.codec();
    var command = codec.wrapCommand(ACTIVATION_ID, 7L, TargetE2EGraphTestFixtures.command());
    JsonNode proposal = TargetE2EGraphTestFixtures.proposalSource();
    byte[] body =
        codec.encodeResult(
            codec.wrapResult(command, TargetE2EGraphTestFixtures.result(), proposal),
            command,
            proposal);
    JsonNode anotherProposal = proposal.deepCopy();
    ((ObjectNode) anotherProposal.required("proposal")).put("payload_hash", "2".repeat(64));

    assertThatThrownBy(
            () -> codec.decodeResult(body, command, MAPPER.writeValueAsBytes(anotherProposal)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("proposal");
  }

  @ParameterizedTest
  @MethodSource("proposalSourceTampering")
  void rejectsProposalSourceAuthorityOrCausalTampering(Consumer<ObjectNode> tamper) {
    var codec = TargetE2EGraphTestFixtures.codec();
    var command = codec.wrapCommand(ACTIVATION_ID, 7L, TargetE2EGraphTestFixtures.command());
    ObjectNode source = (ObjectNode) TargetE2EGraphTestFixtures.proposalSource();
    tamper.accept(source);

    assertThatThrownBy(() -> codec.wrapResult(command, TargetE2EGraphTestFixtures.result(), source))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> proposalSourceTampering() {
    return Stream.of(
        Arguments.of(
            (Consumer<ObjectNode>)
                source -> ((ObjectNode) source.required("proposal")).put("formal_authority", true)),
        Arguments.of(
            (Consumer<ObjectNode>)
                source -> ((ObjectNode) source.required("proposal")).remove("formal_authority")),
        Arguments.of(
            (Consumer<ObjectNode>)
                source ->
                    ((ObjectNode) source.required("proposal"))
                        .put("command_id", "another-command")),
        Arguments.of((Consumer<ObjectNode>) source -> source.put("room_type", "EVIDENCE")),
        Arguments.of(
            (Consumer<ObjectNode>)
                source ->
                    ((ObjectNode) source.required("proposal"))
                        .put("terminal_class", "NEEDS_REVIEW")),
        Arguments.of(
            (Consumer<ObjectNode>)
                source -> ((ObjectNode) source.required("proposal")).put("unexpected", true)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "target-e2e-graph-command-envelope.schema.json",
        "target-e2e-graph-result-envelope.schema.json",
        "target-e2e-room-proposal-source.schema.json"
      })
  void packagedTargetSchemasAreByteIdenticalToTheFrozenContract(String fileName) throws Exception {
    Path frozen = Path.of("..", "contracts", "agent-platform", "target-e2e", "v1", fileName);
    Path packaged =
        Path.of(
            "src",
            "main",
            "resources",
            "contracts",
            "agent-platform",
            "target-e2e",
            "v1",
            fileName);

    assertThat(Files.readAllBytes(packaged)).isEqualTo(Files.readAllBytes(frozen));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "target-e2e-intake-proposal-source-valid.json",
        "target-e2e-evidence-proposal-source-valid.json",
        "target-e2e-hearing-proposal-source-valid.json",
        "target-e2e-review-proposal-source-valid.json"
      })
  void decodesEveryFrozenRoomProposalHashSourceFixture(String fileName) throws Exception {
    Path fixture =
        Path.of(
            "..", "contracts", "agent-platform", "target-e2e", "v1", "fixtures", "valid", fileName);

    TargetE2ERoomProposalSource source =
        MAPPER.readValue(Files.readAllBytes(fixture), TargetE2ERoomProposalSource.class);

    assertThat(source.schemaVersion()).isEqualTo(TargetE2ERoomProposalSource.SCHEMA_VERSION);
    assertThat(source.proposal().formalAuthority()).isFalse();
    assertThat(ContractJson.sha256Hex(MAPPER.valueToTree(source.proposal())))
        .matches("[0-9a-f]{64}");
  }

  @Test
  void rejectsNestedResultWhoseOutputHashWasNotRecomputed() {
    var codec = TargetE2EGraphTestFixtures.codec();
    var command = codec.wrapCommand(ACTIVATION_ID, 7L, TargetE2EGraphTestFixtures.command());
    RoomGraphResult valid = TargetE2EGraphTestFixtures.result();
    RoomGraphResult wrong =
        new RoomGraphResult(
            valid.schemaVersion(),
            valid.commandId(),
            valid.logicalRunId(),
            valid.attemptId(),
            valid.graphKey(),
            valid.graphVersion(),
            valid.checkpointId(),
            valid.cognitiveRevision() + 1,
            valid.status(),
            valid.publicEventProposals(),
            valid.artifactOperations(),
            valid.needsInput(),
            valid.needsReview(),
            valid.error(),
            valid.outputHash(),
            valid.usage(),
            valid.executionMetadata());

    assertThatThrownBy(
            () -> codec.wrapResult(command, wrong, TargetE2EGraphTestFixtures.proposalSource()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("output_hash");
  }
}
