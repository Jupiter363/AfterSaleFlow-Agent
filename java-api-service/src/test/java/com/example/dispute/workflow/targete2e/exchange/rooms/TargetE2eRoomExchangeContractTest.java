package com.example.dispute.workflow.targete2e.exchange.rooms;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.ObjectRef;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.LoadRequest;
import org.junit.jupiter.api.Test;

class TargetE2eRoomExchangeContractTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void acceptsOnlyFrozenCandidateAuthorityAndOpaqueObjectUri() {
    assertDoesNotThrow(() -> new LoadRequest("target-e2e-room-object-load-request.v1", authority(),
        new ObjectRef("snapshot.1", "target-e2e-hearing-invocation.v1", "urn:target-e2e:object:snapshot.1:" + HASH, HASH, 12)));
  }

  @Test
  void rejectsIntakeAndStorageUriShapes() {
    assertThrows(IllegalArgumentException.class, () -> new Authority(
        "target-e2e-room-exchange-authority.v1", "p9act.v1." + "b".repeat(32), 1, HASH, HASH,
        "tenant", "case", "INTAKE", 0, "thread", "command", "run", "attempt", HASH,
        "all-rooms.target-e2e.v1", "target-e2e-graph.2026-07-27.1", "target-e2e-checkpoint.v1", 0, "stage", 0));
    assertThrows(IllegalArgumentException.class, () -> new ObjectRef("snapshot", "fixture.v1", "minio://target-e2e-intake-activation/x", HASH, 1));
  }

  private static Authority authority() {
    return new Authority("target-e2e-room-exchange-authority.v1", "p9act.v1." + "b".repeat(32), 1,
        HASH, HASH, "tenant", "case", "HEARING", 0, "thread", "command", "run", "attempt", HASH,
        "all-rooms.target-e2e.v1", "target-e2e-graph.2026-07-27.1", "target-e2e-checkpoint.v1", 0, "stage", 0);
  }
}
