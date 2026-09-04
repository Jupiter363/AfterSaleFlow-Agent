package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Strict codec and RFC 8785 hash authority for the frozen production-runtime Graph wrappers. */
public final class ProductionGraphEnvelopeCodec {

  private static final String COMMAND_SCHEMA = "room-graph-command.schema.json";
  private static final String RESULT_SCHEMA = "room-graph-result.schema.json";
  private static final Set<String> COMMAND_FIELDS =
      Set.of(
          "schema_version",
          "execution_lane",
          "activation_id",
          "room_fencing_token",
          "command_hash",
          "command_envelope_hash",
          "command");
  private static final Set<String> RESULT_FIELDS =
      Set.of(
          "schema_version",
          "execution_lane",
          "activation_id",
          "room_fencing_token",
          "command_hash",
          "command_envelope_hash",
          "execution_provider",
          "execution_model",
          "result_hash",
          "proposal_hash",
          "result_envelope_hash",
          "graph_output_authority",
          "result");
  private static final Set<String> PROPOSAL_FIELDS =
      Set.of(
          "schema_version",
          "proposal_id",
          "command_id",
          "logical_run_id",
          "attempt_id",
          "payload_schema_version",
          "payload_ref",
          "payload_hash",
          "terminal_class",
          "formal_authority");

  private final AgentPlatformContractCodec v1Codec;
  private final ObjectMapper mapper;

  public ProductionGraphEnvelopeCodec(ObjectMapper objectMapper) {
    this(objectMapper, new AgentPlatformContractCodec());
  }

