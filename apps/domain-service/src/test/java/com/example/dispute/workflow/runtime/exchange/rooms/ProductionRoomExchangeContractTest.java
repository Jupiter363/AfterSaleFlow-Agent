package com.example.dispute.workflow.runtime.exchange.rooms;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.Authority;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.ObjectRef;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.LoadRequest;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import org.junit.jupiter.api.Test;

class ProductionRoomExchangeContractTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void acceptsOnlyFrozenProductionAuthorityAndOpaqueObjectUri() {
    assertDoesNotThrow(() -> new LoadRequest("production-runtime-room-object-load-request.v1", authority(),
        new ObjectRef("snapshot.1", "production-runtime-hearing-invocation.v1", "urn:production-runtime:object:snapshot.1:" + HASH, HASH, 12)));
  }

  @Test
  void rejectsIntakeAndStorageUriShapes() {
    assertThrows(IllegalArgumentException.class, () -> new Authority(
        "production-runtime-room-exchange-authority.v1", "p9act.v1." + "b".repeat(32), 1, HASH, HASH,
        "tenant", "case", "INTAKE", 0, "thread", "command", "run", "attempt", HASH,
        "wrong-graph", TargetTypedRoomProtocol.GRAPH_VERSION,
        TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION, 0, "stage", 0));
    assertThrows(IllegalArgumentException.class, () -> new ObjectRef("snapshot", "fixture.v1", "minio://production-runtime-intake-activation/x", HASH, 1));
  }

  private static Authority authority() {
    return new Authority("production-runtime-room-exchange-authority.v1", "p9act.v1." + "b".repeat(32), 1,
        HASH, HASH, "tenant", "case", "HEARING", 0, "thread", "command", "run", "attempt", HASH,
        TargetTypedRoomProtocol.GRAPH_KEY, TargetTypedRoomProtocol.GRAPH_VERSION,
        TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION, 0, "stage", 0);
  }
}
