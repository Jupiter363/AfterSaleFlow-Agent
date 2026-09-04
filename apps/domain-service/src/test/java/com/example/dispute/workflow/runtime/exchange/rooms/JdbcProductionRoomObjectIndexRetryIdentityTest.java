package com.example.dispute.workflow.runtime.exchange.rooms;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.Authority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

class JdbcProductionRoomObjectIndexRetryIdentityTest {

  private static final String ACTIVATION_ID = "p9act.v1." + "1".repeat(32);
  private static final String HASH = "a".repeat(64);

  @Test
  void proposalObjectIdentityIsDeterministicButAttemptScoped() {
    RoomGraphCommand first = command("hearing-command-1", "hearing-attempt-1");
    RoomGraphCommand second = command("hearing-command-2", "hearing-attempt-2");
    var proposal = new ProductionRoomObjectIndex.ProposalIdentity(
        "proposal-hearing-1", "hearing_judge_v1.v1", "b".repeat(64), 128,
        "hearing-checkpoint", "checkpoint-1", 1);

    String firstRef = JdbcProductionRoomObjectIndex.proposalObjectRef(
        authority(first), first, proposal);
    String replayRef = JdbcProductionRoomObjectIndex.proposalObjectRef(
        authority(first), first, proposal);
    String retryRef = JdbcProductionRoomObjectIndex.proposalObjectRef(
        authority(second), second, proposal);

    assertThat(replayRef).isEqualTo(firstRef);
    assertThat(retryRef).isNotEqualTo(firstRef);
    assertThat(firstRef).matches("urn:production-runtime:proposal:hearing:[0-9a-f]{64}");
    assertThat(retryRef).matches("urn:production-runtime:proposal:hearing:[0-9a-f]{64}");
  }

  @Test
  void retryBindingMigrationKeepsProposalFirstWinsInsideTheExactAttemptAuthority()
      throws Exception {
    String sql = Files.readString(Path.of(
        "src", "main", "resources", "db", "migration",
        "V062__production_runtime_room_object_retry_binding.sql"));

    assertThat(sql)
        .contains("artifact_id varchar(128) not null")
        .contains("schema_version varchar(128) not null")
        .contains("uq_production_runtime_room_object_binding_proposal_identity")
        .contains("room_fencing_token, command_id, logical_run_id, attempt_id,")
        .contains("where object_kind = 'PROPOSAL'");
  }

  @Test
  void bindingReplayRejectsTheSameCommandAndObjectAcrossADifferentAttempt() {
    MapSqlParameterSource expected = new MapSqlParameterSource()
        .addValue("kind", ProductionRoomObjectIndex.Kind.COMMAND_INPUT.name())
        .addValue("artifactId", "hearing-dossier-1")
        .addValue("schemaVersion", "hearing-dossier.v1")
        .addValue("run", "hearing-logical-run-1")
        .addValue("attempt", "hearing-attempt-2")
        .addValue("checkpointNs", null)
        .addValue("checkpointId", null)
        .addValue("revision", null);
    Map<String, Object> persisted = new HashMap<>();
    persisted.put("object_kind", ProductionRoomObjectIndex.Kind.COMMAND_INPUT.name());
    persisted.put("artifact_id", "hearing-dossier-1");
    persisted.put("schema_version", "hearing-dossier.v1");
    persisted.put("logical_run_id", "hearing-logical-run-1");
    persisted.put("attempt_id", "hearing-attempt-1");
    persisted.put("checkpoint_ns", null);
    persisted.put("checkpoint_id", null);
    persisted.put("cognitive_revision", null);

    assertThat(JdbcProductionRoomObjectIndex.sameBinding(persisted, expected)).isFalse();
    persisted.put("attempt_id", "hearing-attempt-2");
    assertThat(JdbcProductionRoomObjectIndex.sameBinding(persisted, expected)).isTrue();
    persisted.put("logical_run_id", "hearing-logical-run-other");
    assertThat(JdbcProductionRoomObjectIndex.sameBinding(persisted, expected)).isFalse();
  }

  private static Authority authority(RoomGraphCommand command) {
    return new Authority(
        "production-runtime-room-exchange-authority.v1",
        ACTIVATION_ID,
        17,
        "c".repeat(64),
        "d".repeat(64),
        command.tenantSurrogate(),
        command.caseId(),
        command.roomType().name(),
        command.roomEpoch(),
        command.threadId(),
        command.commandId(),
        command.logicalRunId(),
        command.attemptId(),
        command.requestHash(),
        command.graphKey(),
        command.graphVersion(),
        command.checkpointSchemaVersion(),
        command.processRevision(),
        command.stageCode(),
        command.stageSequence());
  }

  private static RoomGraphCommand command(String commandId, String attemptId) {
    return new RoomGraphCommand(
        "room-graph-command.v1",
        commandId,
        "hearing-logical-run-1",
        attemptId,
        "tenant-hearing",
        "CASE_HEARING_1",
        RoomType.HEARING,
        3,
        "all-rooms.production-runtime.v2",
        "production-runtime-graph.2026-08-18.1",
        "production-runtime-checkpoint.v2",
        "hearing-thread-1",
        new RoomGraphCommand.ActorScope(
            "judge-1", ActorRole.SYSTEM, Audience.SYSTEM, List.of("case:hearing:adjudicate")),
        9,
        "JUDGE_V1",
        5,
        new RoomGraphCommand.SnapshotRef(
            "hearing-dossier-1", "hearing-dossier.v1",
            "urn:production-runtime:object:hearing-dossier-1", "e".repeat(64), 256),
        new RoomGraphCommand.SnapshotRef(
            "hearing-event-1", "hearing-event.v1",
            "urn:production-runtime:object:hearing-event-1", "f".repeat(64), 128),
        new RoomGraphCommand.InvocationContext(
            "all-rooms-agent.production-runtime.v1",
            "all-rooms-prompt.production-runtime.v2",
            "production-runtime.contract-blocked",
            "production-runtime-room-proposal-source.v2",
            "all-rooms-policy.production-runtime.v1",
            "all-rooms-guardrail.production-runtime.v1",
            List.of(),
            "target-envelope-key",
            "target-envelope-nonce:" + attemptId),
        new RoomGraphCommand.RetryBudget(2, 3, 1),
        Instant.parse("2030-01-01T00:00:00Z"),
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        HASH);
  }
}