  public ProductionGraphEnvelopeCodec(
      ObjectMapper objectMapper, AgentPlatformContractCodec v1Codec) {
    this.v1Codec = Objects.requireNonNull(v1Codec, "v1Codec");
    this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    this.mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ProductionGraphCommandEnvelope wrapCommand(
      String activationId, long roomFencingToken, RoomGraphCommand command) {
    ObjectNode commandJson = validatedCommand(command);
    String commandHash = ContractJson.sha256Hex(commandJson);
    ObjectNode unhashed = mapper.createObjectNode();
    unhashed.put("schema_version", ProductionGraphCommandEnvelope.SCHEMA_VERSION);
    unhashed.put("execution_lane", ProductionGraphCommandEnvelope.EXECUTION_LANE);
    unhashed.put("activation_id", activationId);
    unhashed.put("room_fencing_token", roomFencingToken);
    unhashed.put("command_hash", commandHash);
    unhashed.set("command", commandJson);
    String envelopeHash = ContractJson.sha256Hex(unhashed);
    return new ProductionGraphCommandEnvelope(
        ProductionGraphCommandEnvelope.SCHEMA_VERSION,
        ProductionGraphCommandEnvelope.EXECUTION_LANE,
        activationId,
        roomFencingToken,
        commandHash,
        envelopeHash,
        command);
  }

  /** Computes the nested command hash from the same schema-normalized JSON used by validation. */
  public String commandRequestHash(RoomGraphCommand command) {
    JsonNode encoded = v1Codec.encode(COMMAND_SCHEMA, Objects.requireNonNull(command, "command"));
    if (!(encoded instanceof ObjectNode object)) {
      throw new IllegalArgumentException("serialized command must be an object");
    }
    ObjectNode unhashed = object.deepCopy();
    JsonNode declaredHash = unhashed.remove("request_hash");
    if (declaredHash == null
        || !declaredHash.isTextual()
        || !constantTimeEquals(command.requestHash(), declaredHash.asText())) {
      throw new IllegalArgumentException("command request_hash serialization drifted");
    }
    return ContractJson.sha256Hex(unhashed);
  }

  public ProductionSealedGraphCommand sealCommand(
      String activationId,
      long roomFencingToken,
      RoomGraphCommand command,
      GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding,
      ProductionGraphEnvelopeSigner signer) {
    ProductionGraphCommandEnvelope envelope = wrapCommand(activationId, roomFencingToken, command);
    byte[] body = encodeCommand(envelope);
    ProductionGraphEnvelopeSigner.SignedEnvelope credential =
        Objects.requireNonNull(signer, "signer").sign(envelope, expectedRegistryBinding);
    if (!constantTimeEquals(credential.keyId(), command.invocationContext().envelopeKeyId())
        || constantTimeEquals(credential.jti(), command.invocationContext().envelopeNonce())) {
      throw new IllegalArgumentException(
          "target Graph credential conflicts with its immutable command");
    }
    return new ProductionSealedGraphCommand(envelope, body, credential);
  }

  public ProductionSealedGraphCommand sealParallelCommand(
      String activationId,
      long roomFencingToken,
      RoomGraphCommand command,
      GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding,
      ProductionGraphEnvelopeSigner signer,
      ProductionGraphEnvelopeSigner.ParallelDeliveryBinding deliveryBinding) {
    ProductionGraphCommandEnvelope envelope = wrapCommand(activationId, roomFencingToken, command);
    byte[] body = encodeCommand(envelope);
    ProductionGraphEnvelopeSigner.SignedEnvelope credential =
        Objects.requireNonNull(signer, "signer")
            .signParallel(
                envelope,
                expectedRegistryBinding,
                Objects.requireNonNull(deliveryBinding, "deliveryBinding"));
    if (!constantTimeEquals(credential.keyId(), command.invocationContext().envelopeKeyId())
        || constantTimeEquals(credential.jti(), command.invocationContext().envelopeNonce())) {
      throw new IllegalArgumentException(
          "target Graph parallel credential conflicts with its immutable command");
    }
    return new ProductionSealedGraphCommand(envelope, body, credential);
  }

  public byte[] encodeCommand(ProductionGraphCommandEnvelope envelope) {
    requireValidCommandEnvelope(Objects.requireNonNull(envelope, "envelope"));
    return ContractJson.canonicalize(mapper.valueToTree(envelope));
  }

  public ProductionGraphCommandEnvelope decodeCommand(byte[] body) {
    ObjectNode node = readObject(body, 65_536, "command envelope");
    requireExactFields(node, COMMAND_FIELDS, "command envelope");
    RoomGraphCommand command =
        v1Codec.decode(COMMAND_SCHEMA, node.required("command"), RoomGraphCommand.class);
    ProductionGraphCommandEnvelope envelope =
        new ProductionGraphCommandEnvelope(
            requiredText(node, "schema_version"),
            requiredText(node, "execution_lane"),
            requiredText(node, "activation_id"),
            requiredLong(node, "room_fencing_token"),
            requiredText(node, "command_hash"),
            requiredText(node, "command_envelope_hash"),
            command);
    requireValidCommandEnvelope(envelope);
    return envelope;
  }

  public ProductionGraphResultEnvelope decodeResult(
      byte[] body,
      ProductionGraphCommandEnvelope expectedCommand,
      byte[] schemaValidatedProposalSource) {
    ObjectNode node = readObject(body, 131_072, "result envelope");
    requireExactFields(node, RESULT_FIELDS, "result envelope");
    RoomGraphResult result =
        v1Codec.decode(RESULT_SCHEMA, node.required("result"), RoomGraphResult.class);
    ProductionGraphResultEnvelope envelope =
        new ProductionGraphResultEnvelope(
            requiredText(node, "schema_version"),
            requiredText(node, "execution_lane"),
            requiredText(node, "activation_id"),
            requiredLong(node, "room_fencing_token"),
            requiredText(node, "command_hash"),
            requiredText(node, "command_envelope_hash"),
            requiredText(node, "execution_provider"),
            requiredText(node, "execution_model"),
            requiredText(node, "result_hash"),
            requiredText(node, "proposal_hash"),
            requiredText(node, "result_envelope_hash"),
            requiredText(node, "graph_output_authority"),
            result);
    requireValidResultEnvelope(
        envelope,
        Objects.requireNonNull(expectedCommand, "expectedCommand"),
        readObject(schemaValidatedProposalSource, 65_536, "room proposal source"));
    return envelope;
  }

  String declaredProposalHash(byte[] body) {
    ObjectNode node = readObject(body, 131_072, "result envelope");
    requireExactFields(node, RESULT_FIELDS, "result envelope");
    String value = requiredText(node, "proposal_hash");
    ProductionGraphCommandEnvelope.requirePattern(
        value, ProductionGraphCommandEnvelope.SHA256, "proposalHash");
    return value;
  }

  /** Validates the exact proposal-source document before it crosses the HTTP adapter boundary. */
  public byte[] validateProposalSource(
      byte[] body, RoomGraphCommand expectedCommand, String expectedProposalHash) {
    ProductionGraphCommandEnvelope.requirePattern(
        expectedProposalHash, ProductionGraphCommandEnvelope.SHA256, "expectedProposalHash");
    ObjectNode source = readObject(body, 65_536, "room proposal source");
    ValidatedProposal proposal = validatedProposal(source, expectedCommand);
    if (!constantTimeEquals(expectedProposalHash, proposal.proposalHash())) {
      throw new IllegalArgumentException("room proposal source hash differs from the result");
    }
    return body.clone();
  }

  public byte[] encodeResult(
      ProductionGraphResultEnvelope envelope,
      ProductionGraphCommandEnvelope expectedCommand,
      JsonNode schemaValidatedProposalSource) {
    requireValidResultEnvelope(envelope, expectedCommand, schemaValidatedProposalSource);
    return ContractJson.canonicalize(mapper.valueToTree(envelope));
  }

  public ProductionGraphResultEnvelope wrapResult(
      ProductionGraphCommandEnvelope commandEnvelope,
      RoomGraphResult result,
      JsonNode schemaValidatedProposalSource,
      String executionProvider,
      String executionModel) {
    Objects.requireNonNull(commandEnvelope, "commandEnvelope");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(schemaValidatedProposalSource, "schemaValidatedProposalSource");
    ObjectNode resultJson = validatedResult(result);
    JsonNode declaredOutputHash = resultJson.remove("output_hash");
    String resultHash = ContractJson.sha256Hex(resultJson);
    if (declaredOutputHash == null
        || !declaredOutputHash.isTextual()
        || !constantTimeEquals(resultHash, declaredOutputHash.asText())) {
      throw new IllegalArgumentException("nested result output_hash does not bind its body");
    }
    String proposalHash =
        proposalHash(schemaValidatedProposalSource, commandEnvelope.command(), result);
    ObjectNode unhashed = mapper.createObjectNode();
    unhashed.put("schema_version", ProductionGraphResultEnvelope.SCHEMA_VERSION);
    unhashed.put("execution_lane", ProductionGraphCommandEnvelope.EXECUTION_LANE);
    unhashed.put("activation_id", commandEnvelope.activationId());
    unhashed.put("room_fencing_token", commandEnvelope.roomFencingToken());
    unhashed.put("command_hash", commandEnvelope.commandHash());
    unhashed.put("command_envelope_hash", commandEnvelope.commandEnvelopeHash());
    unhashed.put("execution_provider", executionProvider);
    unhashed.put("execution_model", executionModel);
    unhashed.put("result_hash", resultHash);
    unhashed.put("proposal_hash", proposalHash);
    unhashed.put("graph_output_authority", ProductionGraphResultEnvelope.GRAPH_OUTPUT_AUTHORITY);
    unhashed.set("result", mapper.valueToTree(result));
    String envelopeHash = ContractJson.sha256Hex(unhashed);
    ProductionGraphResultEnvelope envelope =
        new ProductionGraphResultEnvelope(
            ProductionGraphResultEnvelope.SCHEMA_VERSION,
            ProductionGraphCommandEnvelope.EXECUTION_LANE,
            commandEnvelope.activationId(),
            commandEnvelope.roomFencingToken(),
            commandEnvelope.commandHash(),
            commandEnvelope.commandEnvelopeHash(),
            executionProvider,
            executionModel,
            resultHash,
            proposalHash,
            envelopeHash,
            ProductionGraphResultEnvelope.GRAPH_OUTPUT_AUTHORITY,
            result);
    requireValidResultEnvelope(envelope, commandEnvelope, schemaValidatedProposalSource);
    return envelope;
  }

  private void requireValidCommandEnvelope(ProductionGraphCommandEnvelope envelope) {
    ObjectNode commandJson = validatedCommand(envelope.command());
    String actualCommandHash = ContractJson.sha256Hex(commandJson);
    ObjectNode envelopeJson = mapper.valueToTree(envelope);
    JsonNode declaredHash = envelopeJson.remove("command_envelope_hash");
    String actualEnvelopeHash = ContractJson.sha256Hex(envelopeJson);
    if (declaredHash == null
        || !declaredHash.isTextual()
        || !constantTimeEquals(envelope.commandHash(), actualCommandHash)
        || !constantTimeEquals(envelope.commandEnvelopeHash(), declaredHash.asText())
        || !constantTimeEquals(envelope.commandEnvelopeHash(), actualEnvelopeHash)) {
      throw new IllegalArgumentException("target Graph command envelope hash mismatch");
    }
  }

  private void requireValidResultEnvelope(
      ProductionGraphResultEnvelope envelope,
      ProductionGraphCommandEnvelope expectedCommand,
      JsonNode schemaValidatedProposalSource) {
    requireValidCommandEnvelope(expectedCommand);
    ObjectNode resultJson = validatedResult(envelope.result());
    JsonNode declaredOutputHash = resultJson.remove("output_hash");
    String actualResultHash = ContractJson.sha256Hex(resultJson);
    String actualProposalHash =
        proposalHash(schemaValidatedProposalSource, expectedCommand.command(), envelope.result());
    ObjectNode envelopeJson = mapper.valueToTree(envelope);
    JsonNode declaredEnvelopeHash = envelopeJson.remove("result_envelope_hash");
    String actualEnvelopeHash = ContractJson.sha256Hex(envelopeJson);
    RoomGraphCommand command = expectedCommand.command();
    RoomGraphResult result = envelope.result();
    RoomGraphCommand.InvocationContext invocation = command.invocationContext();
    RoomGraphResult.ExecutionMetadata metadata = result.executionMetadata();
    boolean matches =
        constantTimeEquals(envelope.executionLane(), expectedCommand.executionLane())
            && constantTimeEquals(envelope.activationId(), expectedCommand.activationId())
            && envelope.roomFencingToken() == expectedCommand.roomFencingToken()
            && constantTimeEquals(envelope.commandHash(), expectedCommand.commandHash())
            && constantTimeEquals(
                envelope.commandEnvelopeHash(), expectedCommand.commandEnvelopeHash())
            && declaredOutputHash != null
            && declaredOutputHash.isTextual()
            && constantTimeEquals(envelope.resultHash(), declaredOutputHash.asText())
            && constantTimeEquals(envelope.resultHash(), result.outputHash())
            && constantTimeEquals(envelope.resultHash(), actualResultHash)
            && constantTimeEquals(envelope.proposalHash(), actualProposalHash)
            && declaredEnvelopeHash != null
            && declaredEnvelopeHash.isTextual()
            && constantTimeEquals(envelope.resultEnvelopeHash(), declaredEnvelopeHash.asText())
            && constantTimeEquals(envelope.resultEnvelopeHash(), actualEnvelopeHash)
            && command.commandId().equals(result.commandId())
            && command.logicalRunId().equals(result.logicalRunId())
            && command.attemptId().equals(result.attemptId())
            && command.graphKey().equals(result.graphKey())
            && command.graphVersion().equals(result.graphVersion())
            && metadata != null
            && invocation.promptProfileId().equals(metadata.promptVersion())
            && invocation.modelProfileId().equals(metadata.modelProfileId())
            && invocation.outputSchemaVersion().equals(metadata.schemaVersion())
            && invocation.policyVersion().equals(metadata.policyVersion())
            && invocation.guardrailVersion().equals(metadata.guardrailVersion());
    if (!matches) {
      throw new IllegalArgumentException(
          "target Graph result differs from its immutable command or proposal");
    }
  }

  private ObjectNode validatedCommand(RoomGraphCommand command) {
    JsonNode encoded = v1Codec.encode(COMMAND_SCHEMA, Objects.requireNonNull(command, "command"));
    if (!(encoded instanceof ObjectNode object)) {
      throw new IllegalArgumentException("serialized command must be an object");
    }
    ObjectNode unhashed = object.deepCopy();
    JsonNode requestHash = unhashed.remove("request_hash");
    if (requestHash == null
        || !requestHash.isTextual()
        || !constantTimeEquals(command.requestHash(), requestHash.asText())
        || !constantTimeEquals(command.requestHash(), ContractJson.sha256Hex(unhashed))) {
      throw new IllegalArgumentException("command request_hash does not bind its body");
    }
    return object;
  }

  private ObjectNode validatedResult(RoomGraphResult result) {
    JsonNode encoded = v1Codec.encode(RESULT_SCHEMA, Objects.requireNonNull(result, "result"));
    if (!(encoded instanceof ObjectNode object)) {
      throw new IllegalArgumentException("serialized result must be an object");
    }
    return object;
  }

  private String proposalHash(
      JsonNode sourceNode, RoomGraphCommand command, RoomGraphResult result) {
    ValidatedProposal validated = validatedProposal(sourceNode, command);
    if (!validated.proposal().terminalClass().name().equals(result.status().name())) {
      throw new IllegalArgumentException("room proposal source differs from its result terminal");
    }
    return validated.proposalHash();
  }

  private ValidatedProposal validatedProposal(
      JsonNode sourceNode, RoomGraphCommand command) {
    if (!(sourceNode instanceof ObjectNode source)) {
      throw new IllegalArgumentException("room proposal source must be a schema-validated object");
    }
    requireExactFields(
        source, Set.of("schema_version", "room_type", "proposal"), "room proposal source");
    if (!(source.required("proposal") instanceof ObjectNode proposalNode)) {
      throw new IllegalArgumentException("room proposal source /proposal must be an object");
    }
    requireExactFields(proposalNode, PROPOSAL_FIELDS, "room proposal source /proposal");
    ProductionRoomProposalSource decoded;
    try {
      decoded = mapper.treeToValue(source, ProductionRoomProposalSource.class);
    } catch (IOException exception) {
      throw new IllegalArgumentException("room proposal source is invalid", exception);
    }
    ProductionRoomProposalSource.Proposal proposal = decoded.proposal();
    boolean matches =
        decoded.roomType() == command.roomType()
            && proposal.commandId().equals(command.commandId())
            && proposal.logicalRunId().equals(command.logicalRunId())
            && proposal.attemptId().equals(command.attemptId());
    if (!matches) {
      throw new IllegalArgumentException("room proposal source differs from its immutable command");
    }
    return new ValidatedProposal(proposal, ContractJson.sha256Hex(proposalNode));
  }

  private ObjectNode readObject(byte[] body, int maximumBytes, String label) {
    if (body == null || body.length == 0 || body.length > maximumBytes) {
      throw new IllegalArgumentException(label + " size is invalid");
    }
    try {
      JsonNode node =
          mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
      if (!(node instanceof ObjectNode object)) {
        throw new IllegalArgumentException(label + " must be an object");
      }
      return object;
    } catch (IOException exception) {
      throw new IllegalArgumentException(label + " is invalid JSON", exception);
    }
  }

  private static void requireExactFields(ObjectNode node, Set<String> expected, String label) {
    Set<String> actual =
        node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(label + " members are not exact");
    }
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.required(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.asText();
  }

  private static long requiredLong(ObjectNode node, String field) {
    JsonNode value = node.required(field);
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    return value.longValue();
  }

  static boolean constantTimeEquals(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  private record ValidatedProposal(
      ProductionRoomProposalSource.Proposal proposal, String proposalHash) {}
}
