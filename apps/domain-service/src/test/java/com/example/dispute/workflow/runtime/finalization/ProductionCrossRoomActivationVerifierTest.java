package com.example.dispute.workflow.runtime.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.AcceptedCommandProof;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.AuthorizationDecision;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.AuthorizationRequest;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.Decision;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.Lifecycle;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.RuntimeAttestation;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductionCrossRoomActivationVerifierTest {

  private static final String ACTIVATION_ID = "p9act.v1." + "1".repeat(32);
  private static final String MANIFEST_HASH = "a".repeat(64);
  private static final String DB_HASH = "b".repeat(64);
  private static final String COMMAND_HASH = "c".repeat(64);
  private static final String ENVELOPE_HASH = "d".repeat(64);
  private static final Instant ISSUED_AT = Instant.parse("2026-07-29T00:00:00Z");
  private static final Instant EXPIRES_AT = Instant.parse("2026-07-29T01:00:00Z");

  @Test
  void acceptsOnlyAnExactCurrentCrossRoomGrant() {
    AuthorizationRequest request = request();
    ActivationGrant grant = grant(
        Lifecycle.ACTIVE, null, ProductionExecutionLaneVerifier.GRAPH_VERSION,
        ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION);

    assertThat(ProductionCrossRoomActivationVerifier.requireAuthorized(
        decision(grant), request, ACTIVATION_ID, MANIFEST_HASH, DB_HASH))
        .isEqualTo(grant);
  }

  @Test
  void rejectsGraphVersionCheckpointAndTerminalLifecycleDrift() {
    AuthorizationRequest request = request();
    assertThatThrownBy(() -> verify(request, grant(
        Lifecycle.ACTIVE, null, "production-runtime-graph.drifted",
        ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION)))
        .isInstanceOf(ProductionFinalizationRejectedException.class)
        .hasMessageContaining("differs from durable finalization authority");
    assertThatThrownBy(() -> verify(request, grant(
        Lifecycle.ACTIVE, null, ProductionExecutionLaneVerifier.GRAPH_VERSION,
        "production-runtime-checkpoint.drifted")))
        .isInstanceOf(ProductionFinalizationRejectedException.class)
        .hasMessageContaining("differs from durable finalization authority");
    assertThatThrownBy(() -> verify(request, grant(
        Lifecycle.DRAINED, null, ProductionExecutionLaneVerifier.GRAPH_VERSION,
        ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION)))
        .isInstanceOf(ProductionFinalizationRejectedException.class)
        .hasMessageContaining("cannot finalize");
  }

  @Test
  void drainOnlyRequiresTheExactAdmissionWithinTheActivationWindow() {
    AuthorizationRequest request = request();
    AcceptedCommandProof exact = new AcceptedCommandProof(
        request.commandId(), request.commandHash(), request.commandEnvelopeHash(),
        request.roomEpoch(), request.roomFencingToken(), ISSUED_AT.plusSeconds(30));
    ActivationGrant draining = grant(
        Lifecycle.DRAIN_ONLY, exact, ProductionExecutionLaneVerifier.GRAPH_VERSION,
        ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION);
    assertThat(ProductionCrossRoomActivationVerifier.requireAuthorized(
        decision(draining), request, ACTIVATION_ID, MANIFEST_HASH, DB_HASH))
        .isEqualTo(draining);

    AcceptedCommandProof wrong = new AcceptedCommandProof(
        request.commandId(), "e".repeat(64), request.commandEnvelopeHash(),
        request.roomEpoch(), request.roomFencingToken(), ISSUED_AT.plusSeconds(30));
    assertThatThrownBy(() -> verify(request, grant(
        Lifecycle.DRAIN_ONLY, wrong, ProductionExecutionLaneVerifier.GRAPH_VERSION,
        ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION)))
        .isInstanceOf(ProductionFinalizationRejectedException.class)
        .hasMessageContaining("pre-cutoff accepted work");
  }

  @Test
  void deniedOrMissingDecisionFailsClosed() {
    assertThatThrownBy(() -> ProductionCrossRoomActivationVerifier.requireAuthorized(
        AuthorizationDecision.denied(Decision.REVOKED), request(), ACTIVATION_ID,
        MANIFEST_HASH, DB_HASH))
        .isInstanceOf(ProductionFinalizationRejectedException.class)
        .hasMessageContaining("no current allowed activation");
  }

  private static void verify(AuthorizationRequest request, ActivationGrant grant) {
    ProductionCrossRoomActivationVerifier.requireAuthorized(
        decision(grant), request, ACTIVATION_ID, MANIFEST_HASH, DB_HASH);
  }

  private static AuthorizationDecision decision(ActivationGrant grant) {
    return AuthorizationDecision.allowed(grant, new RuntimeAttestation(
        grant.activationId(), grant.activationId(), grant.executionLane(), grant.tenantSurrogate(),
        grant.allowedRoomTypes(), grant.expectedAgentBuildId(), grant.graphKey(),
        grant.graphVersion(), grant.checkpointSchemaVersion(), grant.activationManifestHash(),
        grant.isolatedDomainDbBindingHash(), grant.lifecycle(), grant.issuedAt(),
        grant.expiresAt(), grant.revokedAt()));
  }

  private static AuthorizationRequest request() {
    return new AuthorizationRequest(
        "tenant-target", "CASE_TARGET", "ROOM_EVIDENCE", RoomType.EVIDENCE,
        "RUN_TARGET", "agent-run-v2:RUN_TARGET", "workflow-run-1", "build-target",
        "COMMAND_TARGET", COMMAND_HASH, ENVELOPE_HASH, 4, 9);
  }

  private static ActivationGrant grant(
      Lifecycle lifecycle,
      AcceptedCommandProof proof,
      String graphVersion,
      String checkpointSchemaVersion) {
    return new ActivationGrant(
        ACTIVATION_ID,
        ProductionExecutionLaneVerifier.EXECUTION_LANE,
        "tenant-target",
        Set.of("CASE_TARGET"),
        Set.of(RoomType.EVIDENCE),
        "build-target",
        ProductionExecutionLaneVerifier.GRAPH_KEY,
        graphVersion,
        checkpointSchemaVersion,
        MANIFEST_HASH,
        DB_HASH,
        lifecycle,
        proof,
        ISSUED_AT,
        EXPIRES_AT,
        null);
  }
}
