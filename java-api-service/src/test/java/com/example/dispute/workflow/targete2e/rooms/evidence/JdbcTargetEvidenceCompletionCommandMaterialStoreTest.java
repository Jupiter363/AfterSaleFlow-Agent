package com.example.dispute.workflow.targete2e.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmissionSnapshot;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCompletionCommandMaterialStore.Provenance;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCompletionCommandMaterialStore.Route;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JdbcTargetEvidenceCompletionCommandMaterialStoreTest {

  @Test
  void canonicalMaterialBindsTheDedicatedAdmissionWithoutCreatingAnAgentRun() {
    TargetEvidenceCompletionCommandMaterial material = material();
    CommandAdmission admission = new CommandAdmission(
        material.activationId(), material.activationManifestHash(),
        material.isolatedDomainDbBindingHash(), material.tenantSurrogate(), material.caseId(),
        material.commandId(), material.commandHash(), material.commandEnvelopeHash(),
        material.roomEpoch(), material.roomFencingToken());

    JdbcTargetEvidenceCompletionCommandMaterialStore.requireMaterial(admission, material);

    assertThat(material.commandType().name()).isEqualTo("PARTY_EVIDENCE_COMPLETE");
    assertThat(material.getClass().getRecordComponents())
        .noneMatch(component -> component.getName().equals("request"));
  }

  @Test
  void admissionHashDriftFailsClosed() {
    TargetEvidenceCompletionCommandMaterial material = material();
    CommandAdmission drifted = new CommandAdmission(
        material.activationId(), material.activationManifestHash(),
        material.isolatedDomainDbBindingHash(), material.tenantSurrogate(), material.caseId(),
        material.commandId(), "f".repeat(64), material.commandEnvelopeHash(),
        material.roomEpoch(), material.roomFencingToken());

    assertThatThrownBy(
            () -> JdbcTargetEvidenceCompletionCommandMaterialStore.requireMaterial(drifted, material))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("exactly bind");
  }

  @Test
  void provenanceSqlRequiresExactEpochFenceActorRequestAndPayloadBindings() {
    String sql = JdbcTargetEvidenceCompletionCommandMaterialStore.PROVENANCE_SQL;

    assertThat(sql).contains(
        "m.room_epoch = ?",
        "m.room_fencing_token = ?",
        "m.case_command_request_hash = c.request_hash",
        "m.actor_id = c.actor_id",
        "m.actor_role = c.actor_role",
        "m.actor_scopes_json = c.actor_scopes_json",
        "m.payload_schema_version = c.payload_schema_version",
        "m.payload_uri = c.payload_uri",
        "m.payload_sha256 = c.payload_sha256",
        "m.payload_size_bytes = c.payload_size_bytes",
        "a.command_hash = m.command_hash",
        "a.command_envelope_hash = m.command_envelope_hash");
  }

  @Test
  void pendingAndAcceptedCommandsWithoutLedgerCompletionAreInFlight() {
    TargetEvidenceCompletionCommandMaterial material = material();
    CommandAdmission admission = admission(material);
    CommandAdmissionSnapshot snapshot = snapshot(material, false, null, null);

    assertThat(JdbcTargetEvidenceCompletionCommandMaterialStore.classifyProvenance(
            route(material), admission, material, "PENDING_ORCHESTRATION",
            null, null, snapshot, snapshot.admissionId()))
        .isEqualTo(Provenance.IN_FLIGHT);
    assertThat(JdbcTargetEvidenceCompletionCommandMaterialStore.classifyProvenance(
            route(material), admission, material, "ORCHESTRATION_ACCEPTED",
            null, null, snapshot, snapshot.admissionId()))
        .isEqualTo(Provenance.IN_FLIGHT);
  }

  @Test
  void appliedCommandRequiresTheExactResultAndLedgerCompletion() {
    TargetEvidenceCompletionCommandMaterial material = material();
    CommandAdmission admission = admission(material);
    String completionHash = "9".repeat(64);
    CommandAdmissionSnapshot snapshot = snapshot(
        material, true, completionHash, Instant.parse("2026-07-30T01:02:00Z"));

    assertThat(JdbcTargetEvidenceCompletionCommandMaterialStore.classifyProvenance(
            route(material), admission, material, "APPLIED",
            "urn:target-evidence-completion:one", completionHash,
            snapshot, snapshot.admissionId()))
        .isEqualTo(Provenance.APPLIED_EXACT);
  }

  @Test
  void appliedCommandWithoutActivationReceiptOrWithResultDriftFailsClosed() {
    TargetEvidenceCompletionCommandMaterial material = material();
    CommandAdmission admission = admission(material);
    String completionHash = "9".repeat(64);
    CommandAdmissionSnapshot snapshot = snapshot(
        material, true, completionHash, Instant.parse("2026-07-30T01:02:00Z"));

    assertThatThrownBy(() -> JdbcTargetEvidenceCompletionCommandMaterialStore.classifyProvenance(
            route(material), admission, material, "APPLIED",
            "urn:target-evidence-completion:one", completionHash,
            null, snapshot.admissionId()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("admission is absent");
    assertThatThrownBy(() -> JdbcTargetEvidenceCompletionCommandMaterialStore.classifyProvenance(
            route(material), admission, material, "APPLIED",
            "urn:target-evidence-completion:other", completionHash,
            snapshot, snapshot.admissionId()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("not exact");
    assertThatThrownBy(() -> JdbcTargetEvidenceCompletionCommandMaterialStore.classifyProvenance(
            route(material), admission, material, "APPLIED",
            "urn:target-evidence-completion:one", "8".repeat(64),
            snapshot, snapshot.admissionId()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("not exact");
  }

  @Test
  void provenanceRouteRejectsMaterialEpochOrFenceDrift() {
    TargetEvidenceCompletionCommandMaterial material = material();
    TargetEvidenceCompletionCommandMaterial drifted = TargetEvidenceCompletionCommandMaterial.create(
        material.activationId(), material.activationManifestHash(),
        material.isolatedDomainDbBindingHash(), material.tenantSurrogate(), material.caseId(),
        material.commandId(), 5, 10, material.expectedProcessRevision(),
        material.expectedRoomRevision(), material.actorRef(), material.payloadRef(),
        material.deadlineAt(), material.traceId(), material.caseCommandRequestHash());
    CommandAdmission admission = admission(drifted);
    CommandAdmissionSnapshot snapshot = snapshot(drifted, false, null, null);

    assertThatThrownBy(() -> JdbcTargetEvidenceCompletionCommandMaterialStore.classifyProvenance(
            route(material), admission, drifted, "ORCHESTRATION_ACCEPTED",
            null, null, snapshot, snapshot.admissionId()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("replay route");
  }

  private static CommandAdmission admission(TargetEvidenceCompletionCommandMaterial material) {
    return new CommandAdmission(
        material.activationId(), material.activationManifestHash(),
        material.isolatedDomainDbBindingHash(), material.tenantSurrogate(), material.caseId(),
        material.commandId(), material.commandHash(), material.commandEnvelopeHash(),
        material.roomEpoch(), material.roomFencingToken());
  }

  private static Route route(TargetEvidenceCompletionCommandMaterial material) {
    return new Route(
        material.tenantSurrogate(), material.caseId(), material.commandId(),
        material.roomEpoch(), material.roomFencingToken(), "one");
  }

  private static CommandAdmissionSnapshot snapshot(
      TargetEvidenceCompletionCommandMaterial material,
      boolean completed,
      String completionHash,
      Instant completedAt) {
    return new CommandAdmissionSnapshot(
        "admission-one", material.activationId(), material.activationManifestHash(),
        material.isolatedDomainDbBindingHash(), material.tenantSurrogate(), material.caseId(),
        material.commandId(), material.commandHash(), material.commandEnvelopeHash(),
        material.roomEpoch(), material.roomFencingToken(),
        Instant.parse("2026-07-30T01:00:30Z"), completed, completionHash, completedAt);
  }

  private static TargetEvidenceCompletionCommandMaterial material() {
    return TargetEvidenceCompletionCommandMaterial.create(
        "p9act.v1." + "a".repeat(32), "b".repeat(64), "c".repeat(64),
        "tenant-e2e", "CASE_E2E", "evidence-complete:one", 4, 9, 6, 3,
        new ActorRef("USER_E2E", ActorRole.USER,
            List.of("case:CASE_E2E:command:PARTY_EVIDENCE_COMPLETE")),
        new PayloadRef("target-e2e-evidence-completion.v1",
            "urn:target-e2e:timeline-event:event-one", "d".repeat(64), 128),
        Instant.parse("2026-07-30T01:00:00Z"), "e".repeat(32), "f".repeat(64));
  }
}
