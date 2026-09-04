package com.example.dispute.workflow.runtime.artifact.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.PayloadLoadClaim;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.Authority;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ObjectReference;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.PayloadLoadRequest;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class ProductionIntakeExchangeAuthorityTest {

  private static final String ACTIVATION_ID = "p9act.v1." + "a".repeat(32);

  @Test
  void decodesCamelCaseCanonicalContextWhenInjectedMapperUsesSnakeCase(@TempDir Path classes)
      throws Exception {
    IntakeCommandExecutionContext context = context();
    ObjectMapper persistedMapper = new ObjectMapper().findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
    JsonNode document = persistedMapper.valueToTree(context);
    String canonical = ContractJson.canonicalString(document);
    assertThat(document.has("schemaVersion")).isTrue();
    assertThat(document.has("targetAgentRun")).isTrue();

    NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    when(jdbc.queryForList(any(String.class), any(SqlParameterSource.class)))
        .thenReturn(List.of(row(canonical, document)));

    IntakeExchangeAuthorityValidationPort authority = authority(classes, jdbc);
    PayloadLoadRequest request = new PayloadLoadRequest(
        "intake-payload-load-request.v1", authorityClaim(context), objectReference(context));

    assertThat(authority.requirePayloadLoad(new PayloadLoadClaim(request)).objectVersion())
        .isEqualTo(context.targetAgentRun().request().command().eventRef().sha256());
  }

  @Test
  void rejectsMalformedCanonicalContextEvenWhenInjectedMapperUsesSnakeCase(@TempDir Path classes)
      throws Exception {
    IntakeCommandExecutionContext context = context();
    ObjectMapper persistedMapper = new ObjectMapper().findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
    JsonNode document = persistedMapper.valueToTree(context);
    String canonical = ContractJson.canonicalString(document);
    Map<String, Object> row = row(canonical, document);
    row.put("context_canonical_json", canonical.replace("schemaVersion", "schema_version"));

    NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    when(jdbc.queryForList(any(String.class), any(SqlParameterSource.class))).thenReturn(List.of(row));
    IntakeExchangeAuthorityValidationPort authority = authority(classes, jdbc);
    PayloadLoadRequest request = new PayloadLoadRequest(
        "intake-payload-load-request.v1", authorityClaim(context), objectReference(context));

    assertThatThrownBy(() -> authority.requirePayloadLoad(new PayloadLoadClaim(request)))
        .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class);
  }

  @Test
  void authorizesOnlyTheEventOrSnapshotBoundToTheAdmittedCommand(@TempDir Path classes)
      throws Exception {
    IntakeCommandExecutionContext context = context();
    ObjectMapper persistedMapper = new ObjectMapper().findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
    JsonNode document = persistedMapper.valueToTree(context);
    NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    when(jdbc.queryForList(any(String.class), any(SqlParameterSource.class)))
        .thenReturn(List.of(row(ContractJson.canonicalString(document), document)));
    IntakeExchangeAuthorityValidationPort authority = authority(classes, jdbc);

    PayloadLoadRequest snapshotRequest = new PayloadLoadRequest(
        "intake-payload-load-request.v1", authorityClaim(context), snapshotReference(context));
    assertThat(authority.requirePayloadLoad(new PayloadLoadClaim(snapshotRequest)).objectVersion())
        .isEqualTo(context.targetAgentRun().request().command().domainSnapshotRef().sha256());

    ObjectReference unrelated = new ObjectReference("other-p9", "intake-domain-snapshot.v2",
        "minio://production-runtime/snapshots/other", hash('e'), 1);
    PayloadLoadRequest unrelatedRequest = new PayloadLoadRequest(
        "intake-payload-load-request.v1", authorityClaim(context), unrelated);
    assertThatThrownBy(() -> authority.requirePayloadLoad(new PayloadLoadClaim(unrelatedRequest)))
        .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class);
  }

  private static IntakeExchangeAuthorityValidationPort authority(
      Path classes, NamedParameterJdbcOperations jdbc) throws Exception {
    compileAuthority(classes);
    URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
        ProductionIntakeExchangeAuthorityTest.class.getClassLoader());
    Class<?> type = loader.loadClass(
        "com.example.dispute.workflow.runtime.artifact.exchange.ProductionIntakeExchangeAuthority");
    var constructor = type.getDeclaredConstructor(
        NamedParameterJdbcOperations.class, ObjectMapper.class, String.class);
    constructor.setAccessible(true);
    return (IntakeExchangeAuthorityValidationPort) constructor.newInstance(
        jdbc,
        new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
        ACTIVATION_ID);
  }

  private static void compileAuthority(Path classes) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("a JDK compiler is required for production-runtime source tests").isNotNull();
    Path source = Path.of("src", "production-runtime", "java", "com", "example", "dispute", "workflow",
        "runtime", "artifact", "exchange", "ProductionIntakeExchangeAuthority.java");
    int status = compiler.run(null, null, null,
        "-classpath", System.getProperty("java.class.path"),
        "-d", classes.toString(), source.toString());
    assertThat(status).isZero();
  }

  private static Map<String, Object> row(String canonical, JsonNode document) {
    IntakeCommandExecutionContext context = context();
    RoomGraphCommand graph = context.targetAgentRun().request().command();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("activation_id", ACTIVATION_ID);
    row.put("activation_manifest_hash", hash('3'));
    row.put("isolated_domain_db_binding_hash", hash('4'));
    row.put("tenant_surrogate", graph.tenantSurrogate());
    row.put("case_id", graph.caseId());
    row.put("command_id", graph.commandId());
    row.put("command_hash", hash('b'));
    row.put("command_envelope_hash", hash('5'));
    row.put("room_epoch", graph.roomEpoch());
    row.put("room_fencing_token", 1L);
    row.put("context_canonical_json", canonical);
    row.put("context_sha256", ContractJson.sha256Hex(document));
    row.put("admitted_activation_id", ACTIVATION_ID);
    row.put("admitted_manifest_hash", hash('3'));
    row.put("admitted_domain_binding_hash", hash('4'));
    row.put("admitted_tenant_surrogate", graph.tenantSurrogate());
    row.put("admitted_case_id", graph.caseId());
    row.put("admitted_command_id", graph.commandId());
    row.put("admitted_command_hash", hash('b'));
    row.put("admitted_envelope_hash", hash('5'));
    row.put("admitted_room_epoch", graph.roomEpoch());
    row.put("admitted_room_fencing_token", 1L);
    return row;
  }

  private static Authority authorityClaim(IntakeCommandExecutionContext context) {
    RoomGraphCommand graph = context.targetAgentRun().request().command();
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
    return new Authority("intake-exchange-authority.v1", graph.tenantSurrogate(), graph.caseId(),
        "INTAKE", graph.roomEpoch(), graph.threadId(), graph.actorScope().actorId(),
        graph.actorScope().actorRole(), graph.actorScope().audience(), graph.actorScope().capabilities(),
        ContractJson.sha256Hex(mapper.valueToTree(graph.actorScope())), context.agentSessionId(),
        graph.commandId(), graph.logicalRunId(), graph.attemptId(), graph.requestHash(), graph.graphKey(),
        graph.graphVersion(), graph.checkpointSchemaVersion(), graph.processRevision(), graph.stageCode(),
        graph.stageSequence());
  }

  private static ObjectReference objectReference(IntakeCommandExecutionContext context) {
    RoomGraphCommand.SnapshotRef event = context.targetAgentRun().request().command().eventRef();
    return new ObjectReference(event.artifactId(), event.schemaVersion(), event.uri(), event.sha256(),
        event.sizeBytes());
  }

  private static ObjectReference snapshotReference(IntakeCommandExecutionContext context) {
    RoomGraphCommand.SnapshotRef snapshot = context.targetAgentRun().request().command().domainSnapshotRef();
    return new ObjectReference(snapshot.artifactId(), snapshot.schemaVersion(), snapshot.uri(), snapshot.sha256(),
        snapshot.sizeBytes());
  }

  private static IntakeCommandExecutionContext context() {
    RoomGraphCommand command = new RoomGraphCommand("room-graph-command.v1", "command-p9-001",
        "logical-run-p9-001", "attempt-p9-001", "tenant-p9", "CASE_P9_001", RoomType.INTAKE, 0,
        "all-rooms.production-runtime.v1", "production-runtime-graph.2026-07-27.1", "production-runtime-checkpoint.v1",
        "grt.v1." + "1".repeat(32), new RoomGraphCommand.ActorScope("user-p9", ActorRole.USER,
            Audience.USER, List.of("INTAKE_MESSAGE")), 0, "INTAKE_MESSAGE", 1,
        new RoomGraphCommand.SnapshotRef("snapshot-p9", "intake-domain-snapshot.v2",
            "minio://production-runtime/snapshots/p9", hash('c'), 1),
        new RoomGraphCommand.SnapshotRef("event-p9", "intake-turn-event.v2",
            "minio://production-runtime/events/p9", hash('d'), 1),
        new RoomGraphCommand.InvocationContext("agent-profile-p9", "prompt-profile-p9", "model-profile-p9",
            "production-runtime-room-proposal-source.v1", "policy-p9", "guardrail-p9", List.of(),
            "envelope-key-p9", "envelope-nonce-p9"),
        new RoomGraphCommand.RetryBudget(2, 2, 1), Instant.parse("2026-07-28T09:00:00Z"),
        "00-" + "e".repeat(32) + "-" + "f".repeat(16) + "-01", hash('1'));
    ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(ExecuteAgentRunRequest.SCHEMA_VERSION,
        command.logicalRunId(), 1, 2, "agent-stream.v2", hash('2'), null, false, 0, command);
    IntakeTargetAgentRunContext target = new IntakeTargetAgentRunContext(
        "intake-target-agent-run-context.v1", IntakeTargetAgentRunContext.TARGET_LANE, ACTIVATION_ID,
        hash('3'), 1, 0, 0, "case-build-p9", "control-build-p9", "agent-build-p9", hash('4'),
        "graph-build-p9", hash('b'), hash('5'), request);
    return new IntakeCommandExecutionContext("intake-command-execution-context.v2",
        command.threadId(), "agent-session-p9", Instant.parse("2026-07-28T09:00:00Z").toEpochMilli(),
        new RetryBudget("intake-retry-budget.v1", 2, 2, 1), null, target);
  }

  private static String hash(char value) {
    return String.valueOf(value).repeat(64);
  }
}
