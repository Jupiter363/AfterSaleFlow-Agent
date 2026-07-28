package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetHearingAgentRunTriggerTest {
  @Test void acceptsExactHearingCommandBinding() {
    assertDoesNotThrow(() -> new TargetHearingAgentRunTrigger(TargetHearingAgentRunTrigger.SCHEMA_VERSION,
        command("cmd-1"), request("cmd-1"), 4, hash("f")));
  }
  @Test void rejectsCrossCommandBinding() {
    assertThatThrownBy(() -> new TargetHearingAgentRunTrigger(TargetHearingAgentRunTrigger.SCHEMA_VERSION,
        command("cmd-1"), request("cmd-2"), 4, hash("f")))
        .isInstanceOf(IllegalArgumentException.class);
  }
  private static CaseCommandRef command(String id) {
    return new CaseCommandRef("case-command-ref.v1", id, "tenant", "case", 1,
        CommandType.HEARING_STATEMENT, RoomType.HEARING, 2,
        new ActorRef("user", ActorRole.USER, List.of("hearing")),
        new PayloadRef("event.v1", "minio://event", hash("a"), 1), 4, Instant.EPOCH,
        Instant.EPOCH.plusSeconds(60), "trace", hash("b"));
  }
  private static ExecuteAgentRunRequest request(String id) {
    RoomGraphCommand graph = new RoomGraphCommand("room-graph-command.v1", id, "run", "attempt",
        "tenant", "case", RoomType.HEARING, 2, "all-rooms", "v1", "checkpoint.v1", "thread",
        new RoomGraphCommand.ActorScope("user", ActorRole.USER, Audience.USER, List.of("hearing")), 4,
        "PARTY_ANSWERS_OPEN", 5, new RoomGraphCommand.SnapshotRef("snapshot", "snapshot.v1", "minio://snapshot", hash("c"), 1),
        new RoomGraphCommand.SnapshotRef("event", "event.v1", "minio://event", hash("a"), 1),
        new RoomGraphCommand.InvocationContext("agent", "prompt", "model", "output", "policy", "guardrail", List.of(), "key", "nonce"),
        new RoomGraphCommand.RetryBudget(1, 1, 0), Instant.EPOCH.plusSeconds(60), "trace", hash("b"));
    return new ExecuteAgentRunRequest(ExecuteAgentRunRequest.SCHEMA_VERSION, "run", 1, "agent-stream.v2", hash("d"), null, false, 0, graph);
  }
  private static String hash(String value) { return value.repeat(64); }
}
