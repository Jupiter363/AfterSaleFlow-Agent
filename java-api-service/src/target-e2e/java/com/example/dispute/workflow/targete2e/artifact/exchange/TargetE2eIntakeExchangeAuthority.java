package com.example.dispute.workflow.targete2e.artifact.exchange;

import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.PayloadLoadClaim;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.PayloadLoadGrant;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.ProposalPutClaim;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.ProposalPutGrant;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.Authority;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ObjectReference;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Target-artifact-only authority for the private Intake proposal exchange. */
public final class TargetE2eIntakeExchangeAuthority
    implements IntakeExchangeAuthorityValidationPort {

  private static final String TARGET_GRAPH_KEY = "all-rooms.target-e2e.v1";
  private static final String TARGET_REGISTRY_OUTPUT_SCHEMA =
      "target-e2e-room-proposal-source.v1";
  private static final String PROPOSAL_ARTIFACT_SCHEMA = "intake-turn-proposal.v2";
  private static final String AUTHORITY_SQL = """
      select material.activation_id, material.activation_manifest_hash,
             material.isolated_domain_db_binding_hash, material.tenant_surrogate,
             material.case_id, material.command_id, material.command_hash,
             material.command_envelope_hash, material.room_epoch,
             material.room_fencing_token, material.context_canonical_json,
             material.context_sha256,
             admission.activation_id as admitted_activation_id,
             admission.activation_manifest_hash as admitted_manifest_hash,
             admission.isolated_domain_db_binding_hash as admitted_domain_binding_hash,
             admission.tenant_surrogate as admitted_tenant_surrogate,
             admission.case_id as admitted_case_id,
             admission.command_id as admitted_command_id,
             admission.command_hash as admitted_command_hash,
             admission.command_envelope_hash as admitted_envelope_hash,
             admission.room_epoch as admitted_room_epoch,
             admission.room_fencing_token as admitted_room_fencing_token
        from target_e2e_activation activation
        join target_e2e_case_reservation reservation
          on reservation.activation_id = activation.activation_id
        join target_e2e_room_epoch_binding room
          on room.activation_id = activation.activation_id
         and room.activation_manifest_hash = activation.manifest_hash
         and room.execution_lane = activation.execution_lane
         and room.isolated_domain_db_binding_hash = activation.isolated_domain_db_binding_hash
         and room.tenant_surrogate = reservation.tenant_surrogate
         and room.case_id = reservation.case_id
        join target_e2e_command_admission admission
          on admission.activation_id = activation.activation_id
         and admission.activation_manifest_hash = activation.manifest_hash
         and admission.execution_lane = activation.execution_lane
         and admission.isolated_domain_db_binding_hash = activation.isolated_domain_db_binding_hash
         and admission.tenant_surrogate = room.tenant_surrogate
         and admission.case_id = room.case_id
         and admission.room_epoch = room.room_epoch
         and admission.room_fencing_token = room.room_fencing_token
        join target_e2e_intake_command_material material
          on material.admission_id = admission.admission_id
         and material.activation_id = admission.activation_id
         and material.activation_manifest_hash = admission.activation_manifest_hash
         and material.execution_lane = admission.execution_lane
         and material.isolated_domain_db_binding_hash = admission.isolated_domain_db_binding_hash
         and material.tenant_surrogate = admission.tenant_surrogate
         and material.case_id = admission.case_id
         and material.command_id = admission.command_id
         and material.command_hash = admission.command_hash
         and material.command_envelope_hash = admission.command_envelope_hash
         and material.room_epoch = admission.room_epoch
         and material.room_fencing_token = admission.room_fencing_token
       where activation.activation_id = :activationId
         and activation.execution_lane = 'TARGET_E2E_CANDIDATE'
         and activation.lifecycle_status in ('ACTIVE', 'DRAIN_ONLY')
         and room.execution_lane = 'TARGET_E2E_CANDIDATE'
         and room.room_type = 'INTAKE'
         and material.execution_lane = 'TARGET_E2E_CANDIDATE'
         and material.room_type = 'INTAKE'
         and material.tenant_surrogate = :tenantSurrogate
         and material.case_id = :caseId
         and material.room_epoch = :roomEpoch
         and material.command_id = :commandId
         and admission.admitted_at < activation.expires_at
         and (
              (activation.lifecycle_status = 'ACTIVE'
                   and clock_timestamp() < activation.expires_at)
              or activation.lifecycle_status = 'DRAIN_ONLY'
         )
      """;

  private final NamedParameterJdbcOperations jdbc;
  private final ObjectMapper objectMapper;
  private final String activationId;

  public TargetE2eIntakeExchangeAuthority(
      DataSource dataSource, ObjectMapper objectMapper, String activationId) {
    this(
        new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")),
        objectMapper,
        activationId);
  }

  TargetE2eIntakeExchangeAuthority(
      NamedParameterJdbcOperations jdbc, ObjectMapper objectMapper, String activationId) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
        .copy()
        // The persisted execution-context envelope is camelCase; nested graph contracts retain
        // their explicit snake_case @JsonNaming contracts.
        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
    if (activationId == null || !activationId.matches("p9act\\.v1\\.[0-9a-f]{32}")) {
      throw new IllegalArgumentException("target E2E activationId is invalid");
    }
    this.activationId = activationId;
  }

  @Override
  public PayloadLoadGrant requirePayloadLoad(PayloadLoadClaim claim) {
    Objects.requireNonNull(claim, "claim");
    Material material = requireMaterial(claim.request().authority());
    ObjectReference requested = claim.request().objectRef();
    RoomGraphCommand graph = material.graph();
    if (!matchesExactObject(requested, graph.eventRef())
        && !matchesExactObject(requested, graph.domainSnapshotRef())) {
      throw rejected("target Intake payload reference differs from the exact command material");
    }
    return new PayloadLoadGrant(claim.request(), requested.sha256());
  }

  @Override
  public ProposalPutGrant requireProposalPut(ProposalPutClaim claim) {
    Objects.requireNonNull(claim, "claim");
    Material material = requireMaterial(claim.request().authority());
    if (!PROPOSAL_ARTIFACT_SCHEMA.equals(claim.request().proposal().schemaVersion())) {
      throw rejected("target Intake proposal artifact schema is invalid");
    }
    JsonNode sourceEventHash = claim.canonicalProposal().path("source_event_hash");
    JsonNode profileVersions = claim.canonicalProposal().path("profile_versions");
    if (!sourceEventHash.isTextual()
        || !material.graph().eventRef().sha256().equals(sourceEventHash.textValue())
        || !profileVersions.isObject()
        || !PROPOSAL_ARTIFACT_SCHEMA.equals(
            profileVersions.path("output_schema_version").asText(null))) {
      throw rejected("target Intake proposal source event differs from the exact command material");
    }
    return new ProposalPutGrant(claim.request());
  }

  private Material requireMaterial(Authority authority) {
    List<Map<String, Object>> rows = jdbc.queryForList(AUTHORITY_SQL, parameters(authority));
    if (rows.size() != 1) {
      throw rejected("target Intake exchange authority is not current and exact");
    }
    Map<String, Object> row = rows.getFirst();
    requireExactAdmission(row);
    IntakeCommandExecutionContext context = decodeCanonicalContext(row);
    IntakeTargetAgentRunContext target = context.targetAgentRun();
    if (target == null) {
      throw rejected("target Intake execution context is absent");
    }
    RoomGraphCommand graph = target.request().command();
    requireExactContext(row, context, target, graph, authority);
    return new Material(graph);
  }

  private IntakeCommandExecutionContext decodeCanonicalContext(Map<String, Object> row) {
    String value = text(row, "context_canonical_json");
    String expectedHash = hash(row, "context_sha256");
    try {
      JsonNode document = objectMapper.readTree(value);
      if (document == null
          || !value.equals(ContractJson.canonicalString(document))
          || !expectedHash.equals(ContractJson.sha256Hex(document))) {
        throw rejected("target Intake execution context is not canonical and content-addressed");
      }
      return objectMapper.treeToValue(document, IntakeCommandExecutionContext.class);
    } catch (Rejected failure) {
      throw failure;
    } catch (Exception failure) {
      throw rejected("target Intake execution context is malformed", failure);
    }
  }

  private static void requireExactAdmission(Map<String, Object> row) {
    requireEqual(row, "activation_id", "admitted_activation_id");
    requireEqual(row, "activation_manifest_hash", "admitted_manifest_hash");
    requireEqual(row, "isolated_domain_db_binding_hash", "admitted_domain_binding_hash");
    requireEqual(row, "tenant_surrogate", "admitted_tenant_surrogate");
    requireEqual(row, "case_id", "admitted_case_id");
    requireEqual(row, "command_id", "admitted_command_id");
    requireEqual(row, "command_hash", "admitted_command_hash");
    requireEqual(row, "command_envelope_hash", "admitted_envelope_hash");
    requireEqual(row, "room_epoch", "admitted_room_epoch");
    requireEqual(row, "room_fencing_token", "admitted_room_fencing_token");
  }

  private void requireExactContext(
      Map<String, Object> row,
      IntakeCommandExecutionContext context,
      IntakeTargetAgentRunContext target,
      RoomGraphCommand graph,
      Authority authority) {
    if (!"intake-command-execution-context.v2".equals(context.schemaVersion())
        || !activationId.equals(text(row, "activation_id"))
        || !activationId.equals(target.activationId())
        || !text(row, "activation_manifest_hash").equals(target.activationManifestHash())
        || !hash(row, "command_hash").equals(target.commandHash())
        || !hash(row, "command_envelope_hash").equals(target.commandEnvelopeHash())
        || number(row, "room_fencing_token") != target.roomFencingToken()
        || target.expectedProcessRevision() != graph.processRevision()
        || !target.request().logicalRunId().equals(graph.logicalRunId())
        || !target.request().attemptId().equals(graph.attemptId())) {
      throw rejected("target Intake execution context differs from its exact admission material");
    }
    if (!text(row, "tenant_surrogate").equals(authority.tenantSurrogate())
        || !text(row, "case_id").equals(authority.caseId())
        || !text(row, "command_id").equals(authority.commandId())
        || number(row, "room_epoch") != authority.roomEpoch()
        || !graph.requestHash().equals(authority.requestHash())
        || !graph.commandId().equals(authority.commandId())
        || !graph.tenantSurrogate().equals(authority.tenantSurrogate())
        || !graph.caseId().equals(authority.caseId())
        || graph.roomEpoch() != authority.roomEpoch()
        || !graph.threadId().equals(authority.threadId())
        || !graph.logicalRunId().equals(authority.logicalRunId())
        || !graph.attemptId().equals(authority.attemptId())
        || !TARGET_GRAPH_KEY.equals(graph.graphKey())
        || !graph.graphKey().equals(authority.graphKey())
        || !graph.graphVersion().equals(authority.graphVersion())
        || !graph.checkpointSchemaVersion().equals(authority.checkpointSchemaVersion())
        || !TARGET_REGISTRY_OUTPUT_SCHEMA.equals(
            graph.invocationContext().outputSchemaVersion())
        || graph.processRevision() != authority.processRevision()
        || !graph.stageCode().equals(authority.stageCode())
        || graph.stageSequence() != authority.stageSequence()
        || !context.agentSessionId().equals(authority.agentSessionId())
        || !graph.actorScope().actorId().equals(authority.actorId())
        || graph.actorScope().actorRole() != authority.actorRole()
        || graph.actorScope().audience() != authority.audience()
        || !graph.actorScope().capabilities().equals(authority.actorCapabilities())
        || !ContractJson.sha256Hex(objectMapper.valueToTree(graph.actorScope()))
            .equals(authority.actorScopeHash())
        || graph.eventRef() == null) {
      throw rejected("target Intake exchange authority differs from the exact command material");
    }
  }

  private static void requireExactObject(
      ObjectReference actual, RoomGraphCommand.SnapshotRef expected) {
    if (expected == null
        || !expected.artifactId().equals(actual.artifactId())
        || !expected.schemaVersion().equals(actual.schemaVersion())
        || !expected.uri().equals(actual.uri())
        || !expected.sha256().equals(actual.sha256())
        || expected.sizeBytes() != actual.sizeBytes()) {
      throw rejected("target Intake payload reference differs from the exact command material");
    }
  }

  private static boolean matchesExactObject(
      ObjectReference actual, RoomGraphCommand.SnapshotRef expected) {
    return expected != null
        && expected.artifactId().equals(actual.artifactId())
        && expected.schemaVersion().equals(actual.schemaVersion())
        && expected.uri().equals(actual.uri())
        && expected.sha256().equals(actual.sha256())
        && expected.sizeBytes() == actual.sizeBytes();
  }

  private MapSqlParameterSource parameters(Authority authority) {
    return new MapSqlParameterSource()
        .addValue("activationId", activationId)
        .addValue("tenantSurrogate", authority.tenantSurrogate())
        .addValue("caseId", authority.caseId())
        .addValue("roomEpoch", authority.roomEpoch())
        .addValue("commandId", authority.commandId());
  }

  private static void requireEqual(Map<String, Object> row, String left, String right) {
    Object actual = row.get(left);
    if (actual == null || !actual.equals(row.get(right))) {
      throw rejected("target Intake material differs from its exact command admission");
    }
  }

  private static String text(Map<String, Object> row, String field) {
    Object value = row.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw rejected("target Intake exchange authority row is malformed");
    }
    return text;
  }

  private static String hash(Map<String, Object> row, String field) {
    String value = text(row, field);
    if (!value.matches("[0-9a-f]{64}")) {
      throw rejected("target Intake exchange hash is malformed");
    }
    return value;
  }

  private static long number(Map<String, Object> row, String field) {
    Object value = row.get(field);
    if (!(value instanceof Number number)) {
      throw rejected("target Intake exchange authority row is malformed");
    }
    return number.longValue();
  }

  private static Rejected rejected(String message) {
    return new Rejected(message);
  }

  private static Rejected rejected(String message, Throwable cause) {
    return new Rejected(message, cause);
  }

  private record Material(RoomGraphCommand graph) {}
}
